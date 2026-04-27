package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.OverlayColors
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R

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

    /** Set the small label drawn at the top-center of the lens. Pass null
     *  (or empty) to hide the label. The controller calls this from
     *  onDragMove with the surface text of the token under the finger so
     *  the user has a live readout of which word they're targeting. */
    fun setLabel(text: String?) {
        lensView?.setLabel(text)
    }

    /** Show the lens (creates the window on first call) or update its position
     *  for subsequent calls during the same drag. [fingerX]/[fingerY] are raw
     *  display coordinates. */
    fun show(fingerX: Int, fingerY: Int, screenW: Int, screenH: Int) {
        ensureWindow()
        val view = lensView ?: return
        val p = params ?: return

        val aboveY = fingerY - verticalMarginPx - pillH
        val flipped = aboveY < -topOverhangTolerancePx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH, flipped)
        view.visibility = View.VISIBLE

        val x = (fingerX - pillW / 2).coerceIn(0, (screenW - pillW).coerceAtLeast(0))
        val y = if (!flipped) {
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
        private val density = ctx.resources.displayMetrics.density
        private val borderPx = density * 2f
        private val cornerR = pillH / 2f

        // Overlay-context color resolution: themeColor(R.attr.pt*) is
        // unreliable from the accessibility service / floating-window
        // contexts because the Activity theme isn't applied. OverlayColors
        // reads the user's mode + accent directly from Prefs.
        private val accentColor = OverlayColors.accent(ctx)
        private val accentOnColor = OverlayColors.accentOn(ctx)

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
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

        // Inset drop-shadow stroke just inside the border. The stroke is
        // drawn while the round-rect clip is active, so its outer half is
        // clipped to the lens shape and the inner half blurs softly toward
        // the zoom — a "lens-depth" effect that recesses the magnified
        // content beneath the accent border.
        private val insetShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = density * 14f
            maskFilter = BlurMaskFilter(density * 8f, BlurMaskFilter.Blur.NORMAL)
        }
        private val insetShadowRect = RectF()
        private val insetShadowInset = density * 4f

        // Small accent-colored crosshair marking the focal point.
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = density * 1.5f
            strokeCap = Paint.Cap.ROUND
        }
        private val crosshairHalfLen = density * 6f

        // Top-center label showing the word currently under the finger.
        // Painted in the app's accent color with on-accent text — same
        // treatment used for accent-background buttons elsewhere.
        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentOnColor
            textSize = density * 30f
            textAlign = Paint.Align.CENTER
            // isFakeBoldText synthesizes bold by drawing each glyph with
            // a small stroke offset — works uniformly for Latin AND CJK,
            // unlike Typeface.BOLD or sans-serif-medium which often fall
            // back to regular-weight Noto Sans CJK because no native bold
            // CJK variant exists in the Android fallback chain.
            typeface = Typeface.DEFAULT
            isFakeBoldText = true
        }
        private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.FILL
        }
        // Label sits flush against the top of the lens. The lens's own
        // rounded clip naturally trims the label's top corners to follow
        // the pill's curvature.
        private val labelTopMargin = 0f
        private val labelPadX = density * 12f
        private val labelPadY = density * 2f
        // Soft cap on label width so we can ellipsize gracefully for long
        // compound words. Leaves a margin from each pill edge.
        private val labelMaxW: Float = pillW - density * 24f
        private val labelBgRect = RectF()
        // Reused so we don't allocate per frame.
        private val labelBgPath = Path()
        private val labelBgRadii = FloatArray(8)

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
        private var labelText: String? = null
        // True when the lens is positioned below the finger (because there
        // wasn't enough room above). The label moves to the bottom edge so
        // it stays on the side away from the finger.
        private var flipped = false

        init {
            // BlurMaskFilter (used by the inset shadow) is unreliable on
            // hardware-accelerated layers across devices; software layer
            // is the consistent path. Same convention as the region
            // indicator overlay elsewhere in the app.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setSourceBitmap(bitmap: Bitmap?) {
            sourceBitmap = bitmap
            invalidate()
        }

        fun setSourcePoint(x: Float, y: Float, screenW: Int, screenH: Int, flipped: Boolean) {
            sourceX = x
            sourceY = y
            sourceScreenW = screenW
            sourceScreenH = screenH
            if (this.flipped != flipped) {
                this.flipped = flipped
                invalidate()
                return
            }
            invalidate()
        }

        fun setLabel(text: String?) {
            val normalized = text?.takeIf { it.isNotEmpty() }
            if (labelText == normalized) return
            labelText = normalized
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

            // Inset drop shadow around the inside of the lens border.
            // The stroke is centered slightly inside the lens edge; the
            // outer half clips against the round-rect clip path while the
            // inner half blurs toward the zoom. Drawn before crosshair
            // and label so both render on top of the shadow.
            insetShadowRect.set(
                insetShadowInset,
                insetShadowInset,
                w - insetShadowInset,
                h - insetShadowInset,
            )
            canvas.drawRoundRect(
                insetShadowRect,
                cornerR - insetShadowInset,
                cornerR - insetShadowInset,
                insetShadowPaint,
            )

            // Crosshair at the lens center, accent-colored.
            val lensCx = w / 2f
            val lensCy = h / 2f
            canvas.drawLine(lensCx - crosshairHalfLen, lensCy, lensCx + crosshairHalfLen, lensCy, crosshairPaint)
            canvas.drawLine(lensCx, lensCy - crosshairHalfLen, lensCx, lensCy + crosshairHalfLen, crosshairPaint)

            // Word label, kept on the lens edge that points away from the
            // finger (top when the lens floats above, bottom when flipped
            // below). Drawn inside the round-rect clip so it can't escape
            // the lens shape, on top of the zoom so the text is readable.
            // The flat edge sits flush against the corresponding lens edge;
            // the opposite edge is rounded to look like a half-pill cap.
            val text = labelText
            if (text != null) {
                val fitted = TextUtils.ellipsize(
                    text, labelPaint, labelMaxW - 2 * labelPadX, TextUtils.TruncateAt.END,
                ).toString()
                if (fitted.isNotEmpty()) {
                    val fm = labelPaint.fontMetrics
                    val textHeight = fm.descent - fm.ascent
                    val textWidth = labelPaint.measureText(fitted)
                    val bgW = textWidth + 2 * labelPadX
                    val bgH = textHeight + 2 * labelPadY
                    val bgLeft = (w - bgW) / 2f
                    val bgRight = bgLeft + bgW
                    val bgTop = if (flipped) h - bgH - labelTopMargin else labelTopMargin
                    val bgBottom = bgTop + bgH
                    labelBgRect.set(bgLeft, bgTop, bgRight, bgBottom)
                    val r = bgH  // rounded-end radius = label height
                    if (flipped) {
                        // Flat bottom (against lens bottom edge), rounded top.
                        labelBgRadii[0] = r; labelBgRadii[1] = r        // top-left
                        labelBgRadii[2] = r; labelBgRadii[3] = r        // top-right
                        labelBgRadii[4] = 0f; labelBgRadii[5] = 0f      // bottom-right
                        labelBgRadii[6] = 0f; labelBgRadii[7] = 0f      // bottom-left
                    } else {
                        // Flat top (against lens top edge), rounded bottom.
                        labelBgRadii[0] = 0f; labelBgRadii[1] = 0f      // top-left
                        labelBgRadii[2] = 0f; labelBgRadii[3] = 0f      // top-right
                        labelBgRadii[4] = r; labelBgRadii[5] = r        // bottom-right
                        labelBgRadii[6] = r; labelBgRadii[7] = r        // bottom-left
                    }
                    labelBgPath.reset()
                    labelBgPath.addRoundRect(labelBgRect, labelBgRadii, Path.Direction.CW)
                    canvas.drawPath(labelBgPath, labelBgPaint)
                    val baseline = bgTop + labelPadY - fm.ascent
                    canvas.drawText(fitted, w / 2f, baseline, labelPaint)
                }
            }

            canvas.restore()

            val inset = borderPx / 2f
            borderRect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(borderRect, cornerR - inset, cornerR - inset, borderPaint)
        }
    }
}
