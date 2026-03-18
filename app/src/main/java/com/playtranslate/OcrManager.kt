package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.playtranslate.model.TextSegment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps ML Kit's Japanese text recogniser.
 *
 * OCR pipeline:
 *  1. Scale up small crops so fine text has enough pixels to be read accurately.
 *  2. Group TextBlocks by similar line height so same-size text stays together
 *     and different-size text (dialogue vs. UI labels) is split into paragraphs.
 *  3. Filter out groups whose text is entirely ASCII — these are target-language
 *     labels (e.g. "TALK", "HP: 100") that need no translation.
 *  4. Drop individual elements that are purely UI decoration (arrows, angle
 *     brackets used as dialogue cursors, etc.).
 */
class OcrManager private constructor() {

    private val recognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )


    /** A bounding box with optional confidence for debug overlay. */
    data class DebugBox(val bounds: Rect, val confidence: Float = -1f)

    /** Bounding boxes at each OCR hierarchy level, for debug overlay. */
    data class OcrDebugBoxes(
        val blockBoxes: List<DebugBox>,
        val lineBoxes: List<DebugBox>,
        val elementBoxes: List<DebugBox>,
        /** Combined group bounding boxes (union of merged TextBlocks). */
        val groupBoxes: List<DebugBox>,
        /** Scale factor applied during OCR; divide box coords by this to get original coords. */
        val scaleFactor: Float
    )

    data class OcrResult(
        /** Full text joined across groups, suitable for bulk translation. */
        val fullText: String,
        /** Flat list of segments (one per TextElement) for tappable display. */
        val segments: List<TextSegment>,
        /** Text of each OCR group, for per-group translation. */
        val groupTexts: List<String> = emptyList(),
        /** Bounding box per group in original (pre-scale) bitmap coordinates. */
        val groupBounds: List<Rect> = emptyList(),
        /** Debug bounding boxes at block/line/element level, or null if debug is off. */
        val debugBoxes: OcrDebugBoxes? = null
    )

    suspend fun recognise(bitmap: Bitmap, sourceLang: String = "ja", collectDebugBoxes: Boolean = false): OcrResult? {
        val processed = prepareForOcr(bitmap)
        val scaleFactor = processed.width.toFloat() / bitmap.width

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (processed !== bitmap) processed.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // 2. Group lines by proximity, size, and alignment (not blocks — blocks
        //    can contain spatially distant lines that shouldn't be merged).
        // 3. Discard groups that contain no character from the source language's script.
        val groups = groupLinesByProximity(visionText.textBlocks)
            .filter { group ->
                group.any { line -> line.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        if (groups.isEmpty()) return null

        val segments = mutableListOf<TextSegment>()
        val fullTextBuilder = StringBuilder()
        val groupTexts = mutableListOf<String>()

        groups.forEachIndexed { gi, group ->
            if (gi > 0) {
                fullTextBuilder.append(" ")
                segments += TextSegment("\n\n", isSeparator = true)
            }
            val groupBuilder = StringBuilder()
            group.forEachIndexed { li, line ->
                if (li > 0) {
                    fullTextBuilder.append(" ")
                    groupBuilder.append(" ")
                    segments += TextSegment("\n", isSeparator = true)
                }
                line.elements.forEach { element ->
                    if (!isUiDecoration(element.text)) {
                        fullTextBuilder.append(element.text)
                        groupBuilder.append(element.text)
                        segments += TextSegment(element.text)
                    }
                }
            }
            val gt = groupBuilder.toString().trim()
            if (gt.isNotBlank()) groupTexts += gt
        }

        val fullText = fullTextBuilder.toString().trim()
        if (fullText.isBlank()) return null

        // Compute group bounding boxes (union of lines per group) in original bitmap coords
        val groupBounds = groups.map { group ->
            val rects = group.mapNotNull { it.boundingBox }
            Rect(
                (rects.minOf { it.left } / scaleFactor).toInt(),
                (rects.minOf { it.top } / scaleFactor).toInt(),
                (rects.maxOf { it.right } / scaleFactor).toInt(),
                (rects.maxOf { it.bottom } / scaleFactor).toInt()
            )
        }

        val debugBoxes = if (collectDebugBoxes) {
            val blockBoxes = mutableListOf<DebugBox>()
            val lineBoxes = mutableListOf<DebugBox>()
            val elementBoxes = mutableListOf<DebugBox>()
            for (block in visionText.textBlocks) {
                block.boundingBox?.let { blockBoxes += DebugBox(it) }
                for (line in block.lines) {
                    val lineConf = if (android.os.Build.VERSION.SDK_INT >= 31) line.confidence else -1f
                    line.boundingBox?.let { lineBoxes += DebugBox(it, lineConf) }
                    for (element in line.elements) {
                        val elemConf = if (android.os.Build.VERSION.SDK_INT >= 31) element.confidence else -1f
                        element.boundingBox?.let { elementBoxes += DebugBox(it, elemConf) }
                    }
                }
            }
            // Compute combined group bounding boxes (union of grouped lines)
            val groupBoxes = groups.map { group ->
                val rects = group.mapNotNull { it.boundingBox }
                val union = Rect(
                    rects.minOf { it.left },
                    rects.minOf { it.top },
                    rects.maxOf { it.right },
                    rects.maxOf { it.bottom }
                )
                DebugBox(union)
            }
            OcrDebugBoxes(blockBoxes, lineBoxes, elementBoxes, groupBoxes, scaleFactor)
        } else null

        return OcrResult(fullText, segments, groupTexts, groupBounds, debugBoxes)
    }

    /**
     * Returns true for OCR elements that are pure UI decoration rather than
     * dialogue text — arrows used as "more text" cursors, angle brackets used
     * as decorative dialogue borders, etc.  Only matches elements whose entire
     * text content is made up of these symbols so real Japanese text containing
     * similar characters is never silently dropped.
     */
    private fun isUiDecoration(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.all { it in UI_DECORATION_CHARS }
    }

    /**
     * Combined OCR preprocessing: scale + grayscale + contrast + auto-invert
     * in a single bitmap allocation and one GPU-accelerated Canvas draw.
     *
     * - Grayscale removes color noise so contrast operates on pure luminance.
     * - Contrast (2×) pushes mid-tones to black/white for clean binarization.
     * - Auto-invert detects light-on-dark text (common in JRPGs) and flips it
     *   to dark-on-light, which OCR engines are trained on.
     */
    private fun prepareForOcr(bitmap: Bitmap): Bitmap {
        // Determine scale factor
        val minDim = minOf(bitmap.width, bitmap.height)
        var scaleFactor = if (minDim < TARGET_MIN_DIM)
            (TARGET_MIN_DIM.toFloat() / minDim).coerceAtMost(3f)
        else 1f
        // Cap total output size to avoid OOM on narrow crops (e.g. 1920×357 × 3 = 5760×1071)
        val maxDim = 3000
        if (bitmap.width * scaleFactor > maxDim || bitmap.height * scaleFactor > maxDim) {
            scaleFactor = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        }
        val outW = (bitmap.width * scaleFactor).toInt()
        val outH = (bitmap.height * scaleFactor).toInt()

        // Detect if image is light-on-dark by sampling corner brightness
        val isDark = sampleIsDarkBackground(bitmap)

        // Build combined color matrix: grayscale → contrast → optional inversion
        // Grayscale: standard NTSC luminance weights
        val gray = ColorMatrix().apply { setSaturation(0f) }

        // Contrast: output = input * 2.0 - 127.5
        val contrastScale = 2.0f
        val contrastTranslate = (1f - contrastScale) / 2f * 255f
        val contrast = ColorMatrix(floatArrayOf(
            contrastScale, 0f, 0f, 0f, contrastTranslate,
            0f, contrastScale, 0f, 0f, contrastTranslate,
            0f, 0f, contrastScale, 0f, contrastTranslate,
            0f, 0f, 0f, 1f, 0f
        ))
        gray.postConcat(contrast)

        // Inversion for light-on-dark: output = 255 - input
        if (isDark) {
            val invert = ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            gray.postConcat(invert)
        }

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(gray)
        }
        val canvas = Canvas(out)
        if (scaleFactor != 1f) canvas.scale(scaleFactor, scaleFactor)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /**
     * Samples corner pixels to estimate whether the image has a dark background
     * (suggesting light-on-dark text that should be inverted for OCR).
     */
    private fun sampleIsDarkBackground(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val margin = (minOf(w, h) * 0.05f).toInt().coerceAtLeast(1)
        // Sample 8 points around the edges (corners + midpoints)
        val points = listOf(
            margin to margin,                   // top-left
            w - margin to margin,               // top-right
            margin to h - margin,               // bottom-left
            w - margin to h - margin,           // bottom-right
            w / 2 to margin,                    // top-center
            w / 2 to h - margin,                // bottom-center
            margin to h / 2,                    // left-center
            w - margin to h / 2                 // right-center
        )
        var brightnessSum = 0
        for ((x, y) in points) {
            val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            brightnessSum += (android.graphics.Color.red(px) +
                android.graphics.Color.green(px) +
                android.graphics.Color.blue(px)) / 3
        }
        return brightnessSum / points.size < 100
    }

    /**
     * Extracts all lines from all TextBlocks and groups them by proximity,
     * size, and alignment. Operating on lines (not blocks) avoids the issue
     * where ML Kit groups spatially distant lines into a single TextBlock.
     *
     * A line is merged into the current group when ALL of the following hold:
     *  1. Its height is within 20% of the previous line's height.
     *  2. The vertical gap between them is ≤ 2.5× the larger line height.
     *  3. The current group's text does not end with sentence-final punctuation
     *     — those indicate a complete sentence boundary.
     *  4. Horizontal alignment: the line's left edge is within one line height
     *     of the group's left edge, OR the right edges are similarly aligned.
     */
    private fun groupLinesByProximity(blocks: List<Text.TextBlock>): List<List<Text.Line>> {
        // Extract all lines from all blocks, sorted top-to-bottom.
        // Filter out low-confidence single-character lines (e.g. game UI arrows
        // misdetected as "く") on API 31+ where confidence is available.
        val allLines = blocks.flatMap { it.lines }
            .filter { it.boundingBox != null }
            .filter { line ->
                // Drop single-character lines in blocks with undetermined language
                // (catches game UI arrows/symbols misdetected as Japanese characters).
                if (line.text.trim().length <= 1) {
                    val blockLang = blocks.firstOrNull { b -> line in b.lines }?.recognizedLanguage
                    if (blockLang == "und") return@filter false
                }
                true
            }
            .sortedBy { it.boundingBox!!.top }
        if (allLines.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Text.Line>>()

        for (line in allLines) {
            val lineH = line.boundingBox?.height() ?: 0
            val lineBox = line.boundingBox ?: continue
            val lineTop = lineBox.top

            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && lineH > 0) {
                val prevLine = lastGroup.last()
                val prevH = prevLine.boundingBox?.height() ?: 0
                val prevBottom = prevLine.boundingBox?.bottom ?: 0

                val gap = lineTop - prevBottom
                val refH = maxOf(lineH, prevH)

                val sizeMatch = prevH > 0 && run {
                    val lo = minOf(lineH, prevH)
                    val hi = maxOf(lineH, prevH)
                    (hi - lo).toDouble() / lo <= 0.20
                }
                val closeEnough = refH > 0 && gap <= (refH * 2.5f).toInt()
                val noSentenceEnd = run {
                    val tail = lastGroup.joinToString("") { it.text }.trimEnd()
                    tail.isEmpty() || tail.last() !in SENTENCE_END_CHARS
                }

                // Horizontal alignment: left edges or right edges within one line height
                val alignTolerance = refH
                val groupLeft  = lastGroup.mapNotNull { it.boundingBox?.left }.minOrNull() ?: 0
                val groupRight = lastGroup.mapNotNull { it.boundingBox?.right }.maxOrNull() ?: 0
                val leftAligned  = kotlin.math.abs(lineBox.left - groupLeft) <= alignTolerance
                val rightAligned = kotlin.math.abs(lineBox.right - groupRight) <= alignTolerance
                val aligned = leftAligned || rightAligned

                // Merge even past sentence-end punctuation if a quote is still open
                val hasOpenQuote = run {
                    val text = lastGroup.joinToString("") { it.text }
                    OPEN_QUOTES.sumOf { c -> text.count { it == c } } >
                        CLOSE_QUOTES.sumOf { c -> text.count { it == c } }
                }

                if (sizeMatch && closeEnough && aligned && (noSentenceEnd || hasOpenQuote)) {
                    lastGroup += line
                    continue
                }
            }

            groups += mutableListOf(line)
        }

        return groups
    }

    /** A line of OCR text with its bounding box in original (pre-scale) screen coordinates. */
    data class OcrLine(
        val text: String,
        val bounds: Rect,
        /** Index of the group this line belongs to (lines in the same group are combined text). */
        val groupIndex: Int = 0,
        /** Pre-built combined text of the entire group this line belongs to (same logic as [recognise]). */
        val groupText: String = text
    )

    /**
     * Runs OCR and returns lines with bounding boxes mapped back to the original
     * bitmap's coordinate space (undoing the internal upscale).
     * Used by drag-to-lookup to hit-test finger position against text lines.
     */
    suspend fun recogniseWithPositions(bitmap: Bitmap, sourceLang: String = "ja"): List<OcrLine>? {
        val processed = prepareForOcr(bitmap)
        val scaleFactor = processed.width.toFloat() / bitmap.width

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(processed, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (processed !== bitmap) processed.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // Group lines using the same logic as the main OCR pipeline
        val groups = groupLinesByProximity(visionText.textBlocks)
            .filter { group ->
                group.any { line -> line.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        val lines = mutableListOf<OcrLine>()
        groups.forEachIndexed { gi, group ->
            val groupTextBuilder = StringBuilder()
            group.forEachIndexed { li, line ->
                if (li > 0) groupTextBuilder.append(" ")
                line.elements.forEach { element ->
                    if (!isUiDecoration(element.text)) {
                        groupTextBuilder.append(element.text)
                    }
                }
            }
            val combinedGroupText = groupTextBuilder.toString().trim()

            for (line in group) {
                val b = line.boundingBox ?: continue
                val text = line.elements
                    .filter { !isUiDecoration(it.text) }
                    .joinToString("") { it.text }
                if (text.isBlank()) continue
                lines += OcrLine(
                    text = text,
                    bounds = Rect(
                        (b.left / scaleFactor).toInt(),
                        (b.top / scaleFactor).toInt(),
                        (b.right / scaleFactor).toInt(),
                        (b.bottom / scaleFactor).toInt()
                    ),
                    groupIndex = gi,
                    groupText = combinedGroupText
                )
            }
        }
        return lines.ifEmpty { null }
    }

    companion object {
        /** Process-scoped singleton. The TextRecognizer lives for the app's lifetime. */
        val instance: OcrManager by lazy { OcrManager() }

        /**
         * Minimum pixel count on the shorter side before we skip upscaling.
         * 1200px balances OCR accuracy against memory usage (~6.6MB vs ~18MB
         * for full-screen captures). ML Kit downscales internally if larger.
         */
        private const val TARGET_MIN_DIM = 1200

        /**
         * Returns true if [c] belongs to a script that is native to [sourceLang].
         * Used to filter out OCR groups that contain no source-language characters —
         * e.g. romanizations, symbols, or Latin-with-diacritics when translating from Japanese.
         */
        fun isSourceLangChar(c: Char, sourceLang: String): Boolean = when (sourceLang) {
            "ja" -> c in '\u3040'..'\u309F'   // Hiragana
                 || c in '\u30A0'..'\u30FF'   // Katakana
                 || c in '\u4E00'..'\u9FFF'   // CJK Unified Ideographs (kanji)
                 || c in '\u3400'..'\u4DBF'   // CJK Extension A
                 || c in '\uFF65'..'\uFF9F'   // Half-width Katakana
            "zh", "zh-TW" ->
                   c in '\u4E00'..'\u9FFF'
                 || c in '\u3400'..'\u4DBF'
            "ko" -> c in '\uAC00'..'\uD7AF'   // Hangul Syllables
                 || c in '\u1100'..'\u11FF'   // Hangul Jamo
                 || c in '\u3130'..'\u318F'   // Hangul Compatibility Jamo
            "ar" -> c in '\u0600'..'\u06FF'   // Arabic
            "ru", "bg", "uk" ->
                   c in '\u0400'..'\u04FF'   // Cyrillic
            "th" -> c in '\u0E00'..'\u0E7F'   // Thai
            "hi", "mr", "ne" ->
                   c in '\u0900'..'\u097F'   // Devanagari
            else -> c.code > 0x007F            // Generic: any non-ASCII
        }

        /** Sentence-ending punctuation across languages. */
        private val SENTENCE_END_CHARS = setOf(
            '.', '!', '?', '…',                         // Latin / general
            '。', '！', '？',                             // CJK fullwidth
        )

        /** Opening quote/bracket characters for open-quote detection. */
        private val OPEN_QUOTES = charArrayOf('「', '『', '(', '（', '【', '〔', '《', '〈', '\u201C', '\u2018')

        /** Closing quote/bracket characters for open-quote detection. */
        private val CLOSE_QUOTES = charArrayOf('」', '』', ')', '）', '】', '〕', '》', '〉', '\u201D', '\u2019')

        /** UI-only symbols that are never meaningful dialogue text on their own. */
        private val UI_DECORATION_CHARS = setOf(
            // Arrows / triangles used as dialogue-advance or selection cursors
            '▼', '▽', '▲', '△', '▸', '▾', '◂', '◀', '▶', '►', '◄',
            '↓', '↑', '←', '→', '↵', '↩',
            // Angle brackets used as decorative dialogue borders
            '<', '>', '＜', '＞', '〈', '〉', '《', '》', '«', '»'
        )
    }
}
