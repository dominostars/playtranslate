package com.playtranslate.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.withSave
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * A region's standing indicator: the region picker preview's outside dim +
 * drop shadow + accent glow, bordered with the editor's screen-space DASHES
 * instead of its solid line. Shown by the camera / Import tool over a
 * frozen frame ([com.playtranslate.camera.CameraRegionUi.showIndicator])
 * and by the Anki screenshot cell over its preview. Draw-only, not
 * touchable; [region] is in this view's px. BlurMaskFilter renders on the
 * hardware canvas (minSdk 30); this view must not be software-layered
 * (full-screen layer cap; see RegionOverlayController's label shadow note).
 */
class RegionIndicatorView(ctx: Context, region: Rect) : View(ctx) {
    private val region = Rect(region)
    private val dp = ctx.resources.displayMetrics.density

    /** Re-anchor in place (a zoom re-projection, a layout pass). */
    fun moveTo(r: Rect) {
        if (region == r) return
        region.set(r)
        invalidate()
    }

    private val accent = ctx.themeColor(R.attr.ptAccent)
    private val dimPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent))
        style = Paint.Style.STROKE
        strokeWidth = 12 * dp
        maskFilter = BlurMaskFilter(14 * dp, BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 13 * dp
        maskFilter = BlurMaskFilter(13 * dp, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val l = region.left.toFloat()
        val t = region.top.toFloat()
        val r = region.right.toFloat()
        val b = region.bottom.toFloat()
        canvas.withSave {
            clipOutRect(l, t, r, b)
            drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            val shadowOffset = shadowPaint.strokeWidth / 2f
            drawRect(l - shadowOffset, t - shadowOffset, r + shadowOffset, b + shadowOffset, shadowPaint)
            val glowOffset = glowPaint.strokeWidth / 2f
            drawRect(l - glowOffset, t - glowOffset, r + glowOffset, b + glowOffset, glowPaint)
        }
        val dashPx = REGION_DASH_DP * dp
        val gapPx = REGION_GAP_DP * dp
        drawScreenSpaceDashes(canvas, dashPaint, l, t, r, t, dashPx, gapPx)
        drawScreenSpaceDashes(canvas, dashPaint, r, t, r, b, dashPx, gapPx)
        drawScreenSpaceDashes(canvas, dashPaint, l, b, r, b, dashPx, gapPx)
        drawScreenSpaceDashes(canvas, dashPaint, l, t, l, b, dashPx, gapPx)
    }
}
