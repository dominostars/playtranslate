package com.playtranslate.ui

import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.TokenSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain-JVM tests for [rowInflectedForms], which keeps every distinct inflected
 * form a word appeared as, instead of collapsing to the lemma-deduped first
 * occurrence (which would drop or misreport later forms in the word-result
 * cell), and puts the lookup's own deinflection chain ahead of each form's
 * token tags.
 */
class WordRowResolverTest {

    private fun span(surface: String, lemma: String, vararg tags: InflectionTag) =
        TokenSpan(surface = surface, lookupForm = lemma, inflections = tags.toList())

    @Test
    fun `same lemma in two forms keeps both, in order`() {
        val forms = rowInflectedForms(
            listOf(
                span("食べたい", "食べる", InflectionTag.TAI),
                span("食べられない", "食べる", InflectionTag.PASSIVE, InflectionTag.NEGATIVE),
            ),
            deinflection = emptyList(),
        )
        assertEquals(
            listOf(
                InflectedForm("食べたい", listOf(InflectionTag.TAI)),
                InflectedForm("食べられない", listOf(InflectionTag.PASSIVE, InflectionTag.NEGATIVE)),
            ),
            forms,
        )
    }

    @Test
    fun `uninflected first occurrence does not hide a later inflected one`() {
        // The bug Codex flagged: bare 食べる appears before 食べた; the past form
        // must still surface rather than being masked by the first occurrence.
        val forms = rowInflectedForms(
            listOf(
                span("食べる", "食べる"),
                span("食べた", "食べる", InflectionTag.TA),
            ),
            deinflection = emptyList(),
        )
        assertEquals(listOf(InflectedForm("食べた", listOf(InflectionTag.TA))), forms)
    }

    @Test
    fun `identical repeated forms collapse to one`() {
        val forms = rowInflectedForms(
            listOf(
                span("食べた", "食べる", InflectionTag.TA),
                span("食べた", "食べる", InflectionTag.TA),
            ),
            deinflection = emptyList(),
        )
        assertEquals(listOf(InflectedForm("食べた", listOf(InflectionTag.TA))), forms)
    }

    @Test
    fun `lemma seen only uninflected yields an empty list`() {
        val forms = rowInflectedForms(listOf(span("本", "本")), deinflection = emptyList())
        assertEquals(emptyList<InflectedForm>(), forms)
    }

    @Test
    fun `a tagless occurrence whose lookup deinflected gets the lookup's chain`() {
        // かけろ typed on its own: the token stays かけろ with no tags, and the
        // pack resolves it to かける through the imperative.
        val forms = rowInflectedForms(
            listOf(span("かけろ", "かけろ")),
            deinflection = listOf(InflectionTag.IMPERATIVE),
        )
        assertEquals(listOf(InflectedForm("かけろ", listOf(InflectionTag.IMPERATIVE))), forms)
    }

    @Test
    fun `the lookup's chain goes ahead of the occurrence's own tags`() {
        val forms = rowInflectedForms(
            listOf(span("食べていた", "食べている", InflectionTag.TA)),
            deinflection = listOf(InflectionTag.TE, InflectionTag.IRU),
        )
        assertEquals(
            listOf(
                InflectedForm(
                    "食べていた",
                    listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.TA),
                ),
            ),
            forms,
        )
    }

    @Test
    fun `capInflectionForms limits lines and reports the overflow count`() {
        val forms = (1..5).map { InflectedForm("形$it", listOf(InflectionTag.TA)) }
        val (shown, overflow) = capInflectionForms(forms, max = 3)
        assertEquals(listOf("形1", "形2", "形3"), shown.map { it.surface })
        assertEquals(2, overflow)
    }

    @Test
    fun `a row built without a key opens its detail under its display word`() {
        val row = RowState(
            displayWord = "猫", reading = "ねこ", meaning = "cat", senses = emptyList(),
            freqScore = 0, isCommon = false, surface = "猫",
        )
        assertEquals("猫" to null, row.lookupForm to row.lookupReading)
    }

    @Test
    fun `capInflectionForms reports no overflow at or under the cap`() {
        val forms = listOf(InflectedForm("食べた", listOf(InflectionTag.TA)))
        val (shown, overflow) = capInflectionForms(forms, max = 3)
        assertEquals(1, shown.size)
        assertEquals(0, overflow)
    }
}
