package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM tests for [GermanParadigm] over kaikki's German tag shapes as the de pack stores them. */
class GermanParadigmTest {

    private fun f(surface: String, vararg tags: String) = InflectionForm(surface, tags.toSet())

    @Test
    fun `verb forms read their tense, mood and person in German`() {
        assertEquals("ich · Präsens", GermanParadigm.label(listOf(f("spreche", "first-person", "indicative", "present", "singular"))))
        assertEquals("er, sie, es · Präteritum", GermanParadigm.label(listOf(f("sprach", "indicative", "preterite", "singular", "third-person"))))
        assertEquals("du · Konjunktiv I", GermanParadigm.label(listOf(f("sprechest", "second-person", "singular", "subjunctive", "subjunctive-i"))))
        // Konjunktiv II rows carry `formal rare`, which says nothing here.
        assertEquals("ich · Konjunktiv II", GermanParadigm.label(listOf(f("spräche", "first-person", "formal", "rare", "singular", "subjunctive", "subjunctive-ii"))))
        assertEquals("du · Imperativ", GermanParadigm.label(listOf(f("sprich", "imperative", "second-person", "singular"))))
        assertEquals("ich / er, sie, es · Präsens", GermanParadigm.label(listOf(
            f("spreche", "first-person", "indicative", "present", "singular"),
            f("spreche", "indicative", "present", "singular", "third-person"),
        )))
    }

    @Test
    fun `the infinite forms and the auxiliary row`() {
        assertEquals("Infinitiv", GermanParadigm.label(listOf(f("sprechen", "infinitive"))))
        assertEquals("zu-Infinitiv", GermanParadigm.label(listOf(f("zu sprechen", "infinitive", "infinitive-zu"))))
        assertEquals("Partizip I", GermanParadigm.label(listOf(f("sprechend", "participle", "present"))))
        assertEquals("Partizip II", GermanParadigm.label(listOf(f("gesprochen", "participle", "past"))))
        assertNull(GermanParadigm.label(listOf(f("haben", "auxiliary"))))
    }

    @Test
    fun `nouns read case and number, adjectives read dekliniert or their degree`() {
        assertEquals("Nominativ Singular", GermanParadigm.label(listOf(f("haus", "nominative", "singular"))))
        assertEquals("Dativ Plural", GermanParadigm.label(listOf(f("häusern", "dative", "definite", "plural"))))
        assertEquals("Nominativ Plural / Genitiv Plural / Akkusativ Plural", GermanParadigm.label(listOf(
            f("häuser", "definite", "nominative", "plural"), f("häuser", "definite", "genitive", "plural"), f("häuser", "accusative", "definite", "plural"),
        )))
        assertEquals("Diminutiv", GermanParadigm.label(listOf(f("häuschen", "diminutive", "form-of", "neuter"))))
        assertEquals("dekliniert", GermanParadigm.label(listOf(
            f("freien", "genitive", "masculine", "singular", "strong", "without-article"),
            f("den freien", "accusative", "definite", "includes-article", "masculine", "singular", "weak"),
            f("keine freien", "includes-article", "indefinite", "mixed", "negative", "nominative", "plural"),
        )))
        assertEquals("dekliniert im Komparativ", GermanParadigm.label(listOf(f("freieren", "comparative", "dative", "plural", "strong", "table-2", "without-article"))))
        // A page tags an adjective's form by declension without a case; a page merging du and ihr names both.
        assertEquals("dekliniert", GermanParadigm.label(listOf(f("abartigen", "form-of", "mixed", "plural", "weak"))))
        assertEquals("du / ihr · Präteritum", GermanParadigm.label(listOf(f("abbisst", "form-of", "plural", "preterite", "second-person", "singular"))))
        assertEquals("Komparativ", GermanParadigm.label(listOf(f("freier", "comparative", "masculine", "predicative", "singular", "table-2"))))
        // The positive predicative form is the lemma itself.
        assertNull(GermanParadigm.label(listOf(f("frei", "masculine", "predicative", "singular"))))
    }

