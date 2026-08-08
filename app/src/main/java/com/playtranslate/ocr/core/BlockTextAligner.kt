package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation

/**
 * Aligns a whole-block reading — one string for an entire multi-line group, with no
 * spatial output (manga-ocr's native shape) — against the base engine's per-line
 * readings, so the block text can be split back onto the base lines and carry
 * per-character geometry inherited from the base pass.
 *
 * The premise: by the time a block specialist runs, the base engine has already
 * produced reliable *geometry* (line boxes plus a real char tier — ML Kit symbols,
 * Meiki, Paddle CTC) around unreliable *text*; the specialist is the mirror image.
 * Character-level alignment (Needleman–Wunsch / edit-distance with traceback) marries
 * them: a block char matched OR substituted onto a base char inherits that base
 * char's line and real box — a substitution is the same glyph at the same position,
 * read differently. An inserted char (e.g. the っ the base engine dropped from
 * いったい) takes the line of the NEXT anchor and an interpolated box between its
 * anchors. Attach-to-next resolves the boundary-ambiguous case actually observed
 * (泊してから→一泊してから: a dropped column-INITIAL glyph, which belongs to the
 * following line); interior insertions have same-line anchors on both sides, and a
 * trailing run's only option is the previous anchor's line.
 *
 * The alignment doubles as the adoption guard the per-line "differs → adopt" policy
 * never had: a correction aligns with few gaps, while a hallucinated decode diverges
 * in content and length. Blocks whose insert+delete rate exceeds [MAX_GAP_RATE] or
 * whose exact-match rate falls below [MIN_MATCH_RATE] are [Result.Rejected] and the
 * caller keeps the base result. Both thresholds are provisional pending on-device
 * calibration (the automated golden suite is not reliable enough to tune against).
 *
 * Splice policy per line: a line whose aligned slice equals its base text — or that
 * received no block chars at all (fully-deleted base line, e.g. printed rubi the
 * specialist reads through) — is returned as the SAME [RecognizedLine] instance, so
 * real boxes survive and callers detect change by identity. An adopted line keeps the
 * base line's box and orientation, drops the now-stale element (word) tier, and
 * rebuilds the char tier as above.
 *
 * Inputs: [align]'s `lines` must be in reading order, and `blockText` normalized and
 * space-free (the block decode strips spaces); the base concatenation joins with no
 * separator, matching the CJK group join.
 *
 * Pure string+Rect logic — no bitmap, no model, no engine dependency. The
 * whole-block analogue of [synthesizeEvenCharBoxes], unit-tested the same way
 * (synthetic fixtures, Robolectric for Rect).
 */
internal object BlockTextAligner {

    /**
     * Insert+delete share of max(block, base) length above which the block reading
     * is rejected as a hallucination or mis-scoped crop rather than a correction.
     * Sits just above the fully-deleted-rubi-line case (a 2-char rubi over a 4-char
     * body line is 2/6 ≈ 0.33).
     */
    private const val MAX_GAP_RATE = 0.35

    /**
     * Exact-match share of max(block, base) length below which the readings agree on
     * too little to trust positional correspondence — an all-substitution alignment
     * is indistinguishable from a same-length hallucination, so the base stands.
     */
    private const val MIN_MATCH_RATE = 0.40

    /** DP-size guard: the traceback matrix is (m+1)×(n+1) ints. The specialist's
     *  decode cap (≈300 tokens) keeps real inputs well under this. */
    private const val MAX_ALIGN_LEN = 400

    sealed class Result {
        /** Same size/order as the input lines; unchanged lines are the same instances. */
        data class Aligned(val lines: List<RecognizedLine>, val stats: Stats) : Result()

        /** Block reading unusable — caller keeps the base result. [reason] names the
         *  guard that fired, with its numbers (GroupDecision-style, for the debug log). */
        data class Rejected(val reason: String) : Result()
    }

    /** Alignment op counts, for the caller's debug log. */
    data class Stats(
        val matches: Int,
        val substitutions: Int,
        val insertions: Int,
        val deletions: Int,
    )

