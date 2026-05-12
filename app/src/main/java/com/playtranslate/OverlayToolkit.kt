package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import com.playtranslate.language.HintTextAnnotation
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.ui.TranslationOverlayView

/**
 * Pure, stateless functions for building overlay boxes, sampling colors, and
 * dedup comparison. No Android Service dependency — all inputs as parameters.
 */
object OverlayToolkit {

    // ── Constants ─────────────────────────────────────────────────────────

    const val FILL_PADDING = 30
    const val LIVE_DEDUP_TOLERANCE = 3
    const val LIVE_DEDUP_PCT_THRESHOLD = 0.3f

    // ── Dedup ─────────────────────────────────────────────────────────────

    /** Is the text change significant enough to warrant re-processing? */
    fun isSignificantChange(a: String, b: String): Boolean {
        if (a == b) return false
        val freqA = a.groupingBy { it }.eachCount()
        val freqB = b.groupingBy { it }.eachCount()
        var diff = 0
        for (c in (freqA.keys + freqB.keys).toSet()) {
            diff += kotlin.math.abs((freqA[c] ?: 0) - (freqB[c] ?: 0))
            if (diff > LIVE_DEDUP_TOLERANCE) return true
        }
        val maxLen = maxOf(a.length, b.length)
        if (maxLen > 0 && diff.toFloat() / maxLen > LIVE_DEDUP_PCT_THRESHOLD) return true
        return false
    }

    /** Does detected text have significant additions over existing? */
    fun hasSignificantAdditions(existing: String, detected: String): Boolean {
        val bag = existing.groupingBy { it }.eachCount().toMutableMap()
        var added = 0
        for (c in detected) {
            val count = bag[c] ?: 0
            if (count > 0) {
                bag[c] = count - 1
            } else {
                added++
                if (added > 1) return true
            }
        }
        return false
    }

    // ── Color sampling ────────────────────────────────────────────────────

    /**
     * Sample background and text colors for each group bound from a scaled-down
     * reference bitmap. Returns (bgColor, textColor) per group.
     */
    fun sampleGroupColors(
        colorRef: Bitmap,
        groupBounds: List<Rect>,
        cropLeft: Int, cropTop: Int,
        colorScale: Int
    ): List<Pair<Int, Int>> {
        val buffer = 10 / colorScale
        return groupBounds.map { bounds ->
            val sl = (bounds.left + cropLeft) / colorScale
            val st = (bounds.top + cropTop) / colorScale
            val sr = (bounds.right + cropLeft) / colorScale
            val sb = (bounds.bottom + cropTop) / colorScale
            val bgColor = averageColor(colorRef,
                sl - buffer, st - buffer, sr + buffer, sb + buffer,
                excludeInner = Rect(sl, st, sr, sb))
            val textColor = if (colorLuminance(bgColor) > 128)
                Color.BLACK else Color.WHITE
            Pair(bgColor, textColor)
        }
    }

    private fun colorLuminance(color: Int): Double {
        return 0.299 * Color.red(color) +
            0.587 * Color.green(color) +
            0.114 * Color.blue(color)
    }

