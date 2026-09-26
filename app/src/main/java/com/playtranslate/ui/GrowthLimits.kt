package com.playtranslate.ui

import android.graphics.Rect

/**
 * Where a translation box may grow to reach the minimum text size
 * ([com.playtranslate.Prefs.overlayMinTextSp]), in the same space as
 * [TextBox.bounds] (OCR-crop px). The view maps both like the boxes.
 *
 * @property bounds the capture region: growth never claims pixels outside it
 *   (a box whose own rect already reaches past it keeps that much). Null =
 *   the whole view (camera, Import file).
 * @property avoid what growth must keep clear of besides the other boxes,
 *   each kept at the distance a box keeps from its source (the box padding,
 *   at the view's density): text on screen that no box covers yet
 *   ([unboxedText]) and, on the pinhole tier, space no look has seen
 *   uncovered and the floating icon.
 */
data class GrowthLimits(
    val bounds: Rect? = null,
    val avoid: List<Rect> = emptyList(),
) {
    companion object {
        val NONE = GrowthLimits()

        /** The rects of [text] (the drawn rects of the groups a look read)
         *  that none of [boxes] covers (centre outside every box's drawn
         *  source): text on screen with no box on it, held while it types
         *  out, deferred, or dropped because its translation came back blank.
         *  A box's own text, read again a few px off, is covered, so it never
         *  walls its own box in. */
        fun unboxedText(text: List<Rect>, boxes: List<TextBox>): List<Rect> =
            text.filter { t ->
                boxes.none { it.drawBounds.contains(t.centerX(), t.centerY()) }
            }
    }
}
