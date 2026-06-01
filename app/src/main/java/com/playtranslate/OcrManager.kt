package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.model.TextSegment
import com.playtranslate.ocr.OcrPipeline
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.registry.OcrEngineRegistry
import androidx.core.graphics.get

/**
 * Public facade for on-device OCR.
 *
 * Resolves the [OcrEngine] for a source language, runs the engine-agnostic
 * [OcrPipeline] (preprocess → engine → shared [com.playtranslate.ocr.core.LayoutAnalyzer]),
 * and projects the grouped result into the [OcrResult] / [OcrLine] shapes the
 * app consumes. All grouping/layout logic lives in `ocr.core`; the per-vendor
 * extraction lives in `ocr.engines`. This class keeps only: engine lifecycle,
 * the result projection, and a few ML-Kit-typed helpers the ML Kit adapter
 * reuses ([detectOrientation], [effectiveAlignLeft], [isSourceLangChar]).
 */
class OcrManager private constructor() {

    /** Debug-only: when true, the grouping kernel logs every candidate line's
     *  MERGE/SPLIT decision to logcat under tag "DetectionLog". Pushed from
     *  [PlayTranslateApplication] on start and from the SettingsRenderer toggle. */
    @Volatile var debugLogGroupingEnabled: Boolean = false

    /** Builds + caches the [com.playtranslate.ocr.core.OcrEngine] per source
     *  language; closed in [releaseAll]. */
    private val registry = OcrEngineRegistry()

    /**
     * Drop every cached engine and close its native resources.
     *
     * Wired only into [com.playtranslate.PlayTranslateApplication.onTrimMemory]
     * at [android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE] — the one
     * signal that guarantees no foreground service (and therefore no in-flight
     * OCR) is running. Calling this while [recognise] is mid-call would close an
     * engine out from under its worker, so do NOT hook it into any UI-driven path.
     */
    fun releaseAll() {
        registry.closeAll()
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
        /** Combined group bounding boxes (union of merged lines). */
        val groupBoxes: List<DebugBox>,
        /** Scale factor applied during OCR; divide box coords by this to get original coords. */
        val scaleFactor: Float
    )

    /** A single OCR element's text and bounding box within a line. */
    data class ElementBox(
        val text: String,
        val bounds: Rect
    )

