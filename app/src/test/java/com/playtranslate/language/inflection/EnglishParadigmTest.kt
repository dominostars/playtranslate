package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests for [EnglishParadigm] over kaikki's English tag shapes as the en pack stores them. */
class EnglishParadigmTest {

    private fun f(surface: String, vararg tags: String) = InflectionForm(surface, tags.toSet())

    @Test
    fun `the regular verb forms, nouns and adjectives read their names`() {
        assertEquals("third-person singular present", EnglishParadigm.label(listOf(f("speaks", "present", "singular", "third-person"))))
        assertEquals("past", EnglishParadigm.label(listOf(f("spoke", "past"))))
        assertEquals("past participle", EnglishParadigm.label(listOf(f("spoken", "participle", "past"))))
        assertEquals("present participle", EnglishParadigm.label(listOf(f("speaking", "participle", "present"))))
        assertEquals("present participle", EnglishParadigm.label(listOf(f("phoenixing", "form-of", "gerund"))))
        assertEquals("past / past participle", EnglishParadigm.label(listOf(f("walked", "past"), f("walked", "participle", "past"))))
        assertEquals("plural", EnglishParadigm.label(listOf(f("children", "plural"))))
        assertEquals("comparative", EnglishParadigm.label(listOf(f("bigger", "comparative"))))
        assertEquals("superlative", EnglishParadigm.label(listOf(f("biggest", "superlative"))))
        assertEquals("diminutive", EnglishParadigm.label(listOf(f("wordie", "diminutive", "form-of"))))
    }

    @Test
    fun `the fully conjugated verbs read person and number before the tense, and spellings read the same`() {
        assertEquals("first-person singular present", EnglishParadigm.label(listOf(f("am", "first-person", "present", "singular"))))
        assertEquals("plural past / past subjunctive", EnglishParadigm.label(listOf(f("were", "past", "plural"), f("were", "past", "subjunctive"))))
        assertEquals("second-person singular present (archaic)", EnglishParadigm.label(listOf(f("speakest", "archaic", "present", "second-person", "singular"))))
        assertEquals("imperative", EnglishParadigm.label(listOf(f("be", "imperative", "present"))))
        assertEquals("infinitive", EnglishParadigm.label(listOf(f("be", "infinitive"))))
        assertEquals("past participle", EnglishParadigm.label(listOf(f("pencilled", "UK", "participle", "past"))))
        assertEquals("objective", EnglishParadigm.label(listOf(f("him", "oblique"))))
        assertEquals("objective plural", EnglishParadigm.label(listOf(f("them", "objective", "plural"))))
        assertEquals("reflexive", EnglishParadigm.label(listOf(f("yourself", "reflexive", "singular"))))
        // A page merging persons names each.
        assertEquals("first-person singular past / third-person singular past", EnglishParadigm.label(listOf(
            f("was", "first-person", "form-of", "indicative", "past", "singular", "third-person"),
        )))
    }

    @Test
    fun `usage tags follow the name in parentheses, and alone name no form`() {
        assertEquals("past participle (archaic, dialectal)", EnglishParadigm.label(listOf(f("sitten", "archaic", "dialectal", "participle", "past"))))
        assertEquals("first-person singular past (dated, poetic)", EnglishParadigm.label(listOf(f("sate", "dated", "first-person", "past", "poetic", "singular"))))
        assertEquals("plural (formal)", EnglishParadigm.label(listOf(f("citri", "formal", "plural"))))
        assertEquals("comparative (informal)", EnglishParadigm.label(listOf(f("funner", "comparative", "informal"))))
        assertEquals("past participle (informal)", EnglishParadigm.label(listOf(f("snuck up", "US", "informal", "participle", "past"))))
        // A page merging persons carries the note on each name.
        assertEquals("first-person singular past (archaic) / third-person singular past (archaic)", EnglishParadigm.label(listOf(
            f("wast", "archaic", "first-person", "form-of", "past", "singular", "third-person"),
        )))
        // Two readings of one surface keep their own notes.
        assertEquals("past / past participle (colloquial, nonstandard)", EnglishParadigm.label(listOf(
            f("rode", "past"), f("rode", "colloquial", "nonstandard", "participle", "past"),
        )))
        assertNull(EnglishParadigm.label(listOf(f("'bortion", "form-of", "informal"))))
    }

    @Test
    fun `the label fails closed on English`() {
        // The singular of a plural-only lemma is a form (current events: current event).
        assertEquals("singular", EnglishParadigm.label(listOf(f("current event", "singular"))))
        assertNull(EnglishParadigm.label(listOf(f("there had been", "form-of", "past", "perfect"))))
        assertNull(EnglishParadigm.label(listOf(f("speaks", "past", "present", "singular", "third-person"))))
        assertNull(EnglishParadigm.label(listOf(f("speaks", "present", "singular", "third-person", "Ireland"))))
    }

    // ── the Forms card ──────────────────────────────────────────────────

    private fun rows(table: ConjugationTable) = table.sections.single().rows.map { it.label to it.cells.single().forms }

