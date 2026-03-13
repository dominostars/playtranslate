package com.gamelens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.gamelens.model.TextSegment
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
class OcrManager {

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
        /** Debug bounding boxes at block/line/element level, or null if debug is off. */
        val debugBoxes: OcrDebugBoxes? = null
    )

    suspend fun recognise(bitmap: Bitmap, sourceLang: String = "ja", collectDebugBoxes: Boolean = false): OcrResult? {
        // 1. Scale up if the shorter dimension is small — improves OCR on fine text.
        val scaled = scaleBitmapForOcr(bitmap)
        val scaleFactor = scaled.width.toFloat() / bitmap.width
        // 2. Boost contrast — makes small diacritic marks (dakuten: ぞ vs そ, ば vs は, etc.)
        //    unambiguous by pushing near-white pixels to white and near-black to black.
        //    Game text on flat backgrounds binarises cleanly, reducing ML Kit flip-flopping.
        val enhanced = enhanceContrast(scaled)

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(enhanced, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (enhanced !== scaled) enhanced.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // 2. Group by similar line height.
        // 3. Discard groups that contain no character from the source language's script.
        //    This correctly excludes pure-Latin romanizations ("Yuko"), symbols ("★☆:"),
        //    and Latin-with-diacritics ("Yūko") while keeping mixed text ("裕子Hello").
        val groups = groupBlocksBySize(visionText.textBlocks)
            .filter { group ->
                group.any { block -> block.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        if (groups.isEmpty()) return null

        val segments = mutableListOf<TextSegment>()
        val fullTextBuilder = StringBuilder()
        val groupTexts = mutableListOf<String>()

        groups.forEachIndexed { gi, group ->
            if (gi > 0) {
                fullTextBuilder.append(" ")  // space for translation (no paragraph breaks)
                segments += TextSegment("\n\n", isSeparator = true)
            }
            val groupBuilder = StringBuilder()
            // Blocks within the same group are continuous text (merged by proximity).
            // No separator between them — they flow as one sentence.
            group.forEach { block ->
                block.lines.forEachIndexed { li, line ->
                    if (li > 0) {
                        fullTextBuilder.append(" ")  // space for translation (no line breaks)
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
            }
            val gt = groupBuilder.toString().trim()
            if (gt.isNotBlank()) groupTexts += gt
        }

        val fullText = fullTextBuilder.toString().trim()
        if (fullText.isBlank()) return null

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
            // Compute combined group bounding boxes (union of merged TextBlocks)
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

        return OcrResult(fullText, segments, groupTexts, debugBoxes)
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
     * Applies a strong contrast boost so that small diacritic marks (dakuten, handakuten)
     * are pushed to clearly-on or clearly-off rather than sitting in a grey zone that
     * ML Kit reads inconsistently across frames.
     *
     * The ColorMatrix formula: output = input * scale + translate.
     * With scale=2.0, translate=-127: a pixel at 200 → 273 (clipped to 255, stays white);
     * a pixel at 30 → -67 (clipped to 0, stays black). Grey mid-tones snap to one extreme.
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val scale = 2.0f
        val translate = (1f - scale) / 2f * 255f   // -127.5
        val cm = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /**
     * Scales the bitmap up if its shorter side is ≤ 1600 px.
     * Targets ~2000 px on that axis (capped at 3×) so that small dialogue text
     * has enough pixels to be read correctly.
     *
     * The threshold is set above the Ayn Thor game-screen width (1080 px) so
     * full-screen captures are also upscaled, giving ML Kit larger kanji strokes
     * and reducing confusion between visually similar characters (e.g. 機 vs 横).
     */
    private fun scaleBitmapForOcr(bitmap: Bitmap): Bitmap {
        val minDim = minOf(bitmap.width, bitmap.height)
        if (minDim > 1600) return bitmap
        val scale = (2000f / minDim).coerceAtMost(3f)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true   // bilinear filtering for better quality
        )
    }

    /**
     * Groups TextBlocks into paragraphs based on proximity, size, and alignment.
     *
     * Blocks are processed in top-to-bottom order. A block is merged into the
     * current group when ALL of the following hold:
     *  1. Its median line height is within 20 % of the previous block's height.
     *  2. The vertical gap between them is ≤ 2.5× the larger line height.
     *  3. The current group's text does not end with sentence-final punctuation
     *     — those indicate a complete sentence boundary.
     *  4. Horizontal alignment: the block's left edge is within one line height
     *     of the group's left edge, OR the right edges are similarly aligned.
     */
    private fun groupBlocksBySize(blocks: List<Text.TextBlock>): List<List<Text.TextBlock>> {
        val sorted = blocks.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        if (sorted.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<Text.TextBlock>>()

        for (block in sorted) {
            val blockH = medianLineHeight(block)
            val blockBox = block.boundingBox
            val blockTop = blockBox?.top ?: Int.MAX_VALUE

            val lastGroup = groups.lastOrNull()
            if (lastGroup != null && blockH > 0 && blockBox != null) {
                val prev = lastGroup.last()
                val prevH = medianLineHeight(prev)
                val prevBottom = prev.boundingBox?.bottom ?: 0

                val gap = blockTop - prevBottom
                val refH = maxOf(blockH, prevH)

                val sizeMatch = prevH > 0 && run {
                    val lo = minOf(blockH, prevH)
                    val hi = maxOf(blockH, prevH)
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
                val leftAligned  = kotlin.math.abs(blockBox.left - groupLeft) <= alignTolerance
                val rightAligned = kotlin.math.abs(blockBox.right - groupRight) <= alignTolerance
                val aligned = leftAligned || rightAligned

                // Merge even past sentence-end punctuation if a quote is still open
                val hasOpenQuote = run {
                    val text = lastGroup.joinToString("") { it.text }
                    OPEN_QUOTES.sumOf { c -> text.count { it == c } } >
                        CLOSE_QUOTES.sumOf { c -> text.count { it == c } }
                }

                if (sizeMatch && closeEnough && aligned && (noSentenceEnd || hasOpenQuote)) {
                    lastGroup += block
                    continue
                }
            }

            groups += mutableListOf(block)
        }

        return groups
    }

    private fun medianLineHeight(block: Text.TextBlock): Int {
        val heights = block.lines.mapNotNull { it.boundingBox?.height() }.sorted()
        return if (heights.isEmpty()) 0 else heights[heights.size / 2]
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
        val scaled = scaleBitmapForOcr(bitmap)
        val scaleFactor = scaled.width.toFloat() / bitmap.width
        val enhanced = enhanceContrast(scaled)

        val visionText: Text = try {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(enhanced, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            if (enhanced !== scaled) enhanced.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // Group blocks using the same logic as the main OCR pipeline
        val groups = groupBlocksBySize(visionText.textBlocks)
            .filter { group ->
                group.any { block -> block.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        val lines = mutableListOf<OcrLine>()
        groups.forEachIndexed { gi, group ->
            // Build combined group text using the same logic as recognise():
            // no separator between blocks, space between lines within a block.
            val groupTextBuilder = StringBuilder()
            group.forEach { block ->
                block.lines.forEachIndexed { li, line ->
                    if (li > 0) groupTextBuilder.append(" ")
                    line.elements.forEach { element ->
                        if (!isUiDecoration(element.text)) {
                            groupTextBuilder.append(element.text)
                        }
                    }
                }
            }
            val combinedGroupText = groupTextBuilder.toString().trim()

            for (block in group) {
                for (line in block.lines) {
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
        }
        return lines.ifEmpty { null }
    }

    fun close() = recognizer.close()

    companion object {
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
