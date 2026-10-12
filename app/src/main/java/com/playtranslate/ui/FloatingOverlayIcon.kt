package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.playtranslate.overlay.OverlayEnvironment
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation

/**
 * A circular floating icon that snaps to left/right screen edges, pushed off-
 * screen so only a ~1/4-circle edge-arrow is visible. Supports drag, fling,
 * and tap.
 *
 * Position is persisted as edge (LEFT=0, RIGHT=1) + fraction (0..1) along
 * that edge vertically.
 *
 * During a drag the icon is a bare touch handle until the drag's clean
 * screenshot is in hand: it paints nothing and its window stays where it
 * was, so no visible commit of ours can land in the captured frame. Once the
 * lens reveals ([revealDragVisuals]) the window moves to the finger and
 * paints the "magnifying glass ring" that follows it for the rest of the
 * drag.
 */
class FloatingOverlayIcon(context: Context) : View(context) {

    enum class Edge { LEFT, RIGHT }

    /** Diameter of the (notional) full circle — only ~1/4 of this lands
     *  inside the screen since the rest is pushed off the edge. */
    private val circleSizePx = (56 * resources.displayMetrics.density).toInt()
    /** Extra touch padding around the circle for easier grabbing. */
    private val touchPaddingPx = (12 * resources.displayMetrics.density).toInt()
    /** Total view size (circle + padding on each side). */
    val viewSizePx = circleSizePx + touchPaddingPx * 2
    private val viewHalf = viewSizePx / 2

    /** When true, the circle fill turns red to indicate live mode is active. */
    var liveMode = false
        set(value) { if (field == value) return; field = value; invalidate() }

    /** When true (and liveMode), the circle turns yellow to indicate degraded translation. */
    var degraded = false
        set(value) { if (field == value) return; field = value; invalidate() }

    /**
     * The mark drawn in the icon's visible edge slice: the Material Symbols
     * `arrow_left` triangle, pointing at the screen, whose WEIGHT is the
     * session's state light. [ARROW_FILLED] means the capture session has
     * everything it needs right now; [ARROW_OUTLINED] means a MediaProjection
     * token it needs is missing — on the MediaProjection backend the token is
     * the capture backend itself, on the accessibility backend it matters only
     * while the Anki game-audio feature is on (then consent held plus mic
     * granted reads filled). One mark that changes weight, rather than a mark
     * plus a warning badge, so the icon stays a single glyph at a glance.
     * Decided by [com.playtranslate.capture.GameAudioGate.iconGlyph]; pushed
     * through CaptureService.syncIconState like the other status flags. Filled
     * is the resting default so an icon that lands before the service has
     * pushed shows the common state.
     */
    enum class Glyph { ARROW_FILLED, ARROW_OUTLINED }

    var glyph = Glyph.ARROW_FILLED
        set(value) { if (field == value) return; field = value; invalidate() }

