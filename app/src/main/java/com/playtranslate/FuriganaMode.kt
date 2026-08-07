package com.playtranslate

import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.LiveCaptureSource

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "FuriganaMode"

/** After this many consecutive empty-screenRects frames with boxes cached,
 *  assume showLiveOverlay was blocked and force state recovery. Each frame
 *  is ~500ms (capture interval), so 3 frames = ~1.5s grace period. */
private const val STALL_RECOVERY_FRAMES = 3

/** Safety net: after this many consecutive raw frames without a detected change,
 *  force a clean capture to refresh the ref. Prevents stale ref content from
 *  silently masking real scene changes via overlay-region bleed-through.
 *  At ~500ms per frame, 20 frames ≈ 10 seconds max stall before self-heal. */
private const val STALE_REF_REFRESH_FRAMES = 20

/** Fast-poll window pushed to the capture loop while a typewriter reveal is
 *  active or a frontier hold awaits its confirming frame — long enough to
 *  span OCR + annotation plus a couple of floor-interval polls; every
 *  evolving frame refreshes it ([LiveCaptureSource.pokeFastPoll]). */
private const val FAST_POLL_WINDOW_MS = 3_000L

/**
 * Should this frame's text be OFFERED to the panel? Stateless — computed
 * from the same (previous, current) pair the mode already classifies frames
 * with; delivery-side dedup ([LivePanelRecord]) makes repeated offers of
 * settled text idempotent, so this deliberately says yes on EVERY settled
 * frame rather than tracking a transition edge.
 *
 *  - Growth is a reveal prefix — never offered, EXCEPT suffix-growth
 *    landing exactly on a sentence terminal ([SentenceBoundary], the
 *    [TypewriterGate]'s zero-latency boundary release): that frame IS the
 *    completed message. Suffix-growth is load-bearing — the joined frame
 *    text can end with a STATIC punct-final box while another box is still
 *    typing; interior growth fails the prefix check and falls back to the
 *    settled-repeat path, slower but never wrong.
 *  - Non-growth offers unless the change is significant — a content swap
 *    holds one cycle (the next settled frame offers it), while same-length
 *    OCR jitter on a settled screen still flows (the record absorbs it).
 */
internal fun panelOfferEligible(prevText: String?, frameText: String, langCode: String): Boolean {
    if (prevText == null) return false
    if (frameText.length > prevText.length) {
        return OverlayToolkit.isEvolvingText(prevText, frameText) &&
            SentenceBoundary.endsAtBoundary(frameText, langCode)
    }
    return !OverlayToolkit.isSignificantChange(prevText, frameText)
}

/**
 * Live furigana overlay mode — the ONE furigana tier, every backend and
 * stream kind (2026-08-06 consolidation; see [CaptureService.desiredModeClass]).
 * Shows hiragana readings above kanji on the game screen.
 *
 * Behavior keys off each frame's stamped facts ([CapturedFrame]):
 * contaminated frames (a11y screenshots, whole-display mirrors — our
 * overlays composite in) run OCR-based change detection against a clean
 * reference bitmap, patching overlay regions before OCR; frames a CLEAN
 * task mirror stamps overlay-free skip all of that and process directly —
 * patching them would overwrite real pixels with stale ref strips.
 *
 * Owns ALL its mutable state. When stopped, scope is cancelled and all
 * state (including cleanRefBitmap) is released.
 */
/**
 * @param service the enclosing capture service (for state access and coordinator calls)
 */
