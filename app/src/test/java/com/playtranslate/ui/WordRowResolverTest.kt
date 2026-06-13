package com.playtranslate.ui

import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM tests for [inflectedFormsByLemma] — the per-lemma aggregation that
 * keeps every distinct inflected form a word appeared as, instead of collapsing
 * to the lemma-deduped first occurrence (which would drop or misreport later
 * forms in the word-result cell).
 */
class WordRowResolverTest {

    private fun span(surface: String, lemma: String, vararg tags: InflectionTag) =
        TokenSpan(surface = surface, lookupForm = lemma, inflections = tags.toList())

    @Test
    fun `same lemma in two forms keeps both, in order`() {
        val forms = inflectedFormsByLemma(
            listOf(
                span("食べたい", "食べる", InflectionTag.DESIDERATIVE),
                span("食べられない", "食べる", InflectionTag.PASSIVE, InflectionTag.NEGATIVE),
            ),
        )
        assertEquals(
            listOf(
                InflectedForm("食べたい", listOf(InflectionTag.DESIDERATIVE)),
                InflectedForm("食べられない", listOf(InflectionTag.PASSIVE, InflectionTag.NEGATIVE)),
            ),
            forms["食べる"],
        )
    }

    @Test
    fun `uninflected first occurrence does not hide a later inflected one`() {
        // The bug Codex flagged: bare 食べる appears before 食べた; the past form
        // must still surface rather than being masked by the first occurrence.
        val forms = inflectedFormsByLemma(
            listOf(
                span("食べる", "食べる"),
                span("食べた", "食べる", InflectionTag.PAST),
            ),
        )
        assertEquals(listOf(InflectedForm("食べた", listOf(InflectionTag.PAST))), forms["食べる"])
    }

    @Test
    fun `identical repeated forms collapse to one`() {
        val forms = inflectedFormsByLemma(
            listOf(
                span("食べた", "食べる", InflectionTag.PAST),
                span("食べた", "食べる", InflectionTag.PAST),
            ),
        )
        assertEquals(listOf(InflectedForm("食べた", listOf(InflectionTag.PAST))), forms["食べる"])
    }

    @Test
    fun `lemma seen only uninflected yields an empty list`() {
        val forms = inflectedFormsByLemma(listOf(span("本", "本")))
        assertEquals(emptyList<InflectedForm>(), forms["本"])
    }
}
