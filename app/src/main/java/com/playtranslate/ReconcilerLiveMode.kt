package com.playtranslate

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.capture.StreamKind
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The unified reconciler live loop — ONE sensing pipeline for every live
 * flavor, parameterized by a [LivePresenter] (translation boxes, furigana
 * annotations, panel-only). Grown from the clean-stream TRANSLATION mode and
 * carrying all of its review hardening; see
 * docs/single-app-capture-audit-2026-07-10.md §7 for the design record.
 *
 * Overlay-painting presenters ([LivePresenter.rendersOverlays]) run only
 * on a **clean** MediaProjection stream — the API 34+ "a single app" grant,
 * where the mirror contains only the captured task's surface subtree
 * ([StreamKind.CLEAN], measured by
 * [com.playtranslate.capture.StreamKindProbe]). Our overlay windows, system
 * UI, and every other app are structurally absent from the frames, which
 * dissolves the occlusion problem the pinhole tier exists to fight. A
 * non-painting presenter (panel-only) runs on ANY stream kind and backend —
 * it contaminates nothing, so there is nothing to model.
 *
 * On clean streams specifically:
 *
 *  - Every frame is clean by construction — no cleanRef, no blend model, no
 *    `fillOverlayRegions`, no offscreen overlay renders, no layout-settle
 *    polling, no pixel change gates.
 *  - The game text under a displayed box stays directly visible to OCR, so
 *    "did it change?" is answered in TEXT space by [ScanlineReconciler]:
 *    read the screen, translate the screen, update the screen (Level 0),
 *    plus one narrowly-scoped typewriter hold ([StabilityHold]).
 *  - Our own repaints never composite into a task mirror, so there is no
 *    self-echo class: no forced follow-up looks, no gate exclusions, no
 *    self-paint epochs.
 *  - Boxes render solid ([CaptureService.showLiveOverlay] `pinholeMode =
 *    false`) — no hole veil; the window alpha cap is a touch-security matter
 *    and unchanged.
 *
 * ## The loop
 * pace → park on the delivery gate (no frame delivered since the last one we
 * consumed ⇒ the screen is unchanged ⇒ free skip) → visibility guard →
 * capture the latched frame → secure-black check → identity guard → full-crop
 * OCR → reconcile → stability hold → apply (kept verbatim; removals now;
 * placeholders for the rest, cached translations instantly, the remainder
 * translated in-cycle). The displayed box list is the only cross-cycle
 * overlay state; the hold map is the only other state of any kind.
 *
 * ## Guards, and why each one is load-bearing
 *  - **Visibility**: when the captured task leaves the foreground the
 *    platform hides the mirrored surface — the stream turns BLACK, not
 *    frozen. A black frame reaching the reconciler would REMOVE every box.
 *    So: hide the overlay window immediately (boxes floating over the
 *    launcher are stale + leak content), keep the box list, park until the
 *    task returns, then force a look — unchanged text re-shows instantly via
 *    KEEP.
 *  - **Identity**: a non-fullscreen task is letterboxed into the frame and
 *    its on-screen offset has NO public API — box placement is impossible,
 *    not just unimplemented. `contentSize != frame size` ⇒ pause with a
 *    message rather than misplace.
 *  - **Secure content**: FLAG_SECURE renders black with no callback; N
 *    consecutive black frames ⇒ tell the user once, keep polling.
 *  - **Verdict lifetime**: the CLEAN verdict is trusted for the whole
 *    session, with no runtime tripwires — a projection token's scope is
 *    immutable, so a correct verdict cannot become wrong later, and the
 *    probe ([com.playtranslate.capture.StreamKindProbe]) carries the whole
 *    burden of being right. A wrong CLEAN manifests as visible overlay
 *    churn; restarting live mode re-probes. The one mid-session transition
 *    that IS handled: consent teardown RESETS the verdict under a running
 *    mode — the cycle-start guard rebuilds via
 *    [CaptureService.onCleanVerdictLost].
 */