    @Test
    fun `usage notes read in Duden's words, and the Konjunktiv II template's formal rare says nothing`() {
        assertEquals("dekliniert im Komparativ (selten)", GermanParadigm.label(listOf(
            f("übelere", "accusative", "comparative", "rare", "singular", "strong", "table-2", "without-article"),
        )))
        assertEquals("Genitiv Singular (umgangssprachlich)", GermanParadigm.label(listOf(f("bärs", "genitive", "informal", "singular"))))
        assertEquals("Nominativ Plural (gehoben)", GermanParadigm.label(listOf(f("zahnpasten", "definite", "formal", "nominative", "plural"))))
        assertEquals("Akkusativ Plural (selten, landschaftlich)", GermanParadigm.label(listOf(
            f("scheiter", "Austria", "accusative", "definite", "plural", "rare", "regional"),
        )))
        assertEquals("Partizip II (veraltet, mundartlich)", GermanParadigm.label(listOf(f("gescheint", "dialectal", "obsolete", "participle", "past"))))
        // Two tags with one word show it once; dated with nonstandard and proscribed is two words.
        assertEquals("Akkusativ Plural (veraltet)", GermanParadigm.label(listOf(f("härne", "accusative", "archaic", "definite", "obsolete", "plural"))))
        assertEquals("Akkusativ Plural (veraltend, nicht standardsprachlich)", GermanParadigm.label(listOf(
            f("lobbies", "accusative", "dated", "definite", "nonstandard", "plural", "proscribed"),
        )))
        // The Konjunktiv II's own rows: nothing; rare on a Konjunktiv II page with another note keeps the other.
        assertEquals("wir · Konjunktiv II", GermanParadigm.label(listOf(
            f("anerkennten", "Swiss-German", "first-person", "formal", "plural", "rare", "subjunctive", "subjunctive-ii"),
        )))
        assertEquals("ich · Konjunktiv II (veraltet)", GermanParadigm.label(listOf(
            f("stünde", "archaic", "first-person", "formal", "rare", "singular", "subjunctive", "subjunctive-ii"),
        )))
        // A note never names a form on its own.
        assertNull(GermanParadigm.label(listOf(f("frei", "masculine", "predicative", "rare", "singular"))))
    }

    @Test
    fun `the label fails closed on German`() {
        assertNull(GermanParadigm.label(listOf(f("spreche", "first-person", "indicative", "present", "singular", "Bavaria"))))
        // A page merging both numbers names the case alone.
        assertEquals("Nominativ", GermanParadigm.label(listOf(f("haus", "nominative", "plural", "singular"))))
        // A page merging cases names them; a case with no number is just the case; a pronoun's case form keeps its lemma's person.
        assertEquals("Nominativ Plural / Akkusativ Plural", GermanParadigm.label(listOf(f("bände", "accusative", "form-of", "nominative", "plural"))))
        // A noun page carries the lemma's gender and is still a noun; a page's Konjunktiv tag names its mood.
        assertEquals("Dativ Plural", GermanParadigm.label(listOf(f("aachenern", "dative", "form-of", "masculine", "plural"))))
        assertEquals("Diminutiv", GermanParadigm.label(listOf(f("abendkleidchen", "diminutive", "form-of", "neuter", "strong"))))
        assertEquals("ich / er, sie, es · Konjunktiv I", GermanParadigm.label(listOf(f("aale", "first-person", "form-of", "singular", "subjunctive-i", "third-person"))))
        assertEquals("ich · Präsens", GermanParadigm.label(listOf(f("abarbeite", "dependent", "first-person", "form-of", "present", "singular"))))
        assertEquals("Partizip II", GermanParadigm.label(listOf(f("geschleift", "form-of", "participle", "perfect"))))
        assertEquals("Genitiv", GermanParadigm.label(listOf(f("sms", "genitive"))))
        assertEquals("Dativ Singular", GermanParadigm.label(listOf(f("mir", "dative", "first-person", "personal", "pronoun", "singular"))))
        assertEquals("Femininum", GermanParadigm.label(listOf(f("pharaonin", "feminine"))))
        assertEquals("dekliniert im Superlativ", GermanParadigm.label(listOf(f("blondsten", "dative", "form-of", "genitive", "singular", "superlative"))))
        // A page's imperative without a person is du's or ihr's.
        assertEquals("du · Imperativ", GermanParadigm.label(listOf(f("seh", "form-of", "imperative", "singular"))))
        assertEquals("ihr · Imperativ", GermanParadigm.label(listOf(f("setzt über", "form-of", "imperative", "plural"))))
        assertNull(GermanParadigm.label(listOf(f("sprecht", "imperative", "plural", "third-person"))))
    }

