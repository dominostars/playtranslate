package com.playtranslate.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.playtranslate.R
import com.playtranslate.language.TextOrientation

/**
 * Transparent overlay that positions outlined furigana TextViews above the
 * base text on the game screen during live mode. Font size is fixed per box
 * from the OCR bounds; vertical furigana stacks characters with newlines.
 *
 * Furigana-only: every [TextBox] it receives has `isFurigana` set — its sole
 * construction site is `OverlayUiController.showFuriganaOverlay`, reached only
 * when `boxes.all { it.isFurigana }`. The per-box translation path uses
 * `BoxOverlayView` windows instead.
 */
class TranslationOverlayView(
    context: Context,
) : FrameLayout(context) {

    init {
        clipChildren = false
        clipToPadding = false
    }

    private val dp = context.resources.displayMetrics.density

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var screenshotW = 1
    private var screenshotH = 1

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
            && OverlayLayout.boxesMatchFuzzy(this.boxes, boxes)) return

        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) {
            rebuildChildren()
        }
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
        post { rebuildChildren() }
    }

    private fun rebuildChildren() {
        removeAllViews()
        if (boxes.isEmpty()) return

        val finalRects = OverlayLayout.resolveScreenRects(
            boxes, cropOffsetX, cropOffsetY, screenshotW, screenshotH, width, height, dp
        )

        // Every box here is furigana (see the class KDoc).
        boxes.zip(finalRects).forEach { (box, rect) ->
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
        }
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
}
