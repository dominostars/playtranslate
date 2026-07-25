package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation

/**
 * Char-tier scale measurement — **an instrument, not yet a rule.**
 *
 * Nothing in the grouping path calls this. It exists so the question it answers
 * can be settled with data before any threshold moves, which is the discipline
 * the previous three grouping-threshold attempts skipped.
 *
 * ## The question
 *
 * [LayoutAnalyzer]'s scale gate asks "are these two lines the same font size?"
 * and answers it from glyph-tight box extent (height for horizontal text, width
 * for vertical). That measurement carries the line's CONTENT, not just its
 * scale: a Latin row with no ascender and no descender is roughly half the box
 * of a full row in the same font, and a kana-only vertical column is narrower
 * than a kanji-bearing one. Two device captures show it misfiring — `text_sample`
 * splits a three-line English sentence at ratio 0.31/0.32 ("This is a text
 * sample" h=157 with a descender vs "for translation" h=120 without), and
 * `manga_cogen_02` splits a vertical column pair at width-ratio 0.32. The same
 * statistic also gates [LayoutAnalyzer.interposingLine]'s ruby exemption, where
 * its noise is the documented known miss.
 *
 * The hypothesis to test: **per-character box extents are far less
 * content-dependent than the line box**, because a line's x-height glyphs
 * measure the same regardless of whether the line also happens to contain a `p`
 * or a `T`. If that holds, scale can be compared without inheriting the content
 * noise, and the bare cap stops firing on same-font pairs.
 *
 * ## What [scaleDelta] computes
 *
 * `min` over MATCHED quantiles of `(hi - lo) / lo`. Matched quantiles (q25 vs
 * q25, q50 vs q50, q75 vs q75) is load-bearing: comparing across quantiles would
 * let a 1.4× heading's q25 coincidentally align with the body's q75. Taking the
 * MINIMUM is the "some interpretation agrees" reading — two lines of the same
 * font will align on at least one quantile band even when their content differs
 * (an all-x-height line and a mixed line share their x-height population), while
 * a genuinely larger line is shifted at every quantile so no band aligns.
 *
 * Expected values if the hypothesis holds, all comparable to the existing caps:
 *  - same font, different glyph content → near 0
 *  - Title Case heading at ~1.45× → ~0.45
 *  - CJK heading at ~1.4× → ~0.4
 *  - ruby against its base (~1.67×) → ~0.67
 *
 * ## Where it is blind
 *
 * Only ML Kit measures per-glyph boxes ([OcrCapabilities.emitsCharBoxes] with
 * real symbol bounds). PaddleOCR's CTC boxes and [synthesizeEvenCharBoxes] both
 * take their cross-axis extent from the LINE box, so every char reports the line's
 * own extent and the statistic degenerates to the measurement it was meant to
 * replace. [hasMeasuredCharTier] detects that and both entry points return null
 * rather than a confident-looking restatement of the line box — so a future rule
 * built on this falls back to today's behavior on those engines instead of
 * silently changing it. That per-engine asymmetry is real and has to be priced
 * before anything is wired, which is another reason to measure first.
 */
object GlyphScale {

    /** Minimum char boxes on a line before its quantiles mean anything. */
    private const val MIN_CHARS = 3

    /** Quantiles sampled, in percent. Kept as a triple so the report columns
     *  and any future rule read the same bands. */
    val QUANTILES = intArrayOf(25, 50, 75)

    private fun crossExtent(r: Rect, vertical: Boolean): Int =
        if (vertical) r.width() else r.height()

    /** (near, far) along the reading axis: x for horizontal text, y for vertical. */
    private fun readingSpan(r: Rect, vertical: Boolean): Pair<Int, Int> =
        if (vertical) r.top to r.bottom else r.left to r.right

    /**
     * True when [line]'s char tier carries genuine per-glyph geometry rather
     * than cells sliced out of the line box. Synthesized tiers (Paddle CTC,
     * [synthesizeEvenCharBoxes]) are detected structurally, with no engine flag
     * — which keeps this usable on any future recognizer without a capability
     * table — via two independent signals, either of which proves the tier was
     * measured:
     *
     *  a. **some glyph is thinner than the line** across the compared axis;
     *  b. **some consecutive pair has a positive gap** along the reading axis.
     *
     * Signal (a) alone is NOT sufficient, and assuming it was is a trap this
     * caught during its own bring-up: a line whose glyphs are all the same
     * height (an all-x-height Latin word, a uniform kana column) has every real
     * char box equal to the line's extent whenever the line box is the union of
     * its glyphs, so a purely cross-axis test rejects a perfectly good tier and
     * the instrument goes silent on exactly the content it was built to measure.
     * Signal (b) covers it: real glyphs have inter-character space, while both
     * synthesizers tile the line box contiguously by construction (their cells
     * touch or overlap, never gap).
     */
    fun hasMeasuredCharTier(line: RecognizedLine): Boolean {
        val chars = line.chars
        if (chars.size < MIN_CHARS) return false
        val vertical = line.orientation == TextOrientation.VERTICAL
        val lineExtent = crossExtent(line.box.bounds, vertical)
        if (lineExtent <= 0) return false
        if (chars.any { crossExtent(it.box.bounds, vertical) in 1 until lineExtent }) return true
        val spans = chars.map { readingSpan(it.box.bounds, vertical) }.sortedBy { it.first }
        for (i in 1 until spans.size) if (spans[i].first > spans[i - 1].second) return true
        return false
    }

    /**
     * Cross-axis char-box extents of [line] at [QUANTILES] (nearest-rank on the
     * sorted extents), or null when the line has no measured char tier. Height
     * for horizontal text, width for vertical — the same axis the scale gate
     * compares.
     */
    fun quantiles(line: RecognizedLine): IntArray? {
        if (!hasMeasuredCharTier(line)) return null
        val vertical = line.orientation == TextOrientation.VERTICAL
        val sorted = line.chars
            .map { crossExtent(it.box.bounds, vertical) }
            .filter { it > 0 }
            .sorted()
        if (sorted.size < MIN_CHARS) return null
        val n = sorted.size
        return IntArray(QUANTILES.size) { i -> sorted[(n - 1) * QUANTILES[i] / 100] }
    }

    /**
     * Scale difference between two lines on the char tier: `min` over matched
     * quantiles of `(hi - lo) / lo`, directly comparable to
     * [LayoutAnalyzer.SIZE_RATIO_CAP_CORROBORATED] and the bare cap. Null when
     * either line lacks a measured char tier — callers must then fall back to
     * the line-box comparison rather than treat null as agreement.
     */
    fun scaleDelta(a: RecognizedLine, b: RecognizedLine): Double? {
        val qa = quantiles(a) ?: return null
        val qb = quantiles(b) ?: return null
        var best = Double.MAX_VALUE
        for (i in QUANTILES.indices) {
            val lo = minOf(qa[i], qb[i])
            val hi = maxOf(qa[i], qb[i])
            if (lo <= 0) continue
            val d = (hi - lo).toDouble() / lo
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) null else best
    }

    /** The line-box comparison the scale gate uses today, exposed so the report
     *  can put the two statistics side by side on the same pair. */
    fun lineBoxDelta(a: RecognizedLine, b: RecognizedLine): Double? {
        val vertical = a.orientation == TextOrientation.VERTICAL
        val ea = crossExtent(a.box.bounds, vertical)
        val eb = crossExtent(b.box.bounds, vertical)
        val lo = minOf(ea, eb)
        val hi = maxOf(ea, eb)
        if (lo <= 0) return null
        return (hi - lo).toDouble() / lo
    }
}
