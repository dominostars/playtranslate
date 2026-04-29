package com.playtranslate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * A horizontal flow `ViewGroup` that lays children left-to-right and wraps to
 * the next row when the next child would exceed the available width. Used for
 * the accent-color picker so the swatches reflow naturally based on cell width
 * (no fixed grid). Children are not stretched — each is measured `WRAP_CONTENT`.
 */
class WrappingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ViewGroup(context, attrs, defStyle) {

    /** Horizontal gap between adjacent children in the same row, in px. */
    var horizontalSpacingPx: Int = 0
        set(value) { field = value; requestLayout() }

    /** Vertical gap between rows, in px. */
    var verticalSpacingPx: Int = 0
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)

        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0
        var rowMaxWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            // Honor each child's own LayoutParams (e.g. fixed-size swatch FrameLayouts).
            val lp = child.layoutParams ?: generateDefaultLayoutParams()
            val childWidthSpec = getChildMeasureSpec(
                widthMeasureSpec, paddingLeft + paddingRight, lp.width
            )
            val childHeightSpec = getChildMeasureSpec(
                heightMeasureSpec, paddingTop + paddingBottom, lp.height
            )
            child.measure(childWidthSpec, childHeightSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            val gap = if (rowWidth == 0) 0 else horizontalSpacingPx
            if (rowWidth + gap + cw > maxWidth && rowWidth > 0) {
                totalHeight += rowHeight + verticalSpacingPx
                rowMaxWidth = maxOf(rowMaxWidth, rowWidth)
                rowWidth = cw
                rowHeight = ch
            } else {
                rowWidth += gap + cw
                rowHeight = maxOf(rowHeight, ch)
            }
        }
        totalHeight += rowHeight
        rowMaxWidth = maxOf(rowMaxWidth, rowWidth)

        val resolvedWidth = resolveSize(rowMaxWidth + paddingLeft + paddingRight, widthMeasureSpec)
        val resolvedHeight = resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = (r - l) - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            val gap = if (x == paddingLeft) 0 else horizontalSpacingPx
            if (x + gap + cw > paddingLeft + maxWidth && x > paddingLeft) {
                x = paddingLeft
                y += rowHeight + verticalSpacingPx
                rowHeight = 0
            } else {
                x += gap
            }
            child.layout(x, y, x + cw, y + ch)
            x += cw
            rowHeight = maxOf(rowHeight, ch)
        }
    }
}
