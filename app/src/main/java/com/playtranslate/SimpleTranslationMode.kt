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
    private var shadowMask: Bitmap? = null
    private var overlayScreenRects: List<Rect>? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // Translation cache: source text → translated text
    private val translationCache = mutableMapOf<String, String>()

    /** Per-channel delta threshold for morphological splatter. */
    private val SPLATTER_THRESHOLD = 80

    // OCR text per displayed group for change detection
    private var displayedGroupTexts: List<String> = emptyList()

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
        shadowMask?.recycle()
        shadowMask = null
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    override fun refresh() {
        cachedBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        shadowMask?.recycle()
        shadowMask = null
        displayedGroupTexts = emptyList()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    private fun onButtonDown() {
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        shadowMask?.recycle()
        shadowMask = null
        cachedBoxes = null
        displayedGroupTexts = emptyList()
    }

    // ── Unified Cycle ───────────────────────────────────────────────────

    private suspend fun runCycle() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager ?: return
        val a11y = PlayTranslateAccessibilityService.instance ?: return
        val hasOverlays = cachedBoxes != null

        // Pinhole overlays if present, otherwise screenshot is naturally clean
        if (hasOverlays) {
            a11y.translationOverlayView?.switchToPinhole()
            waitVsync(5)
        }

        // Capture — restore solid in the onCaptured callback (before bitmap copy)
        val raw = mgr.requestRaw(service.gameDisplayId) {
            if (hasOverlays) {
                a11y.translationOverlayView?.switchToSolid()
            }
        }

        if (raw == null) {
            delay(Prefs(service).captureIntervalMs)
            return
        }

        try {
            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            // Build composite if we have a clean reference, otherwise use raw directly
            val ref = cleanRefBitmap
            val imageForOcr = if (hasOverlays && ref != null) {
                buildComposite(raw, ref)
            } else null

            // Update clean ref: raw frame has fresh game content in non-overlay areas.
            // Patch overlay areas from old ref into a mutable copy of raw → new clean ref.
            if (hasOverlays && ref != null) {
                updateCleanRef(raw, ref)
            }

            try {
                val f = java.io.File("/sdcard/Download/ocr_raw.png")
                java.io.FileOutputStream(f).use { raw.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Exception) {}
            val ocrImage = imageForOcr ?: raw
            try {
                val f = java.io.File("/sdcard/Download/ocr_input.png")
                java.io.FileOutputStream(f).use { ocrImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } catch (_: Exception) {}
            val pipeline = service.runOcr(ocrImage)
            imageForOcr?.recycle()

            if (pipeline == null) {
                if (hasOverlays) {
                    // Had overlays but no text found — scene changed
                    service.handleNoTextDetected()
                    cachedBoxes = null
                    displayedGroupTexts = emptyList()
                    cleanRefBitmap?.recycle()
                    cleanRefBitmap = null
                    shadowMask?.recycle()
                    shadowMask = null
                } else {
                    service.handleNoTextDetected()
                }
                delay(Prefs(service).captureIntervalMs)
                return
            }

            val (ocrResult, _, left, top, sw, sh) = pipeline

            if (!hasOverlays) {
                // No overlays — this is a clean capture. Translate and show.
                val boxes = translateAndBuildBoxes(ocrResult, raw, left, top)
                if (boxes.isEmpty()) {
                    delay(Prefs(service).captureIntervalMs)
                    return
                }

                cachedBoxes = boxes
                cropLeft = left; cropTop = top; screenshotW = sw; screenshotH = sh
                displayedGroupTexts = ocrResult.groupTexts.toList()

                // Store clean reference (mutable so we can update it from pinhole cycles)
                cleanRefBitmap?.recycle()
                cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)

                service.showLiveOverlay(boxes, left, top, sw, sh)

                // Generate shadow mask after overlay renders
                waitVsync(2)
                shadowMask?.recycle()
                shadowMask = a11y.translationOverlayView?.generateShadowMask()
                overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()

                // Send to in-app panel
                mgr.saveToCache(raw)
                service.translateAndSendToPanel(ocrResult, mgr.lastCleanPath)
            } else {
                // Had overlays — compare composite OCR against displayed text
                val matchResult = matchOcrToDisplayed(ocrResult)

                if (matchResult.removedIndices.isNotEmpty()) {
                    DetectionLog.log("Pinhole: ${matchResult.removedIndices.size}/${displayedGroupTexts.size} overlays changed/disappeared")
                    for (idx in matchResult.removedIndices) {
                        DetectionLog.log("  REMOVED[$idx]: \"${displayedGroupTexts.getOrNull(idx)?.take(30)}\"")
                    }
                    val remaining = cachedBoxes!!.filterIndexed { i, _ -> i !in matchResult.removedIndices }
                    cachedBoxes = remaining.ifEmpty { null }
                    displayedGroupTexts = displayedGroupTexts.filterIndexed { i, _ -> i !in matchResult.removedIndices }

                    if (remaining.isNotEmpty()) {
                        service.showLiveOverlay(remaining, cropLeft, cropTop, screenshotW, screenshotH)
                        // Update mask and rects for remaining overlays
                        waitVsync(2)
                        shadowMask?.recycle()
                        shadowMask = a11y.translationOverlayView?.generateShadowMask()
                        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()
                    } else {
                        a11y.hideTranslationOverlay()
                        cleanRefBitmap?.recycle()
                        cleanRefBitmap = null
                        shadowMask?.recycle()
                        shadowMask = null
                    }
                }

                if (matchResult.newGroups.isNotEmpty()) {
                    DetectionLog.log("Pinhole: ${matchResult.newGroups.size} new text groups, translating")
                    val newBoxes = translateNewGroups(matchResult.newGroups, raw)
                    if (newBoxes.isNotEmpty()) {
                        val merged = (cachedBoxes ?: emptyList()) + newBoxes
                        cachedBoxes = merged
                        displayedGroupTexts = displayedGroupTexts + matchResult.newGroups.map { it.first }
                        service.showLiveOverlay(merged, cropLeft, cropTop, screenshotW, screenshotH)

                        waitVsync(2)
                        shadowMask?.recycle()
                        shadowMask = a11y.translationOverlayView?.generateShadowMask()
                        overlayScreenRects = a11y.translationOverlayView?.getChildScreenRects() ?: emptyList()
                    }
                }
            }

            delay(Prefs(service).captureIntervalMs)
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Composite Image Construction ────────────────────────────────────

    private fun buildComposite(raw: Bitmap, cleanRef: Bitmap): Bitmap {
        val composite = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, true)
        val rects = overlayScreenRects ?: return composite
        val mask = shadowMask

        val spacing = TranslationOverlayView.PINHOLE_SPACING
        val w = composite.width
        val h = composite.height

        // Read full-view mask pixels once (mask is in overlay view coordinates = screen coordinates)
        val maskBytes: ByteArray? = if (mask != null && mask.width > 0 && mask.height > 0) {
            ByteArray(mask.width * mask.height).also {
                mask.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it))
            }
        } else null
        val maskW = mask?.width ?: 0

        for (rect in rects) {
            val left = rect.left.coerceIn(0, w)
            val top = rect.top.coerceIn(0, h)
            val right = rect.right.coerceIn(0, w)
            val bottom = rect.bottom.coerceIn(0, h)
            val regionW = right - left
            val regionH = bottom - top
            if (regionW <= 0 || regionH <= 0) continue

            val compositePixels = IntArray(regionW * regionH)
            composite.getPixels(compositePixels, 0, regionW, left, top, regionW, regionH)
            val refPixels = IntArray(regionW * regionH)
            cleanRef.getPixels(refPixels, 0, regionW, left, top, regionW, regionH)

            // First pass: replace non-pinhole and text-covered pinhole pixels with clean ref
            for (py in 0 until regionH) {
                for (px in 0 until regionW) {
                    val screenX = left + px
                    val screenY = top + py
                    val isPinhole = isPinholePosition(px, py, spacing)
                    val isText = maskBytes != null && screenX < maskW && screenY < (mask?.height ?: 0)
                        && maskBytes[screenY * maskW + screenX].toInt() != 0

                    if (!isPinhole || isText) {
                        compositePixels[py * regionW + px] = refPixels[py * regionW + px]
                    }
                }
            }

            // Second pass: splatter — if a background pinhole pixel differs significantly
            // from the clean ref, draw a 4x4 block of the new color to structurally
            // damage old text for OCR detection.
            for (py in 0 until regionH) {
                for (px in 0 until regionW) {
                    if (!isPinholePosition(px, py, spacing)) continue
                    val screenX = left + px
                    val screenY = top + py
                    val isText = maskBytes != null && screenX < maskW && screenY < (mask?.height ?: 0)
                        && maskBytes[screenY * maskW + screenX].toInt() != 0
                    if (isText) continue

                    val rawPx = compositePixels[py * regionW + px]
                    val refPx = refPixels[py * regionW + px]
                    val dr = kotlin.math.abs(Color.red(rawPx) - Color.red(refPx))
                    val dg = kotlin.math.abs(Color.green(rawPx) - Color.green(refPx))
                    val db = kotlin.math.abs(Color.blue(rawPx) - Color.blue(refPx))
                    if (dr > SPLATTER_THRESHOLD || dg > SPLATTER_THRESHOLD || db > SPLATTER_THRESHOLD) {
                        for (sy in -1..1) {
                            for (sx in -1..1) {
                                val tx = px + sx
                                val ty = py + sy
                                if (tx in 0 until regionW && ty in 0 until regionH) {
                                    compositePixels[ty * regionW + tx] = rawPx
                                }
                            }
                        }
                    }
                }
            }

            composite.setPixels(compositePixels, 0, regionW, left, top, regionW, regionH)
        }

        return composite
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

    private fun isPinholePosition(localX: Int, localY: Int, spacing: Int): Boolean {
        if (localY % spacing != 0) return false
        val rowGroup = (localY / spacing) % 2
        val xOffset = if (rowGroup == 0) 0 else spacing / 2
        return (localX - xOffset) % spacing == 0 && localX >= xOffset
    }

    // ── Change Detection (text-content matching, no spatial overlap) ────

    private data class MatchResult(
        /** Indices of displayed overlays whose source text no longer appears in OCR. */
        val removedIndices: Set<Int>,
        /** New OCR groups not matching any displayed overlay and not overlapping existing OCR boxes. */
        val newGroups: List<Pair<String, Rect>>
    )

    private fun matchOcrToDisplayed(ocrResult: OcrManager.OcrResult): MatchResult {
        val ocrTexts = ocrResult.groupTexts
        val ocrBounds = ocrResult.groupBounds

        // Track which displayed groups still have a match in the new OCR
        val matchedDisplayIndices = mutableSetOf<Int>()
        val matchedOcrIndices = mutableSetOf<Int>()

        // For each displayed source text, find a matching OCR text by content.
        // Use lenient matching — pinholes cause minor OCR noise (dropped characters,
        // punctuation changes). Only flag as changed when >20% of characters differ.
        for ((displayIdx, displayText) in displayedGroupTexts.withIndex()) {
            for ((ocrIdx, ocrText) in ocrTexts.withIndex()) {
                if (ocrIdx in matchedOcrIndices) continue
                if (isPinholeMatch(displayText, ocrText)) {
                    matchedDisplayIndices.add(displayIdx)
                    matchedOcrIndices.add(ocrIdx)
                    break
                }
            }
        }

        // New OCR groups: not matched to any displayed overlay AND not overlapping existing OCR boxes
        val newGroups = mutableListOf<Pair<String, Rect>>()
        for ((ocrIdx, ocrText) in ocrTexts.withIndex()) {
            if (ocrIdx in matchedOcrIndices) continue
            if (ocrIdx >= ocrBounds.size) continue

            val newBounds = ocrBounds[ocrIdx]
            val overlapsExisting = matchedOcrIndices.any { matchedIdx ->
                matchedIdx < ocrBounds.size && Rect.intersects(newBounds, ocrBounds[matchedIdx])
            }
            if (!overlapsExisting) {
                newGroups.add(ocrText to newBounds)
            }
        }

        // Displayed overlays with no match → only flag as removed if there are also
        // new unmatched groups (real text change). If OCR just missed some groups
        // (all OCR results match existing texts), don't remove anything.
        val removedIndices = if (newGroups.isNotEmpty()) {
            displayedGroupTexts.indices.filter { it !in matchedDisplayIndices }.toSet()
        } else {
            emptySet()
        }

        return MatchResult(removedIndices, newGroups)
    }

    /**
     * Lenient text matching for pinhole composite comparisons.
     * Pinholes introduce OCR noise (dropped characters, punctuation changes).
     * Returns true if texts are similar enough to be the same game text.
     */
    private fun isPinholeMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return true
        val freqA = a.groupingBy { it }.eachCount()
        val freqB = b.groupingBy { it }.eachCount()
        var diff = 0
        for (c in (freqA.keys + freqB.keys).toSet()) {
            diff += kotlin.math.abs((freqA[c] ?: 0) - (freqB[c] ?: 0))
        }
        // Allow up to 20% character difference (vs 30% in isSignificantChange)
        // but no absolute minimum — long texts with a few dropped chars should still match
        return diff.toFloat() / maxLen <= 0.20f
    }

    // ── Translation Helpers ─────────────────────────────────────────────

    private suspend fun translateAndBuildBoxes(
        ocrResult: OcrManager.OcrResult, raw: Bitmap,
        left: Int, top: Int
    ): List<TranslationOverlayView.TextBox> {
        // Check cache for each group
        val uncachedIndices = mutableListOf<Int>()
        val uncachedTexts = mutableListOf<String>()
        val translations = Array(ocrResult.groupTexts.size) { "" }

        for ((idx, text) in ocrResult.groupTexts.withIndex()) {
            val cached = translationCache[text]
            if (cached != null) {
                translations[idx] = cached
            } else {
                uncachedIndices.add(idx)
                uncachedTexts.add(text)
            }
        }

        // Translate uncached groups
        if (uncachedTexts.isNotEmpty()) {
            val results = service.translateGroupsSeparately(uncachedTexts)
            for ((i, idx) in uncachedIndices.withIndex()) {
                val translated = results.getOrNull(i)?.first ?: ""
                translations[idx] = translated
                translationCache[ocrResult.groupTexts[idx]] = translated
            }
        }

        // Color sample + build boxes
        val colorScale = 4
        val colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(colorRef, ocrResult.groupBounds, left, top, colorScale)
        } finally {
            colorRef.recycle()
        }

        return if (ocrResult.groupBounds.size == translations.size) {
            translations.mapIndexed { idx, text ->
                val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
                TranslationOverlayView.TextBox(text, ocrResult.groupBounds[idx], bg, tc)
            }
        } else emptyList()
    }

    private suspend fun translateNewGroups(
        groups: List<Pair<String, Rect>>, raw: Bitmap
    ): List<TranslationOverlayView.TextBox> {
        val texts = groups.map { it.first }
        val bounds = groups.map { it.second }

        // Check cache
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

        val colorScale = 4
        val colorRef = Bitmap.createScaledBitmap(raw, raw.width / colorScale, raw.height / colorScale, false)
        val colors: List<Pair<Int, Int>>
        try {
            colors = OverlayToolkit.sampleGroupColors(colorRef, bounds, cropLeft, cropTop, colorScale)
        } finally {
            colorRef.recycle()
        }

        return translations.mapIndexed { idx, text ->
            val (bg, tc) = colors.getOrElse(idx) { Pair(Color.argb(224, 0, 0, 0), Color.WHITE) }
            TranslationOverlayView.TextBox(text, bounds[idx], bg, tc)
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
