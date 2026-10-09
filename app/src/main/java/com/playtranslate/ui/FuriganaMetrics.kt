package com.playtranslate.ui

import android.graphics.Typeface
import android.text.TextPaint

/**
 * The one definition of how a furigana label is drawn, shared by the renderer
 * ([TranslationOverlayView]) and every placement estimate that asks how wide
 * a reading will be ([com.playtranslate.OverlayToolkit]'s merges,
 * [com.playtranslate.FuriganaSlantPlacement]). A reading is drawn bold, at
 * [TEXT_SIZE_RATIO] of its band's cross extent, with an outline of
 * [OUTLINE_DP]. An estimate that measured any other way (a normal-weight
 * paint, no outline: three copies of it until 2026-10-08) let readings that
 * passed a collision check overlap on screen.
 */
object FuriganaMetrics {
    const val TEXT_SIZE_RATIO = 0.7f
    const val MIN_TEXT_PX = 4f
    const val OUTLINE_DP = 3f

    val typeface: Typeface get() = Typeface.DEFAULT_BOLD

    fun textSizePx(bandExtentPx: Float): Float =
        (bandExtentPx * TEXT_SIZE_RATIO).coerceAtLeast(MIN_TEXT_PX)

    fun outlinePx(density: Float): Float = OUTLINE_DP * density

    /** A paint that measures the way the renderer draws: [typeface], with
     *  the outline carried as the stroke width so [renderedWidth] adds it.
     *  Its text size is set per measurement and restored; callers that read
     *  relative glyph proportions off it see the same face the user does. */
    fun measuringPaint(density: Float): TextPaint = TextPaint().apply {
        typeface = this@FuriganaMetrics.typeface
        strokeWidth = outlinePx(density)
        textSize = 100f
    }

    /** Width of [text] drawn along its reading direction in a band
     *  [bandExtentPx] across, outline included. Leaves [paint]'s size as it
     *  found it. */
    fun renderedWidth(text: String, bandExtentPx: Float, paint: TextPaint): Float {
        val saved = paint.textSize
        paint.textSize = textSizePx(bandExtentPx)
        val width = paint.measureText(text)
        paint.textSize = saved
        return width + paint.strokeWidth
    }
}