    // ── Normal mode paints ──────────────────────────────────────────────
    private val defaultCircleColor = "#CC000000".toColorInt()
    private val liveCircleColor = "#CC990000".toColorInt()
    private val liveDegradedCircleColor = "#CC999900".toColorInt()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultCircleColor
        style = Paint.Style.FILL
    }
    private val borderColor = "#66888888".toColorInt()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    /** Base (undimmed) arrow colour — deliberately off pure white. Pure white
     *  drives all three OLED subpixels at max (blue ages fastest), so a static
     *  white arrow is the icon's main burn-in risk; a light silver cuts emitted
     *  light ~25% with no perceptible change against the dark disc. */
    private val arrowColor = "#C0C0C0".toColorInt()
    /** Arrow paints ([Glyph.ARROW_FILLED] / [Glyph.ARROW_OUTLINED]). The
     *  filled arrow is a solid glyph AND the resting state — a deliberate
     *  design choice (2026-09-05: the arrow's weight is the status light, so
     *  the outline must read as the exception), accepted against the burn-in
     *  argument that had kept the old edge mark a thin stroked chevron: a
     *  fill's core never gets an off-frame from the micro-orbit, so the idle
     *  dim now carries more of the mitigation. The outline's 1.5dp stroke
     *  keeps its interior visibly open at this size. */
    private val triangleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrowColor
        style = Paint.Style.FILL
    }
    private val triangleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrowColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    /** Triangle glyph geometry as fractions of the disc radius: half-height
     *  (Material `arrow_left` is a 1:2 triangle, so its width equals this) and
     *  the nudge from the off-screen disc centre toward the screen that lands
     *  it in the visible slice. About the chevron's height (half-height
     *  0.154r): settled on device review (0.22r read too large, 0.147r a
     *  touch small), so filled vs outlined reads through weight, not size. */
    private val triangleHalfHeight = 0.16f
    private val triangleNudge = 0.72f
    /** Device-review correction: the triangle sits 1dp closer to the screen
     *  edge than the nudge alone puts it (mirrored on the left edge). */
    private val triangleEdgePullPx = 1f * resources.displayMetrics.density
    // Scratch objects reused in onLayout/onDraw — allocating per frame is lint DrawAllocation.
    private val glyphPath = android.graphics.Path()
    private val gestureRect = Rect()
    private val gestureRectList = listOf(gestureRect)

    // ── OLED burn-in mitigation ─────────────────────────────────────────
    // The edge arrow is the only bright, static, always-on element, so it
    // drives burn-in. Three mitigations stack and are all draw-time only (no
    // window moves, so the touch target / gestures / persistence stay
    // untouched): the silver (not white) glyph colour above lowers luminance;
    // [dimLevel] darkens the glyph's colours after inactivity
    // — opacity is deliberately left alone, see [applyDim]; and [orbitDy]
    // slowly slides it a few px so no subpixel stays lit forever.
    private var dimLevel = 1f
    private var dimAnimator: ValueAnimator? = null
    private var orbitDy = 0f
    private var orbitStep = 0

    // ── Loading spinner (separate overlay window) ──────────────────────
    private var spinnerView: View? = null
    private var spinnerWm: WindowManager? = null
    var showLoading = false
        set(value) {
            if (field == value) return
            field = value
            if (value) showSpinnerWindow() else hideSpinnerWindow()
        }

    private fun showSpinnerWindow() {
        hideSpinnerWindow()
        val wm = this.wm ?: return
        val host = overlayHost ?: return
        val spinSize = (28 * resources.displayMetrics.density).toInt()
        val padding = (6 * resources.displayMetrics.density).toInt()
        val totalSize = spinSize + padding * 2

        val spinner = android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(180, 0, 0, 0))
            }
            val bar = android.widget.ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            addView(bar, android.widget.FrameLayout.LayoutParams(spinSize, spinSize).apply {
                gravity = android.view.Gravity.CENTER
            })
        }

        // On the MediaProjection backend the spinner must stay touchable: a
        // non-touchable TYPE_APPLICATION_OVERLAY is opacity-capped. The icon's
        // window owns the hold gesture, so a touchable spinner steals nothing.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (host.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val lp = WindowManager.LayoutParams(
            totalSize, totalSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        }

        // Beside the icon's window as it is whenever the host lays the
        // display out: a controller or hotkey hold shows the spinner with no
        // finger on the icon, so the icon is free to settle while it is up,
        // and the spinner follows in the same refresh.
        val placement = OverlayPlacement.Placed { _, spinnerParams -> placeSpinner(spinnerParams) }
        if (!host.addOverlayWindow(spinner, wm, lp, displayId, placement)) return
        spinnerView = spinner
        spinnerWm = wm
    }

    private fun hideSpinnerWindow() {
        spinnerView?.let { overlayHost?.removeOverlayWindow(it) }
        spinnerView = null
        spinnerWm = null
    }

    /** The spinner window's origin: beside the icon's visible side, centred
     *  on it, from the icon window's params as they are now. */
    private fun placeSpinner(lp: WindowManager.LayoutParams) {
        val p = params ?: return
        val gap = (32 * resources.displayMetrics.density).toInt()
        lp.x = if (currentEdge == Edge.LEFT) p.x + viewSizePx + gap else p.x - lp.width - gap
        lp.y = p.y + (viewSizePx - lp.height) / 2
    }

    // ── Drag mode paints (ring + magnifying glass) ──────────────────────
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }
    private val magGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    var onTap: (() -> Unit)? = null
    var onPositionChanged: ((edge: Int, fraction: Float) -> Unit)? = null
    /** Called once when drag mode activates (past the tap threshold, or a
     *  hold with [holdStartsDrag]). The icon paints nothing and commits no
     *  layout from here until [revealDragVisuals]. */
    var onDragStart: (() -> Unit)? = null
    /** Called on every ACTION_MOVE during a drag (rawX, rawY screen coords). */
    var onDragMove: ((Float, Float) -> Unit)? = null
    /** Called on ACTION_UP after a drag. Return true if the lens went sticky
     *  (the icon settles on its dock without a glide). */
    var onDragEnd: (() -> Boolean)? = null
    /** Called on ACTION_CANCEL while a drag was active — system cancellation
     *  (focus loss, parent intercept, etc.). Drag teardown for the gesture
     *  goes through here instead of [onDragEnd] so no lift-time lookup runs
     *  and the controller can fire its settle callback for state restore. */
    var onDragCancel: (() -> Unit)? = null
    /** Called when the user holds the icon without moving (long press). */
    var onHoldStart: (() -> Unit)? = null
    /** Called when the user lifts after a hold (without having dragged). */
    var onHoldEnd: (() -> Unit)? = null
    /** Called when a hold is cancelled because the user started dragging. */
    var onHoldCancel: (() -> Unit)? = null
    /** Called on every touch event (for dim controller reset). */
    var onAnyTouch: (() -> Unit)? = null

    /** When true, a hold (long press without movement) begins the drag /
     *  magnifying-search gesture instead of firing [onHoldStart]. Set by
     *  [SonarPingIntroView] while it forwards a gesture from the intro
     *  animation: the intro offers only "tap → menu" and "hold or drag →
     *  search", so its forwarded holds route into the drag flow. The docked
     *  icon leaves this false, keeping the hold-for-translation gesture. */
    var holdStartsDrag = false

    var wm: WindowManager? = null

    /** The display this icon (and any sub-windows like the loading spinner)
     *  lives on. Set by the install site so per-display clean-capture
     *  blanking scopes correctly. */
    var displayId: Int = android.view.Display.DEFAULT_DISPLAY
    var params: WindowManager.LayoutParams? = null
    /** Active capture backend's overlay host: it places this window
     *  ([layoutAtRest]) and the loading spinner. Null for the Settings
     *  preview, which is never a window. */
    var overlayHost: OverlayHost? = null

    /** The slot the icon rests on: [setSlot]'s arguments, or the edge and
     *  fraction a snap decided. The one description of where the window
     *  belongs at rest; the host realises it against the display as it is
     *  ([layoutAtRest]), and nothing restores pixels. */
    private var dockSlot: Pair<Edge, Float>? = null

    /** The window has been placed on its slot once ([layoutAtRest]). */
    private var placedOnce = false

    /** Whether no gesture or glide owns the window. While one does, the
     *  window is wherever the finger or the glide put it, and a change of
     *  the display waits for its end, which settles. */
    private val atRest: Boolean
        get() = !hasActiveGesture && !inDragMode && snapAnimator?.isRunning != true

    /** The window origin for [slot] in [env] ([IconDock]). The dock lines
     *  are the display's edges moved inboard by what covers this window
     *  ([OverlayEnvironment.covered]): the cutout and, on the
     *  MediaProjection backend, whose windows lie below the system bars, a
     *  bar the app beneath shows. An accessibility overlay lies above the
     *  bars, nothing covers it, and it docks on the display edge as it
     *  always did, visible and reachable over the buttons: docking it
     *  inboard would hide its clipped outer half over the bar's inner strip
     *  while that half kept taking the bar's touches (a window's extent is
     *  its touch region, and the platform offers no way to shrink one). */
    private fun placeFor(slot: Pair<Edge, Float>, env: OverlayEnvironment): IconDock.Place =
        IconDock.place(
            env.geometry.width, env.geometry.height, env.covered,
            slot.first == Edge.LEFT, slot.second, viewSizePx, gestureInsetPx,
        )

    /**
     * This window's layout ([OverlayPlacement.Placed]), run by the host at
     * the add and whenever the display changes or something on it moved: at
     * rest, the window goes to its slot's place in [env]. While a gesture or
     * a glide owns the window it stays where they put it, and their end lays
     * the display out again ([settle]). Logged when the window moves, with
     * what its place was computed from, so a field report's screenshot can be
     * matched to a line.
     */
    fun layoutAtRest(env: OverlayEnvironment, p: WindowManager.LayoutParams) {
        if (!atRest) return
        val slot = dockSlot ?: return
        val place = placeFor(slot, env)
        if (p.x == place.x && p.y == place.y) return
        p.x = place.x
        p.y = place.y
        // A first placement (an install, or a re-raise's fresh window, every
        // live cycle on the accessibility backend) moved nothing on screen.
        val moved = placedOnce
        placedOnce = true
        if (!moved) return
        Log.i(
            TAG,
            "dock[$displayId] rot=${env.geometry.rotation} " +
                "screen=${env.geometry.width}x${env.geometry.height} covered=${env.covered} " +
                "edge=${slot.first} f=${slot.second} -> (${place.x},${place.y}) view=$viewSizePx",
        )
    }

    /** The window comes to rest: the host lays the display out again, this
     *  window on its slot and everything placed beside it (the spinner, the
     *  sonar intro, the floating menu) with it. Every gesture's end and every
     *  glide's end run it, so a change that came during a press or a glide
     *  is applied at the end without anything remembering that it did. A
     *  no-op while a gesture or glide owns the window. */
    private fun settle() {
        if (!atRest) return
        overlayHost?.invalidateLayout(displayId)
    }

    private var velocityTracker: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var totalMovement = 0f
    private var snapAnimator: ValueAnimator? = null
    private var lastXVel = 0f
    private var lastYVel = 0f
    /** True while a drag gesture is active. Set as soon as the user crosses
     *  the tap threshold so other code (popup dismissal, region-indicator
     *  restore) can tell "the user is dragging" before the screenshot lands.
     *  Until [dragVisualsRevealed] the icon draws nothing. */
    var inDragMode = false
        private set
    /** True once [revealDragVisuals] has moved the window to the finger and
     *  the ring + mag-glass appearance is up; false again when the drag
     *  ends. Between the drag's start and the reveal the window neither
     *  repaints visibly nor relayouts. The drag's clean capture is in flight
     *  then, and a visible commit of ours submitted around its freshness
     *  anchor is served as the "post-blank" frame: the MediaProjection gate
     *  takes the newest delivery above the anchor, and the old move-and-
     *  paint-the-ring at drag start was exactly such a commit, so the ring
     *  at the finger landed in every drag screenshot on a slow phone (Moto
     *  G, 2026-10-09). The capture path's own window-alpha blank stays the
     *  only blank; the icon never blanks itself by alpha, which would read
     *  to the host as nothing to blank and skip the gate. */
    var dragVisualsRevealed = false
        private set
    /** True while the user's finger is on the icon — ACTION_DOWN until
     *  ACTION_UP/ACTION_CANCEL. The z-order re-raise (remove + re-add of this
     *  window) must skip an icon with an active gesture: destroying the
     *  window mid-gesture drops the in-flight touch stream, so the finger-lift
     *  never reaches onTouchEvent and onHoldEnd/onDragEnd never fire. */
    var hasActiveGesture = false
        private set
    /** Whether onDragStart has already been called for this gesture. */
    private var dragStartFired = false
    /** Whether onHoldStart has fired for this gesture. */
    private var holdFired = false
    private val holdDelayMs = 400L
    private val holdRunnable = Runnable {
        if (!dragStartFired && totalMovement < tapThresholdPx) {
            if (holdStartsDrag) {
                // Forwarded intro gesture: a hold opens the magnifying
                // search, not the hold-for-translation preview. Anchor the
                // drag at the press point — the finger hasn't moved.
                beginDrag(downRawX, downRawY)
            } else {
                holdFired = true
                onHoldStart?.invoke()
            }
        }
    }
    /** Current snapped edge — used to position icon on the visible half. */
    var currentEdge = Edge.RIGHT
        private set

    private val tapThresholdPx = TAP_THRESHOLD_DP * resources.displayMetrics.density
    /** The clearance the centre keeps inside the top and bottom dock lines
     *  ([IconDock.verticalRange]): off the system gesture zones on a bare
     *  edge, and the same margin above a bar the window lies below. */
    private val gestureInsetPx = (48 * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingIcon"
        private const val FLING_THRESHOLD = 600f // px/s
        private const val TAP_THRESHOLD_DP = 18f
        private const val SNAP_DURATION_MS = 250L
        /** Idle delay before the icon darkens, and the dimmed level (fraction
         *  of full colour value). 0.6 cuts emitted light ~40% — meaningful
         *  burn-in relief while staying comfortably visible, including the
         *  live-mode status colour. */
        private const val IDLE_DIM_DELAY_MS = 60_000L
        private const val IDLE_DIM_LEVEL = 0.6f
        private const val DIM_FADE_MS = 600L
        private const val UNDIM_FADE_MS = 150L
        /** Vertical micro-orbit: a slow ±4px triangle wave, 1px every 30s (an
         *  8-min cycle). Sub-perceptual on a peripheral handle, but enough that
         *  the thin chevron's pixels each get regular off-frames. */
        private const val ORBIT_STEP_MS = 30_000L
        private val ORBIT_WAVE = intArrayOf(0, 1, 2, 3, 4, 3, 2, 1, 0, -1, -2, -3, -4, -3, -2, -1)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        gestureRect.set(0, 0, width, height)
        systemGestureExclusionRects = gestureRectList
    }

    override fun onDraw(canvas: Canvas) {
        val center = viewSizePx / 2f
        val r = circleSizePx / 2f
        val circleColor = when {
            liveMode && degraded -> liveDegradedCircleColor
            liveMode -> liveCircleColor
            else -> defaultCircleColor
        }

        if (inDragMode) {
            // Nothing until the lens reveals: the window's buffer goes
            // transparent in place (see dragVisualsRevealed), which is also
            // what keeps the capture path's alpha restore from flashing the
            // chevron back at the dock before the reveal moves the window.
            if (!dragVisualsRevealed) return
            // Ring only (transparent inside so the text under the finger
            // stays readable), with a small magnifying glass in the centre.
            canvas.drawCircle(center, center, r - ringPaint.strokeWidth / 2, ringPaint)
            drawMagnifyingGlass(canvas, center, center, r * 0.4f)
            return
        }
        // Compact (always): circle pushed off-screen so only ~1/4 is visible.
        // Burn-in: paint colours are value-scaled by dimLevel (idle darkening)
        // and the whole glyph rides the slow vertical micro-orbit via a canvas
        // translate.
        val compactOffset = r * 0.5f
        val cx = if (currentEdge == Edge.LEFT) center - compactOffset else center + compactOffset
        // Nothing past the dock line, which runs through the view's centre
        // ([IconDock]): off the display when docked on its edge, and over a
        // navigation bar when docked inboard of one, where the disc's far
        // side would otherwise show, cut flat by the view's own bound. Not
        // while a glide carries the window across the screen to its dock: the
        // whole disc travels, as it always did.
        if (snapAnimator?.isRunning != true) {
            if (currentEdge == Edge.LEFT) {
                canvas.clipRect(center, 0f, viewSizePx.toFloat(), viewSizePx.toFloat())
            } else {
                canvas.clipRect(0f, 0f, center, viewSizePx.toFloat())
            }
        }
        canvas.withTranslation(y = orbitDy) {
            applyDim(circlePaint, circleColor)
            canvas.drawCircle(cx, center, r, circlePaint)
            applyDim(borderPaint, borderColor)
            canvas.drawCircle(cx, center, r, borderPaint)
            // Arrow in the visible slice, nudged from the (off-screen) disc
            // centre toward the screen.
            val nudge = r * triangleNudge - triangleEdgePullPx
            val triCx = if (currentEdge == Edge.LEFT) cx + nudge else cx - nudge
            val paint = if (glyph == Glyph.ARROW_FILLED) triangleFillPaint else triangleStrokePaint
            applyDim(paint, arrowColor)
            drawEdgeTriangle(canvas, triCx, center, r * triangleHalfHeight, paint)
        }
    }

    /** Draws the Material `arrow_left` triangle — a 1:2 right isosceles
     *  triangle (width = half-height) with its tip toward the screen centre
     *  and its base toward the edge — filled or outlined per [paint]. */
    private fun drawEdgeTriangle(canvas: Canvas, cx: Float, cy: Float, halfH: Float, paint: Paint) {
        val halfW = halfH / 2
        val path = glyphPath.also { it.reset() }
        if (currentEdge == Edge.LEFT) {
            // Tip points right (toward the screen); base sits toward the edge.
            path.moveTo(cx + halfW, cy)
            path.lineTo(cx - halfW, cy - halfH)
            path.lineTo(cx - halfW, cy + halfH)
        } else {
            path.moveTo(cx - halfW, cy)
            path.lineTo(cx + halfW, cy - halfH)
            path.lineTo(cx + halfW, cy + halfH)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawMagnifyingGlass(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val glassR = size * 0.55f
        val glassOffY = -size * 0.15f
        // Glass circle
        canvas.drawCircle(cx + glassOffY, cy + glassOffY, glassR, magGlassPaint)
        // Handle line from bottom-right of circle
        val handleStartX = cx + glassOffY + glassR * 0.707f
        val handleStartY = cy + glassOffY + glassR * 0.707f
        val handleLen = size * 0.45f
        canvas.drawLine(
            handleStartX, handleStartY,
            handleStartX + handleLen * 0.707f, handleStartY + handleLen * 0.707f,
            magGlassPaint
        )
    }

    private fun enterDragMode() {
        if (inDragMode) return
        inDragMode = true
        invalidate()
    }

    private fun exitDragMode() {
        if (!inDragMode) return
        inDragMode = false
        dragVisualsRevealed = false
        dragStartFired = false
        invalidate()
    }

    /** Enters drag mode for a gesture at (rawX, rawY): the icon's buffer
     *  goes transparent where the window stands, and [onDragStart] fires
     *  followed by an initial [onDragMove]. The window does not move and the
     *  ring is not painted until [revealDragVisuals] (see
     *  [dragVisualsRevealed] for why). Shared by the move-threshold path
     *  ([onTouchEvent]'s ACTION_MOVE) and the hold-to-drag path
     *  ([holdRunnable]). */
    private fun beginDrag(rawX: Float, rawY: Float) {
        if (params == null) return
        enterDragMode()
        dragStartFired = true
        // Synchronous, and it must stay synchronous: ACTION_CANCEL/ACTION_UP
        // invoke their teardown callbacks inline, and a same-batch cancel
        // (the system pilfering the pointers for an edge/bottom swipe it
        // recognized just as the drag threshold tripped) is dispatched before
        // any posted message. A deferred start then runs AFTER the teardown,
        // re-arming the drag with no gesture left to end it — the capture
        // reveals a magnifier lens that nothing will ever dismiss.
        onDragStart?.invoke()
        onDragMove?.invoke(rawX, rawY)
    }

    /** Brings the drag's visuals up at the finger, (rawX, rawY): the window
     *  is centred there, later moves are relative to it, and the ring +
     *  magnifying glass paint from the next frame. The drag controller calls
     *  this in the same step it shows the lens, once the clean screenshot is
     *  in hand (or has failed). Idempotent; a no-op once the drag ended. */
    fun revealDragVisuals(rawX: Float, rawY: Float) {
        if (!inDragMode || dragVisualsRevealed) return
        dragVisualsRevealed = true
        val p = params ?: return
        // Centre the window on the finger: the user may have grabbed the
        // icon off-centre, or (for a forwarded intro gesture) pressed the
        // carrier while it was animating inboard of the dock edge.
        p.x = (rawX - viewHalf).toInt()
        p.y = (rawY - viewHalf).toInt()
        // Rebase so later moves are relative to this centred position.
        downRawX = rawX
        downRawY = rawY
        downParamX = p.x
        downParamY = p.y
        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
        invalidate()
    }

    /** TalkBack double-tap path. Maps to the same callback a short tap
     *  fires through the gesture machinery in [onTouchEvent] — drag/hold
     *  aren't reachable without continuous motion, so [onTap] (whatever the
     *  tap binding in IconGestureBindings.kt does) is the only sensible
     *  accessibility-click action on a floating icon. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onAnyTouch?.invoke()
        wakeFromIdle()
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The gesture owns the window before the glide it interrupts
                // ends: the glide's end settles only at rest, so the window
                // stays where the finger caught it.
                hasActiveGesture = true
                snapAnimator?.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params?.x ?: 0
                downParamY = params?.y ?: 0
                totalMovement = 0f
                lastXVel = 0f
                lastYVel = 0f
                dragStartFired = false
                holdFired = false
                postDelayed(holdRunnable, holdDelayMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                lastXVel = velocityTracker?.xVelocity ?: 0f
                lastYVel = velocityTracker?.yVelocity ?: 0f

                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                totalMovement = abs(dx) + abs(dy)
                val p = params ?: return true
                // The window follows the finger only while the drag's visuals
                // are up. Inside the tap slop it stays put, as any view does
                // within slop; from the drag's start to its reveal it must:
                // a move is a visible commit of this window, submitted right
                // where the drag's clean capture anchors its freshness gate
                // (see dragVisualsRevealed).
                if (dragVisualsRevealed) {
                    p.x = (downParamX + dx).toInt()
                    p.y = (downParamY + dy).toInt()
                    try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                }

                // Once a drag is under way every move feeds onDragMove —
                // tapThresholdPx is only the *entry* gate, and totalMovement
                // can dip back under it when the finger returns toward the
                // start point.
                if (dragStartFired || totalMovement >= tapThresholdPx) {
                    removeCallbacks(holdRunnable)
                    // If hold was active, cancel it and transition to drag
                    if (holdFired) {
                        holdFired = false
                        onHoldCancel?.invoke()
                    }
                    if (!dragStartFired) {
                        beginDrag(event.rawX, event.rawY)
                    } else {
                        onDragMove?.invoke(event.rawX, event.rawY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                hasActiveGesture = false
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                lastXVel = velocityTracker?.xVelocity ?: lastXVel
                lastYVel = velocityTracker?.yVelocity ?: lastYVel
                velocityTracker?.recycle()
                velocityTracker = null

                // Capture before exitDragMode clears it. dragStartFired is the
                // canonical "this gesture became a drag" signal — totalMovement
                // can't be trusted here because the drag-start logic rebases
                // downRawX/Y to the finger position when crossing the tap
                // threshold, so a user who barely moves past the threshold and
                // then releases ends up with totalMovement below it again.
                // Without this capture, the ACTION_UP would take the onTap
                // branch and skip onDragEnd, leaving the magnifier on screen.
                val wasDragStarted = dragStartFired
                exitDragMode()
                removeCallbacks(holdRunnable)

                // Check both the flag AND elapsed time — the holdRunnable may
                // not have executed yet if the main thread was busy.
                val heldLongEnough = event.eventTime - event.downTime >= holdDelayMs
                if (wasDragStarted) {
                    if (onDragEnd?.invoke() == true) {
                        // The release committed the sticky lens: straight to
                        // the dock, no glide. The definitions bind and the
                        // full-screen lens relayout own the next frames, and
                        // a concurrent glide froze mid-screen (~50 ms stall
                        // measured, parked near panel centre). The lens is
                        // the payload; the icon just reappears at its dock.
                        settle()
                    } else {
                        snapToEdge(lastXVel, lastYVel)
                    }
                } else if (holdFired || (heldLongEnough && totalMovement < tapThresholdPx)) {
                    holdFired = false
                    // The window did not move (it follows the finger only
                    // once the drag's visuals are up); a bar that appeared
                    // during the hold is applied here.
                    settle()
                    onHoldEnd?.invoke()
                } else if (totalMovement < tapThresholdPx) {
                    // Before the tap's menu anchors on the icon.
                    settle()
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hasActiveGesture = false
                velocityTracker?.recycle()
                velocityTracker = null
                removeCallbacks(holdRunnable)
                if (holdFired) { holdFired = false; onHoldEnd?.invoke() }
                val wasInDrag = inDragMode
                exitDragMode()
                if (wasInDrag) {
                    // System-driven cancellation (focus loss, parent intercept).
                    // Glide back to the slot rather than snapping to a new
                    // one: the user didn't pick a new spot, so an interrupted
                    // drag shouldn't reposition their icon as a side effect.
                    glideToSlot()
                    onDragCancel?.invoke()
                } else {
                    // The window never moved; nothing to snap.
                    settle()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(xVel: Float, yVel: Float) {
        val p = params ?: return
        // The display as it is at the release: the decision is taken once,
        // here, and the glide's end lays the window out against the display
        // as it is then.
        val env = overlayHost?.environment(displayId) ?: return
        val cx = p.x + viewHalf
        val cy = p.y + viewHalf
        val screenW = env.geometry.width
        val screenH = env.geometry.height
        val covered = env.covered

        val dragDx = p.x - downParamX
        val flingMatchesDrag = (xVel > 0) == (dragDx > 0) && dragDx != 0
        val hasFling = abs(xVel) > FLING_THRESHOLD && flingMatchesDrag

        val edge = when {
            hasFling && xVel < 0 -> Edge.LEFT
            hasFling && xVel > 0 -> Edge.RIGHT
            cx < screenW / 2 -> Edge.LEFT
            else -> Edge.RIGHT
        }

        val edgeCx = IconDock.dockX(screenW, covered, edge == Edge.LEFT)
        val targetCy: Int = if (hasFling && abs(xVel) > 1f) {
            (cy + (edgeCx - cx).toFloat() / xVel * yVel).toInt()
        } else {
            cy
        }
        // The release decides the slot; the glide is the transition to its
        // place, and the glide's end settles, so a bar or display change
        // during it is applied then.
        val fraction = IconDock.fraction(targetCy, screenH, covered, gestureInsetPx)
        dock(edge, fraction)
        onPositionChanged?.invoke(edge.ordinal, fraction)
        glideToSlot()
    }

    /** Record [edge] and [fraction] as the slot the window rests on. */
    private fun dock(edge: Edge, fraction: Float) {
        dockSlot = edge to fraction
        if (currentEdge != edge) {
            currentEdge = edge
            invalidate()
        }
    }

    /** Glide the window to [dockSlot]'s place in the display as it is now;
     *  the glide's end settles. A touch down mid-glide cancels it, and the
     *  end's settle finds the new gesture owning the window and leaves it
     *  there. */
    private fun glideToSlot() {
        val slot = dockSlot ?: return
        val p = params ?: return
        val env = overlayHost?.environment(displayId) ?: return
        snapAnimator?.cancel()
        val target = placeFor(slot, env)
        val startX = p.x
        val startY = p.y
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = (startX + (target.x - startX) * t).toInt()
                p.y = (startY + (target.y - startY) * t).toInt()
                try { wm?.updateViewLayout(this@FloatingOverlayIcon, p) } catch (_: Exception) {}
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Clipped at the dock line again (onDraw).
                    invalidate()
                    settle()
                }
            })
            start()
        }
        // The whole disc for the glide (onDraw).
        invalidate()
    }

    /** Sets the slot from the persisted edge + fraction (a fraction outside
     *  0..1 reads as the middle). No pixels: the host places the window on
     *  the slot when it is added ([layoutAtRest]), and the Settings preview,
     *  which is never a window, takes only the edge it draws on. */
    fun setSlot(edgeOrdinal: Int, fraction: Float) {
        val edge = if (edgeOrdinal == Edge.LEFT.ordinal) Edge.LEFT else Edge.RIGHT
        dock(edge, if (fraction in 0f..1f) fraction else 0.5f)
    }

    // ── OLED burn-in mitigation: idle-dim + micro-orbit drivers ─────────
    /** Applies the idle dim to [baseColor] by scaling its RGB, leaving its
     *  alpha exactly as authored. Fading the icon out instead would make it
     *  harder to find at a glance and wouldn't even buy the burn-in relief
     *  reliably — a translucent dark disc over a bright game frame gets
     *  *brighter* as it fades, since more of the frame shows through. Scaling
     *  the colour value cuts emitted light directly. The disc's near-black
     *  fill has nowhere to go, so the visible effect is the silver chevron,
     *  the grey border, and the live-mode status colour going darker. */
    private fun applyDim(paint: Paint, baseColor: Int) {
        paint.color = if (dimLevel >= 1f) baseColor else Color.argb(
            Color.alpha(baseColor),
            (Color.red(baseColor) * dimLevel).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * dimLevel).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * dimLevel).toInt().coerceIn(0, 255),
        )
    }

    private val idleDimRunnable = object : Runnable {
        override fun run() {
            if (hasActiveGesture) {
                // Finger still down (e.g. a long hold-for-translation) — don't
                // dim mid-gesture; re-arm and let the lift re-evaluate.
                postDelayed(this, IDLE_DIM_DELAY_MS)
            } else {
                animateDim(IDLE_DIM_LEVEL)
            }
        }
    }

    private fun animateDim(target: Float) {
        if (dimLevel == target && dimAnimator?.isRunning != true) return
        dimAnimator?.cancel()
        // Gentle darkening, quick restore.
        val dur = if (target < dimLevel) DIM_FADE_MS else UNDIM_FADE_MS
        dimAnimator = ValueAnimator.ofFloat(dimLevel, target).apply {
            duration = dur
            addUpdateListener { dimLevel = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Restore full brightness and restart the idle countdown — driven off
     *  every touch so the icon is never dim while in use. */
    private fun wakeFromIdle() {
        removeCallbacks(idleDimRunnable)
        animateDim(1f)
        postDelayed(idleDimRunnable, IDLE_DIM_DELAY_MS)
    }

    private val orbitRunnable = object : Runnable {
        override fun run() {
            orbitStep = (orbitStep + 1) % ORBIT_WAVE.size
            orbitDy = ORBIT_WAVE[orbitStep].toFloat()
            invalidate()
            postDelayed(this, ORBIT_STEP_MS)
        }
    }

    private fun startOrbit() {
        removeCallbacks(orbitRunnable)
        postDelayed(orbitRunnable, ORBIT_STEP_MS)
    }

    private fun stopOrbit() {
        removeCallbacks(orbitRunnable)
        orbitStep = 0
        orbitDy = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dimLevel = 1f
        postDelayed(idleDimRunnable, IDLE_DIM_DELAY_MS)
        startOrbit()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideSpinnerWindow()
        removeCallbacks(idleDimRunnable)
        dimAnimator?.cancel()
        dimAnimator = null
        dimLevel = 1f
        stopOrbit()
    }

    /** Call when the icon is permanently removed, not just temporarily detached.
     *  Currently a no-op (no owned resources to free) — kept as a hook so
     *  callers don't have to track whether destroy is still meaningful. */
    fun destroy() {
        // Intentionally empty.
    }
}
