package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.roundToInt

/**
 * The capture sheet's "an overlay is up" signal, in two views the sheet
 * places in its own full-screen root: [ring], a frame around the display
 * edges, and [rail], an accent line with a glow hugging the sheet's top
 * contour. Both are CHILDREN of the sheet root, not windows of their own,
 * so their lifetime is structural: on screen exactly while the sheet is,
 * blanked and torn down with the sheet's window on every show, dismiss,
 * stash, reshow, key-sink linger and display reconfiguration, in the
 * over-game window host and the camera / image-import activity hosts alike,
 * and riding the sheet window's touchable exemption from the MediaProjection
 * obscuring-alpha clamp (a separate non-touchable window renders at ~0.79
 * on that backend).
 *
 * The sheet places [ring] bottom-most (beneath the on-frame boxes) and
 * [rail] just beneath the panel, above the edge shadow, and feeds
 * [setSheetTop] from the pre-draw hook that already tracks the panel.
 *
 * Deliberately no [android.graphics.Path]: rings are
 * [Canvas.drawDoubleRoundRect] (per-corner radii, one primitive each) and
 * the easing is the table-driven [FastOutSlowInInterpolator], the same
 * (0.4, 0, 0.2, 1) curve a PathInterpolator would build through a Path.
 */
class EdgeIndicator(
    context: Context,
    @ColorInt accent: Int,
    /** The sheet's corner radius, so the rail's line and glow follow the
     *  panel's rounded top contour rather than running straight over it. */
    sheetCornerRadiusPx: Float,
) {
    val ring = EdgeRingView(context, accent)
    val rail = EdgeRailView(context, accent, sheetCornerRadiusPx)

    /** A capture / OCR / translation pass is in flight: the rail's line
     *  rests lower and a highlight sweeps along it. */
    var working: Boolean
        get() = rail.working
        set(value) { rail.working = value }

    /** The setting: both views shown or both gone. */
    var isVisible: Boolean
        get() = ring.isVisible
        set(value) {
            ring.isVisible = value
            rail.isVisible = value
        }

    /** Fade both in from nothing. The sheet's own entrance is a slide; the
     *  indicator's is a plain fade with the same easing. */
    fun fadeIn() {
        ring.fadeTo(1f, FADE_MS, from = 0f)
        rail.fadeTo(1f, FADE_MS, from = 0f)
    }

    /** Fade both out over [durationMs], the sheet's exit duration, so the
     *  three leave together. */
    fun fadeOut(durationMs: Long) {
        ring.fadeTo(0f, durationMs)
        rail.fadeTo(0f, durationMs)
    }

    /** One flash of the ring that settles back to its resting strength, so
     *  the sheet's appearance is noticed even in peripheral vision. */
    fun pulse() = ring.pulse()

    /** The sheet's visual top edge in root coordinates (the panel's top plus
     *  its grabber strip, live through drags, parks and the IME lift). */
    fun setSheetTop(yPx: Float) {
        rail.translationY = yPx - rail.edgeOffsetPx
    }

    /** Outline a capture region instead of the display edges: [rect] in the
     *  sheet root's coordinates, or null for the whole display. */
    fun setRegion(rect: RectF?) = ring.setRegion(rect)

    private fun View.fadeTo(alpha: Float, durationMs: Long, from: Float? = null) {
        animate().cancel()
        if (from != null) this.alpha = from
        animate().alpha(alpha).setDuration(durationMs).setInterpolator(EASING).start()
    }

    internal companion object {
        const val FADE_MS = 220L
        /** The standard (0.4, 0, 0.2, 1) curve, table-driven (no Path). */
        val EASING = FastOutSlowInInterpolator()

        fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha shl 24)
    }
}

