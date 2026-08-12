package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.abs

/**
 * Post-recognition line assembly: stitches a detector's recognized sub-line
 * (per-word) regions into line regions, so the shared [LayoutAnalyzer] receives
 * the one-region-per-line input it assumes.
 *
 * **Why post-recognition** (and not pre): PaddleOCR DBNet emits one box per word
 * on word-spaced scripts (Latin, Cyrillic, Korean);
 * [com.playtranslate.ocr.composites.DetectThenRecognize] recognizes each box 1:1
 * from its *own* true DBNet deskew quad, THEN hands the recognized regions here,
 * where we stitch the recognized *text*. Merging boxes into one fabricated crop
 * *before* recognition (the pre-recognition approach) would throw away each word's
 * deskew quad and feed a single upright AABB to the recognizer — corrupting
 * slanted/perspective text and mixing font sizes into one bad crop. Recognizing
 * first avoids that entirely: each word is read from its correct quad, and
 * assembly is a pure text/layout operation. This is the docTR `DocumentBuilder`
 * architecture (recognize words → group); the grouping thresholds mirror docTR
 * `_resolve_lines` + EasyOCR `group_text_box`.
 *
 * Scope is decided UPSTREAM (only `emitsSubLineBoxes` detectors on a
 * `wordsSeparatedByWhitespace` source language). Within that, [assembleLines]:
 *  - applies a **collective-orientation guard** — a vertical-dominant capture
 *    (genuine vertical text, e.g. rare vertical Korean) is returned untouched so
 *    its orientation survives for [LayoutAnalyzer]'s vertical path;
 *  - bands the rest into horizontal lines by vertical CENTER, with the tolerance
 *    scaled to the **current line's mean height** (local, not a frame-wide median —
 *    so a large title can't widen the band and swallow a smaller row);
 *  - admits a box to a line only if its height is within [HEIGHT_THS]× the line's
 *    mean height (EasyOCR `height_ths` — the mixed-font-size guard);
 *  - splits a band on horizontal gaps > [GAP_THS]× the line height (column breaks);
 *  - bands slanted regions per same-angle cluster in that cluster's deskewed
 *    frame ([assembleSlanted]) — the same kernel, slant rotated away first.
 *
 * Pure geometry in [assembleLineIndices]; [assembleLines] adds the text + char-box
 * stitch (carrying per-word [CharBox]es into the line with offsets rebased).
 * Pinned by `LineAssemblyTest`.
 */
object LineAssembler {

    /** Same-line vertical-center tolerance, × the line's mean height (docTR `y_med/2`; EasyOCR `ycenter_ths`). */
    private const val YCENTER_THS = 0.5
    /** Max height deviation to admit a box to a line, × the line's mean height (EasyOCR `height_ths`). */
    private const val HEIGHT_THS = 0.5
    /** Horizontal gap that splits a band into separate lines/columns, × the line's mean height. */
    private const val GAP_THS = 1.5

    /**
     * Stitch recognized word-regions into line-regions. [regions] are already
     * recognized (post-recognition); a multi-word line becomes one region whose
     * text is the member texts joined left-to-right by a single space.
     */
    fun assembleLines(regions: List<RecognizedRegion>, rtl: Boolean = false): List<RecognizedRegion> {
        if (regions.size <= 1) return regions
        // One banding path for every angle ([assembleWithAngles]): measured
        // angles cluster uniformly, each slanted cluster bands in its deskewed
        // frame, and UNMEASURED words rejoin a slanted line by position. The
        // no-measured-slant guard is an OPTIMIZATION, not a semantic fork —
        // with no slanted clusters the general path reduces to exactly the
        // plain upright body (byte-identical on every upright-only frame).
        if (regions.any { it.box.isRotated }) return assembleWithAngles(regions, rtl)
        return assembleUpright(regions, rtl)
    }

    /** The plain upright body — the collective-orientation guard plus the
     *  banding kernel over screen rects. Also the sink for [assembleWithAngles]'
     *  upright pool. */
    private fun assembleUpright(regions: List<RecognizedRegion>, rtl: Boolean): List<RecognizedRegion> {
        if (regions.size <= 1) return regions
        // Collective-orientation guard: a vertical-dominant capture is genuine
        // vertical text (no horizontal-line fragmentation to repair) — leave it
        // untouched so orientation survives for LayoutAnalyzer's vertical path.
        // (Same vertical-dominance test as PaddleOcrSession.orderForReading.)
        val verticalCount = regions.count { it.orientation == TextOrientation.VERTICAL }
        if (verticalCount * 2 > regions.size) return regions
        return assembleLineIndices(regions.map { it.box.bounds }).map { idxs ->
            // Lone box: leave it UNTOUCHED. A VERTICAL-tagged singleton is more
            // likely a genuine vertical column (tall → fails the height band against
            // the short horizontal rows, so it never merges) than a glyph artifact
            // (a tall "I"/"!" that has same-row neighbors and folds into a line). So
            // we must NOT re-tag a singleton HORIZONTAL — that would route a real
            // vertical region through the horizontal layout path. Only a box that
            // actually merges into a multi-member horizontal line becomes HORIZONTAL
            // (in mergeLine).
            if (idxs.size == 1) regions[idxs[0]]
            else mergeLine(idxs.map { regions[it] }, rtl)
        }
    }

