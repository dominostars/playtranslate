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
 * heart of the trim editor. Manual-first by design: pinch to zoom, drag the
 * body to pan, drag either handle to set the selection edges, tap unselected
 * waveform to re-place the whole selection there ([applyTapAt] — onto a
 * detected voice line when the tap lands on one), hold a more-audio arrow to
 * scroll toward what it points at ([arrowScrollTick]). The only
 * auto-placement is the host's VAD snap ([setSelection]), which repositions
 * the untouched default — it never overrides a user trim. Rendering + touch
 * precedents:
 * [RegionPreviewView] (strip onDraw), [RegionDragView] (per-target touch
 * dispatch).
 *
 * Data is per-bucket ABSOLUTE RMS in 0..1 (computed once off-main by the
 * activity); the view itself never touches PCM. Bars are scaled to the loudest
 * column CURRENTLY ON SCREEN, not to the loudest bucket in the file — a 3 min
 * ring routinely holds one stinger 20 dB above the dialogue being trimmed, and
 * file-wide scaling rendered that dialogue as a flat smear while playback (which
 * normalizes the SELECTION) played it back loud. [SILENT_FLOOR_RMS] bounds the
 * boost so the inverse lie stays impossible: a window holding only a noise floor
 * still draws small instead of being stretched into a convincing waveform.
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

    /** Detected voice-line regions (ms), flattened as [start0, end0, start1,
     *  end1, …]. Only what the host's VAD pass actually scanned — the seeded
     *  window around the launch anchor — so absence of highlight outside it
     *  means "unscanned", not "no voice". */
    private var speechRegions = LongArray(0)

    /** Paint the detected voice lines' bars in the warning color. [regions]
     *  are (startMs, endMs) pairs within the loaded file. */
    fun setSpeechRegions(regions: List<Pair<Long, Long>>) {
        speechRegions = LongArray(regions.size * 2).also { arr ->
            regions.forEachIndexed { i, (s, e) -> arr[i * 2] = s; arr[i * 2 + 1] = e }
        }
        invalidate()
    }

    /** Index into [speechRegions] of the voice line containing [ms], or -1.
     *  Returns the index rather than the pair because onDraw runs this per
     *  drawn column — it must not allocate. */
    private fun speechRegionAt(ms: Double): Int {
        var i = 0
        while (i < speechRegions.size) {
            if (ms >= speechRegions[i] && ms < speechRegions[i + 1]) return i
            i += 2
        }
        return -1
    }

    private fun inSpeechRegion(ms: Double): Boolean = speechRegionAt(ms) >= 0

    /** The tap's lookup: the line under [ms], else the nearest one within
     *  [slopMs] — a finger's worth of tolerance, which the caller sizes in
     *  PIXELS. A 700 ms line is a few px wide on a zoomed-out 3 min ring, so
     *  an exact hit test would make the snap unreachable exactly where it
     *  earns its keep. */
    private fun speechRegionNear(ms: Double, slopMs: Double): Int {
        val hit = speechRegionAt(ms)
        if (hit >= 0) return hit
        var best = -1
        var bestDist = slopMs
        var i = 0
        while (i < speechRegions.size) {
            // Containment already missed, so ms is strictly outside every
            // region: one of these two differences is the distance to it.
            val dist = if (ms < speechRegions[i]) {
                speechRegions[i] - ms
            } else {
                ms - speechRegions[i + 1]
            }
            if (dist <= bestDist) {
                best = i
                bestDist = dist
            }
            i += 2
        }
        return best
    }

    /** Fired on every user-driven selection change (drag in progress too). */
    var onSelectionChanged: ((startMs: Long, endMs: Long) -> Unit)? = null

    /** Length a tap lays down when it lands on plain waveform (see
     *  [applyTapAt]). The host sets this to the trim length it seeds cards
     *  with, so a tap re-states the card's own default clip somewhere else
     *  rather than inventing a second notion of "a clip". */
    var tapSelectionMs = 5_000L

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

        /** Auto-pan speed: fraction of the visible window scrolled per
         *  animation frame while a handle drag holds the edge zone
         *  (~0.6 windows/second at 60 fps). Also the speed a held
         *  more-audio arrow glides at, so the two ways the view scrolls
         *  itself travel alike. */
        const val AUTO_PAN_FRACTION = 0.01

        /** What one press of a more-audio arrow is worth before the hold's
         *  glide takes over: a fraction of the visible window, paid out on
         *  touch-down. It is the press's own feedback — at the glide rate
         *  alone a quick tap would move the view by a single frame, i.e.
         *  visibly nothing, and the arrow would read as broken. */
        const val ARROW_STEP_FRACTION = 0.15

        /** Floor for the on-screen bar scale, ~ -50 dBFS RMS. A window whose
         *  loudest column sits below this is drawn proportionally small rather
         *  than stretched to fill the height: capture silence is not always
         *  digital zero (a nonzero noise floor defeats the recorder's
         *  SilenceGate too), and a full-height fuzz band reads as "a voice line
         *  was recorded" when nothing audible was. */
        const val SILENT_FLOOR_RMS = 0.003f

        /** Opening window width. Wide enough that a default 5 s trim lands in
         *  real context rather than filling the view. */
        const val INITIAL_WINDOW_MS = 30_000.0

        /** How far off a voice line a tap may land and still snap to it,
         *  in dp of SCREEN distance rather than ms — the tap aims at what is
         *  drawn, and what is drawn shrinks with the zoom. Half a touch
         *  target: enough to hit a thin line at full zoom-out, small enough
         *  that a tap in visibly empty audio still gets a plain window. */
        const val TAP_SNAP_DP = 12f
    }

    private val barPaint = Paint().apply { color = context.themeColor(R.attr.ptDivider) }
    /** In-selection bars outside any voice line: accent at 50% — quiet
     *  backdrop so the selection's voice bars carry the emphasis. */
    private val barSelectedPaint = Paint().apply {
        color = context.themeColor(R.attr.ptAccent)
        alpha = 128
    }
    /** Bars inside a detected voice line ([setSpeechRegions]) outside the
     *  selection — warning color. */
    private val barSpeechPaint = Paint().apply { color = context.themeColor(R.attr.ptWarning) }
    /** Voice-line bars INSIDE the selection: 75% warning / 25% accent —
     *  the line keeps most of its flagged identity, tinted just enough
     *  toward accent to read as part of the selection. */
    private val barSpeechSelectedPaint = Paint().apply {
        color = androidx.core.graphics.ColorUtils.blendARGB(
            context.themeColor(R.attr.ptWarning),
            context.themeColor(R.attr.ptAccent),
            0.25f,
        )
    }
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
     *  glance. All content mapping is inset by this. Also the arrows' touch
     *  target ([arrowDirAt]): the triangle itself is 7 dp wide, so the lane
     *  it sits alone in is what a finger can actually aim at. */
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

    /** Audio hidden past the window's left / right edge — the condition for
     *  the fade and the arrow, and therefore for the arrow's hit test too:
     *  the gutter is only a scroll control while it visibly holds one. */
    private fun hasHiddenLeft(): Boolean = viewStartMs > EDGE_EPSILON_MS
    private fun hasHiddenRight(): Boolean =
        viewStartMs + contentWidth() * msPerPx < durationMs - EDGE_EPSILON_MS

    /** -1 / +1 when [x] is in the gutter of a SHOWN more-audio arrow, else 0.
     *  A gutter with no arrow in it is not a control: nothing lies that way,
     *  so it stays plain waveform margin and a tap there still places (the
     *  file edge is under the finger by then anyway). */
    private fun arrowDirAt(x: Float): Int = when {
        x < contentLeft() && hasHiddenLeft() -> -1
        x > contentRight() && hasHiddenRight() -> 1
        else -> 0
    }

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

    private enum class DragTarget { LEFT_HANDLE, RIGHT_HANDLE, PAN, ARROW, NONE }
    private var drag = DragTarget.NONE
    /** Which more-audio arrow the finger is holding: -1 left, +1 right, 0
     *  none. Fixed at the press — the hold scrolls the way the arrow it
     *  landed on points, wherever the finger wanders afterwards. */
    private var arrowDir = 0
    private var lastTouchX = 0f
    private var scaling = false
    private var downX = 0f
    private var downY = 0f
    private var panCommitted = false
    /** This gesture can still end as a tap: one pointer, pressed on the
     *  waveform body, never traveled past the slop. Latched (not re-derived
     *  at UP) so a drag that wanders out and returns to its origin stays a
     *  drag. */
    private var tapCandidate = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ── Auto-pan while a handle drag reaches the view's edge ─────────────
    // Dragging a handle into the edge zone scrolls the content under the
    // (possibly stationary) finger so the drag continues into hidden audio,
    // like text-selection auto-scroll. The handle re-derives its position
    // from the finger x every frame, so it rides the visible edge while the
    // waveform slides past.
    private val autoPanZonePx = 20 * density
    private var autoPanDir = 0 // -1 = revealing left, +1 = revealing right
    private val autoPanTick = object : Runnable {
        override fun run() {
            if (autoPanDir == 0 ||
                (drag != DragTarget.LEFT_HANDLE && drag != DragTarget.RIGHT_HANDLE)
            ) {
                return
            }
            val before = viewStartMs
            viewStartMs += autoPanDir * contentWidth() * msPerPx * AUTO_PAN_FRACTION
            clampView()
            if (viewStartMs != before) {
                applyHandleDrag(lastTouchX)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    private fun updateAutoPan(x: Float) {
        val dir = when {
            x < contentLeft() + autoPanZonePx -> -1
            x > contentRight() - autoPanZonePx -> 1
            else -> 0
        }
        if (dir == autoPanDir) return
        removeCallbacks(autoPanTick)
        autoPanDir = dir
        if (dir != 0) postOnAnimation(autoPanTick)
    }

    private fun stopAutoPan() {
        autoPanDir = 0
        removeCallbacks(autoPanTick)
    }

    // ── Held more-audio arrow: scroll toward the hidden audio ────────────
    // The arrows advertise audio off the edge; holding one travels there.
    // Selection-free by construction — this moves the window and nothing
    // else, so reaching for context can't cost the trim.
    private val arrowScrollTick = object : Runnable {
        override fun run() {
            if (drag != DragTarget.ARROW || arrowDir == 0) return
            // Stop at the end rather than burn a frame callback per vsync
            // against a clamp — the arrow has vanished by then anyway.
            if (!scrollWindowBy(arrowDir * contentWidth() * msPerPx * AUTO_PAN_FRACTION)) return
            postOnAnimation(this)
        }
    }

    /** Shift the visible window by [deltaMs], clamped to the file. Returns
     *  false when it could not move at all — already at that end. */
    private fun scrollWindowBy(deltaMs: Double): Boolean {
        val before = viewStartMs
        viewStartMs += deltaMs
        clampView()
        if (viewStartMs == before) return false
        invalidate()
        return true
    }

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

    /** Install the waveform. [rmsBuckets] are ABSOLUTE per-bucket RMS in 0..1
     *  (see the class kdoc — the view does its own on-screen scaling). Fits the
     *  whole file into the view and, when [initialStartMs] ≥ 0, applies +
     *  reveals the initial selection. */
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
        // Pass 1: the loudest column on screen sets the scale (floored, so a
        // near-silent window can't be stretched into a full-height waveform).
        var visiblePeak = 0f
        var scanX = cl
        while (scanX < cr) {
            if (viewStartMs + (scanX - cl) * msPerPx >= durationMs) break
            visiblePeak = max(visiblePeak, columnAmp(scanX, colW))
            scanX += colW
        }
        val ampScale = 1f / max(visiblePeak, SILENT_FLOOR_RMS)
        var x = cl
        while (x < cr) {
            val msAtX = viewStartMs + (x - cl) * msPerPx
            if (msAtX >= durationMs) break
            val amp = (columnAmp(x, colW) * ampScale).coerceAtMost(1f)
            val barH = max(1f * density, amp * (h * 0.88f))
            val speech = inSpeechRegion(msAtX + colW / 2 * msPerPx)
            val selected = x + colW / 2 in selL..selR
            val paint = when {
                speech && selected -> barSpeechSelectedPaint
                speech -> barSpeechPaint
                selected -> barSelectedPaint
                else -> barPaint
            }
            canvas.drawRect(x, midY - barH / 2, x + colW * 0.8f, midY + barH / 2, paint)
            x += colW
        }

        // Edge fades into the host surface, shown only when content actually
        // continues past that edge.
        val hasLeft = hasHiddenLeft()
        val hasRight = hasHiddenRight()
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

    /** Loudest bucket RMS under the column starting at [xLeft], or 0 for a
     *  column that falls entirely outside the data (the overscroll region
     *  before 0 ms). Shared by the scale pass and the draw pass so both see
     *  exactly the same columns. */
    private fun columnAmp(xLeft: Float, colW: Float): Float {
        val msAtX = viewStartMs + (xLeft - contentLeft()) * msPerPx
        val firstBucket = (msAtX / bucketMs).toInt()
        val lastBucket = ((msAtX + colW * msPerPx) / bucketMs).toInt()
        if (lastBucket < 0) return 0f
        var amp = 0f
        for (b in firstBucket..lastBucket) {
            if (b in rms.indices) amp = max(amp, rms[b])
        }
        return amp
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
                tapCandidate = false
            }
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                downX = event.x
                downY = event.y
                panCommitted = false
                // The host forwards touches from the panel's padding into our
                // coordinates ([forwardPanelTouchesToWave]), so they land off
                // the strip. They exist to carry pinch and pan; neither
                // control the strip owns — the arrows, tap-to-place — answers
                // to a press that isn't on it, or the card's own margins
                // would scroll and re-trim the audio.
                val onStrip = event.x in 0f..width.toFloat() &&
                    event.y in 0f..height.toFloat()
                // A shown arrow owns its gutter, ahead of the handle test: a
                // handle parked at that edge is still grabbable from inside
                // the strip, while the arrow has nowhere else to live.
                arrowDir = if (onStrip) arrowDirAt(event.x) else 0
                drag = if (arrowDir != 0) DragTarget.ARROW else hitTest(event.x)
                if (drag == DragTarget.ARROW) {
                    scrollWindowBy(arrowDir * contentWidth() * msPerPx * ARROW_STEP_FRACTION)
                    postOnAnimation(arrowScrollTick)
                }
                // Only a body press can become a tap: a press on a handle is
                // a grab, a press on an arrow is a scroll.
                tapCandidate = drag == DragTarget.PAN && onStrip
                // Embedded body-drags AND arrow presses stay interceptable:
                // the sheet may claim a vertical scroll that merely started
                // there, and swallowing the user's scroll is worse than the
                // stray step a cancelled press leaves behind. (A real hold
                // holds still, so nothing intercepts it.) Handle grabs are
                // ours unconditionally.
                val yieldsToSheet = embedded &&
                    (drag == DragTarget.PAN || drag == DragTarget.ARROW)
                if (!yieldsToSheet) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Before the early returns below: a gesture that travels stops
                // being a tap even while the embedded pan is still undecided,
                // and a vertical drag over the strip (a sheet scroll the
                // parent didn't take) must not land as one either.
                if (tapCandidate &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    tapCandidate = false
                }
                if (scaling || event.pointerCount > 1) return true
                if (drag == DragTarget.PAN && !panCommitted) {
                    // The window does not move until the gesture is PROVEN a
                    // pan. Panning a still-pending tap would drag the audio
                    // out from under the finger between press and release,
                    // and at full zoom-out a few pixels of ordinary jitter is
                    // hundreds of ms of waveform. Embedded adds one clause:
                    // vertical must lose to horizontal, because a sheet is
                    // waiting for the vertical (its win arrives as an
                    // ACTION_CANCEL from the parent's intercept).
                    val adx = abs(event.x - downX)
                    if (adx > touchSlop && (!embedded || adx > abs(event.y - downY))) {
                        panCommitted = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // Resume from here, so committing costs no jump.
                        lastTouchX = event.x
                    }
                    return true
                }
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                when (drag) {
                    DragTarget.LEFT_HANDLE, DragTarget.RIGHT_HANDLE -> {
                        applyHandleDrag(event.x)
                        updateAutoPan(event.x)
                    }
                    DragTarget.PAN -> {
                        viewStartMs -= dx * msPerPx
                        clampView()
                        invalidate()
                    }
                    // The held arrow scrolls on its own clock, not the
                    // finger's — where the finger drifts to is irrelevant.
                    DragTarget.ARROW -> {}
                    DragTarget.NONE -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                // The RELEASE point, not the press point: a body press that
                // stays under the slop still pans the view frame by frame
                // (non-embedded mode), which drags the audio along with the
                // finger. The moment the finger has been holding since it
                // landed is the one under it now — at full zoom-out the
                // difference is seconds, not pixels.
                if (tapCandidate) applyTapAt(event.x)
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun endGesture() {
        drag = DragTarget.NONE
        arrowDir = 0
        panCommitted = false
        tapCandidate = false
        removeCallbacks(arrowScrollTick)
        stopAutoPan()
    }

    /**
     * A tap on unselected waveform re-places the whole selection at the
     * tapped moment: onto the voice line under the finger when there is one
     * (the warning-color bars — that tap means "that line, all of it"), else
     * a [tapSelectionMs] window centered there.
     *
     * A tap INSIDE the selection does nothing. The selection body is where a
     * pan starts and where the eye rests while auditioning a trim; re-placing
     * it under the finger there would move the edges the user just set, and
     * the gesture that means "not this — that" has somewhere else to land.
     */
    private fun applyTapAt(x: Float) {
        val ms = msFor(x).coerceIn(0L, durationMs)
        if (ms in selStartMs..selEndMs) return
        val line = speechRegionNear(ms.toDouble(), TAP_SNAP_DP * density * msPerPx)
        if (line >= 0) {
            commitTapSelection(speechRegions[line], speechRegions[line + 1])
        } else {
            commitTapSelection(ms - tapSelectionMs / 2, ms + tapSelectionMs / 2)
        }
    }

    /** Place a tapped range, then SHIFT it back inside the file rather than
     *  shrink it — a tap near either end still yields a full-length clip.
     *  Fires [onSelectionChanged]: a tap is a deliberate placement, exactly
     *  like a handle drag. */
    private fun commitTapSelection(startMs: Long, endMs: Long) {
        val len = (endMs - startMs).coerceIn(minSelectionMs, max(minSelectionMs, durationMs))
        val start = startMs.coerceIn(0L, max(0L, durationMs - len))
        val end = start + len
        if (start == selStartMs && end == selEndMs) return
        selStartMs = start
        selEndMs = end
        onSelectionChanged?.invoke(start, end)
        invalidate()
    }

    /** Move the dragged handle to the audio position under finger-x [x],
     *  clamped against the opposite handle and the file bounds. Shared by
     *  live MOVE events and the auto-pan tick (same finger x, new view
     *  offset ⇒ new position). */
    private fun applyHandleDrag(x: Float) {
        when (drag) {
            DragTarget.LEFT_HANDLE -> {
                val ms = msFor(x).coerceIn(0L, selEndMs - minSelectionMs)
                if (ms != selStartMs) {
                    selStartMs = ms
                    onSelectionChanged?.invoke(selStartMs, selEndMs)
                    invalidate()
                }
            }
            DragTarget.RIGHT_HANDLE -> {
                val ms = msFor(x).coerceIn(selStartMs + minSelectionMs, durationMs)
                if (ms != selEndMs) {
                    selEndMs = ms
                    onSelectionChanged?.invoke(selStartMs, selEndMs)
                    invalidate()
                }
            }
            else -> {}
        }
    }

    override fun onDetachedFromWindow() {
        stopAutoPan()
        removeCallbacks(arrowScrollTick)
        super.onDetachedFromWindow()
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
        // Open on a fixed [INITIAL_WINDOW_MS] of context rather than a multiple
        // of the selection, so the window doesn't shrink to nothing around a
        // short trim; a selection longer than that widens it to keep its own
        // margin. Never zooms past the clamp limits.
        msPerPx = clampScale(max(INITIAL_WINDOW_MS, selLen * 1.5) / contentWidth())
        viewStartMs = selStartMs - (contentWidth() * msPerPx - selLen) / 2
        clampView()
    }
}
