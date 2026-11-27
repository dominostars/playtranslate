package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.playtranslate.OverlayMode
import com.playtranslate.R
import com.playtranslate.language.HintTextKind
import com.playtranslate.themeColor
import kotlin.math.roundToInt

/**
 * The capture's overlay-mode control, floating over the game display while
 * a capture holds it: expanded, a bar of the segments of every mode the
 * capture offers ([buildPillToggle]) with an X at the end; collapsed by that
 * X, a disc with the selected mode's glyph that a tap expands again. It is
 * panel chrome that floats over the game: the one-screen sheet hosts it in
 * its root above the panel, which parks itself out of reach, and on two
 * screens the game display's window hosts it above the boxes, the app's
 * results page being the panel there. A host routes touches inside its
 * bounds to it and pins it where [hostLayoutParams] and [placeUnder] say.
 *
 * Both states are one capsule, a pill at every width, which this view
 * draws and clips its content to. The bar is measured whole in either
 * state, so the disc is the bar's height, and as wide. A tap on the X or
 * on the disc morphs one into the other: the capsule's width runs between
 * the disc's and the bar's, its contents held to its end edge, where the
 * host pins it and where the X and the disc both sit; the bar keeps its
 * size and is clipped, never squeezed; the two contents fade through, so
 * they never show or take a tap together. [render] sets a collapsed state
 * outright.
 *
 * While the capture is still reading ([render]'s loading) the pill is the
 * disc whatever the user left it as, holding a spinner where the glyph
 * goes, and nothing in it takes a touch, a bar still closing included.
 * When the read ends the spinner gives way to
 * the glyph, or the capsule opens to the bar if that is how the user left
 * it; a read that starts again takes it back to the disc the same way.
 *
 * It fades in whenever it comes up and fades out when it goes: for want of
 * anything to offer ([render] with no options) or as its host's capture
 * closes ([leave]), and a bar closes to the disc as it fades.
 *
 * A controller picks with the shoulder buttons, through the host's key
 * path: [step] picks the segment beside the selected one as a tap on it
 * would, collapsed or not.
 *
 * Idempotent: [render] rebuilds only when the options, the selection, the
 * collapsed state or the loading state changed, so a host may call it from
 * every refresh without cutting a segment's slide or the morph short.
 */
internal class OverlayModePill(context: Context) : FrameLayout(context) {

    /** A segment was picked. The pill shows it selected at once. */
    var onSelect: ((OverlayMode) -> Unit)? = null

    /** The user collapsed the control with its X, or expanded it from the disc. */
    var onCollapsedChanged: ((collapsed: Boolean) -> Unit)? = null

    private var options: List<Pair<String, OverlayMode>> = emptyList()
    private var selected: OverlayMode? = null

    /** Collapsed to the disc by the user. A tap flips it at once and the
     *  morph follows. */
    var collapsed: Boolean = false
        private set

    /** The capture is still reading: the disc with the spinner, whatever
     *  [collapsed] says, and nothing in the pill takes a touch
     *  ([takesNoTouch]). */
    private var loading = false

    /** Where the capsule stands, from the disc (0) to the bar (1): the
     *  state's end whenever no morph is running. */
    private var reveal = 1f

    /** What the disc holds, from the mode's glyph (0) to the spinner (1). */
    private var spin = 0f
    private var morph: ValueAnimator? = null

    /** Fading out ([leave]): nothing in it takes a touch ([takesNoTouch]). */
    private var leaving = false

    private val dp = resources.displayMetrics.density

