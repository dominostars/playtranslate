package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.abs
import kotlin.math.max

/**
 * Waveform of the game-audio snapshot with a draggable trim selection — the
 * heart of the trim editor. Manual finder by design (v1 has no auto-placed
 * range): pinch to zoom, drag the body to pan, drag either handle to set the
 * selection edges. Rendering + touch precedents: [RegionPreviewView] (strip
 * onDraw), [RegionDragView] (per-target touch dispatch).
 *
 * Data is per-bucket RMS (computed once off-main by the activity); the view
 * itself never touches PCM.
 */
class WaveformTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** ms of audio represented by one RMS bucket. */
    var bucketMs = 50L
        private set
    private var rms = FloatArray(0)
    private var durationMs = 0L

    /** ms at the view's left edge / scale. Doubles for smooth pan/zoom. */
    private var viewStartMs = 0.0
    private var msPerPx = 0.0

    var selStartMs = 0L
        private set
    var selEndMs = 0L
        private set
    private var cursorMs: Long? = null

    /** Fired on every user-driven selection change (drag in progress too). */
    var onSelectionChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    /** Embedded mode (the in-card panel inside a scrolling bottom sheet):
     *  the parent keeps vertical gestures — a body drag becomes a pan only
     *  once horizontal movement wins the touch slop, so scrolling the card
     *  by dragging across the waveform still works. Handle grabs and pinch
     *  zooms (any second pointer) always win immediately. */
    var embedded = false

    /** Host surface color the edge fades blend into — ptCard for the in-card
     *  panel, the default ptBg for the full editor. */
    var fadeColor: Int = context.themeColor(R.attr.ptBg)
        set(value) {
            field = value
            fadeShadersDirty = true
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val handleTouchPx = 24 * density
    private val minSelectionMs = 200L
    private val minWindowMs = 2_000.0

    private companion object {
        /** Hidden-content threshold for the edge fade/arrow — about one
         *  RMS bucket; anything smaller isn't meaningfully "more audio". */
        const val EDGE_EPSILON_MS = 60.0
    }

    private val barPaint = Paint().apply { color = context.themeColor(R.attr.ptDivider) }
    private val barSelectedPaint = Paint().apply { color = context.themeColor(R.attr.ptAccent) }
    private val selectionFill = Paint().apply {
        color = context.themeColor(R.attr.ptAccent)
        alpha = 36
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.ptAccent)
    }
    /** Ring behind the grip dot, in the page background color — separates
     *  the dot from the waveform bars it sits over. */
    private val handleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.ptBg)
    }
    private val cursorPaint = Paint().apply {
        color = context.themeColor(R.attr.ptText)
        strokeWidth = 1.5f * density
    }
    private val baselinePaint = Paint().apply {
        color = context.themeColor(R.attr.ptDivider)
        alpha = 120
    }

    // ── More-audio-off-screen affordances: edge fades + arrows ───────────
    /** Empty gutters OUTSIDE the rendered playback range where the
     *  more-audio arrows live — clear of the bars/fades so they read at a
     *  glance. All content mapping is inset by this. */
    private val edgeGutterPx = 14 * density
    private fun contentLeft(): Float = edgeGutterPx
    private fun contentRight(): Float = width - edgeGutterPx
    private fun contentWidth(): Double =
        (width - 2 * edgeGutterPx).toDouble().coerceAtLeast(1.0)

    private val fadeWidthPx = 24 * density
    private var fadeShadersDirty = true
    private val leftFadePaint = Paint()
    private val rightFadePaint = Paint()
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Matches the center baseline exactly (same attr, same alpha).
        color = context.themeColor(R.attr.ptDivider)
        alpha = 120
        style = Paint.Style.FILL
    }
    private val arrowPath = Path()

    private fun ensureFadeShaders() {
        if (!fadeShadersDirty || width == 0) return
        fadeShadersDirty = false
        val transparent = fadeColor and 0x00FFFFFF
        leftFadePaint.shader = LinearGradient(
            contentLeft(), 0f, contentLeft() + fadeWidthPx, 0f,
            fadeColor, transparent, Shader.TileMode.CLAMP,
        )
        rightFadePaint.shader = LinearGradient(
            contentRight() - fadeWidthPx, 0f, contentRight(), 0f,
            transparent, fadeColor, Shader.TileMode.CLAMP,
        )
    }

    private enum class DragTarget { LEFT_HANDLE, RIGHT_HANDLE, PAN, NONE }
    private var drag = DragTarget.NONE
    private var lastTouchX = 0f
    private var scaling = false
    private var downX = 0f
    private var downY = 0f
    private var panCommitted = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                drag = DragTarget.NONE
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (durationMs == 0L || width == 0) return true
                // Keep the ms under the pinch focal point fixed.
                val focalX = detector.focusX - contentLeft()
                val focalMs = viewStartMs + focalX * msPerPx
                msPerPx = clampScale(msPerPx / detector.scaleFactor)
                viewStartMs = focalMs - focalX * msPerPx
                clampView()
                invalidate()
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        },
    )

    /** Install the waveform. Fits the whole file into the view and, when
     *  [initialStartMs] ≥ 0, applies + reveals the initial selection. */
    fun setData(rmsBuckets: FloatArray, bucketMs: Long, durationMs: Long, initialStartMs: Long = -1, initialEndMs: Long = -1) {
        this.rms = rmsBuckets
        this.bucketMs = bucketMs
        this.durationMs = durationMs
        msPerPx = 0.0
        if (initialStartMs >= 0 && initialEndMs > initialStartMs) {
            selStartMs = initialStartMs.coerceIn(0, durationMs)
            selEndMs = initialEndMs.coerceIn(selStartMs + minSelectionMs, durationMs)
        }
        // Data usually lands AFTER layout (the activity loads it async), and
        // requestLayout() on an unchanged size never re-fires onSizeChanged —
        // so fit here when measured; onSizeChanged covers the pre-layout case.
        fitAndReveal()
        invalidate()
    }

    fun setPlaybackCursorMs(ms: Long?) {
        cursorMs = ms
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fadeShadersDirty = true
        if (msPerPx == 0.0) fitAndReveal()
    }

    /** Fit the full file to the content region and scroll/zoom the selection
     *  into view. No-op until both the layout pass and [setData] have happened. */
    private fun fitAndReveal() {
        if (width == 0 || durationMs == 0L) return
        msPerPx = maxMsPerPx()
        viewStartMs = 0.0
        revealSelection()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rms.isEmpty() || durationMs == 0L || msPerPx == 0.0) return
        val h = height.toFloat()
        val midY = h / 2f
        val cl = contentLeft()
        val cr = contentRight()
        canvas.drawRect(cl, midY - 0.5f * density, cr, midY + 0.5f * density, baselinePaint)

        val selL = xFor(selStartMs)
        val selR = xFor(selEndMs)

        // Everything tied to the playback range clips to the content region —
        // the gutters stay clean for the arrows.
        canvas.save()
        canvas.clipRect(cl, 0f, cr, h)
        canvas.drawRect(selL, 0f, selR, h, selectionFill)

        // Bars: one per drawn column when zoomed out (multiple buckets/px, take
        // max), one per bucket when zoomed in.
        val bucketPx = (bucketMs / msPerPx).toFloat()
        val colW = max(1f * density, bucketPx)
        var x = cl
        while (x < cr) {
            val msAtX = viewStartMs + (x - cl) * msPerPx
            if (msAtX >= durationMs) break
            val firstBucket = (msAtX / bucketMs).toInt()
            val lastBucket = ((msAtX + colW * msPerPx) / bucketMs).toInt()
            if (lastBucket < 0) {
                // Overscroll region before 0 ms — nothing to render.
                x += colW
                continue
            }
            var amp = 0f
            for (b in firstBucket..lastBucket) {
                if (b in rms.indices) amp = max(amp, rms[b])
            }
            val barH = max(1f * density, amp * (h * 0.88f))
            val paint = if (x + colW / 2 in selL..selR) barSelectedPaint else barPaint
            canvas.drawRect(x, midY - barH / 2, x + colW * 0.8f, midY + barH / 2, paint)
            x += colW
        }

        // Edge fades into the host surface, shown only when content actually
        // continues past that edge.
        val viewEndMs = viewStartMs + contentWidth() * msPerPx
        val hasLeft = viewStartMs > EDGE_EPSILON_MS
        val hasRight = viewEndMs < durationMs - EDGE_EPSILON_MS
        ensureFadeShaders()
        if (hasLeft) canvas.drawRect(cl, 0f, cl + fadeWidthPx, h, leftFadePaint)
        if (hasRight) canvas.drawRect(cr - fadeWidthPx, 0f, cr, h, rightFadePaint)

        cursorMs?.let { ms ->
            val cx = xFor(ms)
            if (cx in cl..cr) canvas.drawLine(cx, 0f, cx, h, cursorPaint)
        }
        canvas.restore()

        // Handles draw UNCLIPPED so a boundary handle's grip can bleed into
        // the gutter instead of being sliced in half — but only when the
        // handle's position is actually at/inside the visible content edge
        // (an off-screen selection edge stays undrawn).
        for (hx in listOf(selL, selR)) {
            if (hx < cl - 2f * density || hx > cr + 2f * density) continue
            canvas.drawRect(hx - 1.25f * density, 0f, hx + 1.25f * density, h, handlePaint)
            canvas.drawCircle(hx, midY, 8f * density, handleHaloPaint)
            canvas.drawCircle(hx, midY, 6f * density, handlePaint)
        }

        // Arrows live OUTSIDE the playback range, in the empty gutters —
        // fully clear of bars and fades so they're unmissable.
        if (hasLeft) drawEdgeArrow(canvas, midY, pointingLeft = true)
        if (hasRight) drawEdgeArrow(canvas, midY, pointingLeft = false)
    }

    /** The Material arrow_left / arrow_right triangle, centered vertically
     *  in the gutter outside the playback range. */
    private fun drawEdgeArrow(canvas: Canvas, midY: Float, pointingLeft: Boolean) {
        val halfH = 6f * density
        val w = 7f * density
        val tipX = if (pointingLeft) 2f * density else width - 2f * density
        val baseX = if (pointingLeft) tipX + w else tipX - w
        arrowPath.reset()
        arrowPath.moveTo(baseX, midY - halfH)
        arrowPath.lineTo(tipX, midY)
        arrowPath.lineTo(baseX, midY + halfH)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs == 0L) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means pinch — claim the gesture from the
                // sheet and abandon any pending drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                drag = DragTarget.NONE
                panCommitted = false
            }
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                downX = event.x
                downY = event.y
                panCommitted = false
                drag = hitTest(event.x)
                // Embedded body-drags stay interceptable (the sheet may claim
                // a vertical scroll); handle grabs are ours unconditionally.
                if (!embedded || drag != DragTarget.PAN) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling || event.pointerCount > 1) return true
                if (embedded && drag == DragTarget.PAN && !panCommitted) {
                    val adx = abs(event.x - downX)
                    val ady = abs(event.y - downY)
                    if (adx > touchSlop && adx > ady) {
                        // Horizontal won: this is a pan — claim the gesture.
                        panCommitted = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        lastTouchX = event.x
                    }
                    // Else keep waiting; a vertical win means the parent
                    // intercepts and we receive ACTION_CANCEL.
                    return true
                }
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                when (drag) {
                    DragTarget.LEFT_HANDLE -> {
                        val ms = msFor(event.x).coerceIn(0L, selEndMs - minSelectionMs)
                        if (ms != selStartMs) {
                            selStartMs = ms
                            onSelectionChanged?.invoke(selStartMs, selEndMs)
                            invalidate()
                        }
                    }
                    DragTarget.RIGHT_HANDLE -> {
                        val ms = msFor(event.x).coerceIn(selStartMs + minSelectionMs, durationMs)
                        if (ms != selEndMs) {
                            selEndMs = ms
                            onSelectionChanged?.invoke(selStartMs, selEndMs)
                            invalidate()
                        }
                    }
                    DragTarget.PAN -> {
                        viewStartMs -= dx * msPerPx
                        clampView()
                        invalidate()
                    }
                    DragTarget.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = DragTarget.NONE
                panCommitted = false
            }
        }
        return true
    }

    /** Programmatic selection update (e.g. the full editor returned a refined
     *  range) — applies, reveals, does NOT fire [onSelectionChanged]. */
    fun setSelection(startMs: Long, endMs: Long) {
        if (durationMs == 0L || endMs <= startMs) return
        selStartMs = startMs.coerceIn(0, durationMs)
        selEndMs = endMs.coerceIn(selStartMs + minSelectionMs, durationMs)
        revealSelection()
        invalidate()
    }

    private fun hitTest(x: Float): DragTarget {
        val dl = abs(x - xFor(selStartMs))
        val dr = abs(x - xFor(selEndMs))
        return when {
            dl <= handleTouchPx && dl <= dr -> DragTarget.LEFT_HANDLE
            dr <= handleTouchPx -> DragTarget.RIGHT_HANDLE
            else -> DragTarget.PAN
        }
    }

    private fun xFor(ms: Long): Float = (contentLeft() + (ms - viewStartMs) / msPerPx).toFloat()
    private fun msFor(x: Float): Long = (viewStartMs + (x - contentLeft()) * msPerPx).toLong()

    /** Fully-zoomed-out scale: the file exactly fills the content region —
     *  no blank margins past the boundaries (boundary handles get their
     *  standoff from the arrow gutters + the host's padding instead). */
    private fun maxMsPerPx(): Double = durationMs.toDouble() / contentWidth()

    /** Zoom bounds, degenerate-safe: a snapshot shorter than the 2 s minimum
     *  window would otherwise invert the coerce bounds and throw. */
    private fun clampScale(scale: Double): Double {
        val maxScale = maxMsPerPx()
        val minScale = minOf(minWindowMs / contentWidth(), maxScale)
        return scale.coerceIn(minScale, maxScale)
    }

    private fun clampView() {
        val windowMs = contentWidth() * msPerPx
        viewStartMs = viewStartMs.coerceIn(0.0, max(0.0, durationMs - windowMs))
    }

    /** Scroll/zoom so the selection is comfortably on screen. */
    private fun revealSelection() {
        if (width == 0 || durationMs == 0L || selEndMs <= selStartMs) return
        val selLen = (selEndMs - selStartMs).toDouble()
        // Show the selection at ~1/3 of the window, but never zoom past limits.
        msPerPx = clampScale(selLen * 3 / contentWidth())
        viewStartMs = selStartMs - (contentWidth() * msPerPx - selLen) / 2
        clampView()
    }
}
