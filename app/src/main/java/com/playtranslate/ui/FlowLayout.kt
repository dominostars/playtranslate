package com.playtranslate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.MarginLayoutParams

/**
 * Minimal flow container: lays children out left-to-right and wraps to a
 * new line when the next child (including its margins) would overflow the
 * available width. Honours each child's [MarginLayoutParams], so callers
 * keep using `marginStart` / `marginEnd` for inter-pill spacing exactly as
 * they did inside the horizontal [android.widget.LinearLayout] this
 * replaces, and children are centred vertically within each line.
 *
 * Exists because the project has no Flexbox dependency and the badge rows
 * (Common pill, star rating, "in Anki" pill) must wrap to a second line on
 * narrow widths / long deck names rather than clip.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Extra vertical gap inserted between wrapped lines (px). */
    var lineSpacingPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        // When the parent imposes no width bound, never wrap.
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else widthSize - paddingLeft - paddingRight

        var lineWidth = 0          // width used by the current line
        var lineHeight = 0         // tallest child (incl. margins) on the line
        var maxLineWidth = 0       // widest completed line
        var totalHeight = 0        // accumulated height of completed lines

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            // Resolve start/end margins into left/right for the current
            // direction — children set marginStart (XML + programmatic), which
            // otherwise stays unreflected in leftMargin. Also makes RTL correct.
            lp.resolveLayoutDirection(layoutDirection)
            val hMargins = lp.leftMargin + lp.rightMargin
            val vMargins = lp.topMargin + lp.bottomMargin
            val childWidthSpec = ViewGroup.getChildMeasureSpec(
                widthMeasureSpec, paddingLeft + paddingRight + hMargins, lp.width,
            )
            val childHeightSpec = ViewGroup.getChildMeasureSpec(
                heightMeasureSpec, paddingTop + paddingBottom + vMargins, lp.height,
            )
            child.measure(childWidthSpec, childHeightSpec)
            val outerW = child.measuredWidth + hMargins
            val outerH = child.measuredHeight + vMargins

            if (lineWidth > 0 && lineWidth + outerW > available) {
                // Wrap: bank the finished line, start a new one.
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                totalHeight += lineHeight + lineSpacingPx
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += outerW
            lineHeight = maxOf(lineHeight, outerH)
        }
        maxLineWidth = maxOf(maxLineWidth, lineWidth)
        totalHeight += lineHeight

        val resolvedWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            else -> maxLineWidth + paddingLeft + paddingRight
        }
        val resolvedHeight = totalHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolvedWidth,
            View.resolveSize(resolvedHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        // Buffer the current line so its children can be centred vertically
        // within the line height — badges with backgrounds are taller than the
        // reading/stars text they sit beside, and top-aligning looks off.
        val lineViews = ArrayList<View>()
        val lineXs = ArrayList<Int>()

        fun flushLine() {
            for (i in lineViews.indices) {
                val c = lineViews[i]
                val lp = c.layoutParams as MarginLayoutParams
                val outerH = c.measuredHeight + lp.topMargin + lp.bottomMargin
                val top = y + (lineHeight - outerH) / 2 + lp.topMargin
                val left = lineXs[i] + lp.leftMargin
                c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            }
            lineViews.clear()
            lineXs.clear()
        }

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            lp.resolveLayoutDirection(layoutDirection)
            val outerW = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val outerH = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (x > paddingLeft && x - paddingLeft + outerW > available) {
                flushLine()
                x = paddingLeft
                y += lineHeight + lineSpacingPx
                lineHeight = 0
            }
            lineViews.add(child)
            lineXs.add(x)
            x += outerW
            lineHeight = maxOf(lineHeight, outerH)
        }
        flushLine()
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams =
        MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is MarginLayoutParams
}
