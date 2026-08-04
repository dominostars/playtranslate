package com.playtranslate

import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextDirection
import com.playtranslate.model.OcrProvenance
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.graphics.scale

/** THROWAWAY instrumentation (2026-07-09) — trigger economics for the
 *  verification-look design. Counts, per box, how often the pinhole
 *  changed-fraction lands in the "gray zone" between the noise floor and
 *  the removal bar — the band where a verification look (hide box → clean
 *  capture → region OCR) WOULD fire. The 2026-07-09 stuck-menu failure
 *  lived at 1.3–4.2%, squarely in-band. Log-only, no behavior; emits a
 *  per-box histogram every ~20s plus a rate-limited ENTER line per
 *  crossing so trigger timing can be correlated with game events. Delete
 *  once the rates are measured across several games. */
private const val GRAYZONE_COUNTER = true

/** Provisional gray-zone floor (fraction of holes changed). The emitted
 *  histogram has sub-floor buckets so any floor can be re-derived from the
 *  data offline — this constant only styles the ENTER lines. */
private const val GRAYZONE_MIN_PCT = 0.01f

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

    // State
    private var cachedBoxes: List<TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var overlayBitmap: Bitmap? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    /** Most-recent OCR provenance for panel emissions ([sendFullStateToPanel]):
     *  refreshed on every full look whose runOcr returned a result. Removal-only
     *  and sweep emissions (runOcr null — no text found) reuse the last pass's
     *  identity: cachedBoxes were read by SOME earlier pass, and most-recent is
     *  the same accepted slop as the reconciler tier stamping the current
     *  cycle's provenance over kept anchors from earlier cycles/engines. */
    private var panelProvenance: OcrProvenance? = null
    /** Monotonic cycle counter for [Prefs.debugLiveMode] logs. Lets log
     *  consumers correlate per-box pinhole metrics with the cycle's
     *  transition summary and the surrounding render-offscreen lines. */
    private var cycleNum = 0

    /** Debug-gated trace of the commit stream (step-12 far groups) — the
     *  offline feed for translation-log write-gate validation. Null unless
     *  [Prefs.debugLogTrace]; a null check per cycle, an async append per
     *  committing cycle. */
    private val logTrace =
        com.playtranslate.translationlog.LogTraceRecorder.createIfEnabled(service, displayId)

    /** Sentence-gated typewriter dispatch over the step-12 far groups —
     *  see [TypewriterGate]. Whole-read dispatch only on this tier: a
     *  prefix box placed over still-typing text composites into the frame,
     *  gets detected as changed, and flashes out within a cycle. */
    private val typewriterGate = TypewriterGate()

    /** Debug-only quiet-pixel telemetry for the parked accelerator. */
    private val quietProbe = TypewriterQuietProbe()

    /** Earliest open typewriter-hold cap (uptime ms). Pacing and the
     *  engine's park are clamped to it, so a held reveal that finishes
     *  into a static screen still gets its releasing read — the delivery
     *  gate alone would park forever on the now-static frame. */
    private var typewriterDeadlineMs: Long? = null

    /** Production recording backend (Text History / LLM context): feed the
     *  shown stream — group source + the translation it got (with the
     *  producing backend riding the box) — for indices where [boxFor]
     *  returns a box with a non-empty translation. Callers pass the pair
     *  captured BEFORE any translate call, so a mid-flight language change
     *  can't relabel old-pair rows. The recorder no-ops while both
     *  features are off and swallows its own failures. */
    private fun recordShown(
        groups: List<FarGroup>,
        src: String,
        tgt: String,
        boxFor: (Int) -> TextBox?,
    ) {
        groups.forEachIndexed { i, g ->
            val box = boxFor(i) ?: return@forEachIndexed
            if (box.translatedText.isNotEmpty()) {
                service.translationLogRecorder.onShown(
                    g.text, box.translatedText, g.bounds, src, tgt, box.backendDisplayName,
                )
            }
        }
    }

    /** The recording pair at this instant (translation code + target). */
    private fun recordPair(): Pair<String, String> {
        val prefs = Prefs(service)
        return SourceLanguageProfiles[prefs.sourceLangId].translationCode to prefs.targetLang
    }

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
        /** Max per-channel |raw − predicted| — the headroom a real change
         *  has over [PinholeCalibration.SPLATTER_THRESHOLD]. */
        val maxDelta: Int,
        /** Distinct glyph anchors with changed samples — telemetry only
         *  (audit A7; disarmed 2026-07-08 pending ink-aware placement). */
        val glyphAnchorsHit: Int = 0,
    )

    /** The shared pacing/gate/input scheduler — see [LiveCycleEngine] for
     *  the delivery-gate and input-burst semantics. Our own repaints
     *  composite into the whole-display mirror and count as deliveries, so
     *  a parked loop can always be woken by anything that changes the
     *  screen — including us. */
    private val engine = LiveCycleEngine(
        scope, service, displayId, "PinholeOverlayMode",
        source = ::liveSource,
        parkDeadlineMs = { typewriterDeadlineMs },
        firstCycleGate = { service.awaitFirstCycleClear() },
    ) { runCycle() }

    override fun start() {
        engine.cancelCurrent()
        // Input dismisses and waits out an interval (restored 2026-07-20,
        // reverting audit A4's input burst for THIS tier only). The
        // asymmetry with [ReconcilerLiveMode], which keeps the burst, is the
        // occlusion model rather than taste: on a clean task mirror the game
        // text under a displayed box stays directly readable, so an input
        // only ever needs a FASTER look. Here our overlays composite into
        // the frame, so what sits under a box cannot be read at all — change
        // is INFERRED from pinhole samples against a predicted blend. An
        // input is the strongest available evidence that the inference just
        // went wrong under every box at once, and lifting the overlays is
        // the only way to actually read what replaced them.
        CaptureBackendResolver.active().startInputMonitoring(displayId) { dismiss() }
        engine.scheduleNext()
    }

    override fun stop() {
        scope.cancel()
        resetState()
        // Always-on (non-debug) counters — ride the diagnostics log export
        // so a field report self-attributes without logcat instructions.
        Log.i("PinholeOverlayMode", "typewriter stats: ${typewriterGate.stats.summary()}")
        typewriterGate.clear()

        CaptureBackendResolver.active().stopInputMonitoring(displayId)
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
    }

    override fun refresh() {
        resetState()
        // Full gate clear, not just holds: refresh() is reached by
        // coordinate- and content-changing callers (afterRegionChange,
        // language/settings refreshes), and the post-reset cycle is a
        // FIRST capture that re-seeds cropLeft/cropTop without the drift
        // comparison — so stale armed origins from the old crop space
        // would never be detected and could armed-hold unrelated text at
        // coincident coordinates (Codex adversarial review, 2026-07-23).
        // Arming preservation is only for dismiss() (the tap-advance
        // flow), whose coordinate space is unchanged.
        typewriterGate.clear()
        engine.scheduleNext()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    /**
     * Hide, forget everything, and look again no sooner than one capture
     * interval from now. Also the input path (see [start]): every touch and
     * every button press restarts the wait, so the screen stays unobstructed
     * for as long as the user keeps interacting and the overlays return one
     * interval after they stop.
     *
     * That interval is a floor on WHEN we look, never a wait for the screen
     * to change again. [resetState] arms the force flag, so the cycle passes
     * straight through the delivery gate instead of parking, and requestRaw
     * serves the latched frame — the last composition the mirror delivered,
     * which under MediaProjection may be seconds old, because a screen that
     * stops changing stops producing frames. The common case is exactly
     * that: our own overlay-hide is the last thing that composited, and the
     * frame it produced is the one we want to read.
     */
    override fun dismiss() {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        resetState()
        engine.scheduleNext(Prefs(service).captureIntervalMs)
    }

    /** Rotation: [dismiss]'s hide-and-forget, paced by
     *  [LiveMode.ROTATION_SETTLE_MS] instead of the user interval — the
     *  overlays (and the cleanRef/blend model, now void along with every
     *  crop-space bound) come down the moment the rotation is reported
     *  rather than when a post-rotation capture finally trips the reactive
     *  dims guard, and the rebuild look waits for the screen to settle. */
    override fun onDisplayRotated() {
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        resetState()
        // Rotation voids the coordinate space the gate's region memory is
        // keyed in — full clear, not just holds.
        typewriterGate.clear()
        engine.scheduleNext(LiveMode.ROTATION_SETTLE_MS)
    }

    private fun resetState() {
        engine.cancelCurrent()
        cachedBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        overlayBitmap?.recycle()
        overlayBitmap = null
        outsideGrid.reset()
        grayZoneStats.clear()
        grayZoneLastEmitMs = 0L
        // Holds only — region memory and ARMING survive. The input path
        // dismisses per message (tap → dismiss → next message types);
        // wiping arming here would disarm every region in exactly the
        // flow arming exists for. Every OTHER reset (refresh, rotation,
        // dim/crop changes, stop) additionally calls typewriterGate
        // .clear() — dismiss() is the ONLY caller that may keep memory,
        // because it is the only one whose coordinate space and content
        // provably haven't changed.
        typewriterGate.clearHolds()
        typewriterDeadlineMs = null
        quietProbe.clear()
        // The gate is only meaningful relative to a previous look at the
        // screen. After a reset the model is empty (overlays hidden, caches
        // dropped), so the next cycle must run even in delivery silence —
        // otherwise a dismiss on a static screen parks forever with nothing
        // shown. Forced again by every failed/aborted cycle (self-heal must
        // not depend on the screen changing) and every cycle that mutated
        // the overlay (exactly one follow-up look — which, on a static
        // screen, finds nothing, forces nothing, and lets the loop park).
        engine.forceNext()
    }

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

    // Removal hysteresis and the pinhole-side photometric fit were removed
    // 2026-07-08 (speed-first product rule): a false REMOVE costs one brief
    // blink with cached-translation recovery, while both mechanisms priced
    // their insurance in primary-path latency or, worse, missed removals.
    // The fit survives only on the outside gate (OutsideChangeGate), where
    // a false fire costs a single OCR.

    // The broad step-9b FAR suppression and the resurrection-deferral guard
    // were deleted 2026-07-09. Both were transition-layer compensators for
    // false removals whose actual source was upstream (adjacency-stale +
    // cascade on merged unboxed neighbors — see the campfire-menu
    // forensics): the suppression's 0.5W/1.5H inflation starved three
    // correct menu items near an unrelated dying box, and the deferral's
    // "one confirming look" was structurally impossible under the A2 gate.
    // A narrowly-scoped successor to 9b exists at runCycle step 9b
    // (2026-07-10): removal-triggered (pinhole-only at first;
    // stale/cascade removals added 2026-07-16 after the グラウス trace),
    // tight abutment, zero state — see abutsAnyInflated's kdoc for how it
    // differs from the deleted guard and why.

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
        if (service.livePaused) return 100L
        val mgr = liveSource()
        if (mgr == null) {
            // Backend unavailable (service unbinding / mid-swap). Retry
            // unconditionally — recovery must not wait for a delivery.
            engine.forceNext()
            return prefs.captureIntervalMs
        }
        if (CaptureBackendResolver.activeOverlayUi == null) {
            // Overlay host gone (accessibility service died; reresolve may lag
            // the OS settings flush). MediaProjection capture can still work in
            // that window, but there is nowhere to render — skip the cycle
            // instead of burning OCR + translation on output showLiveOverlay
            // will drop. Poll-retry until the host returns or reresolve stops
            // this mode.
            engine.forceNext()
            return prefs.captureIntervalMs
        }
        cycleNum++
        val debug = prefs.debugLiveMode

        // A pending force is consumed by reaching a real capture attempt.
        // Deliberately after the hold/mgr guards above: those returns must
        // not eat a force set by dismiss/refresh during a hold. The captured
        // value rides into the A2 gate below: a forced wake is a forced FULL
        // look, not merely a scheduler wake. Without the bypass, the one
        // follow-up look after a mutation (step 14) gets vetoed by the pixel
        // gate whenever the screen is static — a box-set change carries zero
        // outside-pixel evidence — and text uncovered by a removal waits for
        // the reconcile (observed: 26s, 2026-07-08 campfire-menu forensics).
        val forcedLook = engine.consumeForce()

        // Capture. Boxes that pinhole detection flags as changed are
        // removed and re-OCR'd on the next cycle; there is no longer a
        // dirty-companion buffer (see docs/dirty-overlay-archived-design.md).
        // The frame's stamped facts ride into step 6's runOcr; the pinhole
        // pipeline itself works on the unwrapped bitmap (this mode never
        // runs on CLEAN streams (routing), so frames are always
        // full-display).
        val frame = service.withFirstGrabCardBlink(displayId, mgr) {
            mgr.requestRaw(displayId)
        }

        if (frame == null) {
            // Transient capture failure — a persistently failing capture must
            // keep retrying every interval, not park silently until the
            // screen happens to change.
            engine.forceNext()
            return prefs.captureIntervalMs
        }
        val raw = frame.bitmap

        try {
            // Mid-cycle dimension changes (rotation, display resize) invalidate
            // cleanRef and the cached state. Mirrors FuriganaMode.handleRawFrame's
            // mid-cycle recovery. Clear state inline — do NOT call resetState()
            // from here, because resetState cancels the engine's current job
            // (which IS the currently-running job). Self-cancellation works via
            // cooperative cancellation but is subtle; inline clearing is clearer.
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
                typewriterGate.clear()
                typewriterDeadlineMs = null
                CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                // State cleared + overlay hidden: the rebuild cycle must run
                // even if the post-rotation screen goes immediately static.
                engine.forceNext()
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
                    engine.forceNext()
                    return mgr.minCaptureIntervalMs
                }
                // Preserve the retry-every-interval*3 semantics under the
                // gate — parking here would hide the failure.
                engine.forceNext()
                return prefs.captureIntervalMs * 3
            }

            val bitmapRects = coords.viewListToBitmap(rects)

            // The floating icon is inside every raw frame this mode consumes
            // (whole-display mirrors / a11y screenshots — never CLEAN
            // streams). Its window rect is excluded from the outside gate
            // below: the burn-in micro-orbit and idle dim repaint it, and
            // self-chrome motion must never wake OCR. (The OCR-input
            // blackout itself is central — runOcr keys it on the
            // frameIncludesOwnOverlays fact passed at step 6.) Screen
            // coords index the frame directly: this mode runs at identity
            // scale only (guard above).
            val iconRect =
                CaptureBackendResolver.activeOverlayUi?.getFloatingIconRect(displayId)

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
            //
            // A forced wake bypasses the skip outright. Invariant: a skip
            // streak may only BEGIN after a clean full look (one that found
            // nothing to do). Mutating cycles force their follow-up look
            // (step 14), and that look must reach OCR — the gate senses
            // pixel change, not box-set divergence, so it is structurally
            // blind to "text present but no longer covered".
            var pinholePre: Array<PinholeOutcome>? = null
            var reconcileCycle = false
            // Any open typewriter hold must reach OCR: held-window reads ARE
            // the product (growth tracking + the releasing read), and the
            // releasing read's whole point is that the screen may have gone
            // quiet — exactly what the pixel skip keys on.
            val typewriterActive = typewriterDeadlineMs != null
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
                    // Sanctioned manual crop: pinhole never runs on CLEAN
                    // streams (routing), so its frames always have the bar.
                    service.getStatusBarHeightForDisplay(displayId),
                )
                val inflate = PinholeCalibration.GATE_EXCLUDE_INFLATE_PX
                val exclude = bitmapRects.mapIndexed { i, r ->
                    // Footprint-shaped exclusion: a rotated chip excludes only
                    // its oriented footprint, leaving the AABB corners sampled
                    // — checkPinholes skips them (no overlay drawn there), so
                    // the gate is their only change watcher. Sizes are equal
                    // (guard above), so the zip with gateBoxes is index-safe.
                    val b = gateBoxes[i]
                    OutsideChangeGate.Exclusion(
                        Rect(r).apply { inset(-inflate, -inflate) },
                        angleDeg = b.angleDeg,
                        orientedW = b.orientedWidth + 2 * inflate,
                        orientedH = b.orientedHeight + 2 * inflate,
                    )
                } + listOfNotNull(iconRect?.let { OutsideChangeGate.Exclusion(it) })
                val outside =
                    OutsideChangeGate.check(raw, gateRef, crop, exclude, gateBuffers, outsideGrid)
                reconcileCycle =
                    gateSkipStreak >= PinholeCalibration.GATE_RECONCILE_EVERY_SKIPS
                // Volatile tiles disable skipping outright (product rule:
                // text inside endless animation must never wait past shipped
                // cadence — see OutsideBlockGrid). Such screens run the full
                // cycle every wake, exactly like the shipped app.
                if (!forcedLook && !typewriterActive && allKeep && !outside.fired &&
                    !reconcileCycle && outside.volatileBlocks == 0
                ) {
                    // A moving-but-differing block must get a floor-paced
                    // follow-up look even if the screen has gone silent.
                    if (outside.pendingSettle) engine.forceNext()
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
                    // Skipped cycles pace at the floor while any block is
                    // mid-settle — K=2 settle discipline at floor pacing
                    // costs ~0.5s; at interval pacing it tripled reaction
                    // time in the field (2026-07-08 regression). Game input
                    // no longer paces here at all: it dismisses and waits an
                    // interval instead (see [start]).
                    return typewriterPacing(
                        if (outside.pendingSettle) mgr.minCaptureIntervalMs
                        else prefs.captureIntervalMs,
                        mgr.minCaptureIntervalMs,
                    )
                }
                pinholePre = outcomes
                if (debug) {
                    val why = when {
                        reconcileCycle -> "reconcile after $gateSkipStreak skips"
                        !allKeep ->
                            "pinhole ${outcomes.count { it.result != PinholeResult.KEEP }} box(es)"
                        outside.volatileBlocks > 0 ->
                            "volatile ${outside.volatileBlocks} block(s) — full cadence"
                        forcedLook -> "forced look"
                        typewriterActive -> "typewriter hold open"
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

            // 5. Prepare OCR image: fill overlay regions with bgColor.
            //    Only this copy is mutated; the gate, the pinhole checks,
            //    and cleanRef all keep reading the honest raw. (The floating
            //    icon is blacked out inside runOcr, step 6.)
            val ocrImage: Bitmap
            if (hasOverlays()) {
                ocrImage = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
                fillOverlayRegions(ocrImage, bitmapRects)
            } else {
                ocrImage = raw
            }

            // 6. OCR — try/finally ensures the copy is recycled even if runOcr
            //          throws (e.g. CancellationException from resetState).
            //    The frame's stamped fact (raw grab of a contaminated
            //    source ⇒ true) drives the floating-icon blackout inside.
            val pipeline = try {
                service.runOcr(
                    ocrImage, displayId,
                    frameIncludesOwnOverlays = frame.includesOwnOverlays,
                )
            } finally {
                if (ocrImage !== raw && !ocrImage.isRecycled) ocrImage.recycle()
            }
            if (pipeline != null) {
                panelProvenance = service.panelOcrProvenance(
                    pipeline.ocrResult, displayId,
                    frame.includesSystemUi, frame.includesOwnOverlays,
                )
            }

            // A hold (or the rescue alert) may have started during the OCR
            // suspension. Bail now to avoid wasting CPU on classification/
            // translation the blocked showLiveOverlay will never render.
            if (service.livePaused) return 100L

            // No text on screen and no overlays → nothing to do. This is
            // still a FULL LOOK, so the gate's once-per-full-look contract
            // applies: sweep holds on the evidence of a read that found
            // nothing — otherwise a stale expired deadline bypasses the A2
            // skip and pins pacing at the floor forever on a textless
            // screen.
            if (pipeline == null && !hasOverlays()) {
                typewriterDeadlineMs = typewriterGate.sweepEmptyBatch(SystemClock.uptimeMillis())
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
                    typewriterGate.clear()
                    typewriterDeadlineMs = null
                    CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                    engine.forceNext()
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
                    if (outcome.result == PinholeResult.REMOVE) {
                        pinholeRemovals.add(idx)
                    }
                    if (GRAYZONE_COUNTER && debug) recordGrayZone(box, outcome.pct)
                    if (debug && outcome.result != PinholeResult.KEEP) {
                        val r = bitmapRects[idx]
                        val pctStr = "%.1f".format(outcome.pct * 100f)
                        DetectionLog.log(
                            "D$displayId c$cycleNum box$idx ${outcome.result} " +
                                "text=\"${box.sourceText.take(20)}\" " +
                                "pct=$pctStr% changed=${outcome.changed}/${outcome.total} " +
                                "glyphAnchors=${outcome.glyphAnchorsHit} " +
                                "maxDelta=${outcome.maxDelta} " +
                                "rect=(${r.left},${r.top},${r.right},${r.bottom})"
                        )
                    }
                }
            }

            if (GRAYZONE_COUNTER && debug) maybeEmitGrayZone()

            // 8b. Cascade stale to neighbors. See cascadeStaleRemovals in Classification.kt.
            //     Same coordinate-space reasoning as the proximity check
            //     above: cascade uses unpadded ocrBitmapRects so it agrees
            //     with classification's notion of "neighbor".
            val cascadedRemovals = cascadeStaleRemovals(staleOverlayIndices, boxes, ocrBitmapRects, sourceIsRtl)

            // 9. Resolve: compute final state from immutable snapshot in one pass
            val allRemovals = cascadedRemovals + pinholeRemovals + contentMatchRemovals

            // 9b. Defer FAR fragments abutting a box dying this cycle (see
            //     abutsAnyInflated's kdoc for the full rationale). The group
            //     was OCR'd while the dying box still blinded the region it
            //     borders — it may be only the tail of the text the removal
            //     uncovers. The removal already forces a floor-paced
            //     follow-up look (step 14 + the forced-look gate bypass),
            //     which sees the whole uncovered region and places it
            //     complete. Dying = pinhole REMOVEs (incl. boxes that also
            //     content-matched — taxi-prompt trace 2026-07-10) PLUS
            //     stale/cascade removals (2026-07-16 — the グラウス trace's
            //     partial typewriter box died adjacency-stale and its
            //     freshly-revealed third row placed as a stranded solo box).
            //     Content-match-ONLY removals stay out: scrolling text
            //     content-matches every cycle, and deferring fresh text
            //     entering beside those would starve it for the whole
            //     scroll. The content-match placement promise is carried by
            //     the paired FARs themselves (FarGroup.paired), which the
            //     filter never drops — so an unrelated dying neighbor can't
            //     defer them either (the conversation-close prompt gap).
            val cc = classifyCoords
            val dyingRects =
                (pinholeRemovals + cascadedRemovals).mapNotNull { bitmapRects.getOrNull(it) }
            if (cc != null && dyingRects.isNotEmpty() && farOcrGroups.isNotEmpty()) {
                val before = farOcrGroups.size
                farOcrGroups = deferDyingBoxFragments(
                    farOcrGroups, dyingRects, cc, PinholeCalibration.FRAGMENT_DEFER_ABUT_PX,
                )
                if (debug && before != farOcrGroups.size) {
                    DetectionLog.log(
                        "D$displayId c$cycleNum deferred ${before - farOcrGroups.size} FAR " +
                            "fragment(s) abutting ${dyingRects.size} dying box(es)"
                    )
                }
            }

            val nextBoxes = boxes.mapIndexedNotNull { i, box ->
                if (i in allRemovals) null else box
            }

            cachedBoxes = nextBoxes.ifEmpty { null }
            val anyChanged = allRemovals.isNotEmpty()

            // 9c. Sentence-gated typewriter dispatch ([TypewriterGate]).
            //     On this tier a changed box dies (step 8) BEFORE its fuller
            //     text re-arrives as an unpaired far group, so the gate's
            //     region memory carries the old-text reference across that
            //     gap. Held groups place nothing this cycle — the game's own
            //     reveal stays visible — and release on a boundary-final
            //     read, two agreeing reads, or the cap (whose deadline wakes
            //     a parked loop; see [typewriterDeadlineMs]). Runs on every
            //     full-look cycle, even with zero far groups: a read that no
            //     longer shows a held region is the evidence that sweeps its
            //     hold. `paired` groups bypass the gate inside (placement
            //     promise).
            val gateNowMs = SystemClock.uptimeMillis()
            typewriterGate.debugSink =
                if (!debug) null else ({ s -> DetectionLog.log("D$displayId c$cycleNum tw $s") })
            val farOutcome = typewriterGate.filterFarGroups(
                farOcrGroups,
                SourceLanguageProfiles[prefs.sourceLangId].translationCode,
                sourceIsRtl = sourceIsRtl,
                captureAtMs = frame.capturedAtMs, nowMs = gateNowMs,
            )
            typewriterDeadlineMs = farOutcome.nextDeadlineMs
            typewriterGate.touchRegions(nextBoxes.map { it.bounds }, gateNowMs)
            // Passive quiet-pixel telemetry for the parked accelerator —
            // debug only, no behavior. NOTE: far-group/hold rects are
            // OCR-crop coords; offset into frame space for sampling.
            if (debug) {
                quietProbe.sample(
                    raw,
                    typewriterGate.quietProbeSnapshot().map {
                        it.copy(paddedBounds = Rect(it.paddedBounds).apply { offset(cropLeft, cropTop) })
                    },
                    gateNowMs,
                )
            }
            if (cycleNum % 120 == 0) {
                Log.i("PinholeOverlayMode", "typewriter stats c$cycleNum: ${typewriterGate.stats.summary()}")
            }
            val placeGroups = farOutcome.dispatch
            if (debug && farOutcome.held > 0) {
                DetectionLog.log(
                    "D$displayId c$cycleNum typewriter: held=${farOutcome.held} " +
                        "deadlineIn=${farOutcome.nextDeadlineMs?.let { it - gateNowMs } ?: -1}ms"
                )
            }

            if (debug && (anyChanged || farOcrGroups.isNotEmpty())) {
                DetectionLog.log(
                    "D$displayId c$cycleNum transitions: " +
                        "removed=(pinhole=${pinholeRemovals.toSortedSet()}, " +
                        "contentMatch=${contentMatchRemovals.toSortedSet()}, " +
                        "cascade=${cascadedRemovals.toSortedSet()}, " +
                        "stale=${staleOverlayIndices.toSortedSet()}) " +
                        "far=${placeGroups.size}(+${farOutcome.held} held) " +
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
                } else if (placeGroups.isEmpty()) {
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
                // else: placeGroups is non-empty — the path below will call
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
            if (cleanRefBitmap == null && (placeGroups.isNotEmpty() || nextBoxes.isNotEmpty())) {
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // 12. Show new text (with skeletons for uncached, instant for cached)
            if (placeGroups.isNotEmpty()) {
                // Commit-stream trace (translation-log validation): the
                // post-gate groups are this mode's analog of the
                // reconciler's post-hold toTranslate — the new/changed text
                // actually entering the overlay.
                logTrace?.onCommitGroups(cycleNum, System.currentTimeMillis(), placeGroups)
                val farTexts = placeGroups.map { it.text }
                val farBounds = placeGroups.map { it.bounds }
                val farLineCounts = placeGroups.map { it.lineCount }
                val farOrientations = placeGroups.map { it.orientation }
                val farAlignments = placeGroups.map { it.alignment }
                val farSlants = placeGroups.map { Triple(it.angleDeg, it.orientedWidth, it.orientedHeight) }
                val placeholders = buildPlaceholderBoxes(
                    farTexts, farBounds, farLineCounts, raw, cropLeft, cropTop,
                    farOrientations, farAlignments, farSlants,
                )

                if (placeholders.isNotEmpty()) {
                    val partial = placeholders.mapIndexed { i, ph ->
                        val cached = service.getCachedTranslation(farTexts[i])
                        if (cached != null) {
                            ph.copy(
                                translatedText = cached.text,
                                backendDisplayName = cached.backendDisplayName,
                            )
                        } else ph
                    }
                    val anyUncached = partial.any { it.translatedText.isEmpty() }

                    val merged = (cachedBoxes ?: emptyList()) + partial
                    cachedBoxes = merged
                    showOverlayAndCapture(merged, cropLeft, cropTop, screenshotW, screenshotH)

                    // Recording backend: cache-hits are shown right here and
                    // never reach translatePlaceholders — record them now.
                    // Pair captured pre-translate for the fresh tap below.
                    val (recordSrc, recordTgt) = recordPair()
                    recordShown(placeGroups, recordSrc, recordTgt) { i -> partial[i] }

                    if (anyUncached) {
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val existing = cachedBoxes?.dropLast(placeholders.size) ?: emptyList()
                        val mergedFinal = existing + translated
                        cachedBoxes = mergedFinal
                        showOverlayAndCapture(mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
                        // Freshly translated boxes only — the cache-hits above
                        // already recorded (gate dedupe would absorb a double,
                        // but don't lean on it).
                        recordShown(placeGroups, recordSrc, recordTgt) { i ->
                            if (partial[i].translatedText.isEmpty()) translated[i] else null
                        }
                    }

                }
            }

            // 13. Keep the panel in sync with cachedBoxes — fire on placed
            //     groups OR removals so removal-only cycles don't go
            //     stale, AND on swept holds: a prior cycle's held text
            //     suppressed the no-text signal ("release imminent"); a
            //     sweep means that text vanished without dispatching, and
            //     the suppression must resolve (Codex review, 2026-07-23).
            if (placeGroups.isNotEmpty() || allRemovals.isNotEmpty() || farOutcome.swept > 0) {
                if (cachedBoxes.isNullOrEmpty()) {
                    // Held typewriter text is still text-on-screen: a no-text
                    // signal mid-reveal would blank the panel the imminent
                    // release is about to fill.
                    if (farOutcome.held == 0) service.handleNoTextDetected(displayId)
                } else {
                    // Lazy path: the JPEG save only runs when the panel is
                    // actually visible (inside sendFullStateToPanel's gate).
                    sendFullStateToPanel { mgr.saveToCache(raw, displayId) }
                }
            }

            // 14. Timing. A cycle that mutated the overlay (removals applied
            //     or new text placed) forces exactly one follow-up look — the
            //     deterministic replacement for relying on our own repaint
            //     echoing back through the mirror as a delivery. On a static
            //     screen the follow-up finds nothing, forces nothing, and the
            //     loop parks. Held groups deliberately do NOT force: while
            //     the reveal animates, deliveries wake the loop anyway; once
            //     it stops, the cap deadline (park bound + pacing clamp)
            //     provides the releasing read.
            if (anyChanged || placeGroups.isNotEmpty()) {
                engine.forceNext()
                // The overlay layout changed: block membership under the
                // exclusion rects shifted and the reference re-baselined, so
                // the outside grid's temporal state is stale.
                outsideGrid.reset()
            }
            if (reconcileCycle && (anyChanged || farOcrGroups.isNotEmpty())) {
                // The A2 gate's false-negative metric: the safety-net cycle
                // found work the gate had been skipping past (pre-typewriter-
                // gate counts — this is a detection metric, not dispatch).
                // Loud on purpose — a nonzero rate here means the
                // grid/thresholds miss real changes and the net must stay.
                DetectionLog.log(
                    "D$displayId c$cycleNum gate MISS: reconcile found " +
                        "removed=${allRemovals.size} far=${farOcrGroups.size}"
                )
            }
            return typewriterPacing(
                if (anyRemoved) mgr.minCaptureIntervalMs else prefs.captureIntervalMs,
                mgr.minCaptureIntervalMs,
            )
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /** Next-cycle delay: [baseMs] normally, the FLOOR while any typewriter
     *  hold is open — the pinhole analog of [ReconcilerLiveMode.pacing];
     *  see its kdoc for why floor pacing during holds is safe (serial
     *  cycle chain, hold-scoped, park still deadline-bounded). */
    private fun typewriterPacing(baseMs: Long, floorMs: Long): Long {
        if (typewriterDeadlineMs != null) return floorMs
        return baseMs
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
    // The R2 composite-OCR probe was removed 2026-07-09 after answering its
    // question (commit 34cca8e6 has the implementation): composite input
    // reliably re-reads frozen under-box content (192/192 cycles on the
    // stuck-menu scene) but does NOT reproduce real-scene segmentation —
    // isolated cleanRef patches in a textless live scene merge into one
    // group. Composite-as-production-OCR-input is falsified as designed;
    // any existence check against the frozen ref is also circular for
    // live-change detection (it verifies the box against its own snapshot).

    /** Gray-zone trigger counter — see [GRAYZONE_COUNTER]. One entry per
     *  live box, keyed by TextBox identity (instances persist across
     *  cycles in cachedBoxes). Histogram bucket bounds in changed-fraction
     *  percent: [0,0.5) [0.5,1) [1,2) [2,3.5) [3.5,5). REMOVE outcomes
     *  (≥5%) are already logged by the step-8 path. */
    private class GrayZoneStats {
        val buckets = IntArray(5)
        var cycles = 0
        var maxPct = 0f
        var inZone = false
        var lastEnterLogMs = 0L
    }

    private val grayZoneStats = java.util.IdentityHashMap<TextBox, GrayZoneStats>()
    private var grayZoneLastEmitMs = 0L

    private fun recordGrayZone(box: TextBox, pct: Float) {
        val s = grayZoneStats.getOrPut(box) { GrayZoneStats() }
        s.cycles++
        if (pct > s.maxPct) s.maxPct = pct
        val bucket = when {
            pct < 0.005f -> 0
            pct < 0.01f -> 1
            pct < 0.02f -> 2
            pct < 0.035f -> 3
            pct < PinholeCalibration.PINHOLE_CHANGE_PCT -> 4
            else -> -1
        }
        if (bucket >= 0) s.buckets[bucket]++
        val nowInZone = pct >= GRAYZONE_MIN_PCT &&
            pct < PinholeCalibration.PINHOLE_CHANGE_PCT
        val now = android.os.SystemClock.uptimeMillis()
        if (nowInZone && !s.inZone && now - s.lastEnterLogMs > 5_000) {
            s.lastEnterLogMs = now
            DetectionLog.log(
                "D$displayId c$cycleNum grayzone ENTER " +
                    "\"${box.sourceText.take(12)}\" pct=${"%.1f".format(pct * 100)}%"
            )
        }
        s.inZone = nowInZone
    }

    /** Emit the per-box histograms every ~20s of accumulated full cycles,
     *  then reset the window. Boxes removed mid-window get their final
     *  counts logged once here. */
    private fun maybeEmitGrayZone() {
        if (grayZoneStats.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (grayZoneLastEmitMs == 0L) {
            grayZoneLastEmitMs = now
            return
        }
        if (now - grayZoneLastEmitMs < 20_000) return
        val secs = (now - grayZoneLastEmitMs) / 1000
        val parts = grayZoneStats.entries.joinToString(" | ") { (box, s) ->
            "\"${box.sourceText.take(10)}\" n=${s.cycles} " +
                "h=${s.buckets.joinToString(",")} " +
                "max=${"%.1f".format(s.maxPct * 100)}%"
        }
        DetectionLog.log("D$displayId c$cycleNum grayzone[${secs}s]: $parts")
        grayZoneStats.clear()
        grayZoneLastEmitMs = now
    }

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
            paint.color = box.bgColor or 0xFF000000.toInt()
            if (box.angleDeg != 0f && box.orientedWidth > 0f && box.orientedHeight > 0f) {
                // Rotated chip: fill its true footprint — the oriented rect
                // about the rendered AABB's center — never the AABB, whose
                // corner triangles are live game pixels the user still sees;
                // painting those would blind OCR to real on-screen text
                // (adversarial-review finding). The canvas clips to the bitmap.
                val cx = (rect.left + rect.right) / 2f
                val cy = (rect.top + rect.bottom) / 2f
                val hw = box.orientedWidth / 2f + aaBuffer
                val hh = box.orientedHeight / 2f + aaBuffer
                canvas.save()
                canvas.rotate(box.angleDeg, cx, cy)
                canvas.drawRect(cx - hw, cy - hh, cx + hw, cy + hh, paint)
                canvas.restore()
            } else {
                val l = (rect.left - aaBuffer).coerceAtLeast(0)
                val t = (rect.top - aaBuffer).coerceAtLeast(0)
                val r = (rect.right + aaBuffer).coerceAtMost(bitmap.width)
                val b = (rect.bottom + aaBuffer).coerceAtMost(bitmap.height)
                canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
            }
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
     *     delta[i]     = |raw[i] - predicted[i]|
     *
     * with a pinhole counted as changed when any channel's delta exceeds
     * [PinholeCalibration.SPLATTER_THRESHOLD]. Deliberately RAW deltas, no
     * photometric normalization (2026-07-08 decision): normalizing here
     * trades missed removals (stale overlays — the cardinal failure) for
     * dim-ramp smoothness; a dim now flaps boxes once and self-heals, and
     * the fit lives only on the outside gate where false fires are cheap.
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

        // RAW per-channel deltas at the shipped calibration. The A3
        // photometric fit was removed from THIS path (2026-07-08): its
        // failure mode here is missed removals — stale overlays, the
        // cardinal regression — and it survives only on the outside gate,
        // where a false fire costs a single OCR. A screen dim therefore
        // flaps boxes once (as shipped); the user is about to tap anyway.
        //
        // Glyph anchors (audit A7) are TELEMETRY ONLY: they are laid
        // geometrically along approximated line rows, not on detected ink,
        // so on translucent boxes they can sit over background animation.
        // Their hit count is logged to size the idea against real content;
        // it does NOT influence the REMOVE decision until placement is
        // ink-aware.
        //
        // Mask geometry note (unchanged): the mask is generated at
        // view-global origin, so the grid is tested at (left+px, top+py) —
        // at identity scale view-space == bitmap-space. Sampling box-local
        // would miss the actual on-screen holes for any box whose top-left
        // isn't grid-aligned.
        val vertical =
            box.orientation == com.playtranslate.language.TextOrientation.VERTICAL
        val anchors = GlyphAnchors.forBox(bitmapRect, box.lineCount, vertical)
        var anchorHits = 0L

        var totalPinholes = 0
        var changedPinholes = 0
        var maxDelta = 0
        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                val i = py * regionW + px
                val ovPx = ovPixels[i]
                // A pixel the overlay never drew — a rotated chip's AABB
                // corner, or its AA edge — has no 50/50 blend to predict:
                // raw there is pure game content, and predicted would be
                // cleanRef/2, flagging static screens as changed every cycle.
                // Skip it; those samples belong to the outside gate, whose
                // exclusion is footprint-shaped for rotated boxes. Upright
                // chips fill their whole rect opaque, so this never skips
                // for them.
                if (Color.alpha(ovPx) != 255) continue
                totalPinholes++
                val refPx = refPixels[i]
                val rawPx = rawPixels[i]
                // predicted = clean_ref * 0.5 + overlay_rendered * 0.5
                val predR = (Color.red(refPx) + Color.red(ovPx)) / 2
                val predG = (Color.green(refPx) + Color.green(ovPx)) / 2
                val predB = (Color.blue(refPx) + Color.blue(ovPx)) / 2
                val dr = kotlin.math.abs(Color.red(rawPx) - predR)
                val dg = kotlin.math.abs(Color.green(rawPx) - predG)
                val db = kotlin.math.abs(Color.blue(rawPx) - predB)
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
        if (totalPinholes == 0) return keepZero

        val pct = changedPinholes.toFloat() / totalPinholes
        val result = if (pct >= PinholeCalibration.PINHOLE_CHANGE_PCT) {
            PinholeResult.REMOVE
        } else {
            PinholeResult.KEEP
        }
        return PinholeOutcome(
            result, pct, changedPinholes, totalPinholes, maxDelta,
            glyphAnchorsHit = java.lang.Long.bitCount(anchorHits),
        )
    }

    /**
     * Update clean ref in-place: copy non-overlay pixels from raw into the
     * existing cleanRef. Overlay-COVERED pixels stay frozen at their initial
     * pre-overlay game content (pinhole detection relies on that invariant),
     * while everything else — including a rotated chip's undrawn AABB
     * corners — is refreshed from raw.
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

        // Restore overlay regions from saved pixels. A rotated chip freezes
        // only the pixels it actually draws (overlay alpha 255 — the same
        // criterion checkPinholes samples by): its AABB corners are live game
        // content the outside gate now watches, and a frozen-stale corner
        // would make that gate false-fire forever. Upright boxes keep the
        // whole-rect restore, byte-identical to before.
        val boxes = cachedBoxes
        val overlay = overlayBitmap
        val boxesAligned = boxes != null && boxes.size == bitmapRects.size &&
            overlay != null && overlay.width == w && overlay.height == h
        for ((i, rect) in bitmapRects.withIndex()) {
            val pixels = savedRegions[i] ?: continue
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) continue
            if (boxesAligned && boxes!![i].angleDeg != 0f) {
                val ov = IntArray(regionW * regionH)
                overlay!!.getPixels(ov, 0, regionW, left, top, regionW, regionH)
                val merged = IntArray(regionW * regionH)
                ref.getPixels(merged, 0, regionW, left, top, regionW, regionH)  // == fresh raw here
                for (p in merged.indices) {
                    if (Color.alpha(ov[p]) == 255) merged[p] = pixels[p]
                }
                ref.setPixels(merged, 0, regionW, left, top, regionW, regionH)
            } else {
                ref.setPixels(pixels, 0, regionW, left, top, regionW, regionH)
            }
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
     * Send ALL current cachedBoxes to the in-app panel. No re-OCR is needed —
     * every cached box already carries its sourceText + translatedText.
     * [screenshotPath] is invoked only past the visibility gate — it is a
     * synchronous JPEG write of the frame, wasted whenever the panel is
     * hidden (single-screen mode, the default). Boxes go out in cachedBoxes
     * order, as this tier always has (no reading-order sort — deliberate
     * byte-parity with the shipped behavior).
     */
    private fun sendFullStateToPanel(screenshotPath: () -> String?) {
        val boxes = cachedBoxes ?: return
        if (!service.appPanelVisible()) return
        service.emitPanelResult(
            OverlayToolkit.panelTexts(boxes), screenshotPath(),
            ocrProvenance = panelProvenance,
            backendDisplayName = OverlayToolkit.panelBackendLabel(boxes),
        )
    }

    // ── Translation Helpers ─────────────────────────────────────────────

    /** Build placeholder TextBoxes with empty text (skeleton indicators). Instant, no network. */
    private fun buildPlaceholderBoxes(
        texts: List<String>, bounds: List<Rect>, lineCounts: List<Int>,
        raw: Bitmap, left: Int, top: Int,
        orientations: List<com.playtranslate.language.TextOrientation> = emptyList(),
        alignments: List<com.playtranslate.language.TextAlignment> = emptyList(),
        /** Per-box (angleDeg, orientedWidth, orientedHeight); zeros when upright. */
        slants: List<Triple<Float, Float, Float>> = emptyList(),
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
            val (ang, ow, oh) = slants.getOrElse(idx) { Triple(0f, 0f, 0f) }
            TextBox("", rect, bg, tc, lineCounts.getOrElse(idx) { 1 },
                sourceText = texts.getOrElse(idx) { "" }, orientation = orient, alignment = align,
                angleDeg = ang, orientedWidth = ow, orientedHeight = oh)
        }
    }

    /** Translate texts and return placeholders with filled translatedText
     *  (and the producing backend, for attribution downstream). */
    private suspend fun translatePlaceholders(
        placeholders: List<TextBox>, texts: List<String>
    ): List<TextBox> {
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()
        val translations = arrayOfNulls<CaptureService.GroupTranslation>(texts.size)

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
                translations[idx] = results.getOrNull(i)
            }
        }

        return placeholders.mapIndexed { idx, ph ->
            val t = translations.getOrNull(idx)
            ph.copy(
                translatedText = t?.text ?: "",
                backendDisplayName = t?.backendDisplayName,
            )
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

}
