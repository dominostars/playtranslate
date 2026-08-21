package com.playtranslate.ui

import com.playtranslate.language.PhraseOccurrence
import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [SourceWordLookup.tapTokensWithPhraseMembers] — the synthetic tap
 * spans that keep single-letter members of detected phrases tappable
 * (tokenize drops sub-2-char words, so without these, "a" in "a great
 * deal" has no span and the phrase is reachable from every member except
 * one) — and its contract with [SourceWordLookup.computeSpans]: the merged
 * list is in text order, so the sequential span walk covers every member.
 */
class PhraseMemberTapSpansTest {

    private fun tok(s: String) = TokenSpan(surface = s, lookupForm = s)

    @Test fun `single-letter phrase member gains a tap span in text order`() {
        val text = "a great deal of effort"
        val tokens = listOf(tok("great"), tok("deal"), tok("of"), tok("effort"))
        val phrases = listOf(PhraseOccurrence(0..11, "a great deal", "a great deal"))
        val merged = SourceWordLookup.tapTokensWithPhraseMembers(text, tokens, phrases)
        assertEquals(listOf("a", "great", "deal", "of", "effort"), merged.map { it.surface })

        val spans = SourceWordLookup.computeSpans(text, merged, emptyMap())
        val aSpan = spans.firstOrNull { 0 in it.first }
        assertNotNull("tapping the leading 'a' must resolve a span", aSpan)
        assertEquals("a", aSpan!!.second)
        // The longer members keep their spans (ordering didn't derail the
        // sequential walk).
        assertNotNull(spans.firstOrNull { 2 in it.first && it.second == "great" })
        assertNotNull(spans.firstOrNull { 8 in it.first && it.second == "deal" })
    }

    @Test fun `mid-phrase single-letter members are covered too`() {
        val text = "il y a trois chats"
        val tokens = listOf(tok("il"), tok("trois"), tok("chats"))
        val phrases = listOf(PhraseOccurrence(0..5, "il y a", "il y a"))
        val merged = SourceWordLookup.tapTokensWithPhraseMembers(text, tokens, phrases)
        assertEquals(listOf("il", "y", "a", "trois", "chats"), merged.map { it.surface })
        val spans = SourceWordLookup.computeSpans(text, merged, emptyMap())
        assertNotNull(spans.firstOrNull { 3 in it.first && it.second == "y" })
        assertNotNull(spans.firstOrNull { 5 in it.first && it.second == "a" })
    }

    @Test fun `no phrases means the token list is untouched`() {
        val tokens = listOf(tok("great"), tok("deal"))
        assertEquals(
            tokens,
            SourceWordLookup.tapTokensWithPhraseMembers("great deal", tokens, emptyList()),
        )
    }

    @Test fun `single-letter words outside phrases stay span-less`() {
        val text = "a cat sat"
        val tokens = listOf(tok("cat"), tok("sat"))
        // No phrase spans "a" — it must not become tappable.
        val merged = SourceWordLookup.tapTokensWithPhraseMembers(text, tokens, emptyList())
        val spans = SourceWordLookup.computeSpans(text, merged, emptyMap())
        assertNull(spans.firstOrNull { 0 in it.first })
    }
}
