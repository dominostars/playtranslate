package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.dictionary.FuriganaToken
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
        dictionary: DictionaryManager,
        furiganaPaint: TextPaint
    ): List<FuriganaGroup> {
        val groups = mutableListOf<FuriganaGroup>()

        for (groupIdx in ocrResult.groupTexts.indices) {
            val lines = ocrResult.lineBoxes.filter { it.groupIndex == groupIdx }
            if (lines.isEmpty()) continue

            val groupBoxes = mutableListOf<TranslationOverlayView.TextBox>()
            for (line in lines) {
                if (line.text.isEmpty()) continue
                val furiganaTokens = dictionary.tokenizeForFurigana(line.text)
                val lineBoxes = mutableListOf<TranslationOverlayView.TextBox>()

                if (line.symbols.isNotEmpty()) {
                    // Symbol-based positioning: use exact ML Kit character bounds
                    var searchFrom = 0
                    for (ft in furiganaTokens) {
                        val matchStart = findConsecutiveSymbols(line.symbols, ft.kanjiSurface, searchFrom)
                        if (matchStart != null) {
                            val first = line.symbols[matchStart]
                            val last = line.symbols[matchStart + ft.kanjiSurface.length - 1]
                            searchFrom = matchStart + ft.kanjiSurface.length

                            val furiganaHeight = (first.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                            lineBoxes += TranslationOverlayView.TextBox(
                                translatedText = ft.reading,
                                bounds = Rect(
                                    first.bounds.left,
                                    first.bounds.top - furiganaHeight,
                                    last.bounds.right,
                                    first.bounds.top
                                ),
                                lineCount = 1,
                                isFurigana = true
                            )
                        }
                    }
                } else {
                    // Fallback: TextPaint estimation (no symbols available)
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

                    for (ft in furiganaTokens) {
                        val (left, right) = positionMapper(ft.startOffset, ft.endOffset)
                        val furiganaHeight = (line.bounds.height() * 0.75f).toInt().coerceAtLeast(1)
                        val furiganaBounds = Rect(
                            left,
                            line.bounds.top - furiganaHeight,
                            right,
                            line.bounds.top
                        )
                        lineBoxes += TranslationOverlayView.TextBox(
                            translatedText = ft.reading,
                            bounds = furiganaBounds,
                            lineCount = 1,
                            isFurigana = true
                        )
                    }
                }

                groupBoxes += mergeOverlappingFurigana(lineBoxes, furiganaPaint)
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
        dictionary: DictionaryManager,
        furiganaPaint: TextPaint
    ): List<TranslationOverlayView.TextBox> =
        buildFuriganaBoxesByGroup(ocrResult, dictionary, furiganaPaint).flatMap { it.boxes }

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
     * over "私"), so we estimate the rendered text width to detect visual collisions
     * rather than just checking OCR bounds.
     */
    private fun mergeOverlappingFurigana(
        boxes: List<TranslationOverlayView.TextBox>,
        furiganaPaint: TextPaint
    ): List<TranslationOverlayView.TextBox> {
        if (boxes.size <= 1) return boxes
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
     * Returns the greater of the bounds right edge or the estimated text right edge.
     */
    private fun estimateFuriganaRight(box: TranslationOverlayView.TextBox, paint: TextPaint): Int {
        val textSizePx = (box.bounds.height() * 0.7f).coerceAtLeast(4f)
        val savedSize = paint.textSize
        paint.textSize = textSizePx
        val textWidth = paint.measureText(box.translatedText)
        paint.textSize = savedSize
        return maxOf(box.bounds.right, (box.bounds.left + textWidth).toInt())
    }

    /**
     * Find the starting index where [text] appears as consecutive symbols in [symbols],
     * searching from index [from]. Returns null if not found.
     */
    private fun findConsecutiveSymbols(
        symbols: List<OcrManager.SymbolBox>, text: String, from: Int
    ): Int? {
        outer@ for (start in from..symbols.size - text.length) {
            for ((i, ch) in text.withIndex()) {
                if (symbols[start + i].text != ch.toString()) continue@outer
            }
            return start
        }
        return null
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
        val top    = maxOf((raw.height * activeRegion.top).toInt(), statusBarHeight)
        val left   = (raw.width  * activeRegion.left).toInt()
        val bottom = (raw.height * activeRegion.bottom).toInt()
        val right  = (raw.width  * activeRegion.right).toInt()
        val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
        val bitmap = if (needsCrop)
            Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
        else raw

        val ocrResult: OcrManager.OcrResult?
        try {
            val ocrBitmap = blackoutFloatingIcon(bitmap, left, top, iconRect, compactIcon)
            ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
            if (ocrBitmap !== raw && ocrBitmap !== bitmap) ocrBitmap.recycle()
        } finally {
            // Always clean up the crop (NOT raw — caller manages that)
            if (bitmap !== raw && !bitmap.isRecycled) bitmap.recycle()
        }

        if (ocrResult == null) return null

        val dedupKey = ocrResult.fullText.filter { c -> OcrManager.isSourceLangChar(c, sourceLang) }
        if (dedupKey.isEmpty()) return null

        return OcrPipelineResult(ocrResult, dedupKey, left, top, raw.width, raw.height)
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
