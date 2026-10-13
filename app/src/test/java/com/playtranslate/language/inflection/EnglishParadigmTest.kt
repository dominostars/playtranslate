package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("second-person singular present", EnglishParadigm.label(listOf(f("speakest", "archaic", "present", "second-person", "singular"))))
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
    fun `the label fails closed on English`() {
        // The singular of a plural-only lemma is a form (current events: current event).
        assertEquals("singular", EnglishParadigm.label(listOf(f("current event", "singular"))))
        assertNull(EnglishParadigm.label(listOf(f("there had been", "form-of", "past", "perfect"))))
        assertNull(EnglishParadigm.label(listOf(f("speaks", "past", "present", "singular", "third-person"))))
        assertNull(EnglishParadigm.label(listOf(f("speaks", "present", "singular", "third-person", "Ireland"))))
        assertNull(EnglishParadigm.table("speak", listOf(f("speaks", "present", "singular", "third-person")), highlight = null))
    }
}
