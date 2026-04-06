package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import com.playtranslate.R

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
        val isFurigana: Boolean = false,
        /** Marked when pinhole detection finds a minor change under this overlay. */
        val dirty: Boolean = false
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

    /** When true, dispatchDraw punches pinhole holes through all children. */
    var pinholeEnabled: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** Cached full-view pinhole mask bitmap. Created on size change, recycled on detach. */
    private var pinholeMaskBitmap: Bitmap? = null

    private val dstOutPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

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
        pinholeMaskBitmap?.recycle()
        pinholeMaskBitmap = if (w > 0 && h > 0) createPinholeMask(w, h) else null
        post { rebuildChildren() }
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
        if (!pinholeEnabled || mask == null) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(mask, 0f, 0f, dstOutPaint)
        canvas.restoreToCount(layer)
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
        val rebuildStart = System.currentTimeMillis()
        val textBoxCount = boxes.count { !it.isFurigana && it.translatedText.isNotEmpty() }
        if (textBoxCount > 0) {
            doOnLayout {
                android.util.Log.d("DetectionLog", "LAYOUT COMPLETE: ${System.currentTimeMillis() - rebuildStart}ms after rebuildChildren ($textBoxCount text boxes)")
            }
        }

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
                    OutlinedTextView(context).apply {
                        text = box.translatedText
                        setTextColor(box.textColor)
                        outlineColor = box.textColor xor 0x00FFFFFF  // invert RGB, keep alpha
                        outlineWidth = 1f * dp
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(textMargin, textMargin, textMargin, textMargin)
                        // Pinholes need opaque bg (pinholes handle transparency).
                        // Without pinholes, use native alpha (~224 = 88% opaque).
                        setBackgroundColor(if (pinholeEnabled) box.bgColor or 0xFF000000.toInt() else box.bgColor)
                        setTag(R.id.tag_bg_color, box.bgColor)
                        setTag(R.id.tag_dirty, box.dirty)
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

    /**
     * Build a full-view pinhole mask. Pinhole positions have alpha=128 (50%),
     * all other pixels are fully transparent. Drawn with DST_OUT in dispatchDraw
     * to punch 50% holes through all children.
     */
    private fun createPinholeMask(w: Int, h: Int): Bitmap {
        val spacing = PINHOLE_SPACING
        val pixels = IntArray(w * h) // all 0 = fully transparent
        for (y in 0 until h) {
            val rowGroup = (y / spacing) % 2
            val xOffset = if (rowGroup == 0) 0 else spacing / 2
            if (y % spacing != 0) continue
            var x = xOffset
            while (x < w) {
                pixels[y * w + x] = 0x80000000.toInt() // alpha=128, 50% transparent pinhole
                x += spacing
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Get the actual screen rects of all text box children.
     * Uses getLocationOnScreen for pixel-perfect positioning — no computed approximations.
     * Call after layout completes (doOnLayout).
     */
    fun getChildScreenRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        val location = IntArray(2)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_bg_color) == null) continue
            child.getLocationOnScreen(location)
            rects += Rect(location[0], location[1], location[0] + child.width, location[1] + child.height)
        }
        return rects
    }

    // ── Dirty overlay management ──────────────────────────────────────

    /** Mark a text-box child as dirty by its index among tagged children. */
    fun markChildDirty(index: Int) {
        var taggedIdx = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_bg_color) == null) continue
            if (taggedIdx == index) {
                child.setTag(R.id.tag_dirty, true)
                return
            }
            taggedIdx++
        }
    }

    /** Hide dirty children (INVISIBLE). Returns true if any were hidden. */
    fun hideDirtyChildren(): Boolean {
        var anyHidden = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_dirty) == true) {
                child.visibility = View.INVISIBLE
                anyHidden = true
            }
        }
        return anyHidden
    }

    /** Restore dirty children to VISIBLE. */
    fun restoreDirtyChildren() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_dirty) == true) {
                child.visibility = View.VISIBLE
            }
        }
    }

    /** Remove dirty children from the view and boxes list. Returns remaining boxes. */
    fun removeDirtyChildren(): List<TextBox> {
        val dirtyViewIndices = mutableListOf<Int>()
        val dirtyTaggedIndices = mutableSetOf<Int>()
        var taggedIdx = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_bg_color) == null) continue
            if (child.getTag(R.id.tag_dirty) == true) {
                dirtyViewIndices.add(i)
                dirtyTaggedIndices.add(taggedIdx)
            }
            taggedIdx++
        }
        if (dirtyViewIndices.isEmpty()) return boxes

        // Remove views in reverse order to preserve indices
        for (i in dirtyViewIndices.reversed()) {
            removeViewAt(i)
        }
        boxes = boxes.filterIndexed { idx, _ -> idx !in dirtyTaggedIndices }
        return boxes
    }

    /**
     * Render the overlay to an offscreen bitmap WITHOUT pinholes.
     * Returns the exact pixel-for-pixel content of the overlay (bg + text + outlines).
     * Used for pinhole change detection — provides the overlay_rendered term in:
     *   predicted = clean_ref * 0.5 + overlay_rendered * 0.5
     * Call after layout completes.
     */
    fun renderToOffscreen(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val wasPinholeEnabled = pinholeEnabled
        pinholeEnabled = false
        draw(canvas)
        pinholeEnabled = wasPinholeEnabled
        return bitmap
    }

    companion object {
        const val PINHOLE_SPACING = 3
    }

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