/**
 * The frame around the display edges, bottom to top:
 *  - this view's own draw: a 1dp black scrim on the outermost pixels (what
 *    keeps the frame legible over a near-white scene) and a solid 2dp accent
 *    frame just inside it; constant;
 *  - [bloom]: a 48dp accent bloom on all four edges, breathing as one alpha
 *    property (recorded once per size, never re-drawn per frame);
 *  - [burst]: a wider, brighter bloom held at alpha 0 and flashed by
 *    [pulse].
 *
 * With a capture region ([setRegion]) the same layers outline THAT rectangle
 * instead, and nothing is drawn inside it (that is the area being read):
 * the scrim sits just outside the region's boundary, the frame outside the
 * scrim (so the scrim is on the far side from the glow, as on the display),
 * and the bloom and burst fade outward from the frame into the rest of the
 * screen, so the region reads as the lit thing and the surroundings as what
 * the capture left out. Region corners are square; the display's corner
 * radii apply only to the display frame.
 *
 * Shape: the scrim and frame follow the display's real rounded corners when
 * the platform reports them ([WindowInsets.getRoundedCorner], API 31+,
 * relative to THIS window's frame, so a corner the window does not reach
 * reads as square); otherwise, and below API 31, the corners are square,
 * which is what a square-cornered display needs. No card radius is
 * borrowed. The bloom runs straight into the corners on purpose: the pixels
 * outside a display's rounded corner do not physically exist, so clipping
 * would change nothing visible and would only jag the bloom's arc.
 *
 * Motion follows the system animator scale: at 0 (the platform's
 * reduced-motion switch) nothing moves, the bloom holds a fixed strength and
 * a pulse is dropped. Motion also stops whenever the view cannot be seen
 * ([onVisibilityAggregated]: detached, GONE, or the window hidden), so a
 * sheet lingering as an invisible key sink or an indicator turned off in
 * Settings never animates.
 */