    private fun averageColor(
        bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int,
        excludeInner: Rect? = null
    ): Int {
        val left = l.coerceIn(0, bitmap.width - 1)
        val top = t.coerceIn(0, bitmap.height - 1)
        val right = r.coerceIn(left + 1, bitmap.width)
        val bottom = b.coerceIn(top + 1, bitmap.height)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in top until bottom step 4) {
            for (x in left until right step 4) {
                if (excludeInner != null && excludeInner.contains(x, y)) continue
                val pixel = bitmap.getPixel(x, y)
                rSum += Color.red(pixel)
                gSum += Color.green(pixel)
                bSum += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return Color.argb(224, 0, 0, 0)
        return Color.argb(224,
            (rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    // ── Furigana box building ─────────────────────────────────────────────

    /** A group of furigana boxes with their source OCR group text and bounds. */
    data class FuriganaGroup(
        val groupText: String,
        val groupBounds: Rect,
        val boxes: List<TranslationOverlayView.TextBox>
    )

    /**
     * Build furigana boxes grouped by OCR group, for selective invalidation.
     * Each group carries its source text and bounds so FuriganaMode can track
     * which groups changed and remove only their furigana.
     */
    fun buildFuriganaBoxesByGroup(
        ocrResult: OcrManager.OcrResult,
        engine: SourceLanguageEngine,
        furiganaPaint: TextPaint
    ): List<FuriganaGroup> {
        val groups = mutableListOf<FuriganaGroup>()

        for (groupIdx in ocrResult.groupTexts.indices) {
            val lines = ocrResult.lineBoxes.filter { it.groupIndex == groupIdx }
            if (lines.isEmpty()) continue

            val groupBoxes = mutableListOf<TranslationOverlayView.TextBox>()
            for (line in lines) {
                val isVertical = line.orientation == com.playtranslate.language.TextOrientation.VERTICAL
                if (line.text.isEmpty()) continue
                val annotations = engine.annotateForHintText(line.text)
                val lineBoxes = mutableListOf<TranslationOverlayView.TextBox>()

                if (line.symbols.isNotEmpty()) {
                    for (ann in annotations) {
                        val matching = line.symbols.filter { it.charOffset in ann.baseStart until ann.baseEnd }
                        if (matching.isEmpty()) continue
                        val first = matching.first()
                        val last = matching.last()

                        val bounds = if (isVertical) {
                            // Vertical: furigana to the right of the column
                            val furiganaWidth = (first.bounds.width() * 0.75f).toInt().coerceAtLeast(1)
                            Rect(
                                last.bounds.right,
                                first.bounds.top,
                                last.bounds.right + furiganaWidth,
                                last.bounds.bottom
                            )
                        } else {
                            // Horizontal: furigana above the text
                            val furiganaHeight = (first.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                            Rect(
                                first.bounds.left,
                                first.bounds.top - furiganaHeight,
                                last.bounds.right,
                                first.bounds.top
                            )
                        }
                        lineBoxes += TranslationOverlayView.TextBox(
                            translatedText = ann.hintText,
                            bounds = bounds,
                            lineCount = 1,
                            isFurigana = true,
                            orientation = line.orientation
                        )
                    }
                } else {
                    // Fallback: TextPaint estimation (no symbols available)
                    if (isVertical) {
                        // Vertical fallback: distribute characters along column height
                        val lineH = line.bounds.height().toFloat()
                        val lineTop = line.bounds.top
                        val charCount = line.text.length
                        val charHeight = if (charCount > 0) lineH / charCount else lineH

                        for (ann in annotations) {
                            val top = lineTop + (ann.baseStart * charHeight).toInt()
                            val bottom = lineTop + (ann.baseEnd * charHeight).toInt()
                            val furiganaWidth = (line.bounds.width() * 0.75f).toInt().coerceAtLeast(1)
                            lineBoxes += TranslationOverlayView.TextBox(
                                translatedText = ann.hintText,
                                bounds = Rect(
                                    line.bounds.right,
                                    top,
                                    line.bounds.right + furiganaWidth,
                                    bottom
                                ),
                                lineCount = 1,
                                isFurigana = true,
                                orientation = line.orientation
                            )
                        }
                    } else {
                        // Horizontal fallback (existing logic)
                        val positionMapper: (Int, Int) -> Pair<Int, Int> = if (line.elements.isNotEmpty()) {
                            buildCharToElementMapper(line.elements, furiganaPaint)
                        } else {
                            val lineW = line.bounds.width().toFloat()
                            val lineLeft = line.bounds.left
                            val charWidths = FloatArray(line.text.length).also {
                                furiganaPaint.getTextWidths(line.text, it)
                            }
                            val totalWeight = charWidths.sum()
                            fun(s: Int, e: Int): Pair<Int, Int> {
                                if (totalWeight <= 0f) return lineLeft to lineLeft
                                val lWeight = (0 until s.coerceIn(0, charWidths.size))
                                    .sumOf { charWidths[it].toDouble() }.toFloat()
                                val rWeight = (0 until e.coerceIn(0, charWidths.size))
                                    .sumOf { charWidths[it].toDouble() }.toFloat()
                                val l = lineLeft + (lWeight / totalWeight * lineW).toInt()
                                val r = lineLeft + (rWeight / totalWeight * lineW).toInt()
                                return l to r
                            }
                        }

                        for (ann in annotations) {
                            val (left, right) = positionMapper(ann.baseStart, ann.baseEnd)
                            val furiganaHeight = (line.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                            val furiganaBounds = Rect(
                                left,
                                line.bounds.top - furiganaHeight,
                                right,
                                line.bounds.top
                            )
                            lineBoxes += TranslationOverlayView.TextBox(
                                translatedText = ann.hintText,
                                bounds = furiganaBounds,
                                lineCount = 1,
                                isFurigana = true
                            )
                        }
                    }
                }

                groupBoxes += mergeOverlappingFurigana(lineBoxes, furiganaPaint, isVertical)
            }

            if (groupBoxes.isNotEmpty() && groupIdx < ocrResult.groupBounds.size) {
                groups += FuriganaGroup(
                    groupText = ocrResult.groupTexts[groupIdx],
                    groupBounds = ocrResult.groupBounds[groupIdx],
                    boxes = groupBoxes
                )
            }
        }
        return groups
    }

    /** Convenience: build flat list of furigana boxes (for callers that don't need group tracking). */
    fun buildFuriganaBoxes(
        ocrResult: OcrManager.OcrResult,
        engine: SourceLanguageEngine,
        furiganaPaint: TextPaint
    ): List<TranslationOverlayView.TextBox> =
        buildFuriganaBoxesByGroup(ocrResult, engine, furiganaPaint).flatMap { it.boxes }

    /** Check if two OCR groups match (same text at same approximate location). */
    fun groupsMatch(
        oldText: String, oldBounds: Rect,
        newText: String, newBounds: Rect
    ): Boolean {
        if (isSignificantChange(oldText, newText)) return false
        val tolerance = maxOf(
            oldBounds.width(), oldBounds.height(),
            newBounds.width(), newBounds.height()
        ) / 3
        val dx = Math.abs((oldBounds.left + oldBounds.right) / 2 - (newBounds.left + newBounds.right) / 2)
        val dy = Math.abs((oldBounds.top + oldBounds.bottom) / 2 - (newBounds.top + newBounds.bottom) / 2)
        return dx < tolerance && dy < tolerance
    }

    /**
     * Merge adjacent furigana boxes whose rendered text would overlap.
     * The furigana reading is often wider than the kanji it sits above (e.g., "わたし"
     * over "私"), so we estimate the rendered text extent to detect visual collisions
     * rather than just checking OCR bounds.
     *
     * When [vertical], furigana is to the right of a vertical column — merge
     * along the Y axis (top-to-bottom) instead of the X axis.
     */
    private fun mergeOverlappingFurigana(
        boxes: List<TranslationOverlayView.TextBox>,
        furiganaPaint: TextPaint,
        vertical: Boolean = false
    ): List<TranslationOverlayView.TextBox> {
        if (boxes.size <= 1) return boxes

        if (vertical) {
            // Vertical: furigana boxes are stacked top-to-bottom to the right of the column.
            // Merge boxes that overlap on the Y axis.
            val sorted = boxes.sortedBy { it.bounds.top }
            val merged = mutableListOf<TranslationOverlayView.TextBox>()
            var current = sorted[0]
            var currentBottom = estimateFuriganaBottom(current, furiganaPaint)
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                if (next.bounds.top < currentBottom) {
                    current = TranslationOverlayView.TextBox(
                        translatedText = current.translatedText + next.translatedText,
                        bounds = Rect(
                            minOf(current.bounds.left, next.bounds.left),
                            current.bounds.top,
                            maxOf(current.bounds.right, next.bounds.right),
                            maxOf(current.bounds.bottom, next.bounds.bottom)
                        ),
                        lineCount = 1,
                        isFurigana = true,
                        orientation = current.orientation
                    )
                    currentBottom = estimateFuriganaBottom(current, furiganaPaint)
                } else {
                    merged += current
                    current = next
                    currentBottom = estimateFuriganaBottom(current, furiganaPaint)
                }
            }
            merged += current
            return merged
        }

        // Horizontal: merge along the X axis (existing behavior)
        val sorted = boxes.sortedBy { it.bounds.left }
        val merged = mutableListOf<TranslationOverlayView.TextBox>()
        var current = sorted[0]
        var currentRight = estimateFuriganaRight(current, furiganaPaint)
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.bounds.left < currentRight) {
                current = TranslationOverlayView.TextBox(
                    translatedText = current.translatedText + next.translatedText,
                    bounds = Rect(
                        current.bounds.left,
                        minOf(current.bounds.top, next.bounds.top),
                        maxOf(current.bounds.right, next.bounds.right),
                        maxOf(current.bounds.bottom, next.bounds.bottom)
                    ),
                    lineCount = 1,
                    isFurigana = true
                )
                currentRight = estimateFuriganaRight(current, furiganaPaint)
            } else {
                merged += current
                current = next
                currentRight = estimateFuriganaRight(current, furiganaPaint)
            }
        }
        merged += current
        return merged
    }

    /**
     * Estimate the right edge of a furigana label as it would be rendered.
     * The text is rendered at 0.7× the box height, positioned from box.left.
     */
    private fun estimateFuriganaRight(box: TranslationOverlayView.TextBox, paint: TextPaint): Int {
        val textSizePx = (box.bounds.height() * 0.7f).coerceAtLeast(4f)
        val savedSize = paint.textSize
        paint.textSize = textSizePx
        val textWidth = paint.measureText(box.translatedText)
        paint.textSize = savedSize
        return maxOf(box.bounds.right, (box.bounds.left + textWidth).toInt())
    }

    /** Estimate the bottom edge of a vertical furigana label (text rendered top-to-bottom). */
    private fun estimateFuriganaBottom(box: TranslationOverlayView.TextBox, paint: TextPaint): Int {
        // Each character stacks vertically; estimate total height from char count × char width
        val textSizePx = (box.bounds.width() * 0.7f).coerceAtLeast(4f)
        val charHeight = textSizePx * 1.2f  // line spacing factor
        val totalHeight = box.translatedText.length * charHeight
        return maxOf(box.bounds.bottom, (box.bounds.top + totalHeight).toInt())
    }


    /**
     * Build a mapper from (startCharOffset, endCharOffset) → (left, right) pixel positions,
     * using TextPaint relative proportions scaled to actual OCR element widths.
     *
     * TextPaint provides correct relative ratios (kanji wider than punctuation).
     * ML Kit provides the actual rendered width of each element on screen.
     * Scaling one by the other gives the best of both.
     */
    private fun buildCharToElementMapper(
        elements: List<OcrManager.ElementBox>,
        furiganaPaint: TextPaint
    ): (Int, Int) -> Pair<Int, Int> {
        data class CharMapping(val elemIdx: Int, val offsetInElem: Int)
        val charMap = mutableListOf<CharMapping>()
        for ((ei, elem) in elements.withIndex()) {
            for (ci in elem.text.indices) {
                charMap += CharMapping(ei, ci)
            }
        }

        // Scale TextPaint relative widths to match each element's actual rendered width
        val elemWidths = elements.map { elem ->
            val paintWidths = FloatArray(elem.text.length).also { furiganaPaint.getTextWidths(elem.text, it) }
            val paintTotal = paintWidths.sum()
            val actualTotal = elem.bounds.width().toFloat()
            if (paintTotal > 0f) {
                FloatArray(paintWidths.size) { i -> paintWidths[i] / paintTotal * actualTotal }
            } else {
                FloatArray(elem.text.length) { actualTotal / elem.text.length.coerceAtLeast(1) }
            }
        }

        if (charMap.isEmpty()) return { _, _ -> 0 to 0 }

        return { startOffset: Int, endOffset: Int ->
            val safeStart = startOffset.coerceIn(0, charMap.size - 1)
            val safeEnd = (endOffset - 1).coerceIn(0, charMap.size - 1)

            val startMapping = charMap[safeStart]
            val endMapping = charMap[safeEnd]

            val startElem = elements[startMapping.elemIdx]
            val endElem = elements[endMapping.elemIdx]

            val startWeights = elemWidths[startMapping.elemIdx]
            val startTotalWeight = startWeights.sum()
            val startPrecedingWeight = (0 until startMapping.offsetInElem).sumOf { startWeights[it].toDouble() }.toFloat()
            val left = if (startTotalWeight > 0f)
                startElem.bounds.left + (startPrecedingWeight / startTotalWeight * startElem.bounds.width()).toInt()
            else startElem.bounds.left

            val endWeights = elemWidths[endMapping.elemIdx]
            val endTotalWeight = endWeights.sum()
            val endPrecedingWeight = (0..endMapping.offsetInElem).sumOf { endWeights[it].toDouble() }.toFloat()
            val right = if (endTotalWeight > 0f)
                endElem.bounds.left + (endPrecedingWeight / endTotalWeight * endElem.bounds.width()).toInt()
            else endElem.bounds.right

            left to right
        }
    }

    // ── OCR pipeline ──────────────────────────────────────────────────────

    data class OcrPipelineResult(
        val ocrResult: OcrManager.OcrResult,
        val dedupKey: String,
        val cropLeft: Int, val cropTop: Int,
        val screenshotW: Int, val screenshotH: Int
    )

    /**
     * Compute the OCR input crop bounds for a raw screenshot. Single source of
     * truth for the status-bar clamp — both [runOcrPipeline] (production) and
     * [PlayTranslateAccessibilityService.runDebugCapture] (debug overlay) call
     * this so they exclude the same pixel rows from ML Kit. Callers do their
     * own [Bitmap.createBitmap] afterward to keep recycle ownership local.
     */
    fun computeOcrCrop(
        rawWidth: Int,
        rawHeight: Int,
        activeRegion: RegionEntry,
        statusBarHeight: Int,
    ): Rect {
        val top    = maxOf((rawHeight * activeRegion.top).toInt(), statusBarHeight)
        val left   = (rawWidth  * activeRegion.left).toInt()
        val bottom = (rawHeight * activeRegion.bottom).toInt()
        val right  = (rawWidth  * activeRegion.right).toInt()
        return Rect(left, top, right, bottom)
    }

    /**
     * Crop to active region, blackout floating icon, run OCR, filter source-lang chars.
     * Returns null if no text detected. Does NOT do dedup, translation, or display.
     */
    suspend fun runOcrPipeline(
        raw: Bitmap,
        activeRegion: RegionEntry,
        sourceLang: String,
        ocrManager: OcrManager,
        statusBarHeight: Int,
        iconRect: Rect?,
        compactIcon: Boolean
    ): OcrPipelineResult? {
        val crop = computeOcrCrop(raw.width, raw.height, activeRegion, statusBarHeight)
        val needsCrop = crop.top > 0 || crop.left > 0 || crop.bottom < raw.height || crop.right < raw.width
        val bitmap = if (needsCrop)
            Bitmap.createBitmap(raw, crop.left, crop.top, (crop.right - crop.left).coerceAtLeast(1), (crop.bottom - crop.top).coerceAtLeast(1))
        else raw

        val ocrResult: OcrManager.OcrResult?
        try {
            val ocrBitmap = blackoutFloatingIcon(bitmap, crop.left, crop.top, iconRect, compactIcon)
            try {
                ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            } finally {
                if (ocrBitmap !== raw && ocrBitmap !== bitmap) ocrBitmap.recycle()
            }
        } finally {
            // Always clean up the crop (NOT raw — caller manages that)
            if (bitmap !== raw && !bitmap.isRecycled) bitmap.recycle()
        }

        if (ocrResult == null) return null

        val dedupKey = ocrResult.fullText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }
        if (dedupKey.isEmpty()) return null

        return OcrPipelineResult(ocrResult, dedupKey, crop.left, crop.top, raw.width, raw.height)
    }

    /**
     * Black out the floating icon area in a bitmap so OCR doesn't read it.
     * Pure function — takes icon rect as parameter.
     */
    fun blackoutFloatingIcon(
        bitmap: Bitmap, cropLeft: Int, cropTop: Int,
        iconRect: Rect?, compactIcon: Boolean
    ): Bitmap {
        if (compactIcon) return bitmap
        if (iconRect == null) return bitmap
        val left = (iconRect.left - cropLeft).coerceAtLeast(0)
        val top = (iconRect.top - cropTop).coerceAtLeast(0)
        val right = (iconRect.right - cropLeft).coerceAtMost(bitmap.width)
        val bottom = (iconRect.bottom - cropTop).coerceAtMost(bitmap.height)
        if (left >= right || top >= bottom) return bitmap
        val mutable = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config, true).also { bitmap.recycle() }
        Canvas(mutable).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), blackoutPaint)
        return mutable
    }

    private val blackoutPaint = Paint().apply { color = Color.BLACK }

    // ── Factory ───────────────────────────────────────────────────────────

}
