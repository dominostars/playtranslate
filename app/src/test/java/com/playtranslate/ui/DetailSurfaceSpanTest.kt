package com.playtranslate.ui

import com.playtranslate.language.InflectionTag
import com.playtranslate.language.TokenSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins [detailSurfaceSpan], the word-detail page's rule for which span of its
 * re-tokenized surface supplies the header's conjugation tags: the span whose
 * lookup form is the page's word, else the first; and [detailSurfaceTags],
 * which takes the chain of the engine's alternative for the page's word when
 * that span stands for another word.
 */
class DetailSurfaceSpanTest {

    @Test
    fun `the span whose lookup form is the word wins over an earlier one`() {
        val particle = TokenSpan("は", "は")
        val verb = TokenSpan("食べた", "食べる", "たべる", listOf(InflectionTag.TA))
        assertSame(verb, detailSurfaceSpan(listOf(particle, verb), "食べる"))
    }

    @Test
    fun `no lookup form matches the word, so the first span stands`() {
        val first = TokenSpan("なかった", "ない", inflections = listOf(InflectionTag.TA))
        val second = TokenSpan("よ", "よ")
        assertSame(first, detailSurfaceSpan(listOf(first, second), "無い"))
    }

    @Test
    fun `an empty tokenization has no span`() {
        assertNull(detailSurfaceSpan(emptyList(), "食べる"))
    }

    private val hajiketa = TokenSpan("弾けた", "弾ける", "はじける", listOf(InflectionTag.TA))

    @Test
    fun `the surface's own word reads the span's chain and asks for no alternatives`() = runBlocking {
        val tags = detailSurfaceTags(listOf(hajiketa), "弾けた", "弾ける") { error("not asked") }
        assertEquals(listOf(InflectionTag.TA), tags)
    }

    @Test
    fun `another word the surface could be reads its alternative's chain`() = runBlocking {
        val asked = mutableListOf<TokenSpan>()
        val tags = detailSurfaceTags(listOf(hajiketa), "弾けた", "弾く") { span ->
            asked += span
            listOf(
                TokenSpan("弾けた", "弾ける", "はじける", listOf(InflectionTag.TA)),
                TokenSpan("弾けた", "弾く", "ひく", listOf(InflectionTag.POTENTIAL, InflectionTag.TA)),
            )
        }
        assertEquals(listOf(InflectionTag.POTENTIAL, InflectionTag.TA), tags)
        assertEquals(listOf(TokenSpan("弾けた", "弾く")), asked)
    }

    @Test
    fun `with no alternative for the word the first span's chain stands`() = runBlocking {
        val tags = detailSurfaceTags(listOf(hajiketa), "弾けた", "はじく") { emptyList() }
        assertEquals(listOf(InflectionTag.TA), tags)
    }

    @Test
    fun `an empty tokenization has no chain`() = runBlocking {
        assertEquals(emptyList<InflectionTag>(), detailSurfaceTags(emptyList(), "弾けた", "弾く") { error("not asked") })
    }
}
