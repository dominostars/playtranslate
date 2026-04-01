package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "FuriganaMode"

/**
 * Live furigana overlay mode. Shows hiragana readings above kanji on the
 * game screen. Uses OCR-based change detection: keeps a clean reference
 * bitmap, patches overlay regions from it on raw frames, OCRs the patched
 * image, and compares text to detect game scene changes.
 *
 * Owns ALL its mutable state. When stopped, scope is cancelled and all
 * state (including cleanRefBitmap) is released.
 */
class FuriganaMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanProcessingJob: Job? = null

    // ── Mode-owned state ──────────────────────────────────────────────────

    private var furiganaGroups: List<OverlayToolkit.FuriganaGroup> = emptyList()
    private var cachedFuriganaBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var lastOcrText: String? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        if (mgr == null) {
            DetectionLog.log("ERROR: screenshotManager is null, can't start furigana loop")
            return
        }
        DetectionLog.log("Starting furigana loop on display ${service.gameDisplayId}")
        mgr.requestCleanCapture()
        mgr.startLoop(service.gameDisplayId, service.serviceScope,
            onCleanFrame = ::handleCleanFrame,
            onRawFrame = ::handleRawFrame
        )
    }

    override fun stop() {
        cleanProcessingJob?.cancel()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        scope.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.stopLoop()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    override fun refresh() {
        furiganaGroups = emptyList()
        cachedFuriganaBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        lastOcrText = null
        cleanProcessingJob?.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedFuriganaBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Clean frame handling ──────────────────────────────────────────────

    private fun handleCleanFrame(raw: Bitmap) {
        android.util.Log.d("FuriganaDbg", "CLEAN FRAME received (ocrCount=$rawFrameOcrCount)")
        rawFrameOcrCount = 0
        cleanProcessingJob?.cancel()
        cleanProcessingJob = scope.launch {
            try {
                processCleanFrame(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (service.liveActive) {
                    PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                }
                throw e
            }
        }
    }

    private suspend fun processCleanFrame(raw: Bitmap) {
        if (!service.isConfigured) { raw.recycle(); return }

        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            // Shared OCR pipeline: crop → blackout icon → OCR → filter source chars
            val pipeline = service.runOcr(raw)

            if (pipeline == null) {
                cachedFuriganaBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                service.onLiveNoText?.invoke()
                return
            }

            val (ocrResult, dedupKey, left, top, _, _) = pipeline

            // Dedup: if text unchanged and we have cached furigana, re-show
            if (lastOcrText != null && !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                val boxes = cachedFuriganaBoxes
                if (boxes != null) {
                    android.util.Log.d("FuriganaDbg", "DEDUP HIT: re-showing ${boxes.size} cached boxes")
                    service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH)
                    if (cleanRefBitmap == null) {
                        cleanRefBitmap = raw.copy(raw.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                    }
                    return
                }
                android.util.Log.w("FuriganaDbg", "DEDUP HIT but cachedBoxes=null — falling through to rebuild")
            }

            lastOcrText = dedupKey

            // Build and show furigana (grouped for selective invalidation)
            val dict = DictionaryManager.get(service)
            furiganaGroups = OverlayToolkit.buildFuriganaBoxesByGroup(ocrResult, dict, service.furiganaPaint)
            val furigana = furiganaGroups.flatMap { it.boxes }
            cachedFuriganaBoxes = furigana
            this@FuriganaMode.cropLeft = left
            this@FuriganaMode.cropTop = top
            this@FuriganaMode.screenshotW = raw.width
            this@FuriganaMode.screenshotH = raw.height

            android.util.Log.d("FuriganaDbg", "REBUILD: ${furiganaGroups.size} groups, ${furigana.size} boxes")
            if (furigana.isNotEmpty()) {
                service.showLiveOverlay(furigana, left, top, raw.width, raw.height)
            } else {
                android.util.Log.w("FuriganaDbg", "REBUILD: 0 boxes — nothing to show!")
            }

            // Save clean reference for patching raw frames
            cleanRefBitmap?.recycle()
            cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, false)

            // Save screenshot for Anki + send translation to in-app panel
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            mgr?.saveToCache(raw)
            val screenshotPath = mgr?.lastCleanPath
            android.util.Log.d("FuriganaDbg", "CLEAN: furigana shown, starting translation for panel...")
            service.translateAndSendToPanel(ocrResult, screenshotPath)
            android.util.Log.d("FuriganaDbg", "CLEAN: translation complete, cleanJob finishing")
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Raw frame handling (OCR-based change detection) ───────────────────

    private var rawFrameSkipCount = 0
    private var rawFrameOcrCount = 0

    private fun handleRawFrame(bitmap: Bitmap) {
        if (cleanProcessingJob?.isActive == true) {
            rawFrameSkipCount++
            if (rawFrameSkipCount % 10 == 1) {
                android.util.Log.w("FuriganaDbg", "RAW SKIP: cleanJob active ($rawFrameSkipCount skipped)")
            }
            bitmap.recycle()
            return
        }
        rawFrameSkipCount = 0

        val ref = cleanRefBitmap
        val boxes = cachedFuriganaBoxes
        if (ref == null || boxes.isNullOrEmpty()) {
            android.util.Log.w("FuriganaDbg", "RAW SKIP: ref=${ref != null} boxes=${boxes?.size ?: 0}")
            bitmap.recycle()
            return
        }

        // Patch: copy overlay regions from clean reference into raw frame.
        // Ensure bitmap is mutable for Canvas drawing.
        val patched = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        try {
            val canvas = Canvas(patched)
            for (box in boxes) {
                val srcRect = android.graphics.Rect(
                    box.bounds.left + cropLeft, box.bounds.top + cropTop,
                    box.bounds.right + cropLeft, box.bounds.bottom + cropTop
                )
                srcRect.inset(-4, -4)
                srcRect.intersect(0, 0, ref.width, ref.height)
                canvas.drawBitmap(ref, srcRect, RectF(
                    srcRect.left.toFloat(), srcRect.top.toFloat(),
                    srcRect.right.toFloat(), srcRect.bottom.toFloat()
                ), null)
            }
        } catch (e: Exception) {
            if (!patched.isRecycled) patched.recycle()
            return
        }

        // OCR the patched frame asynchronously (ownership transferred to coroutine)
        rawFrameOcrCount++
        scope.launch {
            try {
                val pipeline = service.runOcr(patched)
                if (pipeline != null) {
                    val prevText = lastOcrText
                    if (prevText != null && OverlayToolkit.isSignificantChange(prevText, pipeline.dedupKey)) {
                        android.util.Log.w("FuriganaDbg", "TEXT CHANGED: \"${prevText.take(40)}\" → \"${pipeline.dedupKey.take(40)}\"")
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
                        android.util.Log.d("FuriganaDbg", "  Kept: ${surviving.map { "\"${it.groupText.take(20)}\"" }}")
                        android.util.Log.d("FuriganaDbg", "  Removed: ${removed.map { "\"${it.groupText.take(20)}\"" }}")
                        // Remove only the changed groups' child views — survivors stay in place
                        val removedBoxes = removed.flatMap { it.boxes }
                        service.removeOverlayBoxes(removedBoxes)

                        furiganaGroups = surviving
                        cachedFuriganaBoxes = surviving.flatMap { it.boxes }.ifEmpty { null }
                        lastOcrText = null  // force full rebuild on next clean frame

                        if (cachedFuriganaBoxes == null) {
                            PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                        }

                        cleanRefBitmap?.recycle()
                        cleanRefBitmap = null
                        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                    }
                } else {
                    android.util.Log.w("FuriganaDbg", "RAW OCR: pipeline null (OCR failed on patched frame)")
                }
            } finally {
                if (!patched.isRecycled) patched.recycle()
            }
        }
    }
}