class EdgeRingView(
    context: Context,
    @ColorInt private val accent: Int,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    /** Whole pixels: a fractional-pixel ring smears over two pixel rows
     *  under anti-aliasing and reads soft. */
    private val scrimPx = dp(SCRIM_DP).roundToInt().coerceAtLeast(1).toFloat()
    private val framePx = dp(FRAME_DP).roundToInt().coerceAtLeast(1).toFloat()

    /** Display corner radii in px, in [Canvas.drawDoubleRoundRect]'s corner
     *  order (top-left, top-right, bottom-right, bottom-left); 0 = square.
     *  Written only from the window's insets on API 31+ ([readCorners]). */
    private val cornerRadii = FloatArray(4)

    private val scrimPaint = fillPaint(EdgeIndicator.withAlpha(Color.BLACK, ALPHA_SCRIM))
    private val framePaint = fillPaint(EdgeIndicator.withAlpha(accent, ALPHA_FRAME))
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val outerRadii = FloatArray(8)
    private val innerRadii = FloatArray(8)

    /** The capture region to outline instead of the display, in this view's
     *  coordinates, with its rings precomputed; null = the display edges. */
    private var regionRings: EdgeGeometry.RegionRings? = null

    private val bloom = EdgeBands(context, dp(BLOOM_DP), dp(BLOOM_KNEE_DP), ALPHA_BLOOM_EDGE, ALPHA_BLOOM_KNEE)
    private val burst = EdgeBands(context, dp(BURST_DP), dp(BURST_DP) / 2f, ALPHA_BURST_EDGE, ALPHA_BURST_EDGE / 2)

    private var breathe: ValueAnimator? = null

    /** Effective visibility, from [onVisibilityAggregated]. */
    private var shown = false

    /** A [pulse] asked for while not shown, played at the next show: the
     *  sheet asks as it attaches, before its window is on screen. */
    private var pendingPulse = false

    /** The bloom layer's current alpha: breathing, or held still. */
    @get:VisibleForTesting
    internal val bloomAlpha: Float get() = bloom.alpha

    /** A pulse flash is on screen (its burst layer has not settled). */
    @get:VisibleForTesting
    internal val isPulsing: Boolean get() = burst.alpha > 0f

    /** The corner radii in use, [Canvas.drawDoubleRoundRect] corner order. */
    @VisibleForTesting
    internal fun cornerRadiiForTest(): FloatArray = cornerRadii.copyOf()

    /** The region being outlined, or null for the display edges. */
    @VisibleForTesting
    internal fun regionForTest(): RectF? = regionRings?.scrimInner?.let { RectF(it) }

    /** Outline [rect] (this view's coordinates) instead of the display, or
     *  the display again with null. The bloom and burst fade outward from
     *  the frame's outer edge. */
    fun setRegion(rect: RectF?) {
        val rings = rect?.let { EdgeGeometry.regionRings(it, framePx, scrimPx) }
        regionRings = rings
        bloom.setEdge(rings?.frameOuter)
        burst.setEdge(rings?.frameOuter)
        invalidate()
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(bloom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(burst, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        burst.alpha = 0f
    }

    /** The enter/exit fades multiply into each band instead of rendering the
     *  whole view through a full-screen offscreen layer; with bands this
     *  translucent the difference from true group alpha is invisible. */
    override fun hasOverlappingRendering(): Boolean = false

    /** Flash the burst layer to full and settle it back over [BURST_MS].
     *  Dropped with animations off (a still flash is just a brighter frame),
     *  deferred until shown otherwise. */
    fun pulse() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (!shown) {
            pendingPulse = true
            return
        }
        burst.animate().cancel()
        burst.alpha = 1f
        burst.animate().alpha(0f).setDuration(BURST_MS).setInterpolator(EdgeIndicator.EASING).start()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readCorners(insets)
        return super.onApplyWindowInsets(insets)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readCorners(insets: WindowInsets) {
        val positions = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT,
            RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT,
            RoundedCorner.POSITION_BOTTOM_LEFT,
        )
        var changed = false
        for (i in positions.indices) {
            // Window-relative: a corner this window's frame does not reach
            // reads null, which is the square it should draw.
            val r = insets.getRoundedCorner(positions[i])?.radius?.toFloat() ?: 0f
            if (r != cornerRadii[i]) {
                cornerRadii[i] = r
                changed = true
            }
        }
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val rings = regionRings
        if (rings != null) {
            // The region: scrim just outside its boundary, frame outside that;
            // nothing inside the region itself.
            canvas.drawDoubleRoundRect(rings.scrimOuter, ZERO_RADII, rings.scrimInner, ZERO_RADII, scrimPaint)
            canvas.drawDoubleRoundRect(rings.frameOuter, ZERO_RADII, rings.frameInner, ZERO_RADII, framePaint)
            return
        }
        // Outermost pixels: the black scrim, then the solid accent frame
        // just inside it.
        fillRing(canvas, 0f, scrimPx, scrimPaint)
        fillRing(canvas, scrimPx, framePx, framePaint)
    }

    /** Fill a [widthPx]-wide ring whose outer edge is the display outline
     *  inset by [outerInset] px: corners follow the reported radii
     *  (concentric, so the ring keeps a fixed distance inside the display's
     *  own arc), square where none is reported. */
    private fun fillRing(canvas: Canvas, outerInset: Float, widthPx: Float, paint: Paint) {
        val innerInset = outerInset + widthPx
        outerRect.set(outerInset, outerInset, width - outerInset, height - outerInset)
        innerRect.set(innerInset, innerInset, width - innerInset, height - innerInset)
        for (i in 0 until 4) {
            val outer = (cornerRadii[i] - outerInset).coerceAtLeast(0f)
            val inner = (cornerRadii[i] - innerInset).coerceAtLeast(0f)
            outerRadii[i * 2] = outer
            outerRadii[i * 2 + 1] = outer
            innerRadii[i * 2] = inner
            innerRadii[i * 2 + 1] = inner
        }
        canvas.drawDoubleRoundRect(outerRect, outerRadii, innerRect, innerRadii, paint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        syncMotion()
    }

    /** Start or stop the breathe to match [shown] and the system animator
     *  scale, and play a deferred pulse once shown. Idempotent: a running
     *  breathe is left running. */
    private fun syncMotion() {
        val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
        if (!shown || !animatorsEnabled) {
            breathe?.cancel()
            breathe = null
            burst.animate().cancel()
            burst.alpha = 0f
            if (!animatorsEnabled) {
                bloom.alpha = STILL_BLOOM_ALPHA
                pendingPulse = false
            }
            return
        }
        if (breathe == null) {
            breathe = ValueAnimator.ofFloat(BREATHE_MIN_ALPHA, 1f).apply {
                duration = BREATHE_CYCLE_MS / 2
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = EdgeIndicator.EASING
                addUpdateListener { bloom.alpha = it.animatedValue as Float }
                start()
            }
        }
        if (pendingPulse) {
            pendingPulse = false
            pulse()
        }
    }

    private fun fillPaint(@ColorInt color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    /** Gradient bands, [depthPx] deep: [edgeAlpha] at the edge line,
     *  [kneeAlpha] at [kneePx], nothing at [depthPx]. Inward from the view's
     *  own edges by default; outward from a rectangle after [setEdge], with
     *  quarter-radial corners (see [EdgeGeometry.bands]). Declares no
     *  overlapping rendering so an animated alpha multiplies into the
     *  gradients directly instead of rendering through a full-screen
     *  offscreen layer every frame. */
    private inner class EdgeBands(
        c: Context,
        private val depthPx: Float,
        private val kneePx: Float,
        private val edgeAlpha: Int,
        private val kneeAlpha: Int,
    ) : View(c) {
        private val paints = Array(EdgeGeometry.MAX_BANDS) { Paint() }
        private var bands: List<EdgeGeometry.Band> = emptyList()
        private var edge: RectF? = null

        override fun hasOverlappingRendering(): Boolean = false

        /** Fade outward from [rect] instead of inward from the view, or
         *  back to the view's edges with null. */
        fun setEdge(rect: RectF?) {
            edge = rect?.let { RectF(it) }
            rebuild()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild()
        }

        private fun rebuild() {
            if (width <= 0 || height <= 0) {
                bands = emptyList()
                return
            }
            val colors = intArrayOf(
                EdgeIndicator.withAlpha(accent, edgeAlpha),
                EdgeIndicator.withAlpha(accent, kneeAlpha),
                Color.TRANSPARENT,
            )
            val stops = floatArrayOf(0f, kneePx / depthPx, 1f)
            bands = EdgeGeometry.bands(width.toFloat(), height.toFloat(), edge, depthPx)
            bands.forEachIndexed { i, b ->
                paints[i].shader = if (b.radial) {
                    RadialGradient(b.x0, b.y0, depthPx, colors, stops, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(b.x0, b.y0, b.x1, b.y1, colors, stops, Shader.TileMode.CLAMP)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            bands.forEachIndexed { i, b -> canvas.drawRect(b.rect, paints[i]) }
        }
    }

    private companion object {
        const val SCRIM_DP = 1f
        const val FRAME_DP = 2f
        const val BLOOM_DP = 48f
        /** Where the bloom bends from its edge strength to its knee strength. */
        const val BLOOM_KNEE_DP = 24f
        /** The pulse flash: a wider, brighter bloom that settles to nothing. */
        const val BURST_DP = 64f

        val ZERO_RADII = FloatArray(8)

        const val ALPHA_SCRIM = 0x66       // 40%
        const val ALPHA_FRAME = 0x91       // 57%, two thirds of the 85% first tried
        const val ALPHA_BLOOM_EDGE = 0x57  // 34%
        const val ALPHA_BLOOM_KNEE = 0x1F  // 12%
        const val ALPHA_BURST_EDGE = 0x66  // 40%

        /** The breathe's low point; the high point is 1. Low amplitude on purpose. */
        const val BREATHE_MIN_ALPHA = 0.58f
        /** One full breathe, up and back down. */
        const val BREATHE_CYCLE_MS = 5200L
        /** The bloom's fixed strength when the system has animations off. */
        const val STILL_BLOOM_ALPHA = 0.8f
        const val BURST_MS = 550L
    }
}

/**
 * The rail on the sheet's top edge: a 1dp accent line hugging the panel's
 * rounded top contour, a soft accent glow bleeding upward from it into the
 * game, and, while [working], a highlight sweeping along the line. Sits
 * where the eye already is in both the parked and the expanded state, which
 * is why the design's bottom weight lives here and not on the display's
 * bottom edge (the panel and the full-width sliver cover that edge in every
 * state).
 *
 * Geometry is in the view's own coordinates: the sheet's top edge is at
 * [edgeOffsetPx]; the line occupies the strip just above it and curves down
 * around the corners with the panel's radius; the glow is the OUTER blur of
 * that contour, baked ONCE per width into a bitmap (BlurMaskFilter only
 * blurs on a software canvas, the same trick as the sheet's edge shadow)
 * and blitted from then on. The host positions the view with
 * `translationY = sheetTop - edgeOffsetPx` from its pre-draw hook. Anything
 * below the sheet edge is covered by the opaque panel.
 */
class EdgeRailView(
    context: Context,
    @ColorInt private val accent: Int,
    private val cornerRadiusPx: Float,
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val linePx = dp(LINE_DP).roundToInt().coerceAtLeast(1).toFloat()
    /** How far above the line the glow reaches (the blur's visible extent). */
    private val glowReachPx = (dp(GLOW_BLUR_DP) * GLOW_REACH_MULT).roundToInt()

    /** Local y of the sheet's top edge. */
    val edgeOffsetPx: Int = glowReachPx + linePx.toInt()

    /** The view's height: the glow, the line, and the corner arcs below the
     *  edge (covered by the panel, but the line's curve is drawn there). */
    val heightPx: Int = edgeOffsetPx + (cornerRadiusPx + linePx).roundToInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerShader: LinearGradient
    private val shimmerMatrix = Matrix()
    private val shimmerWidthPx = dp(SHIMMER_WIDTH_DP)
    private val outer = RectF()
    private val inner = RectF()

    private var glow: Bitmap? = null
    private var sweep: ValueAnimator? = null
    private var shown = false

    /** A pass is in flight: the line rests lower and the highlight sweeps. */
    var working: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncMotion()
        }

    /** The sweep is running (and the line rests at its lower strength). */
    @get:VisibleForTesting
    internal val isSweeping: Boolean get() = sweep != null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        linePaint.color = EdgeIndicator.withAlpha(accent, ALPHA_LINE_REST)
        // Transparent, full accent, transparent across the highlight's width;
        // the local matrix slides it along the line.
        shimmerShader = LinearGradient(
            0f, 0f, shimmerWidthPx, 0f,
            intArrayOf(Color.TRANSPARENT, EdgeIndicator.withAlpha(accent, 0xFF), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        shimmerPaint.shader = shimmerShader
    }

    override fun hasOverlappingRendering(): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow?.recycle()
        glow = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glow?.recycle()
        glow = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        if (w <= 0 || height <= 0) return
        val g = glow ?: bakeGlow(w).also { glow = it }
        canvas.drawBitmap(g, 0f, 0f, null)
        drawLine(canvas, linePaint)
        if (sweep != null) drawLine(canvas, shimmerPaint)
    }

    /** The line: the ring between the panel's contour expanded by the line
     *  width and the contour itself, from the top edge around the corners.
     *  The side runs sit outside the view horizontally and the body runs
     *  off the bottom, so only the top run and the corner arcs show. */
    private fun drawLine(canvas: Canvas, paint: Paint) {
        val edge = edgeOffsetPx.toFloat()
        val far = height * 3f
        outer.set(-linePx, edge - linePx, width + linePx, far)
        inner.set(0f, edge, width.toFloat(), far)
        val ro = cornerRadiusPx + linePx
        canvas.drawDoubleRoundRect(outer, ro, ro, inner, cornerRadiusPx, cornerRadiusPx, paint)
    }

    /** The glow: an OUTER blur of the line's outer contour, cast upward into
     *  the strip above the sheet and around the corner arcs. */
    private fun bakeGlow(w: Int): Bitmap {
        val h = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = EdgeIndicator.withAlpha(accent, ALPHA_GLOW_FILL)
            maskFilter = BlurMaskFilter(dp(GLOW_BLUR_DP), BlurMaskFilter.Blur.OUTER)
        }
        val top = edgeOffsetPx - linePx
        val r = cornerRadiusPx + linePx
        c.drawRoundRect(-linePx, top, w + linePx, h * 3f, r, r, paint)
        return bitmap
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        syncMotion()
    }

    /** Start or stop the sweep to match [shown], [working] and the system
     *  animator scale, and keep the line's resting strength in step with
     *  it: the drop exists to give the highlight headroom, so with no sweep
     *  (hidden, or animations off) a working sheet keeps the full line
     *  rather than merely dimming it. */
    private fun syncMotion() {
        val sweeping = shown && working && ValueAnimator.areAnimatorsEnabled()
        if (sweeping == (sweep != null)) return
        if (sweeping) {
            sweep = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SWEEP_MS
                repeatMode = ValueAnimator.RESTART
                repeatCount = ValueAnimator.INFINITE
                interpolator = EdgeIndicator.EASING
                addUpdateListener {
                    // From fully off the left edge to fully off the right;
                    // the width is read live, so a relayout needs no rebuild.
                    val f = it.animatedValue as Float
                    shimmerMatrix.setTranslate(-shimmerWidthPx + f * (width + shimmerWidthPx), 0f)
                    shimmerShader.setLocalMatrix(shimmerMatrix)
                    invalidate()
                }
                start()
            }
        } else {
            sweep?.cancel()
            sweep = null
        }
        linePaint.color = EdgeIndicator.withAlpha(accent, if (sweeping) ALPHA_LINE_SWEEPING else ALPHA_LINE_REST)
        invalidate()
    }

    private companion object {
        const val LINE_DP = 1f
        /** The glow's blur radius; its visible reach is [GLOW_REACH_MULT] times it. */
        const val GLOW_BLUR_DP = 12f
        const val GLOW_REACH_MULT = 2.5f
        /** Fill alpha of the blurred contour; an OUTER blur lands the cast
         *  edge at about half this. */
        const val ALPHA_GLOW_FILL = 200
        const val ALPHA_LINE_REST = 0xB8      // 72%
        const val ALPHA_LINE_SWEEPING = 0x42  // 26%
        const val SHIMMER_WIDTH_DP = 120f
        const val SWEEP_MS = 1900L
    }
}


/**
 * Pure geometry for the ring's gradient bands and a region's rings, in px,
 * so the inward-from-the-display / outward-from-a-region rule is pinned by
 * a test without rasterizing.
 */
internal object EdgeGeometry {
    /** One gradient band: [rect] to fill, and its gradient. Linear: the
     *  axis from the edge line at ([x0], [y0]), full strength, to the fade
     *  end at ([x1], [y1]). [radial]: centred at ([x0], [y0]), full
     *  strength there, fading to nothing at the band depth (a quarter of
     *  the disc shows inside [rect]). */
    data class Band(
        val rect: RectF,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val radial: Boolean = false,
    )

    const val MAX_BANDS = 8

    /** The bands, [depth] deep. With [edge] null, four bands inward from a
     *  [w] x [h] view's own edges (left, right, top, bottom); they overlap
     *  in the corner squares, and at a display's corners that sum is
     *  accepted. With [edge], the exact outer glow of that rectangle: four
     *  side bands no longer than their sides (same order) and four radial
     *  corners (top-left, top-right, bottom-right, bottom-left), so the
     *  glow's strength is a function of distance to the rectangle
     *  everywhere and the joins are seamless. */
    fun bands(w: Float, h: Float, edge: RectF?, depth: Float): List<Band> {
        if (edge == null) {
            return listOf(
                Band(RectF(0f, 0f, depth, h), 0f, 0f, depth, 0f),
                Band(RectF(w - depth, 0f, w, h), w, 0f, w - depth, 0f),
                Band(RectF(0f, 0f, w, depth), 0f, 0f, 0f, depth),
                Band(RectF(0f, h - depth, w, h), 0f, h, 0f, h - depth),
            )
        }
        val e = edge
        return listOf(
            Band(RectF(e.left - depth, e.top, e.left, e.bottom), e.left, 0f, e.left - depth, 0f),
            Band(RectF(e.right, e.top, e.right + depth, e.bottom), e.right, 0f, e.right + depth, 0f),
            Band(RectF(e.left, e.top - depth, e.right, e.top), 0f, e.top, 0f, e.top - depth),
            Band(RectF(e.left, e.bottom, e.right, e.bottom + depth), 0f, e.bottom, 0f, e.bottom + depth),
            Band(RectF(e.left - depth, e.top - depth, e.left, e.top), e.left, e.top, e.left - depth, e.top - depth, radial = true),
            Band(RectF(e.right, e.top - depth, e.right + depth, e.top), e.right, e.top, e.right + depth, e.top - depth, radial = true),
            Band(RectF(e.right, e.bottom, e.right + depth, e.bottom + depth), e.right, e.bottom, e.right + depth, e.bottom + depth, radial = true),
            Band(RectF(e.left - depth, e.bottom, e.left, e.bottom + depth), e.left, e.bottom, e.left - depth, e.bottom + depth, radial = true),
        )
    }

    /** A region's outline, entirely OUTSIDE the region so nothing is drawn
     *  over the area being read: the scrim ring on the boundary
     *  ([scrimInner] is the region itself), the frame ring outside it. Each
     *  ring is the area between its outer and inner rect. The bands then
     *  fade outward from [frameOuter]. */
    data class RegionRings(
        val frameOuter: RectF,
        val frameInner: RectF,
        val scrimOuter: RectF,
        val scrimInner: RectF,
    )

    fun regionRings(region: RectF, framePx: Float, scrimPx: Float): RegionRings {
        val scrimOuter = RectF(region.left - scrimPx, region.top - scrimPx, region.right + scrimPx, region.bottom + scrimPx)
        val frameOuter = RectF(
            scrimOuter.left - framePx, scrimOuter.top - framePx, scrimOuter.right + framePx, scrimOuter.bottom + framePx,
        )
        return RegionRings(
            frameOuter = frameOuter,
            frameInner = RectF(scrimOuter),
            scrimOuter = scrimOuter,
            scrimInner = RectF(region),
        )
    }
}
