package com.playtranslate.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Plain-JVM tests for [singleWordRow] — the predicate that decides whether a
 * translation result is exactly one token, in which case the Anki button opens
 * the word card directly (no sentence/word toggle) instead of the sentence
 * sheet. Matches the row's surface form against the full source text,
 * whitespace-insensitive, so a lone inflected word matches while a word +
 * particle, a repeat, or trailing punctuation does not.
 */
class SingleWordRowTest {

    private fun row(displayWord: String, surface: String) = RowState(
        displayWord = displayWord,
        reading = "",
        meaning = "x",
        senses = emptyList(),
        freqScore = 0,
        isCommon = false,
        surface = surface,
    )

    private fun settled(vararg rows: RowState) = WordLookupsState.Settled(
        rows = rows.toList(),
        tokenSpans = emptyList(),
        lookupToReading = emptyMap(),
    )

    @Test
    fun `lone word whose surface is the whole text matches`() {
        val r = row(displayWord = "猫", surface = "猫")
        assertSame(r, settled(r).singleWordRow("猫"))
    }

    @Test
    fun `lone inflected word matches on surface not lemma`() {
        // displayWord is the dictionary form 使う; the source showed 使わない.
        val r = row(displayWord = "使う", surface = "使わない")
        assertSame(r, settled(r).singleWordRow("使わない"))
    }

    @Test
    fun `surrounding and inner whitespace is ignored`() {
        val r = row(displayWord = "Hello", surface = "Hello")
        assertSame(r, settled(r).singleWordRow("  Hello\n"))
    }

    @Test
    fun `word plus trailing particle does not match`() {
        // 猫は tokenizes to a single lookup row 猫; は stays in the text.
        val r = row(displayWord = "猫", surface = "猫")
        assertNull(settled(r).singleWordRow("猫は"))
    }

    @Test
    fun `repeated word does not match`() {
        val r = row(displayWord = "猫", surface = "猫")
        assertNull(settled(r).singleWordRow("猫猫"))
    }

    @Test
    fun `trailing punctuation does not match`() {
        val r = row(displayWord = "Hello", surface = "Hello")
        assertNull(settled(r).singleWordRow("Hello!"))
    }

    @Test
    fun `two rows never match`() {
        assertNull(settled(row("猫", "猫"), row("犬", "犬")).singleWordRow("猫犬"))
    }

    @Test
    fun `no rows never match`() {
        assertNull(settled().singleWordRow("猫"))
    }

    @Test
    fun `blank surface never matches`() {
        val r = row(displayWord = "猫", surface = "")
        assertNull(settled(r).singleWordRow(""))
    }
}
