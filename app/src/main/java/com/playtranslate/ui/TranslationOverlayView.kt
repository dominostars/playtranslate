package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat

/**
 * Transparent overlay that positions auto-sizing TextViews inside bounding
 * boxes on the game screen during live mode. Each box corresponds to an OCR
 * text group and is filled with a semi-transparent background so the
 * translated text is readable over game graphics. Font size auto-scales
 * via Android's built-in [TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration].
 *
 * When boxes have empty [TextBox.translatedText], skeleton placeholder lines
 * are shown with a pulsing animation until text arrives via a subsequent
 * [setBoxes] call.
 */
class TranslationOverlayView(context: Context) : FrameLayout(context) {

    data class TextBox(
        val translatedText: String,
        /** Bounding box in original bitmap pixel coordinates. */
        val bounds: Rect,
        /** Average color of the game content behind this box (ARGB). */
        val bgColor: Int = Color.argb(200, 0, 0, 0),
        /** Text color — estimated from game text or chosen for contrast. */
        val textColor: Int = Color.WHITE,
        /** Number of OCR lines in this group (for skeleton placeholders). */
        val lineCount: Int = 1,
        /** True for furigana readings (smaller text, pill background). */
        val isFurigana: Boolean = false
    )

    private val dp = context.resources.displayMetrics.density

    private val minTextSizeSp = 6
    private val maxTextSizeSp = 200
    private val boxPadding = 6f * dp
    /** Small inset so text doesn't touch the edges of the background. */
    private val textMargin = (3f * dp).toInt()
    private val skeletonBarHeight = (8f * dp).toInt()
    private val skeletonBarGap = (4f * dp).toInt()
    private val skeletonCornerRadius = 3f * dp

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var screenshotW = 1
    private var screenshotH = 1

    private var shimmerAnimator: ValueAnimator? = null

