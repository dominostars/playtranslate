package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextOrientation

/**
 * Pure geometry for translation overlays: maps OCR-bitmap box bounds to
 * on-screen rects and resolves overlaps between neighbouring boxes.
 *
 * Extracted from `TranslationOverlayView.rebuildChildren` so the per-box
 * overlay-window path and the single-window furigana renderer resolve box
 * geometry through one tested implementation. Pure and side-effect free —
 * unit-testable without a live View.
 */
internal object OverlayLayout {

    /** Padding (dp) added around a non-furigana box for visual breathing room. */
    private const val BOX_PADDING_DP = 6f

    /** Map an OCR-bitmap rect to on-screen coordinates. */
    fun mapRect(
        r: Rect,
        cropOffsetX: Int, cropOffsetY: Int,
        scaleX: Float, scaleY: Float,
    ): RectF = RectF(
        (r.left + cropOffsetX) * scaleX,
        (r.top + cropOffsetY) * scaleY,
        (r.right + cropOffsetX) * scaleX,
        (r.bottom + cropOffsetY) * scaleY,
    )

    /**
     * Resolve every box's final on-screen rect: map OCR bounds → screen, pad
     * non-furigana boxes, then resolve vertical overlaps between horizontal
     * boxes and horizontal overlaps between vertical boxes.
     *
     * Returns one [RectF] per input box, index-aligned with [boxes].
     */
    fun resolveScreenRects(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        displayW: Int, displayH: Int,
        density: Float,
    ): List<RectF> {
        val scaleX = displayW.toFloat() / screenshotW
        val scaleY = displayH.toFloat() / screenshotH
        val boxPadding = BOX_PADDING_DP * density
        val dW = displayW.toFloat()
        val dH = displayH.toFloat()

        // Map OCR bounds to screen coordinates; pad non-furigana boxes.
        val finalRects = boxes.map { box ->
            val r = mapRect(box.bounds, cropLeft, cropTop, scaleX, scaleY)
            if (box.isFurigana) {
                RectF(r.left, r.top, r.right, r.bottom)
            } else {
                RectF(
                    (r.left - boxPadding).coerceAtLeast(0f),
                    (r.top - boxPadding).coerceAtLeast(0f),
                    (r.right + boxPadding).coerceAtMost(dW),
                    (r.bottom + boxPadding).coerceAtMost(dH),
                )
            }
        }

        // Resolve vertical overlaps for non-furigana horizontal boxes.
        val hBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].orientation != TextOrientation.VERTICAL
        }.sortedBy { finalRects[it].top }
        for (a in hBoxIndices.indices) {
            for (b in a + 1 until hBoxIndices.size) {
                val ri = finalRects[hBoxIndices[a]]
                val rj = finalRects[hBoxIndices[b]]
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        // Resolve horizontal overlaps for non-furigana vertical boxes
        // (adjacent columns whose overlay boxes overlap on the X axis).
        // Sort by right edge descending (right-to-left reading order).
        val vBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].orientation == TextOrientation.VERTICAL
        }.sortedByDescending { finalRects[it].right }
        for (a in vBoxIndices.indices) {
            for (b in a + 1 until vBoxIndices.size) {
                val ri = finalRects[vBoxIndices[a]]
                val rj = finalRects[vBoxIndices[b]]
                if (ri.left < rj.right && ri.top < rj.bottom && ri.bottom > rj.top) {
                    val mid = (ri.left + rj.right) / 2f
                    ri.left = mid
                    rj.right = mid
                }
            }
        }

        return finalRects
    }

    /** Fuzzy comparison: same content, bounds within [tolerance] px. Absorbs
     *  OCR jitter so stable on-screen text doesn't trigger an overlay rebuild. */
    fun boxesMatchFuzzy(a: List<TextBox>, b: List<TextBox>, tolerance: Int = 20): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val ba = a[i]; val bb = b[i]
            if (ba.translatedText != bb.translatedText) return false
            if (ba.isFurigana != bb.isFurigana) return false
            if (ba.sourceText != bb.sourceText) return false
            if (ba.orientation != bb.orientation) return false
            if (ba.alignment != bb.alignment) return false
            val ra = ba.bounds; val rb = bb.bounds
            if (Math.abs(ra.left - rb.left) > tolerance ||
                Math.abs(ra.top - rb.top) > tolerance ||
                Math.abs(ra.right - rb.right) > tolerance ||
                Math.abs(ra.bottom - rb.bottom) > tolerance) return false
        }
        return true
    }
}
