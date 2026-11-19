package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.StaticLayout
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes

/**
 * A primary button of the floating controls: an icon over a bold label of at
 * most two lines, on a rounded fill, the label sized down until it fits
 * ([fitLabel]). The quick menu's Capture and Auto-translate
 * ([FloatingIconMenu]) are two of them, and the detached window narrowed to
 * its capture state is one that fills its window, so the three cannot drift
 * apart. The host decides what the button says and in which colors ([show]);
 * the button owns the shape.
 *
 * [fixedSizePx] is the side of a button its host sizes up front: the menu
 * reads its card's size before any layout, so the label must be fitted at
 * construction. Null for a button its parent sizes, whose label is fitted to
 * the width it is measured at.
 */
class PrimaryActionButton(
    context: Context,
    private val fixedSizePx: Int? = null,
) : LinearLayout(context) {

    private val dp = resources.displayMetrics.density

    /** Keeps the label off the button's rounded edges: wrap-content plus this
     *  padding caps the text at the button's width less twice this, so long
     *  (or localized) labels wrap instead of running to the corners. */
    private val labelSidePad = (LABEL_SIDE_PAD_DP * dp).toInt()

    /** Text column inside the button: its width less the label's side padding. */
    private var labelColumnPx = fixedSizePx?.let { it - 2 * labelSidePad } ?: 0

    private val fill = GradientDrawable().apply { cornerRadius = CORNER_DP * dp }

    private val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LayoutParams((ICON_DP * dp).toInt(), (ICON_DP * dp).toInt())
    }

    private val label = TextView(context).apply {
        textSize = LABEL_MAX_SP
        gravity = Gravity.CENTER
        maxLines = 2
        setTypeface(null, Typeface.BOLD)
        setPadding(labelSidePad, 0, labelSidePad, 0)
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (5 * dp).toInt() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = fill
        addView(icon)
        addView(label)
    }

    /** What the button says and how it looks: its glyph, its label (also its
     *  spoken name), the fill, the color of glyph and label, and a hairline
     *  around it when [strokeColor] is given. */
    fun show(
        @DrawableRes iconRes: Int,
        text: CharSequence,
        fillColor: Int,
        contentColor: Int,
        strokeColor: Int? = null,
    ) {
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(contentColor)
        label.text = text
        label.setTextColor(contentColor)
        contentDescription = text
        fill.setColor(fillColor)
        if (strokeColor != null) fill.setStroke((1 * dp).toInt(), strokeColor)
        else fill.setStroke(0, Color.TRANSPARENT)
        fitLabel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fixedSizePx == null) {
            val column = MeasureSpec.getSize(widthMeasureSpec) - 2 * labelSidePad
            if (column != labelColumnPx) {
                labelColumnPx = column
                fitLabel()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Sizes the label's text down from [LABEL_MAX_SP] until it renders within
     *  the button's two-line budget, laid out exactly as the `maxLines = 2`
     *  TextView will: wrapped at the real break opportunities — spaces and
     *  authored `\n` — inside [labelColumnPx]. A single [StaticLayout]
     *  measurement mirrors the view's own wrap, so one pass covers every case
     *  the labels hit:
     *
     *   - "Auto Translate" (one authored line, no `\n`) soft-wraps at its space
     *     onto the second rendered line and stays at full size. Measuring the
     *     whole phrase as one unbreakable run — as this once did — instead shrank
     *     it to cram onto a single line while the second line sat empty.
     *   - vi "Chụp\nmàn hình" (two authored lines) shrinks before line 2's
     *     "màn hình" has to wrap to a third line and drop "hình" past the cap.
     *   - a single long token with no legal break (ru "Автоперевод",
     *     th "อัตโนมัติ") overflows its one line and steps down rather than taking
     *     Android's mid-character split; this also absorbs a large font scale. */
    private fun fitLabel() {
        if (labelColumnPx <= 0) return
        val text = label.text?.toString().orEmpty()
        if (text.isBlank()) return
        var size = LABEL_MAX_SP
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        while (size > LABEL_MIN_SP && !labelFitsTwoLines(text)) {
            size -= 0.5f
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
    }

    /** True when [text] wraps to at most two lines within [labelColumnPx] and no
     *  line overflows it, measured at the label's current text size and
     *  mirroring the view's break/hyphenation settings so the fit matches what
     *  gets drawn. A line wider than the column is an unbreakable token (no
     *  space, no `\n`) that StaticLayout left whole — the signal to step the
     *  size down. */
    private fun labelFitsTwoLines(text: String): Boolean {
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, label.paint, labelColumnPx)
            .setBreakStrategy(label.breakStrategy)
            .setHyphenationFrequency(label.hyphenationFrequency)
            .build()
        if (layout.lineCount > 2) return false
        for (i in 0 until layout.lineCount) {
            if (layout.getLineWidth(i) > labelColumnPx) return false
        }
        return true
    }

    companion object {
        /** The button's corner radius. */
        const val CORNER_DP = 11f
        private const val ICON_DP = 26f
        private const val LABEL_SIDE_PAD_DP = 6f

        /** Resting text size of the label, and the floor [fitLabel] is allowed
         *  to shrink it to. */
        private const val LABEL_MAX_SP = 11f
        private const val LABEL_MIN_SP = 8.5f
    }
}