    /**
     * The unified angle path, mirroring the grouping shell
     * ([LayoutAnalyzer]'s `groupWithAngles`) at the word level: MEASURED
     * words — 0° included — cluster uniformly; each slanted cluster bands in
     * its deskewed frame (heights there are true oriented heights, so the
     * banding thresholds keep their meaning); UNMEASURED words
     * ([OcrBox.angleUnmeasured]) are admitted to a cluster by position and
     * kept only when banding actually merges them with a measured member —
     * that is what lets the short word of a mixed-length slanted sentence
     * rejoin its line instead of splitting at a bucket boundary. Everything
     * unconfirmed (plus the measured-upright mass) runs the plain upright
     * body, in original input order. A multi-member band merges via
     * [mergeLine] with the frame (bounds = exact screen AABB of the in-frame
     * union, oriented dims = union dims, angle = θ̄ verbatim); a lone
     * measured word stays the ORIGINAL instance, slant and all.
     */
    private fun assembleWithAngles(regions: List<RecognizedRegion>, rtl: Boolean): List<RecognizedRegion> {
        val unmeasured = regions.filter { it.box.angleUnmeasured }
        val measured = regions.filter { !it.box.angleUnmeasured }
        val clusters = DeskewGeometry.clusterByAngle(
            measured.map { it.box.angleDeg },
            measured.map { it.box.orientedWidth },
            measured.map { it.box.orientedHeight },
            DeskewGeometry.DEFAULT_CLUSTER_CAP_DEG,
        )
        val claimed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        val pooled = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        val slanted = ArrayList<RecognizedRegion>(regions.size)
        for (cluster in clusters) {
            if (cluster.angleDeg == 0f) continue // the upright mass bands in the pool
            val members = cluster.memberIndices.map { measured[it] }
            val union = Rect(members[0].box.bounds)
            for (m in members.drop(1)) union.union(m.box.bounds)
            val frame = AngleFrame(cluster.angleDeg, union.centerX(), union.centerY())
            val memberBoxes = members.map { it.box }
            val admitted = unmeasured.filter {
                it !in claimed && DeskewGeometry.admitUnmeasured(it.box, frame, memberBoxes)
            }
            if (members.size == 1 && admitted.isEmpty()) {
                slanted += members[0]
                continue
            }
            val all = members + admitted
            val deskewed = all.map { DeskewGeometry.deskew(it.box, frame) }
            for (idxs in assembleLineIndices(deskewed)) {
                val band = idxs.map { all[it] }
                // Confirmation = CARRIED-SLANT evidence, mirroring the
                // grouping shell: a band with no isRotated member has no
                // slant evidence — ALL its words fall back to the pool,
                // where their true line-mates are (an absorbed measured-0
                // banding away from its unmeasured siblings fragmented lines
                // whose stitched whole survived downstream filters that its
                // pieces individually cannot — the FF-VI garble regression).
                val hasCarried = band.any { it.box.isRotated }
                when {
                    !hasCarried -> pooled.addAll(band)
                    band.size == 1 -> slanted += band[0]
                    else -> {
                        slanted += mergeLine(band, rtl, frame)
                        for (b in band) if (b.box.angleUnmeasured) claimed.add(b)
                    }
                }
            }
        }
        val poolable = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        // Same exactly-once rule as the grouping shell: a word pooled by an
        // early cluster's unconfirmed band but CLAIMED by a later cluster's
        // confirmed one must not band again in the pool.
        for (r in pooled) if (r !in claimed) poolable.add(r)
        for (cluster in clusters) {
            if (cluster.angleDeg != 0f) continue
            for (i in cluster.memberIndices) poolable.add(measured[i])
        }
        for (u in unmeasured) if (u !in claimed) poolable.add(u)
        val pool = regions.filter { it in poolable }
        return assembleUpright(pool, rtl) + slanted
    }

