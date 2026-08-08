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
        // Rotated regions band in their own deskewed frames ([assembleSlanted]):
        // same-angle clusters run the same banding kernel on rects whose slant
        // has been rotated away, so a slanted word row stitches exactly like an
        // upright one. The re-entrant call runs the upright partition alone, so
        // the vertical-dominance vote below can't be tipped by rotated members
        // (all HORIZONTAL by the producer invariant); a frame with no rotated
        // region skips this branch entirely.
        if (regions.any { it.box.isRotated }) {
            val (rotated, upright) = regions.partition { it.box.isRotated }
            return assembleLines(upright, rtl) + assembleSlanted(rotated, rtl)
        }
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
     * Band slanted regions per same-angle cluster, in that cluster's deskewed
     * frame — the same [assembleLineIndices] kernel the upright path runs, on
     * rects whose slant has been rotated away (heights there are the true
     * oriented heights, so the banding thresholds keep their meaning). A
     * singleton line stays the ORIGINAL region, slant and all; a multi-member
     * line merges via [mergeLine] with the frame, emitting the in-frame union
     * rotated back (bounds = exact screen AABB, oriented dims = union dims,
     * angle = θ̄ verbatim). Regions far apart in angle never band, whatever
     * their AABB overlap. Cluster anchor = the members' AABB-union center,
     * matching the grouping shell's convention.
     */
    private fun assembleSlanted(rotated: List<RecognizedRegion>, rtl: Boolean): List<RecognizedRegion> {
        val clusters = DeskewGeometry.clusterByAngle(
            rotated.map { it.box.angleDeg },
            rotated.map { it.box.orientedWidth },
            rotated.map { it.box.orientedHeight },
            DeskewGeometry.DEFAULT_CLUSTER_CAP_DEG,
        )
        val out = ArrayList<RecognizedRegion>(rotated.size)
        for (cluster in clusters) {
            val members = cluster.memberIndices.map { rotated[it] }
            if (members.size == 1) {
                out += members[0]
                continue
            }
            val union = Rect(members[0].box.bounds)
            for (m in members.drop(1)) union.union(m.box.bounds)
            val frame = AngleFrame(cluster.angleDeg, union.centerX(), union.centerY())
            val deskewed = members.map { DeskewGeometry.deskew(it.box, frame) }
            out += assembleLineIndices(deskewed).map { idxs ->
                if (idxs.size == 1) members[idxs[0]]
                else mergeLine(idxs.map { members[it] }, rtl, frame)
            }
        }
        return out
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
