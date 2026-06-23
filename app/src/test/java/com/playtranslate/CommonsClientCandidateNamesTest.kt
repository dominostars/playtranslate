package com.playtranslate

import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.sources.CommonsClient
import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Wikimedia Commons filename-guess seam — the load-bearing
 * "iterate-and-tune" surface of recording matching, isolated from networking so
 * it's fast and deterministic.
 */
class CommonsClientCandidateNamesTest {

    private val client = CommonsClient()

    @Test fun german_word_produces_code_and_capitalized_variants() {
        val names = client.candidateFilenames(AudioRequest.word("Wort", null, SourceLangId.DE))
        assertTrue("de-Wort.ogg" in names)
        assertTrue("De-Wort.ogg" in names)
        assertTrue("de-Wort.wav" in names)
    }

    @Test fun traditional_chinese_uses_zh_wiki_code() {
        val names = client.candidateFilenames(AudioRequest.word("字", null, SourceLangId.ZH_HANT))
        assertTrue(names.any { it.startsWith("zh-字.") })
    }

    @Test fun japanese_includes_both_surface_and_reading() {
        val names = client.candidateFilenames(AudioRequest.word("初夏", "しょか", SourceLangId.JA))
        assertTrue(names.any { it.contains("初夏") })
        assertTrue(names.any { it.contains("しょか") })
    }

    @Test fun result_has_no_duplicates() {
        val names = client.candidateFilenames(AudioRequest.word("cat", "cat", SourceLangId.EN))
        assertEquals(names, names.distinct())
    }
}
