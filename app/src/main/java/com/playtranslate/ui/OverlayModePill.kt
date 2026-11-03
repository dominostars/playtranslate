package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.roundToInt

/**
 * The capture's overlay-mode control, floating over the game display while
 * a capture holds it: expanded, a bar of the segments of every mode the
 * capture offers ([buildPillToggle]) with an X at the end; collapsed by that
 * X, a disc with the selected mode's glyph that a tap expands again. It is
 * panel chrome that floats because the over-game panel parks itself out of
 * reach, so it lives in the host's root above the panel; the host routes
 * touches inside its bounds to it and places it under the status bar.
 *
 * Both states are one capsule, which this view draws and clips its content
 * to. The bar is measured whole in either state, so the disc is the bar's
 * height, and as wide. A tap on the X or on the disc morphs one into the
 * other: the capsule's width runs between the disc's and the bar's, its
 * contents held to its end edge, where the host pins it and where the X
 * and the disc both sit; the bar keeps its size and is clipped, never
 * squeezed; the two contents fade through, so they never show or take a
 * tap together. [render] sets a state outright.
 *
 * Idempotent: [render] rebuilds only when the options, the selection or the
 * collapsed state changed, so a host may call it from every refresh without
 * cutting a segment's slide or the morph short.
 */
internal class OverlayModePill(context: Context) : FrameLayout(context) {

    /** A segment was picked. The pill shows it selected at once. */
    var onSelect: ((OverlayMode) -> Unit)? = null

    /** The user collapsed the control with its X, or expanded it from the disc. */
    var onCollapsedChanged: ((collapsed: Boolean) -> Unit)? = null

    private var options: List<Pair<String, OverlayMode>> = emptyList()
    private var selected: OverlayMode? = null

    /** Collapsed to the disc. A tap flips it at once and the morph follows. */
    var collapsed: Boolean = false
        private set

    /** Where the capsule stands, from the disc (0) to the bar (1): the
     *  collapsed state's end whenever no morph is running. */
    private var reveal = 1f
    private var morph: ValueAnimator? = null

    private val dp = resources.displayMetrics.density

    private val capsule = GradientDrawable().apply {
        setColor(context.themeColor(R.attr.ptElevated))
        setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
    }

    /** The segments and the X; null while nothing is offered. */
    private var bar: View? = null

