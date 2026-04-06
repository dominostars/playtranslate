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
    private var overlayBitmap: Bitmap? = null
    private var overlayScreenRects: List<Rect>? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // Translation cache: source text → translated text
    private val translationCache = mutableMapOf<String, String>()

    /** Per-channel delta threshold for pinhole pixel change detection. */
    private val SPLATTER_THRESHOLD = 60

    /** Fraction of pinholes that must change to mark dirty (minor change). */
    private val PINHOLE_DIRTY_PCT = 0.01f

    /** Fraction of pinholes that must change to remove immediately (major change). */
    private val PINHOLE_CHANGE_PCT = 1.00f

    private enum class PinholeResult { KEEP, DIRTY, REMOVE }

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
        overlayBitmap?.recycle()
        overlayBitmap = null
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    override fun refresh() {
        cachedBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        overlayBitmap?.recycle()
        overlayBitmap = null
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private fun onButtonDown() {
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        overlayBitmap?.recycle()
        overlayBitmap = null
        cachedBoxes = null
    }

    // ── Unified Cycle ───────────────────────────────────────────────────

    private fun hasOverlays(): Boolean = cachedBoxes != null

    private suspend fun runCycle() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager ?: return
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val prefs = Prefs(service)
        val overlay = a11y.translationOverlayView
        DetectionLog.log("CYCLE: start cachedBoxes=${cachedBoxes?.size} dirty=${cachedBoxes?.count { it.dirty } ?: 0}")

        // 1. Hide dirty children before capture (no-op if none dirty)
        val hadDirty = overlay?.hideDirtyChildren() == true
        if (hadDirty) {
            waitVsync(5)
        }

        // 2. Capture raw screenshot — restore dirty children in the capture callback
        //    (overlay only needs to be hidden until the OS grabs the frame buffer)
        val raw = mgr.requestRaw(service.gameDisplayId) {
            if (hadDirty) overlay?.restoreDirtyChildren()
        }

        if (raw == null) {
            delay(prefs.captureIntervalMs)
            return
        }

        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            // 4. Update clean ref: patch non-overlay pixels from raw
            if (hasOverlays()) {
                cleanRefBitmap?.let { updateCleanRef(raw, it) }
            }

            // 6. Prepare OCR image: fill overlay regions with bgColor
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
            val isFirstCapture = !hasOverlays()

            // On first capture, set crop/screenshot dimensions from pipeline
            if (isFirstCapture && pipeline != null) {
                val (_, _, left, top, sw, sh) = pipeline
                cropLeft = left; cropTop = top; screenshotW = sw; screenshotH = sh
            }

            val boxes = cachedBoxes ?: emptyList()
            val rects = overlayScreenRects ?: emptyList()

            // 5. Classify OCR results: near existing overlay (stale) or far (new text)
            val staleOverlayIndices = mutableSetOf<Int>()
            data class FarGroup(val text: String, val bounds: Rect, val lineCount: Int)
            val farOcrGroups = mutableListOf<FarGroup>()

            if (pipeline != null) {
                val (ocrResult, _, left, top, _, _) = pipeline
                DetectionLog.log("OCR found ${ocrResult.groupTexts.size} groups with ${boxes.size} overlays")
                for (ocrIdx in ocrResult.groupTexts.indices) {
                    if (ocrIdx >= ocrResult.groupBounds.size) continue
                    val ocrBound = ocrResult.groupBounds[ocrIdx]
                    val ocrFullRect = Rect(
                        ocrBound.left + left, ocrBound.top + top,
                        ocrBound.right + left, ocrBound.bottom + top
                    )
                    var nearExisting = false
                    for (boxIdx in boxes.indices) {
                        if (boxIdx >= rects.size) continue
                        if (boxes[boxIdx].dirty) continue // don't compare against dirty overlays
                        if (areRectsNearby(rects[boxIdx], ocrFullRect)) {
                            nearExisting = true
                            staleOverlayIndices.add(boxIdx)
                            DetectionLog.log("  OCR[$ocrIdx] \"${ocrResult.groupTexts[ocrIdx].take(30)}\" NEAR overlay[$boxIdx]")
                        }
                    }
                    if (!nearExisting) {
                        val lc = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
                        farOcrGroups.add(FarGroup(ocrResult.groupTexts[ocrIdx], ocrBound, lc))
                        DetectionLog.log("  OCR[$ocrIdx] \"${ocrResult.groupTexts[ocrIdx].take(30)}\" → new text")
                    }
                }
            } else if (!isFirstCapture) {
                DetectionLog.log("OCR returned null (fill covered all text) — skipping to pinhole detection")
            }

            // 6. Pinhole change detection for existing overlays
            val cleanRef = cleanRefBitmap
            val pinholeRemovals = mutableSetOf<Int>()
            if (cleanRef != null) {
                for ((idx, box) in boxes.withIndex()) {
                    if (idx >= rects.size) continue
                    if (box.dirty) continue // dirty overlays already handled
                    if (idx in staleOverlayIndices) continue
                    when (checkPinholes(raw, cleanRef, rects[idx])) {
                        PinholeResult.REMOVE -> pinholeRemovals.add(idx)
                        PinholeResult.DIRTY -> {
                            if (!box.dirty) {
                                cachedBoxes = cachedBoxes?.toMutableList()?.also {
                                    it[idx] = box.copy(dirty = true)
                                }
                            }
                        }
                        PinholeResult.KEEP -> {}
                    }
                }
            }

            // 7. Apply removals (keep dirty overlays visible — they'll be replaced in step 9)
            val allRemovals = staleOverlayIndices + pinholeRemovals
            if (allRemovals.isNotEmpty()) {
                anyRemoved = true
                DetectionLog.log("Removing ${allRemovals.size} overlays: stale=${staleOverlayIndices.size} pinhole=${pinholeRemovals.size}")
                val remaining = boxes.filterIndexed { i, _ -> i !in allRemovals }
                cachedBoxes = remaining.ifEmpty { null }

                if (remaining.isNotEmpty()) {
                    showOverlayAndCapture(a11y, remaining, cropLeft, cropTop, screenshotW, screenshotH)
                } else {
                    a11y.hideTranslationOverlay()
                    overlayScreenRects = emptyList()
                }
            }

            // 8. On first capture, set cleanRef before showing overlays
            if (isFirstCapture) {
                cleanRefBitmap?.recycle()
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // 9. Translate new text, strip dirty, show in single rebuild
            val cleanBoxes = (cachedBoxes ?: emptyList()).filter { !it.dirty }
            if (farOcrGroups.isNotEmpty()) {
                val farTexts = farOcrGroups.map { it.text }
                val farBounds = farOcrGroups.map { it.bounds }
                val farLineCounts = farOcrGroups.map { it.lineCount }
                val placeholders = buildPlaceholderBoxes(farTexts, farBounds, farLineCounts, raw, cropLeft, cropTop)

                if (placeholders.isNotEmpty()) {
                    // Fill cached translations immediately, leave uncached as skeletons
                    val partial = placeholders.mapIndexed { i, ph ->
                        val cached = translationCache[farTexts[i]]
                        if (cached != null) ph.copy(translatedText = cached) else ph
                    }
                    val anyUncached = partial.any { it.translatedText.isEmpty() }

                    // Show what we have (translated + skeletons for uncached)
                    val merged = cleanBoxes + partial
                    cachedBoxes = merged
                    showOverlayAndCapture(a11y, merged, cropLeft, cropTop, screenshotW, screenshotH)

                    // If any were uncached, translate and rebuild with final text
                    if (anyUncached) {
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val mergedFinal = cleanBoxes + translated
                        cachedBoxes = mergedFinal
                        showOverlayAndCapture(a11y, mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
                    }
                }
            } else if (cleanBoxes.size != (cachedBoxes?.size ?: 0)) {
                // No new text but dirty overlays need to be stripped
                DetectionLog.log("Stripping ${(cachedBoxes?.size ?: 0) - cleanBoxes.size} dirty overlays")
                cachedBoxes = cleanBoxes.ifEmpty { null }
                if (cleanBoxes.isNotEmpty()) {
                    showOverlayAndCapture(a11y, cleanBoxes, cropLeft, cropTop, screenshotW, screenshotH)
                } else {
                    a11y.hideTranslationOverlay()
                    overlayScreenRects = emptyList()
                }
            }

            // 10. First-capture post-processing: save to cache, update in-app panel
            if (isFirstCapture && cachedBoxes != null) {
                mgr.saveToCache(raw)
                pipeline?.let { (ocrResult, _, _, _, _, _) ->
                    service.translateAndSendToPanel(ocrResult, mgr.lastCleanPath)
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

    /** Show overlay, enable pinholes, wait for layout, capture screen rects and overlay render. */
    private suspend fun showOverlayAndCapture(
        a11y: PlayTranslateAccessibilityService, boxes: List<TranslationOverlayView.TextBox>,
        left: Int, top: Int, sw: Int, sh: Int
    ) {
        service.showLiveOverlay(boxes, left, top, sw, sh)
        a11y.translationOverlayView?.pinholeEnabled = true
        waitVsync(2)
        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()
        overlayBitmap?.recycle()
        overlayBitmap = a11y.translationOverlayView?.renderToOffscreen()
    }

    // ── Detection Helpers ───────────────────────────────────────────────

    /** Fill non-dirty overlay regions in a mutable bitmap with their background color. */
    private fun fillOverlayRegions(bitmap: Bitmap) {
        val boxes = cachedBoxes ?: return
        val fillPadding = OverlayToolkit.FILL_PADDING
        val paint = android.graphics.Paint()
        val canvas = Canvas(bitmap)
        for (box in boxes) {
            if (box.dirty) continue // dirty regions were hidden for capture — don't fill
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

    /** Check pinhole pixels: KEEP (no change), DIRTY (minor), or REMOVE (major). */
    private fun checkPinholes(
        raw: Bitmap, cleanRef: Bitmap, screenRect: Rect
    ): PinholeResult {
        val overlay = overlayBitmap ?: return PinholeResult.KEEP
        val spacing = TranslationOverlayView.PINHOLE_SPACING

        val left = screenRect.left.coerceIn(0, raw.width)
        val top = screenRect.top.coerceIn(0, raw.height)
        val right = screenRect.right.coerceIn(0, raw.width)
        val bottom = screenRect.bottom.coerceIn(0, raw.height)
        val regionW = right - left
        val regionH = bottom - top
        if (regionW <= 0 || regionH <= 0) return PinholeResult.KEEP

        val rawPixels = IntArray(regionW * regionH)
        raw.getPixels(rawPixels, 0, regionW, left, top, regionW, regionH)
        val refPixels = IntArray(regionW * regionH)
        cleanRef.getPixels(refPixels, 0, regionW, left, top, regionW, regionH)

        // Overlay bitmap is in view coordinates (same as screen coordinates for full-screen overlay)
        val ovLeft = left.coerceIn(0, overlay.width)
        val ovTop = top.coerceIn(0, overlay.height)
        val ovRight = right.coerceIn(0, overlay.width)
        val ovBottom = bottom.coerceIn(0, overlay.height)
        val ovW = ovRight - ovLeft
        val ovH = ovBottom - ovTop
        if (ovW != regionW || ovH != regionH) return PinholeResult.KEEP
        val ovPixels = IntArray(regionW * regionH)
        overlay.getPixels(ovPixels, 0, regionW, ovLeft, ovTop, regionW, regionH)

        var totalPinholes = 0
        var changedPinholes = 0
        var maxDelta = 0

        for (py in 0 until regionH) {
            for (px in 0 until regionW) {
                if (!isPinholePosition(left + px, top + py, spacing)) continue
                totalPinholes++

                val refPx = refPixels[py * regionW + px]
                val ovPx = ovPixels[py * regionW + px]
                // predicted = clean_ref * 0.5 + overlay_rendered * 0.5
                val predR = ((Color.red(refPx) + Color.red(ovPx)) / 2).coerceIn(0, 255)
                val predG = ((Color.green(refPx) + Color.green(ovPx)) / 2).coerceIn(0, 255)
                val predB = ((Color.blue(refPx) + Color.blue(ovPx)) / 2).coerceIn(0, 255)

                val rawPx = rawPixels[py * regionW + px]
                val dr = kotlin.math.abs(Color.red(rawPx) - predR)
                val dg = kotlin.math.abs(Color.green(rawPx) - predG)
                val db = kotlin.math.abs(Color.blue(rawPx) - predB)
                val delta = maxOf(dr, dg, db)
                if (delta > maxDelta) maxDelta = delta

                if (dr > SPLATTER_THRESHOLD || dg > SPLATTER_THRESHOLD || db > SPLATTER_THRESHOLD) {
                    changedPinholes++
                    if (changedPinholes <= 3) {
                        DetectionLog.log("  H1: changed pinhole@(${left+px},${top+py}) raw=(${Color.red(rawPx)},${Color.green(rawPx)},${Color.blue(rawPx)}) pred=($predR,$predG,$predB) ref=(${Color.red(refPx)},${Color.green(refPx)},${Color.blue(refPx)}) ov=(${Color.red(ovPx)},${Color.green(ovPx)},${Color.blue(ovPx)}) delta=($dr,$dg,$db)")
                    }
                }
            }
        }

        if (totalPinholes == 0) return PinholeResult.KEEP
        val pct = changedPinholes.toFloat() / totalPinholes
        val result = when {
            pct >= PINHOLE_CHANGE_PCT -> PinholeResult.REMOVE
            pct >= PINHOLE_DIRTY_PCT -> PinholeResult.DIRTY
            else -> PinholeResult.KEEP
        }
        DetectionLog.log("H1: pinhole rect=$screenRect total=$totalPinholes changed=$changedPinholes (${(pct * 100).toInt()}%) maxDelta=$maxDelta → $result")
        return result
    }

    /**
     * Update clean ref in-place: copy non-overlay pixels from raw into the
     * existing cleanRef. Overlay areas are preserved (they contain clean game
     * content from before overlays were shown, not pinhole-contaminated pixels).
     */
    private fun updateCleanRef(raw: Bitmap, ref: Bitmap) {
        val rects = overlayScreenRects ?: return
        val w = ref.width
        val h = ref.height

        // Save overlay region pixels from ref (clean game content)
        val savedRegions = rects.map { rect ->
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) return@map null
            val pixels = IntArray(regionW * regionH)
            ref.getPixels(pixels, 0, regionW, left, top, regionW, regionH)
            pixels
        }

        // Overwrite entire ref with raw (fresh non-overlay game content)
        val allPixels = IntArray(w * h)
        raw.getPixels(allPixels, 0, w, 0, 0, w, h)
        ref.setPixels(allPixels, 0, w, 0, 0, w, h)

        // Restore overlay regions from saved pixels
        for ((i, rect) in rects.withIndex()) {
            val pixels = savedRegions[i] ?: continue
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) continue
            ref.setPixels(pixels, 0, regionW, left, top, regionW, regionH)
        }
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
