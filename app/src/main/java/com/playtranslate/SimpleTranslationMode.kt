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
    private val PINHOLE_DIRTY_PCT = 0.03f

    /** Fraction of pinholes that must change to remove immediately (major change). */
    private val PINHOLE_CHANGE_PCT = 0.10f

    private enum class PinholeResult { KEEP, DIRTY, REMOVE }

    override fun start() {
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(service.gameDisplayId) { onButtonDown() }

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
        translationCache.clear()
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
        val dirtyView = a11y.dirtyOverlayView
        val hasDirty = cachedBoxes?.any { it.dirty } == true

        // 1. Hide dirty overlay window before capture (window-level, fast + reliable)
        if (hasDirty) {
            dirtyView?.visibility = android.view.View.INVISIBLE
            waitVsync(5)
        }

        // 2. Capture — restore dirty window in callback (before bitmap copy)
        val raw = mgr.requestRaw(service.gameDisplayId) {
            if (hasDirty) dirtyView?.visibility = android.view.View.VISIBLE
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

            // 3. Dirty view stays visible until after OCR results

            // 4. Update clean ref: patch non-overlay pixels from raw
            if (hasOverlays()) {
                cleanRefBitmap?.let { updateCleanRef(raw, it) }
            }

            // 5. Prepare OCR image: fill overlay regions with bgColor
            val ocrImage: Bitmap
            if (hasOverlays()) {
                ocrImage = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
                fillOverlayRegions(ocrImage)
            } else {
                ocrImage = raw
            }

            // 6. OCR
            val pipeline = service.runOcr(ocrImage)
            if (ocrImage !== raw && !ocrImage.isRecycled) ocrImage.recycle()

            // After OCR, clear dirty state — dirty overlays have been captured and evaluated
            cachedBoxes = cachedBoxes?.filter { !it.dirty }?.ifEmpty { null }
            dirtyView?.setBoxes(emptyList(), cropLeft, cropTop, screenshotW, screenshotH)

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
            // NOTE: screen rects are used as bitmap pixel coordinates. This assumes
            // screenshot resolution == display resolution (scale 1.0), which holds for
            // AccessibilityService.takeScreenshot. Would need a full rethink for
            // MediaProjection with a different virtual display resolution — pinhole
            // detection fundamentally assumes 1:1 pixel correspondence.
            val rects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()

            // 7. Classify OCR results: near existing overlay (stale) or far (new text)
            val staleOverlayIndices = mutableSetOf<Int>()
            data class FarGroup(val text: String, val bounds: Rect, val lineCount: Int)
            val farOcrGroups = mutableListOf<FarGroup>()

            if (pipeline != null) {
                val (ocrResult, _, left, top, _, _) = pipeline
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
                        if (boxes[boxIdx].dirty) continue
                        if (areRectsNearby(rects[boxIdx], ocrFullRect)) {
                            nearExisting = true
                            staleOverlayIndices.add(boxIdx)
                        }
                    }
                    if (!nearExisting) {
                        val lc = ocrResult.groupLineCounts.getOrElse(ocrIdx) { 1 }
                        farOcrGroups.add(FarGroup(ocrResult.groupTexts[ocrIdx], ocrBound, lc))
                    }
                }
            }

            // 8. Pinhole change detection — DIRTY moves overlays to dirty window
            val cleanRef = cleanRefBitmap
            val pinholeRemovals = mutableSetOf<Int>()
            val pinholeDirty = mutableSetOf<Int>()
            if (cleanRef != null) {
                for ((idx, box) in boxes.withIndex()) {
                    if (idx >= rects.size) continue
                    if (box.dirty) continue
                    if (idx in staleOverlayIndices) continue
                    when (checkPinholes(raw, cleanRef, rects[idx])) {
                        PinholeResult.REMOVE -> pinholeRemovals.add(idx)
                        PinholeResult.DIRTY -> pinholeDirty.add(idx)
                        PinholeResult.KEEP -> {}
                    }
                }
            }

            // 9. Resolve: compute final state from immutable snapshot in one pass
            val allRemovals = staleOverlayIndices + pinholeRemovals
            val nextBoxes = boxes.mapIndexedNotNull { i, box ->
                when {
                    i in allRemovals -> null
                    i in pinholeDirty -> box.copy(dirty = true)
                    else -> box
                }
            }

            val cleanBoxes = nextBoxes.filter { !it.dirty }
            val dirtyBoxes = nextBoxes.filter { it.dirty }
            cachedBoxes = nextBoxes.ifEmpty { null }
            val anyChanged = allRemovals.isNotEmpty() || pinholeDirty.isNotEmpty()

            // 10. Apply to views — single commit point
            dirtyView?.setBoxes(dirtyBoxes, cropLeft, cropTop, screenshotW, screenshotH)

            if (anyChanged) {
                anyRemoved = allRemovals.isNotEmpty()
                if (cleanBoxes.isNotEmpty()) {
                    showOverlayAndCapture(a11y, cleanBoxes, cropLeft, cropTop, screenshotW, screenshotH)
                } else if (dirtyBoxes.isEmpty()) {
                    a11y.hideTranslationOverlay()
                } else {
                    // Only dirty boxes remain — clear clean window content
                    a11y.translationOverlayView?.setBoxes(
                        emptyList(), cropLeft, cropTop, screenshotW, screenshotH
                    )
                }
            }

            // 11. On first capture, set cleanRef before showing overlays
            if (isFirstCapture) {
                cleanRefBitmap?.recycle()
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // 12. Show new text (with skeletons for uncached, instant for cached)
            if (farOcrGroups.isNotEmpty()) {
                val farTexts = farOcrGroups.map { it.text }
                val farBounds = farOcrGroups.map { it.bounds }
                val farLineCounts = farOcrGroups.map { it.lineCount }
                val placeholders = buildPlaceholderBoxes(farTexts, farBounds, farLineCounts, raw, cropLeft, cropTop)

                if (placeholders.isNotEmpty()) {
                    val partial = placeholders.mapIndexed { i, ph ->
                        val cached = translationCache[farTexts[i]]
                        if (cached != null) ph.copy(translatedText = cached) else ph
                    }
                    val anyUncached = partial.any { it.translatedText.isEmpty() }

                    val currentClean = (cachedBoxes ?: emptyList()).filter { !it.dirty }
                    val merged = currentClean + partial
                    cachedBoxes = merged
                    showOverlayAndCapture(a11y, merged, cropLeft, cropTop, screenshotW, screenshotH)
                    // Dirty window cleared — clean window now has replacements
                    dirtyView?.setBoxes(emptyList(), cropLeft, cropTop, screenshotW, screenshotH)

                    if (anyUncached) {
                        val translated = translatePlaceholders(placeholders, farTexts)
                        val existing = cachedBoxes?.dropLast(placeholders.size) ?: emptyList()
                        val mergedFinal = existing + translated
                        cachedBoxes = mergedFinal
                        showOverlayAndCapture(a11y, mergedFinal, cropLeft, cropTop, screenshotW, screenshotH)
                    }

                }
            }

            // 13. First-capture post-processing
            if (isFirstCapture && cachedBoxes != null) {
                mgr.saveToCache(raw)
                pipeline?.let { (ocrResult, _, _, _, _, _) ->
                    service.translateAndSendToPanel(ocrResult, mgr.lastCleanPath)
                }
            }

            // 14. Timing
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
            if (box.dirty) continue
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

    /** Check pinhole pixels: KEEP (no change), DIRTY (minor), or REMOVE (major).
     *  screenRect is used to index into raw, cleanRef, AND overlayBitmap — assumes
     *  all three are at the same resolution (screenshot == display == view). See note above. */
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
        return result
    }

    /**
     * Update clean ref in-place: copy non-overlay pixels from raw into the
     * existing cleanRef. Overlay areas are preserved (they contain clean game
     * content from before overlays were shown, not pinhole-contaminated pixels).
     */
    /** Update clean ref in-place. Uses screen rects as bitmap coordinates (see scale note above). */
    private fun updateCleanRef(raw: Bitmap, ref: Bitmap) {
        val rects = PlayTranslateAccessibilityService.instance
            ?.translationOverlayView?.getChildScreenRects() ?: return
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
