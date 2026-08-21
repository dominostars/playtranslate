package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [whitespaceMemberWords] — the [SourceLanguageEngine.memberWordsOf]
 * default the space-delimited languages inherit, and the exact behavior the
 * detail page's Words section had before the seam moved into the engine
 * (whitespace split, letter-bearing words only, deduped, empty below two
 * members).
 */
class WhitespaceMemberWordsTest {

    private fun members(headword: String): List<String> =
        whitespaceMemberWords(headword).map { it.lookupForm }

    @Test fun `multi-word headword splits into its words`() {
        assertEquals(listOf("a", "great", "deal"), members("a great deal"))
    }

    @Test fun `single-word headword has no members`() {
        assertEquals(emptyList<String>(), members("door"))
    }

    @Test fun `non-letter tokens drop and repeats dedupe`() {
        assertEquals(listOf("chapter", "and"), members("chapter 7 and and"))
    }

    @Test fun `surface equals lookupForm for split members`() {
        assertEquals(
            listOf("give" to "give", "up" to "up"),
            whitespaceMemberWords("give up").map { it.surface to it.lookupForm },
        )
    }
}
