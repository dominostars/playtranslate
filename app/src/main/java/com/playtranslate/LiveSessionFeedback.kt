package com.playtranslate

import android.view.Display
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.capture.MediaProjectionController
import com.playtranslate.capture.StartupChip
import com.playtranslate.capture.StreamKind
import com.playtranslate.capture.StreamKindProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One live session's feedback machinery: the eager OCR engine warm-up, the
 * startup chip (probe checker → warm-up label) with its grace and leak-guard
 * timers, the pre-first-cycle gate, and the slow-pass busy tracking that
 * drives the floating icons' breathe.
 *
 * Created by [CaptureService.startLive], disposed by stopLive and by the
 * next start — the Region-session idiom: the old session is cancelled and
 * replaced atomically, no field-by-field reset checklist on the
 * process-lived service. Every timer runs in [scope], a child of the
 * service scope, so [dispose] kills all of them at once; and an OCR pass
 * finalizer holds a reference to ITS session object plus a membership
 * token, so a pass that outlives its session (native inference cancels
 * lazily) finalizes into a disposed, inert object — structurally unable to
 * touch the next session's state. (The 2026-07-15 adversarial reviews found
 * three interference bugs of exactly that shape in the loose-field version.)
 *
 * Main-confined, like the live-mode mutators that drive it.
 */
