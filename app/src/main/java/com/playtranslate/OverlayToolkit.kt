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

    fun colorLuminance(color: Int): Double {
        return 0.299 * Color.red(color) +
            0.587 * Color.green(color) +
            0.114 * Color.blue(color)
    }

    fun averageColor(
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
        if (count == 0) return Color.argb(230, 0, 0, 0)
        return Color.argb(230,
            (rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    /**
     * Color-match overlay boxes from a screenshot file.
     * All inputs as parameters — no mode state access.
     */
    fun buildColorMatchedBoxes(
        boxes: List<TranslationOverlayView.TextBox>,
        screenshotPath: String,
        cropLeft: Int, cropTop: Int,
        colorScale: Int = 4
    ): List<TranslationOverlayView.TextBox>? {
        val bitmap = android.graphics.BitmapFactory.decodeFile(screenshotPath) ?: return null
        var colorRef: Bitmap? = null
        try {
            colorRef = Bitmap.createScaledBitmap(
                bitmap, bitmap.width / colorScale, bitmap.height / colorScale, false
            )
            bitmap.recycle()
            val colors = sampleGroupColors(colorRef, boxes.map { it.bounds },
                cropLeft, cropTop, colorScale)
            colorRef.recycle()
            return boxes.mapIndexed { idx, box ->
                val (bg, tc) = colors[idx]
                box.copy(bgColor = bg, textColor = tc)
            }
        } catch (_: Exception) {
            return null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            colorRef?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    // ── Furigana box building ─────────────────────────────────────────────

    /**
     * Build furigana (hiragana reading) overlay boxes from OCR result.
     * Uses TextPaint-weighted positioning within OCR element bounds.
     */
    fun buildFuriganaBoxes(
        ocrResult: OcrManager.OcrResult,
        dictionary: DictionaryManager,
        furiganaPaint: TextPaint
    ): List<TranslationOverlayView.TextBox> {
        val boxes = mutableListOf<TranslationOverlayView.TextBox>()

        for (groupIdx in ocrResult.groupTexts.indices) {
            val lines = ocrResult.lineBoxes.filter { it.groupIndex == groupIdx }
            if (lines.isEmpty()) continue

            for (line in lines) {
                if (line.text.isEmpty()) continue
                val furiganaTokens = dictionary.tokenizeForFurigana(line.text)

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
                        (line.bounds.top - furiganaHeight).coerceAtLeast(0),
                        right,
                        line.bounds.top
                    )
                    boxes += TranslationOverlayView.TextBox(
                        translatedText = ft.reading,
                        bounds = furiganaBounds,
                        lineCount = 1,
                        isFurigana = true
                    )
                }
            }
        }
        return boxes
    }

    /**
     * Build a mapper from (startCharOffset, endCharOffset) → (left, right) pixel positions,
     * using TextPaint-weighted character widths within OCR element bounds.
     */
    fun buildCharToElementMapper(
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

        val elemWidths = elements.map { elem ->
            FloatArray(elem.text.length).also { furiganaPaint.getTextWidths(elem.text, it) }
        }

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

        val ocrBitmap = blackoutFloatingIcon(bitmap, left, top, iconRect, compactIcon)
        val ocrResult = ocrManager.recognise(ocrBitmap, sourceLang, screenshotWidth = raw.width)
        if (ocrBitmap !== raw && ocrBitmap !== bitmap) ocrBitmap.recycle()

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
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        Canvas(mutable).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), blackoutPaint)
        return mutable
    }

    private val blackoutPaint = Paint().apply { color = Color.BLACK }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Create a box builder function for the given overlay mode.
     * Used by one-shot to build the right type of boxes without mode checks in the pipeline.
     */
    fun createOverlayBoxBuilder(
        overlayMode: OverlayMode,
        dictionary: DictionaryManager,
        furiganaPaint: TextPaint
    ): ((OcrManager.OcrResult) -> List<TranslationOverlayView.TextBox>)? = when (overlayMode) {
        OverlayMode.FURIGANA -> { ocrResult -> buildFuriganaBoxes(ocrResult, dictionary, furiganaPaint) }
        OverlayMode.TRANSLATION -> null  // translation boxes are built inline with color sampling
    }
}