    fun setBoxes(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        // Skip rebuild if content is identical (avoids flash on false-positive recaptures)
        if (this.boxes == boxes && cropOffsetX == cropLeft && cropOffsetY == cropTop
            && this.screenshotW == screenshotW && this.screenshotH == screenshotH) return

        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) {
            updateScales()
            rebuildChildren()
        }
    }

    /** Remove specific boxes by content match. Removes only the corresponding
     *  child views — surviving children stay in place with no rebuild. */
    fun removeBoxesByContent(toRemove: List<TextBox>) {
        if (toRemove.isEmpty()) return
        val removeSet = toRemove.toSet()
        for (i in (childCount - 1) downTo 0) {
            if (i < boxes.size && boxes[i] in removeSet) {
                removeViewAt(i)
            }
        }
        boxes = boxes.filter { it !in removeSet }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScales()
        post { rebuildChildren() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun updateScales() {
        displayScaleX = width.toFloat() / screenshotW
        displayScaleY = height.toFloat() / screenshotH
    }

    private fun mapRect(r: Rect): RectF {
        val left   = (r.left   + cropOffsetX) * displayScaleX
        val top    = (r.top    + cropOffsetY) * displayScaleY
        val right  = (r.right  + cropOffsetX) * displayScaleX
        val bottom = (r.bottom + cropOffsetY) * displayScaleY
        return RectF(left, top, right, bottom)
    }

    private fun rebuildChildren() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        skeletonBars.clear()
        removeAllViews()
        if (boxes.isEmpty()) return

        val displayW = width.toFloat()
        val displayH = height.toFloat()

        // Map OCR bounds to screen coordinates
        val screenRects = boxes.map { box ->
            val r = mapRect(box.bounds)
            if (box.isFurigana) {
                // Furigana: exact position, no padding or overlap adjustment
                RectF(r.left, r.top, r.right, r.bottom)
            } else {
                RectF(
                    (r.left - boxPadding).coerceAtLeast(0f),
                    (r.top - boxPadding).coerceAtLeast(0f),
                    (r.right + boxPadding).coerceAtMost(displayW),
                    (r.bottom + boxPadding).coerceAtMost(displayH)
                )
            }
        }

        // Resolve vertical overlaps for non-furigana boxes only
        val finalRects = screenRects.map { RectF(it) }
        val nonFuriganaIndices = boxes.indices.filter { !boxes[it].isFurigana }
            .sortedBy { finalRects[it].top }
        for (a in nonFuriganaIndices.indices) {
            for (b in a + 1 until nonFuriganaIndices.size) {
                val i = nonFuriganaIndices[a]
                val j = nonFuriganaIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        val hasPlaceholders = boxes.any { it.translatedText.isEmpty() }

        boxes.zip(finalRects).forEach { (box, rect) ->
            if (box.isFurigana) {
                // Furigana: outlined text, no box constraint, sized from line height
                val textSizePx = (rect.height() * 0.7f).coerceAtLeast(4f)
                val strokeW = 3f * dp
                val strokePad = (strokeW / 2f + 0.5f).toInt()
                val child = OutlinedTextView(context).apply {
                    text = box.translatedText
                    setTextColor(Color.WHITE)
                    outlineColor = Color.BLACK
                    outlineWidth = strokeW
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setPadding(strokePad, strokePad, strokePad, strokePad)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                }
                addView(child, LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ))
                // Position after measurement but before draw — no (0,0) flash
                child.doOnLayout {
                    child.translationX = rect.left - strokePad
                    child.translationY = rect.bottom - child.measuredHeight
                }
            } else {
                val rectW = rect.width().toInt().coerceAtLeast(1)
                val rectH = rect.height().toInt().coerceAtLeast(1)

                val child: View = if (box.translatedText.isEmpty()) {
                    buildSkeletonView(rectW, rectH, box.lineCount, box.bgColor, box.textColor)
                } else {
                    TextView(context).apply {
                        text = box.translatedText
                        setTextColor(box.textColor)
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(textMargin, textMargin, textMargin, textMargin)
                        setBackgroundColor(box.bgColor)
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this, minTextSizeSp, maxTextSizeSp, 1, TypedValue.COMPLEX_UNIT_SP
                        )
                    }
                }

                val lp = LayoutParams(rectW, rectH).apply {
                    leftMargin = rect.left.toInt()
                    topMargin = rect.top.toInt()
                }
                addView(child, lp)
            }
        }

        if (hasPlaceholders) startShimmer()
    }

    /** Builds a skeleton placeholder with [lineCount] bars evenly spaced within the box. */
    private fun buildSkeletonView(boxW: Int, boxH: Int, lineCount: Int, bgColor: Int, barColor: Int): View {
        val container = FrameLayout(context).apply {
            setBackgroundColor(bgColor)
        }

        val sideMargin = textMargin * 2
        val availW = boxW - sideMargin * 2

        for (line in 0 until lineCount) {
            val widthFraction = if (line == lineCount - 1 && lineCount > 1) 0.6f else 0.85f
            val barW = (availW * widthFraction).toInt().coerceAtLeast(1)

            // Evenly distribute: bar centers at boxH*(i+1)/(N+1)
            val centerY = boxH * (line + 1) / (lineCount + 1)
            val barTop = centerY - skeletonBarHeight / 2

            val bar = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(barColor)
                    cornerRadius = skeletonCornerRadius
                }
            }
            skeletonBars.add(bar)
            val barLp = LayoutParams(barW, skeletonBarHeight).apply {
                leftMargin = sideMargin
                topMargin = barTop
            }
            container.addView(bar, barLp)
        }

        return container
    }

    private val skeletonBars = mutableListOf<View>()

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofFloat(0.8f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                for (bar in skeletonBars) {
                    bar.alpha = a
                }
            }
            start()
        }
    }

    /** TextView that draws a stroke outline behind the text for readability without a background. */
    private class OutlinedTextView(context: android.content.Context) : TextView(context) {
        var outlineColor: Int = Color.argb(220, 34, 34, 34)
        var outlineWidth: Float = 0f

        override fun onDraw(canvas: Canvas) {
            if (outlineWidth > 0f) {
                val savedColor = currentTextColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outlineWidth
                paint.strokeJoin = Paint.Join.ROUND
                setTextColor(outlineColor)
                super.onDraw(canvas)
                paint.style = Paint.Style.FILL
                setTextColor(savedColor)
            }
            super.onDraw(canvas)
        }
    }
}
