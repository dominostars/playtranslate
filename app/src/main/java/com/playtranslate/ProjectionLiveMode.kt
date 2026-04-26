package com.playtranslate

import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polled live mode for Share-Screen (MediaProjection) sessions.
 *
 * The accessibility-mode pinhole/furigana modes rely on
 * [PlayTranslateAccessibilityService]'s input monitoring + per-pixel
 * change detection. Neither is available without the Accessibility
 * permission, so for projection mode we fall back to the simplest
 * possible behavior: re-capture / OCR / translate / re-paint the
 * overlay on a fixed interval, with a content-dedup guard so we don't
 * thrash the overlay when the screen hasn't actually changed.
 *
 * Owns all its mutable state. Renders through the active [OverlayHost]
 * (= [ProjectionOverlayHost] in this mode), so the same translation
 * overlay window the one-shot uses is reused for live updates.
 */
class ProjectionLiveMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null

    private var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
    private var lastOcrText: String? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0

    override fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                if (service.holdActive) {
                    delay(100)
                    continue
                }
                val pipeline = service.runCaptureOcrTranslate()
                if (pipeline != null) {
                    val dedupKey = pipeline.result.originalText
                        .filter { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
                    if (lastOcrText == null ||
                        OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                        lastOcrText = dedupKey
                        val boxes = pipeline.groupBounds.zip(pipeline.groupTranslations)
                            .map { (b, t) -> TranslationOverlayView.TextBox(t, b) }
                        cachedOverlayBoxes = boxes
                        cropLeft = pipeline.cropLeft
                        cropTop = pipeline.cropTop
                        screenshotW = pipeline.screenshotW
                        screenshotH = pipeline.screenshotH
                        service.showLiveOverlay(
                            boxes, cropLeft, cropTop, screenshotW, screenshotH,
                            force = false, pinholeMode = false,
                        )
                        // Mirror in the in-app panel if MainActivity is up.
                        service.onResult?.invoke(pipeline.result)
                    }
                } else {
                    // No text — clear the overlay so a stale translation
                    // doesn't sit forever after the user navigates away
                    // from the OCR'd region.
                    OverlayHost.current?.hideTranslationOverlay()
                    cachedOverlayBoxes = null
                    lastOcrText = null
                    service.onLiveNoText?.invoke()
                }
                delay(Prefs(service).captureIntervalMs)
            }
        }
    }

    override fun stop() {
        pollingJob?.cancel()
        scope.cancel()
        OverlayHost.current?.hideTranslationOverlay()
    }

    override fun refresh() {
        cachedOverlayBoxes = null
        lastOcrText = null
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedOverlayBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }
}
