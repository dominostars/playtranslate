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
import com.playtranslate.PinholeCalibration
import com.playtranslate.R
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation

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
/**
 * @param pinholeMode Fixed at construction. When true, [dispatchDraw] punches
 *   pinhole holes through all children and [rebuildChildren] forces child
 *   backgrounds to full opacity so [PinholeOverlayMode]'s change-detection
 *   math (`predicted = clean_ref * 0.5 + overlay_rendered * 0.5` at pinhole
 *   pixels) holds. Pinhole mode cannot be toggled on an existing view —
 *   creating a new view is required, which matches the lifecycle anyway
 *   since each `LiveMode` class tears down and recreates the overlay on
 *   start/stop.
 */
class TranslationOverlayView(
    context: Context,
    val pinholeMode: Boolean = false,
) : FrameLayout(context) {

    init {
        clipChildren = false
        clipToPadding = false
    }

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
        val dirty: Boolean = false,
        /** Original OCR source text this overlay translates. Used for content-based matching. */
        val sourceText: String = "",
        /** Text orientation — vertical boxes render with 90° CW rotated text. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        /** Block alignment for horizontal boxes — drives skeleton bar placement
         *  and translated-text gravity. Ignored for vertical boxes. */
        val alignment: TextAlignment = TextAlignment.LEFT
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

        // Skip rebuild if only bounds jittered within tolerance (OCR noise)
        if (cropOffsetX == cropLeft && cropOffsetY == cropTop
            && this.screenshotW == screenshotW && this.screenshotH == screenshotH
            && boxesMatchFuzzy(this.boxes, boxes)) return

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

    /** Fuzzy comparison: same text content, bounds within tolerance (OCR jitter). */
    private fun boxesMatchFuzzy(
        a: List<TextBox>, b: List<TextBox>, tolerance: Int = 20
    ): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val ba = a[i]; val bb = b[i]
            if (ba.translatedText != bb.translatedText) return false
            if (ba.isFurigana != bb.isFurigana) return false
            if (ba.sourceText != bb.sourceText) return false
            if (ba.orientation != bb.orientation) return false
            if (ba.alignment != bb.alignment) return false
            val ra = ba.bounds; val rb = bb.bounds
            if (Math.abs(ra.left - rb.left) > tolerance ||
                Math.abs(ra.top - rb.top) > tolerance ||
                Math.abs(ra.right - rb.right) > tolerance ||
                Math.abs(ra.bottom - rb.bottom) > tolerance) return false
        }
        return true
    }

    /** Remove specific boxes by content match (text + bounds). Removes only the
     *  corresponding child views — surviving children stay in place with no rebuild. */
    fun removeBoxesByContent(toRemove: List<TextBox>) {
        if (toRemove.isEmpty()) return
        fun matches(a: TextBox, b: TextBox) = a.translatedText == b.translatedText && a.bounds == b.bounds
        for (i in (childCount - 1) downTo 0) {
            if (i < boxes.size && toRemove.any { matches(boxes[i], it) }) {
                removeViewAt(i)
            }
        }
        boxes = boxes.filter { box -> !toRemove.any { matches(box, it) } }
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
        if (!pinholeMode || mask == null) {
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

        // Resolve vertical overlaps for non-furigana horizontal boxes
        val finalRects = screenRects.map { RectF(it) }
        val hBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].orientation != TextOrientation.VERTICAL
        }.sortedBy { finalRects[it].top }
        for (a in hBoxIndices.indices) {
            for (b in a + 1 until hBoxIndices.size) {
                val i = hBoxIndices[a]
                val j = hBoxIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        // Resolve horizontal overlaps for non-furigana vertical boxes
        // (adjacent columns whose overlay boxes overlap on the X axis).
        // Sort by right edge descending (right-to-left reading order).
        val vBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].orientation == TextOrientation.VERTICAL
        }.sortedByDescending { finalRects[it].right }
        for (a in vBoxIndices.indices) {
            for (b in a + 1 until vBoxIndices.size) {
                val i = vBoxIndices[a]
                val j = vBoxIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                if (ri.left < rj.right && ri.top < rj.bottom && ri.bottom > rj.top) {
                    val mid = (ri.left + rj.right) / 2f
                    ri.left = mid
                    rj.right = mid
                }
            }
        }

        val hasPlaceholders = boxes.any { it.translatedText.isEmpty() }

        boxes.zip(finalRects).forEach { (box, rect) ->
            if (box.isFurigana) {
                val isVerticalFurigana = box.orientation == TextOrientation.VERTICAL
                // Vertical furigana: size from box width; horizontal: from box height
                val textSizePx = if (isVerticalFurigana) {
                    (rect.width() * 0.7f).coerceAtLeast(4f)
                } else {
                    (rect.height() * 0.7f).coerceAtLeast(4f)
                }
                val strokeW = 3f * dp
                val strokePad = (strokeW / 2f + 0.5f).toInt()
                // Vertical: stack characters top-to-bottom with newlines
                val displayText = if (isVerticalFurigana) {
                    box.translatedText.toList().joinToString("\n")
                } else {
                    box.translatedText
                }
                val child = OutlinedTextView(context).apply {
                    text = displayText
                    setTextColor(Color.WHITE)
                    outlineColor = Color.BLACK
                    outlineWidth = strokeW
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setShadowLayer(strokeW, 0f, 0f, Color.TRANSPARENT)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                    if (isVerticalFurigana) {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        setPadding(strokePad, 0, strokePad, 0)
                        setLineSpacing(0f, 0.8f)
                    } else {
                        setPadding(strokePad, strokePad, strokePad, strokePad)
                    }
                }
                child.setTag(R.id.tag_bg_color, Color.BLACK)
                addView(child, LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                ))
                // Position after measurement but before draw — no (0,0) flash
                child.doOnLayout {
                    if (isVerticalFurigana) {
                        // Vertical: align to top of OCR box, no Y offset
                        child.translationX = rect.left - strokePad
                        child.translationY = rect.top
                    } else {
                        child.translationX = rect.left - strokePad
                        child.translationY = (rect.bottom - child.measuredHeight).coerceAtLeast(0f)
                    }
                }
            } else {
                val rectW = rect.width().toInt().coerceAtLeast(1)
                val rectH = rect.height().toInt().coerceAtLeast(1)
                val isVertical = box.orientation == TextOrientation.VERTICAL

                val child: View = if (box.translatedText.isEmpty()) {
                    buildSkeletonView(rectW, rectH, box.lineCount, box.bgColor, box.textColor, box.alignment)
                } else {
                    OutlinedTextView(context).apply {
                        text = box.translatedText
                        setTextColor(box.textColor)
                        outlineColor = box.textColor xor 0x00FFFFFF  // invert RGB, keep alpha
                        outlineWidth = 1f * dp
                        typeface = Typeface.DEFAULT_BOLD
                        // Vertical text is rotated at the FrameLayout level —
                        // horizontal alignment of the inner TextView still maps
                        // to horizontal screen alignment after rotation, so we
                        // only apply CENTER when the source group classified as
                        // such. Vertical boxes always classify LEFT in OcrManager,
                        // so no extra orientation gate is needed here.
                        gravity = if (box.alignment == TextAlignment.CENTER)
                            Gravity.CENTER
                        else
                            Gravity.CENTER_VERTICAL
                        setPadding(textMargin, textMargin, textMargin, textMargin)
                        // Pinholes need opaque bg (pinholes handle transparency).
                        // Without pinholes, use native alpha (~224 = 88% opaque).
                        setBackgroundColor(if (pinholeMode) box.bgColor or 0xFF000000.toInt() else box.bgColor)
                        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                            this, minTextSizeSp, maxTextSizeSp, 1, TypedValue.COMPLEX_UNIT_SP
                        )
                    }
                }

                child.setTag(R.id.tag_bg_color, box.bgColor)

                if (isVertical && box.translatedText.isNotEmpty()) {
                    // Vertical text: create view with swapped dimensions (width=rectH,
                    // height=rectW) so auto-sizing picks a readable font, then rotate
                    // 90° CW so text reads top-to-bottom in the original narrow box.
                    val lp = LayoutParams(rectH, rectW)
                    addView(child, lp)
                    child.rotation = 90f
                    // Position so the visual center aligns with the original box center.
                    // After rotation, the (rectH × rectW) layout visually becomes (rectW × rectH).
                    child.translationX = rect.centerX() - rectH / 2f
                    child.translationY = rect.centerY() - rectW / 2f
                } else {
                    val lp = LayoutParams(rectW, rectH).apply {
                        leftMargin = rect.left.toInt()
                        topMargin = rect.top.toInt()
                    }
                    addView(child, lp)
                }
            }
        }

        if (hasPlaceholders) startShimmer()
    }

    /** Builds a skeleton placeholder with [lineCount] bars evenly spaced within the box.
     *  When [alignment] is [TextAlignment.CENTER], bars are horizontally centered
     *  within the box so the short last-row bar visually reflects center-aligned
     *  source text rather than dropping to the left edge. */
    private fun buildSkeletonView(
        boxW: Int, boxH: Int, lineCount: Int, bgColor: Int, barColor: Int,
        alignment: TextAlignment = TextAlignment.LEFT,
    ): View {
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
            val barLeft = if (alignment == TextAlignment.CENTER) {
                ((boxW - barW) / 2).coerceAtLeast(sideMargin)
            } else {
                sideMargin
            }
            val barLp = LayoutParams(barW, skeletonBarHeight).apply {
                leftMargin = barLeft
                topMargin = barTop
            }
            container.addView(bar, barLp)
        }

        return container
    }

    private val skeletonBars = mutableListOf<View>()

    /**
     * Build a full-view pinhole mask. Pinhole positions have alpha
     * [PinholeCalibration.MASK_ALPHA], all other pixels are fully transparent.
     * Drawn with DST_OUT in dispatchDraw to punch partially-transparent holes
     * through all children.
     *
     * See [PinholeCalibration] for why the mask alpha and spacing are
     * tightly coupled to `PinholeOverlayMode.checkPinholes` — editing them
     * here without re-tuning the detection thresholds silently breaks
     * pinhole detection.
     *
     * **Scale note:** The mask is generated at VIEW resolution, with pinhole
     * positions on a fixed [PinholeCalibration.PINHOLE_SPACING]-pixel grid
     * in view coordinates. Pinhole detection assumes the mask spacing is
     * also valid in screenshot-bitmap coordinates, which requires view dims
     * == screenshot dims (identity scale). At non-identity scale the sparse
     * mask pattern is smeared by bitmap downsampling and the
     * `predicted = (ref + overlay) / 2` math no longer holds. See
     * [com.playtranslate.FrameCoordinates] KDoc for the full explanation.
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
     * Get the actual screen rects of all text box children.
     * Uses getLocationOnScreen for pixel-perfect positioning — no computed approximations.
     * For rotated children (vertical text overlays), uses the visual bounds
     * from the transformation matrix rather than the layout width/height.
     * Call after layout completes (doOnLayout).
     */
    fun getChildScreenRects(): List<Rect> {
        val rects = mutableListOf<Rect>()
        val location = IntArray(2)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.getTag(R.id.tag_bg_color) == null) continue
            if (child.rotation != 0f) {
                // Rotated child: compute visual bounds via the hit rect,
                // which accounts for rotation/translation transforms.
                val hitRect = android.graphics.Rect()
                child.getHitRect(hitRect)
                // getHitRect returns parent-relative coords; offset to screen
                getLocationOnScreen(location)
                hitRect.offset(location[0], location[1])
                rects += hitRect
            } else {
                child.getLocationOnScreen(location)
                rects += Rect(location[0], location[1], location[0] + child.width, location[1] + child.height)
            }
        }
        return rects
    }

    /**
     * True iff this view AND every child currently has measured dimensions
     * and no layout pass is pending. Callers (PinholeOverlayMode) poll this
     * to defer [renderToOffscreen] until the freshly-rebuilt children have
     * actually been laid out — without this gate, pinhole detection captures
     * an empty/stale overlay bitmap on the cycle right after a teardown,
     * then over-flags every box for REMOVE on the following cycle.
     */
    fun areChildrenLaidOut(): Boolean {
        if (width <= 0 || height <= 0) return false
        if (isLayoutRequested) return false
        if (childCount == 0) return boxes.isEmpty()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.width <= 0 || c.height <= 0) return false
            if (c.isLayoutRequested) return false
        }
        return true
    }

    /**
     * Render the overlay to an offscreen bitmap WITHOUT pinholes.
     * Returns the exact pixel-for-pixel content of the overlay (bg + text +
     * outlines), at the view's current dimensions. Used for pinhole change
     * detection — provides the overlay_rendered term in:
     *   predicted = clean_ref * 0.5 + overlay_rendered * 0.5
     * Call after layout completes.
     *
     * **Scale assumption:** the output is at **view dimensions**. Pinhole
     * detection assumes view dims == screenshot dims (identity scale).
     * See [com.playtranslate.FrameCoordinates] KDoc and
     * [com.playtranslate.PinholeOverlayMode.checkPinholes] for why
     * non-identity scale is not a supported configuration; the live modes
     * fail-closed at non-identity before calling this.
     */
    fun renderToOffscreen(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Draw each child directly. This deliberately sidesteps our own
        // [dispatchDraw] override (which would punch pinhole holes when
        // [pinholeMode] is true) so the resulting bitmap is the "what the
        // overlay would look like without holes" reference used by
        // [PinholeOverlayMode.checkPinholes]. This view never uses custom
        // z-order, disappearing-child animations, or `getChildDrawingOrder`,
        // so iterating in child index order is equivalent to
        // `super.dispatchDraw` minus the mask.
        val drawingTime = drawingTime
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == VISIBLE) {
                drawChild(canvas, child, drawingTime)
            }
        }
        return bitmap
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