    /** A single character with its exact bounding box.
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
        /** Per-character symbols with exact bounds. Empty if unavailable. */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL
    )

    /**
     * One grouped paragraph: combined [text], [bounds] (original-bitmap coords),
     * the voted [orientation] + classified [alignment], and its constituent
     * [lines]. Replaces the former parallel group-* lists with one cohesive type
     * — index desync is impossible.
     */
    data class OcrGroup(
        val text: String,
        val bounds: Rect,
        val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        val alignment: TextAlignment = TextAlignment.LEFT,
        val lines: List<LineBox> = emptyList(),
    )

    data class OcrResult(
        /** Full text joined across groups, suitable for bulk translation. */
        val fullText: String,
        /** Flat list of segments (one per TextElement) for tappable display. */
        val segments: List<TextSegment>,
        /** The OCR groups (paragraphs) in reading order — the source of truth. */
        val groups: List<OcrGroup> = emptyList(),
        /** Debug bounding boxes at line/element/group level, or null if debug is off. */
        val debugBoxes: OcrDebugBoxes? = null,
    )

    /**
     * Run OCR and return a grouped, translation-ready [OcrResult] in original
     * bitmap coordinates. Resolves the engine for [sourceLang], runs the shared
     * pipeline, and projects the result.
     */
    suspend fun recognise(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        collectDebugBoxes: Boolean = false,
        screenshotWidth: Int = 0,
        recipe: OcrPreprocessingRecipe = selectOcrRecipe(sourceLang)
    ): OcrResult? {
        val output = OcrPipeline.run(
            engine = registry.engineFor(sourceLang),
            bitmap = bitmap,
            sourceLang = sourceLang,
            screenshotWidth = screenshotWidth,
            recipe = recipe,
            isDarkBackground = sampleIsDarkBackground(bitmap),
            logGrouping = debugLogGroupingEnabled,
        ) ?: return null

        val result = buildOcrResult(output.groups, output.scaleFactor, collectDebugBoxes)
        if (result.fullText.isBlank()) return null

        android.util.Log.d("DetectionLog", "OCR raw: ${result.groups.size} groups")
        for ((i, g) in result.groups.withIndex()) {
            android.util.Log.d("DetectionLog", "  group[$i]: \"${g.text.take(50)}\"")
        }
        return result
    }

    /**
     * Run OCR and return lines with bounding boxes in original bitmap coordinates.
     * Used by drag-to-lookup to hit-test finger position against text lines. Does
     * NOT split menu groups (screenshotWidth = 0), matching the prior behavior.
     */
    suspend fun recogniseWithPositions(
        bitmap: Bitmap,
        sourceLang: String = "ja",
        recipe: OcrPreprocessingRecipe = selectOcrRecipe(sourceLang)
    ): List<OcrLine>? {
        val output = OcrPipeline.run(
            engine = registry.engineFor(sourceLang),
            bitmap = bitmap,
            sourceLang = sourceLang,
            screenshotWidth = 0,
            recipe = recipe,
            isDarkBackground = sampleIsDarkBackground(bitmap),
            logGrouping = debugLogGroupingEnabled,
        ) ?: return null

        return buildOcrLines(output.groups, output.scaleFactor).ifEmpty { null }
    }

    // ── Projection: LayoutGroup (engine-input coords) → app result types ─────

    /** Divide a box by [sf] to map engine-input coords back to original-bitmap coords. */
    private fun scaleRect(r: Rect, sf: Float): Rect =
        if (sf == 1f) r
        else Rect((r.left / sf).toInt(), (r.top / sf).toInt(), (r.right / sf).toInt(), (r.bottom / sf).toInt())

    private fun buildOcrResult(
        groups: List<LayoutGroup>,
        scaleFactor: Float,
        collectDebugBoxes: Boolean,
    ): OcrResult {
        val segments = mutableListOf<TextSegment>()

        val ocrGroups = groups.mapIndexed { gi, group ->
            if (gi > 0) segments += TextSegment("\n\n", isSeparator = true)
            val lines = group.lines.mapIndexed { li, line ->
                if (li > 0) segments += TextSegment("\n", isSeparator = true)
                // `segments` is a display-only projection: ClickableTextView
                // concatenates it to render the original text, and tap-to-lookup
                // re-tokenizes by character offset (segment boundaries and
                // isSeparator are never read). Source it from the universal
                // line.text tier — produced by EVERY engine — not the optional
                // element tier, which is empty for PaddleOCR / whole-region
                // recognizers and otherwise leaves the original text blank. For
                // ML Kit, line.text IS the element concatenation incl. word
                // spacing (MlKitTextMapper.walkLine), so this is display-
                // identical. The per-element / per-char boxes that bounds-based
                // features (drag-lookup, furigana) need still live in LineBox.
                if (line.text.isNotEmpty()) segments += TextSegment(line.text)
                LineBox(
                    text = line.text,
                    bounds = scaleRect(line.box.bounds, scaleFactor),
                    groupIndex = gi,
                    elements = line.elements.map { ElementBox(it.text, scaleRect(it.box.bounds, scaleFactor)) },
                    symbols = line.chars.map { SymbolBox(it.text, scaleRect(it.box.bounds, scaleFactor), it.charOffset) },
                    orientation = line.orientation,
                )
            }
            OcrGroup(
                text = group.text,
                bounds = scaleRect(group.bounds, scaleFactor),
                orientation = group.orientation,
                alignment = group.alignment,
                lines = lines,
            )
        }

        val fullText = groups.joinToString(" ") { it.text }.trim()
        val debugBoxes = if (collectDebugBoxes) buildDebugBoxes(groups, scaleFactor) else null
        return OcrResult(
            fullText = fullText,
            segments = segments,
            groups = ocrGroups,
            debugBoxes = debugBoxes,
        )
    }

    private fun buildOcrLines(groups: List<LayoutGroup>, scaleFactor: Float): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        groups.forEachIndexed { gi, group ->
            for (line in group.lines) {
                out += OcrLine(
                    text = line.text,
                    bounds = scaleRect(line.box.bounds, scaleFactor),
                    groupIndex = gi,
                    groupText = group.text,
                    symbols = line.chars.map { SymbolBox(it.text, scaleRect(it.box.bounds, scaleFactor), it.charOffset) },
                    orientation = line.orientation,
                )
            }
        }
        return out
    }

    /**
     * Debug overlay boxes, projected from the grouped result. Boxes are in
     * engine-input (pre-scale) coordinates with [OcrDebugBoxes.scaleFactor] set,
     * matching the prior contract (consumers divide). The block tier is empty —
     * the vendor-neutral model carries line/element/group levels only.
     */
    private fun buildDebugBoxes(groups: List<LayoutGroup>, scaleFactor: Float): OcrDebugBoxes {
        val lineBoxes = mutableListOf<DebugBox>()
        val elementBoxes = mutableListOf<DebugBox>()
        val groupBoxes = mutableListOf<DebugBox>()
        for (group in groups) {
            groupBoxes += DebugBox(group.bounds)
            for (line in group.lines) {
                lineBoxes += DebugBox(line.box.bounds, text = line.text)
                for (el in line.elements) elementBoxes += DebugBox(el.box.bounds, text = el.text)
            }
        }
        return OcrDebugBoxes(emptyList(), lineBoxes, elementBoxes, groupBoxes, scaleFactor)
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
            val px = bitmap[x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)]
            brightnessSum += (android.graphics.Color.red(px) +
                android.graphics.Color.green(px) +
                android.graphics.Color.blue(px)) / 3
        }
        return brightnessSum / points.size < 100
    }

    /** A line of OCR text with its bounding box in original (pre-scale) screen coordinates. */
    data class OcrLine(
        val text: String,
        val bounds: Rect,
        /** Index of the group this line belongs to (lines in the same group are combined text). */
        val groupIndex: Int = 0,
        /** Pre-built combined text of the entire group this line belongs to. */
        val groupText: String = text,
        /**
         * Per-character bounding boxes, aligned 1:1 with [text]. Empty if the
         * engine didn't emit symbols. When populated, drag-lookup uses these for
         * precise (non-monospaced) hit testing; empty triggers the legacy
         * charWidth fallback.
         */
        val symbols: List<SymbolBox> = emptyList(),
        /** Text orientation detected from ML Kit angle / bounding box geometry. */
        val orientation: TextOrientation = TextOrientation.HORIZONTAL
    )

    companion object {
        /** Process-scoped singleton. Engines live for the app's lifetime. */
        val instance: OcrManager by lazy { OcrManager() }

        /**
         * Characters that should not be treated as the body-text left edge when
         * they appear as a line's first glyph: opening punctuation that visually
         * hangs to the left of body text (brackets, quotes, middle dots), plus
         * glyphs OCR commonly misreads for them. See [effectiveAlignLeft].
         */
        private val HANGING_PUNCT_LEFT = setOf(
            '「', '『', '（', '【', '〔', '《', '〈',
            '(', '[', '{',
            '・', '·',
            '“', '‘', '"', '\'',
            ',',
        )

        /**
         * Effective left edge of [line] for paragraph-alignment checks. If the
         * line begins with a [HANGING_PUNCT_LEFT] character, the returned left is
         * shifted right past that glyph so a body line beneath `「こんにちは`
         * aligns to where `こ` starts. Returns the raw [Rect.left] when no
         * adjustment applies, or null if [line] has no bounding box.
         *
         * Reused by [com.playtranslate.ocr.engines.mlkit.MlKitTextMapper] to
         * populate `RecognizedLine.effectiveAlignLeft`.
         */
        internal fun effectiveAlignLeft(line: Text.Line): Int? {
            val box = line.boundingBox ?: return null
            val firstChar = line.text.firstOrNull { !it.isWhitespace() && it != '|' }
                ?: return box.left
            if (firstChar !in HANGING_PUNCT_LEFT) return box.left
            val symbolWidth = line.elements.firstOrNull()
                ?.symbols?.firstOrNull()?.boundingBox?.width()
            val cap = (box.height() * 1.5f).toInt()
            val charWidth = (symbolWidth ?: box.height()).coerceAtMost(cap)
            return box.left + charWidth
        }

        /**
         * Detects whether a Text.Line is vertical (tategaki) or horizontal based
         * on ML Kit's reported angle and bounding box geometry. ~90° (or a tall
         * aspect ratio on multi-char lines) indicates vertical. Single-character
         * lines are ambiguous and default to horizontal.
         *
         * Reused by [com.playtranslate.ocr.engines.mlkit.MlKitTextMapper].
         */
        fun detectOrientation(line: Text.Line): TextOrientation {
            if (line.text.trim().length <= 1) return TextOrientation.HORIZONTAL
            try {
                val angle = line.angle.toDouble()
                if (angle in 60.0..120.0 || angle in -120.0..-60.0) {
                    return TextOrientation.VERTICAL
                }
            } catch (_: Throwable) {
                // getAngle() may not exist in all versions — fall through to geometry
            }
            val bb = line.boundingBox ?: return TextOrientation.HORIZONTAL
            val w = bb.width()
            val h = bb.height()
            if (w > 0 && h.toFloat() / w > 2.0f) return TextOrientation.VERTICAL
            return TextOrientation.HORIZONTAL
        }

        /**
         * Returns true if [c] belongs to a script native to [sourceLang]. Reused
         * by the ML Kit adapter's line filter. (The layout stage uses the
         * vendor-neutral copy in
         * [com.playtranslate.ocr.core.LayoutAnalyzer.isSourceLangChar].)
         */
        fun isSourceLangChar(c: Char, sourceLang: String): Boolean = when (sourceLang) {
            "ja" -> c in '぀'..'ゟ'   // Hiragana
                 || c in '゠'..'ヿ'   // Katakana
                 || c in '一'..'鿿'   // CJK Unified Ideographs (kanji)
                 || c in '㐀'..'䶿'   // CJK Extension A
                 || c in '･'..'ﾟ'   // Half-width Katakana
            "zh", "zh-TW" ->
                   c in '一'..'鿿'
                 || c in '㐀'..'䶿'
            "ko" -> c in '가'..'힯'   // Hangul Syllables
                 || c in 'ᄀ'..'ᇿ'   // Hangul Jamo
                 || c in '㄰'..'㆏'   // Hangul Compatibility Jamo
            "ar" -> c in '؀'..'ۿ'   // Arabic
            "ru", "bg", "uk" ->
                   c in 'Ѐ'..'ӿ'   // Cyrillic
            "th" -> c in '฀'..'๿'   // Thai
            "hi", "mr", "ne" ->
                   c in 'ऀ'..'ॿ'   // Devanagari
            else -> {
                val profile = SourceLanguageProfiles.forCode(sourceLang)
                if (profile != null) profile.isScriptChar(c) else c.code > 0x007F
            }
        }
    }
}