class FuriganaMode(
    private val service: CaptureService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.FURIGANA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanProcessingJob: Job? = null
    private var rawOcrJob: Job? = null
    private var restartJob: Job? = null
    private var startLoopJob: Job? = null

    // ── Mode-owned state ──────────────────────────────────────────────────

    private var furiganaGroups: List<OverlayToolkit.FuriganaGroup> = emptyList()
    private var cachedFuriganaBoxes: List<TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null

    private var lastOcrText: String? = null

    /** Texts of groups whose frontier ruby was WITHHELD last cycle
     *  ([com.playtranslate.language.withFrontierHeld]). Non-empty means the
     *  display is deliberately incomplete, so the dedup-skip fast path must
     *  not re-show it: the first settled frame after a reveal has to fall
     *  through to the rebuild, which re-annotates exactly these groups
     *  without the hold and releases the final word's reading. Without this,
     *  the cached held boxes replay forever and the last furigana of every
     *  typewriter sentence never appears (device-observed). */
    private var heldFrontierTexts: Set<String> = emptySet()
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    /** Consecutive frames where boxes were cached but overlay screen rects were empty.
     *  Used to detect a stuck state where [showLiveOverlay] was blocked (holdActive,
     *  a11y not ready, etc.) so cachedFuriganaBoxes is set but no view is attached. */
    private var emptyRectsStallCount = 0

    /** Consecutive raw frames processed without detecting a change. Used as a safety
     *  net to periodically refresh the clean ref so stale ref content can't silently
     *  mask real scene changes (especially via bleed-through in overlay regions). */
    private var noChangeRawFrameCount = 0

    /** Receipt-time pacing poke. Frame callbacks run SYNCHRONOUSLY inside
     *  the capture loop's iteration, before it computes the next inter-frame
     *  wait — so a poke here is always visible to that computation. The
     *  poke from [processPipeline] alone is not enough: it fires from the
     *  async OCR job, landing mid-park, where it wouldn't be read until the
     *  following iteration (and with user intervals above the window it
     *  would expire unread — 2026-08-06 review). [heldFrontierTexts] is the
     *  "reveal in flight / release pending" signal the previous cycle
     *  already computed; the async poke still covers the reveal's first
     *  frames before any hold exists. */
    private fun pokeIfRevealInFlight() {
        if (heldFrontierTexts.isNotEmpty()) {
            CaptureBackendResolver.activeLiveCaptureSource
                ?.pokeFastPoll(displayId, FAST_POLL_WINDOW_MS)
        }
    }

    /** Reset all mode-owned state. Does NOT hide overlays or notify UI. */
    private fun clearState() {
        furiganaGroups = emptyList()
        cachedFuriganaBoxes = null
        lastOcrText = null
        heldFrontierTexts = emptySet()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        emptyRectsStallCount = 0
        noChangeRawFrameCount = 0
    }

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        val source = CaptureBackendResolver.activeLiveCaptureSource
        if (source == null) {
            DetectionLog.log("ERROR: no live capture source, can't start furigana loop")
            return
        }
        CaptureBackendResolver.active().startInputMonitoring(displayId) { dismiss() }
        DetectionLog.log("Starting furigana loop on display $displayId")
        startLoop(source)
    }

    override fun stop() {
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        restartJob?.cancel()
        startLoopJob?.cancel()
        clearState()
        scope.cancel()
        CaptureBackendResolver.active().stopInputMonitoring(displayId)
        CaptureBackendResolver.activeLiveCaptureSource?.stopLoop(displayId)
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
    }

    override fun refresh() {
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        restartJob?.cancel()
        clearState()
        val source = CaptureBackendResolver.activeLiveCaptureSource ?: return
        if (source.isLoopRunning(displayId)) {
            source.requestCleanCapture(displayId)
        } else {
            // Loop was stopped (e.g. via hotkeyHoldStart). Restart it;
            // startLoop's first frame is clean by construction.
            startLoop(source)
        }
    }

    private fun startLoop(source: LiveCaptureSource) {
        startLoopJob?.cancel()
        startLoopJob = scope.launch {
            // Same pre-first-cycle gate the LiveCycleEngine modes run: the
            // engine warm-up joined, so no frame pays the lazy model load
            // mid-pass. Idempotent — restarts (dismiss, refresh) pass
            // straight through once the warm-up has settled.
            service.awaitFirstCycleClear()
            // A hold can arrive while the gate is parked, and its global
            // stopAllLoops only stops loops that EXIST — a start that hasn't
            // fired yet isn't one of them. Same rule as the dismiss-restart
            // guard below: never start into a hold-preview or under the
            // rescue alert; hotkeyHoldEnd's refresh() (or the alert handlers'
            // refreshLiveOverlay) restarts the loop cleanly.
            if (service.livePaused) {
                DetectionLog.log("furigana startLoop skipped (livePaused)")
                return@launch
            }
            source.startLoop(displayId, service.serviceScope,
                onCleanFrame = ::handleCleanFrame,
                onRawFrame = ::handleRawFrame
            )
        }
    }

    override fun dismiss() = hideAndRestartAfter(Prefs(service).captureIntervalMs)

    /** Rotation: the [dismiss] hide-and-restart, but waiting out
     *  [LiveMode.ROTATION_SETTLE_MS] instead of the user interval — the
     *  annotations (at now-void coordinates) come down immediately and the
     *  loop restarts once the rotation has settled. */
    override fun onDisplayRotated() = hideAndRestartAfter(LiveMode.ROTATION_SETTLE_MS)

    private fun hideAndRestartAfter(delayMs: Long) {
        val source = CaptureBackendResolver.activeLiveCaptureSource ?: return
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        // A gated start still parked in awaitFirstCycleClear has no loop for
        // stopLoop below to stop — cancel it, or it would fire into the
        // dismiss interval (or a hold) the restart guard exists to protect.
        startLoopJob?.cancel()
        source.stopLoop(displayId)
        CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
        clearState()
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            // A hotkey combo routes through onKeyEvent → onGameInput → here
            // BEFORE checkHotkeyCombos sets holdActive, so we can't gate the
            // scheduling itself. Instead, skip the restart if a hold-preview
            // is now in progress — hotkeyHoldEnd's refresh() will restart the
            // loop cleanly on release.
            if (service.livePaused) {
                DetectionLog.log("dismiss restart skipped (livePaused)")
                return@launch
            }
            startLoop(source)
        }
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedFuriganaBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Clean frame handling ──────────────────────────────────────────────

    private fun handleCleanFrame(frame: com.playtranslate.capture.CapturedFrame) {
        val raw = frame.bitmap
        // Skip frames while a capture hold is active (floating menu / hold-to-
        // preview), so the loop stops requesting overlay-blanking clean captures
        // behind it. Processing resumes once the hold clears.
        if (service.livePaused) { raw.recycle(); return }
        pokeIfRevealInFlight()
        cleanProcessingJob?.cancel()
        cleanProcessingJob = scope.launch {
            try {
                processCleanFrame(raw, frame.includesSystemUi, frame.includesOwnOverlays)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (service.isLive) {
                    CaptureBackendResolver.activeLiveCaptureSource?.requestCleanCapture(displayId)
                }
                throw e
            }
        }
    }

    /** [frameIncludesOwnOverlays]: despite this function's name, its input
     *  is not always a clean capture — [handleRawFrame] delegates RAW frames
     *  here when no overlay exists yet ("inherently clean" of furigana, but
     *  still carrying the floating icon). The frame's stamped fact travels
     *  in so runOcr blacks the icon out exactly when it is really there
     *  (2026-07-16 adversarial-review finding). */
    private suspend fun processCleanFrame(
        raw: Bitmap,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
    ) {
        if (!service.isConfigured) { raw.recycle(); return }

        try {
            // Shared OCR pipeline: crop → icon blackout (stamped raw frames
            // only) → OCR → filter source chars. The status-bar crop and
            // blackout decisions come from the frame's own stamped facts
            // (CapturedFrame).
            val pipeline = service.runOcr(
                raw, displayId, frameIncludesSystemUi, frameIncludesOwnOverlays,
            )

            if (pipeline == null) {
                cachedFuriganaBoxes = null
                service.handleNoTextDetected(displayId)
                return
            }

            processPipeline(pipeline, raw, frameIncludesSystemUi, frameIncludesOwnOverlays)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    /** The rebuild core, split from [processCleanFrame] so [handleRawFrame]
     *  can feed it too: a raw frame whose overlay regions were patched from
     *  [cleanRefBitmap] is exactly as OCR-clean as a blanked capture, and
     *  routing typewriter reveals through it instead of requestCleanCapture
     *  is what keeps the a11y backend from hiding every overlay for the
     *  capture — the per-reveal furigana blink the device pass caught.
     *  (Frames STAMPED overlay-free — a CLEAN task mirror — skip the raw
     *  path entirely and arrive here via [handleCleanFrame] unpatched.)
     *  Does NOT recycle [raw]; callers own their bitmap. */
    private suspend fun processPipeline(
        pipeline: OverlayToolkit.OcrPipelineResult,
        raw: Bitmap,
        frameIncludesSystemUi: Boolean,
        frameIncludesOwnOverlays: Boolean,
    ) {
        val (ocrResult, dedupKey, left, top, _, _) = pipeline

        // Pipeline drift defense — mirrors PinholeOverlayMode.kt:303-318.
        // The existing TranslationOverlayView's width/height are frozen at
        // its initial display dims; silently reusing it with mismatched
        // pipeline dims flips scaleX off identity and shifts boxes
        // (statusBarHeight toggling, MP capture-size race, rotation, etc).
        // Tear down so the next raw frame falls into handleRawFrame's
        // null-ref branch and rebuilds against a fresh overlay view.
        if (cleanRefBitmap != null &&
            (left != cropLeft || top != cropTop ||
                raw.width != screenshotW || raw.height != screenshotH)) {
            Log.w(
                TAG,
                "Pipeline drift (crop=($cropLeft,$cropTop)→($left,$top), " +
                    "screen=${screenshotW}x$screenshotH→${raw.width}x${raw.height})"
            )
            clearState()
            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
            return
        }

        // Panel policy: OFFER every eligible frame; [LivePanelRecord] at
        // the delivery layer makes repeated offers idempotent. No mode-side
        // emission state — the record owner is the panel's own truth.
        val prevText = lastOcrText
        val panelEligible = panelOfferEligible(
            prevText, dedupKey,
            SourceLanguageProfiles[Prefs(service).sourceLangId].translationCode,
        )
        // Eager typewriter: growth of 1–3 chars/cycle sits INSIDE the
        // significant-change tolerance, so evolving text needs its own
        // trigger or readings lag the reveal.
        val frameEvolving = prevText != null && OverlayToolkit.isEvolvingText(prevText, dedupKey)

        // Floor-paced follow-up while a reveal is active or a frontier hold
        // is pending release: the confirming frame (which releases held
        // ruby and completes the panel settle) arrives one floor interval
        // away instead of a full user interval later. Self-expiring window
        // — pacing decays on its own once the reveal stops refreshing it.
        if (frameEvolving || heldFrontierTexts.isNotEmpty()) {
            CaptureBackendResolver.activeLiveCaptureSource
                ?.pokeFastPoll(displayId, FAST_POLL_WINDOW_MS)
        }

        // Dedup: if text unchanged (and not evolving) with cached
        // furigana, re-show — and complete any pending panel settle.
        // A non-empty heldFrontierTexts disqualifies the fast path: the
        // cache is missing ruby by design, and this settled frame is the
        // confirmation that releases it (see the field's kdoc).
        if (prevText != null && !frameEvolving && heldFrontierTexts.isEmpty() &&
            !OverlayToolkit.isSignificantChange(prevText, dedupKey)) {
            val boxes = cachedFuriganaBoxes
            if (boxes != null) {
                service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH, displayId = displayId)
                if (cleanRefBitmap == null) {
                    cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
                }
                // Quiet cycles re-offer settled text; the pre-flight skips
                // the screenshot JPEG when the record would drop the offer
                // anyway (hidden panel, already-delivered content).
                if (panelEligible && service.livePanelWouldAccept(displayId, dedupKey)) {
                    val screenshotPath = service.captureSaveToCache(raw, displayId)
                    service.translateAndSendToPanel(
                        ocrResult, screenshotPath, displayId, frameIncludesSystemUi,
                        frameIncludesOwnOverlays, liveDedup = true,
                    )
                }
                return
            }
        }

        lastOcrText = dedupKey

        // Build and show furigana (grouped for selective invalidation).
        //
        // Reuse-or-rebuild per group. Eager typewriter dispatch lands
        // here every 1–3 revealed chars, and rebuilding EVERY group's
        // boxes each cycle made the whole overlay churn while one line
        // typed (device-observed flicker). A group whose text and bounds
        // are unchanged keeps its prior boxes verbatim — identical
        // objects, zero annotation work — so per-cycle turnover shrinks
        // to the line actually revealing.
        //
        // Two disqualifiers force a rebuild even on identical text:
        //  - held now: the group is mid-reveal (or its held text hasn't
        //    been confirmed yet); rebuild WITH the frontier hold — its last
        //    word's ruby waits for a confirming frame, so early readings
        //    never visibly revise;
        //  - previously held: the group's text matching what was held IS
        //    the confirmation — rebuild without the hold releases the
        //    reading.
        val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
        val prevGroups = furiganaGroups
        val prevHeld = heldFrontierTexts
        val newHeld = mutableSetOf<String>()
        val rebuilt = mutableListOf<OverlayToolkit.FuriganaGroup>()
        for (g in ocrResult.groups) {
            val evolving = prevGroups.any { pg ->
                Rect.intersects(pg.groupBounds, g.bounds) &&
                    OverlayToolkit.isEvolvingText(pg.groupText, g.text)
            }
            // Release demands CONFIRMATION, not just non-growth: an OCR
            // jitter frame mid-reveal (half-drawn glyph misread) is neither
            // evolving nor equal to the held text. Treating it as settled
            // would render the frontier ruby early and risk the visible
            // revision the hold exists to prevent — so a text that overlaps
            // a held group without matching its held text keeps holding;
            // whatever the reveal truly ends on repeats next frame and
            // releases then.
            val overlapsHeld = prevGroups.any { pg ->
                Rect.intersects(pg.groupBounds, g.bounds) && pg.groupText in prevHeld
            }
            val holdNow = evolving || (overlapsHeld && g.text !in prevHeld)
            if (!holdNow && g.text !in prevHeld) {
                val prior = prevGroups.firstOrNull { pg ->
                    pg.groupText == g.text && pg.groupBounds == g.bounds
                }
                if (prior != null) {
                    rebuilt += prior
                    continue
                }
            }
            if (holdNow) newHeld += g.text
            val boxes = OverlayToolkit.buildFuriganaBoxesForGroup(
                g, engine, service.furiganaPaint,
                debugTiming = Prefs(service).debugLiveMode,
                holdFrontier = holdNow,
            )
            if (boxes.isNotEmpty()) {
                rebuilt += OverlayToolkit.FuriganaGroup(g.text, g.bounds, boxes)
            }
        }
        heldFrontierTexts = newHeld
        furiganaGroups = rebuilt
        val furigana = furiganaGroups.flatMap { it.boxes }
        cachedFuriganaBoxes = furigana
        this@FuriganaMode.cropLeft = left
        this@FuriganaMode.cropTop = top
        this@FuriganaMode.screenshotW = raw.width
        this@FuriganaMode.screenshotH = raw.height

        if (furigana.isNotEmpty()) {
            service.showLiveOverlay(furigana, left, top, raw.width, raw.height, displayId = displayId)
        }

        // Save clean reference for patching raw frames (mutable for updateCleanRef)
        cleanRefBitmap?.recycle()
        cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)

        // Save screenshot for Anki unconditionally (per-display filename —
        // see ScreenshotManager.saveToCache); the PANEL offer rides
        // [panelOfferEligible]: a mid-reveal frame never reaches MT (except
        // a boundary-complete read), and repeated settled offers are
        // deduped at the delivery layer.
        val screenshotPath = service.captureSaveToCache(raw, displayId)
        if (panelEligible) {
            service.translateAndSendToPanel(
                ocrResult, screenshotPath, displayId, frameIncludesSystemUi,
                frameIncludesOwnOverlays, liveDedup = true,
            )
        }
    }

    // ── Raw frame handling (OCR-based change detection) ───────────────────
    //
    // View-space rects from getChildScreenRects() are converted to bitmap
    // pixel coordinates via FrameCoordinates.viewToBitmap before indexing
    // the raw/ref bitmaps. At identity scale (our only currently supported
    // case), the conversion is a no-op via reference short-circuit; see
    // FrameCoordinates KDoc for details on the coordinate spaces.

    private fun handleRawFrame(frame: com.playtranslate.capture.CapturedFrame) {
        val bitmap = frame.bitmap
        val frameIncludesSystemUi = frame.includesSystemUi
        val frameIncludesOwnOverlays = frame.includesOwnOverlays
        // Skip frames while a capture hold is active — see handleCleanFrame.
        if (service.livePaused) { bitmap.recycle(); return }
        pokeIfRevealInFlight()
        if (cleanProcessingJob?.isActive == true || rawOcrJob?.isActive == true) {
            bitmap.recycle()
            return
        }

        // A frame STAMPED free of our overlays (a CLEAN task-scoped mirror —
        // the source's single stamping point is stream-aware) already IS the
        // clean frame this mode works so hard to reconstruct. Everything
        // below — overlay-rect patching, patched-frame OCR, the clean-capture
        // round trip — exists to recover this property on contaminated
        // streams; running it anyway would overwrite real current pixels
        // with stale ref strips and OCR the chimera (2026-08-06 adversarial
        // review, the consolidation flip's one real hole). Process it as
        // what it is.
        if (!frameIncludesOwnOverlays) {
            emptyRectsStallCount = 0
            handleCleanFrame(frame)
            return
        }

        val ref = cleanRefBitmap
        val boxes = cachedFuriganaBoxes
        val overlayView = CaptureBackendResolver.activeOverlayUi?.translationOverlayForDisplay(displayId)
        val screenRects = overlayView?.getChildScreenRects() ?: emptyList()

        if (ref == null || boxes.isNullOrEmpty()) {
            // No overlay exists — raw frame is inherently clean, process it directly
            emptyRectsStallCount = 0
            handleCleanFrame(frame)
            return
        }

        if (screenRects.isEmpty()) {
            // Boxes are cached but no overlay view rects exist. Usually a transient
            // layout-pending state, but could indicate a stall if showLiveOverlay
            // was blocked (holdActive, missing display, a11y not ready).
            bitmap.recycle()
            emptyRectsStallCount++
            if (emptyRectsStallCount >= STALL_RECOVERY_FRAMES) {
                // Too long without a rendered overlay — force recovery
                emptyRectsStallCount = 0
                clearState()
                CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                CaptureBackendResolver.activeLiveCaptureSource?.requestCleanCapture(displayId)
            }
            return
        }
        emptyRectsStallCount = 0

        // Screenshot dimensions changed (display resize, rotation, inset change):
        // every geometry-dependent field is stale. Clear all cached state, hide
        // the old overlay (positions don't map to the new size), and request a
        // fresh clean capture to rebuild from scratch.
        if (bitmap.width != ref.width || bitmap.height != ref.height) {
            clearState()
            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
            CaptureBackendResolver.activeLiveCaptureSource?.requestCleanCapture(displayId)
            bitmap.recycle()
            return
        }

        // Build the coordinate context for this raw frame. FuriganaMode only
        // uses view→bitmap (the drawBitmap call below); ocrToBitmap isn't
        // exercised here since OCR results are fed straight to a full-frame
        // overlay rebuild by handleCleanFrame, not indexed into raw.
        //
        // Unlike PinholeOverlayMode, FuriganaMode's raw-frame patching is
        // coordinate-scale-agnostic: it's a region-based bulk copy from
        // cleanRef to patched via Canvas.drawBitmap, not a per-pixel blend.
        // At non-identity scale viewToBitmap still produces the correct
        // physical region (just with potential 1-pixel truncation at the
        // edges), and the patch operation copies the right bytes. So
        // Furigana does NOT fail-closed at non-identity scale here; it
        // will keep running and you'll see the FrameCoordinates log-once
        // warning as a diagnostic signal only. Note that this is an
        // asymmetry with PinholeOverlayMode, which does fail-closed at
        // non-identity because its pinhole detection math breaks — see
        // PinholeOverlayMode.checkPinholes KDoc for the full story.
        val coords = FrameCoordinates(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            viewWidth = overlayView?.width ?: 0,
            viewHeight = overlayView?.height ?: 0,
            cropLeft = cropLeft,
            cropTop = cropTop,
        )
        val bitmapRects = coords.viewListToBitmap(screenRects)

        // Patch raw frame: overwrite overlay regions with clean ref pixels so OCR
        // doesn't read the rendered furigana text. Uses Canvas.drawBitmap (hardware-
        // accelerated when possible) to avoid full-frame pixel array allocations.
        val patched = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        try {
            val canvas = Canvas(patched)
            val margin = 12  // covers stroke/shadow extension beyond view bounds
            for (bitmapRect in bitmapRects) {
                val left = (bitmapRect.left - margin).coerceAtLeast(0)
                val top = (bitmapRect.top - margin).coerceAtLeast(0)
                val right = (bitmapRect.right + margin).coerceAtMost(patched.width)
                val bottom = (bitmapRect.bottom + margin).coerceAtMost(patched.height)
                if (right <= left || bottom <= top) continue
                val src = Rect(left, top, right, bottom)
                val dst = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                canvas.drawBitmap(ref, src, dst, null)
            }
        } catch (e: Exception) {
            if (!patched.isRecycled) patched.recycle()
            return
        }

        // OCR the patched frame asynchronously. The patch replaced only the
        // furigana overlay rects with cleanRef pixels — the floating icon
        // survives in every other region of this raw frame, so the stamped
        // fact must ride into runOcr's blackout (2026-07-16 finding).
        rawOcrJob = scope.launch {
            try {
                val pipeline = service.runOcr(
                    patched, displayId, frameIncludesSystemUi, frameIncludesOwnOverlays,
                )
                if (pipeline != null) {
                    val prevText = lastOcrText
                    val prevKanji = if (prevText != null) kanjiOnly(prevText) else ""
                    val newKanji = kanjiOnly(pipeline.dedupKey)
                    val kanjiChanged = prevText != null && OverlayToolkit.isSignificantChange(prevKanji, newKanji)
                    // Typewriter reveals and settled repeats run the rebuild
                    // core on THIS patched frame instead of round-tripping
                    // through requestCleanCapture. The patched bitmap is
                    // already overlay-free where it matters, and on the a11y
                    // backend every clean capture blanks ALL overlays for the
                    // grab — routed through captures, an eager reveal blinked
                    // the whole screen's furigana every few characters
                    // (device pass). Evolving must be checked before
                    // kanjiChanged: a growth step that adds several kanji is
                    // both. The settled repeat has business the counter
                    // branch can't do: it offers the settled sentence to
                    // the panel and releases a held frontier — and per-group
                    // reuse plus the delivery-layer record make it
                    // idempotent on every later quiet frame. Scene changes
                    // (significant, non-evolving) keep the clean-capture
                    // path: their patch content is stale by definition, and
                    // one blink under a full redraw is invisible.
                    val frameEvolving = prevText != null &&
                        OverlayToolkit.isEvolvingText(prevText, pipeline.dedupKey)
                    val settledRepeat = prevText != null &&
                        !OverlayToolkit.isSignificantChange(prevText, pipeline.dedupKey)
                    if (frameEvolving) {
                        noChangeRawFrameCount = 0
                        processPipeline(
                            pipeline, patched, frameIncludesSystemUi, frameIncludesOwnOverlays,
                        )
                    } else if (kanjiChanged) {
                        noChangeRawFrameCount = 0
                        DetectionLog.log("Furigana: text changed, requesting clean capture")

                        // Selective invalidation: remove furigana for changed groups, keep the rest
                        val newOcrGroups = pipeline.ocrResult.groups.map { it.text to it.bounds }
                        val surviving = furiganaGroups.filter { old ->
                            newOcrGroups.any { (newText, newBounds) ->
                                OverlayToolkit.groupsMatch(old.groupText, old.groupBounds, newText, newBounds)
                            }
                        }
                        val removed = furiganaGroups.filter { old ->
                            !newOcrGroups.any { (newText, newBounds) ->
                                OverlayToolkit.groupsMatch(old.groupText, old.groupBounds, newText, newBounds)
                            }
                        }
                        val removedBoxes = removed.flatMap { it.boxes }
                        service.removeOverlayBoxes(removedBoxes, displayId)

                        furiganaGroups = surviving
                        cachedFuriganaBoxes = surviving.flatMap { it.boxes }.ifEmpty { null }
                        // Null lastOcrText forces the rebuild path in processCleanFrame.
                        // Don't clear cleanRefBitmap here: doing so races with the screenshot
                        // loop — if another raw frame arrives before the clean capture lands,
                        // handleRawFrame would fall into the "treat as clean" path and OCR
                        // a bitmap that still has furigana visible on screen. Leaving the old
                        // ref in place keeps raw-frame patching working until processCleanFrame
                        // replaces the ref on the next clean frame.
                        lastOcrText = null

                        if (cachedFuriganaBoxes == null) {
                            CaptureBackendResolver.activeOverlayUi?.hideTranslationOverlayForDisplay(displayId)
                        }

                        CaptureBackendResolver.activeLiveCaptureSource?.requestCleanCapture(displayId)
                    } else {
                        // No change detected (settled repeat, jitter, or a
                        // kana-only shift below the kanji gate). Settled
                        // repeats still visit the rebuild core — frontier
                        // release and the panel's settle emission live there;
                        // with nothing pending it's the dedup fast path.
                        if (settledRepeat) {
                            processPipeline(
                                pipeline, patched, frameIncludesSystemUi, frameIncludesOwnOverlays,
                            )
                        }
                        // Periodically force a clean capture to refresh
                        // the ref — stale ref content in overlay regions can mask real scene
                        // changes via bleed-through, creating a self-reinforcing stall.
                        noChangeRawFrameCount++
                        if (noChangeRawFrameCount >= STALE_REF_REFRESH_FRAMES) {
                            noChangeRawFrameCount = 0
                            DetectionLog.log("Furigana: safety-net clean capture (stale ref refresh)")
                            // Force rebuild path in processCleanFrame. Don't clear cleanRefBitmap
                            // here — see race comment above.
                            lastOcrText = null
                            CaptureBackendResolver.activeLiveCaptureSource?.requestCleanCapture(displayId)
                        }
                    }
                } else {
                    clearState()
                    service.handleNoTextDetected(displayId)
                }
            } finally {
                if (!patched.isRecycled) patched.recycle()
            }
        }
    }

    /** Keep only CJK Unified Ideographs (kanji) — changes in kana/ascii are
     *  irrelevant for furigana overlay staleness. */
    private fun kanjiOnly(s: String): String =
        s.filter { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' || it in '\uF900'..'\uFAFF' }
}
