package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.roundToInt

/**
 * The text-size sliders' shared look and axis: a pill track with round
 * handles, sized once for both the results Text size picker
 * ([FontSizeRangePopover]) and the overlay minimum row ([SizeSliderView]), so
 * the two can't drift apart. The track is inset by half a handle's touch box
 * at each end, so an end handle's whole grab area stays inside its view (a
 * parent won't dispatch a touch that misses the child's bounds).
 */
internal class SliderTrackPainter(ctx: Context) {

    private val density = ctx.resources.displayMetrics.density

    val trackHalf = TRACK_H_DP * density / 2f
    val handleRadius = HANDLE_RADIUS_DP * density
    /** Half a handle's square grab area; also the track's end inset. */
    val touchHalf = TOUCH_BOX_DP * density / 2f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.themeColor(R.attr.ptSurface)
    }
    private val stretchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.themeColor(R.attr.ptCard)
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
    }
    private val rect = RectF()

    /** The plain pill from [left] to [right], centred on [cy]. */
    fun drawTrack(canvas: Canvas, left: Float, right: Float, cy: Float) {
        rect.set(left, cy - trackHalf, right, cy + trackHalf)
        canvas.drawRoundRect(rect, trackHalf, trackHalf, trackPaint)
    }

    /** A [color]-filled stretch of the pill (the range picker's selection). */
    fun drawStretch(canvas: Canvas, left: Float, right: Float, cy: Float, color: Int) {
        stretchPaint.color = color
        rect.set(left, cy - trackHalf, right, cy + trackHalf)
        canvas.drawRoundRect(rect, trackHalf, trackHalf, stretchPaint)
    }

    /** One handle centred on ([x], [cy]): a card-coloured disc in a [ringColor] ring. */
    fun drawHandle(canvas: Canvas, x: Float, cy: Float, ringColor: Int) {
        canvas.drawCircle(x, cy, handleRadius, handleFill)
        handleRing.color = ringColor
        canvas.drawCircle(x, cy, handleRadius - handleRing.strokeWidth / 2f, handleRing)
    }

    /** Where [value] in [from]..[to] sits on a track spanning a view [width]
     *  wide, left to right. */
    fun xFor(value: Int, from: Int, to: Int, width: Int): Float {
        val t = if (to == from) 0f else (value - from).toFloat() / (to - from)
        return touchHalf + t * span(width)
    }

    /** The whole value in [from]..[to] nearest [x] on that track. */
    fun valueFor(x: Float, from: Int, to: Int, width: Int): Int {
        val t = ((x - touchHalf) / span(width)).coerceIn(0f, 1f)
        return (from + t * (to - from)).roundToInt().coerceIn(from, to)
    }

    private fun span(width: Int) = (width - touchHalf * 2).coerceAtLeast(1f)

    companion object {
        const val TRACK_H_DP = 10f
        const val HANDLE_RADIUS_DP = 9f
        const val RING_DP = 2f
        const val TOUCH_BOX_DP = 48f
    }
}
