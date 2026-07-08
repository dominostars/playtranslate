package com.playtranslate

import android.graphics.Rect

/**
 * Glyph-anchor geometry for the pinhole detector (audit A7).
 *
 * A uniform sample grid dilutes small real changes: one swapped digit in a
 * wide box moves far less than [PinholeCalibration.PINHOLE_CHANGE_PCT] of
 * samples and false-KEEPs (the stale-counter class). But text changes must
 * manifest ON the text — so each box gets a small set of anchor points laid
 * along its (approximated) text lines, and changed samples landing near
 * distinct anchors raise suspicion regardless of area percentage.
 *
 * Per-line OCR rects aren't retained on [com.playtranslate.ui.TextBox], so
 * anchors approximate the source line bands from the rendered rect +
 * lineCount: [PinholeCalibration.GLYPH_ANCHORS_PER_LINE] points (start,
 * 1/3, 2/3, end) along each line's center row, after insetting the rect
 * past its rendering padding. Vertical boxes swap axes (columns packed
 * right-to-left).
 *
 * Pure geometry, JVM-tested; the sampling side lives in checkPinholes.
 */
object GlyphAnchors {

    /** Anchor points for a box: packed [x0, y0, x1, y1, …] in the same
     *  coordinate space as [rect]. Returns an empty array for degenerate
     *  geometry. Capped at [MAX_ANCHORS] points (bitset-tracked). */
    fun forBox(rect: Rect, lineCount: Int, vertical: Boolean): IntArray {
        val inset = PinholeCalibration.GLYPH_ANCHOR_INSET_PX
        val left = rect.left + inset
        val top = rect.top + inset
        val right = rect.right - inset
        val bottom = rect.bottom - inset
        if (right - left < MIN_SPAN || bottom - top < MIN_SPAN) return EMPTY

        val lines = lineCount.coerceIn(1, MAX_LINES)
        val perLine = PinholeCalibration.GLYPH_ANCHORS_PER_LINE
        val out = IntArray(lines * perLine * 2)
        var i = 0
        if (!vertical) {
            val lineH = (bottom - top).toFloat() / lines
            for (l in 0 until lines) {
                val y = (top + (l + 0.5f) * lineH).toInt()
                for (a in 0 until perLine) {
                    val x = left + ((right - left).toLong() * a / (perLine - 1)).toInt()
                    out[i++] = x; out[i++] = y
                }
            }
        } else {
            // Vertical text: columns, laid right-to-left.
            val colW = (right - left).toFloat() / lines
            for (l in 0 until lines) {
                val x = (right - (l + 0.5f) * colW).toInt()
                for (a in 0 until perLine) {
                    val y = top + ((bottom - top).toLong() * a / (perLine - 1)).toInt()
                    out[i++] = x; out[i++] = y
                }
            }
        }
        return out
    }

    /** Index of the first anchor within [PinholeCalibration.GLYPH_PROBE_RADIUS_PX]
     *  (Chebyshev) of ([x], [y]), or -1. [anchors] as packed by [forBox]. */
    fun anchorNear(anchors: IntArray, x: Int, y: Int): Int {
        val r = PinholeCalibration.GLYPH_PROBE_RADIUS_PX
        var idx = 0
        var i = 0
        while (i < anchors.size) {
            val dx = x - anchors[i]
            val dy = y - anchors[i + 1]
            if (dx in -r..r && dy in -r..r) return idx
            idx++
            i += 2
        }
        return -1
    }

    /** Anchor-point cap so hit-tracking fits one Long bitset. */
    const val MAX_ANCHORS = 64
    private const val MAX_LINES = MAX_ANCHORS / 4
    private const val MIN_SPAN = 12
    private val EMPTY = IntArray(0)
}