    @Test
    fun `the four headline forms are the card, in paradigm order`() {
        val table = EnglishParadigm.table("look", listOf(
            f("looks", "present", "singular", "third-person"), f("looking", "participle", "present"),
            f("looked", "past"), f("looked", "participle", "past"),
        ), highlight = "Looked")!!
        assertEquals(TableKind.FORMS, table.kind)
        assertTrue(table.sections.single().isList)
        assertEquals(
            listOf("third-person singular present" to listOf("looks"), "past" to listOf("looked"),
                "past participle" to listOf("looked"), "present participle" to listOf("looking")),
            rows(table),
        )
        // The arrival form lights every row it sits in.
        assertEquals(listOf(false, true, true, false), table.sections.single().rows.map { it.cells.single().highlighted })
    }

    @Test
    fun `a full table folds its persons into present and past and lists a subjunctive or imperative only when it adds a form`() {
        val walk = listOf(
            f("walk", "infinitive"), f("walk", "first-person", "present", "singular"), f("walked", "first-person", "past", "singular"),
            f("walk", "present", "second-person", "singular"), f("walkest", "archaic", "present", "second-person", "singular"),
            f("walked", "past", "second-person", "singular"), f("walkedst", "archaic", "past", "second-person", "singular"),
            f("walks", "present", "singular", "third-person"), f("walketh", "archaic", "present", "singular", "third-person"),
            f("walked", "past", "singular", "third-person"), f("walk", "plural", "present"), f("walked", "past", "plural"),
            f("walk", "present", "subjunctive"), f("walked", "past", "subjunctive"), f("walk", "imperative", "present"),
            f("walking", "participle", "present"), f("walked", "participle", "past"),
        )
        assertEquals(
            listOf(
                "infinitive" to listOf("walk"), "present" to listOf("walk", "walkest"),
                "third-person singular present" to listOf("walks", "walketh"), "past" to listOf("walked", "walkedst"),
                "past participle" to listOf("walked"), "present participle" to listOf("walking"),
            ),
            rows(EnglishParadigm.table("walk", walk, highlight = null)!!),
        )
        // be: the subjunctive be is the infinitive and were is a past, so neither is a row; the imperative be likewise.
        val be = listOf(
            f("be", "infinitive"), f("am", "first-person", "present", "singular"), f("was", "first-person", "past", "singular"),
            f("are", "present", "second-person", "singular"), f("art", "archaic", "present", "second-person", "singular"),
            f("were", "past", "second-person", "singular"), f("wast", "archaic", "past", "second-person", "singular"),
            f("is", "present", "singular", "third-person"), f("was", "past", "singular", "third-person"),
            f("are", "plural", "present"), f("were", "past", "plural"),
            f("be", "present", "subjunctive"), f("were", "past", "subjunctive"), f("be", "imperative", "present"),
            f("being", "participle", "present"), f("been", "participle", "past"),
        )
        assertEquals(
            listOf(
                "infinitive" to listOf("be"), "present" to listOf("am", "are", "art"), "third-person singular present" to listOf("is"),
                "past" to listOf("was", "were", "wast"), "past participle" to listOf("been"), "present participle" to listOf("being"),
            ),
            rows(EnglishParadigm.table("be", be, highlight = null)!!),
        )
        // A subjunctive with a surface of its own stays (a made-up paradigm, to pin the rule).
        val odd = listOf(f("go", "infinitive"), f("went", "past"), f("gang", "present", "subjunctive"))
        assertEquals(listOf("infinitive", "present subjunctive", "past"), rows(EnglishParadigm.table("go", odd, highlight = null)!!).map { it.first })
    }

    @Test
    fun `spellings share a row, adjectives get two rows, and one row is no card`() {
        val travel = listOf(
            f("travels", "present", "singular", "third-person"), f("travelled", "past"), f("traveled", "US", "past"),
            f("travelled", "participle", "past"), f("traveled", "US", "participle", "past"),
            f("travelling", "participle", "present"), f("traveling", "US", "participle", "present"),
        )
        assertEquals(
            listOf("third-person singular present" to listOf("travels"), "past" to listOf("travelled", "traveled"),
                "past participle" to listOf("travelled", "traveled"), "present participle" to listOf("travelling", "traveling")),
            rows(EnglishParadigm.table("travel", travel, highlight = null)!!),
        )
        assertEquals(
            listOf("comparative" to listOf("bigger"), "superlative" to listOf("biggest")),
            rows(EnglishParadigm.table("big", listOf(f("bigger", "comparative"), f("biggest", "superlative")), highlight = null)!!),
        )
        assertNull(EnglishParadigm.table("child", listOf(f("children", "plural"), f("childer", "form-of", "plural")), highlight = null))
        assertNull(EnglishParadigm.table("he", listOf(f("him", "oblique")), highlight = null))
        // A line the reader does not read is no row.
        assertNull(EnglishParadigm.table("speak", listOf(f("speaks", "present", "singular", "third-person"), f("speak", "Ireland")), highlight = null))
    }
}
