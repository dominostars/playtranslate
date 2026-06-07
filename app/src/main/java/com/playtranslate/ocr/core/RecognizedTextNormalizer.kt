package com.playtranslate.ocr.core

/**
 * Shared, vendor-neutral post-recognition text normalization — the single stage
 * that cleans recognized line text for EVERY engine, run by `OcrPipeline` between
 * recognition and [LayoutAnalyzer]. It folds together the passes that used to live
 * only in the ML Kit adapter (and so silently vanished under Meiki / PaddleOCR /
 * manga-ocr): pipe-trim, UI-decoration stripping, and the noise/garble filter.
 *
 * Each in-scope engine emits one [RecognizedLine] per [RecognizedRegion]
 * ([RegionOrigin.LINE]); this normalizes that line and REBUILDS the region so
 * `region.text == lines[0].text`. That rebuild is load-bearing: grouping and the
 * translation input read `region.text` (LayoutAnalyzer.buildLayoutGroup), while
 * `segments`/furigana read `line.text`/`line.chars` — the two must not diverge, or
 * a Meiki/Paddle line shows clean on screen but sends junk to the translator.
 *
 * Hanging-punctuation alignment is deliberately NOT here — it is a non-mutating
 * layout hint computed on demand in [LayoutAnalyzer.effectiveAlignLeft].
 */
object RecognizedTextNormalizer {

    /** Dialogue-advance / selection cursors and arrows: unambiguous junk. Both
     *  edge-strippable and whole-line-droppable. */
    private val ARROW_CURSOR = setOf(
        '▼', '▽', '▲', '△', '▸', '▾', '◂', '◀', '▶', '►', '◄',
        '↓', '↑', '←', '→', '↵', '↩',
    )

    /** Angle brackets used as decorative dialogue borders — but ALSO real CJK
     *  quote/title marks (《…》). So a region is dropped only when it is ENTIRELY
     *  bracket(s) (a lone `《` the detector split off); these are NEVER edge-stripped
     *  from a mixed line, which would butcher Chinese punctuation. */
    private val ANGLE_BRACKET = setOf(
        '<', '>', '＜', '＞', '〈', '〉', '《', '》', '«', '»',
    )

    /** Leading/trailing run trimmed by [cleanLine]: OCR'd border pipes, cursor
     *  arrows, and surrounding whitespace. (Angle brackets excluded — see above.) */
    private fun isEdgeJunk(c: Char): Boolean =
        c == '|' || c.isWhitespace() || c in ARROW_CURSOR

    /**
     * Normalize one OCR pass. Drops regions that clean away to nothing or read as
     * noise; rebuilds survivors so region text and line text/chars stay consistent.
     */
    fun normalize(regions: List<RecognizedRegion>, sourceLang: String): List<RecognizedRegion> =
        regions.mapNotNull { region ->
            // Only single-line LINE regions are normalized. A whole-region recognizer
            // (manga-ocr bubble) has no inter-line separator policy here, so it passes
            // through untouched rather than silently normalizing only lines[0].
            if (region.lines.size != 1) return@mapNotNull region
            val cleaned = cleanLine(region.lines[0], region.confidence, region.languageUndetermined, sourceLang)
                ?: return@mapNotNull null
            region.copy(text = cleaned.text, lines = listOf(cleaned))
        }

    private fun cleanLine(
        line: RecognizedLine,
        confidence: Float,
        languageUndetermined: Boolean,
        sourceLang: String,
    ): RecognizedLine? {
        // 1. Edge-strip pipes / cursor arrows / whitespace (offset-safe).
        val text = line.text
        var lead = 0
        while (lead < text.length && isEdgeJunk(text[lead])) lead++
        var trail = 0
        while (trail < text.length - lead && isEdgeJunk(text[text.length - 1 - trail])) trail++
        val trimmed = if (lead > 0 || trail > 0) trimEdges(line, lead, trail) else line
        val t = trimmed.text

        // 2. Whole-line decoration: nothing but arrows / angle-brackets / space left
        //    (covers a lone `▼` or `《` region the detector split off).
        if (t.isBlank()) return null
        if (t.all { it.isWhitespace() || it in ARROW_CURSOR || it in ANGLE_BRACKET }) return null

        // 3. Noise / garble. Two signals decide whether a lone glyph is content or a
        //    stray OCR fragment:
        //      • confidence — a low-confidence glyph is noise; a confident one is real
        //        (an expressive `！`/`？`/`…`, a kana/kanji word) and survives;
        //      • language context — a lone non-kanji glyph in a block whose language the
        //        recognizer couldn't classify ([languageUndetermined]) is a stray
        //        fragment. This is the ONLY signal that catches a stray *source-script*
        //        kana (confidence and script-membership both say "keep"), and the only
        //        one left when confidence is absent (pre-API-31 ML Kit). A confident
        //        glyph overrides it; specialist engines leave languageUndetermined
        //        false, so their confident lone `！`/kana always survive.
        val lowConfidence = confidence >= 0f && confidence < 0.35f
        if (t.length == 1) {
            if (lowConfidence) return null
            val c = t[0]
            val isKanji = c in '一'..'鿿' || c in '㐀'..'䶿'
            val confident = confidence >= 0.35f
            if (languageUndetermined && !isKanji && !confident) return null
            return trimmed
        }
        // Multi-char garble: mostly-non-source AND low-confidence (ML Kit's old
        // API-31+ filter, generalized to any engine that reports confidence).
        if (lowConfidence) {
            val sourceCount = t.count { LayoutAnalyzer.isSourceLangChar(it, sourceLang) }
            if (sourceCount.toFloat() / t.length < 0.30f) return null
        }
        return trimmed
    }

    /**
     * Slice [dropLeading] chars off the front and [dropTrailing] off the back of
     * [line]'s text, keeping the char tier consistent: char boxes inside the kept
     * span survive with `charOffset` re-based to the new string; the rest are
     * dropped. The element tier carries no offsets to re-map, so it is cleared on
     * any trim — the char tier drives furigana/drag-lookup, and the element-box
     * furigana mapper already falls back when `elements` is empty.
     */
    private fun trimEdges(line: RecognizedLine, dropLeading: Int, dropTrailing: Int): RecognizedLine {
        val full = line.text
        val end = full.length - dropTrailing
        val newChars = line.chars
            .filter { it.charOffset in dropLeading until end }
            .map { it.copy(charOffset = it.charOffset - dropLeading) }
        return line.copy(
            text = full.substring(dropLeading, end),
            chars = newChars,
            elements = if (line.elements.isEmpty()) line.elements else emptyList(),
        )
    }
}