    private val capsule = GradientDrawable().apply {
        setColor(context.themeColor(R.attr.ptElevated))
        setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptDivider))
    }

    /** The segments and the X; null while nothing is offered. */
    private var bar: View? = null

    /** The bar's segments, for a pick without a touch ([step]); null with
     *  no bar. */
    private var toggle: PillToggle? = null

    /** The disc's glyph and its tap target: a square the bar's height. */
    private val glyph = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptText))
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { setCollapsedByTap(false) }
    }

    /** The disc's sign that the capture is still reading, where the glyph
     *  goes and at its size. Invisible outside loading, which also stops
     *  it: nothing spins over the game once a result is up. */
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptText))
    }

    init {
        background = capsule
        // Mid-morph the bar runs past the capsule's start edge.
        clipToOutline = true
        // Nothing to show until a render: the first one fades it in.
        visibility = View.GONE
    }

    /** Show [options] with [selected] marked: the disc with its spinner
     *  while [loading], else [collapsed] or expanded. A pill that was not
     *  up fades in as asked for; one that is up morphs into and out of
     *  loading, and takes a change of [collapsed] alone with no morph. An
     *  empty [options] fades the control out ([leave]). */
    fun render(
        options: List<Pair<String, OverlayMode>>,
        selected: OverlayMode,
        collapsed: Boolean,
        loading: Boolean = false,
    ) {
        if (options.isEmpty()) {
            if (this.options.isNotEmpty()) {
                this.options = options
                leave()
            }
            return
        }
        val contentChanged = options != this.options || selected != this.selected
        val collapsedChanged = collapsed != this.collapsed
        val loadingChanged = loading != this.loading
        if (!contentChanged && !collapsedChanged && !loadingChanged) return
        // Not up, or on its way out: it comes up as asked for.
        val appearing = bar == null || leaving
        this.options = options
        this.selected = selected
        this.collapsed = collapsed
        this.loading = loading
        if (contentChanged) {
            removeAllViews()
            bar = buildBar(options, selected).also { addView(it) }
            addView(glyph)
            addView(spinner)
            showGlyph(selected)
        }
        val revealTo = if (loading || collapsed) 0f else 1f
        val spinTo = if (loading) 1f else 0f
        when {
            appearing -> {
                settleAt(revealTo, spinTo)
                fadeIn()
            }
            loadingChanged -> morphTo(revealTo, spinTo)
            collapsedChanged -> settleAt(revealTo, spinTo)
            else -> applyReveal()
        }
    }

    /** [render] with each of [modes] under its name for a source language
     *  whose reading hint is [hint]: the one way every host names them. */
    fun renderModes(
        modes: List<OverlayMode>,
        hint: HintTextKind,
        selected: OverlayMode,
        collapsed: Boolean,
        loading: Boolean = false,
    ) = render(modes.map { context.getString(it.labelRes(hint)) to it }, selected, collapsed, loading)

    /** Fade out over [durationMs], a bar closing to the disc on the way,
     *  then hold nothing: with nothing left to offer, or as the host's
     *  capture closes. What the user left it as is not touched and nothing
     *  is reported. A render with something to show brings it back. */
    fun leave(durationMs: Long = FADE_MS) {
        if (bar == null || leaving) return
        leaving = true
        morphTo(0f, spin, durationMs)
        animate().cancel()
        animate().alpha(0f).setDuration(durationMs).withEndAction { drop() }.start()
    }

    /** The end of [leave]: gone, and nothing held to measure or draw. */
    private fun drop() {
        morph?.cancel()
        morph = null
        removeAllViews()
        bar = null
        toggle = null
        options = emptyList()
        leaving = false
        visibility = View.GONE
    }

    /** Up from nothing, or back from a [leave] cut short. */
    private fun fadeIn() {
        leaving = false
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(FADE_MS).start()
    }

    /** No touch reaches the contents while the capture reads or the pill
     *  is on its way out: a bar still closing for either would otherwise
     *  take a pick with no result to paint it on, and a segment moves its
     *  highlight before it reports. The pill takes the touch itself, so it
     *  does not reach what the host has under it either, and a tap already
     *  down on a segment when either begins is cancelled there (the
     *  interception's own doing) and ends as nothing. */
    private val takesNoTouch: Boolean get() = loading || leaving

    /** A [step] would pick something: the pill is up and taking touches,
     *  with more than one mode on offer. */
    val canStep: Boolean
        get() = toggle != null && !takesNoTouch && options.size > 1

    /** Pick the segment beside the selected one on screen, [toRight] of it
     *  or left of it, the far end's past either end (the shoulder buttons,
     *  R1 and L1), as a tap on that segment picks it: expanded, the
     *  highlight slides there; collapsed, the disc takes its glyph; either
     *  way [onSelect] reports it. Collapsed or expanded stays as the user
     *  left it. Under a right-to-left layout the row starts at the right, so
     *  the segment to the right is the one before. Nothing unless it
     *  [canStep]. */
    fun step(toRight: Boolean) {
        val toggle = toggle ?: return
        if (!canStep) return
        val forward = toRight != (layoutDirection == View.LAYOUT_DIRECTION_RTL)
        toggle.select((toggle.selectedIndex + if (forward) 1 else -1).mod(options.size))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = takesNoTouch

    override fun onTouchEvent(ev: MotionEvent): Boolean = takesNoTouch || super.onTouchEvent(ev)

    /** Keep the pill [TOP_DP] under [topInsetPx], the top the host's
     *  content keeps clear (the status bar or a cutout), from its insets
     *  listener. Laid out by [hostLayoutParams]. */
    fun placeUnder(topInsetPx: Int) {
        val lp = layoutParams as? MarginLayoutParams ?: return
        val top = topInsetPx + (TOP_DP * dp).toInt()
        if (lp.topMargin == top) return
        lp.topMargin = top
        requestLayout()
    }

    /** The user's tap on the X (collapse) or on the disc: report it and
     *  morph there from wherever the capsule stands. Nothing while loading:
     *  the disc is the spinner's then. */
    private fun setCollapsedByTap(collapsed: Boolean) {
        if (loading || collapsed == this.collapsed) return
        this.collapsed = collapsed
        morphTo(if (collapsed) 0f else 1f, 0f)
        onCollapsedChanged?.invoke(collapsed)
    }

    /** Morph from wherever the pill stands to [revealTo] and [spinTo]. A
     *  capsule that changes width takes [shapeMs] and holds one thing in
     *  its disc throughout, the spinner if either end shows it, so the
     *  glyph never flashes on the way into or out of loading; a disc that
     *  stays a disc swaps its content in [SWAP_MS]. */
    private fun morphTo(revealTo: Float, spinTo: Float, shapeMs: Long = MORPH_MS) {
        morph?.cancel()
        morph = null
        val revealFrom = reveal
        val spinFrom = spin
        if (revealFrom == revealTo && spinFrom == spinTo) return
        val shape = revealFrom != revealTo
        val held = maxOf(spinFrom, spinTo)
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (shape) shapeMs else SWAP_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                reveal = if (t >= 1f) revealTo else revealFrom + (revealTo - revealFrom) * t
                spin = when {
                    t >= 1f -> spinTo
                    shape -> held
                    else -> spinFrom + (spinTo - spinFrom) * t
                }
                applyReveal()
            }
            start()
        }
    }

    /** Puts the pill at [revealTo] and [spinTo] outright, a morph or not. */
    private fun settleAt(revealTo: Float, spinTo: Float) {
        morph?.cancel()
        morph = null
        reveal = revealTo
        spin = spinTo
        applyReveal()
    }

    /** The contents for [reveal] and [spin], and a relayout for the width.
     *  They fade through: the disc's content over the disc's end of the
     *  morph and the bar over the rest (the disc's content sits where the X
     *  does), and in the disc the glyph over its half of [spin] and the
     *  spinner over the other. */
    private fun applyReveal() {
        val bar = bar ?: return
        val barAlpha = ((reveal - FADE_SPLIT) / (1f - FADE_SPLIT)).coerceIn(0f, 1f)
        val discAlpha = (1f - reveal / FADE_SPLIT).coerceIn(0f, 1f)
        show(bar, barAlpha)
        show(glyph, discAlpha * (1f - 2f * spin).coerceIn(0f, 1f))
        show(spinner, discAlpha * (2f * spin - 1f).coerceIn(0f, 1f))
        requestLayout()
    }

    /** [view] at [alpha]; one faded out takes no touch, focus or reading. */
    private fun show(view: View, alpha: Float) {
        view.alpha = alpha
        view.visibility = if (alpha > 0f) View.VISIBLE else View.INVISIBLE
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
        val drawn = MeasureSpec.makeMeasureSpec((GLYPH_DP * dp).roundToInt(), MeasureSpec.EXACTLY)
        spinner.measure(drawn, drawn)
        // A pill at every width: round ends the bar's height, so the disc
        // is a circle and the bar's ends are concentric with the track's.
        val radius = h / 2f
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
        // The spinner in the middle of the disc, where the glyph draws.
        val inset = (h - spinner.measuredWidth) / 2
        spinner.layout(glyphStart + inset, inset, glyphStart + inset + spinner.measuredWidth, inset + spinner.measuredHeight)
    }

    private fun showGlyph(mode: OverlayMode) {
        glyph.setImageResource(glyphOf(mode))
        glyph.contentDescription = context.getString(
            R.string.capture_overlay_pill_collapsed_cd,
            options.firstOrNull { it.second == mode }?.first ?: "",
        )
    }

    /** The segments and the X inside the capsule, which is their background:
     *  one inset all round, so the track sits in the capsule's start end
     *  and the X's square, the track's height, in its other end, each
     *  concentric with it. */
    private fun buildBar(options: List<Pair<String, OverlayMode>>, selected: OverlayMode): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inset = (INSET_DP * dp).toInt()
            setPadding(inset, inset, inset, inset)
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
        toggle = buildPillToggle(toggleHost, options, selected, roundEnds = true) { mode ->
            this.selected = mode
            showGlyph(mode)
            onSelect?.invoke(mode)
        }
        row.addView(toggleHost)
        val closeSize = pillToggleHeightPx(context)
        val closePad = (closeSize - (GLYPH_DP * dp).roundToInt()) / 2
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
        /** Where a host pins the pill: this far under the top its content
         *  keeps clear ([placeUnder]) and from the end edge. */
        const val TOP_DP = 12
        const val END_DP = 16

        /** The capsule's buffer around the track and the X, the same on
         *  every side. */
        const val INSET_DP = 4
        /** Each segment's least width in the expanded control; longer
         *  labels widen it ([pillToggleFitWidthPx]). */
        const val SEGMENT_DP = 78
        /** The disc's glyph, the size the X draws at. */
        private const val GLYPH_DP = 20
        private const val MORPH_MS = 250L
        /** The spinner and the glyph changing places in a disc. */
        private const val SWAP_MS = 200L
        /** The pill coming up, and going with nothing left to offer. */
        private const val FADE_MS = 200L
        /** Where along the morph the glyph has faded out and the bar starts
         *  to fade in. */
        private const val FADE_SPLIT = 0.4f

        /** The pill's place in a host's full-screen root: at the top end,
         *  [TOP_DP] down until the insets arrive ([placeUnder]). */
        fun hostLayoutParams(context: Context): FrameLayout.LayoutParams {
            val density = context.resources.displayMetrics.density
            return FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = (TOP_DP * density).toInt()
                marginEnd = (END_DP * density).toInt()
            }
        }

        fun glyphOf(mode: OverlayMode): Int = when (mode) {
            OverlayMode.TRANSLATION -> R.drawable.ic_translate
            OverlayMode.FURIGANA -> R.drawable.ic_furigana
            OverlayMode.DEFINITIONS -> R.drawable.ic_dictionary
            OverlayMode.NONE -> R.drawable.ic_visibility_off
        }
    }
}
