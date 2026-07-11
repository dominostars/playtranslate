package com.playtranslate.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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

    private val density = resources.displayMetrics.density
    private val handleTouchPx = 24 * density
    private val minSelectionMs = 200L
    private val minWindowMs = 2_000.0

    private val barPaint = Paint().apply { color = context.themeColor(R.attr.ptDivider) }
    private val barSelectedPaint = Paint().apply { color = context.themeColor(R.attr.ptAccent) }
    private val selectionFill = Paint().apply {
        color = context.themeColor(R.attr.ptAccent)
        alpha = 36
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.attr.ptAccent)
    }
    private val cursorPaint = Paint().apply {
        color = context.themeColor(R.attr.ptText)
        strokeWidth = 1.5f * density
    }
    private val baselinePaint = Paint().apply {
        color = context.themeColor(R.attr.ptDivider)
        alpha = 120
    }

    private enum class DragTarget { LEFT_HANDLE, RIGHT_HANDLE, PAN, NONE }
    private var drag = DragTarget.NONE
    private var lastTouchX = 0f
    private var scaling = false

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
                val focalMs = viewStartMs + detector.focusX * msPerPx
                msPerPx = (msPerPx / detector.scaleFactor)
                    .coerceIn(minWindowMs / width, durationMs.toDouble() / width)
                viewStartMs = focalMs - detector.focusX * msPerPx
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
        msPerPx = 0.0 // re-fit on next layout pass
        if (initialStartMs >= 0 && initialEndMs > initialStartMs) {
            selStartMs = initialStartMs.coerceIn(0, durationMs)
            selEndMs = initialEndMs.coerceIn(selStartMs + minSelectionMs, durationMs)
        }
        requestLayout()
        invalidate()
    }

    fun setPlaybackCursorMs(ms: Long?) {
        cursorMs = ms
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && durationMs > 0 && msPerPx == 0.0) {
            msPerPx = durationMs.toDouble() / w
            viewStartMs = 0.0
            revealSelection()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rms.isEmpty() || durationMs == 0L || msPerPx == 0.0) return
        val h = height.toFloat()
        val midY = h / 2f
        canvas.drawRect(0f, midY - 0.5f * density, width.toFloat(), midY + 0.5f * density, baselinePaint)

        // Bars: one per drawn column when zoomed out (multiple buckets/px, take
        // max), one per bucket when zoomed in.
        val selL = xFor(selStartMs)
        val selR = xFor(selEndMs)
        canvas.drawRect(selL, 0f, selR, h, selectionFill)

        val bucketPx = (bucketMs / msPerPx).toFloat()
        val colW = max(1f * density, bucketPx)
        var x = 0f
        while (x < width) {
            val msAtX = viewStartMs + x * msPerPx
            if (msAtX >= durationMs) break
            val firstBucket = (msAtX / bucketMs).toInt()
            val lastBucket = ((msAtX + colW * msPerPx) / bucketMs).toInt()
            var amp = 0f
            for (b in firstBucket..lastBucket) {
                if (b in rms.indices) amp = max(amp, rms[b])
            }
            val barH = max(1f * density, amp * (h * 0.88f))
            val paint = if (x + colW / 2 in selL..selR) barSelectedPaint else barPaint
            canvas.drawRect(x, midY - barH / 2, x + colW * 0.8f, midY + barH / 2, paint)
            x += colW
        }

        // Handles: full-height bar + a grip dot, mirroring RegionDragView's
        // handle affordance.
        for (hx in listOf(selL, selR)) {
            canvas.drawRect(hx - 1.25f * density, 0f, hx + 1.25f * density, h, handlePaint)
            canvas.drawCircle(hx, midY, 6f * density, handlePaint)
        }

        cursorMs?.let { ms ->
            val cx = xFor(ms)
            if (cx in 0f..width.toFloat()) canvas.drawLine(cx, 0f, cx, h, cursorPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs == 0L) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                drag = hitTest(event.x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling || event.pointerCount > 1) return true
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = DragTarget.NONE
        }
        return true
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

    private fun xFor(ms: Long): Float = ((ms - viewStartMs) / msPerPx).toFloat()
    private fun msFor(x: Float): Long = (viewStartMs + x * msPerPx).toLong()

    private fun clampView() {
        val windowMs = width * msPerPx
        viewStartMs = viewStartMs.coerceIn(0.0, max(0.0, durationMs - windowMs))
    }

    /** Scroll/zoom so the selection is comfortably on screen. */
    private fun revealSelection() {
        if (width == 0 || durationMs == 0L || selEndMs <= selStartMs) return
        val selLen = (selEndMs - selStartMs).toDouble()
        // Show the selection at ~1/3 of the window, but never zoom past limits.
        msPerPx = (selLen * 3 / width).coerceIn(minWindowMs / width, durationMs.toDouble() / width)
        viewStartMs = selStartMs - (width * msPerPx - selLen) / 2
        clampView()
    }
}