    private fun sprechen(): List<InflectionForm> = listOf(
        f("haben", "auxiliary"),
        f("sprechen", "infinitive"),
        f("zu sprechen", "infinitive", "infinitive-zu"),
        f("sprechend", "participle", "present"),
        f("gesprochen", "participle", "past"),
        f("spreche", "first-person", "indicative", "present", "singular"),
        f("sprichst", "indicative", "present", "second-person", "singular"),
        f("spricht", "indicative", "present", "singular", "third-person"),
        f("sprechen", "first-person", "indicative", "plural", "present"),
        f("sprach", "first-person", "indicative", "preterite", "singular"),
        f("spreche", "first-person", "singular", "subjunctive", "subjunctive-i"),
        f("spräche", "first-person", "formal", "rare", "singular", "subjunctive", "subjunctive-ii"),
        f("sprich", "imperative", "second-person", "singular"),
        f("sprecht", "imperative", "plural", "second-person"),
    )

    private fun ConjugationTable.section(title: String) = sections.first { it.title == title }
    private fun ConjugationSection.cell(row: String, column: String) = rows.first { it.label == row }.cells[columns.indexOf(column)]

    @Test
    fun `the verb card has the German sections and a separable verb lights its cell from its first word`() {
        val table = GermanParadigm.table("sprechen", sprechen(), highlight = "sprichst")!!
        assertEquals(TableKind.CONJUGATION, table.kind)
        assertEquals(listOf("Indikativ", "Konjunktiv", "Imperativ", "Infinite Formen"), table.sections.map { it.title })
        assertEquals(listOf("Präsens", "Präteritum"), table.section("Indikativ").columns)
        assertEquals(listOf("sprichst"), table.section("Indikativ").cell("du", "Präsens").forms)
        assertTrue(table.section("Indikativ").cell("du", "Präsens").highlighted)
        assertEquals(listOf("spräche"), table.section("Konjunktiv").cell("ich", "Konjunktiv II").forms)
        assertEquals(listOf("du", "ihr"), table.section("Imperativ").rows.map { it.label })
        assertEquals(listOf("Infinitiv", "zu-Infinitiv", "Partizip I", "Partizip II"), table.section("Infinite Formen").rows.map { it.label })

        val aufstehen = listOf(
            f("aufstehen", "infinitive"),
            f("stehe auf", "first-person", "indicative", "present", "singular"),
            f("aufstehe", "first-person", "indicative", "present", "singular", "subordinate-clause"),
            f("stehst auf", "indicative", "present", "second-person", "singular"),
            f("steht auf", "indicative", "present", "singular", "third-person"),
            f("stehen auf", "first-person", "indicative", "plural", "present"),
            f("stand auf", "first-person", "indicative", "preterite", "singular"),
            f("steh auf", "imperative", "second-person", "singular"),
        )
        val sep = GermanParadigm.table("aufstehen", aufstehen, highlight = "stehe")!!
        assertEquals(listOf("stehe auf", "aufstehe"), sep.section("Indikativ").cell("ich", "Präsens").forms)
        assertTrue(sep.section("Indikativ").cell("ich", "Präsens").highlighted)
    }

    @Test
    fun `a noun gets a declension card, an adjective none`() {
        val haus = listOf(
            f("haus", "nominative", "singular"), f("häuser", "definite", "nominative", "plural"),
            f("hauses", "genitive", "singular"), f("häuser", "definite", "genitive", "plural"),
            f("haus", "dative", "singular"), f("hause", "dative", "singular"), f("häusern", "dative", "definite", "plural"),
            f("haus", "accusative", "singular"), f("häuser", "accusative", "definite", "plural"),
            f("häuschen", "diminutive", "form-of", "neuter"),
        )
        val table = GermanParadigm.table("haus", haus, highlight = "häusern")!!
        assertEquals(TableKind.DECLENSION, table.kind)
        val d = table.section("Deklination")
        assertEquals(listOf("Singular", "Plural"), d.columns)
        assertEquals(listOf("Nominativ", "Genitiv", "Dativ", "Akkusativ"), d.rows.map { it.label })
        assertEquals(listOf("haus", "hause"), d.cell("Dativ", "Singular").forms)
        assertEquals(listOf("häusern"), d.cell("Dativ", "Plural").forms)
        assertTrue(d.cell("Dativ", "Plural").highlighted)
        assertNull(GermanParadigm.table("frei", listOf(
            f("frei", "masculine", "predicative", "singular"),
            f("freier", "masculine", "nominative", "singular", "strong", "without-article"),
            f("freien", "genitive", "masculine", "singular", "strong", "without-article"),
        ), highlight = null))
    }
}
