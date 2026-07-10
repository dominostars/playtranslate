package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.capture.StreamKind
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
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
 * Live TRANSLATION mode for a **clean** MediaProjection stream — the API 34+
 * "a single app" grant, where the mirror contains only the captured task's
 * surface subtree ([StreamKind.CLEAN], measured by
 * [com.playtranslate.capture.StreamKindProbe]). Our overlay windows, system
 * UI, and every other app are structurally absent from the frames, which
 * dissolves the occlusion problem the pinhole tier exists to fight:
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
 *    self-paint epochs. (The one echo we DO watch for is the tripwire below —
 *    evidence the CLEAN verdict itself was wrong.)
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
 *  - **Contamination tripwire**: if OCR ever reads back a displayed box's own
 *    TRANSLATION at that box's rect (while differing from its source text),
 *    the stream contains our rendering — the probe's CLEAN verdict was wrong.
 *    Two consecutive sightings demote the session
 *    ([com.playtranslate.capture.MediaProjectionController
 *    .demoteStreamKindToContaminated]) and rebuild into the pinhole tier via
 *    [CaptureService.onStreamKindDemoted].
 */
class CleanStreamOverlayMode(
    private val service: CaptureService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.TRANSLATION

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
    private var echoStreak = 0
    private var identityNotified = false

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
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private fun resetState() {
        currentJob?.cancel()
        cachedBoxes = null
        stabilityHold.clear()
        holdDeadlineMs = null
        inputBurstUntilMs = 0L
        blackStreak = 0
        echoStreak = 0
        identityNotified = false
        forceNextCycle = true
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
        if (controller.streamKind != StreamKind.CLEAN) {
            // Consent torn down (kind reset to UNKNOWN) or the tripwire
            // demoted the session — either way this mode must not run on a
            // stream that isn't provably clean. Rebuild through the mutator;
            // our scope is cancelled inside this call.
            service.onStreamKindDemoted()
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

        // Visibility guard — see the class doc. The collector in [start]
        // already hid the overlay window; keep the box list and suspend until
        // the task returns (cancellable park; no polling).
        if (!controller.contentVisible.value) {
            controller.contentVisible.first { it }
            forceNextCycle = true
            return 0L
        }

        cycleNum++
        val debug = prefs.debugLiveMode
        forceNextCycle = false

        // The hold's cap anchor: when this read's frame entered the pipeline.
        // The latched frame may be up to one delivery older; that slack is
        // well inside the cap's tolerance.
        val captureAtMs = SystemClock.uptimeMillis()
        val raw = mgr.requestRaw(displayId)
        if (raw == null) {
            // Transient failure (or consent loss — requestRaw's
            // checkConsentLost stops live mode itself in that case).
            forceNextCycle = true
            return prefs.captureIntervalMs
        }

        try {
            // Rotation / display reconfig: crop-space bounds are void.
            if (screenshotW != 0 && (raw.width != screenshotW || raw.height != screenshotH)) {
                Log.w(TAG, "Capture dims changed (${screenshotW}x$screenshotH → ${raw.width}x${raw.height}), clearing state")
                clearDisplayed()
                return prefs.captureIntervalMs
            }

            // Identity guard — fullscreen-only is a platform limit.
            val contentSize = controller.contentSize.value
            if (contentSize != null &&
                (contentSize.x != raw.width || contentSize.y != raw.height)
            ) {
                if (!identityNotified) {
                    identityNotified = true
                    service.emitError(service.getString(R.string.error_single_app_not_fullscreen))
                }
                clearDisplayed()
                // Deliveries (and a resize callback) keep coming while the
                // user rearranges windows; each look re-checks cheaply.
                return prefs.captureIntervalMs
            }
            identityNotified = false

            // Secure-content guard: FLAG_SECURE renders black, silently.
            if (isAllBlack(raw)) {
                blackStreak++
                if (blackStreak == SECURE_BLACK_STREAK) {
                    service.emitError(service.getString(R.string.error_capture_blocked_secure))
                    clearDisplayed()
                }
                return prefs.captureIntervalMs
            }
            blackStreak = 0

            // OCR the full crop. No status-bar exclusion: a task stream
            // contains no system UI — those top rows are game content.
            val ocrStartMs = SystemClock.uptimeMillis()
            val pipeline = service.runOcr(raw, displayId, statusBarHeightOverride = 0)
            val ocrMs = SystemClock.uptimeMillis() - ocrStartMs

            // A hold gesture may have started during the OCR suspension.
            if (service.holdActive) return 100L

            val boxes = cachedBoxes ?: emptyList()
            if (pipeline == null && boxes.isEmpty()) {
                service.handleNoTextDetected(displayId)
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

            // Contamination tripwire — see the class doc.
            if (isOwnEcho(groups, boxes)) {
                echoStreak++
                DetectionLog.log("D$displayId c$cycleNum echo suspected ($echoStreak/$ECHO_STREAK_LIMIT)")
                if (echoStreak >= ECHO_STREAK_LIMIT) {
                    controller.demoteStreamKindToContaminated(
                        "own translation echoed at box rects"
                    )
                    service.onStreamKindDemoted()
                    return prefs.captureIntervalMs
                }
            } else {
                echoStreak = 0
            }

            // Reconcile in text space; then the typewriter hold.
            val verdicts = ScanlineReconciler.reconcile(groups, boxes)
            val holdOut = stabilityHold.filter(
                verdicts, captureAtMs, SystemClock.uptimeMillis(),
            )
            holdDeadlineMs = holdOut.nextDeadlineMs

            val kept = verdicts.keptBoxes + holdOut.heldBoxes
            val toTranslate = holdOut.toTranslate

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
                    service.handleNoTextDetected(displayId)
                } else {
                    showBoxes(kept)
                    sendFullStateToPanel(mgr.saveToCache(raw, displayId))
                }
                return pacing(prefs)
            }

            // Placeholders now (color-sampled skeletons; cached translations
            // land instantly), translation for the rest in-cycle — serial by
            // construction, so at most one OCR and one MT batch are ever in
            // flight (the loop's backpressure).
            val texts = toTranslate.map { it.text }
            val placeholders = OverlayToolkit.buildPlaceholderBoxes(
                texts,
                toTranslate.map { it.bounds },
                toTranslate.map { it.lineCount },
                raw, cropLeft, cropTop,
                toTranslate.map { it.orientation },
                toTranslate.map { it.alignment },
            )
            val partial = placeholders.mapIndexed { i, ph ->
                service.getCachedTranslation(texts[i])
                    ?.let { ph.copy(translatedText = it) } ?: ph
            }
            cachedBoxes = kept + partial
            showBoxes(kept + partial)
            if (partial.any { it.translatedText.isEmpty() }) {
                val translated =
                    OverlayToolkit.translatePlaceholders(service, placeholders, texts)
                cachedBoxes = kept + translated
                showBoxes(kept + translated)
            }
            sendFullStateToPanel(mgr.saveToCache(raw, displayId))
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

    private fun showBoxes(boxes: List<TextBox>) {
        service.showLiveOverlay(
            boxes, cropLeft, cropTop, screenshotW, screenshotH,
            pinholeMode = false, displayId = displayId,
        )
    }

    /** Drop everything displayed + the hold state; force the rebuild look. */
    private fun clearDisplayed() {
        cachedBoxes = null
        stabilityHold.clear()
        holdDeadlineMs = null
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        forceNextCycle = true
    }

    /** One box reading back its own translation at its own rect. Guarded
     *  against non-discriminating boxes (translation ≈ source: numbers,
     *  names) — those can't distinguish echo from game text. */
    private fun isOwnEcho(groups: List<OcrManager.OcrGroup>, boxes: List<TextBox>): Boolean {
        for (box in boxes) {
            if (box.translatedText.isEmpty()) continue
            if (!OverlayToolkit.isSignificantChange(box.translatedText, box.sourceText)) continue
            for (g in groups) {
                if (!Rect.intersects(g.bounds, box.bounds)) continue
                if (!OverlayToolkit.isSignificantChange(g.text, box.translatedText)) return true
            }
        }
        return false
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

    /** Build a TranslationResult from ALL current cachedBoxes and send to the
     *  in-app panel — same shape as the pinhole tier's panel sync. */
    private fun sendFullStateToPanel(screenshotPath: String?) {
        val boxes = cachedBoxes ?: return
        val appPanelVisible = !Prefs.isSingleScreen(service) && MainActivity.isInForeground
        if (!appPanelVisible) return

        val originalText = boxes.filter { it.sourceText.isNotEmpty() }
            .joinToString("\n") { it.sourceText }
        val translatedText = boxes.filter { it.translatedText.isNotEmpty() }
            .joinToString("\n\n") { it.translatedText }
        val segments = TextSegments.ofLines(boxes.map { it.sourceText })
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        service.emitResult(TranslationResult(
            originalText = originalText,
            segments = segments,
            translatedText = translatedText,
            timestamp = timestamp,
            screenshotPath = screenshotPath,
            langContext = Prefs(service).langContext(),
        ))
    }

    private companion object {
        const val TAG = "CleanStreamMode"

        /** Consecutive all-black frames before the secure-content message. */
        const val SECURE_BLACK_STREAK = 3

        /** Per-channel level at or below which a sample counts as black. */
        const val BLACK_LEVEL_MAX = 12

        /** Consecutive own-echo sightings before the tripwire demotes. */
        const val ECHO_STREAK_LIMIT = 2
    }
}
