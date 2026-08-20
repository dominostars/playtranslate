package com.playtranslate.ui

import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [SourceWordLookup.computeSpans]'s span mapping, in particular the
 * whitespace-tolerant retry for fused phrase spans: the tokenizer runs on
 * the ORIGINAL text but the displayed text may carry OCR line breaks, so a
 * phrase whose surface is "a great deal" must still map when the display
 * wrapped it as "a great\ndeal". Single words keep exact-match-only
 * semantics — a reflowed single word was never mapped before and still
 * isn't.
 */
class SourceWordLookupSpanTest {

    private fun span(surface: String, lookupForm: String = surface) =
        TokenSpan(surface = surface, lookupForm = lookupForm, reading = null)

    @Test fun `maps single words exactly as before`() {
        val text = "a great deal"
        val spans = SourceWordLookup.computeSpans(text, listOf(span("great"), span("deal")), emptyMap())
        assertEquals(listOf(2..6 to "great", 8..11 to "deal"), spans.map { it.first to it.second })
    }

    @Test fun `maps a fused phrase span verbatim`() {
        val text = "worth a great deal today"
        val spans = SourceWordLookup.computeSpans(
            text, listOf(span("worth"), span("a great deal"), span("today")), emptyMap(),
        )
        assertEquals(
            listOf(0..4 to "worth", 6..17 to "a great deal", 19..23 to "today"),
            spans.map { it.first to it.second },
        )
    }

    @Test fun `maps a phrase across a display line break`() {
        // Tokenized text had a space; the display wrapped it to a newline.
        val displayed = "worth a great\ndeal today"
        val spans = SourceWordLookup.computeSpans(
            displayed, listOf(span("worth"), span("a great deal"), span("today")), emptyMap(),
        )
        assertEquals(
            listOf(0..4 to "worth", 6..17 to "a great deal", 19..23 to "today"),
            spans.map { it.first to it.second },
        )
        // The range covers the wrapped display slice, newline included.
        assertEquals("a great\ndeal", displayed.substring(spans[1].first.first, spans[1].first.last + 1))
    }

    @Test fun `search continues after a tolerant match`() {
        val displayed = "give\nup now"
        val spans = SourceWordLookup.computeSpans(
            displayed, listOf(span("give up", "give up"), span("now")), emptyMap(),
        )
        assertEquals(listOf("give up", "now"), spans.map { it.second })
        assertEquals(8..10, spans[1].first)
    }

    @Test fun `unmatched single words are skipped, later spans still map`() {
        val displayed = "great deal"
        val spans = SourceWordLookup.computeSpans(
            displayed, listOf(span("missing"), span("deal")), emptyMap(),
        )
        assertEquals(listOf("deal"), spans.map { it.second })
    }
}