class ReconcilerLiveMode(
    private val service: CaptureService,
    private val displayId: Int,
    private val presenter: LivePresenter,
) : LiveMode {

    override val flavor: OverlayFlavor get() = presenter.flavor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    private var visibilityJob: Job? = null

    /** Displayed boxes, bounds in OCR-crop space — the mode's only
     *  cross-cycle overlay state (Level 0). */
    private var cachedBoxes: List<TextBox>? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var cycleNum = 0

    private val stabilityHold = StabilityHold()
    /** Earliest open hold's cap deadline (uptime ms) — the delivery gate
     *  parks only until this, so a typewriter that finishes into a static
     *  screen still gets its releasing read. */
    private var holdDeadlineMs: Long? = null

    /** Run the next cycle regardless of delivery silence. Starts true (the
     *  bootstrap look); set by resets, failures, visibility resumes, and
     *  expired hold deadlines. Cleared right before each capture attempt. */
    private var forceNextCycle = true

    private var inputBurstUntilMs = 0L
    private var lastInputKickMs = 0L

    private var blackStreak = 0
    private var secureNotified = false

    override fun start() {
        currentJob?.cancel()
        CaptureBackendResolver.active().startInputMonitoring(displayId) { onGameInput() }
        // Hide the overlay the moment the captured task leaves the foreground
        // — and force a look (with a wake) the moment it returns. drop(1)
        // skips the StateFlow's replay of the current value; runCycle's
        // visibility guard covers the started-while-hidden case.
        visibilityJob?.cancel()
        visibilityJob = scope.launch {
            service.mediaProjectionController.contentVisible.drop(1).collect { visible ->
                // The flow describes the MP stream; act only if that stream
                // is what feeds THIS display (re-checked per emission).
                if (liveSource()?.contentVisible == null) return@collect
                if (!visible) {
                    CaptureBackendResolver.activeOverlayUi
                        ?.hideTranslationOverlayForDisplay(displayId)
                } else {
                    forceNextCycle = true
                    currentJob?.cancel()
                    scheduleNextCycle()
                }
            }
        }
        scheduleNextCycle()
    }

    override fun stop() {
        scope.cancel()
        resetState()
        CaptureBackendResolver.active().stopInputMonitoring(displayId)
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
    }

    override fun refresh() {
        resetState()
        scheduleNextCycle()
    }

    override fun dismiss() {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        resetState()
        scheduleNextCycle(Prefs(service).captureIntervalMs)
    }

    override fun getCachedState(): CachedOverlayState? {
        val anchors = cachedBoxes ?: return null
        val display = anchors.flatMap { presenter.displayBoxesFor(it) }
        return CachedOverlayState(display, cropLeft, cropTop, screenshotW, screenshotH)
    }

    /**
     * The single owner of every piece of evidence + geometry state a cycle's
     * guards key on or accumulate. Every reset path routes through here so a
     * guard is always disarmed by the reset it triggers — the dims guard once
     * kept its own key (screenshotW/H) across its clear, which turned every
     * rotation into an endless clear/retry loop with translation silently
     * dead (adversarial-review finding). Geometry reseeds from the next OCR
     * pass.
     *
     * The user-facing notification latch ([secureNotified]) deliberately live OUTSIDE this clear: their guards
     * invoke it on every trip, and clearing the latch with it would re-emit
     * the same message every cycle. They reset in [resetState] (mode
     * restart) and where their condition clears.
     */
    private fun clearEvidenceState() {
        cachedBoxes?.let { presenter.onAnchorsDropped(it) }
        cachedBoxes = null
        stabilityHold.clear()
        holdDeadlineMs = null
        cropLeft = 0
        cropTop = 0
        screenshotW = 0
        screenshotH = 0
        blackStreak = 0
        forceNextCycle = true
    }

    private fun resetState() {
        currentJob?.cancel()
        clearEvidenceState()
        inputBurstUntilMs = 0L
        secureNotified = false
    }

    /** Game input as a scoped change hint — same A4 semantics as the pinhole
     *  tier: the burst window paces the next ~2.5s of cycles at the floor,
     *  and a rate-limited kick wakes a parked loop immediately. Nothing is
     *  hidden or reset — the reconciler decides what actually changed. */
    private fun onGameInput() {
        val now = SystemClock.uptimeMillis()
        inputBurstUntilMs = now + PinholeCalibration.INPUT_BURST_MS
        forceNextCycle = true
        val floor = liveSource()?.minCaptureIntervalMs ?: 500L
        if (now - lastInputKickMs >= floor) {
            lastInputKickMs = now
            currentJob?.cancel()
            scheduleNextCycle()
        }
    }

    /** This mode exists only on the MediaProjection stream; the resolver
     *  returns it whenever consent is held. Consent loss is caught by the
     *  stream-kind check at the top of [runCycle]. */
    private fun liveSource(): LiveCaptureSource? =
        CaptureBackendResolver.liveCaptureSourceFor(displayId)

    private fun scheduleNextCycle(delayMs: Long = 0) {
        currentJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                awaitCycleReason()
                val nextDelay = runCycle()
                scheduleNextCycle(nextDelay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "runCycle failed, rescheduling", e)
                forceNextCycle = true
                scheduleNextCycle(Prefs(service).captureIntervalMs)
            }
        }
    }

    /**
     * The delivery gate: park until there is a reason to run a cycle — a
     * forced look, a hold (peek) in progress, a fresh delivery, or an open
     * [StabilityHold] reaching its cap (the park is bounded by the earliest
     * hold deadline; a static screen after a typewriter must still deliver
     * the releasing read). Our own repaints never wake this gate — a task
     * mirror doesn't composite them — which is exactly right: nothing we
     * draw can change what needs reading.
     */
    private suspend fun awaitCycleReason() {
        val signal = liveSource()?.deliverySignal ?: return
        val debug = Prefs(service).debugLiveMode
        var parkedAtMs = 0L
        while (!forceNextCycle && !service.holdActive &&
            signal.seqNow() <= signal.lastServedSeq
        ) {
            val deadline = holdDeadlineMs
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

    /** Run one read→reconcile→update cycle. Returns the delay (ms) before the
     *  next cycle. */
    private suspend fun runCycle(): Long {
        val prefs = Prefs(service)
        if (service.holdActive) return 100L
        val controller = service.mediaProjectionController
        if (presenter.rendersOverlays && controller.streamKind != StreamKind.CLEAN) {
            // Consent teardown reset the verdict (a backend switch can follow
            // it) — an overlay-painting mode must not keep running on a
            // stream that is no longer provably clean. Rebuild through the
            // mutator; our scope is cancelled inside this call.
            service.onCleanVerdictLost()
            return prefs.captureIntervalMs
        }
        val mgr = liveSource()
        if (mgr == null) {
            forceNextCycle = true
            return prefs.captureIntervalMs
        }
        if (CaptureBackendResolver.activeOverlayUi == null) {
            forceNextCycle = true
            return prefs.captureIntervalMs
        }

        // Visibility guard — see the class doc. Keyed to THIS instance's
        // capture source: only the MP stream has visibility semantics (its
        // mirror goes black when the captured task backgrounds); an
        // accessibility-fed display must not park on some other stream's
        // state. The collector in [start] already hid the overlay window;
        // keep the box list and suspend until the task returns (cancellable
        // park; no polling).
        val visibility = mgr.contentVisible
        if (visibility != null && !visibility.value) {
            visibility.first { it }
            forceNextCycle = true
            return 0L
        }


        cycleNum++
        val debug = prefs.debugLiveMode
        forceNextCycle = false

        val frame = mgr.requestRaw(displayId)
        if (frame == null) {
            // Generators of a null capture, enumerated: transient failure
            // (retry), consent loss (requestRaw's checkConsentLost stops live
            // mode itself), or the SOURCE's geometry refusal — CLEAN stream
            // whose task stopped filling the frame (split screen / freeform;
            // the source owns the one-time user message). For the refusal,
            // stale boxes must not float over wrong geometry: drop them and
            // poll at interval until the task is fullscreen again.
            if (!controller.frameGeometryProven()) {
                clearDisplayed()
            }
            forceNextCycle = true
            return prefs.captureIntervalMs
        }
        val raw = frame.bitmap
        // The hold's cap anchor: stamped by the source at serve time
        // (CapturedFrame) — not a caller-side clock read.
        val captureAtMs = frame.capturedAtMs

        try {
            // Rotation / display reconfig: crop-space bounds are void.
            if (screenshotW != 0 && (raw.width != screenshotW || raw.height != screenshotH)) {
                Log.w(TAG, "Capture dims changed (${screenshotW}x$screenshotH → ${raw.width}x${raw.height}), clearing state")
                clearDisplayed()
                return prefs.captureIntervalMs
            }

            // (Identity itself is enforced upstream: the capture source
            // refuses to serve CLEAN frames while geometry is unproven or
            // non-fullscreen, so a frame reaching here is coordinate-safe.)

            // Secure-content guard: FLAG_SECURE renders black, silently. The
            // notified latch (not the streak) gates the message — the clear
            // below zeroes the streak with the rest of the evidence state,
            // and re-counting must not re-emit mid-blackout.
            if (isAllBlack(raw)) {
                blackStreak++
                if (blackStreak >= SECURE_BLACK_STREAK && !secureNotified) {
                    secureNotified = true
                    service.emitError(service.getString(R.string.error_capture_blocked_secure))
                    clearDisplayed()
                }
                return prefs.captureIntervalMs
            }
            blackStreak = 0
            secureNotified = false

            // OCR the full crop. No status-bar exclusion: a task stream
            // contains no system UI — those top rows are game content.
            val ocrStartMs = SystemClock.uptimeMillis()
            val pipeline = service.runOcr(raw, displayId, frame.includesSystemUi)
            val ocrMs = SystemClock.uptimeMillis() - ocrStartMs

            // A hold gesture may have started during the OCR suspension.
            if (service.holdActive) return 100L

            val boxes = cachedBoxes ?: emptyList()
            if (pipeline == null && boxes.isEmpty()) {
                presenter.emitNoText()
                return pacing(prefs)
            }

            // Crop bookkeeping: seed on the first placement-capable cycle,
            // reset on drift (statusBar/region changes mid-session).
            if (pipeline != null) {
                val (_, _, pipeLeft, pipeTop, sw, sh) = pipeline
                if (boxes.isEmpty()) {
                    cropLeft = pipeLeft; cropTop = pipeTop
                    screenshotW = sw; screenshotH = sh
                } else if (pipeLeft != cropLeft || pipeTop != cropTop) {
                    Log.w(TAG, "Crop offsets changed ($cropLeft,$cropTop → $pipeLeft,$pipeTop), clearing state")
                    clearDisplayed()
                    return prefs.captureIntervalMs
                }
            }

            val groups = pipeline?.ocrResult?.groups ?: emptyList()

            // Reconcile in text space; then the typewriter hold.
            val verdicts = ScanlineReconciler.reconcile(groups, boxes)
            val holdOut = stabilityHold.filter(
                verdicts, captureAtMs, SystemClock.uptimeMillis(),
            )
            holdDeadlineMs = holdOut.nextDeadlineMs

            val kept = verdicts.keptBoxes + holdOut.heldBoxes
            val toTranslate = holdOut.toTranslate
            // Every anchor leaving the display is announced: REMOVEs, and the
            // boxes RETRANSLATE entries replace (post-hold — a held region's
            // box stays displayed). Presenters with per-anchor state depend
            // on seeing every exit.
            val dropped = verdicts.removals + toTranslate.mapNotNull { it.replacesBox }
            if (dropped.isNotEmpty()) presenter.onAnchorsDropped(dropped)

            if (debug) {
                DetectionLog.log(
                    "D$displayId c$cycleNum clean: ocr=${ocrMs}ms " +
                        "u=${verdicts.unchanged} c=${verdicts.changed} " +
                        "m=${verdicts.missing} n=${verdicts.added} " +
                        "held=${holdOut.heldBoxes.size} repos=${verdicts.repositioned}"
                )
            }

            val mutated = verdicts.removals.isNotEmpty() || toTranslate.isNotEmpty() ||
                verdicts.repositioned > 0
            if (!mutated) {
                // Steady state: what's displayed is already exactly right
                // (kept verbatim + held verbatim). No rendering work.
                return pacing(prefs)
            }

            if (toTranslate.isEmpty()) {
                cachedBoxes = kept.ifEmpty { null }
                if (kept.isEmpty()) {
                    presenter.emitNoText()
                } else {
                    showBoxes(kept)
                    presenter.emitApplied(
                        kept, pipeline?.ocrResult, frame.includesSystemUi,
                        mgr.saveToCache(raw, displayId),
                    )
                }
                return pacing(prefs)
            }

            // The presenter turns regions into anchors — serially, in-cycle,
            // so at most one OCR and one present pass (MT / tokenizer) are
            // ever in flight (the loop's backpressure). A provisional render
            // (skeletons, cache fills) may land first via onPartial.
            val finalAnchors = presenter.present(
                toTranslate, frame, cropLeft, cropTop,
                onPartial = { partial ->
                    cachedBoxes = kept + partial
                    showBoxes(kept + partial)
                },
            )
            cachedBoxes = kept + finalAnchors
            showBoxes(kept + finalAnchors)
            presenter.emitApplied(
                kept + finalAnchors, pipeline?.ocrResult, frame.includesSystemUi,
                mgr.saveToCache(raw, displayId),
            )
            return pacing(prefs)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /** Next-cycle delay: the interval (floor-paced during an input burst),
     *  never past an open hold's cap deadline, never below the source floor. */
    private fun pacing(prefs: Prefs): Long {
        val floor = liveSource()?.minCaptureIntervalMs ?: 500L
        val base = if (SystemClock.uptimeMillis() < inputBurstUntilMs) floor
        else prefs.captureIntervalMs
        val deadline = holdDeadlineMs ?: return base
        val untilCap = deadline - SystemClock.uptimeMillis()
        return maxOf(floor, minOf(base, untilCap))
    }

    /** Render the anchors' DISPLAY boxes (identity for most presenters;
     *  furigana maps anchors to annotation boxes). Panel-only presenters
     *  paint nothing — anchors still back hold-to-preview. */
    private fun showBoxes(anchors: List<TextBox>) {
        if (!presenter.rendersOverlays) return
        service.showLiveOverlay(
            anchors.flatMap { presenter.displayBoxesFor(it) },
            cropLeft, cropTop, screenshotW, screenshotH,
            pinholeMode = false, displayId = displayId,
            // Reconciler-tier bounds are hysteresis-filtered upstream — the
            // view must not fuzzy-hold children in place (drift lag bug).
            authoritativeBounds = true,
        )
    }

    /** Drop everything displayed + all evidence/geometry state and hide the
     *  overlay window; the next cycle rebuilds from a fresh OCR pass. */
    private fun clearDisplayed() {
        clearEvidenceState()
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
    }

    /** Strided luma scan — true when every sample is near-black (the
     *  FLAG_SECURE / hidden-surface signature). ~300 samples at 1080p. */
    private fun isAllBlack(bmp: Bitmap): Boolean {
        val step = 64
        var x = step / 2
        while (x < bmp.width) {
            var y = step / 2
            while (y < bmp.height) {
                val p = bmp.getPixel(x, y)
                if ((p shr 16 and 0xFF) > BLACK_LEVEL_MAX ||
                    (p shr 8 and 0xFF) > BLACK_LEVEL_MAX ||
                    (p and 0xFF) > BLACK_LEVEL_MAX
                ) return false
                y += step
            }
            x += step
        }
        return true
    }


    private companion object {
        const val TAG = "ReconcilerLive"

        /** Consecutive all-black frames before the secure-content message. */
        const val SECURE_BLACK_STREAK = 3

        /** Per-channel level at or below which a sample counts as black. */
        const val BLACK_LEVEL_MAX = 12
    }
}
