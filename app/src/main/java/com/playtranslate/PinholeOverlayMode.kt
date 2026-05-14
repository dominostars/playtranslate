package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.View
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
 * @param a11y the accessibility service instance, captured at mode construction time.
 *   Previously fetched via [PlayTranslateAccessibilityService.instance] scattered
 *   throughout this class; now injected so the dependency is explicit and the
 *   mode is unit-testable with a mocked service. If the accessibility service is
 *   torn down mid-session the cached reference becomes stale, but every internal
 *   field access we do through it (`screenshotManager`, `translationOverlayView`,
 *   etc.) is already nullable and null-checked inline, so stale references
 *   degrade gracefully to the same "nothing happens" behavior the pre-injection
 *   `instance?.` pattern produced.
 */
class PinholeOverlayMode(
    private val service: CaptureService,
    private val a11y: PlayTranslateAccessibilityService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.TRANSLATION

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    // State
    private var cachedBoxes: List<TranslationOverlayView.TextBox>? = null
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

    private enum class PinholeResult { KEEP, DIRTY, REMOVE }

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
        val maxDelta: Int,
    )

    override fun start() {
        currentJob?.cancel()
        a11y.startInputMonitoring(displayId) { onButtonDown() }
        scheduleNextCycle()
    }

    private fun scheduleNextCycle(delayMs: Long = 0) {
        currentJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val nextDelay = runCycle()
                scheduleNextCycle(nextDelay)
            } catch (e: CancellationException) {
                // Normal cancellation (stop/refresh/onButtonDown) — propagate.
                throw e
            } catch (e: Exception) {
                // Unexpected throw (display went away, WindowManager token
                // invalidated, bitmap op on detached view, etc.). Log and
                // reschedule so the cycle self-heals instead of silently
                // going dormant.
                Log.e("PinholeOverlayMode", "runCycle failed, rescheduling", e)
                scheduleNextCycle(Prefs(service).captureIntervalMs)
            }
        }
    }

    override fun stop() {
        scope.cancel()
        resetState()

        a11y.stopInputMonitoring(displayId)
        a11y.hideTranslationOverlayForDisplay(displayId)
    }

    override fun refresh() {
        resetState()
        scheduleNextCycle()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private fun onButtonDown() {
        a11y.hideTranslationOverlayForDisplay(displayId)
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
    }

    // ── Unified Cycle ───────────────────────────────────────────────────

    /** True only when cached boxes are actually rendered on screen. An external
     *  hideTranslationOverlay (e.g. holdCancel) can null the overlay windows
     *  without clearing cachedBoxes — in that state this returns false so
     *  fillOverlayRegions and the isFirstCapture branch skip correctly.
     *  (Step 4's cleanRef reconcile uses bitmapRects directly, not this,
     *  because the visible-children signal is what cleanRef actually tracks.) */
    private fun hasOverlays(): Boolean =
        cachedBoxes != null &&
        a11y.translationOverlayForDisplay(displayId) != null

    /** Run one capture-detect-translate cycle. Returns the delay (ms) before the next cycle. */
    private suspend fun runCycle(): Long {
        val prefs = Prefs(service)
        if (service.holdActive) return 100L
        val mgr = a11y.screenshotManager ?: return prefs.captureIntervalMs
        val dirtyView = a11y.dirtyOverlayForDisplay(displayId)
        val hasDirty = cachedBoxes?.any { it.dirty } == true
        cycleNum++
        val debug = prefs.debugLiveMode

        // 1. Hide dirty overlay window before capture (hardware layer alpha + frame commit sync)
        if (hasDirty && dirtyView != null) {
            val committed = hideAndAwaitCommit(dirtyView)
            if (!committed) {
                // View detached or timed out — skip this capture
                return prefs.captureIntervalMs
            }
            waitVsync(2)
        }

        // 2. Capture — restore dirty window in callback (before bitmap copy)
        val raw = mgr.requestRaw(displayId) {
            if (hasDirty) dirtyView?.alpha = 1f
        }

        if (raw == null) {
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
                a11y.hideTranslationOverlayForDisplay(displayId)
                return prefs.captureIntervalMs
            }

            // Build FrameCoordinates for this cycle. At identity scale
            // (accessibility takeScreenshot on standard displays, the only
            // configuration this mode supports), viewToBitmap is a no-op via
            // reference short-circuit and bitmapRects share instances with
            // rects. See FrameCoordinates KDoc for details on the coordinate
            // spaces and why non-identity is fail-closed below.
            val overlayView = a11y.translationOverlayForDisplay(displayId)
            val rects = overlayView?.getChildScreenRects() ?: emptyList()
            val coords = FrameCoordinates(
                bitmapWidth = raw.width,
                bitmapHeight = raw.height,
                viewWidth = overlayView?.width ?: 0,
                viewHeight = overlayView?.height ?: 0,
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
                return prefs.captureIntervalMs * 3
            }

            val bitmapRects = coords.viewListToBitmap(rects)

            // 3. Dirty view stays visible until after OCR results

            // 4. Reconcile cleanRef against the visible overlay state.
            //    Single site of truth for the cleanRef-tracks-overlays
            //    invariant. bitmapRects is the canonical signal: it's
            //    the clean view's children at step 2 capture time, i.e.
            //    exactly what raw shows and what updateCleanRef operates
            //    on. This cuts cleanly through every odd state —
            //      • all-dirty (boxes live on the dirty view) → empty
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
                ocrImage = raw.copy(raw.config, true)
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

            // After OCR, clear dirty state — dirty overlays have been captured and evaluated
            cachedBoxes = cachedBoxes?.filter { !it.dirty }?.ifEmpty { null }
            dirtyView?.setBoxes(emptyList(), cropLeft, cropTop, screenshotW, screenshotH)

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
                    a11y.hideTranslationOverlayForDisplay(displayId)
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
            val ocrBitmapRects: List<Rect>
            val classification: ClassificationResult
            if (pipeline != null) {
                val (ocrResult, _, pipeCropLeft, pipeCropTop, _, _) = pipeline
                val classifyCoords = FrameCoordinates(
                    bitmapWidth = raw.width,
                    bitmapHeight = raw.height,
                    viewWidth = overlayView?.width ?: 0,
                    viewHeight = overlayView?.height ?: 0,
                    cropLeft = pipeCropLeft,
                    cropTop = pipeCropTop,
                )
                ocrBitmapRects = boxes.map { classifyCoords.ocrToBitmap(it.bounds) }
                classification = classifyOcrResults(ocrResult, boxes, ocrBitmapRects, classifyCoords)
            } else {
                ocrBitmapRects = emptyList()
                classification = ClassificationResult(emptySet(), emptySet(), emptyList())
            }
            val contentMatchRemovals = classification.contentMatchRemovals
            val staleOverlayIndices = classification.staleOverlayIndices
            val farOcrGroups = classification.farOcrGroups

            // 8. Pinhole change detection — DIRTY moves overlays to dirty window
            val cleanRef = cleanRefBitmap
            val pinholeRemovals = mutableSetOf<Int>()
            val pinholeDirty = mutableSetOf<Int>()
            if (cleanRef != null) {
                for ((idx, box) in boxes.withIndex()) {
                    if (idx >= bitmapRects.size) continue
                    if (box.dirty) continue
                    if (idx in staleOverlayIndices) continue
                    val outcome = checkPinholes(raw, cleanRef, bitmapRects[idx])
                    when (outcome.result) {
                        PinholeResult.REMOVE -> pinholeRemovals.add(idx)
                        PinholeResult.DIRTY -> pinholeDirty.add(idx)
                        PinholeResult.KEEP -> {}
                    }
                    if (debug && outcome.result != PinholeResult.KEEP) {
                        val r = bitmapRects[idx]
                        val pctStr = "%.1f".format(outcome.pct * 100f)
                        DetectionLog.log(
                            "D$displayId c$cycleNum box$idx ${outcome.result} " +
                                "text=\"${box.sourceText.take(20)}\" " +
                                "pct=$pctStr% changed=${outcome.changed}/${outcome.total} " +
                                "maxDelta=${outcome.maxDelta} " +
                                "rect=(${r.left},${r.top},${r.right},${r.bottom})"
                        )
                    }
                }
            }

            // 8b. Cascade stale to neighbors. See cascadeStaleRemovals in Classification.kt.
            //     Same coordinate-space reasoning as the proximity check
            //     above: cascade uses unpadded ocrBitmapRects so it agrees
            //     with classification's notion of "neighbor".
            val cascadedRemovals = cascadeStaleRemovals(staleOverlayIndices, boxes, ocrBitmapRects)

            // 9. Resolve: compute final state from immutable snapshot in one pass
            val allRemovals = cascadedRemovals + pinholeRemovals + contentMatchRemovals
            val nextBoxes = boxes.mapIndexedNotNull { i, box ->
                when {
                    i in allRemovals -> null
                    i in pinholeDirty -> box.copy(dirty = true)
                    else -> box
                }
            }

            val cleanBoxes = nextBoxes.filter { !it.dirty }
            val dirtyBoxes = nextBoxes.filter { it.dirty }
            cachedBoxes = nextBoxes.ifEmpty { null }
            val anyChanged = allRemovals.isNotEmpty() || pinholeDirty.isNotEmpty() || dirtyBoxes.isNotEmpty()

            if (debug && (anyChanged || farOcrGroups.isNotEmpty())) {
                DetectionLog.log(
                    "D$displayId c$cycleNum transitions: " +
                        "dirty=${pinholeDirty.toSortedSet()} " +
                        "removed=(pinhole=${pinholeRemovals.toSortedSet()}, " +
                        "contentMatch=${contentMatchRemovals.toSortedSet()}, " +
                        "cascade=${cascadedRemovals.toSortedSet()}, " +
                        "stale=${staleOverlayIndices.toSortedSet()}) " +
                        "far=${farOcrGroups.size} " +
                        "boxesIn=${boxes.size} cleanOut=${cleanBoxes.size} dirtyOut=${dirtyBoxes.size}"
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
                    for (i in ocrR.groupTexts.indices) {
                        val t = ocrR.groupTexts[i].take(40)
                        val b = ocrR.groupBounds.getOrNull(i)
                        DetectionLog.log(
                            "D$displayId c$cycleNum   ocr[$i] text=\"$t\" " +
                                "ocrRect=${b?.let { "(${it.left},${it.top},${it.right},${it.bottom})" } ?: "null"}"
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

            // 10. Apply to views — single commit point
            dirtyView?.setBoxes(dirtyBoxes, cropLeft, cropTop, screenshotW, screenshotH)

            if (anyChanged) {
                anyRemoved = allRemovals.isNotEmpty()
                if (cleanBoxes.isNotEmpty()) {
                    showOverlayAndCapture(a11y, cleanBoxes, cropLeft, cropTop, screenshotW, screenshotH)
                } else if (farOcrGroups.isEmpty()) {
                    // No clean boxes AND no replacement coming — clear the
                    // clean window so stale boxes don't linger. setBoxes
                    // (not hideTranslationOverlayForDisplay) keeps the
                    // overlay window alive: tearing it down forces a
                    // wm.removeView / wm.addView round-trip whose composition
                    // latency the user sees as a visible "off" period.
                    //
                    // Dirty boxes (when present) live on the dirtyView — we
                    // never need to set them back into the clean view here.
                    a11y.translationOverlayForDisplay(displayId)?.setBoxes(
                        emptyList(), cropLeft, cropTop, screenshotW, screenshotH
                    )
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
            //     re-showed surviving cleanBoxes after an external hide).
            //     Reaching here with cleanRef null means step 4 dropped it
            //     (bitmapRects was empty at step 2), so raw is pre-overlay
            //     game pixels — a valid baseline. The gate avoids one
            //     full-bitmap copy per idle cycle where the view is empty
            //     and there's nothing to place.
            if (cleanRefBitmap == null && (farOcrGroups.isNotEmpty() || cleanBoxes.isNotEmpty())) {
                cleanRefBitmap = raw.copy(raw.config, true)
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

                    val currentClean = (cachedBoxes ?: emptyList()).filter { !it.dirty }
                    val merged = currentClean + partial
                    cachedBoxes = merged
                    showOverlayAndCapture(a11y, merged, cropLeft, cropTop, screenshotW, screenshotH)
                    // Dirty window cleared — clean window now has replacements
                    dirtyView?.setBoxes(emptyList(), cropLeft, cropTop, screenshotW, screenshotH)

                    if (anyUncached) {
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val existing = cachedBoxes?.dropLast(placeholders.size) ?: emptyList()
                        val mergedFinal = existing + translated
                        cachedBoxes = mergedFinal
                        showOverlayAndCapture(a11y, mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
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

            // 14. Timing
            return if (anyRemoved) mgr.MIN_SCREENSHOT_INTERVAL_MS else prefs.captureIntervalMs
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
        a11y: PlayTranslateAccessibilityService, boxes: List<TranslationOverlayView.TextBox>,
        left: Int, top: Int, sw: Int, sh: Int
    ) {
        service.showLiveOverlay(boxes, left, top, sw, sh, pinholeMode = true, displayId = displayId)
        // Wait for children to be laid out before snapshotting. addOverlayWindow
        // is async; onSizeChanged posts rebuildChildren; rebuildChildren adds
        // children that themselves need a layout pass. Until that completes,
        // renderToOffscreen returns an empty/stale bitmap and pinhole detection
        // over-flags REMOVE for every box on the next cycle. Poll up to ~133ms
        // and fall through if it never settles.
        val view = a11y.translationOverlayForDisplay(displayId)
        var waited = 0
        if (view != null) {
            while (waited < 8 && !view.areChildrenLaidOut()) {
                waitVsync(1)
                waited++
            }
            if (waited >= 8) Log.w("PinholeOverlayMode", "renderToOffscreen: layout never settled after 8 vsyncs on display $displayId")
        } else {
            waitVsync(2)
        }
        overlayBitmap?.recycle()
        overlayBitmap = view?.renderToOffscreen()
        if (Prefs(service).debugLiveMode) {
            val ob = overlayBitmap
            DetectionLog.log(
                "D$displayId c$cycleNum renderOffscreen: settled=${waited}vsync " +
                    "viewDims=${view?.width ?: -1}x${view?.height ?: -1} " +
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
     * Check pinhole pixels in the given rect: KEEP (no change), DIRTY (minor
     * change), or REMOVE (major change).
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
     * This assumes that AT PINHOLE POSITIONS, the raw on-screen pixel is a
     * 50/50 blend of the clean game background (cleanRef) and the solid
     * overlay rendering (overlayBitmap). That's true because:
     *
     *   1. [TranslationOverlayView.createPinholeMask] generates a mask with
     *      alpha [PinholeCalibration.MASK_ALPHA] (50%) at sparse pinhole
     *      positions spaced [PinholeCalibration.PINHOLE_SPACING] apart, 0
     *      elsewhere.
     *   2. [TranslationOverlayView.dispatchDraw] composites that mask via
     *      DST_OUT on the rendered children, punching 50% holes at the mask
     *      positions and leaving non-pinhole positions fully opaque.
     *   3. The final on-screen pixel at a pinhole is therefore
     *      50% overlay + 50% game.
     *
     * The sampling loop iterates every pixel in the region and skips non-
     * pinhole positions via [isPinholePosition], which uses BITMAP
     * coordinates with a fixed spacing. The mask is generated at VIEW
     * coordinates with the same spacing. **At identity scale the two
     * coordinate systems are identical**, so the sampler hits real pinhole
     * positions.
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
     *   - Re-tuning [PinholeCalibration.SPLATTER_THRESHOLD],
     *     [PinholeCalibration.PINHOLE_DIRTY_PCT], and
     *     [PinholeCalibration.PINHOLE_CHANGE_PCT] for whatever new blend
     *     ratio results.
     *
     * None of this is done today. Identity scale only.
     */
    private fun checkPinholes(
        raw: Bitmap, cleanRef: Bitmap, bitmapRect: Rect
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

        // Overlay bitmap is in view coordinates (same as screen coordinates for full-screen overlay)
        val ovLeft = left.coerceIn(0, overlay.width)
        val ovTop = top.coerceIn(0, overlay.height)
        val ovRight = right.coerceIn(0, overlay.width)
        val ovBottom = bottom.coerceIn(0, overlay.height)
        val ovW = ovRight - ovLeft
        val ovH = ovBottom - ovTop
        if (ovW != regionW || ovH != regionH) return keepZero
        val ovPixels = IntArray(regionW * regionH)
        overlay.getPixels(ovPixels, 0, regionW, ovLeft, ovTop, regionW, regionH)

        var totalPinholes = 0
        var changedPinholes = 0
        var maxDelta = 0

        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                totalPinholes++

                val refPx = refPixels[py * regionW + px]
                val ovPx = ovPixels[py * regionW + px]
                // predicted = clean_ref * 0.5 + overlay_rendered * 0.5
                val predR = ((Color.red(refPx) + Color.red(ovPx)) / 2).coerceIn(0, 255)
                val predG = ((Color.green(refPx) + Color.green(ovPx)) / 2).coerceIn(0, 255)
                val predB = ((Color.blue(refPx) + Color.blue(ovPx)) / 2).coerceIn(0, 255)

                val rawPx = rawPixels[py * regionW + px]
                val dr = kotlin.math.abs(Color.red(rawPx) - predR)
                val dg = kotlin.math.abs(Color.green(rawPx) - predG)
                val db = kotlin.math.abs(Color.blue(rawPx) - predB)
                val delta = maxOf(dr, dg, db)
                if (delta > maxDelta) maxDelta = delta

                if (dr > PinholeCalibration.SPLATTER_THRESHOLD ||
                    dg > PinholeCalibration.SPLATTER_THRESHOLD ||
                    db > PinholeCalibration.SPLATTER_THRESHOLD) {
                    changedPinholes++
                }
            }
        }

        if (totalPinholes == 0) return keepZero
        val pct = changedPinholes.toFloat() / totalPinholes
        val result = when {
            pct >= PinholeCalibration.PINHOLE_CHANGE_PCT -> PinholeResult.REMOVE
            pct >= PinholeCalibration.PINHOLE_DIRTY_PCT -> PinholeResult.DIRTY
            else -> PinholeResult.KEEP
        }
        return PinholeOutcome(result, pct, changedPinholes, totalPinholes, maxDelta)
    }

    /**
     * Update clean ref in-place: copy non-clean-overlay pixels from raw into
     * the existing cleanRef. Clean box positions stay frozen at their initial
     * pre-overlay game content (pinhole detection relies on that invariant),
     * while everything else — including dirty positions — is refreshed from
     * raw. Raw is safe to copy because the dirty view was hidden before the
     * capture, so dirty positions contain current clean game pixels.
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
     * in-app panel. Unlike TranslationOverlayMode which re-OCRs the bare screen,
     * we already have sourceText + translatedText on every box.
     */
    private fun sendFullStateToPanel(screenshotPath: String?) {
        val boxes = cachedBoxes ?: return
        val appPanelVisible = !Prefs.isSingleScreen(service) && MainActivity.isInForeground
        if (!appPanelVisible) return

        val originalText = boxes.filter { it.sourceText.isNotEmpty() }
            .joinToString("\n") { it.sourceText }
        val translatedText = boxes.filter { it.translatedText.isNotEmpty() }
            .joinToString("\n\n") { it.translatedText }
        val segments = boxes.filter { it.sourceText.isNotEmpty() }
            .flatMap { box ->
                box.sourceText.map { ch -> TextSegment(ch.toString()) } +
                    TextSegment("\n", isSeparator = true)
            }
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        service.emitResult(TranslationResult(
            originalText = originalText,
            segments = segments,
            translatedText = translatedText,
            timestamp = timestamp,
            screenshotPath = screenshotPath
        ))
    }

    // ── Translation Helpers ─────────────────────────────────────────────

    /** Build placeholder TextBoxes with empty text (skeleton indicators). Instant, no network. */
    private fun buildPlaceholderBoxes(
        texts: List<String>, bounds: List<Rect>, lineCounts: List<Int>,
        raw: Bitmap, left: Int, top: Int,
        orientations: List<com.playtranslate.language.TextOrientation> = emptyList(),
        alignments: List<com.playtranslate.language.TextAlignment> = emptyList()
    ): List<TranslationOverlayView.TextBox> {
        val colorScale = 4
        val colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)
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
            TranslationOverlayView.TextBox("", rect, bg, tc, lineCounts.getOrElse(idx) { 1 },
                sourceText = texts.getOrElse(idx) { "" }, orientation = orient, alignment = align)
        }
    }

    /** Translate texts and return placeholders with filled translatedText. */
    private suspend fun translatePlaceholders(
        placeholders: List<TranslationOverlayView.TextBox>, texts: List<String>
    ): List<TranslationOverlayView.TextBox> {
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
                translations[idx] = results.getOrNull(i)?.first ?: ""
            }
        }

        return placeholders.mapIndexed { idx, ph ->
            ph.copy(translatedText = translations.getOrElse(idx) { "" })
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────


    /**
     * Hide the dirty overlay via alpha and wait for the RenderThread to commit
     * the transparent frame to SurfaceFlinger.
     *
     * Forces a view invalidation after setting alpha=0 so the VTO callback
     * actually fires (hardware layer alpha changes skip performTraversals,
     * but registerFrameCommitCallback requires a full traversal pass).
     *
     * Returns true if the frame was committed, false on timeout or detach.
     */
    private suspend fun hideAndAwaitCommit(dirtyView: View): Boolean {
        return withTimeoutOrNull(200L) {
            suspendCancellableCoroutine { cont ->
                dirtyView.alpha = 0f
                dirtyView.invalidate() // Force traversal so VTO callback fires

                val vto = dirtyView.viewTreeObserver
                if (!vto.isAlive || !dirtyView.isAttachedToWindow) {
                    cont.resume(false)
                    return@suspendCancellableCoroutine
                }

                vto.registerFrameCommitCallback {
                    if (cont.isActive) cont.resume(true)
                }
            }
        } ?: false
    }

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