    /**
     * Splits [blockText] (the specialist's whole-group reading) across [lines] (the
     * base engine's lines for that group, in reading order). See the object kdoc for
     * the algorithm, guards, and splice policy.
     */
    fun align(blockText: String, lines: List<RecognizedLine>): Result {
        if (blockText.isBlank()) return Result.Rejected("block text blank")
        val m = blockText.length
        val n = lines.sumOf { it.text.length }
        if (n == 0) return Result.Rejected("no base text")
        if (m > MAX_ALIGN_LEN || n > MAX_ALIGN_LEN) {
            return Result.Rejected("too long to align (block=$m base=$n cap=$MAX_ALIGN_LEN)")
        }

        // Base concatenation (reading order, no separator) plus concat-index →
        // (line, offset-within-line-text) maps for anchor lookups. Offsets index the
        // line's text — the same space CharBox.charOffset lives in.
        val base = StringBuilder(n)
        val lineOf = IntArray(n)
        val offsetOf = IntArray(n)
        var p = 0
        lines.forEachIndexed { li, line ->
            for (o in line.text.indices) {
                base.append(line.text[o])
                lineOf[p] = li
                offsetOf[p] = o
                p++
            }
        }

        // Standard edit-distance DP. Full matrix kept for the traceback; ≤ ~640KB
        // transient at the MAX_ALIGN_LEN cap.
        val w = n + 1
        val dp = IntArray((m + 1) * w)
        for (j in 0..n) dp[j] = j
        for (i in 1..m) {
            dp[i * w] = i
            val a = blockText[i - 1]
            for (j in 1..n) {
                val diag = dp[(i - 1) * w + j - 1] + if (a == base[j - 1]) 0 else 1
                val up = dp[(i - 1) * w + j] + 1 // block char inserted (absent from base)
                val left = dp[i * w + j - 1] + 1 // base char deleted (absent from block)
                dp[i * w + j] = minOf(diag, up, left)
            }
        }

        // Traceback, diagonal-preferred on ties so every agreeing char becomes an
        // anchor. corr[i] = base index block char i aligned onto (match or
        // substitution); -1 = inserted.
        val corr = IntArray(m) { -1 }
        var matches = 0
        var substitutions = 0
        var insertions = 0
        var deletions = 0
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            val cur = dp[i * w + j]
            if (i > 0 && j > 0) {
                val subCost = if (blockText[i - 1] == base[j - 1]) 0 else 1
                if (dp[(i - 1) * w + j - 1] + subCost == cur) {
                    corr[i - 1] = j - 1
                    if (subCost == 0) matches++ else substitutions++
                    i--; j--
                    continue
                }
            }
            if (j > 0 && dp[i * w + j - 1] + 1 == cur) {
                deletions++; j--
            } else {
                insertions++; i--
            }
        }

        val denom = maxOf(m, n)
        val gapRate = (insertions + deletions).toDouble() / denom
        if (gapRate > MAX_GAP_RATE) {
            return Result.Rejected(
                "gap rate ${"%.2f".format(gapRate)} > $MAX_GAP_RATE " +
                    "(ins=$insertions del=$deletions block=$m base=$n)"
            )
        }
        val matchRate = matches.toDouble() / denom
        if (matchRate < MIN_MATCH_RATE) {
            return Result.Rejected(
                "match rate ${"%.2f".format(matchRate)} < $MIN_MATCH_RATE " +
                    "(match=$matches sub=$substitutions block=$m base=$n)"
            )
        }

        // Assign every block char a line: anchors take their base char's line; an
        // insertion takes the NEXT anchor's line (see object kdoc), so one backward
        // pass covers interior, boundary, and leading runs alike; a trailing run
        // falls back to the last anchor's line. Anchor lines are non-decreasing
        // (the traceback is monotonic), so each line's chars form one contiguous
        // slice of blockText. The match-rate guard guarantees at least one anchor.
        val lineAssign = IntArray(m)
        var nextLine = lineOf[corr[corr.indexOfLast { it >= 0 }]]
        for (k in m - 1 downTo 0) {
            val cj = corr[k]
            if (cj >= 0) nextLine = lineOf[cj]
            lineAssign[k] = nextLine
        }

