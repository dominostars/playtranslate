package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the results list's fresh-render order ([hiddenLast]): visible words
 * first, hidden words after, each group keeping its lookup order; the same
 * instance when nothing in the list is hidden. The fragment applies it only
 * when a list is rendered anew — a toggle re-stubs in place.
 */
class RowStateHiddenOrderTest {

    private fun row(word: String) = RowState(
        displayWord = word, reading = "", meaning = "m", senses = emptyList(),
        freqScore = 0, isCommon = false, surface = word,
    )

    @Test
    fun `visible first, hidden after, both groups stable`() {
        val rows = listOf("a", "b", "c", "d", "e").map(::row)
        val out = rows.hiddenLast(setOf("b", "d", "not-here"))
        assertEquals(listOf("a", "c", "e", "b", "d"), out.map { it.displayWord })
    }

    @Test
    fun `nothing hidden in the list is the same instance`() {
        val rows = listOf("a", "b").map(::row)
        assertSame(rows, rows.hiddenLast(emptySet()))
        assertSame(rows, rows.hiddenLast(setOf("elsewhere")))
    }

    @Test
    fun `all hidden keeps lookup order`() {
        val rows = listOf("a", "b", "c").map(::row)
        assertEquals(listOf("a", "b", "c"), rows.hiddenLast(setOf("a", "b", "c")).map { it.displayWord })
    }
}
