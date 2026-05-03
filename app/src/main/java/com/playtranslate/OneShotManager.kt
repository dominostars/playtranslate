package com.playtranslate

import android.graphics.Bitmap
import com.playtranslate.language.SourceLanguageEngines
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
    /** The display the current cycle is targeting. Defaults to the
     *  primary game display; the icon long-press path passes the icon's
     *  own displayId so taps on icon B translate display B, not the
     *  primary. */
    private var targetDisplayId: Int = android.view.Display.DEFAULT_DISPLAY

    /** Start a one-shot capture cycle on [displayId]. Cancels any previous
     *  in-flight cycle. */
    fun runHoldOverlay(
        forceMode: OverlayMode? = null,
        displayId: Int = service.primaryGameDisplayId(),
    ) {
        forcedMode = forceMode
        targetDisplayId = displayId
        currentJob?.cancel()
        currentJob = service.serviceScope.launch {
            runCycle()
        }
    }

    /** Cancel the current cycle and hide the overlay it targeted. */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlayForDisplay(targetDisplayId)
    }


    private suspend fun runCycle() {
        if (!service.isConfigured) return
        val displayId = targetDisplayId

        // 1. Capture clean screenshot
        val raw: Bitmap = service.captureScreen(displayId) ?: return

        try {
            // 2. Flash region indicator
            service.flashRegionIndicator(displayId)

            // 3. OCR via shared pipeline
            val pipeline = service.runOcr(raw, displayId)
            if (pipeline == null) {
                service.emitHoldLoading(false)
                showNoTextPill(displayId)
                service.emitLiveNoText()
                return
            }

            val (ocrResult, _, cropLeft, cropTop, screenshotW, screenshotH) = pipeline

            // 4. Save screenshot for Anki — per-display filename so a
            //    concurrent live cycle on another display can't clobber it.
            val screenshotPath = PlayTranslateAccessibilityService.instance
                ?.screenshotManager?.saveToCache(raw, displayId)

            // 5. Build boxes via processor (factory decides furigana vs translation)
            val processor = createProcessor()
            val boxes = processor.buildBoxes(ocrResult, raw, cropLeft, cropTop, screenshotW, screenshotH) {
                // Callback for intermediate display (shimmer placeholders)
                service.showLiveOverlay(it, cropLeft, cropTop, screenshotW, screenshotH, force = true, displayId = displayId)
            }

            // 6. Show final overlay
            if (boxes.isNotEmpty()) {
                service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH, force = true, displayId = displayId)
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
            OverlayMode.FURIGANA -> {
                val engine = SourceLanguageEngines.get(service, Prefs(service).sourceLangId)
                FuriganaOneShotProcessor(engine, service.furiganaPaint)
            }
            OverlayMode.TRANSLATION -> TranslationOneShotProcessor(
                service::translateGroupsSeparately
            )
        }
    }

    private fun showNoTextPill(displayId: Int) {
        val a11y = PlayTranslateAccessibilityService.instance
        val dm = service.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm?.getDisplay(displayId)
        if (a11y != null && display != null) {
            a11y.showNoTextPill(display, service.noTextMessage(displayId))
        }
    }
}
