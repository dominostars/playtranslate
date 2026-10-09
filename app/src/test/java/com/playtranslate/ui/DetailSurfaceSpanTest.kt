package com.playtranslate.ui

import com.playtranslate.language.InflectionTag
import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins [detailSurfaceSpan], the word-detail page's rule for which span of its
 * re-tokenized surface supplies the header's conjugation tags: the span whose
 * lookup form is the page's word, else the first.
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
}
