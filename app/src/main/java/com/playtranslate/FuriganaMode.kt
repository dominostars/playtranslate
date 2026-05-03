package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.ui.TranslationOverlayView
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

/**
 * Live furigana overlay mode. Shows hiragana readings above kanji on the
 * game screen. Uses OCR-based change detection: keeps a clean reference
 * bitmap, patches overlay regions from it on raw frames, OCRs the patched
 * image, and compares text to detect game scene changes.
 *
 * Owns ALL its mutable state. When stopped, scope is cancelled and all
 * state (including cleanRefBitmap) is released.
 */
/**
 * @param service the enclosing capture service (for state access and coordinator calls)
 * @param a11y the accessibility service instance, captured at mode construction.
 *   Injected so the dependency is explicit and the mode is unit-testable. See
 *   PinholeOverlayMode's equivalent KDoc for the rationale — same pattern.
 */
class FuriganaMode(
    private val service: CaptureService,
    private val a11y: PlayTranslateAccessibilityService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.FURIGANA

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanProcessingJob: Job? = null
    private var rawOcrJob: Job? = null
    private var restartJob: Job? = null

    // ── Mode-owned state ──────────────────────────────────────────────────

    private var furiganaGroups: List<OverlayToolkit.FuriganaGroup> = emptyList()
    private var cachedFuriganaBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var lastOcrText: String? = null
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

    /** Reset all mode-owned state. Does NOT hide overlays or notify UI. */
    private fun clearState() {
        furiganaGroups = emptyList()
        cachedFuriganaBoxes = null
        lastOcrText = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        emptyRectsStallCount = 0
        noChangeRawFrameCount = 0
    }

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        val mgr = a11y.screenshotManager
        if (mgr == null) {
            DetectionLog.log("ERROR: screenshotManager is null, can't start furigana loop")
            return
        }
        a11y.startInputMonitoring(displayId) { onButtonDown() }
        DetectionLog.log("Starting furigana loop on display $displayId")
        startLoop(mgr)
    }

    override fun stop() {
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        restartJob?.cancel()
        clearState()
        scope.cancel()
        a11y.stopInputMonitoring(displayId)
        a11y.screenshotManager?.stopLoop(displayId)
        a11y.hideTranslationOverlayForDisplay(displayId)
    }

    override fun refresh() {
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        restartJob?.cancel()
        clearState()
        val mgr = a11y.screenshotManager ?: return
        if (mgr.isLoopRunning(displayId)) {
            mgr.requestCleanCapture(displayId)
        } else {
            // Loop was stopped (e.g. via hotkeyHoldStart). Restart it; startLoop
            // itself calls requestCleanCapture so the next frame comes in clean.
            startLoop(mgr)
        }
    }

    private fun startLoop(mgr: ScreenshotManager) {
        mgr.requestCleanCapture(displayId)
        mgr.startLoop(displayId, service.serviceScope,
            onCleanFrame = ::handleCleanFrame,
            onRawFrame = ::handleRawFrame
        )
    }

    private fun onButtonDown() {
        val mgr = a11y.screenshotManager ?: return
        cleanProcessingJob?.cancel()
        rawOcrJob?.cancel()
        mgr.stopLoop(displayId)
        a11y.hideTranslationOverlayForDisplay(displayId)
        clearState()
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(Prefs(service).captureIntervalMs)
            // A hotkey combo routes through onKeyEvent → onGameInput → here
            // BEFORE checkHotkeyCombos sets holdActive, so we can't gate the
            // scheduling itself. Instead, skip the restart if a hold-preview
            // is now in progress — hotkeyHoldEnd's refresh() will restart the
            // loop cleanly on release.
            if (service.holdActive) {
                DetectionLog.log("onButtonDown restart skipped (holdActive)")
                return@launch
            }
            startLoop(mgr)
        }
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedFuriganaBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Clean frame handling ──────────────────────────────────────────────

    private fun handleCleanFrame(raw: Bitmap) {
        cleanProcessingJob?.cancel()
        cleanProcessingJob = scope.launch {
            try {
                processCleanFrame(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (service.isLive) {
                    a11y.screenshotManager?.requestCleanCapture(displayId)
                }
                throw e
            }
        }
    }

    private suspend fun processCleanFrame(raw: Bitmap) {
        if (!service.isConfigured) { raw.recycle(); return }

        try {
            // Shared OCR pipeline: crop → blackout icon → OCR → filter source chars
            val pipeline = service.runOcr(raw, displayId)

            if (pipeline == null) {
                cachedFuriganaBoxes = null
                service.handleNoTextDetected(displayId)
                return
            }

            val (ocrResult, dedupKey, left, top, _, _) = pipeline

            // Dedup: if text unchanged and we have cached furigana, re-show
            if (lastOcrText != null && !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                val boxes = cachedFuriganaBoxes
                if (boxes != null) {
                    service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH, displayId = displayId)
                    if (cleanRefBitmap == null) {
                        cleanRefBitmap = raw.copy(raw.config, true)
                    }
                    return
                }
            }

            lastOcrText = dedupKey

            // Build and show furigana (grouped for selective invalidation)
            val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
            furiganaGroups = OverlayToolkit.buildFuriganaBoxesByGroup(ocrResult, engine, service.furiganaPaint)
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
            cleanRefBitmap = raw.copy(raw.config, true)

            // Save screenshot for Anki + send translation to in-app panel
            val mgr = a11y.screenshotManager
            mgr?.saveToCache(raw)
            val screenshotPath = mgr?.lastCleanPath
            service.translateAndSendToPanel(ocrResult, screenshotPath)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Raw frame handling (OCR-based change detection) ───────────────────
    //
    // View-space rects from getChildScreenRects() are converted to bitmap
    // pixel coordinates via FrameCoordinates.viewToBitmap before indexing
    // the raw/ref bitmaps. At identity scale (our only currently supported
    // case), the conversion is a no-op via reference short-circuit; see
    // FrameCoordinates KDoc for details on the coordinate spaces.

    private fun handleRawFrame(bitmap: Bitmap) {
        if (cleanProcessingJob?.isActive == true || rawOcrJob?.isActive == true) {
            bitmap.recycle()
            return
        }

        val ref = cleanRefBitmap
        val boxes = cachedFuriganaBoxes
        val overlayView = a11y.translationOverlayForDisplay(displayId)
        val screenRects = overlayView?.getChildScreenRects() ?: emptyList()

        if (ref == null || boxes.isNullOrEmpty()) {
            // No overlay exists — raw frame is inherently clean, process it directly
            emptyRectsStallCount = 0
            handleCleanFrame(bitmap)
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
                a11y.hideTranslationOverlayForDisplay(displayId)
                a11y.screenshotManager?.requestCleanCapture(displayId)
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
            a11y.hideTranslationOverlayForDisplay(displayId)
            a11y.screenshotManager?.requestCleanCapture(displayId)
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
            else bitmap.copy(bitmap.config, true).also { bitmap.recycle() }
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

        // OCR the patched frame asynchronously
        rawOcrJob = scope.launch {
            try {
                val pipeline = service.runOcr(patched, displayId)
                if (pipeline != null) {
                    val prevText = lastOcrText
                    val prevKanji = if (prevText != null) kanjiOnly(prevText) else ""
                    val newKanji = kanjiOnly(pipeline.dedupKey)
                    val kanjiChanged = prevText != null && OverlayToolkit.isSignificantChange(prevKanji, newKanji)
                    if (kanjiChanged) {
                        noChangeRawFrameCount = 0
                        DetectionLog.log("Furigana: text changed, requesting clean capture")

                        // Selective invalidation: remove furigana for changed groups, keep the rest
                        val newOcrGroups = pipeline.ocrResult.groupTexts.zip(pipeline.ocrResult.groupBounds)
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
                            a11y.hideTranslationOverlayForDisplay(displayId)
                        }

                        a11y.screenshotManager?.requestCleanCapture(displayId)
                    } else {
                        // No change detected. Periodically force a clean capture to refresh
                        // the ref — stale ref content in overlay regions can mask real scene
                        // changes via bleed-through, creating a self-reinforcing stall.
                        noChangeRawFrameCount++
                        if (noChangeRawFrameCount >= STALE_REF_REFRESH_FRAMES) {
                            noChangeRawFrameCount = 0
                            DetectionLog.log("Furigana: safety-net clean capture (stale ref refresh)")
                            // Force rebuild path in processCleanFrame. Don't clear cleanRefBitmap
                            // here — see race comment above.
                            lastOcrText = null
                            a11y.screenshotManager?.requestCleanCapture(displayId)
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
