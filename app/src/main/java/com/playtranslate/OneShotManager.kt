package com.playtranslate

import android.graphics.Bitmap
import com.playtranslate.dictionary.DictionaryManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages one-shot hold-to-preview captures. Stateless — every hold is a fresh
 * capture with no dedup or caching. Delegates box building to [OneShotProcessor]
 * implementations (furigana vs translation) via factory.
 */
class OneShotManager(private val service: CaptureService) {

    private var currentJob: Job? = null
    private var forcedMode: OverlayMode? = null

    /** Start a one-shot capture cycle. Cancels any previous in-flight cycle. */
    fun runHoldOverlay(forceMode: OverlayMode? = null) {
        forcedMode = forceMode
        currentJob?.cancel()
        currentJob = service.serviceScope.launch {
            runCycle()
        }
    }

    /** Cancel the current cycle and hide overlays. */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }


    private suspend fun runCycle() {
        if (!service.isConfigured) return

        // 1. Capture clean screenshot
        val raw: Bitmap = service.captureScreen(service.gameDisplayId) ?: return

        try {
            // 2. Flash region indicator
            service.flashRegionIndicator()

            // 3. OCR via shared pipeline
            val pipeline = service.runOcr(raw)
            if (pipeline == null) {
                service.onHoldLoadingChanged?.invoke(false)
                showNoTextPill()
                service.onLiveNoText?.invoke()
                return
            }

            val (ocrResult, _, cropLeft, cropTop, screenshotW, screenshotH) = pipeline

            // 4. Save screenshot for Anki
            PlayTranslateAccessibilityService.instance?.screenshotManager?.saveToCache(raw)
            val screenshotPath = PlayTranslateAccessibilityService.instance?.screenshotManager?.lastCleanPath

            // 5. Build boxes via processor (factory decides furigana vs translation)
            val processor = createProcessor()
            val boxes = processor.buildBoxes(ocrResult, raw, cropLeft, cropTop, screenshotW, screenshotH) {
                // Callback for intermediate display (shimmer placeholders)
                service.showLiveOverlay(it, cropLeft, cropTop, screenshotW, screenshotH, force = true)
            }

            // 6. Show final overlay
            if (boxes.isNotEmpty()) {
                service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH, force = true)
            }

            // 7. Send translation to in-app panel (if visible)
            service.translateAndSendToPanel(ocrResult, screenshotPath)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun createProcessor(): OneShotProcessor {
        val mode = forcedMode ?: Prefs(service).overlayMode
        return when (mode) {
            OverlayMode.FURIGANA -> FuriganaOneShotProcessor(
                DictionaryManager.get(service),
                service.furiganaPaint
            )
            OverlayMode.TRANSLATION -> TranslationOneShotProcessor(
                service::translateGroupsSeparately
            )
        }
    }

    private fun showNoTextPill() {
        val a11y = PlayTranslateAccessibilityService.instance
        val dm = service.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm?.getDisplay(service.gameDisplayId)
        if (a11y != null && display != null) {
            a11y.showNoTextPill(display, service.noTextMessage())
        }
    }
}
