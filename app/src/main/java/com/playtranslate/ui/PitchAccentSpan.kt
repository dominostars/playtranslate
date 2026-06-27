package com.playtranslate.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import com.playtranslate.dictionary.pitch.Mora
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a kana reading with its pitch-accent contour in NHK line notation:
 * an overline across the HIGH morae and a vertical drop tick where the
 * pitch falls. Odaka words get the tick at the word's end (the drop lands
 * on the particle) while heiban end with a bare overline — that final hook
 * is what visually separates them; the `[n]` suffix carries it in text form.
 *
 * Reports the paint's own FontMetrics unchanged (unlike [FuriganaSpan],
 * which reserves extra ascent) so host rows don't reflow: hosts grant the
 * overline headroom via padding instead. See WordResultCell for the
 * layout-stability contract.
 */
class PitchAccentSpan(private val downstep: Int) : ReplacementSpan() {

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        // ReplacementSpan contract: the layout uses whatever this leaves in
        // [fm] as the run's line metrics — leaving it UNTOUCHED means stale
        // garbage from the previous measurement, not "inherit". Fill it with
        // the paint's own metrics, adding nothing: line metrics match plain
        // text by construction (the layout-stability contract), and the
        // baseline stays put so the contour lands in the host's padding
        // band instead of being clipped above a mis-raised line.
        fm?.let { paint.getFontMetricsInt(it) }
        return ceil(paint.measureText(text, start, end)).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        drawPitchContour(canvas, text.subSequence(start, end).toString(), x, y.toFloat(), paint, downstep)
    }
}

/**
 * Shared contour painter — used by [PitchAccentSpan] over normal-size
 * readings and by [FuriganaSpan] over half-size ruby. Draws relative to
 * [textPaint]'s metrics: overline ~10% of the text size above the ascent,
 * everything else scaled off [Paint.getTextSize] so it works at any scale.
 */
internal fun drawPitchContour(
    canvas: Canvas,
    reading: String,
    x: Float,
    baselineY: Float,
    textPaint: Paint,
    downstep: Int,
) {
    val morae = Mora.segment(reading)
    if (morae.isEmpty()) return
    val contour = Mora.contour(downstep, morae.size)

    val size = textPaint.textSize
    val highY = baselineY + textPaint.fontMetrics.ascent - size * 0.10f
    val lowY = highY + size * 0.18f

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPaint.color
        strokeWidth = max(size * 0.06f, 1.5f)
        style = Paint.Style.STROKE
    }

    // Mora boundaries as cumulative advances from x.
    val bounds = FloatArray(morae.size + 1)
    bounds[0] = x
    for (i in morae.indices) {
        bounds[i + 1] = x + textPaint.measureText(reading, 0, morae[i].end)
    }

    // Overline each run of consecutive HIGH morae; a run that ends in a fall
    // gets a drop tick at its right edge — mid-word (next mora low) or
    // word-final when the drop lands on the particle (odaka,
    // ghostHigh=false). Heiban runs end bare. Rises are unmarked (NHK
    // convention).
    var i = 0
    while (i < morae.size) {
        if (contour.high[i]) {
            var j = i
            while (j + 1 < morae.size && contour.high[j + 1]) j++
            canvas.drawLine(bounds[i], highY, bounds[j + 1], highY, linePaint)
            val falls = j + 1 < morae.size || !contour.ghostHigh
            if (falls) canvas.drawLine(bounds[j + 1], highY, bounds[j + 1], lowY, linePaint)
            i = j + 1
        } else {
            i++
        }
    }
}

/**
 * Reading text annotated with its pitch contour plus a smaller trailing
 * `[n]` suffix listing every downstep variant (`[0]·[3]`); the contour
 * itself draws the first (primary) variant. Shared by the word-detail
 * header and [WordResultCell]. Hosts must add top padding for the overline
 * band (~8-10dp at their text size).
 */
fun buildPitchAnnotatedReading(reading: String, pitch: List<Int>): CharSequence {
    val sb = SpannableStringBuilder(reading)
    sb.setSpan(
        PitchAccentSpan(pitch.first()),
        0, reading.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    val suffixStart = sb.length
    sb.append(" " + pitch.joinToString("·") { "[$it]" })
    sb.setSpan(
        RelativeSizeSpan(0.75f),
        suffixStart, sb.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    // Keep the [n] numbers light even when the host text is bold (e.g. the
    // kana-only title / headword the contour now rides on).
    sb.setSpan(
        NormalWeightSpan(),
        suffixStart, sb.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    return sb
}

/** Forces normal weight on its span even when the host text is bold — keeps the
 *  `[n]` pitch suffix light against a bold title/headword. */
private class NormalWeightSpan : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = unbold(tp)
    override fun updateMeasureState(tp: TextPaint) = unbold(tp)
    private fun unbold(tp: TextPaint) {
        tp.typeface = Typeface.create(tp.typeface, Typeface.NORMAL)
    }
}
