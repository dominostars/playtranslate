package com.playtranslate.ui

import android.content.Context
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Right-stick vertical scroll drive for a focused overlay surface: a
 * Choreographer repeater that turns stick deflection into per-frame
 * [onStep] scroll deltas. The event-handling rules are the hard-won ones
 * from [CaptureSheetControllerNav] / [MagnifierLens]:
 *
 *  - Hat-driven dpads bail UNCONSUMED so ViewRootImpl keeps synthesizing the
 *    DPAD key events navigation runs on.
 *  - Left-stick X/Y motion is left alone entirely — unlike the capture sheet
 *    (whose left stick is the grabber drag), a workspace-style surface wants
 *    ViewRootImpl's synthetic DPAD keys from the left stick, so nothing here
 *    may consume those MOVEs.
 *  - The right stick resolves to AXIS_RZ when the device declares a range,
 *    else AXIS_RY; the dead zone is the device's reported flat raised to
 *    [CaptureSheetControllerNav.STICK_DEAD_ZONE].
 *  - The centering MOVE of a deflection this drive owned is consumed (it
 *    carries no other information), and a dropped-frame gap is clamped so a
 *    jank spike can't teleport the scroll.
 *  - [suspendWhen] is checked every FRAME, not only per event: a modal
 *    opening mid-deflection swallows the centering MOVE in the caller's
 *    event path, after which a stick at rest emits no further events at all —
 *    without the per-frame check the stale deflection would drive forever.
 */
class StickScrollDrive(
    ctx: Context,
    private val onStep: (dyPx: Int) -> Unit,
    private val suspendWhen: () -> Boolean = { false },
) {
    private val density = ctx.resources.displayMetrics.density

    private var active = false
    private var stickY = 0f
    private var deadZone = CaptureSheetControllerNav.STICK_DEAD_ZONE

    /** Feed joystick ACTION_MOVEs. True = consumed (a deflection this drive
     *  owns, or its centering event). */
    fun handleGenericMotion(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (suspendWhen()) return false
        // Hat-driven dpad: leave the event UNCONSUMED so ViewRootImpl's
        // synthetic joystick handler still converts AXIS_HAT_* into DPAD keys.
        if (ev.getAxisValue(MotionEvent.AXIS_HAT_X) != 0f ||
            ev.getAxisValue(MotionEvent.AXIS_HAT_Y) != 0f
        ) {
            return false
        }
        val device = ev.device
        // Standard gamepad mapping puts the right stick on Z/RZ; a controller
        // that declares no RZ range reports it as RX/RY instead.
        val ryAxis =
            if (device?.getMotionRange(MotionEvent.AXIS_RZ, ev.source) != null) MotionEvent.AXIS_RZ
            else MotionEvent.AXIS_RY
        val flat = device?.getMotionRange(ryAxis, ev.source)?.flat ?: 0f
        deadZone = maxOf(flat, CaptureSheetControllerNav.STICK_DEAD_ZONE)
        val ry = ev.getAxisValue(ryAxis)
        val now = abs(ry) > deadZone

        val ownedBefore = active
        active = now
        stickY = ry

        if (!now) {
            stopRepeater()
            // Consume only the centering event of a deflection we owned; an
            // idle-stick MOVE (left-stick nav motion) falls through so the
            // synthetic DPAD handler can key off it.
            return ownedBefore
        }
        startRepeater()
        return true
    }

    private var repeaterRunning = false
    private var lastFrameNs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!repeaterRunning) return
            if (suspendWhen()) {
                suspend()
                return
            }
            // Clamp a dropped-frame gap so a jank spike can't teleport the drive.
            val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
            lastFrameNs = frameTimeNanos
            val mag = ((abs(stickY) - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
            onStep(
                (sign(stickY) * mag *
                    CaptureSheetControllerNav.STICK_MAX_DP_PER_SEC * density * dt).roundToInt(),
            )
            Choreographer.getInstance().postFrameCallback(this)
        }
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

    /** Stop the loop and forget the deflection (modal entry, teardown). */
    fun suspend() {
        active = false
        stopRepeater()
    }

    /** Teardown alias — stops timers. */
    fun release() = suspend()
}
