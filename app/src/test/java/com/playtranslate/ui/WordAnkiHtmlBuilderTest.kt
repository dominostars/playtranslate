package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WordAnkiHtmlBuilder.buildBackHtml] — PT's own (legacy) word
 * card back. Covers the pitch diagram + frequency list added for JA words and
 * graceful degradation when there's no pitch/frequency data.
 */
class WordAnkiHtmlBuilderTest {

    private fun back(
        reading: String = "ねこ",
        freqScore: Int = 0,
        pitch: List<Int> = emptyList(),
        frequencies: List<FrequencyTag> = emptyList(),
    ) = WordAnkiHtmlBuilder.buildBackHtml(
        word = "猫", reading = reading, pos = "noun", freqScore = freqScore,
        pitch = pitch, frequencies = frequencies,
        imageFilename = null, audioFilename = null, definitionHtml = "<div>cat</div>",
    )

    @Test fun `renders the pitch diagram over the reading when pitch is present`() {
        val html = back(reading = "ねこ", pitch = listOf(1))
        assertTrue("pitch diagram present", html.contains("class=\"pa-m"))
    }

    @Test fun `renders the frequency list (stars + dicts) when frequencies present`() {
        val html = back(freqScore = 2, frequencies = listOf(FrequencyTag("JPDB", "1234", value = 1234.0)))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("★★"))
        assertTrue(html.contains("JPDB: 1234"))
    }

    @Test fun `falls back to plain reading and no freq list without data`() {
        val html = back(reading = "ねこ", freqScore = 0, pitch = emptyList(), frequencies = emptyList())
        assertTrue("plain reading shown", html.contains("ねこ"))
        assertFalse("no pitch markup", html.contains("class=\"pa-m"))
        assertFalse("no freq list", html.contains("<ul>"))
    }

    @Test fun `renders pitch over the word for a kana-only entry with blank reading`() {
        // Kana-only entries collapse the kana into the word and carry no
        // separate reading, but still have pitch — must still render.
        val html = WordAnkiHtmlBuilder.buildBackHtml(
            word = "なるほど", reading = "", pos = "", freqScore = 0,
            pitch = listOf(0), frequencies = emptyList(),
            imageFilename = null, audioFilename = null, definitionHtml = "",
        )
        assertTrue("pitch rendered for kana-only word", html.contains("class=\"pa-m"))
    }

    @Test fun `no pitch over a kanji word with blank reading`() {
        // Guard: the contour must never cover kanji — a blank reading with a
        // kanji word has no kana to map morae onto, so render no diagram.
        val html = WordAnkiHtmlBuilder.buildBackHtml(
            word = "猫", reading = "", pos = "", freqScore = 0,
            pitch = listOf(1), frequencies = emptyList(),
            imageFilename = null, audioFilename = null, definitionHtml = "",
        )
        assertFalse("no pitch over kanji", html.contains("class=\"pa-m"))
    }
}
