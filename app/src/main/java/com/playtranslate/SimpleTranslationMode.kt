package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color
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
 * Simple polling translation overlay mode. Each cycle: capture a clean
 * screenshot, OCR, translate, show overlays. No pixel-diff detection,
 * no stabilization, no partial updates. Dedup prevents re-translating
 * identical text across cycles.
 */
class SimpleTranslationMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollingJob: Job? = null

    private var cachedBoxes: List<TranslationOverlayView.TextBox>? = null
    private var lastOcrText: String? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    override fun start() {
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(service.gameDisplayId) { onButtonDown() }

        pollingJob = scope.launch {
            while (isActive) {
                if (!service.holdActive) {
                    runCycle()
                }
                delay(Prefs(service).captureIntervalMs)
            }
        }
    }

    override fun stop() {
        pollingJob?.cancel()
        scope.cancel()
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    private fun onButtonDown() {
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        // Clear dedup so next cycle re-captures fresh
        lastOcrText = null
    }

    override fun refresh() {
        cachedBoxes = null
        lastOcrText = null
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private suspend fun runCycle() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager ?: return

        val raw = mgr.requestClean(service.gameDisplayId) ?: return

        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            val pipeline = service.runOcr(raw)
            if (pipeline == null) {
                service.handleNoTextDetected()
                return
            }

            val (ocrResult, dedupKey, left, top, sw, sh) = pipeline

            // Dedup: skip translation if text unchanged
            if (lastOcrText != null && !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                cachedBoxes?.let { service.showLiveOverlay(it, cropLeft, cropTop, screenshotW, screenshotH) }
                return
            }
            lastOcrText = dedupKey

            // Translate
            val perGroup = service.translateGroupsSeparately(ocrResult.groupTexts)

            // Color sample + build boxes
            val colorScale = 4
            val colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)
            val colors: List<Pair<Int, Int>>
            try {
                colors = OverlayToolkit.sampleGroupColors(colorRef, ocrResult.groupBounds, left, top, colorScale)
            } finally {
                colorRef.recycle()
            }

            val boxes = if (ocrResult.groupBounds.size == perGroup.size) {
                perGroup.mapIndexed { idx, (text, _) ->
                    val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(200, 0, 0, 0), Color.WHITE) }
                    TranslationOverlayView.TextBox(text, ocrResult.groupBounds[idx], bg, tc)
                }
            } else emptyList()

            if (boxes.isNotEmpty()) {
                cachedBoxes = boxes
                cropLeft = left; cropTop = top; screenshotW = sw; screenshotH = sh
                service.showLiveOverlay(boxes, left, top, sw, sh)
            }

            // Send to in-app panel
            PlayTranslateAccessibilityService.instance?.screenshotManager?.saveToCache(raw)
            val screenshotPath = PlayTranslateAccessibilityService.instance?.screenshotManager?.lastCleanPath
            service.translateAndSendToPanel(ocrResult, screenshotPath)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }
}
