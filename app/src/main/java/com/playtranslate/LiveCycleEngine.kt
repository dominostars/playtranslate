package com.playtranslate

import android.os.SystemClock
import android.util.Log
import com.playtranslate.capture.LiveCaptureSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The cycle scheduler shared by the live modes ([PinholeOverlayMode],
 * [ReconcilerLiveMode]): pacing delay → delivery-gate park → one [cycle] →
 * reschedule, plus the force flag and the A4 input-burst machinery that
 * feed the gate. Extracted because two hand-kept copies of this exact
 * logic had already begun to drift (the hold-deadline bound existed in one,
 * the identity-fallback source fork in the other) — and every subtle fix
 * here must land in ALL live loops or none.
 *
 * ## The delivery gate ([awaitCycleReason])
 * Park before the cycle until there is a reason to run it. Reasons, in the
 * order checked:
 *  - the capture source exposes no [com.playtranslate.capture.DeliverySignal]
 *    (accessibility `takeScreenshot` — no silence evidence, poll exactly as
 *    before);
 *  - a hold is active (the cycle's 100 ms hold-poll must keep polling);
 *  - a forced cycle is pending ([forceNext]);
 *  - the mirror delivered a frame newer than the one the last cycle consumed
 *    (`seqNow > lastServedSeq`);
 *  - [parkDeadlineMs] (when the owner supplies one — the reconciler's
 *    stability-hold cap) expired: a typewriter that finishes into a static
 *    screen must still get its releasing read.
 *
 * Pacing (the `delay` before the park) always runs first, so cycles can
 * never fire closer together than they did on the blind timer — the gate
 * only ever *skips* work. The park is a cancellable suspend; stop/refresh/
 * dismiss cancel the current job while parked exactly as they cancelled a
 * pending `delay`.
 *
 * ## Loop integrity
 * The loop is a cancel-and-relaunch chain: each job launches its successor.
 * That shape is only sound if a cancelled job NEVER reaches its relaunch —
 * so after [cycle] returns, the job re-asserts its own liveness
 * (`ensureActive`) before scheduling. Without that, any swallowed
 * cancellation inside the cycle (a broad catch around a suspend) would fork
 * a second, untracked chain next to the canceller's replacement.
 *
 * Main-thread confined, like the mode state it choreographs. Owns no
 * scope: jobs launch on the owner's [scope], so a mode's `stop()`
 * (scope.cancel) kills the chain exactly as before.
 *
 * @param source the capture source the cycles AND the gate use. Both must
 *   resolve identically — parking on one source's delivery signal while
 *   capturing from another would desync the served-frame cursor the gate
 *   compares against.
 * @param parkDeadlineMs uptime deadline past which a park must end in a
 *   forced cycle, re-read each wait; null = park indefinitely.
 * @param firstCycleGate suspends before cycle 1's park and reports whether
 *   the session is PROVEN clear to capture (engine warm-up joined, startup
 *   chip off screen and provably out of the frames this mode will read).
 *   False = not proven (a whole-display mirror hasn't delivered a post-chip
 *   frame yet): the engine reschedules the gate at the capture interval
 *   WITHOUT running a cycle — a frame that may show our own chip must never
 *   be OCR'd, and on a dead pipeline idling here matches what the delivery
 *   park would do anyway. Latched as done only on true; a CANCELLED gate
 *   (stop, input kick) re-runs on the relaunch; any other failure marks it
 *   done so a broken gate cannot re-park the loop forever.
 * @param cycle one full capture→detect→apply pass; returns the delay (ms)
 *   before the next cycle.
 */
internal class LiveCycleEngine(
    private val scope: CoroutineScope,
    private val service: CaptureService,
    private val displayId: Int,
    private val tag: String,
    private val source: () -> LiveCaptureSource?,
    private val parkDeadlineMs: () -> Long? = { null },
    private val firstCycleGate: suspend () -> Boolean = { true },
    private val cycle: suspend () -> Long,
) {

    private var currentJob: Job? = null

    /** Run the next cycle regardless of delivery silence. Starts true (the
     *  bootstrap look); set by resets, failures, and every path that must
     *  not wait for the screen to change. Consumed by the owner right
     *  before its capture attempt ([consumeForce]). */
    private var forceNextCycle = true

    /** Timestamp until which cycles pace at the backend floor because game
     *  input predicted a change (audit A4). */
    private var inputBurstUntilMs = 0L
    private var lastInputKickMs = 0L

    /** The one-time [firstCycleGate] reported clear. Cancellation must not
     *  set it: [onGameInput] cancels the current job and relaunches, and a
     *  gate killed mid-park (warm-up unjoined, startup chip still up) must
     *  run again on the relaunch. A non-cancellation failure DOES set it —
     *  a broken gate must not re-park the loop forever (the gate itself is
     *  idempotent, so re-running after cancellation is safe). */
    private var firstCycleGateRan = false

    /** Launch the next link of the chain: pace → park → cycle → relaunch. */
    fun scheduleNext(delayMs: Long = 0) {
        currentJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                if (!firstCycleGateRan) {
                    val clear = try {
                        firstCycleGate()
                    } catch (e: Exception) {
                        if (e !is CancellationException) firstCycleGateRan = true
                        throw e
                    }
                    if (!clear) {
                        // Not proven clear — retry the gate next round; no
                        // capture may happen off an unproven frame.
                        ensureActive()
                        scheduleNext(Prefs(service).captureIntervalMs)
                        return@launch
                    }
                    firstCycleGateRan = true
                }
                awaitCycleReason()
                val nextDelay = cycle()
                // A cancelled job must never launch its successor — see the
                // class doc's loop-integrity contract.
                ensureActive()
                scheduleNext(nextDelay)
            } catch (e: CancellationException) {
                // Normal cancellation (stop/refresh/dismiss) — propagate.
                throw e
            } catch (e: Exception) {
                // Unexpected throw (display went away, WindowManager token
                // invalidated, bitmap op on detached view, etc.). Log and
                // reschedule so the cycle self-heals instead of silently
                // going dormant. Force the retry — self-healing must not
                // depend on the screen changing.
                Log.e(tag, "runCycle failed, rescheduling", e)
                ensureActive()
                forceNextCycle = true
                scheduleNext(Prefs(service).captureIntervalMs)
            }
        }
    }

    /** Cancel the in-flight/parked job without scheduling a successor. */
    fun cancelCurrent() {
        currentJob?.cancel()
    }

    /** Arm the force flag: the next park is a pass-through and the owner's
     *  next [consumeForce] reads true. */
    fun forceNext() {
        forceNextCycle = true
    }

    /** Read-and-clear the force flag. The owner calls this at its real
     *  capture attempt — after its early-return guards — so those returns
     *  can't eat a force set by dismiss/refresh during a hold. */
    fun consumeForce(): Boolean {
        val forced = forceNextCycle
        forceNextCycle = false
        return forced
    }

    /** True while cycles should pace at the backend floor (audit A4). */
    fun inInputBurst(): Boolean = SystemClock.uptimeMillis() < inputBurstUntilMs

    /** Disarm the input burst (mode state reset). */
    fun resetInputBurst() {
        inputBurstUntilMs = 0L
    }

    /**
     * Game input as a scoped change hint (audit A4), replacing the previous
     * dismiss-everything semantics: hide nothing, reset nothing. The burst
     * window makes the next ~2.5s of cycles pace at the backend floor, the
     * force opens the delivery gate, and the rate-limited kick wakes a
     * parked loop immediately — so an input whose response is subtle (or
     * still rendering) gets looked at right away instead of a full interval
     * later. Detection then lifts only the regions that actually changed:
     * static HUD boxes survive a tap, and tapping through dialogue no
     * longer blanks every overlay on screen. Whether touches reach this at
     * all is still gated by the touches-refresh pref at the sentinel layer,
     * exactly as before; gamepad keys (accessibility backend) always do.
     */
    fun onGameInput() {
        val now = SystemClock.uptimeMillis()
        inputBurstUntilMs = now + PinholeCalibration.INPUT_BURST_MS
        forceNextCycle = true
        val floor = source()?.minCaptureIntervalMs ?: 500L
        if (now - lastInputKickMs >= floor) {
            lastInputKickMs = now
            // Same cancel-and-relaunch the old dismiss path used; the new
            // job's gate sees the force and runs at once. Cancelling a
            // mid-flight cycle (even mid-translation) matches the previous
            // input behavior.
            currentJob?.cancel()
            scheduleNext()
        }
    }

    private suspend fun awaitCycleReason() {
        val signal = source()?.deliverySignal ?: return
        val debug = Prefs(service).debugLiveMode
        var parkedAtMs = 0L
        while (!forceNextCycle && !service.holdActive &&
            signal.seqNow() <= signal.lastServedSeq
        ) {
            val deadline = parkDeadlineMs()
            val remainingMs = if (deadline == null) null
            else deadline - SystemClock.uptimeMillis()
            if (remainingMs != null && remainingMs <= 0) {
                forceNextCycle = true
                break
            }
            if (parkedAtMs == 0L) {
                parkedAtMs = SystemClock.uptimeMillis()
                if (debug) DetectionLog.log("D$displayId gate: parked at seq=${signal.seqNow()}")
            }
            if (remainingMs != null) {
                withTimeoutOrNull(remainingMs) { signal.awaitSeqAfter(signal.lastServedSeq) }
            } else {
                signal.awaitSeqAfter(signal.lastServedSeq)
            }
        }
        if (parkedAtMs != 0L && debug) {
            val why = when {
                forceNextCycle -> "forced"
                service.holdActive -> "hold"
                else -> "delivery seq=${signal.seqNow()}"
            }
            val ms = SystemClock.uptimeMillis() - parkedAtMs
            DetectionLog.log("D$displayId gate: wake after ${ms}ms ($why)")
        }
    }
}
