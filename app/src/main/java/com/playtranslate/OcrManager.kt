package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.ScreenTextRecognizer
import com.playtranslate.language.ScreenTextRecognizerFactory
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegment
import java.util.concurrent.ConcurrentHashMap

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

    // Lazy cache of recognizers keyed by OCR backend. Phase 1 only ever
    // populates the OcrBackend.MLKitJapanese entry, identical to the old
    // single-recognizer pattern. Later phases use this map to switch
    // backends per source language.
    private val recognizers = ConcurrentHashMap<OcrBackend, ScreenTextRecognizer>()

    private fun recognizerFor(sourceLang: String): ScreenTextRecognizer {
        val profile = SourceLanguageProfiles.forCode(sourceLang)
            ?: SourceLanguageProfiles[SourceLangId.JA]
        return recognizers.getOrPut(profile.ocrBackend) {
            ScreenTextRecognizerFactory.create(profile.ocrBackend)
        }
    }

    /**
     * Drop every cached recognizer and close its underlying ML Kit client.
     *
     * Wired only into [com.playtranslate.PlayTranslateApplication.onTrimMemory]
     * at [android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE] — the
     * one signal that guarantees no foreground service (and therefore no
     * in-flight OCR) is running. Calling this while [recognise] is mid-call
     * would close the client out from under the ML Kit worker, so do NOT
     * hook this into pack-uninstall or any other UI-driven path.
     */
    fun releaseAll() {
        val snapshot = recognizers.keys.toList()
        for (backend in snapshot) {
            recognizers.remove(backend)?.close()
        }
    }


    /** A bounding box with optional confidence for debug overlay. */
    data class DebugBox(
        val bounds: Rect,
        val confidence: Float = -1f,
        val text: String = "",
        val lang: String = ""
    )

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

    /** A single OCR element's text and bounding box within a line. */
    data class ElementBox(
        val text: String,
        val bounds: Rect
    )

    /** A single character (ML Kit Symbol) with its exact bounding box.
     *  [charOffset] is the character's position within the containing line's
     *  processed text string. Consumers filter symbols by offset range rather
     *  than assuming 1:1 positional alignment — spaces and missing symbols
     *  simply have no entry, which is correct. */
    data class SymbolBox(
        val text: String,
        val bounds: Rect,
        val charOffset: Int,
    )

    /** A per-line bounding box with its processed text and group association. */
    data class LineBox(
        /** Processed text of this line (decorations stripped, pipes trimmed). */
        val text: String,
        /** Bounding box in original (pre-scale) bitmap coordinates. */
        val bounds: Rect,
        /** Index of the group this line belongs to. */
        val groupIndex: Int,
        /** Per-element bounding boxes within this line (for precise character positioning). */
        val elements: List<ElementBox> = emptyList(),
        /** Per-character symbols with exact bounds from ML Kit. Empty if unavailable. */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL
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
        /** Number of OCR lines per group (for skeleton placeholder display). */
        val groupLineCounts: List<Int> = emptyList(),
        /** Per-line bounding boxes with processed text, for furigana positioning. */
        val lineBoxes: List<LineBox> = emptyList(),
        /** Debug bounding boxes at block/line/element level, or null if debug is off. */
        val debugBoxes: OcrDebugBoxes? = null,
        /** Orientation per group (majority vote of constituent lines). */
        val groupOrientations: List<TextOrientation> = emptyList()
    )

    suspend fun recognise(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        collectDebugBoxes: Boolean = false,
        screenshotWidth: Int = 0,
        recipe: OcrPreprocessingRecipe = OcrPreprocessingRecipe.Default
    ): OcrResult? {
        val processed = recipe.apply(bitmap, sampleIsDarkBackground(bitmap))
        val scaleFactor = processed.width.toFloat() / bitmap.width
        val addWordSpaces = SourceLanguageProfiles.forCode(sourceLang)?.wordsSeparatedByWhitespace ?: false

        val visionText: Text = try {
            recognizerFor(sourceLang).recognize(processed)
        } finally {
            if (processed !== bitmap) processed.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // 2. Group lines by proximity, size, and alignment (not blocks — blocks
        //    can contain spatially distant lines that shouldn't be merged).
        // 3. Discard groups that contain no character from the source language's script.
        val rawGroups = groupLinesByProximity(visionText.textBlocks, sourceLang)
            .filter { group ->
                group.any { line -> line.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        if (rawGroups.isEmpty()) return null

        val splitResult = if (screenshotWidth > 0) {
            splitMenuGroups(rawGroups, screenshotWidth * scaleFactor)
        } else rawGroups.map { SplitGroup(it) }
        val groups = splitResult.map { it.lines }

        val segments = mutableListOf<TextSegment>()
        val fullTextBuilder = StringBuilder()
        val groupTexts = mutableListOf<String>()
        val lineBoxes = mutableListOf<LineBox>()

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
                val lineBuilder = StringBuilder()
                val lineElements = mutableListOf<ElementBox>()
                val lineSymbols = mutableListOf<SymbolBox>()
                var lineCharCount = 0
                line.elements.forEachIndexed { ei, element ->
                    if (!isUiDecoration(element.text)) {
                        var text = element.text
                        // Strip leading | from first element, trailing | from last
                        if (ei == 0) text = text.trimStart('|').trimStart()
                        if (ei == line.elements.lastIndex) text = text.trimEnd('|').trimEnd()
                        if (text.isNotEmpty()) {
                            if (addWordSpaces && lineCharCount > 0) {
                                fullTextBuilder.append(' ')
                                groupBuilder.append(' ')
                                lineBuilder.append(' ')
                                segments += TextSegment(" ", isSeparator = true)
                                lineCharCount++
                            }
                            val elementOffset = lineCharCount
                            fullTextBuilder.append(text)
                            groupBuilder.append(text)
                            lineBuilder.append(text)
                            lineCharCount += text.length
                            segments += TextSegment(text)
                            // Collect element bounding box for precise furigana positioning
                            element.boundingBox?.let { ebb ->
                                lineElements += ElementBox(
                                    text = text,
                                    bounds = Rect(
                                        (ebb.left / scaleFactor).toInt(),
                                        (ebb.top / scaleFactor).toInt(),
                                        (ebb.right / scaleFactor).toInt(),
                                        (ebb.bottom / scaleFactor).toInt()
                                    )
                                )
                            }
                            // Collect per-character symbols with exact bounds
                            lineSymbols += extractElementSymbols(element, text, scaleFactor, elementOffset)
                        }
                    }
                }
                // Collect per-line bounding box for furigana character positioning
                val lineText = lineBuilder.toString()
                if (lineText.isNotEmpty()) {
                    line.boundingBox?.let { bb ->
                        lineBoxes += LineBox(
                            text = lineText,
                            bounds = Rect(
                                (bb.left / scaleFactor).toInt(),
                                (bb.top / scaleFactor).toInt(),
                                (bb.right / scaleFactor).toInt(),
                                (bb.bottom / scaleFactor).toInt()
                            ),
                            groupIndex = gi,
                            elements = lineElements,
                            symbols = lineSymbols,
                            orientation = detectOrientation(line)
                        )
                    }
                }
            }
            val gt = groupBuilder.toString().trim()
            if (gt.isNotBlank()) groupTexts += gt
        }

        val fullText = fullTextBuilder.toString().trim()
        android.util.Log.d("DetectionLog", "OCR raw: ${groupTexts.size} groups")
        for ((i, gt) in groupTexts.withIndex()) {
            android.util.Log.d("DetectionLog", "  group[$i]: \"${gt.take(50)}\"")
        }
        if (fullText.isBlank()) return null

        // Compute group bounding boxes (union of lines per group) in original bitmap coords.
        // For split menu items, use the parent group's left/right so all items align.
        val groupBounds = splitResult.map { sg ->
            val rects = sg.lines.mapNotNull { it.boundingBox }
            val left = sg.parentLeft ?: rects.minOf { it.left }
            val right = sg.parentRight ?: rects.maxOf { it.right }
            Rect(
                (left / scaleFactor).toInt(),
                (rects.minOf { it.top } / scaleFactor).toInt(),
                (right / scaleFactor).toInt(),
                (rects.maxOf { it.bottom } / scaleFactor).toInt()
            )
        }

        val debugBoxes = if (collectDebugBoxes) {
            val blockBoxes = mutableListOf<DebugBox>()
            val lineBoxes = mutableListOf<DebugBox>()
            val elementBoxes = mutableListOf<DebugBox>()
            for (block in visionText.textBlocks) {
                block.boundingBox?.let { blockBoxes += DebugBox(it, text = block.text, lang = block.recognizedLanguage) }
                for (line in block.lines) {
                    val lineConf = if (android.os.Build.VERSION.SDK_INT >= 31) line.confidence else -1f
                    line.boundingBox?.let { lineBoxes += DebugBox(it, lineConf, text = line.text, lang = line.recognizedLanguage) }
                    for (element in line.elements) {
                        val elemConf = if (android.os.Build.VERSION.SDK_INT >= 31) element.confidence else -1f
                        element.boundingBox?.let { elementBoxes += DebugBox(it, elemConf, text = element.text, lang = element.recognizedLanguage) }
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

        val groupLineCounts = groups.map { it.size }

        // Compute per-group orientation by majority vote of constituent lines.
        val groupOrientations = groups.map { group ->
            val verticalCount = group.count { detectOrientation(it) == TextOrientation.VERTICAL }
            if (verticalCount > group.size / 2) TextOrientation.VERTICAL
            else TextOrientation.HORIZONTAL
        }

        return OcrResult(fullText, segments, groupTexts, groupBounds, groupLineCounts, lineBoxes, debugBoxes, groupOrientations)
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
     * Walks [element]'s raw ML Kit symbols and returns a [SymbolBox] for each
     * character in [processedText] (the post-pipe-trim, post-decoration-filter
     * text for this element). Shared between [recognise] and
     * [recogniseWithPositions] so both methods produce symbol lists aligned 1:1
     * with their line text.
     *
     * Symbols whose `text` doesn't match the corresponding character are
     * skipped — this is the same match-and-advance pattern ML Kit requires
     * since its symbol ordering isn't guaranteed to be left-to-right on some
     * RTL inputs. Coordinates are divided by [scaleFactor] to undo the OCR
     * upscale.
     */
    private fun extractElementSymbols(
        element: Text.Element,
        processedText: String,
        scaleFactor: Float,
        startOffset: Int,
    ): List<SymbolBox> {
        val out = mutableListOf<SymbolBox>()
        val rawSymbols = element.symbols
        var symIdx = 0
        for ((charIdx, ch) in processedText.withIndex()) {
            while (symIdx < rawSymbols.size) {
                val sym = rawSymbols[symIdx]
                symIdx++
                if (sym.text == ch.toString()) {
                    sym.boundingBox?.let { sbb ->
                        out += SymbolBox(
                            text = sym.text,
                            bounds = Rect(
                                (sbb.left / scaleFactor).toInt(),
                                (sbb.top / scaleFactor).toInt(),
                                (sbb.right / scaleFactor).toInt(),
                                (sbb.bottom / scaleFactor).toInt()
                            ),
                            charOffset = startOffset + charIdx,
                        )
                    }
                    break
                }
            }
        }
        return out
    }

    /**
     * Samples corner pixels to estimate whether the image has a dark background
     * (suggesting light-on-dark text that should be inverted for OCR).
     *
     * Exposed [internal] so [OcrPreprocessingRecipe] implementations and the
     * instrumented golden-set tests can reuse the same auto-invert decision
     * production uses.
     */
    internal fun sampleIsDarkBackground(bitmap: Bitmap): Boolean {
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
    private fun groupLinesByProximity(blocks: List<Text.TextBlock>, sourceLang: String = "ja"): List<List<Text.Line>> {
        // Extract all lines from all blocks and filter noise.
        val allLines = blocks.flatMap { it.lines }
            .filter { it.boundingBox != null }
            .filter { line ->
                // Drop single-character lines that aren't real words.
                if (line.text.trim().length <= 1) {
                    val blockLang = blocks.firstOrNull { b -> line in b.lines }?.recognizedLanguage
                    if (blockLang == null || blockLang == "und") {
                        val c = line.text.trim().firstOrNull() ?: return@filter false
                        val isKanji = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
                        if (!isKanji) return@filter false
                    }
                }
                // Drop garbled multi-char lines: mostly non-source characters AND
                // low confidence.
                if (android.os.Build.VERSION.SDK_INT >= 31 && line.text.trim().length > 1) {
                    val text = line.text.trim()
                    val sourceCount = text.count { c -> isSourceLangChar(c, sourceLang) }
                    val ratio = sourceCount.toFloat() / text.length
                    if (ratio < 0.30f && line.confidence < 0.35f) return@filter false
                }
                true
            }
        if (allLines.isEmpty()) return emptyList()

        // Partition lines by detected orientation, group each set with its
        // own sort order and axis-aware proximity rules.
        val (verticalLines, horizontalLines) = allLines.partition {
            detectOrientation(it) == TextOrientation.VERTICAL
        }

        // Horizontal: sort top-to-bottom (existing behavior)
        val hGroups = groupLinesOnePass(
            horizontalLines.sortedBy { it.boundingBox!!.top },
            TextOrientation.HORIZONTAL
        )

        // Vertical: sort right-to-left (rightmost column first = Japanese reading order)
        val vGroups = groupLinesOnePass(
            verticalLines.sortedByDescending { it.boundingBox!!.right },
            TextOrientation.VERTICAL
        )

        return hGroups + vGroups
    }

    /** Groups pre-sorted lines using orientation-aware proximity rules. */
    private fun groupLinesOnePass(
        sortedLines: List<Text.Line>,
        orientation: TextOrientation
    ): List<List<Text.Line>> {
        if (sortedLines.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Text.Line>>()
        for (line in sortedLines) {
            val lineBox = line.boundingBox ?: continue
            val lastGroup = groups.lastOrNull()
            if (lastGroup != null) {
                val prevBox = lastGroup.last().boundingBox
                val groupRect = if (orientation == TextOrientation.VERTICAL) {
                    // For vertical columns: track group's top edge for alignment
                    val groupTop = lastGroup.mapNotNull { it.boundingBox?.top }.minOrNull() ?: 0
                    Rect(prevBox?.left ?: 0, groupTop, prevBox?.right ?: 0, prevBox?.bottom ?: 0)
                } else {
                    val groupLeft = lastGroup.mapNotNull { it.boundingBox?.left }.minOrNull() ?: 0
                    Rect(groupLeft, prevBox?.top ?: 0, prevBox?.right ?: 0, prevBox?.bottom ?: 0)
                }
                if (wouldGroup(groupRect, lineBox, orientation)) {
                    lastGroup += line
                    continue
                }
            }
            groups += mutableListOf(line)
        }
        return groups
    }

    private data class SplitGroup(
        val lines: List<Text.Line>,
        /** Horizontal bounds from the parent group (scaled coords), set for split menu items. */
        val parentLeft: Int? = null,
        val parentRight: Int? = null
    )

    /**
     * Splits groups that look like menus/lists into individual lines.
     * A group is "menu-like" if it has 3+ lines, is narrow (< 1/3 screen),
     * and its line edges don't cluster the way wrapped paragraph text would.
     * Split items inherit the parent group's left/right bounds for aligned overlays.
     */
    private fun splitMenuGroups(
        groups: List<List<Text.Line>>,
        screenWidthScaled: Float
    ): List<SplitGroup> {
        return groups.flatMap { group ->
            if (group.size >= 4 && isMenuLike(group, screenWidthScaled)) {
                val boxes = group.mapNotNull { it.boundingBox }
                val groupLeft = boxes.minOf { it.left }
                val groupRight = boxes.maxOf { it.right }
                group.map { SplitGroup(listOf(it), parentLeft = groupLeft, parentRight = groupRight) }
            } else {
                listOf(SplitGroup(group))
            }
        }
    }

    private fun isMenuLike(lines: List<Text.Line>, screenWidthScaled: Float): Boolean {
        val boxes = lines.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) return false

        // Layer 1: group width < 1/3 of full screen width
        val groupWidth = boxes.maxOf { it.right } - boxes.minOf { it.left }
        if (groupWidth >= screenWidthScaled / 3f) return false

        // Layer 2: a paragraph clusters on BOTH edges (left margin + right wrap).
        // A menu clusters on at most one edge (alignment side) but scatters on
        // the other (varying item lengths). Allow 1 outlier per edge (the final
        // line of a paragraph is typically shorter).
        val avgLineHeight = boxes.map { it.height() }.average().toFloat()
        val minLeft = boxes.minOf { it.left }
        val maxRight = boxes.maxOf { it.right }
        val clusterThreshold = boxes.size - 1

        val nearMinLeft = boxes.count { it.left - minLeft <= avgLineHeight }
        val nearMaxRight = boxes.count { maxRight - it.right <= avgLineHeight }
        val leftClustered = nearMinLeft >= clusterThreshold
        val rightClustered = nearMaxRight >= clusterThreshold

        // Both edges must cluster for it to be a paragraph — skip split
        if (leftClustered && rightClustered) return false

        return true
    }

    /** A line of OCR text with its bounding box in original (pre-scale) screen coordinates. */
    data class OcrLine(
        val text: String,
        val bounds: Rect,
        /** Index of the group this line belongs to (lines in the same group are combined text). */
        val groupIndex: Int = 0,
        /** Pre-built combined text of the entire group this line belongs to (same logic as [recognise]). */
        val groupText: String = text,
        /**
         * Per-character bounding boxes, aligned 1:1 with [text]. Empty if ML Kit
         * didn't emit symbols for this line (some older model versions). When
         * populated, drag-lookup uses these for precise (non-monospaced) hit
         * testing; empty triggers the legacy charWidth fallback.
         */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL
    )

    /**
     * Runs OCR and returns lines with bounding boxes mapped back to the original
     * bitmap's coordinate space (undoing the internal upscale).
     * Used by drag-to-lookup to hit-test finger position against text lines.
     */
    suspend fun recogniseWithPositions(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        recipe: OcrPreprocessingRecipe = OcrPreprocessingRecipe.Default
    ): List<OcrLine>? {
        val processed = recipe.apply(bitmap, sampleIsDarkBackground(bitmap))
        val scaleFactor = processed.width.toFloat() / bitmap.width
        val addWordSpaces = SourceLanguageProfiles.forCode(sourceLang)?.wordsSeparatedByWhitespace ?: false

        val visionText: Text = try {
            recognizerFor(sourceLang).recognize(processed)
        } finally {
            if (processed !== bitmap) processed.recycle()
        }

        if (visionText.textBlocks.isEmpty()) return null

        // Group lines using the same logic as the main OCR pipeline
        val groups = groupLinesByProximity(visionText.textBlocks, sourceLang)
            .filter { group ->
                group.any { line -> line.text.any { c -> isSourceLangChar(c, sourceLang) } }
            }

        val lines = mutableListOf<OcrLine>()
        groups.forEachIndexed { gi, group ->
            val groupTextBuilder = StringBuilder()
            var groupLineCharCount = 0
            group.forEachIndexed { li, line ->
                if (li > 0) groupTextBuilder.append(" ")
                groupLineCharCount = 0
                line.elements.forEachIndexed { ei, element ->
                    if (!isUiDecoration(element.text)) {
                        var text = element.text
                        if (ei == 0) text = text.trimStart('|').trimStart()
                        if (ei == line.elements.lastIndex) text = text.trimEnd('|').trimEnd()
                        if (text.isNotEmpty()) {
                            if (addWordSpaces && groupLineCharCount > 0) {
                                groupTextBuilder.append(' ')
                                groupLineCharCount++
                            }
                            groupTextBuilder.append(text)
                            groupLineCharCount += text.length
                        }
                    }
                }
            }
            val combinedGroupText = groupTextBuilder.toString().trim()

            for (line in group) {
                val b = line.boundingBox ?: continue
                // Walk elements with the same pipe-trim + decoration-filter +
                // symbol-extraction rules that recognise() uses. Symbols carry
                // charOffset for offset-based lookup in drag-to-lookup.
                val lineTextBuilder = StringBuilder()
                val lineSymbols = mutableListOf<SymbolBox>()
                var lineCharCount = 0
                line.elements.forEachIndexed { ei, element ->
                    if (!isUiDecoration(element.text)) {
                        var text = element.text
                        if (ei == 0) text = text.trimStart('|').trimStart()
                        if (ei == line.elements.lastIndex) text = text.trimEnd('|').trimEnd()
                        if (text.isNotEmpty()) {
                            if (addWordSpaces && lineCharCount > 0) {
                                lineTextBuilder.append(' ')
                                lineCharCount++
                            }
                            val elementOffset = lineCharCount
                            lineTextBuilder.append(text)
                            lineCharCount += text.length
                            lineSymbols += extractElementSymbols(element, text, scaleFactor, elementOffset)
                        }
                    }
                }
                val text = lineTextBuilder.toString()
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
                    groupText = combinedGroupText,
                    symbols = lineSymbols,
                    orientation = detectOrientation(line),
                )
            }
        }
        return lines.ifEmpty { null }
    }

    companion object {
        /** Process-scoped singleton. The TextRecognizer lives for the app's lifetime. */
        val instance: OcrManager by lazy { OcrManager() }

        /**
         * Would two rects be grouped as the same text block?
         * Three checks: intersection (fill leak), inline (same line/column),
         * block (next line/column in paragraph with alignment).
         *
         * When [orientation] is [TextOrientation.VERTICAL], all axis logic is
         * swapped: "inline" checks for vertical continuation in the same column,
         * and "block" checks for horizontal continuation to the next column
         * (right-to-left).
         */
        fun wouldGroup(
            a: Rect,
            b: Rect,
            orientation: TextOrientation = TextOrientation.HORIZONTAL
        ): Boolean {
            if (orientation == TextOrientation.VERTICAL) {
                return wouldGroupVertical(a, b)
            }

            val refH = maxOf(a.height(), b.height())
            if (refH <= 0) return false

            // 1. Intersection: rects physically overlap
            if (Rect.intersects(a, b)) return true

            // 2. Inline: horizontal continuation on the same line
            val aCenterY = (a.top + a.bottom) / 2
            val bCenterY = (b.top + b.bottom) / 2
            val aContainsB = bCenterY in a.top..a.bottom
            val bContainsA = aCenterY in b.top..b.bottom
            if (aContainsB || bContainsA) {
                val dx = if (a.right <= b.left) b.left - a.right
                         else if (b.right <= a.left) a.left - b.right
                         else 0
                if (dx < (refH * 1.5f).toInt()) return true
            }

            // 3. Block: vertical continuation (next line in same paragraph)
            val dy = if (a.bottom <= b.top) b.top - a.bottom
                     else if (b.bottom <= a.top) a.top - b.bottom
                     else 0
            if (dy < (refH * 0.8f).toInt()) {
                val alignTolerance = (refH * 0.5f).toInt()
                val leftAligned = kotlin.math.abs(a.left - b.left) <= alignTolerance
                val centerAligned = kotlin.math.abs(a.centerX() - b.centerX()) <= alignTolerance
                if (leftAligned || centerAligned) {
                    val lo = minOf(a.height(), b.height())
                    val hi = maxOf(a.height(), b.height())
                    if (lo <= 0 || (hi - lo).toDouble() / lo <= 0.30) return true
                }
            }

            return false
        }

        /**
         * Vertical-text variant of [wouldGroup]. Axes are swapped:
         * - "Inline" = vertical continuation in the same column (same X-band)
         * - "Block"  = horizontal continuation to the next column (top-aligned
         *   or center-Y-aligned, right-to-left flow)
         * - Reference dimension is width (column thickness) not height.
         */
        private fun wouldGroupVertical(a: Rect, b: Rect): Boolean {
            val refW = maxOf(a.width(), b.width())
            if (refW <= 0) return false

            // 1. Intersection
            if (Rect.intersects(a, b)) return true

            // 2. Inline: vertical continuation in the same column
            val aCenterX = (a.left + a.right) / 2
            val bCenterX = (b.left + b.right) / 2
            val aContainsB = bCenterX in a.left..a.right
            val bContainsA = aCenterX in b.left..b.right
            if (aContainsB || bContainsA) {
                val dy = if (a.bottom <= b.top) b.top - a.bottom
                         else if (b.bottom <= a.top) a.top - b.bottom
                         else 0
                if (dy < (refW * 1.5f).toInt()) return true
            }

            // 3. Block: horizontal continuation (next column in same paragraph)
            val dx = if (a.left <= b.right && b.right <= a.right) 0
                     else if (b.left <= a.right && a.right <= b.right) 0
                     else if (a.right <= b.left) b.left - a.right
                     else a.left - b.right
            if (dx < (refW * 0.8f).toInt()) {
                val alignTolerance = (refW * 0.5f).toInt()
                val topAligned = kotlin.math.abs(a.top - b.top) <= alignTolerance
                val centerAligned = kotlin.math.abs(a.centerY() - b.centerY()) <= alignTolerance
                if (topAligned || centerAligned) {
                    val lo = minOf(a.width(), b.width())
                    val hi = maxOf(a.width(), b.width())
                    if (lo <= 0 || (hi - lo).toDouble() / lo <= 0.30) return true
                }
            }

            return false
        }

        /**
         * Detects whether a Text.Line is vertical (tategaki) or horizontal
         * based on ML Kit's reported angle and bounding box geometry.
         *
         * Primary signal: [Text.Line.getAngle] — ~90° indicates vertical text.
         * Fallback: bounding box aspect ratio (height/width > 2 for multi-char lines).
         * Single-character lines are ambiguous and default to [TextOrientation.HORIZONTAL].
         */
        fun detectOrientation(line: Text.Line): TextOrientation {
            // Single-character lines are ambiguous — a tall narrow box could be
            // one large character, not a vertical column.
            if (line.text.trim().length <= 1) return TextOrientation.HORIZONTAL

            // Primary: ML Kit angle (~90° = vertical)
            try {
                val angle = line.angle.toDouble()
                if (angle in 60.0..120.0 || angle in -120.0..-60.0) {
                    return TextOrientation.VERTICAL
                }
            } catch (_: Throwable) {
                // getAngle() may not exist in all versions — fall through to geometry
            }

            // Fallback: bounding box aspect ratio
            val bb = line.boundingBox ?: return TextOrientation.HORIZONTAL
            val w = bb.width()
            val h = bb.height()
            if (w > 0 && h.toFloat() / w > 2.0f) return TextOrientation.VERTICAL

            return TextOrientation.HORIZONTAL
        }

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
            else -> {
                // For registered source languages (EN, future Latin, etc.)
                // use the profile's isScriptChar lambda — it knows the correct
                // character ranges. Fallback to non-ASCII heuristic only for
                // source codes that aren't in the profile registry.
                val profile = SourceLanguageProfiles.forCode(sourceLang)
                if (profile != null) profile.isScriptChar(c) else c.code > 0x007F
            }
        }

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
