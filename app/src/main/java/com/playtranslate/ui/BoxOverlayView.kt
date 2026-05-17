package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.widget.TextViewCompat
import com.playtranslate.PinholeCalibration
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation

/**
 * Renders a single [TextBox] as the content of its own overlay window. The
 * window is sized and positioned to the box's on-screen rect, so this view
 * always fills its window and the box content fills this view.
 *
 * The per-box-window counterpart of [TranslationOverlayView]: where that view
 * lays out N boxes as children inside one full-screen window, each translation
 * box now gets its own window with one of these as the content view. See the
 * "Per-Box Overlay Windows" plan. Furigana keeps using [TranslationOverlayView];
 * this view only handles translation boxes (text + skeleton, horizontal +
 * vertical).
 *
 * @param pinholeMode Fixed at construction. When true, [dispatchDraw] punches
 *   pinhole holes through the content and the box background is forced fully
 *   opaque so [com.playtranslate.PinholeOverlayMode]'s change-detection blend
 *   math holds. Cannot be toggled — recreate the view to change it.
 */
class BoxOverlayView(
    context: Context,
    val pinholeMode: Boolean = false,
) : FrameLayout(context) {

    init {
        clipChildren = false
        clipToPadding = false
    }

    private val dp = context.resources.displayMetrics.density
    private val minTextSizeSp = 6
    private val maxTextSizeSp = 200
    private val textMargin = (3f * dp).toInt()
    private val skeletonBarHeight = (8f * dp).toInt()
    private val skeletonCornerRadius = 3f * dp

    private var box: TextBox? = null
    private val skeletonBars = mutableListOf<View>()
    private var shimmerAnimator: ValueAnimator? = null

    /** Cached pinhole mask, sized to this view. Created on size change. */
    private var pinholeMaskBitmap: Bitmap? = null
    private val dstOutPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** Set (or replace) the box this window renders, and rebuild the content. */
    fun setBox(box: TextBox) {
        this.box = box
        if (width > 0 && height > 0) rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pinholeMaskBitmap?.recycle()
        pinholeMaskBitmap = if (w > 0 && h > 0) createPinholeMask(w, h) else null
        post { rebuild() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        pinholeMaskBitmap?.recycle()
        pinholeMaskBitmap = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val mask = pinholeMaskBitmap
        if (!pinholeMode || mask == null) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(mask, 0f, 0f, dstOutPaint)
        canvas.restoreToCount(layer)
    }

    private fun rebuild() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        skeletonBars.clear()
        removeAllViews()
        val box = this.box ?: return
        if (width <= 0 || height <= 0) return

        if (box.translatedText.isEmpty()) {
            // Skeleton placeholder fills the view.
            val skeleton = buildSkeletonView(
                width, height, box.lineCount, box.bgColor, box.textColor, box.alignment
            )
            addView(skeleton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            startShimmer()
            return
        }

        val child = OutlinedTextView(context).apply {
            text = box.translatedText
            setTextColor(box.textColor)
            outlineColor = box.textColor xor 0x00FFFFFF  // invert RGB, keep alpha
            outlineWidth = 1f * dp
            typeface = Typeface.DEFAULT_BOLD
            gravity = if (box.alignment == TextAlignment.CENTER)
                Gravity.CENTER
            else
                Gravity.CENTER_VERTICAL
            setPadding(textMargin, textMargin, textMargin, textMargin)
            // Pinhole detection needs an opaque background (the pinhole mask
            // supplies the transparency); without pinholes use the box's alpha.
            setBackgroundColor(if (pinholeMode) box.bgColor or 0xFF000000.toInt() else box.bgColor)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, minTextSizeSp, maxTextSizeSp, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

        if (box.orientation == TextOrientation.VERTICAL) {
            // Vertical text: lay the TextView out with swapped dimensions so
            // auto-sizing picks a readable font, then rotate 90° CW. This view
            // is the box, so centre the rotated child on the view's centre.
            addView(child, LayoutParams(height, width))
            child.rotation = 90f
            child.translationX = (width - height) / 2f
            child.translationY = (height - width) / 2f
        } else {
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun buildSkeletonView(
        boxW: Int, boxH: Int, lineCount: Int, bgColor: Int, barColor: Int,
        alignment: TextAlignment,
    ): View {
        val container = FrameLayout(context).apply { setBackgroundColor(bgColor) }
        val sideMargin = textMargin * 2
        val availW = boxW - sideMargin * 2
        for (line in 0 until lineCount) {
            val widthFraction = if (line == lineCount - 1 && lineCount > 1) 0.6f else 0.85f
            val barW = (availW * widthFraction).toInt().coerceAtLeast(1)
            val centerY = boxH * (line + 1) / (lineCount + 1)
            val barTop = centerY - skeletonBarHeight / 2
            val bar = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(barColor)
                    cornerRadius = skeletonCornerRadius
                }
            }
            skeletonBars.add(bar)
            val barLeft = if (alignment == TextAlignment.CENTER) {
                ((boxW - barW) / 2).coerceAtLeast(sideMargin)
            } else {
                sideMargin
            }
            container.addView(bar, FrameLayout.LayoutParams(barW, skeletonBarHeight).apply {
                leftMargin = barLeft
                topMargin = barTop
            })
        }
        return container
    }

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofFloat(0.8f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                for (bar in skeletonBars) bar.alpha = a
            }
            start()
        }
    }

    /**
     * Build a pinhole mask sized to this view: pinhole positions carry alpha
     * [PinholeCalibration.MASK_ALPHA], every other pixel is transparent.
     * Composited DST_OUT in [dispatchDraw] to punch partially-transparent holes.
     *
     * The grid is generated from this view's own (0,0). Pinhole detection
     * samples the same grid in box-local coordinates.
     */
    private fun createPinholeMask(w: Int, h: Int): Bitmap {
        val spacing = PinholeCalibration.PINHOLE_SPACING
        val pixels = IntArray(w * h) // all 0 = fully transparent
        for (y in 0 until h) {
            val rowGroup = (y / spacing) % 2
            val xOffset = if (rowGroup == 0) 0 else spacing / 2
            if (y % spacing != 0) continue
            var x = xOffset
            while (x < w) {
                pixels[y * w + x] = PinholeCalibration.MASK_PIXEL
                x += spacing
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Render the box content to an offscreen bitmap WITHOUT the pinhole mask —
     * the "overlay_rendered" reference for pinhole change detection. Returns
     * null until the view has been measured.
     */
    fun renderToOffscreen(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val time = drawingTime
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) drawChild(canvas, child, time)
        }
        return bitmap
    }

    /** True once this view and its content child have real measured dimensions
     *  and no layout pass is pending — pinhole detection must defer
     *  [renderToOffscreen] until then or it captures a stale/empty bitmap. */
    fun isBoxLaidOut(): Boolean {
        if (width <= 0 || height <= 0) return false
        if (isLayoutRequested) return false
        if (childCount == 0) return box == null
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.width <= 0 || c.height <= 0) return false
            if (c.isLayoutRequested) return false
        }
        return true
    }
}
