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
    /** Distance in px between finger center and the near edge of the pill.
     *  Sized to clear the floating icon (≈80dp diameter, half = 40dp). */
    private val verticalMarginPx = dp(50f)
    private val zoom = 2f

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

        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat())
        view.visibility = View.VISIBLE

        val x = (fingerX - pillW / 2).coerceIn(0, (screenW - pillW).coerceAtLeast(0))
        val aboveY = fingerY - verticalMarginPx - pillH
        val y = if (aboveY >= 0) {
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
            try { wm.removeView(view) } catch (_: Exception) {}
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
        try { wm.addView(view, lp) } catch (_: Exception) { return }
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
        private val placeholderPaint = Paint().apply {
            color = Color.parseColor("#33000000")
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

        fun setSourceBitmap(bitmap: Bitmap?) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setSourcePoint(x: Float, y: Float) {
            sourceX = x
            sourceY = y
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
            if (bitmap != null && !bitmap.isRecycled) {
                val srcW = (pillW / zoom).toInt().coerceAtLeast(1)
                val srcH = (pillH / zoom).toInt().coerceAtLeast(1)
                val cx = sourceX.toInt()
                val cy = sourceY.toInt()
                srcRect.set(
                    cx - srcW / 2,
                    cy - srcH / 2,
                    cx - srcW / 2 + srcW,
                    cy - srcH / 2 + srcH,
                )
                dstRect.set(0f, 0f, w, h)
                if (srcRect.right > 0 && srcRect.bottom > 0 &&
                    srcRect.left < bitmap.width && srcRect.top < bitmap.height
                ) {
                    canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
                } else {
                    canvas.drawRect(0f, 0f, w, h, placeholderPaint)
                }
            } else {
                canvas.drawRect(0f, 0f, w, h, placeholderPaint)
            }

            canvas.restore()

            val inset = borderPx / 2f
            borderRect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(borderRect, cornerR - inset, cornerR - inset, borderPaint)
        }
    }
}
