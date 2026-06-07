package com.playtranslate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.displaySizePx
import com.playtranslate.displayWindowMetrics
import kotlin.math.abs
import androidx.core.graphics.toColorInt

/**
 * A circular floating icon that snaps to left/right screen edges, pushed off-
 * screen so only a ~1/4-circle edge-arrow is visible. Supports drag, fling,
 * and tap.
 *
 * Position is persisted as edge (LEFT=0, RIGHT=1) + fraction (0..1) along
 * that edge vertically.
 *
 * During a drag, switches to a "magnifying glass ring" appearance so the
 * text underneath is visible for screenshot capture.
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
        set(value) { field = value; invalidate() }

    /** When true (and liveMode), the circle turns yellow to indicate degraded translation. */
    var degraded = false
        set(value) { field = value; invalidate() }

    // ── Normal mode paints ──────────────────────────────────────────────
    private val defaultCircleColor = "#CC000000".toColorInt()
    private val liveCircleColor = "#CC990000".toColorInt()
    private val liveDegradedCircleColor = "#CC999900".toColorInt()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = defaultCircleColor
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#66888888".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    // Scratch objects reused in onLayout/onDraw — allocating per frame is lint DrawAllocation.
    private val gestureRect = Rect()
    private val gestureRectList = listOf(gestureRect)

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
        val p = params ?: return
        val spinSize = (28 * resources.displayMetrics.density).toInt()
        val padding = (6 * resources.displayMetrics.density).toInt()
        val totalSize = spinSize + padding * 2
        val gap = (32 * resources.displayMetrics.density).toInt()

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

        // Position to the visible side of the icon
        val spinX = if (currentEdge == Edge.LEFT) {
            p.x + viewSizePx + gap
        } else {
            p.x - totalSize - gap
        }
        val spinY = p.y + (viewSizePx - totalSize) / 2

        // On the MediaProjection backend the spinner must stay touchable: a
        // non-touchable TYPE_APPLICATION_OVERLAY is opacity-capped. The icon's
        // window owns the hold gesture, so a touchable spinner steals nothing.
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (overlayHost?.windowType != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val lp = WindowManager.LayoutParams(
            totalSize, totalSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            x = spinX
            y = spinY
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
        }

        val host = overlayHost
        val added = if (host != null) host.addOverlayWindow(spinner, wm, lp, displayId)
            else PlayTranslateAccessibilityService.addOverlay(spinner, wm, lp, displayId)
        if (!added) return
        spinnerView = spinner
        spinnerWm = wm
    }

    private fun hideSpinnerWindow() {
        val view = spinnerView
        val w = spinnerWm
        if (view != null && w != null) {
            val host = overlayHost
            if (host != null) host.removeOverlayWindow(view)
            else PlayTranslateAccessibilityService.removeOverlay(view, w)
        }
        spinnerView = null
        spinnerWm = null
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
    /** Called once when drag mode activates (past tap threshold). Icon is now transparent. */
    var onDragStart: (() -> Unit)? = null
    /** Called on every ACTION_MOVE during a drag (rawX, rawY screen coords). */
    var onDragMove: ((Float, Float) -> Unit)? = null
    /** Called on ACTION_UP after a drag. Return true if popup is active (icon returns to saved pos). */
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
    /** Active capture backend's overlay host, used to attach the loading
     *  spinner sub-window with the right window type. Null falls back to the
     *  accessibility-service path (which fails on the MediaProjection
     *  backend, where no accessibility service is connected). */
    var overlayHost: OverlayHost? = null

    private fun queryScreenSize(): Point = context.displaySizePx()

    private val screenW: Int get() = queryScreenSize().x
    private val screenH: Int get() = queryScreenSize().y

    /** Display-cutout safe insets in the icon's coordinate space. The icon's
     *  window has [FLAG_LAYOUT_NO_LIMITS] so its origin is at the absolute
     *  display origin (not the cutout-safe origin); on devices with a notch
     *  this means snapping x=0 lands the icon behind the cutout. Edge-snap
     *  code consults these insets so "Edge.LEFT" means "left of the safe
     *  area" instead of "left of the display". */
    private fun cutoutSafeInsetX(): Pair<Int, Int> {
        // WindowMetrics.windowInsets is API 30+. displayWindowMetrics() already
        // returns null below R, but guard explicitly so the API-30 member access
        // is off the API-29 compile path. Pre-30 the icon skips cutout-safe
        // insetting (no notch handling) — acceptable.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0 to 0
        val cutout = context.displayWindowMetrics()?.windowInsets?.displayCutout
            ?: return 0 to 0
        return cutout.safeInsetLeft to cutout.safeInsetRight
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
    /** Saved position before drag started (for restoring when popup is shown). */
    private var savedParamX = 0
    private var savedParamY = 0
    /** True while a drag gesture is active. Set as soon as the user crosses
     *  the tap threshold so other code (popup dismissal, region-indicator
     *  restore) can tell "the user is dragging" before the screenshot lands.
     *  Also gates the ring + mag-glass appearance — the screenshot pipeline
     *  blanks the icon's window during capture, so we can flip the visuals
     *  immediately without contaminating the captured pixels. */
    var inDragMode = false
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
    /** Inset from top/bottom to avoid system gesture zones. */
    private val gestureInsetPx = (48 * resources.displayMetrics.density).toInt()
    private val minCy get() = gestureInsetPx
    private val maxCy get() = screenH - gestureInsetPx

    companion object {
        private const val FLING_THRESHOLD = 600f // px/s
        private const val TAP_THRESHOLD_DP = 18f
        private const val SNAP_DURATION_MS = 250L
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        gestureRect.set(0, 0, width, height)
        systemGestureExclusionRects = gestureRectList
    }

    override fun onDraw(canvas: Canvas) {
        val center = viewSizePx / 2f
        val r = circleSizePx / 2f
        circlePaint.color = when {
            liveMode && degraded -> liveDegradedCircleColor
            liveMode -> liveCircleColor
            else -> defaultCircleColor
        }

        if (inDragMode) {
            // Ring only (transparent inside so text is visible for screenshot).
            // The screenshot path blanks the icon's window via window-level
            // alpha during the actual capture, so the ring won't appear in
            // the captured pixels even though we paint it immediately.
            canvas.drawCircle(center, center, r - ringPaint.strokeWidth / 2, ringPaint)
            // Small magnifying glass icon in center
            drawMagnifyingGlass(canvas, center, center, r * 0.4f)
            return
        }
        // Compact (always): circle pushed off-screen so only ~1/4 is visible.
        val compactOffset = r * 0.5f
        val cx = if (currentEdge == Edge.LEFT) center - compactOffset else center + compactOffset
        canvas.drawCircle(cx, center, r, circlePaint)
        canvas.drawCircle(cx, center, r, borderPaint)
        // Arrow in the visible slice, nudged toward the screen edge.
        val arrowNudge = r * 0.65f
        val arrowCx = if (currentEdge == Edge.LEFT) cx + arrowNudge else cx - arrowNudge
        drawEdgeArrow(canvas, arrowCx, center, r * 0.22f)
    }

    /** Draws a small arrow pointing toward the screen center (away from the edge). */
    private fun drawEdgeArrow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val path = android.graphics.Path()
        val hw = size * 0.5f  // half-width (horizontal)
        val hh = size * 0.7f  // half-height (vertical)
        if (currentEdge == Edge.LEFT) {
            // Arrow pointing right (toward screen)
            path.moveTo(cx + hw, cy)
            path.lineTo(cx - hw * 0.3f, cy - hh)
            path.lineTo(cx - hw * 0.3f, cy + hh)
        } else {
            // Arrow pointing left (toward screen)
            path.moveTo(cx - hw, cy)
            path.lineTo(cx + hw * 0.3f, cy - hh)
            path.lineTo(cx + hw * 0.3f, cy + hh)
        }
        path.close()
        canvas.drawPath(path, arrowPaint)
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
        dragStartFired = false
        invalidate()
    }

    /** Enters drag mode for a gesture anchored at (rawX, rawY): re-centres
     *  the icon window on that point, flips to the ring + magnifying-glass
     *  appearance, and fires [onDragStart] followed by an initial
     *  [onDragMove]. Shared by the move-threshold path ([onTouchEvent]'s
     *  ACTION_MOVE) and the hold-to-drag path ([holdRunnable]). */
    private fun beginDrag(rawX: Float, rawY: Float) {
        val p = params ?: return
        // Centre the icon on the finger — the user may have grabbed it
        // off-centre, or (for a forwarded intro gesture) pressed the
        // carrier while it was animating inboard of the dock edge.
        p.x = (rawX - viewHalf).toInt()
        p.y = (rawY - viewHalf).toInt()
        // Rebase so future moves are relative to this centred position.
        downRawX = rawX
        downRawY = rawY
        downParamX = p.x
        downParamY = p.y
        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
        enterDragMode()
        dragStartFired = true
        post { onDragStart?.invoke() }
        onDragMove?.invoke(rawX, rawY)
    }

    /** TalkBack double-tap path. Maps to the same callback a short tap
     *  fires through the gesture machinery in [onTouchEvent] — drag/hold
     *  aren't reachable without continuous motion, so [onTap] is the only
     *  sensible accessibility-click action on a floating icon. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onAnyTouch?.invoke()
        parent?.requestDisallowInterceptTouchEvent(true)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params?.x ?: 0
                downParamY = params?.y ?: 0
                savedParamX = downParamX
                savedParamY = downParamY
                totalMovement = 0f
                lastXVel = 0f
                lastYVel = 0f
                dragStartFired = false
                holdFired = false
                hasActiveGesture = true
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
                p.x = (downParamX + dx).toInt()
                p.y = (downParamY + dy).toInt()
                try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}

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
                        restorePosition()
                    } else {
                        snapToEdge(lastXVel, lastYVel)
                    }
                } else if (holdFired || (heldLongEnough && totalMovement < tapThresholdPx)) {
                    holdFired = false
                    val p = params
                    if (p != null) {
                        p.x = savedParamX
                        p.y = savedParamY
                        try { wm?.updateViewLayout(this, p) } catch (_: Exception) {}
                    }
                    onHoldEnd?.invoke()
                } else if (totalMovement < tapThresholdPx) {
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
                    // Revert to the icon's pre-gesture position rather than
                    // snapping to an edge — the user didn't pick a new spot,
                    // so an interrupted drag shouldn't reposition their icon
                    // as a side effect.
                    restorePosition()
                    onDragCancel?.invoke()
                } else {
                    snapToEdge(0f, 0f)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(xVel: Float, yVel: Float) {
        val p = params ?: return
        val cx = p.x + viewHalf
        val cy = p.y + viewHalf

        val dragDx = p.x - downParamX
        val flingMatchesDrag = (xVel > 0) == (dragDx > 0) && dragDx != 0
        val hasFling = abs(xVel) > FLING_THRESHOLD && flingMatchesDrag

        val edge = when {
            hasFling && xVel < 0 -> Edge.LEFT
            hasFling && xVel > 0 -> Edge.RIGHT
            cx < screenW / 2 -> Edge.LEFT
            else -> Edge.RIGHT
        }

        val (cutLeft, cutRight) = cutoutSafeInsetX()
        val edgeCx = if (edge == Edge.LEFT) cutLeft else screenW - cutRight
        val targetX = edgeCx - viewHalf

        val targetCy: Int = if (hasFling && abs(xVel) > 1f) {
            (cy + (edgeCx - cx).toFloat() / xVel * yVel).toInt()
        } else {
            cy
        }

        val targetY = targetCy.coerceIn(minCy, maxCy) - viewHalf
        animateTo(targetX, targetY, edge)
    }

    private fun animateTo(targetX: Int, targetY: Int, edge: Edge? = null) {
        val p = params ?: return
        val startX = p.x
        val startY = p.y
        if (edge != null) {
            currentEdge = edge
            invalidate()
        }
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                p.x = (startX + (targetX - startX) * t).toInt()
                p.y = (startY + (targetY - startY) * t).toInt()
                try { wm?.updateViewLayout(this@FloatingOverlayIcon, p) } catch (_: Exception) {}
            }
            if (edge != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val cy = p.y + viewHalf
                        val range = maxCy - minCy
                        val fraction = if (range > 0) (cy - minCy).toFloat() / range else 0.5f
                        onPositionChanged?.invoke(edge.ordinal, fraction.coerceIn(0f, 1f))
                    }
                })
            }
            start()
        }
    }

    private fun restorePosition() {
        animateTo(savedParamX, savedParamY)
    }

    /** Sets position from persisted edge + fraction without animation. */
    fun setPosition(edgeOrdinal: Int, fraction: Float) {
        val edge = if (edgeOrdinal == Edge.LEFT.ordinal) Edge.LEFT else Edge.RIGHT
        currentEdge = edge
        val f = if (fraction in 0f..1f) fraction else 0.5f
        val p = params ?: return
        val (cutLeft, cutRight) = cutoutSafeInsetX()
        p.x = if (edge == Edge.LEFT) cutLeft - viewHalf else screenW - cutRight - viewHalf
        val cy = (minCy + f * (maxCy - minCy)).toInt().coerceIn(minCy, maxCy)
        p.y = cy - viewHalf
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideSpinnerWindow()
    }

    /** Call when the icon is permanently removed, not just temporarily detached.
     *  Currently a no-op (no owned resources to free) — kept as a hook so
     *  callers don't have to track whether destroy is still meaningful. */
    fun destroy() {
        // Intentionally empty.
    }
}
