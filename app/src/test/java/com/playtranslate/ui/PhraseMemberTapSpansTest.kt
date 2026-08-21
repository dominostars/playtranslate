package com.playtranslate.ui

import com.playtranslate.language.PhraseOccurrence
import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [SourceWordLookup.computeTapSpans] — the tap spans that keep
 * single-letter members of detected phrases tappable (tokenize drops
 * sub-2-char words, so without these, "a" in "a great deal" has no span
 * and the phrase is reachable from every member except one). Member
 * positions must come from the PHRASE's own displayed range, never from
 * re-searching the letter: the token list doesn't tile single-letter
 * occurrences, so a bare indexOf("a") binds to an earlier stray duplicate.
 */
class PhraseMemberTapSpansTest {

    private fun tok(s: String) = TokenSpan(surface = s, lookupForm = s)

    private fun spans(
        text: String,
        tokens: List<TokenSpan>,
        phrases: List<PhraseOccurrence>,
    ) = SourceWordLookup.computeTapSpans(text, tokens, emptyMap(), phrases)

    @Test fun `single-letter phrase member gains a tap span at its position`() {
        val text = "a great deal of effort"
        val s = spans(
            text,
            listOf(tok("great"), tok("deal"), tok("of"), tok("effort")),
            listOf(PhraseOccurrence(0..11, "a great deal", "a great deal")),
        )
        val aSpan = s.firstOrNull { 0 in it.first }
        assertNotNull("tapping the leading 'a' must resolve a span", aSpan)
        assertEquals("a", aSpan!!.second)
        assertNotNull(s.firstOrNull { 2 in it.first && it.second == "great" })
        assertNotNull(s.firstOrNull { 8 in it.first && it.second == "deal" })
    }

    @Test fun `stray duplicate letter before the phrase cannot steal the member span`() {
        // "cat a a great deal": the first standalone "a" (offset 4) is NOT a
        // phrase member; the second (offset 6) is. The member span must land
        // on 6 and the stray must stay span-less.
        val text = "cat a a great deal"
        val s = spans(
            text,
            listOf(tok("cat"), tok("great"), tok("deal")),
            listOf(PhraseOccurrence(6..17, "a great deal", "a great deal")),
        )
        val memberSpan = s.firstOrNull { it.second == "a" }
        assertNotNull(memberSpan)
        assertEquals(6..6, memberSpan!!.first)
        assertNull("the stray 'a' outside the phrase must stay span-less",
            s.firstOrNull { 4 in it.first })
    }

    @Test fun `mid-phrase single-letter members are covered`() {
        val text = "il y a trois chats"
        val s = spans(
            text,
            listOf(tok("il"), tok("trois"), tok("chats")),
            listOf(PhraseOccurrence(0..5, "il y a", "il y a")),
        )
        assertNotNull(s.firstOrNull { 3 in it.first && it.second == "y" })
        assertNotNull(s.firstOrNull { 5 in it.first && it.second == "a" })
    }

    @Test fun `display-only newline inside the phrase still yields member spans`() {
        // The occurrence surface came from the analyzed text; the displayed
        // text wrapped the phrase. The whitespace-tolerant phrase match must
        // still anchor the member.
        val displayed = "a great\ndeal of effort"
        val s = spans(
            displayed,
            listOf(tok("great"), tok("deal"), tok("of"), tok("effort")),
            listOf(PhraseOccurrence(0..11, "a great deal", "a great deal")),
        )
        val aSpan = s.firstOrNull { 0 in it.first }
        assertNotNull(aSpan)
        assertEquals("a", aSpan!!.second)
    }

    @Test fun `no phrases means plain computeSpans output`() {
        val text = "a cat sat"
        val s = spans(text, listOf(tok("cat"), tok("sat")), emptyList())
        assertEquals(2, s.size)
        assertNull(s.firstOrNull { 0 in it.first })
    }
}