    /** The disc's glyph and its tap target: a square the bar's height. */
    private val glyph = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptText))
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { setCollapsedByTap(false) }
    }

    init {
        background = capsule
        // Mid-morph the bar runs past the capsule's start edge.
        clipToOutline = true
    }

    /** Show [options] with [selected] marked, [collapsed] or expanded, with
     *  no morph; an empty [options] hides the control. */
    fun render(options: List<Pair<String, OverlayMode>>, selected: OverlayMode, collapsed: Boolean) {
        val contentChanged = options != this.options || selected != this.selected
        if (!contentChanged && collapsed == this.collapsed) return
        this.options = options
        this.selected = selected
        if (collapsed != this.collapsed) {
            this.collapsed = collapsed
            settle()
        }
        if (contentChanged) {
            removeAllViews()
            bar = null
            if (options.isNotEmpty()) {
                bar = buildBar(options, selected).also { addView(it) }
                addView(glyph)
                showGlyph(selected)
            }
        }
        if (bar == null) {
            settle()
            visibility = View.GONE
        } else {
            visibility = View.VISIBLE
            applyReveal()
        }
    }

    /** The user's tap on the X (collapse) or on the disc: report it and
     *  morph there from wherever the capsule stands. */
    private fun setCollapsedByTap(collapsed: Boolean) {
        if (collapsed == this.collapsed) return
        this.collapsed = collapsed
        morph?.cancel()
        morph = ValueAnimator.ofFloat(reveal, if (collapsed) 0f else 1f).apply {
            duration = MORPH_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                reveal = it.animatedValue as Float
                applyReveal()
            }
            start()
        }
        onCollapsedChanged?.invoke(collapsed)
    }

    /** Puts the capsule at the collapsed state's end, a morph or not. */
    private fun settle() {
        morph?.cancel()
        morph = null
        reveal = if (collapsed) 0f else 1f
    }

    /** The contents for [reveal], and a relayout for its width. They fade
     *  through, the glyph over the disc's end of the morph and the bar over
     *  the rest: the glyph sits where the X does. */
    private fun applyReveal() {
        val bar = bar ?: return
        val barAlpha = ((reveal - FADE_SPLIT) / (1f - FADE_SPLIT)).coerceIn(0f, 1f)
        val glyphAlpha = (1f - reveal / FADE_SPLIT).coerceIn(0f, 1f)
        bar.alpha = barAlpha
        bar.visibility = if (barAlpha > 0f) View.VISIBLE else View.INVISIBLE
        glyph.alpha = glyphAlpha
        glyph.visibility = if (glyphAlpha > 0f) View.VISIBLE else View.INVISIBLE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bar = bar
        if (bar == null) {
            setMeasuredDimension(0, 0)
            return
        }
        // Whole whatever the capsule's width: its labels keep their size
        // through a morph, and its height is the disc's. Only a screen too
        // narrow for it makes it give way, collapsed or not.
        bar.measure(
            ViewGroup.getChildMeasureSpec(widthMeasureSpec, 0, LayoutParams.WRAP_CONTENT),
            ViewGroup.getChildMeasureSpec(heightMeasureSpec, 0, LayoutParams.WRAP_CONTENT),
        )
        val h = bar.measuredHeight
        val pad = (h - (GLYPH_DP * dp).roundToInt()) / 2
        glyph.setPadding(pad, pad, pad, pad)
        val square = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        glyph.measure(square, square)
        // A circle at the disc, the bar's own corners at the bar.
        val radius = h / 2f + (ROW_RADIUS_DP * dp - h / 2f) * reveal
        if (capsule.cornerRadius != radius) capsule.cornerRadius = radius
        setMeasuredDimension((h + (bar.measuredWidth - h) * reveal).roundToInt(), h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bar = bar ?: return
        val w = right - left
        val h = bottom - top
        // Both at the end edge, which the host pins: the capsule grows from
        // the disc and shrinks into it there.
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val barStart = if (rtl) 0 else w - bar.measuredWidth
        bar.layout(barStart, 0, barStart + bar.measuredWidth, h)
        val glyphStart = if (rtl) 0 else w - h
        glyph.layout(glyphStart, 0, glyphStart + h, h)
    }

    private fun showGlyph(mode: OverlayMode) {
        glyph.setImageResource(glyphOf(mode))
        glyph.contentDescription = context.getString(
            R.string.capture_overlay_pill_collapsed_cd,
            options.firstOrNull { it.second == mode }?.first ?: "",
        )
    }

    /** The segments and the X, padded inside the capsule, which is their
     *  background. */
    private fun buildBar(options: List<Pair<String, OverlayMode>>, selected: OverlayMode): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (6 * dp).toInt()
            val padV = (4 * dp).toInt()
            setPadding(padH, padV, padH, padV)
        }
        val toggleHost = FrameLayout(context).apply {
            // Wide enough for every label at its full size, and no narrower
            // than the segments always were. On a screen too narrow for it
            // the weight gives way, so the X stays on screen and the labels
            // shrink as they always could.
            layoutParams = LinearLayout.LayoutParams(
                maxOf((SEGMENT_DP * dp).toInt() * options.size, pillToggleFitWidthPx(context, options.map { it.first })),
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        buildPillToggle(toggleHost, options, selected) { mode ->
            this.selected = mode
            showGlyph(mode)
            onSelect?.invoke(mode)
        }
        row.addView(toggleHost)
        val closeSize = (32 * dp).toInt()
        val closePad = (6 * dp).toInt()
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(closeSize, closeSize).apply {
                marginStart = (4 * dp).toInt()
            }
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptTextMuted))
            setPadding(closePad, closePad, closePad, closePad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.capture_overlay_pill_collapse_cd)
            isClickable = true
            isFocusable = true
            setOnClickListener { setCollapsedByTap(true) }
        })
        return row
    }

    companion object {
        const val ROW_RADIUS_DP = 20f
        /** Each segment's least width in the expanded control; longer
         *  labels widen it ([pillToggleFitWidthPx]). */
        const val SEGMENT_DP = 78
        /** The disc's glyph, the size the X draws at. */
        private const val GLYPH_DP = 20
        private const val MORPH_MS = 250L
        /** Where along the morph the glyph has faded out and the bar starts
         *  to fade in. */
        private const val FADE_SPLIT = 0.4f

        fun glyphOf(mode: OverlayMode): Int = when (mode) {
            OverlayMode.TRANSLATION -> R.drawable.ic_translate
            OverlayMode.FURIGANA -> R.drawable.ic_furigana
            OverlayMode.DEFINITIONS -> R.drawable.ic_dictionary
            OverlayMode.NONE -> R.drawable.ic_visibility_off
        }
    }
}
