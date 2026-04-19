package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Small preview thumbnail showing a region's bounds within the display.
 * Draws a display-shaped box (surface color) with the region outlined inside.
 */
class RegionPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var regionTop = 0f
    private var regionBottom = 1f
    private var regionLeft = 0f
    private var regionRight = 1f
    private var isSelectedRegion = false
    private var displayRatio = 16f / 9f // width / height

    var surfaceColor: Int = 0
    var accentColor: Int = 0
    var mutedColor: Int = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val regionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val regionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    fun setRegion(top: Float, bottom: Float, left: Float, right: Float) {
        regionTop = top
        regionBottom = bottom
        regionLeft = left
        regionRight = right
        invalidate()
    }

    fun setRegionSelected(selected: Boolean) {
        isSelectedRegion = selected
        invalidate()
    }

    fun setDisplayRatio(widthOverHeight: Float) {
        displayRatio = widthOverHeight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (28 * resources.displayMetrics.density).toInt()
        val desiredW = (desiredH * displayRatio).toInt()
        setMeasuredDimension(desiredW, desiredH)
    }

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val cornerR = 3f * dp

        // Display background
        bgPaint.color = surfaceColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerR, cornerR, bgPaint)

        // Region bounds
        val rLeft = w * regionLeft
        val rTop = h * regionTop
        val rRight = w * regionRight
        val rBottom = h * regionBottom
        rect.set(rLeft, rTop, rRight, rBottom)

        regionStrokePaint.strokeWidth = 1.5f * dp

        if (isSelectedRegion) {
            // Filled with 25% accent + accent outline
            regionFillPaint.color = android.graphics.Color.argb(
                64, android.graphics.Color.red(accentColor),
                android.graphics.Color.green(accentColor),
                android.graphics.Color.blue(accentColor)
            )
            regionStrokePaint.color = accentColor
            canvas.drawRect(rect, regionFillPaint)
            canvas.drawRect(rect, regionStrokePaint)
        } else {
            // Muted outline only
            regionStrokePaint.color = mutedColor
            canvas.drawRect(rect, regionStrokePaint)
        }
    }
}
