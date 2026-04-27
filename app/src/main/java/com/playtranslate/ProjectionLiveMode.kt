package com.playtranslate

import android.graphics.Bitmap
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var forceCleanCapture = true
    private var showRegionFlash = true
    private var consecutiveNoText = 0
    private var lastRawSample: IntArray? = null

    override fun start() {
        pollingJob = scope.launch {
            while (isActive) {
                if (service.holdActive) {
                    delay(100)
                    continue
                }

                if (forceCleanCapture || cachedOverlayBoxes == null) {
                    processCleanCapture()
                } else {
                    val changed = hasRawSceneChanged()
                    if (changed) processCleanCapture()
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
        lastRawSample = null
        consecutiveNoText = 0
        showRegionFlash = true
        forceCleanCapture = true
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedOverlayBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private suspend fun processCleanCapture() {
        forceCleanCapture = false
        val raw = service.captureScreen(service.gameDisplayId) ?: return
        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            val pipeline = service.runOcr(raw)
            if (pipeline == null) {
                handleNoText()
                return
            }

            val (ocrResult, dedupKey, left, top, sw, sh) = pipeline
            consecutiveNoText = 0

            val oldText = lastOcrText
            if (oldText != null &&
                !OverlayToolkit.isSignificantChange(oldText, dedupKey) &&
                cachedOverlayBoxes != null) {
                return
            }

            lastOcrText = dedupKey
            cropLeft = left
            cropTop = top
            screenshotW = sw
            screenshotH = sh

            val screenshotPath = service.currentScreenshotProvider()?.saveToCache(raw)
            val colorScale = 4
            val colorRef = Bitmap.createScaledBitmap(
                raw,
                (raw.width / colorScale).coerceAtLeast(1),
                (raw.height / colorScale).coerceAtLeast(1),
                false,
            )
            val colors = try {
                OverlayToolkit.sampleGroupColors(colorRef, ocrResult.groupBounds, left, top, colorScale)
            } finally {
                colorRef.recycle()
            }

            val placeholders = ocrResult.groupBounds.mapIndexed { idx, bounds ->
                val (bgColor, textColor) = colors.getOrElse(idx) {
                    android.graphics.Color.argb(224, 0, 0, 0) to android.graphics.Color.WHITE
                }
                val lineCount = ocrResult.groupLineCounts.getOrElse(idx) { 1 }
                val orientation = ocrResult.groupOrientations.getOrElse(idx) {
                    com.playtranslate.language.TextOrientation.HORIZONTAL
                }
                val sourceText = ocrResult.groupTexts.getOrElse(idx) { "" }
                TranslationOverlayView.TextBox(
                    translatedText = "",
                    bounds = bounds,
                    bgColor = bgColor,
                    textColor = textColor,
                    lineCount = lineCount,
                    sourceText = sourceText,
                    orientation = orientation,
                )
            }
            service.showLiveOverlay(placeholders, left, top, sw, sh)

            service.onTranslationStarted?.invoke()
            val perGroup = service.translateGroupsSeparately(ocrResult.groupTexts)
            if (perGroup.size != placeholders.size) return

            val boxes = perGroup.zip(placeholders).map { (translation, placeholder) ->
                placeholder.copy(translatedText = translation.first)
            }
            cachedOverlayBoxes = boxes
            service.showLiveOverlay(boxes, left, top, sw, sh)

            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            service.onResult?.invoke(
                com.playtranslate.model.TranslationResult(
                    originalText = ocrResult.fullText,
                    segments = ocrResult.segments,
                    translatedText = translated,
                    timestamp = timestamp,
                    screenshotPath = screenshotPath,
                    note = note,
                )
            )

            lastRawSample = null
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun handleNoText() {
        consecutiveNoText++
        if (cachedOverlayBoxes == null || consecutiveNoText >= 2) {
            OverlayHost.current?.hideTranslationOverlay()
            cachedOverlayBoxes = null
            lastOcrText = null
            service.onLiveNoText?.invoke()
        }
    }

    private suspend fun hasRawSceneChanged(): Boolean {
        val raw = service.currentScreenshotProvider()
            ?.requestRaw(service.gameDisplayId)
            ?: return true
        return try {
            val sample = sampleFrame(raw)
            val previous = lastRawSample
            lastRawSample = sample
            previous != null && samplesDiffer(previous, sample)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun sampleFrame(bitmap: Bitmap): IntArray {
        val cols = 12
        val rows = 8
        val values = IntArray(cols * rows)
        var i = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (col in 0 until cols) {
                val x = ((col + 0.5f) * bitmap.width / cols).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                values[i++] = (
                    0.299f * android.graphics.Color.red(pixel) +
                        0.587f * android.graphics.Color.green(pixel) +
                        0.114f * android.graphics.Color.blue(pixel)
                    ).toInt()
            }
        }
        return values
    }

    private fun samplesDiffer(previous: IntArray, current: IntArray): Boolean {
        var changed = 0
        var totalDiff = 0
        for (i in previous.indices) {
            val diff = kotlin.math.abs(previous[i] - current[i])
            totalDiff += diff
            if (diff > 25) changed++
        }
        return changed >= 4 && totalDiff / previous.size > 8
    }
}
