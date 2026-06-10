package com.playtranslate.model

/**
 * Single source of truth for projecting recognised text into the flat
 * [TextSegment] list the results UI renders.
 *
 * [com.playtranslate.ui.ClickableTextView.setSegments] concatenates the segment
 * texts into the displayed original string, and tap-to-lookup re-tokenizes by
 * character offset — so segment *boundaries* are display-irrelevant; what
 * matters is that the concatenation reproduces the intended layout. Routing
 * every producer (OCR result, drag-sentence mode, pinhole panel, edit-overlay
 * commit, single-line lookups) through here keeps the separator tokens and the
 * content/separator split identical instead of each call site re-inventing them.
 *
 * Granularity is one content segment per line/run, NOT per character: since
 * boundaries are unused, the coarsest faithful representation is the honest one.
 * Bounds-carrying per-element/char data that drag-lookup and furigana need lives
 * in the OCR model (`OcrManager.LineBox.elements` / `.symbols`), never here.
 */
object TextSegments {

    /** Layout separator placed between lines within a group. */
    const val LINE_SEPARATOR = "\n"

    /** Layout separator placed between groups / blocks. */
    const val GROUP_SEPARATOR = "\n\n"

    /**
     * A single run of text. Embedded newlines are honoured as [LINE_SEPARATOR]s
     * (so a multi-line edit commit keeps its layout); a run with no newline
     * becomes exactly one content segment.
     */
    fun ofText(text: String): List<TextSegment> = ofLines(text.split('\n'))

    /**
     * A flat sequence of text runs joined by [LINE_SEPARATOR]; blank runs are
     * skipped (e.g. pinhole boxes, or the lines of a single block). One content
     * segment per non-blank run.
     */
    fun ofLines(lines: List<String>): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        for (line in lines) {
            if (line.isEmpty()) continue
            if (out.isNotEmpty()) out += TextSegment(LINE_SEPARATOR, isSeparator = true)
            out += TextSegment(line)
        }
        return out
    }

    /**
     * One content segment per group's already-combined text, [GROUP_SEPARATOR]
     * between groups; blank groups are skipped. The canonical OCR-result
     * projection: pass each group's `.text` (exactly the per-group string sent
     * to the translator) so the rendered source is paragraph-grouped to mirror
     * the per-group translation layout, instead of exposing the intra-group OCR
     * line breaks. The group text already carries its language-appropriate
     * intra-group join (space for whitespace languages, none for CJK), set in
     * [com.playtranslate.ocr.core.LayoutAnalyzer].
     */
    fun ofGroupTexts(groupTexts: List<String>): List<TextSegment> {
        val out = mutableListOf<TextSegment>()
        for (text in groupTexts) {
            if (text.isBlank()) continue
            if (out.isNotEmpty()) out += TextSegment(GROUP_SEPARATOR, isSeparator = true)
            out += TextSegment(text)
        }
        return out
    }
}