    /**
     * Pure rect kernel — groups box indices into lines (mirrors EasyOCR
     * `group_text_box`: vertical-center band against the line's running-mean height,
     * plus a height-compatibility guard), then splits each band on large horizontal
     * (column) gaps. Member order within a returned group is left-to-right.
     */
    internal fun assembleLineIndices(boxes: List<Rect>): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        // 1) band by vertical center, using the CURRENT line's running-mean height
        //    for the tolerance, with a height-compatibility guard. Local height (not
        //    a frame-wide median) keeps a large title from widening the band enough
        //    to absorb a smaller adjacent row.
        val order = boxes.indices.sortedWith(compareBy({ boxes[it].centerY() }, { boxes[it].left }))
        val bands = ArrayList<MutableList<Int>>()
        var sumCenter = 0.0
        var sumHeight = 0.0
        var n = 0
        for (i in order) {
            val c = boxes[i].centerY().toDouble()
            val h = boxes[i].height().toDouble()
            if (bands.isNotEmpty()) {
                val meanCenter = sumCenter / n
                val meanHeight = sumHeight / n
                val sameBand = abs(c - meanCenter) < YCENTER_THS * meanHeight
                val heightOk = abs(h - meanHeight) < HEIGHT_THS * meanHeight
                if (sameBand && heightOk) {
                    bands.last() += i; sumCenter += c; sumHeight += h; n++
                    continue
                }
            }
            bands += mutableListOf(i); sumCenter = c; sumHeight = h; n = 1
        }
        // 2) within each band, split on large horizontal gaps (column breaks)
        val lines = ArrayList<List<Int>>()
        for (band in bands) {
            val gapMax = GAP_THS * band.map { boxes[it].height() }.average()
            val sorted = band.sortedBy { boxes[it].left }
            var run = mutableListOf(sorted.first())
            for (k in 1 until sorted.size) {
                if (boxes[sorted[k]].left - boxes[sorted[k - 1]].right > gapMax) {
                    lines += run; run = mutableListOf(sorted[k])
                } else {
                    run += sorted[k]
                }
            }
            lines += run
        }
        return lines
    }

    /** Stitch one line's word-regions into one region: union box, member texts
     *  joined left-to-right by a single space, mean confidence, and the members'
     *  per-character boxes carried through with each [CharBox.charOffset] rebased into
     *  the joined text. A recognizer that emits char boxes per WORD (PaddleOCR) would
     *  otherwise lose all of them here — and word separation IS PaddleOCR's main path
     *  for alphabetic scripts — so drag-lookup/furigana would fall back to proportional
     *  on exactly that path. Each member carries its chars on its single recognized
     *  line (line.text == member text); the inserted join spaces get no symbol, matching
     *  the rest of the symbol pipeline (and what consumers expect — they index by offset).
     *  With a non-null [frame] (a slanted cluster's), ordering and the union run on the
     *  members' DESKEWED rects, and the merged box is that in-frame union rotated back:
     *  bounds = exact screen AABB, oriented dims = union dims, angle = the frame's θ̄. */
    private fun mergeLine(
        members: List<RecognizedRegion>,
        rtl: Boolean,
        frame: AngleFrame? = null,
    ): RecognizedRegion {
        val withRects = members.map {
            it to if (frame == null) it.box.bounds else DeskewGeometry.deskew(it.box, frame)
        }
        // RTL sources (Arabic) read right-to-left: the rightmost word comes first in
        // logical order. LTR sources join left-to-right as before.
        val orderedPairs = if (rtl) withRects.sortedByDescending { it.second.left }
        else withRects.sortedBy { it.second.left }
        val ordered = orderedPairs.map { it.first }
        val text = ordered.joinToString(" ") { it.text }
        val rects = orderedPairs.map { it.second }
        val union = Rect(
            rects.minOf { it.left }, rects.minOf { it.top },
            rects.maxOf { it.right }, rects.maxOf { it.bottom },
        )
        val box = if (frame == null) OcrBox.upright(union)
        else OcrBox(
            DeskewGeometry.screenAabbOf(union, frame),
            union.width().toFloat(),
            union.height().toFloat(),
            frame.angleDeg,
        )
        val confs = ordered.map { it.confidence }.filter { it >= 0f }
        val confidence = if (confs.isEmpty()) -1f else confs.average().toFloat()
        // Shift each member's word-local char offsets by where that member's text starts
        // in the space-joined line (cumulative prior member lengths + one space each), so
        // the carried chars still index the merged [text]. Members without chars (e.g.
        // ML Kit) contribute none — the line just has fewer symbols, which is fine.
        val chars = ArrayList<CharBox>()
        var base = 0
        for (m in ordered) {
            for (line in m.lines) for (ch in line.chars) {
                chars += ch.copy(charOffset = ch.charOffset + base)
            }
            base += m.text.length + 1
        }
        return RecognizedRegion(
            text = text,
            box = box,
            orientation = TextOrientation.HORIZONTAL,
            confidence = confidence,
            lines = listOf(RecognizedLine(
                text = text, box = box, orientation = TextOrientation.HORIZONTAL, chars = chars,
            )),
            origin = RegionOrigin.LINE,
        )
    }
}