        val out = ArrayList<RecognizedLine>(lines.size)
        var cursor = 0
        for (li in lines.indices) {
            val start = cursor
            while (cursor < m && lineAssign[cursor] == li) cursor++
            val slice = blockText.substring(start, cursor)
            val baseLine = lines[li]
            out += if (slice.isEmpty() || slice == baseLine.text) baseLine
            else adoptLine(baseLine, slice, start, corr, offsetOf)
        }
        return Result.Aligned(out, Stats(matches, substitutions, insertions, deletions))
    }

    /**
     * Build the adopted line: base geometry, block text. Anchored chars inherit the
     * base char's real box, matched through the base offset (the base char tier may
     * be sparse — a missing symbol is allowed); the rest are interpolated between
     * anchors by [fillGaps]. No anchor boxes at all → even-spread synthesis, the
     * behavior a text-only recognizer gets anyway.
     *
     * A rotated base line interpolates along its BASELINE: anchors project into
     * the deskewed frame, [fillGaps] runs there (staying `Array<Rect?>`-pure),
     * and filled cells rotate their centers back out as upright screen rects.
     * The adopted line keeps the ROTATED [RecognizedLine.box] with UPRIGHT char
     * cells riding the baseline — deliberately: that is the one slanted-char
     * representation every consumer shares (ML Kit measures it, Paddle and the
     * even-spread synthesizers emit it), not an inconsistency.
     */
    private fun adoptLine(
        base: RecognizedLine,
        slice: String,
        sliceStart: Int,
        corr: IntArray,
        offsetOf: IntArray,
    ): RecognizedLine {
        val vertical = base.orientation == TextOrientation.VERTICAL
        val bounds = base.box.bounds
        val boxByOffset = HashMap<Int, Rect>(base.chars.size)
        for (c in base.chars) if (c.charOffset !in boxByOffset) boxByOffset[c.charOffset] = c.box.bounds

        val rects = arrayOfNulls<Rect>(slice.length)
        var anchored = false
        for (k in slice.indices) {
            val cj = corr[sliceStart + k]
            if (cj >= 0) {
                val r = boxByOffset[offsetOf[cj]]
                if (r != null) {
                    rects[k] = r
                    anchored = true
                }
            }
        }
        val chars = if (!anchored) {
            synthesizeEvenCharBoxes(slice, base.box, vertical)
        } else if (!base.box.isRotated) {
            fillGaps(rects, bounds, vertical)
            slice.indices.map { k ->
                CharBox(text = slice[k].toString(), box = OcrBox.upright(rects[k]!!), charOffset = k)
            }
        } else {
            val frame = AngleFrame(base.box.angleDeg, bounds.centerX(), bounds.centerY())
            val inFrameLine = DeskewGeometry.deskew(base.box, frame)
            val ax = frame.anchorX.toFloat()
            val ay = frame.anchorY.toFloat()
            val uRects = arrayOfNulls<Rect>(slice.length)
            for (k in slice.indices) {
                val r = rects[k] ?: continue
                val ucx = ax + DeskewGeometry.toFrameU(r.exactCenterX(), r.exactCenterY(), ax, ay, frame.cosT, frame.sinT)
                val ucy = ay + DeskewGeometry.toFrameV(r.exactCenterX(), r.exactCenterY(), ax, ay, frame.cosT, frame.sinT)
                val l = DeskewGeometry.roundHalfUp(ucx - r.width() / 2f)
                val t = DeskewGeometry.roundHalfUp(ucy - r.height() / 2f)
                uRects[k] = Rect(l, t, l + r.width(), t + r.height())
            }
            // Rotated ⟹ horizontal reading axis in-frame (producer invariant).
            fillGaps(uRects, inFrameLine, vertical = false)
            slice.indices.map { k ->
                val cell = rects[k] ?: run {
                    val u = uRects[k]!!
                    val bx = DeskewGeometry.rotateX(u.exactCenterX(), u.exactCenterY(), ax, ay, frame.cosT, frame.sinT)
                    val by = DeskewGeometry.rotateY(u.exactCenterX(), u.exactCenterY(), ax, ay, frame.cosT, frame.sinT)
                    val l = DeskewGeometry.roundHalfUp(bx - u.width() / 2f)
                    val t = DeskewGeometry.roundHalfUp(by - u.height() / 2f)
                    Rect(l, t, l + u.width(), t + u.height())
                }
                CharBox(text = slice[k].toString(), box = OcrBox.upright(cell), charOffset = k)
            }
        }
        return RecognizedLine(
            text = slice,
            box = base.box,
            orientation = base.orientation,
            // The base element (word) tier labels the replaced reading — stale.
            // Consumers fall back cleanly on an empty tier.
            elements = emptyList(),
            chars = chars,
        )
    }

    /**
     * Fill null cells (inserted chars, or anchors whose base box is missing) between
     * known boxes: each null run tiles the pixel span between its bounding anchors —
     * or the line edge, for leading/trailing runs — evenly along the reading axis,
     * cross-axis from the line box; the run-scoped analogue of
     * [synthesizeEvenCharBoxes]. A run with no room (anchors adjacent or overlapping)
     * copies the nearest anchor's box instead: a shared rect stays correct for the
     * offset-range consumers (they union boxes over a span) and beats a zero-area
     * cell that drag hit-testing can't hit.
     */
    private fun fillGaps(rects: Array<Rect?>, lineBounds: Rect, vertical: Boolean) {
        var k = 0
        while (k < rects.size) {
            if (rects[k] != null) {
                k++
                continue
            }
            val runStart = k
            while (k < rects.size && rects[k] == null) k++
            val prev = rects.getOrNull(runStart - 1)
            val next = if (k < rects.size) rects[k] else null
            val lo = when {
                prev != null -> if (vertical) prev.bottom else prev.right
                else -> if (vertical) lineBounds.top else lineBounds.left
            }
            val hi = when {
                next != null -> if (vertical) next.top else next.left
                else -> if (vertical) lineBounds.bottom else lineBounds.right
            }
            val len = k - runStart
            val span = hi - lo
            if (span < len) {
                // prev ?: next is non-null: an all-null array never reaches fillGaps
                // (the caller takes the even-spread path), so every run borders an anchor.
                val donor = prev ?: next!!
                for (idx in runStart until k) rects[idx] = donor
            } else {
                for (idx in runStart until k) {
                    val c0 = lo + span * (idx - runStart) / len
                    val c1 = maxOf(c0 + 1, lo + span * (idx - runStart + 1) / len)
                    rects[idx] =
                        if (vertical) Rect(lineBounds.left, c0, lineBounds.right, c1)
                        else Rect(c0, lineBounds.top, c1, lineBounds.bottom)
                }
            }
        }
    }
}
