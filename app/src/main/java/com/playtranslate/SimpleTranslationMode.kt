package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.Choreographer
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
class SimpleTranslationMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mainJob: Job? = null

    // State
    private var cachedBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var overlayScreenRects: List<Rect>? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // Translation cache: source text → translated text
    private val translationCache = mutableMapOf<String, String>()

    /** Per-channel delta threshold for pinhole pixel change detection. */
    private val SPLATTER_THRESHOLD = 80

    /** Fraction of pinholes in an overlay that must change to trigger removal. */
    private val PINHOLE_CHANGE_PCT = 0.10f

    override fun start() {
        // TODO: temporarily disabled for change detection testing
        // PlayTranslateAccessibilityService.instance
        //     ?.startInputMonitoring(service.gameDisplayId) { onButtonDown() }

        mainJob = scope.launch {
            while (isActive) {
                if (service.holdActive) { delay(100); continue }
                runCycle()
            }
        }
    }

    override fun stop() {
        mainJob?.cancel()
        scope.cancel()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    override fun refresh() {
        cachedBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private fun onButtonDown() {
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        cachedBoxes = null
    }

    // ── Unified Cycle ───────────────────────────────────────────────────

    private fun hasOverlays(): Boolean = cachedBoxes != null

    private suspend fun runCycle() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager ?: return
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val prefs = Prefs(service)
        DetectionLog.log("CYCLE: start hasOverlays=${hasOverlays()} cachedBoxes=${cachedBoxes?.size}")

        // 1. Capture raw screenshot (overlays visible with parent-level pinholes)
        val raw = mgr.requestRaw(service.gameDisplayId)
        if (raw == null) {
            delay(prefs.captureIntervalMs)
            return
        }

        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            // 2. Update clean ref: copy non-overlay pixels from raw
            val ref = cleanRefBitmap
            if (!hasOverlays() || ref == null) {
                cleanRefBitmap?.recycle()
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
            } else {
                updateCleanRef(raw, ref)
            }

            // 3. Prepare OCR image: fill overlay regions with bgColor
            val ocrImage: Bitmap
            if (hasOverlays()) {
                ocrImage = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
                fillOverlayRegions(ocrImage)
            } else {
                ocrImage = raw
            }

            // 4. OCR
            val pipeline = service.runOcr(ocrImage)
            if (ocrImage !== raw && !ocrImage.isRecycled) ocrImage.recycle()

            // No text on screen and no overlays → nothing to do
            if (pipeline == null && !hasOverlays()) {
                service.handleNoTextDetected()
                delay(prefs.captureIntervalMs)
                return
            }

            var anyRemoved = false

            if (!hasOverlays()) {
                // 5a. No overlays: show placeholders → translate → update
                val (ocrResult, _, left, top, sw, sh) = pipeline!!
                val placeholders = buildPlaceholderBoxes(
                    ocrResult.groupTexts, ocrResult.groupBounds,
                    ocrResult.groupLineCounts, raw, left, top
                )
                if (placeholders.isEmpty()) {
                    delay(prefs.captureIntervalMs)
                    return
                }

                cropLeft = left; cropTop = top; screenshotW = sw; screenshotH = sh

                // Show skeleton placeholders immediately
                cachedBoxes = placeholders
                service.showLiveOverlay(placeholders, left, top, sw, sh)
                a11y.translationOverlayView?.pinholeEnabled = true
                waitVsync(2)
                overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()

                cleanRefBitmap?.recycle()
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)

                // Translate and replace placeholders with text
                val finalBoxes = translatePlaceholders(placeholders, ocrResult.groupTexts)
                cachedBoxes = finalBoxes
                service.showLiveOverlay(finalBoxes, left, top, sw, sh)
                a11y.translationOverlayView?.pinholeEnabled = true
                waitVsync(2)
                overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()

                mgr.saveToCache(raw)
                service.translateAndSendToPanel(ocrResult, mgr.lastCleanPath)
                DetectionLog.log("CYCLE: end of !hasOverlays branch, cachedBoxes=${cachedBoxes?.size}")
            } else {
                val boxes = cachedBoxes ?: emptyList()
                val rects = overlayScreenRects ?: emptyList()

                // 5b. Process OCR results (if any) for new/stale text detection
                val staleOverlayIndices = mutableSetOf<Int>()
                data class FarGroup(val text: String, val bounds: Rect, val lineCount: Int)
                val farOcrGroups = mutableListOf<FarGroup>()

                if (pipeline != null) {
                    val (ocrResult, _, left, top, _, _) = pipeline
                    DetectionLog.log("OCR found ${ocrResult.groupTexts.size} groups with ${boxes.size} overlays filled")
                    for (ocrIdx in ocrResult.groupTexts.indices) {
                        if (ocrIdx >= ocrResult.groupBounds.size) continue
                        val ocrBound = ocrResult.groupBounds[ocrIdx]
                        val ocrFullRect = Rect(
                            ocrBound.left + left, ocrBound.top + top,
                            ocrBound.right + left, ocrBound.bottom + top
                        )
                        DetectionLog.log("  OCR[$ocrIdx] \"${ocrResult.groupTexts[ocrIdx].take(40)}\" bounds=$ocrFullRect")
                        var nearExisting = false
                        for (boxIdx in boxes.indices) {
                            if (boxIdx >= rects.size) continue
                            if (areRectsNearby(rects[boxIdx], ocrFullRect)) {
                                nearExisting = true
                                staleOverlayIndices.add(boxIdx)
                                DetectionLog.log("    NEAR overlay[$boxIdx] rect=${rects[boxIdx]} → marking stale")
                            }
                        }
                        if (!nearExisting) {
                            val lc = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
                            farOcrGroups.add(FarGroup(ocrResult.groupTexts[ocrIdx], ocrBound, lc))
                            DetectionLog.log("    NOT near any overlay → new text")
                        }
                    }
                } else {
                    DetectionLog.log("OCR returned null (fill covered all text) — skipping to pinhole detection")
                }

                // 6. Pinhole change detection for existing overlays
                val cleanRef = cleanRefBitmap
                val pinholeRemovals = mutableSetOf<Int>()
                if (cleanRef != null) {
                    for ((idx, box) in boxes.withIndex()) {
                        if (idx >= rects.size) continue
                        if (idx in staleOverlayIndices) continue // already marked for removal
                        if (isPinholeChanged(raw, cleanRef, rects[idx], box.bgColor)) {
                            pinholeRemovals.add(idx)
                        }
                    }
                }

                // 7. Apply all removals
                val allRemovals = staleOverlayIndices + pinholeRemovals
                if (allRemovals.isNotEmpty()) {
                    anyRemoved = true
                    DetectionLog.log("Removing ${allRemovals.size} overlays: stale=${staleOverlayIndices.size} pinhole=${pinholeRemovals.size}")
                    val remaining = boxes.filterIndexed { i, _ -> i !in allRemovals }
                    cachedBoxes = remaining.ifEmpty { null }

                    if (remaining.isNotEmpty()) {
                        service.showLiveOverlay(remaining, cropLeft, cropTop, screenshotW, screenshotH)
                        a11y.translationOverlayView?.pinholeEnabled = true
                        waitVsync(2)
                        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()
                    } else {
                        a11y.hideTranslationOverlay()
                        overlayScreenRects = emptyList()
                    }
                }

                // 8. Merge new far-away text: show placeholders → translate → update
                if (farOcrGroups.isNotEmpty()) {
                    DetectionLog.log("New text groups: ${farOcrGroups.size}, showing placeholders")
                    val farTexts = farOcrGroups.map { it.text }
                    val farBounds = farOcrGroups.map { it.bounds }
                    val farLineCounts = farOcrGroups.map { it.lineCount }
                    val placeholders = buildPlaceholderBoxes(farTexts, farBounds, farLineCounts, raw, cropLeft, cropTop)

                    if (placeholders.isNotEmpty()) {
                        // Show skeletons merged with existing overlays
                        val mergedWithPlaceholders = (cachedBoxes ?: emptyList()) + placeholders
                        cachedBoxes = mergedWithPlaceholders
                        service.showLiveOverlay(mergedWithPlaceholders, cropLeft, cropTop, screenshotW, screenshotH)
                        a11y.translationOverlayView?.pinholeEnabled = true
                        waitVsync(2)
                        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()

                        // Translate and replace placeholders
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val existing = cachedBoxes?.dropLast(placeholders.size) ?: emptyList()
                        val mergedFinal = existing + translated
                        cachedBoxes = mergedFinal
                        service.showLiveOverlay(mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
                        a11y.translationOverlayView?.pinholeEnabled = true
                        waitVsync(2)
                        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()
                    }
                }
            }

            // 9. Timing
            DetectionLog.log("CYCLE: before delay, cachedBoxes=${cachedBoxes?.size} anyRemoved=$anyRemoved")
            if (anyRemoved) {
                delay(mgr.MIN_SCREENSHOT_INTERVAL_MS)
            } else {
                delay(prefs.captureIntervalMs)
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Detection Helpers ───────────────────────────────────────────────

    /** Fill overlay regions in a mutable bitmap with their background color. */
    private fun fillOverlayRegions(bitmap: Bitmap) {
        val boxes = cachedBoxes ?: return
        val fillPadding = OverlayToolkit.FILL_PADDING
        val paint = android.graphics.Paint()
        val canvas = Canvas(bitmap)
        for (box in boxes) {
            val l = (box.bounds.left + cropLeft - fillPadding).coerceAtLeast(0)
            val t = (box.bounds.top + cropTop - fillPadding).coerceAtLeast(0)
            val r = (box.bounds.right + cropLeft + fillPadding).coerceAtMost(bitmap.width)
            val b = (box.bounds.bottom + cropTop + fillPadding).coerceAtMost(bitmap.height)
            paint.color = box.bgColor or 0xFF000000.toInt()
            canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
        }
    }

    /** Check if two rects are spatially near each other. */
    private fun areRectsNearby(a: Rect, b: Rect): Boolean {
        val dx = maxOf(0, maxOf(a.left - b.right, b.left - a.right))
        val dy = maxOf(0, maxOf(a.top - b.bottom, b.top - a.bottom))
        val refHeight = maxOf(a.height(), b.height())
        val threshold = maxOf((refHeight * 1.5f).toInt(), OverlayToolkit.FILL_PADDING + 15)
        return dx < threshold && dy < threshold
    }

    /** Check if pinhole pixels in an overlay region have changed vs alpha-predicted values. */
    private fun isPinholeChanged(
        raw: Bitmap, cleanRef: Bitmap, screenRect: Rect, bgColor: Int
    ): Boolean {
        val spacing = TranslationOverlayView.PINHOLE_SPACING
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        val left = screenRect.left.coerceIn(0, raw.width)
        val top = screenRect.top.coerceIn(0, raw.height)
        val right = screenRect.right.coerceIn(0, raw.width)
        val bottom = screenRect.bottom.coerceIn(0, raw.height)
        val regionW = right - left
        val regionH = bottom - top
        if (regionW <= 0 || regionH <= 0) return false

        val rawPixels = IntArray(regionW * regionH)
        raw.getPixels(rawPixels, 0, regionW, left, top, regionW, regionH)
        val refPixels = IntArray(regionW * regionH)
        cleanRef.getPixels(refPixels, 0, regionW, left, top, regionW, regionH)

        var totalPinholes = 0
        var changedPinholes = 0

        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                // Use absolute coordinates to match parent-level pinhole mask
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                totalPinholes++

                val refPx = refPixels[py * regionW + px]
                // Pinholes are fully transparent — raw pixel = pure game content = clean ref
                val predR = Color.red(refPx)
                val predG = Color.green(refPx)
                val predB = Color.blue(refPx)

                val rawPx = rawPixels[py * regionW + px]
                val dr = kotlin.math.abs(Color.red(rawPx) - predR)
                val dg = kotlin.math.abs(Color.green(rawPx) - predG)
                val db = kotlin.math.abs(Color.blue(rawPx) - predB)

                if (dr > SPLATTER_THRESHOLD || dg > SPLATTER_THRESHOLD || db > SPLATTER_THRESHOLD) {
                    changedPinholes++
                    // H1: log first 3 changed pinholes per overlay for diagnosis
                    if (changedPinholes <= 3) {
                        DetectionLog.log("  H1: changed pinhole@(${left+px},${top+py}) raw=(${Color.red(rawPx)},${Color.green(rawPx)},${Color.blue(rawPx)}) pred=($predR,$predG,$predB) ref=(${Color.red(refPx)},${Color.green(refPx)},${Color.blue(refPx)}) bg=($bgR,$bgG,$bgB) delta=($dr,$dg,$db)")
                    }
                }
            }
        }

        if (totalPinholes == 0) return false
        val pct = changedPinholes.toFloat() / totalPinholes
        val triggered = pct >= PINHOLE_CHANGE_PCT
        DetectionLog.log("H1: pinhole rect=$screenRect total=$totalPinholes changed=$changedPinholes (${(pct * 100).toInt()}%) threshold=${(PINHOLE_CHANGE_PCT * 100).toInt()}% → ${if (triggered) "REMOVE" else "keep"}")
        return triggered
    }

    /**
     * Update clean ref from the raw frame. Makes a mutable copy of raw, then
     * patches overlay areas from the old ref (since those areas have pinhole
     * artifacts in raw). Non-overlay areas get fresh game content from raw.
     */
    private fun updateCleanRef(raw: Bitmap, oldRef: Bitmap) {
        val rects = overlayScreenRects ?: return
        val newRef = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
        val w = newRef.width
        val h = newRef.height

        for (rect in rects) {
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) continue

            val refPixels = IntArray(regionW * regionH)
            oldRef.getPixels(refPixels, 0, regionW, left, top, regionW, regionH)
            newRef.setPixels(refPixels, 0, regionW, left, top, regionW, regionH)
        }

        cleanRefBitmap?.recycle()
        cleanRefBitmap = newRef
    }

    private fun isPinholePosition(x: Int, y: Int, spacing: Int): Boolean {
        if (y % spacing != 0) return false
        val rowGroup = (y / spacing) % 2
        val xOffset = if (rowGroup == 0) 0 else spacing / 2
        return (x - xOffset) % spacing == 0 && x >= xOffset
    }

    // ── Translation Helpers ─────────────────────────────────────────────

    /** Build placeholder TextBoxes with empty text (skeleton indicators). Instant, no network. */
    private fun buildPlaceholderBoxes(
        texts: List<String>, bounds: List<Rect>, lineCounts: List<Int>,
        raw: Bitmap, left: Int, top: Int
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
            TranslationOverlayView.TextBox("", rect, bg, tc, lineCounts.getOrElse(idx) { 1 })
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
            val cached = translationCache[text]
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
                val translated = results.getOrNull(i)?.first ?: ""
                translations[idx] = translated
                translationCache[texts[idx]] = translated
            }
        }

        return placeholders.mapIndexed { idx, ph ->
            ph.copy(translatedText = translations.getOrElse(idx) { "" })
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────


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
