package com.playtranslate.ui

import com.playtranslate.language.ReadingOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [withCardTarget], the one rule that places a card's own word among its
 * sentence's rows: a row with the word, read the same way and found as the
 * same text, stands (and the analysis's reading with it); otherwise the
 * target takes the place of the rows it displaces (the one under its word,
 * the one found as its text) or joins at the front, bringing its reading
 * override, as long as the text still holds its surface. A restored row that
 * lost its surface is placed again.
 */
class CardTargetPlacementTest {

    private fun row(word: String, reading: String, surface: String = "") =
        SentenceAnkiHtmlBuilder.WordEntry(word, reading, "$word meaning", surfaceForm = surface)

    private val ichiban = row("一番", "いちばん")
    private val umai = row("うまい", "", "うまく")
    private val hajikeru = row("弾ける", "はじける", "弾けた")
    private val sentence = listOf(ichiban, umai, hajikeru)

    private val hikuOverride = ReadingOverride("弾けた", "弾く", "ひけた")
    private val hiku = ResolvedCardTarget(row("弾く", "ひく", "弾けた"), hikuOverride)

    @Test
    fun `an alternative takes the place of the word the analysis chose for its text`() {
        val placed = withCardTarget(sentence, hiku, TEXT)
        assertEquals(listOf(ichiban, umai, hiku.row), placed.words)
        assertEquals(hikuOverride, placed.readingOverride)
    }

    @Test
    fun `a homograph read differently takes the place of the row under its word`() {
        // The words table is keyed by written form: 方 read ほう gives way.
        val hou = row("方", "ほう")
        val kata = ResolvedCardTarget(row("方", "かた", "方"), ReadingOverride("方", "方", "かた"))
        val placed = withCardTarget(listOf(ichiban, hou, umai), kata, "一番の方がうまい")
        assertEquals(listOf(ichiban, kata.row, umai), placed.words)
        assertEquals(kata.readingOverride, placed.readingOverride)
    }

    @Test
    fun `a member word displaces nothing and joins at the front`() {
        val kiNiNaru = row("気になる", "きになる")
        val ki = ResolvedCardTarget(row("気", "き", "気"), ReadingOverride("気", "気", "き"))
        val placed = withCardTarget(listOf(kiNiNaru), ki, "気になる")
        assertEquals(listOf(ki.row, kiNiNaru), placed.words)
    }

    @Test
    fun `the analysis's own word stands, with its reading`() {
        val same = ResolvedCardTarget(row("弾ける", "はじける", "弾けた"), null)
        val placed = withCardTarget(sentence, same, TEXT)
        assertSame(sentence, placed.words)
        assertNull(placed.readingOverride)
    }

    @Test
    fun `an uninflected word with no recorded surface stands`() {
        // The sentence cache records a surface only where it differs from
        // the word.
        val hou = row("方", "ほう")
        val words = listOf(ichiban, hou)
        val placed = withCardTarget(words, ResolvedCardTarget(row("方", "ほう", "方"), null), "一番の方")
        assertSame(words, placed.words)
    }

    @Test
    fun `the word found as other text gives way to the occurrence the card is for`() {
        // 「ギターを弾く。一番うまく弾けた。」: the analysis has 弾く for the
        // first verb; the card is for 弾く as the 弾けた after it.
        val guitar = row("ギター", "")
        val hikuThere = row("弾く", "ひく")
        val text = "ギターを弾く。一番うまく弾けた。"
        val placed = withCardTarget(listOf(guitar, hikuThere, ichiban, umai, hajikeru), hiku, text)
        assertEquals(listOf(guitar, hiku.row, ichiban, umai), placed.words)
        assertEquals(hikuOverride, placed.readingOverride)
    }

    @Test
    fun `a restored row that lost its surface and senses is placed again`() {
        // A saved-state rebuild keeps word, reading, meaning and frequency;
        // the surface came from the sentence cache, which never held it.
        val flat = SentenceAnkiHtmlBuilder.WordEntry("弾く", "ひく", "弾く meaning")
        val full = hiku.copy(
            row = hiku.row.copy(senses = listOf(SenseDisplay(pos = emptyList(), definition = "to play", misc = emptyList()))),
        )
        val placed = withCardTarget(listOf(flat, ichiban, umai), full, TEXT)
        assertEquals(listOf(full.row, ichiban, umai), placed.words)
        assertEquals(hikuOverride, placed.readingOverride)
    }

    @Test
    fun `a sentence that no longer holds the surface keeps its rows`() {
        val edited = listOf(ichiban, row("書く", "かく", "書けた"))
        val placed = withCardTarget(edited, hiku, "一番うまく書けた！")
        assertSame(edited, placed.words)
        assertNull(placed.readingOverride)
    }

    @Test
    fun `no target keeps the rows`() {
        val placed = withCardTarget(sentence, null, TEXT)
        assertSame(sentence, placed.words)
        assertNull(placed.readingOverride)
    }

    @Test
    fun `a target the engine derives no reading for still takes its place`() {
        val placed = withCardTarget(sentence, hiku.copy(readingOverride = null), TEXT)
        assertEquals(listOf(ichiban, umai, hiku.row), placed.words)
        assertNull(placed.readingOverride)
    }

    private companion object {
        const val TEXT = "一番うまく弾けた！"
    }
}
