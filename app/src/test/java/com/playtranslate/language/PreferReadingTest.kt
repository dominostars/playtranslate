package com.playtranslate.language

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [preferReading] — the entry-selection step that turns a
 * contextual reading hint (set by `ChineseEngine.tokenize`) into the entry
 * `lookup` returns first, so EVERY reading writer (result-screen ViewModel,
 * Anki word cache, …) that flows through `engine.lookup` gets the corrected
 * reading. Pure JVM — no HanLP / dict.
 */
class PreferReadingTest {

    private fun entry(reading: String) = DictionaryEntry(
        slug = reading,
        isCommon = false,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = listOf(Headword(written = "x", reading = reading)),
        senses = emptyList(),
    )

    private fun DictionaryResponse.readings() = entries.map { it.headwords.first().reading }

    @Test fun `floats the matching-reading entry to the front`() {
        // 东西: freq-first dōng xī, but context resolved dōng xi.
        val r = DictionaryResponse(listOf(entry("dōng xī"), entry("dōng xi")))
        assertEquals(listOf("dōng xi", "dōng xī"), r.preferReading("dōng xi").readings())
    }

    @Test fun `keeps frequency order when the hint is null`() {
        val r = DictionaryResponse(listOf(entry("a"), entry("b")))
        assertEquals(listOf("a", "b"), r.preferReading(null).readings())
    }

    @Test fun `keeps frequency order when no entry matches the hint`() {
        val r = DictionaryResponse(listOf(entry("a"), entry("b")))
        assertEquals(listOf("a", "b"), r.preferReading("zzz").readings())
    }

    @Test fun `no-op when the match is already first`() {
        val r = DictionaryResponse(listOf(entry("a"), entry("b")))
        assertEquals(listOf("a", "b"), r.preferReading("a").readings())
    }

    @Test fun `preserves the relative order of the non-matching entries`() {
        val r = DictionaryResponse(listOf(entry("a"), entry("b"), entry("c")))
        assertEquals(listOf("c", "a", "b"), r.preferReading("c").readings())
    }
}