internal class LiveSessionFeedback(
    parentScope: CoroutineScope,
    private val controller: MediaProjectionController,
    sourceLang: String,
) {

    private val scope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Main
    )

    /** Eager engine warm-up, started at construction so the Meiki/Paddle
     *  native session load overlaps consent/probe/MP setup instead of
     *  landing inside cycle 1's runOcr. Joined by [awaitFirstCycleClear]. */
    private val warmUpJob: Job = scope.launch {
        OcrManager.instance.warmUpEngine(sourceLang)
    }

    // ── Startup chip ─────────────────────────────────────────────────────

    private var chip: StartupChip? = null
    private var chipHardCap: Job? = null
    private var chipGrace: Job? = null

    /** Delivery seq stamped when the chip left the screen (from the MP
     *  controller — the chip only ever lives on the projected display, and
     *  every MP-backed source's DeliverySignal shares that counter).
     *  -1 = no chip shown this session. */
    private var chipRemovalSeq = -1L

    /** A gate's post-removal fresh-frame wait completed — later gate entries
     *  (a mode's loop restarting) skip straight through. Never set by a
     *  cancelled wait, which re-runs. */
    private var chipFreshWaitDone = false

    /** Show the checker chip ahead of a stream-kind probe. The probe draws
     *  its pattern into it via [probeSurface]. */
    fun showProbeChip() {
        showChip(withPattern = true)
    }

    /** The chip as the probe's pattern host; null (→ the probe's own
     *  ephemeral window) when no chip is on screen. */
    val probeSurface: StreamKindProbe.ProbeSurface?
        get() = chip?.takeIf { !it.isRemoved }

    /** The stream-kind verdict settled (whatever it settled to): the card's
     *  grid slot swaps to the loading spinner, and the window stops
     *  consuming center-screen taps. */
    fun onVerdictSettled() {
        chip?.onVerdictSettled()
    }

    /** Hide/re-show around the UNKNOWN stream-kind prompt — an
     *  "Initializing…" chip over a question would wrongly say no action is
     *  needed. */
    fun setChipVisible(visible: Boolean) {
        chip?.setVisible(visible)
    }

    /** Spinner-variant grace chip for starts where no probe runs
     *  (accessibility tier, cached stream kind): engine warm-up is then the
     *  only wait, and chrome should exist only when the wait is real. Armed
     *  once per start, after the probe question is settled — a spinner card
     *  must never collide with a pattern the probe is about to measure. A
     *  warm engine never flashes chrome. */
    fun armChipGrace() {
        if (chip?.isRemoved == false) return // probe chip already up
        chipGrace?.cancel()
        chipGrace = scope.launch {
            delay(CHIP_GRACE_MS)
            if (warmUpJob.isActive) showChip(withPattern = false)
        }
    }

    private fun showChip(withPattern: Boolean) {
        if (chip?.isRemoved == false) return
        chip = StartupChip.show(controller, withPattern)?.also {
            // Leak guard, not a normal-path truncator: generously above the
            // worst-case probe (~3.4s) plus a slow warm-up, for the paths
            // where no first cycle ever runs to remove the chip (an aborted
            // setLiveDisplays, a mode that never starts).
            chipHardCap?.cancel()
            chipHardCap = scope.launch {
                delay(CHIP_HARD_CAP_MS)
                removeChip()
            }
        }
    }

    /** Remove the chip window. Idempotent — called from the first-cycle
     *  gate, the hard cap, explicit start-aborts, and [dispose]. */
    fun removeChip() {
        chipHardCap?.cancel()
        chipHardCap = null
        chip?.let {
            if (!it.isRemoved) {
                it.remove()
                chipRemovalSeq = controller.deliverySeqNow
            }
        }
        chip = null
    }

    // ── Pre-first-cycle gate ─────────────────────────────────────────────

    /**
     * A live cycle may not capture until
     *  1. the engine warm-up SETTLED — otherwise the lazy session load lands
     *     mid-cycle and then OCRs a frame that went stale while the model
     *     loaded; and
     *  2. the chip is off screen and provably outside the frame the cycle
     *     will read — its label must never be OCR'd and boxed as game text.
     *     The chip only exists on the projected (default) display, so only
     *     that display's mode pays the wait.
     * Returns true when the cycle is PROVEN clear to capture; false when a
     * whole-display mirror has not yet delivered a post-chip frame — the
     * caller must retry the gate instead of capturing, because the latched
     * frame likely still shows the chip. Idempotent and safe to re-enter
     * (loop restarts, input kicks, retries). Runs in the CALLER's scope, so
     * a mode stop cancels it like any parked cycle. Every wait is capped:
     * feedback must never wedge the feature.
     */
    suspend fun awaitFirstCycleClear(displayId: Int, source: LiveCaptureSource?): Boolean {
        // warmUpEngine always returns (failure settles to the floor/empty
        // engine); the cap is a hang guard, not policy.
        withTimeoutOrNull(WARMUP_JOIN_CAP_MS) { warmUpJob.join() }
        removeChip()
        if (displayId != Display.DEFAULT_DISPLAY) return true
        if (chipRemovalSeq < 0 || chipFreshWaitDone) return true
        // A CLEAN grant is a task mirror: our windows are structurally
        // absent from its stream (the probe's own detection premise), so
        // the chip was never in these frames and there is nothing to wait
        // for. Waiting would also never RESOLVE on a static game — the
        // removal composites on the display, not into the task mirror —
        // and would burn the whole cap on every quiet single-app start.
        if (controller.streamKind == StreamKind.CLEAN) {
            chipFreshWaitDone = true
            return true
        }
        val signal = source?.deliverySignal
        if (signal != null) {
            // Whole-display mirror: the chip WAS in the stream, and its
            // removal forces a composition into it, so on a healthy
            // pipeline this resolves within a frame or two. A timeout means
            // the pipeline is wedged or very late and the latched frame
            // likely still shows the chip — report not-clear so the caller
            // retries the gate rather than OCR'ing our own label.
            val fresh = withTimeoutOrNull(FRESH_FRAME_TIMEOUT_MS) {
                signal.awaitSeqAfter(chipRemovalSeq)
            } != null
            if (!fresh) {
                DetectionLog.log(
                    "startup chip fresh-frame wait timed out; holding first cycle"
                )
                return false
            }
        } else {
            // Accessibility capture has no delivery signal (and takes its
            // screenshots at call time, so there is no latched frame to go
            // stale); a short settle lets the removal reach the screen.
            delay(A11Y_CHIP_SETTLE_MS)
        }
        chipFreshWaitDone = true
        return true
    }

    // ── Slow-pass busy tracking ──────────────────────────────────────────
    // On devices where an OCR pass runs multi-second, live mode shows
    // nothing while it works and users read the silence as "broken". After
    // any pass has been in flight past the grace period, every floating
    // icon breathes until none is. Membership tokens, not a counter: passes
    // overlap (per-display cycles, a hold fan-out over a still-unwinding
    // live pass), and a token identifies its pass — a stale finalizer
    // removes only its own token from its own session, so the books cannot
    // drift and no generation tagging is needed.

    private val busyPasses = mutableSetOf<Any>()
    private var busyArmJob: Job? = null

    /** Register an in-flight live OCR pass; hand the token back to
     *  [endOcrPass] from the pass's finally. */
    fun beginOcrPass(): Any {
        val token = Any()
        busyPasses.add(token)
        if (busyPasses.size == 1) {
            busyArmJob?.cancel()
            busyArmJob = scope.launch {
                delay(OCR_BUSY_GRACE_MS)
                if (busyPasses.isNotEmpty()) setIconsBusy(true)
            }
        }
        return token
    }

    fun endOcrPass(token: Any) {
        // A token absent from the set is a pass that outlived its session
        // ([dispose] cleared it) — inert by construction.
        if (!busyPasses.remove(token)) return
        if (busyPasses.isEmpty()) {
            busyArmJob?.cancel()
            busyArmJob = null
            setIconsBusy(false)
        }
    }

    private fun setIconsBusy(busy: Boolean) {
        CaptureBackendResolver.activeOverlayUi?.setIconsBusy(busy)
    }

    // ── Teardown ─────────────────────────────────────────────────────────

    /** Tear the session down atomically: every timer dies with [scope], the
     *  chip leaves the screen, the icons stop breathing, and outstanding
     *  pass tokens become inert. Idempotent. */
    fun dispose() {
        scope.cancel()
        removeChip()
        busyPasses.clear()
        setIconsBusy(false)
    }

    companion object {
        /** How long a live OCR pass may run before the icons start
         *  breathing. Above typical fast-device cycles (quick passes never
         *  flicker the icon), well under the multi-second passes slow
         *  devices experience — the population the signal exists for. */
        const val OCR_BUSY_GRACE_MS = 700L

        /** No-probe starts show the label-only chip only when warm-up is
         *  still running after this grace. */
        const val CHIP_GRACE_MS = 300L

        /** Chip leak guard — fires only when no first cycle ever ran to
         *  remove the chip. */
        const val CHIP_HARD_CAP_MS = 12_000L

        // Gate caps — hang guards, not policy; each covers a wait that
        // resolves far sooner on every healthy path.
        const val WARMUP_JOIN_CAP_MS = 8_000L

        /** Post-removal fresh-delivery cap (whole-display mirrors only —
         *  CLEAN streams skip the wait). The removal composites into the
         *  mirror, so this resolves in a frame or two normally; a timeout
         *  reports not-clear and the caller retries the gate. */
        const val FRESH_FRAME_TIMEOUT_MS = 2_000L
        const val A11Y_CHIP_SETTLE_MS = 200L
    }
}
