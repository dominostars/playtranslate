package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Pill-shaped overlay that magnifies the area of [sourceBitmap] under the
 * user's finger while the floating icon is being dragged. Owns its own
 * `TYPE_ACCESSIBILITY_OVERLAY` window — non-touchable, non-focusable, so it
 * never steals events from the dragged icon underneath.
 *
 * Position: centered horizontally on the finger, above by default. If the
 * pill would clip the top of the screen (status bar / notch territory),
 * flips below the finger.
 */
class MagnifierLens(
    private val ctx: Context,
    private val wm: WindowManager,
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val pillW = dp(220f)
    private val pillH = dp(110f)
    /** Distance in px between finger center and the near edge of the pill. */
    private val verticalMarginPx = dp(25f)
    private val zoom = 2f
    /** How far the lens may slide off the top of the screen before we flip
     *  it below the finger. Tolerating some clipping avoids a jarring flip
     *  the instant the lens touches the top edge. */
    private val topOverhangTolerancePx = pillH / 5

    private var lensView: LensView? = null
    private var params: WindowManager.LayoutParams? = null

    fun setBitmap(bitmap: Bitmap?) {
        lensView?.setSourceBitmap(bitmap)
    }

    /** Show the lens (creates the window on first call) or update its position
     *  for subsequent calls during the same drag. [fingerX]/[fingerY] are raw
     *  display coordinates. */
    fun show(fingerX: Int, fingerY: Int, screenW: Int, screenH: Int) {
        ensureWindow()
        val view = lensView ?: return
        val p = params ?: return

        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.visibility = View.VISIBLE

        val x = (fingerX - pillW / 2).coerceIn(0, (screenW - pillW).coerceAtLeast(0))
        val aboveY = fingerY - verticalMarginPx - pillH
        val y = if (aboveY >= -topOverhangTolerancePx) {
            aboveY
        } else {
            (fingerY + verticalMarginPx).coerceAtMost((screenH - pillH).coerceAtLeast(0))
        }
        if (p.x != x || p.y != y) {
            p.x = x
            p.y = y
            try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
        } else {
            view.invalidate()
        }
    }

    /** Hide the lens without releasing the window. Cheap to call repeatedly. */
    fun hide() {
        lensView?.visibility = View.INVISIBLE
    }

    /** Tear down the window entirely. Call once per drag (on drag end). */
    fun dismiss() {
        val view = lensView
        lensView = null
        params = null
        if (view != null) {
            PlayTranslateAccessibilityService.removeOverlay(view, wm)
        }
    }

    private fun ensureWindow() {
        if (lensView != null) return
        val view = LensView(ctx, pillW, pillH, zoom)
        val lp = WindowManager.LayoutParams(
            pillW, pillH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        if (!PlayTranslateAccessibilityService.addOverlay(view, wm, lp)) return
        lensView = view
        params = lp
    }

    private class LensView(
        ctx: Context,
        private val pillW: Int,
        private val pillH: Int,
        private val zoom: Float,
    ) : View(ctx) {
        private val borderPx = ctx.resources.displayMetrics.density * 2f
        private val cornerR = pillH / 2f

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.themeColor(R.attr.ptAccent).takeIf { it != 0 }
                ?: Color.parseColor("#4DD0C2")
            style = Paint.Style.STROKE
            strokeWidth = borderPx
        }
        // Painted under the zoom every frame so the parts of the lens that
        // would otherwise sample outside the source bitmap (finger near a
        // screen edge, or before the screenshot lands) read as solid black
        // instead of revealing the screen behind the lens window.
        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val borderRect = RectF()

        private var sourceBitmap: Bitmap? = null
        private var sourceX = 0f
        private var sourceY = 0f
        // Screen dimensions used as the in/out-screenshot boundary while the
        // bitmap hasn't landed yet. The boundary is the same as the bitmap's
        // own bounds once it arrives (screenshots are display-sized), so the
        // lens can mark off-screen regions black even before OCR runs.
        private var sourceScreenW = 0
        private var sourceScreenH = 0

        fun setSourceBitmap(bitmap: Bitmap?) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setSourcePoint(x: Float, y: Float, screenW: Int, screenH: Int) {
            sourceX = x
            sourceY = y
            sourceScreenW = screenW
            sourceScreenH = screenH
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = pillW.toFloat()
            val h = pillH.toFloat()

            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, w, h, cornerR, cornerR, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            val bitmap = sourceBitmap
            // Boundary is the bitmap once it arrives, otherwise the screen
            // size — they match for full-display screenshots, so the in/out
            // partition is consistent before and after the bitmap lands.
            val boundsW = if (bitmap != null && !bitmap.isRecycled) bitmap.width else sourceScreenW
            val boundsH = if (bitmap != null && !bitmap.isRecycled) bitmap.height else sourceScreenH

            val srcW = (pillW / zoom).toInt().coerceAtLeast(1)
            val srcH = (pillH / zoom).toInt().coerceAtLeast(1)
            val cx = sourceX.toInt()
            val cy = sourceY.toInt()
            val srcLeft = cx - srcW / 2
            val srcTop = cy - srcH / 2
            val srcRight = srcLeft + srcW
            val srcBottom = srcTop + srcH

            // Compute the in-screenshot slice (in source coords, then
            // mapped to dst). Anything outside this slice is "outside the
            // screenshot" and gets painted black; the in-slice stays
            // transparent so the live screen shows through until the
            // bitmap is ready, and shows the zoomed bitmap once it lands.
            val cSrcLeft = srcLeft.coerceAtLeast(0)
            val cSrcTop = srcTop.coerceAtLeast(0)
            val cSrcRight = srcRight.coerceAtMost(boundsW)
            val cSrcBottom = srcBottom.coerceAtMost(boundsH)

            val haveInSlice = cSrcLeft < cSrcRight && cSrcTop < cSrcBottom
            if (haveInSlice) {
                val srcWf = srcW.toFloat()
                val srcHf = srcH.toFloat()
                val dstInLeft = w * (cSrcLeft - srcLeft) / srcWf
                val dstInTop = h * (cSrcTop - srcTop) / srcHf
                val dstInRight = w * (cSrcRight - srcLeft) / srcWf
                val dstInBottom = h * (cSrcBottom - srcTop) / srcHf

                // Black out the four strips around the in-slice.
                if (dstInTop > 0f) canvas.drawRect(0f, 0f, w, dstInTop, backgroundPaint)
                if (dstInBottom < h) canvas.drawRect(0f, dstInBottom, w, h, backgroundPaint)
                if (dstInLeft > 0f) canvas.drawRect(0f, dstInTop, dstInLeft, dstInBottom, backgroundPaint)
                if (dstInRight < w) canvas.drawRect(dstInRight, dstInTop, w, dstInBottom, backgroundPaint)

                if (bitmap != null && !bitmap.isRecycled) {
                    srcRect.set(cSrcLeft, cSrcTop, cSrcRight, cSrcBottom)
                    dstRect.set(dstInLeft, dstInTop, dstInRight, dstInBottom)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                }
            } else {
                // Source rect entirely outside the screenshot — whole lens
                // is "outside" and reads as black.
                canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            }

            canvas.restore()

            val inset = borderPx / 2f
            borderRect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(borderRect, cornerR - inset, cornerR - inset, borderPaint)
        }
    }
}
