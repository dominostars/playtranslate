package com.playtranslate.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ReplacementSpan

/**
 * Inline furigana (ruby text) span. Draws the original kanji at the baseline
 * and the reading above it in a smaller font. Adjusts line metrics so the
 * line is tall enough to fit the furigana without clipping.
 *
 * When [pitchDownstep] is set (whole-word, uninflected tokens only — see
 * [com.playtranslate.language.HintTextAnnotation.pitchDownstep]), the word's
 * pitch contour is drawn above the ruby and the line reserves a little more
 * headroom for it.
 */
class FuriganaSpan(
    private val reading: String,
    private val pitchDownstep: Int? = null,
) : ReplacementSpan() {

    /** Reused fill for the word-lookup highlight; see [draw]. Color is set
     *  per-draw from the underlying [BackgroundColorSpan]. */
    private val highlightPaint = Paint()

    private companion object {
        const val FURIGANA_SCALE = 0.5f
        const val FURIGANA_GAP = 0.15f // fraction of furigana size, gap between reading and kanji
        const val PITCH_BAND = 0.35f // extra fraction reserved above the ruby for the contour
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        val kanjiWidth = paint.measureText(text, start, end)
        val furiganaSize = paint.textSize * FURIGANA_SCALE
        val furiganaPaint = Paint(paint).apply { textSize = furiganaSize }
        val furiganaWidth = furiganaPaint.measureText(reading)

        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val band = 1f + FURIGANA_GAP + if (pitchDownstep != null) PITCH_BAND else 0f
            val furiganaHeight = (furiganaSize * band).toInt()
            fm.ascent -= furiganaHeight
            fm.top -= furiganaHeight
        }

        return maxOf(kanjiWidth, furiganaWidth).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val furiganaSize = paint.textSize * FURIGANA_SCALE
        val furiganaPaint = Paint(paint).apply { textSize = furiganaSize }

        val baseWidth = paint.measureText(text, start, end)
        val readingWidth = furiganaPaint.measureText(reading)
        val spanWidth = maxOf(baseWidth, readingWidth)

        // A ReplacementSpan owns every pixel of its run, so the framework never
        // paints a BackgroundColorSpan that sits beneath us — the word-lookup
        // highlight would vanish behind furigana-bearing kanji. Read that span
        // off the text and fill it ourselves, matching the plain-run path's
        // top..bottom extent so the band stays continuous with the kana tails.
        (text as? Spanned)
            ?.getSpans(start, end, BackgroundColorSpan::class.java)
            ?.firstOrNull()
            ?.let { hl ->
                highlightPaint.color = hl.backgroundColor
                canvas.drawRect(x, top.toFloat(), x + spanWidth, bottom.toFloat(), highlightPaint)
            }

        // Center base text within span
        val baseX = x + (spanWidth - baseWidth) / 2f
        canvas.drawText(text, start, end, baseX, y.toFloat(), paint)

        // Center reading above base text
        val readingX = x + (spanWidth - readingWidth) / 2f
        val gap = furiganaSize * FURIGANA_GAP
        val furiganaY = y.toFloat() + paint.fontMetrics.ascent - gap - furiganaPaint.fontMetrics.descent
        canvas.drawText(reading, readingX, furiganaY, furiganaPaint)

        if (pitchDownstep != null) {
            drawPitchContour(canvas, reading, readingX, furiganaY, furiganaPaint, pitchDownstep)
        }
    }
}
