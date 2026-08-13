package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Right-stick scrolling for every in-app page, installed once from the
 *  Application: each activity's Window.Callback is wrapped at create time, so
 *  no page implements anything and future pages get it for free. Joystick
 *  MotionEvents enter an activity through that callback before the view tree,
 *  which makes it the one seam above the pages' three different base classes.
 *
 *  Unlike the capture sheet's stick handling this is purely observational —
 *  the wrapper never consumes an event. ViewRootImpl synthesizes DPAD keys
 *  only off AXIS_X/Y + hat, never Z/RZ, so the right stick can be read while
 *  every event is left for the synthetic handler: the left stick and hat keep
 *  walking real view focus exactly as before. (The sheet must consume because
 *  its LEFT stick is the grabber drag; no such claim exists here.)
 *
 *  Vertical only, same feel dials as the sheet and lens
 *  ([CaptureSheetControllerNav.STICK_DEAD_ZONE] /
 *  [CaptureSheetControllerNav.STICK_MAX_DP_PER_SEC]), and no arming gate:
 *  activities are naturally focused, and a device without a controller simply
 *  never emits joystick events.
 */
object ActivityStickScroll {

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Fires during super.onCreate(), after AppCompat's delegate has
                // already installed its own callback wrapper — ours layers on
                // top of it and delegates everything through. Each recreate is
                // a fresh Activity, so double-wrapping can't occur.
                val window = activity.window ?: return
                window.callback = StickScrollCallback(activity, window.callback)
            }

            // Focus loss covers dialogs and most departures, but a
            // multi-window pause can leave the window focus-ambiguous —
            // suspend on pause too so a held deflection can never outlive the
            // page that owned it (the stale-deflection class: once events stop
            // arriving, the centering MOVE that would end the drive never
            // comes).
            override fun onActivityPaused(activity: Activity) {
                (activity.window?.callback as? StickScrollCallback)?.suspendStick()
            }

            override fun onActivityDestroyed(activity: Activity) {
                (activity.window?.callback as? StickScrollCallback)?.suspendStick()
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}

private class StickScrollCallback(
    private val activity: Activity,
    private val delegate: Window.Callback,
) : Window.Callback by delegate {

    private val density = activity.resources.displayMetrics.density
    private var rightY = 0f
    private var rightDeadZone = CaptureSheetControllerNav.STICK_DEAD_ZONE

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = delegate.dispatchGenericMotionEvent(event)
        if (event.actionMasked != MotionEvent.ACTION_MOVE ||
            event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK
        ) {
            return handled
        }
        if (handled) {
            // A view below claimed the joystick — it owns the deflection.
            // (No activity view does today; this keeps a future consumer from
            // being double-driven.)
            suspendStick()
            return true
        }

        // Standard gamepad mapping puts the right stick on Z/RZ; a controller
        // that declares no RZ range reports it as RX/RY instead. Same
        // resolution as the capture sheet's.
        val device = event.device
        val axis =
            if (device?.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null) MotionEvent.AXIS_RZ
            else MotionEvent.AXIS_RY
        rightDeadZone = maxOf(
            device?.getMotionRange(axis, event.source)?.flat ?: 0f,
            CaptureSheetControllerNav.STICK_DEAD_ZONE,
        )
        rightY = event.getAxisValue(axis)
        if (abs(rightY) > rightDeadZone) startRepeater() else stopRepeater()
        // Never consumed: X/Y + hat stay visible to ViewRootImpl's synthetic
        // DPAD handler, and nothing downstream reacts to a bare Z/RZ event.
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        // A dialog opening (its own window takes focus) or the activity
        // leaving mid-deflection stops joystick events without a centering
        // MOVE — the event path alone would leave a stale deflection
        // scrolling forever.
        if (!hasFocus) suspendStick()
        delegate.onWindowFocusChanged(hasFocus)
    }

    // ── Repeater: held deflection = velocity, stepped per frame ──────────

    private var repeaterRunning = false
    private var lastFrameNs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!repeaterRunning) return
            // Clamp a dropped-frame gap so a jank spike can't teleport the
            // scroll.
            val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNs = frameTimeNanos
            val direction = if (rightY > 0f) 1 else -1
            val mag = ((abs(rightY) - rightDeadZone) / (1f - rightDeadZone)).coerceIn(0f, 1f)
            scrollTarget(direction)?.scrollBy(
                0,
                (sign(rightY) * mag * CaptureSheetControllerNav.STICK_MAX_DP_PER_SEC * density * dt)
                    .roundToInt(),
            )
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** The view this frame's scroll drives, or null for a stick no-op (page
     *  with nothing scrollable, or nothing scrollable further in [direction]).
     *  Resolved per frame, not per deflection: focus moves and content
     *  appears/disappears while the stick is held, and the walk is trivial
     *  over settings-sized trees. Preference order: the focused view's
     *  nearest scrollable ancestor (so a focused inner scroller wins), else
     *  the first visible scrollable in decor order — which on every current
     *  page is the main content scroller. */
    private fun scrollTarget(direction: Int): View? {
        val decor = activity.window?.peekDecorView() ?: return null
        var v: View? = activity.window?.currentFocus
        while (v != null) {
            if (v.canScrollVertically(direction)) return v
            v = v.parent as? View
        }
        return firstScrollable(decor, direction)
    }

    private fun firstScrollable(v: View, direction: Int): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.canScrollVertically(direction)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstScrollable(v.getChildAt(i), direction)?.let { return it }
            }
        }
        return null
    }

    private fun startRepeater() {
        if (repeaterRunning) return
        repeaterRunning = true
        lastFrameNs = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopRepeater() {
        if (!repeaterRunning) return
        repeaterRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun suspendStick() {
        rightY = 0f
        stopRepeater()
    }
}
