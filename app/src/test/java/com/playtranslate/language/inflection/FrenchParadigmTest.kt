package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests for [FrenchParadigm] over kaikki's French tag shapes as the fr pack stores them. */
class FrenchParadigmTest {

    private fun f(surface: String, vararg tags: String) = InflectionForm(surface, tags.toSet())

    @Test
    fun `tenses are named as French grammars name them`() {
        assertEquals("nous · présent", FrenchParadigm.label(listOf(f("parlons", "first-person", "indicative", "plural", "present"))))
        // The passé simple is historic + past; the conditional carries no mood.
        assertEquals("il, elle, on · passé simple", FrenchParadigm.label(listOf(f("parla", "historic", "indicative", "past", "singular", "third-person"))))
        assertEquals("tu · conditionnel", FrenchParadigm.label(listOf(f("parlerais", "conditional", "second-person", "singular"))))
        assertEquals("ils, elles · subjonctif imparfait", FrenchParadigm.label(listOf(f("parlassent", "imperfect", "plural", "subjunctive", "third-person"))))
        assertEquals("vous · impératif", FrenchParadigm.label(listOf(f("parlez", "imperative", "plural", "second-person"))))
    }

    @Test
    fun `the three participle shapes and the infinitive`() {
        assertEquals("participe présent", FrenchParadigm.label(listOf(f("parlant", "gerund", "participle", "present"))))
        assertEquals("participe présent", FrenchParadigm.label(listOf(f("absentant", "form-of", "participle", "present"))))
        assertEquals("participe passé", FrenchParadigm.label(listOf(f("parlé", "participle", "past"))))
        assertEquals("participe passé féminin pluriel", FrenchParadigm.label(listOf(f("aimées", "feminine", "form-of", "participle", "plural"))))
        assertEquals("infinitif", FrenchParadigm.label(listOf(f("parler", "infinitive"))))
    }

    @Test
    fun `a page merging the indicative and the subjunctive names both`() {
        assertEquals("ils, elles · présent / subjonctif présent", FrenchParadigm.label(listOf(
            f("absentent", "form-of", "indicative", "plural", "present", "subjunctive", "third-person"),
        )))
        assertEquals("je / il, elle, on · présent / subjonctif présent", FrenchParadigm.label(listOf(
            f("absente", "first-person", "form-of", "indicative", "present", "singular", "subjunctive", "third-person"),
        )))
    }

    @Test
    fun `the label fails closed on French`() {
        // No first-person singular imperative exists; a page claiming one is not read.
        assertNull(FrenchParadigm.label(listOf(f("ferme-la", "first-person", "form-of", "imperative", "singular"))))
        // A tense without its pair: past alone is not the passé simple.
        assertNull(FrenchParadigm.label(listOf(f("parla", "indicative", "past", "singular", "third-person"))))
        assertNull(FrenchParadigm.label(listOf(f("parle", "first-person", "indicative", "present", "singular", "Quebec"))))
        // A register alone, a pronoun page.
        assertNull(FrenchParadigm.label(listOf(f("nan", "form-of", "informal"))))
        // Usage notes follow the name.
        assertEquals("féminin (péjoratif)", FrenchParadigm.label(listOf(f("beurette", "derogatory", "feminine"))))
        assertEquals("pluriel (rare)", FrenchParadigm.label(listOf(f("oufs", "plural", "uncommon"))))
        assertNull(FrenchParadigm.label(listOf(f("je", "singular"))))
    }

    private fun absenter(): List<InflectionForm> = listOf(
        f("s'absenter", "infinitive"),
        f("s'absentant", "gerund", "participle", "present"),
        f("absenté", "participle", "past"),
        f("m'absente", "first-person", "indicative", "present", "singular"),
        f("t'absentes", "indicative", "present", "second-person", "singular"),
        f("s'absente", "indicative", "present", "singular", "third-person"),
        f("nous absentons", "first-person", "indicative", "plural", "present"),
        f("vous absentez", "indicative", "plural", "present", "second-person"),
        f("s'absentent", "indicative", "plural", "present", "third-person"),
        f("nous absentions", "first-person", "imperfect", "indicative", "plural"),
        f("s'absenta", "historic", "indicative", "past", "singular", "third-person"),
        f("m'absenterais", "conditional", "first-person", "singular"),
        f("m'absente", "first-person", "present", "singular", "subjunctive"),
        f("absente-toi", "imperative", "second-person", "singular"),
        f("absentons-nous", "first-person", "imperative", "plural"),
        // A page merging two moods, appended by the builder for a surface the table lacks.
        f("absentassent", "form-of", "imperfect", "plural", "subjunctive", "third-person"),
    )

    private fun ConjugationTable.section(title: String) = sections.first { it.title == title }
    private fun ConjugationSection.cell(row: String, column: String) = rows.first { it.label == row }.cells[columns.indexOf(column)]

    @Test
    fun `the grid has the French sections and a reflexive verb's two-word rows are its own cells`() {
        val table = FrenchParadigm.table("absenter", absenter(), highlight = "absente")!!
        assertEquals(listOf("Indicatif", "Subjonctif", "Impératif", "Formes non conjuguées"), table.sections.map { it.title })
        val ind = table.section("Indicatif")
        assertEquals(listOf("présent", "imparfait", "passé simple", "futur simple", "conditionnel"), ind.columns)
        assertEquals(listOf("m'absente"), ind.cell("je", "présent").forms)
        assertEquals(listOf("nous absentions"), ind.cell("nous", "imparfait").forms)
        assertEquals(listOf("s'absenta"), ind.cell("il, elle, on", "passé simple").forms)
        assertEquals(listOf("m'absenterais"), ind.cell("je", "conditionnel").forms)
        assertTrue("the arrival form matches the verb after the elided pronoun", ind.cell("je", "présent").highlighted)
        assertEquals(listOf("tu", "nous", "vous"), table.section("Impératif").rows.map { it.label })
        assertEquals(listOf("absente-toi"), table.section("Impératif").cell("tu", "présent").forms)
        assertEquals(listOf("infinitif", "participe présent", "participe passé"), table.section("Formes non conjuguées").rows.map { it.label })
    }

    @Test
    fun `a merged-mood form sits in both moods' cells`() {
        val forms = absenter() + f("absentent", "form-of", "indicative", "plural", "present", "subjunctive", "third-person")
        val table = FrenchParadigm.table("absenter", forms, highlight = null)!!
        assertEquals(listOf("s'absentent", "absentent"), table.section("Indicatif").cell("ils, elles", "présent").forms)
        assertEquals(listOf("absentent"), table.section("Subjonctif").cell("ils, elles", "présent").forms)
    }
}
