package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests for [PortugueseParadigm] over kaikki's Portuguese tag shapes as the pt pack stores them. */
class PortugueseParadigmTest {

    private fun f(surface: String, vararg tags: String) = InflectionForm(surface, tags.toSet())

    @Test
    fun `tenses are named as Portuguese grammars name them`() {
        assertEquals("nós · presente", PortugueseParadigm.label(listOf(f("falamos", "first-person", "indicative", "plural", "present"))))
        assertEquals("nós · pretérito perfeito", PortugueseParadigm.label(listOf(f("falamos", "Brazil", "first-person", "indicative", "plural", "preterite"))))
        assertEquals("eu · pretérito mais-que-perfeito", PortugueseParadigm.label(listOf(f("falara", "first-person", "indicative", "pluperfect", "singular"))))
        // The conditional carries no mood.
        assertEquals("eu · condicional", PortugueseParadigm.label(listOf(f("falaria", "conditional", "first-person", "singular"))))
        assertEquals("eles, elas, vocês · futuro do subjuntivo", PortugueseParadigm.label(listOf(f("falarem", "future", "plural", "subjunctive", "third-person"))))
    }

    @Test
    fun `the imperative addresses você in the third person and the negative keeps its não`() {
        assertEquals("você · imperativo", PortugueseParadigm.label(listOf(f("fale", "imperative", "singular", "third-person"))))
        assertEquals("tu · imperativo negativo", PortugueseParadigm.label(listOf(f("não fales", "imperative", "negative", "second-person", "singular"))))
        assertEquals("vocês · imperativo negativo", PortugueseParadigm.label(listOf(f("não falem", "imperative", "negative", "plural", "third-person"))))
    }

    @Test
    fun `the personal infinitive is a paradigm of its own and shares its surfaces with the future subjunctive`() {
        assertEquals("tu · infinitivo pessoal", PortugueseParadigm.label(listOf(f("falares", "infinitive", "second-person", "singular"))))
        assertEquals("infinitivo", PortugueseParadigm.label(listOf(f("falar", "impersonal", "infinitive"))))
        assertEquals("tu · futuro do subjuntivo / infinitivo pessoal", PortugueseParadigm.label(listOf(
            f("falares", "future", "second-person", "singular", "subjunctive"),
            f("falares", "infinitive", "second-person", "singular"),
        )))
    }

    @Test
    fun `participles with a short form are the participle, and agreement reads in Portuguese`() {
        assertEquals("particípio", PortugueseParadigm.label(listOf(f("aceito", "masculine", "participle", "past", "short-form", "singular"))))
        assertEquals("particípio feminino plural", PortugueseParadigm.label(listOf(f("aceitadas", "feminine", "long-form", "participle", "past", "plural"))))
        assertEquals("feminino plural", PortugueseParadigm.label(listOf(f("casas", "feminine", "plural"))))
        assertEquals("diminutivo masculino", PortugueseParadigm.label(listOf(f("amiguinho", "diminutive", "form-of", "masculine"))))
        assertNull(PortugueseParadigm.label(listOf(f("naum", "form-of", "informal"))))
        // Usage notes follow the name; informal on a noun's form is its register, not an address.
        assertEquals("feminino (informal)", PortugueseParadigm.label(listOf(f("chefa", "feminine", "informal"))))
        assertEquals("feminino plural (pouco comum)", PortugueseParadigm.label(listOf(f("morcegas", "feminine", "plural", "uncommon"))))
        assertEquals("nós · imperativo negativo (arcaico, proscrito)", PortugueseParadigm.label(listOf(
            f("não pairamos", "archaic", "first-person", "imperative", "negative", "plural", "proscribed", "sometimes"),
        )))
        assertEquals("aumentativo masculino (informal)", PortugueseParadigm.label(listOf(f("viajão", "augmentative", "form-of", "informal", "masculine"))))
        assertNull(PortugueseParadigm.label(listOf(f("falo", "first-person", "indicative", "present", "singular", "Angola"))))
    }

    private fun falar(): List<InflectionForm> = listOf(
        f("falar", "impersonal", "infinitive"),
        f("falar", "first-person", "infinitive", "singular"),
        f("falares", "infinitive", "second-person", "singular"),
        f("falarmos", "first-person", "infinitive", "plural"),
        f("falando", "gerund"),
        f("falado", "masculine", "participle", "past", "singular"),
        f("falada", "feminine", "participle", "past", "singular"),
        f("falo", "first-person", "indicative", "present", "singular"),
        f("falas", "indicative", "present", "second-person", "singular"),
        f("fala", "indicative", "present", "singular", "third-person"),
        f("falamos", "first-person", "indicative", "plural", "present"),
        f("falamos", "Brazil", "first-person", "indicative", "plural", "preterite"),
        f("falámos", "first-person", "indicative", "plural", "preterite"),
        f("falara", "first-person", "indicative", "pluperfect", "singular"),
        f("falaria", "conditional", "first-person", "singular"),
        f("fale", "first-person", "present", "singular", "subjunctive"),
        f("falasse", "first-person", "imperfect", "singular", "subjunctive"),
        f("falar", "first-person", "future", "singular", "subjunctive"),
        f("fala", "imperative", "second-person", "singular"),
        f("fale", "imperative", "singular", "third-person"),
        f("não fales", "imperative", "negative", "second-person", "singular"),
    )

    private fun ConjugationTable.section(title: String) = sections.first { it.title == title }
    private fun ConjugationSection.cell(row: String, column: String) = rows.first { it.label == row }.cells[columns.indexOf(column)]

    @Test
    fun `the grid has the Portuguese sections, six indicative tenses, and the personal infinitive as its own`() {
        val table = PortugueseParadigm.table("falar", falar(), highlight = "falares")!!
        assertEquals(listOf("Indicativo", "Subjuntivo", "Imperativo", "Infinitivo pessoal", "Formas nominais"), table.sections.map { it.title })
        val ind = table.section("Indicativo")
        assertEquals(listOf("presente", "pretérito perfeito", "imperfeito", "mais-que-perfeito", "futuro", "condicional"), ind.columns)
        // Spelling variants sit side by side; the Brazil tag changes nothing.
        assertEquals(listOf("falamos", "falámos"), ind.cell("nós", "pretérito perfeito").forms)
        assertEquals(listOf("falaria"), ind.cell("eu", "condicional").forms)
        assertEquals(listOf("falara"), ind.cell("eu", "mais-que-perfeito").forms)
        assertEquals(listOf("falar"), table.section("Subjuntivo").cell("eu", "futuro").forms)
        val imp = table.section("Imperativo")
        assertEquals(listOf("tu", "você", "nós", "vós", "vocês"), imp.rows.map { it.label })
        assertEquals(listOf("fale"), imp.cell("você", "afirmativo").forms)
        // The pack's surface carries the não; nothing is written before it.
        assertEquals(listOf("não fales"), imp.cell("tu", "negativo").forms)
        val pi = table.section("Infinitivo pessoal")
        assertEquals(listOf("falares"), pi.cell("tu", "infinitivo pessoal").forms)
        assertTrue(pi.cell("tu", "infinitivo pessoal").highlighted)
        assertEquals(3, table.openSection)
        assertEquals(listOf("infinitivo", "gerúndio", "particípio"), table.section("Formas nominais").rows.map { it.label })
    }
}
