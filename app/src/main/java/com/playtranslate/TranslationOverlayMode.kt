package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TranslationOverlayMode"

/**
 * Live translation overlay mode. Continuously captures the game screen,
 * translates text, shows color-matched translation overlays. Uses pixel-diff
 * detection (CHECK A/B/C) to detect when game text changes under opaque
 * overlays.
 *
 * Owns ALL its mutable state — detection references, cached boxes, dedup key,
 * pixel buffers. When stopped, scope is cancelled and all state is released.
 */
class TranslationOverlayMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanProcessingJob: Job? = null
    private var interactionDebounceJob: Job? = null
    private var recheckJob: Job? = null

    // ── Detection constants ───────────────────────────────────────────────

    private val SCENE_CHANGE_THRESHOLD = 0.40f
    private val OVERLAY_CHANGE_THRESHOLD = 0.10f
    private val PIXEL_DIFF_THRESHOLD = 30
    private val OVERLAY_PIXEL_DIFF_THRESHOLD = 20
    private val MAX_STABILIZATION_FRAMES = 10

    // ── Mode-owned state ──────────────────────────────────────────────────

    // Overlay caches
    private var cachedOverlayBoxes: List<TranslationOverlayView.TextBox>? = null
    private var lastOcrText: String? = null
    private var cachedOcrResult: OcrManager.OcrResult? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // Detection state
    private var detectionRefNonOverlay: IntArray? = null
    private var detectionRefOverlay: IntArray? = null
    private var detectionOverlayActive: BooleanArray? = null
    private var detectionNonOverlayPositions: List<Pair<Int, Int>> = emptyList()
    private var detectionOverlaySamples: List<OverlaySampleData> = emptyList()
    private var detectionOverlayBoxes: List<Rect> = emptyList()
    private var detectionOverlayTextBoxes: List<TranslationOverlayView.TextBox> = emptyList()
    private var detectionPrevNonOverlay: IntArray? = null
    private var detectionHasPrev = false
    private var stabilizationFrameCount = 0
    private var pixelBuffer: IntArray? = null
    private var sceneMoving = false
    private var forceCheckC = false

    private data class OverlaySampleData(val x: Int, val y: Int, val textColor: Int, val boxIdx: Int)
    private data class OverlayDiffResult(val maxDiff: Float, val triggeredBoxIndices: Set<Int>)

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(service.gameDisplayId) { onUserInteraction() }

        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        if (mgr == null) {
            DetectionLog.log("ERROR: screenshotManager is null, can't start loop")
            return
        }
        DetectionLog.log("Starting loop on display ${service.gameDisplayId}")
        mgr.requestCleanCapture()
        mgr.startLoop(service.gameDisplayId, service.serviceScope,
            onCleanFrame = ::handleCleanFrame,
            onRawFrame = ::handleRawFrame
        )
    }

    override fun stop() {
        cleanProcessingJob?.cancel()
        interactionDebounceJob?.cancel()
        recheckJob?.cancel()
        scope.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.stopLoop()
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        service.setDegraded(false)
    }

    override fun refresh() {
        cachedOverlayBoxes = null
        lastOcrText = null
        cachedOcrResult = null
        clearDetectionState()
        cleanProcessingJob?.cancel()
        interactionDebounceJob?.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedOverlayBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Clean frame handling ──────────────────────────────────────────────

    private fun handleCleanFrame(raw: Bitmap) {
        if (cleanProcessingJob?.isActive == true) {
            // Never cancel in-progress processing for a new frame.
            // Detection will trigger a recapture if the result is stale.
            DetectionLog.log("Clean frame DROPPED — processing in progress")
            raw.recycle()
            return
        }
        DetectionLog.log("Clean frame received")
        cleanProcessingJob = scope.launch {
            processCleanFrame(raw)
        }
    }

    private suspend fun processCleanFrame(raw: Bitmap) {
        if (!service.isConfigured) {
            DetectionLog.log("processClean: not configured, skipping")
            raw.recycle(); return
        }

        var colorRef: Bitmap? = null
        try {
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)

            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            // Shared OCR pipeline: crop → blackout icon → OCR → filter source chars
            val pipeline = service.runOcr(raw)

            if (pipeline == null) {
                DetectionLog.log("processClean: OCR returned null/empty")
                lastOcrText = null
                cachedOverlayBoxes = null
                service.handleNoTextDetected()
                setupDetection(raw, emptyList(), emptyList())
                return
            }

            val (ocrResult, dedupKey, left, top, _, _) = pipeline

            // Dedup
            if (lastOcrText != null && !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                val boxes = cachedOverlayBoxes
                if (boxes != null) {
                    DetectionLog.log("processClean: dedup match, re-showing cached")
                    service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH)
                    setupDetection(raw, boxes.map { b ->
                        Rect(
                            b.bounds.left + cropLeft, b.bounds.top + cropTop,
                            b.bounds.right + cropLeft, b.bounds.bottom + cropTop
                        )
                    }, boxes)
                    return
                }
                DetectionLog.log("processClean: dedup match but no cached boxes, re-translating")
            }
            lastOcrText = dedupKey
            DetectionLog.log("processClean: new text, ${ocrResult.groupTexts.size} groups, translating...")

            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            val screenshotPath = mgr?.saveToCache(raw)

            val liveGroupBounds = ocrResult.groupBounds
            val liveGroupLineCounts = ocrResult.groupLineCounts

            // Shimmer placeholders → translate → show translation overlays
            val cRef = colorRef!!
            val colors = OverlayToolkit.sampleGroupColors(cRef, liveGroupBounds, left, top, colorScale)
            val placeholderBoxes = liveGroupBounds.mapIndexed { idx, bounds ->
                val (bgColor, textColor) = colors[idx]
                val lineCount = liveGroupLineCounts.getOrElse(idx) { 1 }
                TranslationOverlayView.TextBox("", bounds, bgColor, textColor, lineCount)
            }
            service.showLiveOverlay(placeholderBoxes, left, top, raw.width, raw.height)

            // Translate
            service.onTranslationStarted?.invoke()
            val perGroup = service.translateGroupsSeparately(ocrResult.groupTexts)
            val translated = perGroup.joinToString("\n\n") { it.first }
            val note = perGroup.mapNotNull { it.second }.firstOrNull()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            service.onResult?.invoke(
                com.playtranslate.model.TranslationResult(
                    originalText = ocrResult.fullText, segments = ocrResult.segments,
                    translatedText = translated, timestamp = timestamp,
                    screenshotPath = screenshotPath, note = note
                )
            )

            if (liveGroupBounds.size == perGroup.size) {
                val translationBoxes = perGroup.zip(placeholderBoxes).map { (tr, placeholder) ->
                    placeholder.copy(translatedText = tr.first)
                }
                cachedOverlayBoxes = translationBoxes
                cachedOcrResult = ocrResult
                this@TranslationOverlayMode.cropLeft = left
                this@TranslationOverlayMode.cropTop = top
                this@TranslationOverlayMode.screenshotW = raw.width
                this@TranslationOverlayMode.screenshotH = raw.height
                service.showLiveOverlay(translationBoxes, left, top, raw.width, raw.height)
                val fullDisplayBoxes = translationBoxes.map { b ->
                    Rect(b.bounds.left + left, b.bounds.top + top,
                        b.bounds.right + left, b.bounds.bottom + top)
                }
                setupDetection(raw, fullDisplayBoxes, translationBoxes)
                DetectionLog.log("processClean: done, ${translationBoxes.size} translation boxes")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            DetectionLog.log("processClean: cancelled")
            throw e
        } catch (e: Throwable) {
            DetectionLog.log("processClean: ERROR ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "processCleanFrame failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            colorRef?.recycle()
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Raw frame handling (pixel-diff detection) ─────────────────────────

    private fun handleRawFrame(bitmap: Bitmap) {
        if (cleanProcessingJob?.isActive == true) {
            bitmap.recycle()
            return
        }

        val refNonOverlay = detectionRefNonOverlay
        val refOverlay = detectionRefOverlay
        val overlayActive = detectionOverlayActive
        val nonOverlayPositions = detectionNonOverlayPositions
        val overlaySamples = detectionOverlaySamples

        if (refNonOverlay == null || nonOverlayPositions.isEmpty()) {
            bitmap.recycle()
            return
        }

        val w = bitmap.width
        val h = bitmap.height
        val bufSize = w * h
        val allPixels = pixelBuffer?.takeIf { it.size >= bufSize }
            ?: IntArray(bufSize).also { pixelBuffer = it }
        bitmap.getPixels(allPixels, 0, w, 0, 0, w, h)

        // First raw frame: set overlay reference
        if (detectionRefOverlay == null && overlaySamples.isNotEmpty()) {
            val ovrRef = IntArray(overlaySamples.size)
            val ovrActive = BooleanArray(overlaySamples.size)
            for (i in overlaySamples.indices) {
                val sample = overlaySamples[i]
                val px = if (sample.x < w && sample.y < h) allPixels[sample.y * w + sample.x] else 0
                ovrRef[i] = px
                ovrActive[i] = !isColorMatch(px, sample.textColor)
            }
            detectionRefOverlay = ovrRef
            detectionOverlayActive = ovrActive
            val activeCount = ovrActive.count { it }
            val activePct = if (overlaySamples.isNotEmpty()) activeCount * 100 / overlaySamples.size else 0
            DetectionLog.log("Overlay ref set: ${overlaySamples.size} ovr ($activeCount active, $activePct%) — ${if (activePct == 100) "WARNING: no text pixels found, view may not be rendered yet" else "OK"}")
            bitmap.recycle()
            return
        }

        val currentNonOverlay = IntArray(nonOverlayPositions.size)
        for (i in nonOverlayPositions.indices) {
            val (x, y) = nonOverlayPositions[i]
            currentNonOverlay[i] = if (x < w && y < h) allPixels[y * w + x] else 0
        }
        val currentOverlay = IntArray(overlaySamples.size)
        for (i in overlaySamples.indices) {
            val sample = overlaySamples[i]
            currentOverlay[i] = if (sample.x < w && sample.y < h) allPixels[sample.y * w + sample.x] else 0
        }

        if (!sceneMoving) {
            // CHECK A: Non-overlay pixel diff
            val nonOverlayDiff = pixelDiffPercent(refNonOverlay, currentNonOverlay)
            if (nonOverlayDiff >= SCENE_CHANGE_THRESHOLD) {
                DetectionLog.log("A: Scene change (${"%.2f".format(nonOverlayDiff*100)}%)")
                bitmap.recycle()
                sceneMoving = true
                detectionHasPrev = false
                stabilizationFrameCount = 0
                lastOcrText = null
                cachedOverlayBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                return
            }

            // CHECK B: Per-box overlay diff
            val overlayResult = if (refOverlay != null && overlayActive != null)
                overlayDiffPerBox(overlaySamples, refOverlay, currentOverlay, overlayActive)
            else OverlayDiffResult(0f, emptySet())
            val overlayDiff = overlayResult.maxDiff
            if (overlayResult.triggeredBoxIndices.isNotEmpty()) {
                val allBoxes = detectionOverlayTextBoxes.ifEmpty { null }
                val fullBoxes = detectionOverlayBoxes
                if (allBoxes != null && fullBoxes.isNotEmpty()) {
                    val toRemove = findNearbyBoxIndices(overlayResult.triggeredBoxIndices, fullBoxes)
                    DetectionLog.log("B: Removing ${toRemove.size}/${allBoxes.size} overlays (triggered=${overlayResult.triggeredBoxIndices})")

                    val remainingBoxes = allBoxes.filterIndexed { i, _ -> i !in toRemove }
                    val remainingFullBoxes = fullBoxes.filterIndexed { i, _ -> i !in toRemove }

                    cachedOverlayBoxes = remainingBoxes.ifEmpty { null }
                    detectionOverlayTextBoxes = remainingBoxes
                    detectionOverlayBoxes = remainingFullBoxes
                    lastOcrText = null

                    if (remainingBoxes.isNotEmpty()) {
                        service.showLiveOverlay(remainingBoxes, cropLeft, cropTop, screenshotW, screenshotH)
                    } else {
                        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                    }

                    forceCheckC = true
                    detectionRefOverlay = null
                    detectionOverlayActive = null
                    detectionOverlaySamples = detectionOverlaySamples.filter { it.boxIdx !in toRemove }

                    bitmap.recycle()
                    return
                }
            }

            // CHECK C
            val anyChange = forceCheckC || nonOverlayDiff > 0.005f || overlayDiff > 0.005f
            if (forceCheckC) forceCheckC = false
            if (anyChange) {
                DetectionLog.log("C: Change (non=${"%.2f".format(nonOverlayDiff*100)}% ovr=${"%.2f".format(overlayDiff*100)}%)")
                if (detectionOverlayBoxes.isEmpty()) {
                    DetectionLog.log("C: No overlays → raw as clean")
                    handleCleanFrame(bitmap)
                    return
                }
                val overlayBoxesCopy = detectionOverlayBoxes.toList()
                recheckJob?.cancel()
                recheckJob = scope.launch {
                    val triggered = performOcrRecheck(bitmap, overlayBoxesCopy)
                    if (triggered) {
                        DetectionLog.log("D: New text → recapture/merge")
                    }
                }
            } else {
                bitmap.recycle()
            }
        } else {
            // Stabilization
            stabilizationFrameCount++
            val prevNonOverlay = detectionPrevNonOverlay
            if (detectionHasPrev && prevNonOverlay != null) {
                val pct = pixelDiffPercent(prevNonOverlay, currentNonOverlay)
                if (pct < SCENE_CHANGE_THRESHOLD) {
                    DetectionLog.log("Stabilized (${"%.2f".format(pct*100)}%) frame $stabilizationFrameCount → clean as raw")
                    sceneMoving = false
                    handleCleanFrame(bitmap)
                    return
                }
                if (stabilizationFrameCount >= MAX_STABILIZATION_FRAMES) {
                    DetectionLog.log("Stabilization timeout (${"%.2f".format(pct*100)}%) after $stabilizationFrameCount frames → forcing clean")
                    sceneMoving = false
                    handleCleanFrame(bitmap)
                    return
                }
                DetectionLog.log("Waiting to stabilize (${"%.2f".format(pct*100)}%) frame $stabilizationFrameCount/$MAX_STABILIZATION_FRAMES")
            }
            detectionPrevNonOverlay = currentNonOverlay
            detectionHasPrev = true
            bitmap.recycle()
        }
    }

    // ── Detection setup ───────────────────────────────────────────────────

    private fun setupDetection(
        cleanRef: Bitmap,
        fullDisplayBoxes: List<Rect>,
        textBoxes: List<TranslationOverlayView.TextBox>
    ) {
        val regionTop = (cleanRef.height * service.activeRegion.top).toInt()
        val regionBottom = (cleanRef.height * service.activeRegion.bottom).toInt()
        val regionLeft = (cleanRef.width * service.activeRegion.left).toInt()
        val regionRight = (cleanRef.width * service.activeRegion.right).toInt()

        val nonOvrPos = mutableListOf<Pair<Int, Int>>()
        val ovrSamples = mutableListOf<OverlaySampleData>()

        for (y in regionTop until regionBottom step 10) {
            for (x in regionLeft until regionRight step 10) {
                if (fullDisplayBoxes.none { it.contains(x, y) }) {
                    nonOvrPos.add(x to y)
                }
            }
        }
        for ((boxIdx, box) in fullDisplayBoxes.withIndex()) {
            val textColor = textBoxes.getOrNull(boxIdx)?.textColor ?: 0
            val bTop = box.top.coerceIn(regionTop, regionBottom)
            val bBottom = box.bottom.coerceIn(regionTop, regionBottom)
            val bLeft = box.left.coerceIn(regionLeft, regionRight)
            val bRight = box.right.coerceIn(regionLeft, regionRight)
            for (y in bTop until bBottom step 3) {
                for (x in bLeft until bRight step 3) {
                    ovrSamples.add(OverlaySampleData(x, y, textColor, boxIdx))
                }
            }
        }

        val refW = cleanRef.width
        val refH = cleanRef.height
        val refPixels = pixelBuffer?.takeIf { it.size >= refW * refH }
            ?: IntArray(refW * refH).also { pixelBuffer = it }
        cleanRef.getPixels(refPixels, 0, refW, 0, 0, refW, refH)

        detectionRefNonOverlay = IntArray(nonOvrPos.size) { i ->
            val (x, y) = nonOvrPos[i]
            if (x < refW && y < refH) refPixels[y * refW + x] else 0
        }
        detectionNonOverlayPositions = nonOvrPos
        detectionOverlaySamples = ovrSamples
        detectionOverlayBoxes = fullDisplayBoxes
        detectionOverlayTextBoxes = textBoxes

        detectionRefOverlay = null
        detectionOverlayActive = null
        detectionHasPrev = false
        detectionPrevNonOverlay = null
        sceneMoving = false

        DetectionLog.log("Detection setup: ${nonOvrPos.size} non-ovr (clean), ${ovrSamples.size} ovr (waiting for raw ref)")
    }

    private fun clearDetectionState() {
        detectionRefNonOverlay = null
        detectionRefOverlay = null
        detectionOverlayActive = null
        detectionNonOverlayPositions = emptyList()
        detectionOverlaySamples = emptyList()
        detectionOverlayBoxes = emptyList()
        detectionOverlayTextBoxes = emptyList()
        detectionPrevNonOverlay = null
        detectionHasPrev = false
        stabilizationFrameCount = 0
        sceneMoving = false
        forceCheckC = false
    }

    // ── Input monitoring ──────────────────────────────────────────────────

    private fun onUserInteraction() {
        if (!service.liveActive) return
        DetectionLog.log("USER INTERACTION — cancelling processing")
        cleanProcessingJob?.cancel()
        cachedOverlayBoxes = null
        lastOcrText = null
        clearDetectionState()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()

        interactionDebounceJob?.cancel()
        interactionDebounceJob = scope.launch {
            val settleMs = Prefs(service).captureIntervalMs
            kotlinx.coroutines.delay(settleMs)
            while (PlayTranslateAccessibilityService.instance?.isInputActive == true) {
                kotlinx.coroutines.delay(settleMs)
            }
            PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
        }
    }

    // ── Detection helpers ─────────────────────────────────────────────────

    private fun pixelDiffPercent(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var changed = 0
        for (i in a.indices) {
            val dr = kotlin.math.abs(Color.red(a[i]) - Color.red(b[i]))
            val dg = kotlin.math.abs(Color.green(a[i]) - Color.green(b[i]))
            val db = kotlin.math.abs(Color.blue(a[i]) - Color.blue(b[i]))
            if (dr > PIXEL_DIFF_THRESHOLD || dg > PIXEL_DIFF_THRESHOLD || db > PIXEL_DIFF_THRESHOLD) {
                changed++
            }
        }
        return changed.toFloat() / a.size
    }

    private fun overlayDiffPerBox(
        samples: List<OverlaySampleData>,
        refPixels: IntArray,
        curPixels: IntArray,
        active: BooleanArray
    ): OverlayDiffResult {
        if (refPixels.isEmpty()) return OverlayDiffResult(0f, emptySet())
        val boxChanged = mutableMapOf<Int, Int>()
        val boxCounted = mutableMapOf<Int, Int>()
        for (i in refPixels.indices) {
            if (!active[i]) continue
            val boxIdx = samples[i].boxIdx
            boxCounted[boxIdx] = (boxCounted[boxIdx] ?: 0) + 1
            val dr = kotlin.math.abs(Color.red(refPixels[i]) - Color.red(curPixels[i]))
            val dg = kotlin.math.abs(Color.green(refPixels[i]) - Color.green(curPixels[i]))
            val db = kotlin.math.abs(Color.blue(refPixels[i]) - Color.blue(curPixels[i]))
            if (dr > OVERLAY_PIXEL_DIFF_THRESHOLD || dg > OVERLAY_PIXEL_DIFF_THRESHOLD || db > OVERLAY_PIXEL_DIFF_THRESHOLD) {
                boxChanged[boxIdx] = (boxChanged[boxIdx] ?: 0) + 1
            }
        }
        val triggered = mutableSetOf<Int>()
        var maxDiff = 0f
        for ((idx, count) in boxCounted) {
            if (count > 0) {
                val diff = (boxChanged[idx] ?: 0).toFloat() / count
                if (diff > maxDiff) maxDiff = diff
                if (diff >= OVERLAY_CHANGE_THRESHOLD) triggered.add(idx)
            }
        }
        return OverlayDiffResult(maxDiff, triggered)
    }

    private fun findNearbyBoxIndices(
        triggeredIndices: Set<Int>,
        fullDisplayBoxes: List<Rect>
    ): Set<Int> {
        val toRemove = triggeredIndices.toMutableSet()
        for (trigIdx in triggeredIndices) {
            if (trigIdx >= fullDisplayBoxes.size) continue
            for (otherIdx in fullDisplayBoxes.indices) {
                if (otherIdx in toRemove) continue
                if (areRectsNearby(fullDisplayBoxes[trigIdx], fullDisplayBoxes[otherIdx])) {
                    toRemove.add(otherIdx)
                }
            }
        }
        return toRemove
    }

    private fun isColorMatch(pixel: Int, color: Int): Boolean {
        val dr = kotlin.math.abs(Color.red(pixel) - Color.red(color))
        val dg = kotlin.math.abs(Color.green(pixel) - Color.green(color))
        val db = kotlin.math.abs(Color.blue(pixel) - Color.blue(color))
        return dr <= PIXEL_DIFF_THRESHOLD && dg <= PIXEL_DIFF_THRESHOLD && db <= PIXEL_DIFF_THRESHOLD
    }

    private fun areRectsNearby(a: Rect, b: Rect): Boolean {
        val dx = maxOf(0, maxOf(a.left - b.right, b.left - a.right))
        val dy = maxOf(0, maxOf(a.top - b.bottom, b.top - a.bottom))
        val refHeight = maxOf(a.height(), b.height())
        val threshold = maxOf((refHeight * 1.5f).toInt(), OverlayToolkit.FILL_PADDING + 15)
        return dx < threshold && dy < threshold
    }

    // ── OCR recheck (CHECK C/D) ──────────────────────────────────────────

    private suspend fun performOcrRecheck(
        rawBitmap: Bitmap,
        overlayBoxes: List<Rect>
    ): Boolean {
        val overlays = cachedOverlayBoxes ?: detectionOverlayTextBoxes.ifEmpty { null }
        if (overlays == null) { rawBitmap.recycle(); return false }
        // Ensure bitmap is mutable for Canvas drawing (raw frames may be immutable)
        val bitmap = if (rawBitmap.isMutable) rawBitmap
            else rawBitmap.copy(rawBitmap.config, true)
                .also { rawBitmap.recycle() }
        var colorRef: Bitmap? = null

        try {
            val colorScale = 4
            colorRef = Bitmap.createScaledBitmap(bitmap, bitmap.width / colorScale, bitmap.height / colorScale, false)

            val fillPadding = OverlayToolkit.FILL_PADDING
            val fillPaint = Paint()
            val fillCanvas = Canvas(bitmap)
            for (box in overlays) {
                val l = (box.bounds.left + cropLeft - fillPadding).coerceAtLeast(0)
                val t = (box.bounds.top + cropTop - fillPadding).coerceAtLeast(0)
                val r = (box.bounds.right + cropLeft + fillPadding).coerceAtMost(bitmap.width)
                val b = (box.bounds.bottom + cropTop + fillPadding).coerceAtMost(bitmap.height)
                fillPaint.color = box.bgColor or (0xFF shl 24)
                fillCanvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), fillPaint)
            }

            // OCR the filled bitmap using shared pipeline (crop → blackout → OCR)
            val pipeline = service.runOcr(bitmap)
            if (pipeline == null) return false

            val (ocrResult, newDedupKey, left, top, _, _) = pipeline
            val prevText = lastOcrText
            if (prevText != null && !OverlayToolkit.hasSignificantAdditions(prevText, newDedupKey)) return false

            // Find which existing overlays are near any new text and remove them
            // Find which existing overlays are near any new text
            val nearbyIndices = mutableSetOf<Int>()
            for (newRect in ocrResult.groupBounds) {
                val newFullRect = Rect(newRect.left + left, newRect.top + top,
                    newRect.right + left, newRect.bottom + top)
                for ((idx, existing) in overlays.withIndex()) {
                    val existingFullRect = Rect(
                        existing.bounds.left + cropLeft, existing.bounds.top + cropTop,
                        existing.bounds.right + cropLeft, existing.bounds.bottom + cropTop)
                    if (areRectsNearby(existingFullRect, newFullRect)) {
                        nearbyIndices.add(idx)
                    }
                }
            }

            if (nearbyIndices.isNotEmpty()) {
                // New text near existing overlays (typewriter text, partial sentence growing).
                // Remove the nearby overlays and recapture clean to get the full sentence.
                val remaining = overlays.filterIndexed { i, _ -> i !in nearbyIndices }
                DetectionLog.log("D: Removing ${nearbyIndices.size}/${overlays.size} nearby overlays, recapturing")
                cachedOverlayBoxes = remaining.ifEmpty { null }
                lastOcrText = null
                clearDetectionState()
                if (remaining.isNotEmpty()) {
                    service.showLiveOverlay(remaining, cropLeft, cropTop, screenshotW, screenshotH)
                } else {
                    PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                }
                PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                return true
            }

            // New text far from existing overlays — translate and merge
            val newGroupTexts = ocrResult.groupTexts.filter { text ->
                text.any { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
            }
            if (newGroupTexts.isEmpty()) return false

            val newGroupBounds = ocrResult.groupBounds.filterIndexed { i, _ ->
                i < ocrResult.groupTexts.size &&
                    ocrResult.groupTexts[i].any { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
            }

            val perGroup = service.translateGroupsSeparately(newGroupTexts)
            val cRef = colorRef

            val newOverlayBoxes = if (newGroupBounds.size == perGroup.size) {
                val colors = OverlayToolkit.sampleGroupColors(cRef, newGroupBounds, left, top, colorScale)
                perGroup.indices.map { idx ->
                    val (bg, tc) = colors.getOrElse(idx) { Pair(android.graphics.Color.argb(200,0,0,0), android.graphics.Color.WHITE) }
                    TranslationOverlayView.TextBox(perGroup[idx].first, newGroupBounds[idx], bg, tc)
                }
            } else emptyList()

            if (newOverlayBoxes.isNotEmpty()) {
                val merged = overlays + newOverlayBoxes
                cachedOverlayBoxes = merged
                lastOcrText = (prevText ?: "") + newDedupKey
                service.showLiveOverlay(merged, cropLeft, cropTop, screenshotW, screenshotH)
                forceCheckC = true
                detectionRefOverlay = null
                detectionOverlayActive = null
                return true
            }
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "OCR re-check failed: ${e.message}")
            return false
        } finally {
            colorRef?.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
