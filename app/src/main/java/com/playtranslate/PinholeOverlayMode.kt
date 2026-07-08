package com.playtranslate

import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextDirection
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.graphics.scale

/** Inflation ratios for the FAR-suppression proximity check at runCycle step 9b.
 *  Asymmetric: vertical dominates because typewriter wrapping spills new lines
 *  above/below the dying overlay. Horizontal is the more likely knob to bump
 *  for long-line growth where new text exits the right edge well past the
 *  original width. */
private const val FAR_SUPPRESS_HORIZONTAL_RATIO = 0.5f
private const val FAR_SUPPRESS_VERTICAL_RATIO = 1.5f

/**
 * Simple translation overlay mode with Shadow Mask detection.
 *
 * Phase 1 (clean): Capture with no overlays → OCR → translate → show overlays.
 * Phase 2 (pinhole): Switch overlay backgrounds to pinholes → capture raw →
 *   restore solid → build composite (clean ref + pinholes) → OCR → detect changes.
 *
 * Overlays only disappear on button press or when game text changes.
 * No constant flicker from hide/show cycles.
 */
/**
 * @param service the enclosing capture service (for state access and coordinator calls)
 */
class PinholeOverlayMode(
    private val service: CaptureService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.TRANSLATION

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    // State
    private var cachedBoxes: List<TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var overlayBitmap: Bitmap? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    /** Monotonic cycle counter for [Prefs.debugLiveMode] logs. Lets log
     *  consumers correlate per-box pinhole metrics with the cycle's
     *  transition summary and the surrounding render-offscreen lines. */
    private var cycleNum = 0

    private enum class PinholeResult { KEEP, REMOVE }

    /** Result of [checkPinholes] plus the metrics that drove the
     *  classification decision. The metrics are only consumed by the
     *  [Prefs.debugLiveMode] log path, but [checkPinholes] computes them
     *  unconditionally on the way to its result, so threading them out is
     *  effectively free. */
    private data class PinholeOutcome(
        val result: PinholeResult,
        val pct: Float,
        val changed: Int,
        val total: Int,
        /** Max |per-channel residual| after the photometric fit — the
         *  headroom a real change has over the threshold. */
        val maxDelta: Int,
        /** Average per-channel fit slope (Q16; 65536 = 1.0). Well off 1.0
         *  with result=KEEP is a brightness ramp being absorbed. */
        val fitSlopeQ16: Long = 1L shl PhotometricFit.Q,
        /** Distinct glyph anchors with changed samples (audit A7). */
        val glyphAnchorsHit: Int = 0,
    )

    override fun start() {
        currentJob?.cancel()
        CaptureBackendResolver.active().startInputMonitoring(displayId) { onGameInput() }
        scheduleNextCycle()
    }

    /** Timestamp until which cycles pace at the backend floor because game
     *  input predicted a change (audit A4). */
    private var inputBurstUntilMs = 0L
    private var lastInputKickMs = 0L

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
    private fun onGameInput() {
        val now = android.os.SystemClock.uptimeMillis()
        inputBurstUntilMs = now + PinholeCalibration.INPUT_BURST_MS
        forceNextCycle = true
        val floor = liveSource()?.minCaptureIntervalMs ?: 500L
        if (now - lastInputKickMs >= floor) {
            lastInputKickMs = now
            // Same cancel-and-relaunch the old dismiss path used; the new
            // job's gate sees the force and runs at once. Cancelling a
            // mid-flight cycle (even mid-translation) matches the previous
            // input behavior.
            currentJob?.cancel()
            scheduleNextCycle()
        }
    }

    private fun scheduleNextCycle(delayMs: Long = 0) {
        currentJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                awaitCycleReason()
                val nextDelay = runCycle()
                scheduleNextCycle(nextDelay)
            } catch (e: CancellationException) {
                // Normal cancellation (stop/refresh/dismiss) — propagate.
                throw e
            } catch (e: Exception) {
                // Unexpected throw (display went away, WindowManager token
                // invalidated, bitmap op on detached view, etc.). Log and
                // reschedule so the cycle self-heals instead of silently
                // going dormant. Force the retry — self-healing must not
                // depend on the screen changing.
                Log.e("PinholeOverlayMode", "runCycle failed, rescheduling", e)
                forceNextCycle = true
                scheduleNextCycle(Prefs(service).captureIntervalMs)
            }
        }
    }

    /**
     * The delivery gate: park before the cycle until there is a reason to run
     * it. Reasons, in the order checked:
     *  - the capture source exposes no [DeliverySignal] (accessibility
     *    `takeScreenshot` — no silence evidence, poll exactly as before);
     *  - a hold is active ([runCycle]'s 100 ms hold-poll must keep polling);
     *  - a forced cycle is pending ([forceNextCycle]);
     *  - the mirror delivered a frame newer than the one the last cycle
     *    consumed (`seqNow > lastServedSeq`).
     *
     * Pacing (the `delay` before this call) always runs first, so cycles can
     * never fire closer together than they did on the blind timer — the gate
     * only ever *skips* work. The park is a cancellable suspend;
     * stop/refresh/dismiss cancel [currentJob] while parked exactly as they
     * cancelled a pending `delay`. Our own repaints composite and count as
     * deliveries, so a parked loop can always be woken by anything that
     * changes the screen — including us.
     */
    private suspend fun awaitCycleReason() {
        val signal = liveSource()?.deliverySignal ?: return
        val debug = Prefs(service).debugLiveMode
        var parkedAtMs = 0L
        while (!forceNextCycle && !service.holdActive &&
            signal.seqNow() <= signal.lastServedSeq
        ) {
            if (parkedAtMs == 0L) {
                parkedAtMs = android.os.SystemClock.uptimeMillis()
                if (debug) DetectionLog.log("D$displayId gate: parked at seq=${signal.seqNow()}")
            }
            signal.awaitSeqAfter(signal.lastServedSeq)
        }
        if (parkedAtMs != 0L && debug) {
            val why = when {
                forceNextCycle -> "forced"
                service.holdActive -> "hold"
                else -> "delivery seq=${signal.seqNow()}"
            }
            val ms = android.os.SystemClock.uptimeMillis() - parkedAtMs
            DetectionLog.log("D$displayId gate: wake after ${ms}ms ($why)")
        }
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

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    override fun dismiss() {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        resetState()
        scheduleNextCycle(Prefs(service).captureIntervalMs)
    }

    private fun resetState() {
        currentJob?.cancel()
        cachedBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        overlayBitmap?.recycle()
        overlayBitmap = null
        pendingRemovals.clear()
        outsideGrid.reset()
        inputBurstUntilMs = 0L
        // The gate is only meaningful relative to a previous look at the
        // screen. After a reset the model is empty (overlays hidden, caches
        // dropped), so the next cycle must run even in delivery silence —
        // otherwise a dismiss on a static screen parks forever with nothing
        // shown.
        forceNextCycle = true
    }

    /** Run the next cycle regardless of delivery silence. Starts true (the
     *  bootstrap look), set again by every state reset ([resetState]), every
     *  failed/aborted cycle (self-heal must not depend on the screen
     *  changing), and every cycle that mutated the overlay (exactly one
     *  follow-up look — which, on a static screen, finds nothing, forces
     *  nothing, and lets the loop park). Cleared right before each capture
     *  attempt. Main-thread only, like the rest of the mode's state. */
    private var forceNextCycle = true

    /** Sticky per-instance fallback: set when the identity-scale guard trips
     *  on the MediaProjection stream (the unresolved capture-vs-overlay size
     *  mismatch) while accessibility capture is available. Once set, this
     *  instance captures via the accessibility source — never worse off than
     *  the pre-split behavior. A mode rebuild (stop/start) retries the
     *  stream. */
    private var forceA11yCapture = false

    /** The capture source this mode's cycles AND the delivery gate use.
     *  Both must resolve identically — parking on one source's delivery
     *  signal while capturing from another would desync the served-frame
     *  cursor the gate compares against. */
    private fun liveSource(): LiveCaptureSource? =
        if (forceA11yCapture) CaptureBackendResolver.active().liveCaptureSource
        else CaptureBackendResolver.liveCaptureSourceFor(displayId)

    /** Consecutive A2 gate skips since the last full cycle — drives the
     *  reconciliation net (see runCycle's gate block). */
    private var gateSkipStreak = 0

    /** Reused sampling buffers for [OutsideChangeGate] — no steady-state
     *  allocation on skipped cycles. */
    private val gateBuffers = OutsideChangeGate.Buffers()

    /** Per-block temporal state for the outside gate (audit A3): volatility
     *  exclusion + the settle gate. Reset whenever the overlay layout
     *  changes or the mode's state resets. */
    private val outsideGrid = OutsideBlockGrid()

    /** Reused per-channel photometric-fit scratch for [checkPinholes]. */
    private val fitR = PhotometricFit.Fit()
    private val fitG = PhotometricFit.Fit()
    private val fitB = PhotometricFit.Fit()

    /** Removal hysteresis (audit A3): boxes whose pinhole check said REMOVE
     *  exactly once. A REMOVE lifts a box only on the second consecutive
     *  changed look — one-frame transients (hit-flash, a particle crossing
     *  the holes, a layout-settle glitch) revert to KEEP on the next frame
     *  and clear their entry instead of blinking the overlay. Keyed by box
     *  IDENTITY (kept boxes are carried by instance across cycles; replaced
     *  boxes are fresh instances): value equality would collapse two boxes
     *  rendering identical fields into one entry, letting the second
     *  "confirm" off the first's pend and bypass the two-look rule. Pruned
     *  against the surviving set every apply. */
    private val pendingRemovals: MutableSet<TextBox> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    // ── Unified Cycle ───────────────────────────────────────────────────

    /** True only when cached boxes are actually rendered on screen. An external
     *  hideTranslationOverlay (e.g. holdCancel) can null the overlay windows
     *  without clearing cachedBoxes — in that state this returns false so
     *  fillOverlayRegions and the isFirstCapture branch skip correctly.
     *  (Step 4's cleanRef reconcile uses bitmapRects directly, not this,
     *  because the visible-children signal is what cleanRef actually tracks.) */
    private fun hasOverlays(): Boolean =
        cachedBoxes != null &&
        CaptureBackendResolver.activeOverlayUi?.hasTranslationOverlay(displayId) == true

    /** Run one capture-detect-translate cycle. Returns the delay (ms) before the next cycle. */
    private suspend fun runCycle(): Long {
        val prefs = Prefs(service)
        if (service.holdActive) return 100L
        val mgr = liveSource()
        if (mgr == null) {
            // Backend unavailable (service unbinding / mid-swap). Retry
            // unconditionally — recovery must not wait for a delivery.
            forceNextCycle = true
            return prefs.captureIntervalMs
        }
        if (CaptureBackendResolver.activeOverlayUi == null) {
            // Overlay host gone (accessibility service died; reresolve may lag
            // the OS settings flush). MediaProjection capture can still work in
            // that window, but there is nowhere to render — skip the cycle
            // instead of burning OCR + translation on output showLiveOverlay
            // will drop. Poll-retry until the host returns or reresolve stops
            // this mode.
            forceNextCycle = true
            return prefs.captureIntervalMs
        }
        cycleNum++
        val debug = prefs.debugLiveMode

        // A pending force is consumed by reaching a real capture attempt.
        // Deliberately after the hold/mgr guards above: those returns must
        // not eat a force set by dismiss/refresh during a hold.
        forceNextCycle = false

        // Capture. Boxes that pinhole detection flags as changed are
        // removed and re-OCR'd on the next cycle; there is no longer a
        // dirty-companion buffer (see docs/dirty-overlay-archived-design.md).
        val raw = mgr.requestRaw(displayId)

        if (raw == null) {
            // Transient capture failure — a persistently failing capture must
            // keep retrying every interval, not park silently until the
            // screen happens to change.
            forceNextCycle = true
            return prefs.captureIntervalMs
        }

        try {
            // Mid-cycle dimension changes (rotation, display resize) invalidate
            // cleanRef and the cached state. Mirrors FuriganaMode.handleRawFrame's
            // mid-cycle recovery. Clear state inline — do NOT call resetState()
            // from here, because resetState cancels currentJob (which IS the
            // currently-running job). Self-cancellation works via cooperative
            // cancellation but is subtle; inline clearing is clearer.
            val existingRef = cleanRefBitmap
            if (existingRef != null &&
                (raw.width != existingRef.width || raw.height != existingRef.height)) {
                Log.w(
                    "PinholeOverlayMode",
                    "Capture dims changed (${existingRef.width}x${existingRef.height} → " +
                        "${raw.width}x${raw.height}), clearing cached state"
                )
                cachedBoxes = null
                cleanRefBitmap?.recycle()
                cleanRefBitmap = null
                overlayBitmap?.recycle()
                overlayBitmap = null
                CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                // State cleared + overlay hidden: the rebuild cycle must run
                // even if the post-rotation screen goes immediately static.
                forceNextCycle = true
                return prefs.captureIntervalMs
            }

            // Build FrameCoordinates for this cycle. At identity scale
            // (accessibility takeScreenshot on standard displays, the only
            // configuration this mode supports), viewToBitmap is a no-op via
            // reference short-circuit and bitmapRects share instances with
            // rects. See FrameCoordinates KDoc for details on the coordinate
            // spaces and why non-identity is fail-closed below.
            val ui = CaptureBackendResolver.activeOverlayUi
            val rects = ui?.boxScreenRects(displayId) ?: emptyList()
            val overlayDisplaySize = ui?.translationOverlayDisplaySize(displayId)
            val coords = FrameCoordinates(
                bitmapWidth = raw.width,
                bitmapHeight = raw.height,
                viewWidth = overlayDisplaySize?.x ?: 0,
                viewHeight = overlayDisplaySize?.y ?: 0,
                cropLeft = cropLeft,
                cropTop = cropTop,
            )

            // Non-identity scale is not supported. The pinhole detection math
            // in [checkPinholes] assumes the sparse view-resolution pinhole
            // mask translates 1:1 into bitmap pixels — which holds only when
            // screenshot dims == view dims. At non-identity scale:
            //   1. The pinhole mask's 3-pixel spacing is defined in view
            //      coordinates, but checkPinholes samples every 3 BITMAP
            //      pixels. At any scale != 1 the sampling grid no longer
            //      aligns with actual pinhole positions.
            //   2. More fundamentally, the `predicted = (ref + overlay) / 2`
            //      math assumes there EXIST bitmap positions where the raw
            //      pixel is a 50/50 blend of game and overlay. Under bitmap
            //      downsampling (e.g. MediaProjection virtual display), the
            //      sparse pinhole pattern smears across multiple view pixels
            //      per bitmap pixel; the averaged alpha becomes ~87% overlay
            //      uniformly and no 50/50 blend exists anywhere.
            //
            // Fail-closed rather than silently producing wrong results. To
            // actually support non-identity scale we'd need to rework the
            // pinhole pattern and detection math (see FrameCoordinates KDoc
            // for the full story).
            if (!coords.isIdentityScale) {
                // The recorded 1240-vs-1920 field mismatch means this path is
                // reachable. When it trips on the MediaProjection stream and
                // accessibility capture exists, fall back to it permanently
                // for this instance — an accessibility user must never end up
                // worse than the pre-split behavior. (User-visible notice is
                // an open l10n item; DetectionLog only for now.)
                val a11ySource = CaptureBackendResolver.active().liveCaptureSource
                if (!forceA11yCapture && a11ySource != null && a11ySource !== mgr) {
                    forceA11yCapture = true
                    DetectionLog.log(
                        "D$displayId identity mismatch (view=${coords.viewWidth}x${coords.viewHeight} " +
                            "bitmap=${raw.width}x${raw.height}) — falling back to accessibility capture"
                    )
                    forceNextCycle = true
                    return mgr.minCaptureIntervalMs
                }
                // Preserve the retry-every-interval*3 semantics under the
                // gate — parking here would hide the failure.
                forceNextCycle = true
                return prefs.captureIntervalMs * 3
            }

            val bitmapRects = coords.viewListToBitmap(rects)

            // ── A2: cheap change gate in front of OCR ──────────────────
            // Pixel evidence BEFORE the expensive stages: the pinhole check
            // (under boxes — computed once here, reused by step 8) plus a
            // sparse brightness-normalized luma diff of everything else
            // inside the OCR crop (OutsideChangeGate). Both quiet and
            // nothing pending → skip OCR/classification/render outright.
            // New text cannot appear without changing pixels — uncovered
            // space is the outside diff's territory, under-box space the
            // pinholes' — so the skip is sound down to the sample grid;
            // the reconciliation cycle below is the net for anything finer,
            // and a reconcile that finds work logs a gate MISS.
            //
            // A skipped cycle mutates nothing: cleanRef stays anchored at
            // the last full look (a per-skip refresh would let slow drifts
            // creep under the threshold), no OCR bitmap is built, no state
            // moves, and the delivery gate re-parks on return.
            var pinholePre: Array<PinholeOutcome>? = null
            var reconcileCycle = false
            run gate@{
                val gateBoxes = cachedBoxes ?: return@gate
                val gateRef = cleanRefBitmap ?: return@gate
                if (overlayBitmap == null) return@gate
                if (gateBoxes.isEmpty() || bitmapRects.size != gateBoxes.size) return@gate
                // Pending fills (skeleton or failed-empty boxes) keep full
                // cycles running — their recovery paths ride on OCR churn.
                if (gateBoxes.any { it.translatedText.isEmpty() }) return@gate

                val outcomes = Array(gateBoxes.size) { i ->
                    checkPinholes(raw, gateRef, bitmapRects[i], gateBoxes[i])
                }
                val allKeep = outcomes.all { it.result == PinholeResult.KEEP }
                val crop = OverlayToolkit.computeOcrCrop(
                    raw.width, raw.height,
                    service.activeRegionForDisplay(displayId),
                    service.getStatusBarHeightForDisplay(displayId),
                )
                val exclude = bitmapRects.map { r ->
                    Rect(r).apply {
                        inset(
                            -PinholeCalibration.GATE_EXCLUDE_INFLATE_PX,
                            -PinholeCalibration.GATE_EXCLUDE_INFLATE_PX,
                        )
                    }
                }
                val outside =
                    OutsideChangeGate.check(raw, gateRef, crop, exclude, gateBuffers, outsideGrid)
                reconcileCycle =
                    gateSkipStreak >= PinholeCalibration.GATE_RECONCILE_EVERY_SKIPS
                if (allKeep && !outside.fired && !reconcileCycle) {
                    // Every box is verifiably healthy on this frame — any
                    // pending one-look removals were transients; forget them.
                    pendingRemovals.clear()
                    // A block awaiting its stillness confirmation must get a
                    // follow-up look even if the screen has gone silent.
                    if (outside.pendingSettle) forceNextCycle = true
                    gateSkipStreak++
                    if (debug) {
                        DetectionLog.log(
                            "D$displayId c$cycleNum gate: skip #$gateSkipStreak " +
                                "(outside ${outside.changedSamples}/${outside.totalSamples} " +
                                "${outside.fitLabel()} mv=${outside.movingBlocks} " +
                                "vol=${outside.volatileBlocks}" +
                                (if (outside.pendingSettle) " settling" else "") + ")"
                        )
                    }
                    // Skipped cycles pace at the floor whenever the next look
                    // matters: during an input burst, and while any block is
                    // mid-settle — K=2 settle discipline at floor pacing
                    // costs ~0.5s; at interval pacing it tripled reaction
                    // time in the field (2026-07-08 regression).
                    val fastSkip = outside.pendingSettle ||
                        android.os.SystemClock.uptimeMillis() < inputBurstUntilMs
                    return if (fastSkip) mgr.minCaptureIntervalMs else prefs.captureIntervalMs
                }
                pinholePre = outcomes
                if (debug) {
                    val why = when {
                        reconcileCycle -> "reconcile after $gateSkipStreak skips"
                        !allKeep ->
                            "pinhole ${outcomes.count { it.result != PinholeResult.KEEP }} box(es)"
                        else ->
                            "outside ${outside.changedSamples}/${outside.totalSamples} " +
                                outside.fitLabel()
                    }
                    DetectionLog.log("D$displayId c$cycleNum gate: GO ($why)")
                }
            }
            gateSkipStreak = 0

            // 4. Reconcile cleanRef against the visible overlay state.
            //    Single site of truth for the cleanRef-tracks-overlays
            //    invariant. bitmapRects is the canonical signal: it's
            //    the overlay's children at step 2 capture time, i.e.
            //    exactly what raw shows and what updateCleanRef operates
            //    on. This cuts cleanly through every odd state —
            //      • external-hide (overlay view nulled) → empty
            //      • prior cycle did pinhole-REMOVE-all → empty
            //      • normal stable overlays → non-empty positions
            //    Empty branch drops any stale ref so the next cycle's
            //    step 11 can seed a fresh baseline from a pure-game raw.
            //    Non-empty branch maintains the existing ref; if it's
            //    somehow null here (external-hide-then-restore between
            //    cycles), pinhole detection skips for one cycle and step
            //    11 re-seeds when overlays re-place. The wholesale state
            //    resets (resetState / dim change / crop change) still
            //    null cleanRef inline because they bypass this cycle.
            if (bitmapRects.isEmpty()) {
                cleanRefBitmap?.recycle()
                cleanRefBitmap = null
            } else {
                cleanRefBitmap?.let { updateCleanRef(raw, it, bitmapRects) }
            }

            // 5. Prepare OCR image: fill overlay regions with bgColor
            val ocrImage: Bitmap
            if (hasOverlays()) {
                ocrImage = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
                fillOverlayRegions(ocrImage, bitmapRects)
            } else {
                ocrImage = raw
            }

            // 6. OCR — try/finally ensures the copy is recycled even if runOcr
            //          throws (e.g. CancellationException from resetState).
            val pipeline = try {
                service.runOcr(ocrImage, displayId)
            } finally {
                if (ocrImage !== raw && !ocrImage.isRecycled) ocrImage.recycle()
            }

            // A hold may have started during OCR suspension. Bail now to
            // avoid wasting CPU on classification/translation the blocked
            // showLiveOverlay will never render.
            if (service.holdActive) return 100L

            // No text on screen and no overlays → nothing to do
            if (pipeline == null && !hasOverlays()) {
                service.handleNoTextDetected(displayId)
                return prefs.captureIntervalMs
            }

            var anyRemoved = false
            val isFirstCapture = !hasOverlays()

            // On first capture, set crop/screenshot dimensions from pipeline.
            // On subsequent cycles, verify the pipeline's crop still matches
            // what we cached — drift without a dim change (e.g. statusBarHeight
            // toggling mid-session) invalidates the cached box coordinates in
            // the same way a dim change does, so handle it the same way.
            if (isFirstCapture && pipeline != null) {
                val (_, _, left, top, sw, sh) = pipeline
                cropLeft = left; cropTop = top; screenshotW = sw; screenshotH = sh
            } else if (pipeline != null) {
                val (_, _, pipeLeft, pipeTop, _, _) = pipeline
                if (pipeLeft != cropLeft || pipeTop != cropTop) {
                    Log.w(
                        "PinholeOverlayMode",
                        "Crop offsets changed ($cropLeft,$cropTop → " +
                            "$pipeLeft,$pipeTop), clearing cached state"
                    )
                    cachedBoxes = null
                    cleanRefBitmap?.recycle()
                    cleanRefBitmap = null
                    overlayBitmap?.recycle()
                    overlayBitmap = null
                    CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                    // Same reasoning as the dim-change reset above.
                    forceNextCycle = true
                    return prefs.captureIntervalMs
                }
            }

            val boxes = cachedBoxes ?: emptyList()

            // 7. Classify OCR results: content match, stale, or far (new text).
            //    The actual logic lives in Classification.kt as pure functions
            //    so it can be unit-tested without a live capture pipeline.
            //
            //    Classification reasons about *text* relationships, so it
            //    needs the boxes' OCR-derived bitmap rects (no rendering
            //    padding) — bitmapRects (from getChildScreenRects) include
            //    the ~14px boxPadding the renderer adds for visual breathing
            //    room, which would falsely reach across genuine paragraph
            //    gaps and trigger wouldGroup against unrelated neighbors.
            //    Pinhole keeps using bitmapRects below: it samples actual
            //    on-screen pixels, so the rendered (padded) rect is correct
            //    there.
            // RTL sources (Arabic) align paragraphs on the right edge — cross-frame
            // overlay matching must use the same convention as within-frame grouping
            // (LayoutAnalyzer.analyze) or live overlays go stale/duplicate.
            val sourceIsRtl =
                SourceLanguageProfiles[prefs.sourceLangId].textDirection == TextDirection.RTL
            val ocrBitmapRects: List<Rect>
            val classification: ClassificationResult
            val classifyCoords: FrameCoordinates?
            if (pipeline != null) {
                val (ocrResult, _, pipeCropLeft, pipeCropTop, _, _) = pipeline
                classifyCoords = FrameCoordinates(
                    bitmapWidth = raw.width,
                    bitmapHeight = raw.height,
                    viewWidth = overlayDisplaySize?.x ?: 0,
                    viewHeight = overlayDisplaySize?.y ?: 0,
                    cropLeft = pipeCropLeft,
                    cropTop = pipeCropTop,
                )
                ocrBitmapRects = boxes.map { classifyCoords.ocrToBitmap(it.bounds) }
                classification = classifyOcrResults(ocrResult, boxes, ocrBitmapRects, classifyCoords, sourceIsRtl)
            } else {
                classifyCoords = null
                ocrBitmapRects = emptyList()
                classification = ClassificationResult(emptySet(), emptySet(), emptyList())
            }
            val contentMatchRemovals = classification.contentMatchRemovals
            val staleOverlayIndices = classification.staleOverlayIndices
            var farOcrGroups = classification.farOcrGroups

            // 8. Pinhole change detection — any classified-as-changed box is
            //    removed and re-OCR'd on the next cycle. The previous design
            //    had a soft DIRTY state that parked the box on a companion
            //    overlay window for one cycle as a smooth-transition buffer;
            //    see docs/dirty-overlay-archived-design.md for the historical
            //    architecture. The companion was retired because its second
            //    full-screen TYPE_APPLICATION_OVERLAY window pushed AOSP's
            //    combined obscuring-opacity over the touch-passthrough cap
            //    on MediaProjection.
            val cleanRef = cleanRefBitmap
            val pinholeRemovals = mutableSetOf<Int>()
            if (cleanRef != null) {
                for ((idx, box) in boxes.withIndex()) {
                    if (idx >= bitmapRects.size) continue
                    if (idx in staleOverlayIndices) continue
                    // Reuse the A2 gate's outcomes when it ran this cycle —
                    // identical inputs (under-box cleanRef is frozen, so the
                    // gate-time snapshot survives updateCleanRef), saves the
                    // second per-box region read.
                    val outcome = pinholePre?.getOrNull(idx)
                        ?: checkPinholes(raw, cleanRef, bitmapRects[idx], box)
                    // Removal hysteresis (audit A3): the first changed look
                    // only marks the box pending; the removal applies on the
                    // second consecutive changed look. A one-frame transient
                    // reverts to KEEP on its next look and is forgotten.
                    val confirmed: Boolean
                    if (outcome.result == PinholeResult.REMOVE) {
                        confirmed = box in pendingRemovals
                        if (confirmed) {
                            pinholeRemovals.add(idx)
                            pendingRemovals.remove(box)
                        } else {
                            pendingRemovals.add(box)
                        }
                    } else {
                        confirmed = false
                        pendingRemovals.remove(box)
                    }
                    if (debug && outcome.result != PinholeResult.KEEP) {
                        val r = bitmapRects[idx]
                        val pctStr = "%.1f".format(outcome.pct * 100f)
                        val phase = if (confirmed) "REMOVE" else "REMOVE-pending"
                        DetectionLog.log(
                            "D$displayId c$cycleNum box$idx $phase " +
                                "text=\"${box.sourceText.take(20)}\" " +
                                "pct=$pctStr% changed=${outcome.changed}/${outcome.total} " +
                                "glyphAnchors=${outcome.glyphAnchorsHit} " +
                                "maxResidual=${outcome.maxDelta} " +
                                "a=%.2f ".format(outcome.fitSlopeQ16 / 65536.0) +
                                "rect=(${r.left},${r.top},${r.right},${r.bottom})"
                        )
                    }
                }
            }

            // 8b. Cascade stale to neighbors. See cascadeStaleRemovals in Classification.kt.
            //     Same coordinate-space reasoning as the proximity check
            //     above: cascade uses unpadded ocrBitmapRects so it agrees
            //     with classification's notion of "neighbor".
            val cascadedRemovals = cascadeStaleRemovals(staleOverlayIndices, boxes, ocrBitmapRects, sourceIsRtl)

            // 9. Resolve: compute final state from immutable snapshot in one pass
            val allRemovals = cascadedRemovals + pinholeRemovals + contentMatchRemovals

            // 9b. Suppress FAR groups near going-away cached overlays. The fill
            //     rect at step 5 hides the dying box's interior but bleeds
            //     slivers of new text around its edges; those slivers become
            //     FAR placeholders that get translated to garbage before the
            //     next cycle drops them. Drop them now — next cycle's OCR sees
            //     the full new text uncovered.
            //
            //     Subtracting contentMatchRemovals is REQUIRED, not just
            //     omission: a content-matched box (same text, drifted position)
            //     is the textbook pinhole REMOVE trigger and can also be
            //     pulled into cascade, so it can appear in any of the three
            //     sources above. Its paired replacement FAR sits a few px from
            //     the old rect (within inflation), so without the subtraction
            //     every drift-driven content-match stutters.
            val cc = classifyCoords
            val goingAwayIndices = (cascadedRemovals + pinholeRemovals) - contentMatchRemovals
            if (cc != null && goingAwayIndices.isNotEmpty() && farOcrGroups.isNotEmpty()) {
                val goingAwayBitmapRects = goingAwayIndices.mapNotNull { ocrBitmapRects.getOrNull(it) }
                val before = farOcrGroups.size
                farOcrGroups = farOcrGroups.filter { far ->
                    val farBitmap = cc.ocrToBitmap(far.bounds)
                    goingAwayBitmapRects.none { dying -> intersectsInflated(dying, farBitmap) }
                }
                if (debug && before != farOcrGroups.size) {
                    DetectionLog.log(
                        "D$displayId c$cycleNum suppressed ${before - farOcrGroups.size} FAR " +
                            "near ${goingAwayIndices.size} going-away boxes"
                    )
                }
            }

            val nextBoxes = boxes.mapIndexedNotNull { i, box ->
                if (i in allRemovals) null else box
            }

            cachedBoxes = nextBoxes.ifEmpty { null }
            // Pending removals only make sense for boxes that still exist —
            // anything removed this cycle (by any mechanism) or replaced by
            // a rebuild drops out of hysteresis tracking here. The prune must
            // match the set's identity semantics (a List.contains check would
            // compare by value).
            if (pendingRemovals.isNotEmpty()) {
                val survivors: MutableSet<TextBox> =
                    java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
                survivors.addAll(nextBoxes)
                pendingRemovals.retainAll(survivors)
            }
            val anyChanged = allRemovals.isNotEmpty()

            if (debug && (anyChanged || farOcrGroups.isNotEmpty())) {
                DetectionLog.log(
                    "D$displayId c$cycleNum transitions: " +
                        "removed=(pinhole=${pinholeRemovals.toSortedSet()}, " +
                        "contentMatch=${contentMatchRemovals.toSortedSet()}, " +
                        "cascade=${cascadedRemovals.toSortedSet()}, " +
                        "stale=${staleOverlayIndices.toSortedSet()}) " +
                        "far=${farOcrGroups.size} " +
                        "boxesIn=${boxes.size} boxesOut=${nextBoxes.size}"
                )
                // Why classification picked stale/contentMatch/far: dump
                // each OCR group's text+bounds and each cached box's
                // sourceText+bounds. Compare to figure out whether OCR is
                // finding the same text the placeholder already covers
                // (→ content-match should fire but isn't), or different
                // text near it (→ stale is correct), or whether bounds
                // are off enough that fillOverlayRegions left text visible.
                if (pipeline != null) {
                    val ocrR = pipeline.ocrResult
                    for ((i, g) in ocrR.groups.withIndex()) {
                        val t = g.text.take(40)
                        val b = g.bounds
                        DetectionLog.log(
                            "D$displayId c$cycleNum   ocr[$i] text=\"$t\" " +
                                "ocrRect=(${b.left},${b.top},${b.right},${b.bottom})"
                        )
                    }
                }
                for (i in boxes.indices) {
                    val b = boxes[i]
                    val br = bitmapRects.getOrNull(i)
                    DetectionLog.log(
                        "D$displayId c$cycleNum   box[$i] src=\"${b.sourceText.take(40)}\" " +
                            "ocrBounds=(${b.bounds.left},${b.bounds.top},${b.bounds.right},${b.bounds.bottom}) " +
                            "bitmapRect=${br?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "null"} " +
                            "dirty=${b.dirty}"
                    )
                }
            }

            // 10. Apply to the main overlay view — single commit point.
            if (anyChanged) {
                anyRemoved = allRemovals.isNotEmpty()
                if (nextBoxes.isNotEmpty()) {
                    showOverlayAndCapture(nextBoxes, cropLeft, cropTop, screenshotW, screenshotH)
                } else if (farOcrGroups.isEmpty()) {
                    // No surviving boxes AND no replacement coming — empty
                    // the main overlay so stale boxes don't linger.
                    // setBoxes(emptyList()) (not hideTranslationOverlayForDisplay)
                    // keeps the overlay window alive: tearing it down forces
                    // a wm.removeView / wm.addView round-trip whose
                    // composition latency the user sees as a visible "off"
                    // period.
                    CaptureBackendResolver.activeOverlayUi?.translationOverlayForDisplay(displayId)
                        ?.setBoxes(emptyList(), cropLeft, cropTop, screenshotW, screenshotH)
                }
                // else: farOcrGroups is non-empty — the path below will call
                // setBoxes(merged) which is the actual swap. Calling
                // setBoxes(emptyList()) here too would force an extra
                // rebuildChildren back-to-back; on stable content where
                // classifyOcrResults treats every match as
                // "contentMatchRemoval + queued placeholder", that means
                // every cycle does two redundant rebuilds. Fuzzy-match
                // dedup in TranslationOverlayView.setBoxes makes the
                // single setBoxes(merged) call below a no-op when the
                // placeholders match the existing children — zero rebuilds
                // for genuinely-unchanged content.
            }

            // 11. Seed cleanRef if missing AND we'll actually use it this
            //     cycle (about to place placeholders, or step 10 just
            //     re-showed surviving boxes after an external hide).
            //     Reaching here with cleanRef null means step 4 dropped it
            //     (bitmapRects was empty at step 2), so raw is pre-overlay
            //     game pixels — a valid baseline. The gate avoids one
            //     full-bitmap copy per idle cycle where the view is empty
            //     and there's nothing to place.
            if (cleanRefBitmap == null && (farOcrGroups.isNotEmpty() || nextBoxes.isNotEmpty())) {
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // 12. Show new text (with skeletons for uncached, instant for cached)
            if (farOcrGroups.isNotEmpty()) {
                val farTexts = farOcrGroups.map { it.text }
                val farBounds = farOcrGroups.map { it.bounds }
                val farLineCounts = farOcrGroups.map { it.lineCount }
                val farOrientations = farOcrGroups.map { it.orientation }
                val farAlignments = farOcrGroups.map { it.alignment }
                val placeholders = buildPlaceholderBoxes(farTexts, farBounds, farLineCounts, raw, cropLeft, cropTop, farOrientations, farAlignments)

                if (placeholders.isNotEmpty()) {
                    val partial = placeholders.mapIndexed { i, ph ->
                        val cached = service.getCachedTranslation(farTexts[i])
                        if (cached != null) ph.copy(translatedText = cached) else ph
                    }
                    val anyUncached = partial.any { it.translatedText.isEmpty() }

                    val merged = (cachedBoxes ?: emptyList()) + partial
                    cachedBoxes = merged
                    showOverlayAndCapture(merged, cropLeft, cropTop, screenshotW, screenshotH)

                    if (anyUncached) {
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val existing = cachedBoxes?.dropLast(placeholders.size) ?: emptyList()
                        val mergedFinal = existing + translated
                        cachedBoxes = mergedFinal
                        showOverlayAndCapture(mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
                    }

                }
            }

            // 13. Keep the panel in sync with cachedBoxes — fire on far
            //     groups OR removals so removal-only cycles don't go stale.
            if (farOcrGroups.isNotEmpty() || allRemovals.isNotEmpty()) {
                if (cachedBoxes.isNullOrEmpty()) {
                    service.handleNoTextDetected(displayId)
                } else {
                    sendFullStateToPanel(mgr.saveToCache(raw, displayId))
                }
            }

            // 14. Timing. A cycle that mutated the overlay (removals applied
            //     or new text placed) forces exactly one follow-up look — the
            //     deterministic replacement for relying on our own repaint
            //     echoing back through the mirror as a delivery. On a static
            //     screen the follow-up finds nothing, forces nothing, and the
            //     loop parks.
            if (anyChanged || farOcrGroups.isNotEmpty()) {
                forceNextCycle = true
                // The overlay layout changed: block membership under the
                // exclusion rects shifted and the reference re-baselined, so
                // the outside grid's temporal state is stale.
                outsideGrid.reset()
            }
            // A pending (one-look) removal needs its confirming second look
            // even if the screen delivers nothing further — the change that
            // tripped it may have settled into silence, and silence would
            // otherwise park the loop with a stale overlay up.
            if (pendingRemovals.isNotEmpty()) forceNextCycle = true
            if (reconcileCycle && (anyChanged || farOcrGroups.isNotEmpty())) {
                // The A2 gate's false-negative metric: the safety-net cycle
                // found work the gate had been skipping past. Loud on
                // purpose — a nonzero rate here means the grid/thresholds
                // miss real changes and the net must stay.
                DetectionLog.log(
                    "D$displayId c$cycleNum gate MISS: reconcile found " +
                        "removed=${allRemovals.size} far=${farOcrGroups.size}"
                )
            }
            val inInputBurst =
                android.os.SystemClock.uptimeMillis() < inputBurstUntilMs
            // Floor pacing also covers pending one-look removals: the
            // confirming look should land ~250ms later, not a full interval
            // — two-look hysteresis at interval pacing doubled stale-overlay
            // latency in the field.
            return if (anyRemoved || inInputBurst || pendingRemovals.isNotEmpty()) {
                mgr.minCaptureIntervalMs
            } else {
                prefs.captureIntervalMs
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /** Show overlay in pinhole mode, wait for layout, capture screen rects and
     *  overlay render. The `overlayBitmap` produced here is at view dimensions;
     *  [checkPinholes] assumes view dims == screenshot dims (identity scale)
     *  and [runCycle] fails closed before reaching here if that assumption
     *  doesn't hold. Pinhole mode is set at view construction via
     *  [PlayTranslateAccessibilityService.showTranslationOverlay]'s
     *  `pinholeMode` parameter, which eliminates the ordering/timing race
     *  between flipping a mutable flag and [TranslationOverlayView.rebuildChildren]. */
    private suspend fun showOverlayAndCapture(
        boxes: List<TextBox>,
        left: Int, top: Int, sw: Int, sh: Int
    ) {
        service.showLiveOverlay(boxes, left, top, sw, sh, pinholeMode = true, displayId = displayId)
        // Wait for children to be laid out before snapshotting. addOverlayWindow
        // is async; onSizeChanged posts rebuildChildren; rebuildChildren adds
        // children that themselves need a layout pass. Until that completes,
        // renderToOffscreen returns an empty/stale bitmap and pinhole detection
        // over-flags REMOVE for every box on the next cycle. Poll up to ~133ms
        // and fall through if it never settles.
        val ui = CaptureBackendResolver.activeOverlayUi
        var waited = 0
        while (waited < 8 && ui?.areTranslationBoxesLaidOut(displayId) != true) {
            waitVsync(1)
            waited++
        }
        if (waited >= 8) Log.w("PinholeOverlayMode", "renderToOffscreen: layout never settled after 8 vsyncs on display $displayId")
        overlayBitmap?.recycle()
        overlayBitmap = ui?.renderTranslationOverlayOffscreen(displayId)
        if (Prefs(service).debugLiveMode) {
            val ob = overlayBitmap
            val size = ui?.translationOverlayDisplaySize(displayId)
            DetectionLog.log(
                "D$displayId c$cycleNum renderOffscreen: settled=${waited}vsync " +
                    "displayDims=${size?.x ?: -1}x${size?.y ?: -1} " +
                    "bitmapDims=${ob?.width ?: -1}x${ob?.height ?: -1} " +
                    "boxCount=${boxes.size}"
            )
        }
    }

    // ── Detection Helpers ───────────────────────────────────────────────

    /** Fill non-dirty overlay regions in a mutable bitmap with their background
     *  color. Uses the actual rendered child rects ([bitmapRects], from
     *  [com.playtranslate.ui.TranslationOverlayView.getChildScreenRects]) so the
     *  fill matches what the user sees on screen.
     *
     *  Earlier versions computed the fill from each box's stored `bounds` +
     *  a fixed padding. That diverged from the rendered extent whenever
     *  [com.playtranslate.ui.TranslationOverlayView.rebuildChildren]'s
     *  overlap-resolution pass shrank a child's rect (e.g. when a wide
     *  multi-line cached overlay had a slight x-overlap with a small
     *  adjacent-row indicator). The bounds-based fill then covered an area
     *  where nothing was rendered on screen, so an exposed game-text line
     *  inside the cached box's bounds was visible to the user but obscured
     *  from ML Kit. Using the rendered rects keeps the two views aligned.
     *
     *  [bitmapRects] is in cleanBoxes order (the non-dirty subset of
     *  cachedBoxes, in cachedBoxes' original order — see runCycle step 9).
     *  Index alignment via sequential walk over non-dirty boxes. */
    private fun fillOverlayRegions(bitmap: Bitmap, bitmapRects: List<Rect>) {
        val boxes = cachedBoxes ?: return
        // Small anti-aliasing buffer beyond the rendered overlay's edge, so
        // ML Kit doesn't read AA fringe pixels as glyph fragments. Kept tiny
        // (3 px) so adjacent text lines outside the rendered overlay aren't
        // accidentally obscured — see PinholeOverlayMode fillOverlayRegions kdoc.
        val aaBuffer = 3
        val paint = android.graphics.Paint()
        val canvas = Canvas(bitmap)
        var rectIdx = 0
        for (box in boxes) {
            if (box.dirty) continue
            val rect = bitmapRects.getOrNull(rectIdx) ?: break
            rectIdx++
            val l = (rect.left - aaBuffer).coerceAtLeast(0)
            val t = (rect.top - aaBuffer).coerceAtLeast(0)
            val r = (rect.right + aaBuffer).coerceAtMost(bitmap.width)
            val b = (rect.bottom + aaBuffer).coerceAtMost(bitmap.height)
            paint.color = box.bgColor or 0xFF000000.toInt()
            canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
        }
    }

    /**
     * Check pinhole pixels in the given rect: KEEP (no change) or REMOVE
     * (sample fraction above threshold).
     *
     * [bitmapRect] indexes into raw, cleanRef, and overlayBitmap — all three
     * are expected to be at the same resolution. Callers should pre-convert
     * view-space rects via [FrameCoordinates.viewToBitmap] before passing in.
     *
     * ## Scale assumption (important)
     *
     * This function is only valid at identity scale (screenshot dims == view
     * dims). [runCycle] fails closed at non-identity scale before reaching
     * here; do not call this at non-identity scale without re-reading the
     * following and reworking the math.
     *
     * The core detection math is:
     *
     *     predicted[i] = (cleanRef[i] + overlayBitmap[i]) / 2
     *     raw[i]       ≈ a·predicted[i] + b        (per-channel affine fit)
     *     residual[i]  = raw[i] − (a·predicted[i] + b)
     *
     * and a pinhole counts as changed when any channel's |residual| exceeds
     * [PinholeCalibration.SPLATTER_THRESHOLD]. The affine fit (audit A3,
     * see [PhotometricFit]) absorbs compositor-side brightness transforms —
     * the pre-sleep dim, auto-brightness — that a raw |raw − predicted|
     * compare misread as near-total change on high-contrast boxes.
     *
     * This assumes that AT PINHOLE POSITIONS, the raw on-screen pixel is a
     * 50/50 blend of the clean game background (cleanRef) and the solid
     * overlay rendering (overlayBitmap). That's true because:
     *
     *   1. [com.playtranslate.ui.TranslationOverlayView.createPinholeMask]
     *      generates a full-view mask with alpha
     *      [PinholeCalibration.MASK_ALPHA] (50%) at sparse pinhole positions
     *      spaced [PinholeCalibration.PINHOLE_SPACING] apart, 0 elsewhere.
     *      On the MediaProjection backend the window α is reduced to the
     *      system obscuring cap and the mask alpha is compensated so the
     *      *effective* pinhole α is still 50% — the math below is invariant
     *      under that compensation.
     *   2. [com.playtranslate.ui.TranslationOverlayView.dispatchDraw]
     *      composites that mask via DST_OUT on the rendered overlay,
     *      punching 50% holes at the mask positions and leaving non-pinhole
     *      positions fully opaque.
     *   3. The final on-screen pixel at a pinhole is therefore
     *      50% overlay + 50% game.
     *
     * The sampling loop iterates every pixel in the box region and skips
     * non-pinhole positions via [isPinholePosition] using **view-local**
     * coordinates derived from the box's on-screen rect, so the box-local
     * sampling grid lines up with the view's actual on-screen holes.
     *
     * ## Why this breaks at non-identity scale
     *
     * At non-identity scale (e.g. MediaProjection with a scaled virtual
     * display, producing a bitmap smaller than the view):
     *
     *   - The mask's 3-view-pixel spacing no longer corresponds to 3-bitmap-
     *     pixel spacing. Sampling every 3 bitmap pixels hits positions that
     *     aren't actually pinholes.
     *   - More fundamentally: bitmap downsampling averages multiple view
     *     pixels per bitmap pixel. A 2x2 view block contains ~1 pinhole
     *     pixel at 50% alpha and ~3 non-pinhole pixels at 100% alpha,
     *     averaging to ~87% alpha. No bitmap pixel corresponds to a 50%
     *     blend; every bitmap pixel is at ~87% overlay uniformly. The
     *     `predicted = (ref + overlay) / 2` math never matches raw; every
     *     position reports a large delta and the classifier over-flags.
     *
     * Supporting non-identity scale would require, at minimum:
     *   - A pinhole pattern that survives downsampling (e.g. larger mask
     *     elements, not single pixels), OR
     *   - Generating the mask at bitmap resolution and compositing it
     *     directly into `overlayBitmap` so detection has a known-position
     *     pinhole pattern in bitmap space, AND
     *   - Re-tuning [PinholeCalibration.SPLATTER_THRESHOLD] and
     *     [PinholeCalibration.PINHOLE_CHANGE_PCT] for whatever new blend
     *     ratio results.
     *
     * None of this is done today. Identity scale only.
     */
    private fun checkPinholes(
        raw: Bitmap, cleanRef: Bitmap, bitmapRect: Rect, box: TextBox
    ): PinholeOutcome {
        val keepZero = PinholeOutcome(PinholeResult.KEEP, 0f, 0, 0, 0)
        val overlay = overlayBitmap ?: return keepZero
        val spacing = PinholeCalibration.PINHOLE_SPACING

        val left = bitmapRect.left.coerceIn(0, raw.width)
        val top = bitmapRect.top.coerceIn(0, raw.height)
        val right = bitmapRect.right.coerceIn(0, raw.width)
        val bottom = bitmapRect.bottom.coerceIn(0, raw.height)
        val regionW = right - left
        val regionH = bottom - top
        if (regionW <= 0 || regionH <= 0) return keepZero

        val rawPixels = IntArray(regionW * regionH)
        raw.getPixels(rawPixels, 0, regionW, left, top, regionW, regionH)
        val refPixels = IntArray(regionW * regionH)
        cleanRef.getPixels(refPixels, 0, regionW, left, top, regionW, regionH)

        // overlayBitmap is the display-sized composite of every clean box
        // window's content (no pinholes) — slice it by the same rect as raw.
        val ovLeft = left.coerceIn(0, overlay.width)
        val ovTop = top.coerceIn(0, overlay.height)
        val ovRight = right.coerceIn(0, overlay.width)
        val ovBottom = bottom.coerceIn(0, overlay.height)
        val ovW = ovRight - ovLeft
        val ovH = ovBottom - ovTop
        if (ovW != regionW || ovH != regionH) return keepZero
        val ovPixels = IntArray(regionW * regionH)
        overlay.getPixels(ovPixels, 0, regionW, ovLeft, ovTop, regionW, regionH)

        // Two passes over the fetched regions (audit A3 normalization).
        //
        // Pass 1 accumulates per-channel least-squares sums for the affine
        // fit raw ≈ a·predicted + b; pass 2 thresholds the per-channel
        // RESIDUALS from that fit instead of the raw deltas. When the game
        // under the box is unchanged, any brightness pipeline the compositor
        // applies (the pre-sleep dim, auto-brightness, night light) acts
        // ~affinely on the whole box and collapses into (a, b) — residuals
        // stay at the noise floor and the box KEEPs, where the old absolute
        // compare flagged 85–95% of samples on a mere dim ramp (Thor
        // 2026-07-08, c35/c56). A real text change decorrelates raw from
        // predicted, no line fits it, and the residuals stay large.
        //
        // Mask geometry note (unchanged): the mask is generated at
        // view-global origin, so the grid is tested at (left+px, top+py) —
        // at identity scale view-space == bitmap-space. Sampling box-local
        // would miss the actual on-screen holes for any box whose top-left
        // isn't grid-aligned.
        var totalPinholes = 0
        var sxR = 0L; var syR = 0L; var sxxR = 0L; var sxyR = 0L
        var sxG = 0L; var syG = 0L; var sxxG = 0L; var sxyG = 0L
        var sxB = 0L; var syB = 0L; var sxxB = 0L; var sxyB = 0L

        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                totalPinholes++
                val i = py * regionW + px
                val refPx = refPixels[i]
                val ovPx = ovPixels[i]
                val rawPx = rawPixels[i]
                // predicted = clean_ref * 0.5 + overlay_rendered * 0.5
                val pR = ((Color.red(refPx) + Color.red(ovPx)) / 2).toLong()
                val pG = ((Color.green(refPx) + Color.green(ovPx)) / 2).toLong()
                val pB = ((Color.blue(refPx) + Color.blue(ovPx)) / 2).toLong()
                val rR = Color.red(rawPx).toLong()
                val rG = Color.green(rawPx).toLong()
                val rB = Color.blue(rawPx).toLong()
                sxR += pR; syR += rR; sxxR += pR * pR; sxyR += pR * rR
                sxG += pG; syG += rG; sxxG += pG * pG; sxyG += pG * rG
                sxB += pB; syB += rB; sxxB += pB * pB; sxyB += pB * rB
            }
        }
        if (totalPinholes == 0) return keepZero

        PhotometricFit.finish(totalPinholes, sxR, syR, sxxR, sxyR, fitR)
        PhotometricFit.finish(totalPinholes, sxG, syG, sxxG, sxyG, fitG)
        PhotometricFit.finish(totalPinholes, sxB, syB, sxxB, sxyB, fitB)

        // Glyph anchors (audit A7): approximated text-line probe points.
        // Changed samples near two DISTINCT anchors mark the box suspect
        // regardless of area percentage — a swapped digit in a wide box
        // moves far too few samples for the pct rule but must land on the
        // text rows. Hits are tracked in one Long bitset.
        val vertical =
            box.orientation == com.playtranslate.language.TextOrientation.VERTICAL
        val anchors = GlyphAnchors.forBox(bitmapRect, box.lineCount, vertical)
        var anchorHits = 0L

        var changedPinholes = 0
        var maxDelta = 0
        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                val i = py * regionW + px
                val refPx = refPixels[i]
                val ovPx = ovPixels[i]
                val rawPx = rawPixels[i]
                val predR = ((Color.red(refPx) + Color.red(ovPx)) / 2)
                val predG = ((Color.green(refPx) + Color.green(ovPx)) / 2)
                val predB = ((Color.blue(refPx) + Color.blue(ovPx)) / 2)
                val dr = kotlin.math.abs(PhotometricFit.residual(fitR, predR, Color.red(rawPx)))
                val dg = kotlin.math.abs(PhotometricFit.residual(fitG, predG, Color.green(rawPx)))
                val db = kotlin.math.abs(PhotometricFit.residual(fitB, predB, Color.blue(rawPx)))
                val delta = maxOf(dr, dg, db)
                if (delta > maxDelta) maxDelta = delta
                if (dr > PinholeCalibration.SPLATTER_THRESHOLD ||
                    dg > PinholeCalibration.SPLATTER_THRESHOLD ||
                    db > PinholeCalibration.SPLATTER_THRESHOLD) {
                    changedPinholes++
                    if (anchors.isNotEmpty()) {
                        val a = GlyphAnchors.anchorNear(anchors, left + px, top + py)
                        if (a in 0 until GlyphAnchors.MAX_ANCHORS) {
                            anchorHits = anchorHits or (1L shl a)
                        }
                    }
                }
            }
        }

        val pct = changedPinholes.toFloat() / totalPinholes
        val glyphAnchorsHit = java.lang.Long.bitCount(anchorHits)
        val result = if (pct >= PinholeCalibration.PINHOLE_CHANGE_PCT ||
            glyphAnchorsHit >= PinholeCalibration.GLYPH_PROBE_MIN_ANCHORS
        ) {
            PinholeResult.REMOVE
        } else {
            PinholeResult.KEEP
        }
        val avgSlope = (fitR.slopeQ16 + fitG.slopeQ16 + fitB.slopeQ16) / 3
        return PinholeOutcome(
            result, pct, changedPinholes, totalPinholes, maxDelta, avgSlope, glyphAnchorsHit,
        )
    }

    /**
     * Update clean ref in-place: copy non-overlay pixels from raw into the
     * existing cleanRef. Cached box positions stay frozen at their initial
     * pre-overlay game content (pinhole detection relies on that
     * invariant), while everything else is refreshed from raw.
     *
     * Takes pre-converted bitmap-space [bitmapRects] from the caller (built
     * via [FrameCoordinates.viewListToBitmap]). The caller is responsible for
     * the view-to-bitmap conversion so this function doesn't need to know
     * about the view at all.
     *
     * Step 4 only calls this on the non-empty bitmapRects branch, so
     * [bitmapRects] is non-empty in practice. The early return is a
     * defensive no-op.
     */
    private fun updateCleanRef(raw: Bitmap, ref: Bitmap, bitmapRects: List<Rect>) {
        if (bitmapRects.isEmpty()) return
        val w = ref.width
        val h = ref.height

        // Save overlay region pixels from ref (clean game content)
        val savedRegions = bitmapRects.map { rect ->
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) return@map null
            val pixels = IntArray(regionW * regionH)
            ref.getPixels(pixels, 0, regionW, left, top, regionW, regionH)
            pixels
        }

        // Overwrite entire ref with raw (fresh non-overlay game content)
        val allPixels = IntArray(w * h)
        raw.getPixels(allPixels, 0, w, 0, 0, w, h)
        ref.setPixels(allPixels, 0, w, 0, 0, w, h)

        // Restore overlay regions from saved pixels
        for ((i, rect) in bitmapRects.withIndex()) {
            val pixels = savedRegions[i] ?: continue
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) continue
            ref.setPixels(pixels, 0, regionW, left, top, regionW, regionH)
        }
    }

    private fun isPinholePosition(x: Int, y: Int, spacing: Int): Boolean {
        if (y % spacing != 0) return false
        val rowGroup = (y / spacing) % 2
        val xOffset = if (rowGroup == 0) 0 else spacing / 2
        return (x - xOffset) % spacing == 0 && x >= xOffset
    }

    // ── Panel ────────────────────────────────────────────────────────────

    /**
     * Build a TranslationResult from ALL current cachedBoxes and send to the
     * in-app panel. No re-OCR is needed — every cached box already carries
     * its sourceText + translatedText.
     */
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

    // ── Translation Helpers ─────────────────────────────────────────────

    /** Build placeholder TextBoxes with empty text (skeleton indicators). Instant, no network. */
    private fun buildPlaceholderBoxes(
        texts: List<String>, bounds: List<Rect>, lineCounts: List<Int>,
        raw: Bitmap, left: Int, top: Int,
        orientations: List<com.playtranslate.language.TextOrientation> = emptyList(),
        alignments: List<com.playtranslate.language.TextAlignment> = emptyList()
    ): List<TextBox> {
        val colorScale = 4
        val colorRef = raw.scale(raw.width / colorScale, raw.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(colorRef, bounds, left, top, colorScale)
        } finally {
            colorRef.recycle()
        }
        return bounds.mapIndexed { idx, rect ->
            val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
            val orient = orientations.getOrElse(idx) { com.playtranslate.language.TextOrientation.HORIZONTAL }
            val align = alignments.getOrElse(idx) { com.playtranslate.language.TextAlignment.LEFT }
            TextBox("", rect, bg, tc, lineCounts.getOrElse(idx) { 1 },
                sourceText = texts.getOrElse(idx) { "" }, orientation = orient, alignment = align)
        }
    }

    /** Translate texts and return placeholders with filled translatedText. */
    private suspend fun translatePlaceholders(
        placeholders: List<TextBox>, texts: List<String>
    ): List<TextBox> {
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()
        val translations = Array(texts.size) { "" }

        for ((idx, text) in texts.withIndex()) {
            val cached = service.getCachedTranslation(text)
            if (cached != null) {
                translations[idx] = cached
            } else {
                uncachedIndices.add(idx)
                uncachedTexts.add(text)
            }
        }

        if (uncachedTexts.isNotEmpty()) {
            val results = service.translateGroupsSeparately(uncachedTexts)
            for ((i, idx) in uncachedIndices.withIndex()) {
                translations[idx] = results.getOrNull(i)?.text ?: ""
            }
        }

        return placeholders.mapIndexed { idx, ph ->
            ph.copy(translatedText = translations.getOrElse(idx) { "" })
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────


    private suspend fun waitVsync(frames: Int) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /** Inflate [dying] by [FAR_SUPPRESS_HORIZONTAL_RATIO] / [FAR_SUPPRESS_VERTICAL_RATIO]
     *  of its own width/height and test intersection with [far]. Used by step 9b. */
    private fun intersectsInflated(dying: Rect, far: Rect): Boolean {
        val dx = (dying.width() * FAR_SUPPRESS_HORIZONTAL_RATIO).toInt()
        val dy = (dying.height() * FAR_SUPPRESS_VERTICAL_RATIO).toInt()
        val inflated = Rect(dying).apply { inset(-dx, -dy) }
        return Rect.intersects(inflated, far)
    }
}
