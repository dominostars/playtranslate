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
 * In-App Only live mode. Shows results only in the app panel (bottom screen).
 * Polls at a configured interval. Caches overlay boxes for hold-to-preview.
 *
 * Owns ALL its mutable state. No detection loop — text change is detected
 * via dedup comparison on each poll cycle.
 */
class InAppOnlyMode(
    private val service: CaptureService,
    private val displayId: Int,
) : LiveMode {

    override val flavor: OverlayFlavor = OverlayFlavor.IN_APP_ONLY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null

    // ── Mode-owned state ──────────────────────────────────────────────────

    private var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
    private var lastOcrText: String? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                // Pause polling while hold-to-preview is active
                if (service.holdActive) {
                    delay(100)
                    continue
                }

                when (val outcome = service.runCaptureOcrTranslate(displayId)) {
                    is CaptureService.PipelineOutcome.Success -> {
                        val pipeline = outcome.pipeline
                        val dedupKey = pipeline.result.originalText
                            .filter { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
                        if (lastOcrText != null &&
                            !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                            delay(Prefs(service).captureIntervalMs)
                            continue
                        }
                        lastOcrText = dedupKey
                        service.emitResult(pipeline.result)
                        cacheOverlayData(pipeline)
                    }
                    CaptureService.PipelineOutcome.NoText -> service.emitLiveNoText()
                    is CaptureService.PipelineOutcome.Failed -> {
                        // Failed in live mode: surface the error like before
                        // (runCaptureOcrTranslate used to emit to _errors itself)
                        // and treat the cycle as a no-text outcome so the loop
                        // continues without a stale result hanging on screen.
                        service.emitError(outcome.message)
                        service.emitLiveNoText()
                    }
                }
                delay(Prefs(service).captureIntervalMs)
            }
        }
    }

    override fun stop() {
        pollingJob?.cancel()
        scope.cancel()
    }

    override fun refresh() {
        cachedOverlayBoxes = null
        lastOcrText = null
        // Next poll cycle re-captures naturally (dedup cleared)
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedOverlayBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun cacheOverlayData(pipeline: CaptureService.PipelineResult) {
        cropLeft = pipeline.cropLeft
        cropTop = pipeline.cropTop
        screenshotW = pipeline.screenshotW
        screenshotH = pipeline.screenshotH
        cachedOverlayBoxes = pipeline.groupBounds.zip(pipeline.groupTranslations)
            .map { (bounds, text) -> TranslationOverlayView.TextBox(text, bounds) }
    }
}
