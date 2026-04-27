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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.playtranslate.OverlayColors
import com.playtranslate.PlayTranslateAccessibilityService

/**
 * Rounded-rect magnifier overlay shown while the floating icon is dragged.
 * Owns a `TYPE_ACCESSIBILITY_OVERLAY` window — non-touchable, non-focusable,
 * so it never steals events from the dragged icon underneath.
 *
 * Layout: split horizontally into a fixed-width accent-colored left panel
 * showing the word/reading currently under the finger (mirroring the
 * dictionary popup's left column), and a transparent right panel that
 * holds the zoom and the focal-point crosshair. Dimensions and proportions
 * intentionally match the dictionary popup so the lens during drag and
 * the popup after release feel like the same UI element.
 *
 * Position: the right panel's center is placed over the finger so the
 * crosshair stays at the actual focal point. Above the finger by default;
 * flips below if the lens would overrun the top of the screen.
 */
class MagnifierLens(
    private val ctx: Context,
    private val wm: WindowManager,
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val lensH = dp(120f)
    private val cornerR = dp(18f).toFloat()

    /** Distance in px between finger center and the near edge of the lens. */
    private val verticalMarginPx = dp(25f)
    private val zoom = 2f
    /** How far the lens may slide off the top of the screen before we flip
     *  it below the finger. Tolerating some clipping avoids a jarring flip
     *  the instant the lens touches the top edge. */
    private val topOverhangTolerancePx = lensH / 5

    private var lensView: LensView? = null
    private var params: WindowManager.LayoutParams? = null

    fun setBitmap(bitmap: Bitmap?) {
        lensView?.setSourceBitmap(bitmap)
    }

    /** Set the word + reading shown in the lens's left panel. Pass null
     *  for either to hide that line. The controller calls this from
     *  onDragMove with the surface text of the token under the finger. */
    fun setLabel(word: String?, reading: String?) {
        lensView?.setLabel(word, reading)
    }

    /** Lens width tracks WordLookupPopup's `baseW` (no open-button column —
     *  the lens has no buttons on the right, just zoom). The popup subtracts
     *  dp(24) for its root FrameLayout's 12dp+12dp horizontal padding before
     *  splitting; the lens has no such padding, so left-panel width is a
     *  flat 25% of lensW.
     *    lensW = min(screenW × 0.85, dp(360))
     *    leftW = lensW × 0.25
     *  Returns (lensW, leftPanelW) — both in pixels. */
    private fun lensDimensions(screenW: Int): Pair<Int, Int> {
        val lensW = (screenW * 0.85f).toInt().coerceAtMost(dp(360f))
        val leftW = (lensW * 0.25f).toInt()
        return lensW to leftW
    }

    /** Show the lens (creates the window on first call) or update its
     *  position. The window is anchored so the lens's center is over the
     *  finger — the crosshair lives at that center, so the visual focal
     *  point stays on the actual finger position. */
    fun show(fingerX: Int, fingerY: Int, screenW: Int, screenH: Int) {
        val (lensW, leftPanelW) = lensDimensions(screenW)
        ensureWindow(lensW, leftPanelW)
        val view = lensView ?: return
        val p = params ?: return

        val aboveY = fingerY - verticalMarginPx - lensH
        val flipped = aboveY < -topOverhangTolerancePx
        view.setSourcePoint(fingerX.toFloat(), fingerY.toFloat(), screenW, screenH)
        view.visibility = View.VISIBLE

        val x = (fingerX - lensW / 2).coerceIn(0, (screenW - lensW).coerceAtLeast(0))
        val y = if (!flipped) {
            aboveY
        } else {
            (fingerY + verticalMarginPx).coerceAtMost((screenH - lensH).coerceAtLeast(0))
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

    private fun ensureWindow(lensW: Int, leftPanelW: Int) {
        if (lensView != null) return
        val view = LensView(ctx, lensW, lensH, leftPanelW, cornerR, zoom)
        val lp = WindowManager.LayoutParams(
            lensW, lensH,
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

    /**
     * The lens is a FrameLayout so the word/reading text can be hosted as
     * real TextViews — letting the framework handle SP sizing, autoSize,
     * ellipsize, and centering instead of reimplementing them with paint /
     * StaticLayout. The custom canvas work (zoom, inset shadow, crosshair,
     * border) lives in onDraw / draw around the children.
     */
    private class LensView(
        ctx: Context,
        private val lensW: Int,
        private val lensH: Int,
        leftPanelW: Int,
        private val cornerR: Float,
        private val zoom: Float,
    ) : FrameLayout(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val borderPx = density * 2f

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
        // Painted under the zoom every frame so the parts of the right
        // panel that sample outside the source bitmap (finger near a
        // screen edge, or before the screenshot lands) read as solid
        // black instead of revealing the screen behind the lens window.
        private val backgroundPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Inset drop-shadow stroke just inside the border. The stroke is
        // drawn while the round-rect clip is active (see [draw]), so its
        // outer half is clipped to the lens shape and the inner half blurs
        // softly toward the content — a "lens-depth" effect that recesses
        // both panels beneath the accent border.
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

        // Reading + word TextViews mirror the dictionary popup's left-column
        // properties exactly: SP sizes via TypedValue.COMPLEX_UNIT_SP, SP-
        // granularity autoSize on the word, framework gravity / ellipsize.
        // Colors swapped to on-accent (the lens panel is accent colored, not
        // the popup's #242424); reading uses ~75% alpha to recreate the
        // popup's #EFEFEF / #A0A0A0 contrast hierarchy.
        private val readingView = TextView(ctx).apply {
            setTextColor((accentOnColor and 0x00FFFFFF) or (0xC0 shl 24))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        private val wordView = TextView(ctx).apply {
            setTextColor(accentOnColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(
                10, 22, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }
        // Left column hosts the labels and supplies the accent fill via its
        // background — the panel and labels appear/disappear together by
        // toggling its visibility, no separate canvas pass for the fill.
        private val leftCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(accentColor)
            addView(readingView)
            addView(wordView)
            visibility = GONE
            layoutParams = LayoutParams(
                leftPanelW,
                LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        }

        private val clipPath = Path()
        private val srcRect = Rect()
        private val dstRect = RectF()
        private val borderRect = RectF()

        private var sourceBitmap: Bitmap? = null
        private var sourceX = 0f
        private var sourceY = 0f
        // Screen dimensions used as the in/out-screenshot boundary while
        // the bitmap hasn't landed yet. The boundary is the same as the
        // bitmap's own bounds once it arrives (screenshots are display-
        // sized), so the lens can mark off-screen regions black even
        // before OCR runs.
        private var sourceScreenW = 0
        private var sourceScreenH = 0

        init {
            // BlurMaskFilter (used by the inset shadow) is unreliable on
            // hardware-accelerated layers across devices; software layer
            // is the consistent path. Same convention as the region
            // indicator overlay elsewhere in the app.
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            // FrameLayout sets willNotDraw based on background/foreground;
            // we paint zoom + shadow in onDraw, so we need it cleared.
            setWillNotDraw(false)
            addView(leftCol)
            clipPath.addRoundRect(
                0f, 0f, lensW.toFloat(), lensH.toFloat(),
                cornerR, cornerR, Path.Direction.CW,
            )
        }

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

        fun setLabel(word: String?, reading: String?) {
            val w = word?.takeIf { it.isNotEmpty() }
            val r = reading?.takeIf { it.isNotEmpty() }
            wordView.text = w.orEmpty()
            readingView.text = r.orEmpty()
            readingView.visibility = if (r == null) GONE else VISIBLE
            leftCol.visibility = if (w == null) GONE else VISIBLE
        }

        // Below: the accent-colored left panel and its labels are drawn by
        // dispatchDraw (children) inside [draw]; onDraw only handles the
        // zoom + inset shadow that sit underneath them.
        override fun onDraw(canvas: Canvas) {
            val w = lensW.toFloat()
            val h = lensH.toFloat()

            drawZoom(canvas, w, h)

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
        }

        /** Wraps the entire draw pass (background, onDraw, children) in
         *  the rounded-rect clip, then paints the crosshair and border
         *  on top of the restored canvas — they need to sit visually
         *  above both the zoom and the left panel labels. */
        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipPath(clipPath)
            super.draw(canvas)
            canvas.restore()

            val crosshairCx = lensW / 2f
            val crosshairCy = lensH / 2f
            canvas.drawLine(
                crosshairCx - crosshairHalfLen, crosshairCy,
                crosshairCx + crosshairHalfLen, crosshairCy, crosshairPaint,
            )
            canvas.drawLine(
                crosshairCx, crosshairCy - crosshairHalfLen,
                crosshairCx, crosshairCy + crosshairHalfLen, crosshairPaint,
            )

            val inset = borderPx / 2f
            borderRect.set(inset, inset, lensW - inset, lensH - inset)
            canvas.drawRoundRect(
                borderRect,
                cornerR - inset, cornerR - inset,
                borderPaint,
            )
        }

        /** Renders the zoom (with black underlay for off-screenshot regions)
         *  across the full lens dimensions. Source is centered on the
         *  finger position; dst is the full lens. */
        private fun drawZoom(canvas: Canvas, w: Float, h: Float) {
            val bitmap = sourceBitmap
            val boundsW = if (bitmap != null && !bitmap.isRecycled) bitmap.width else sourceScreenW
            val boundsH = if (bitmap != null && !bitmap.isRecycled) bitmap.height else sourceScreenH

            val srcW = (w / zoom).toInt().coerceAtLeast(1)
            val srcH = (h / zoom).toInt().coerceAtLeast(1)
            val cx = sourceX.toInt()
            val cy = sourceY.toInt()
            val srcLeft = cx - srcW / 2
            val srcTop = cy - srcH / 2
            val srcRight = srcLeft + srcW
            val srcBottom = srcTop + srcH

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
                canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            }
        }
    }
}
