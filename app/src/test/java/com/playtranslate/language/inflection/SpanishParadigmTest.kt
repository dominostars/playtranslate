package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for [SpanishParadigm] over kaikki's Spanish tag shapes as the
 * es pack stores them (see the object's KDoc): the one-line label and the
 * conjugation table.
 */
class SpanishParadigmTest {

    private fun f(surface: String, vararg tags: String) = InflectionForm(surface, tags.toSet())

    // ── label ───────────────────────────────────────────────────────────

    @Test
    fun `one pronoun with two tenses joins the tenses`() {
        val label = SpanishParadigm.label(listOf(
            f("hablamos", "first-person", "indicative", "plural", "present"),
            f("hablamos", "first-person", "indicative", "plural", "preterite"),
        ))
        assertEquals("nosotros · presente / pretérito", label)
    }

    @Test
    fun `third person singular reads as the three pronouns`() {
        val label = SpanishParadigm.label(listOf(f("fue", "indicative", "preterite", "singular", "third-person")))
        assertEquals("él, ella, usted · pretérito", label)
    }

    @Test
    fun `one tense with two pronouns joins the pronouns`() {
        val label = SpanishParadigm.label(listOf(
            f("hablaba", "first-person", "imperfect", "indicative", "singular"),
            f("hablaba", "imperfect", "indicative", "singular", "third-person"),
        ))
        assertEquals("yo / él, ella, usted · imperfecto", label)
    }

    @Test
    fun `different pronoun and tense per reading lists each pair as its own group`() {
        val label = SpanishParadigm.label(listOf(
            f("habla", "indicative", "present", "singular", "third-person"),
            f("habla", "imperative", "informal", "second-person", "singular"),
        ))
        assertEquals("él, ella, usted · presente; tú · imperativo", label)
    }

    @Test
    fun `pronouns with the same tenses merge into one group`() {
        // hable: yo and él share the subjunctive; usted has the two imperatives.
        assertEquals(
            "yo / él, ella, usted · presente de subjuntivo; usted · imperativo / imperativo negativo",
            SpanishParadigm.label(listOf(
                f("hable", "first-person", "present", "singular", "subjunctive"),
                f("hable", "present", "singular", "subjunctive", "third-person"),
                f("hable", "formal", "imperative", "second-person-semantically", "singular", "third-person"),
                f("hable", "imperative", "singular", "third-person"),
                f("hable", "formal", "imperative", "negative", "second-person-semantically", "singular", "third-person"),
                f("hable", "imperative", "negative", "singular", "third-person"),
            )),
        )
        // The table's order does not leak into the label: third person listed
        // before first still reads yo first.
        assertEquals("yo / él, ella, usted · imperfecto", SpanishParadigm.label(listOf(
            f("hablaba", "imperfect", "indicative", "singular", "third-person"),
            f("hablaba", "first-person", "imperfect", "indicative", "singular"),
        )))
        // hablen: ellos keep the subjunctive, ustedes the two imperatives.
        assertEquals("ellos, ellas, ustedes · presente de subjuntivo; ustedes · imperativo / imperativo negativo", SpanishParadigm.label(listOf(
            f("hablen", "plural", "present", "subjunctive", "third-person"),
            f("hablen", "formal", "imperative", "plural", "second-person-semantically", "third-person"),
            f("hablen", "formal", "imperative", "negative", "plural", "second-person-semantically", "third-person"),
        )))
    }

    @Test
    fun `vos, usted, vosotros and the negative imperative name their rows`() {
        assertEquals("vos · presente", SpanishParadigm.label(listOf(
            f("hablás", "indicative", "informal", "present", "second-person", "singular", "vos-form"),
        )))
        assertEquals("usted · imperativo", SpanishParadigm.label(listOf(
            f("hable", "formal", "imperative", "second-person-semantically", "singular", "third-person"),
        )))
        assertEquals("vosotros · imperativo", SpanishParadigm.label(listOf(
            f("hablad", "imperative", "plural", "second-person"),
        )))
        assertEquals("tú · imperativo negativo", SpanishParadigm.label(listOf(
            f("hables", "imperative", "negative", "second-person", "singular"),
        )))
        assertEquals("nosotros · imperfecto de subjuntivo", SpanishParadigm.label(listOf(
            f("hablásemos", "first-person", "imperfect", "imperfect-se", "plural", "subjunctive"),
        )))
        assertEquals("ellos, ellas, ustedes · condicional", SpanishParadigm.label(listOf(
            f("hablarían", "conditional", "indicative", "plural", "third-person"),
        )))
    }

    @Test
    fun `non-finite forms are named alone and the participle carries its agreement`() {
        assertEquals("gerundio", SpanishParadigm.label(listOf(f("hablando", "gerund"))))
        assertEquals("infinitivo", SpanishParadigm.label(listOf(f("hablar", "infinitive"))))
        assertEquals("participio", SpanishParadigm.label(listOf(
            f("hablado", "masculine", "participle", "past", "singular"),
        )))
        assertEquals("participio femenino plural", SpanishParadigm.label(listOf(
            f("habladas", "feminine", "participle", "past", "plural"),
        )))
    }

    @Test
    fun `nouns and adjectives read their number and gender`() {
        assertEquals("plural", SpanishParadigm.label(listOf(f("casas", "plural"))))
        assertEquals("femenino", SpanishParadigm.label(listOf(f("blanca", "feminine", "singular"))))
        assertEquals("femenino plural", SpanishParadigm.label(listOf(f("blancas", "feminine", "plural"))))
        assertEquals("masculino plural", SpanishParadigm.label(listOf(f("blancos", "masculine", "plural"))))
        // One plural for both genders (grandes): no gender word.
        assertEquals("plural", SpanishParadigm.label(listOf(f("grandes", "feminine", "masculine", "plural"))))
        assertEquals("superlativo", SpanishParadigm.label(listOf(f("blanquísimo", "superlative"))))
        assertEquals("neutro", SpanishParadigm.label(listOf(f("alte", "form-of", "neuter", "singular"))))
        // A register on a noun's or adjective's form is a usage note (simplicísimo: the learned superlative).
        assertEquals("superlativo (formal)", SpanishParadigm.label(listOf(f("simplicísimo", "form-of", "formal", "superlative"))))
        assertEquals("aumentativo femenino (informal)", SpanishParadigm.label(listOf(f("tetaza", "augmentative", "feminine", "form-of", "informal"))))
        assertEquals("plural (poco común)", SpanishParadigm.label(listOf(f("bocas flojas", "feminine", "masculine", "plural", "uncommon"))))
        // A bare masculine reading is a feminine lemma's counterpart (matrón under matrona).
        assertEquals("masculino", SpanishParadigm.label(listOf(f("matrón", "masculine"))))
        // A participle's masculine singular is the participle itself.
        assertEquals("participio", SpanishParadigm.label(listOf(f("hablado", "masculine", "participle", "past", "singular"))))
        assertNull(SpanishParadigm.label(emptyList()))
    }

    @Test
    fun `clitic-attached forms read as the base form plus the pronoun`() {
        assertEquals("tú · imperativo + me", SpanishParadigm.label(listOf(
            f("háblame", "combined-form", "dative", "imperative", "informal", "object-first-person",
              "object-singular", "second-person", "singular", "with-tú"),
        )))
        // háblalo, háblala and háblese share one tagset: the surface says which.
        assertEquals("gerundio + lo", SpanishParadigm.label(listOf(
            f("hablándolo", "accusative", "combined-form", "gerund", "object-singular", "object-third-person"),
        )))
        assertEquals("gerundio + la", SpanishParadigm.label(listOf(
            f("hablándola", "accusative", "combined-form", "gerund", "object-singular", "object-third-person"),
        )))
        assertEquals("gerundio + se", SpanishParadigm.label(listOf(
            f("hablándose", "accusative", "combined-form", "gerund", "object-singular", "object-third-person"),
        )))
        assertEquals("infinitivo + se", SpanishParadigm.label(listOf(
            f("hablarse", "combined-form", "dative", "infinitive", "object-singular", "object-third-person"),
        )))
        assertEquals("infinitivo + les", SpanishParadigm.label(listOf(
            f("hablarles", "combined-form", "dative", "infinitive", "object-plural", "object-third-person"),
        )))
        assertEquals("nosotros · imperativo + nos", SpanishParadigm.label(listOf(
            f("hablémonos", "combined-form", "dative", "first-person", "imperative", "object-first-person",
              "object-plural", "plural"),
        )))
        // A form-of page tags the clitic only by its object tags.
        assertEquals("tú · imperativo + me", SpanishParadigm.label(listOf(
            f("dame", "form-of", "imperative", "object-first-person", "object-singular", "second-person", "singular"),
        )))
        assertEquals("ustedes · imperativo + los", SpanishParadigm.label(listOf(
            f("ándenlos", "accusative", "form-of", "imperative", "object-masculine", "object-plural",
              "object-third-person", "plural", "third-person"),
        )))
        // Two pronouns: the indirect one first, then the direct third-person one.
        assertEquals("tú · imperativo + me lo", SpanishParadigm.label(listOf(
            f("dámelo", "accusative", "combined-form", "dative", "imperative", "informal", "object-first-person",
              "object-singular", "object-third-person", "second-person", "singular", "with-tú"),
        )))
    }

    @Test
    fun `a usage note follows the name, after an attached pronoun`() {
        assertEquals("usted · imperativo + nos (arcaico)", SpanishParadigm.label(listOf(
            f("pléganos", "accusative", "archaic", "combined-form", "formal", "imperative", "object-first-person", "object-plural", "second-person", "singular"),
        )))
        assertEquals("ustedes · imperativo + nos (obsoleto)", SpanishParadigm.label(listOf(
            f("afiérrennos", "accusative", "combined-form", "formal", "imperative", "object-first-person", "object-plural", "obsolete", "plural", "second-person"),
        )))
        // A note never names a form on its own, and on a finite form informal is tú, not a note.
        assertNull(SpanishParadigm.label(listOf(f("grx", "form-of", "informal"))))
        assertEquals("tú · presente", SpanishParadigm.label(listOf(f("hablas", "indicative", "informal", "present", "second-person", "singular"))))
    }

    @Test
    fun `clitic imperatives keep their address, usted and vos as the clitic table tags them`() {
        // The clitic table tags usted `formal second-person` (the plain table:
        // `third-person formal second-person-semantically`) and vos `with-vos`
        // (the plain table: `vos-form`); a page says `with-voseo`.
        assertEquals("usted · imperativo + me", SpanishParadigm.label(listOf(
            f("hábleme", "combined-form", "dative", "formal", "imperative", "object-first-person", "object-singular",
              "second-person", "singular"),
        )))
        assertEquals("ustedes · imperativo + me", SpanishParadigm.label(listOf(
            f("háblenme", "combined-form", "dative", "formal", "imperative", "object-first-person", "object-singular",
              "plural", "second-person"),
        )))
        assertEquals("vos · imperativo + me", SpanishParadigm.label(listOf(
            f("hablame", "combined-form", "dative", "imperative", "informal", "object-first-person", "object-singular",
              "second-person", "singular", "with-vos"),
        )))
        assertEquals("vos · imperativo + te", SpanishParadigm.label(listOf(
            f("molate", "form-of", "imperative", "object-second-person", "object-singular", "second-person", "singular",
              "with-voseo"),
        )))
        // An attached se is an untagged third-person object in the table and
        // `reflexive` on a page; both read "se" off the surface.
        assertEquals("usted · imperativo + se", SpanishParadigm.label(listOf(
            f("háblese", "accusative", "combined-form", "formal", "imperative", "object-singular", "object-third-person",
              "second-person", "singular"),
        )))
        assertEquals("ustedes · imperativo + se", SpanishParadigm.label(listOf(
            f("háblense", "combined-form", "dative", "formal", "imperative", "object-plural", "object-third-person",
              "plural", "second-person"),
        )))
        assertEquals("usted · imperativo + se", SpanishParadigm.label(listOf(
            f("siéntese", "form-of", "imperative", "reflexive", "singular", "third-person"),
        )))
        // The pronoun hangs off the verb, the first word of a phrasal form.
        assertEquals("nosotros · imperativo + nos", SpanishParadigm.label(listOf(
            f("démonos a luz", "accusative", "combined-form", "first-person", "imperative", "object-first-person",
              "object-plural", "plural"),
        )))
    }

    @Test
    fun `a page merging persons or tenses in one tagset names each`() {
        assertEquals("nosotros · presente / pretérito", SpanishParadigm.label(listOf(
            f("quejamos", "first-person", "form-of", "indicative", "plural", "present", "preterite"),
        )))
        assertEquals("yo / él, ella, usted · imperfecto de subjuntivo", SpanishParadigm.label(listOf(
            f("alistara", "first-person", "form-of", "imperfect", "singular", "subjunctive", "third-person"),
        )))
        // A headline form names its tense with no mood: the indicative.
        assertEquals("yo · presente", SpanishParadigm.label(listOf(f("alisto", "first-person", "present", "singular"))))
        // A region or usage tag changes nothing.
        assertEquals("vos · presente", SpanishParadigm.label(listOf(
            f("hablás", "Uruguay", "indicative", "informal", "present", "second-person", "singular", "vos-form"),
        )))
    }

    @Test
    fun `the label fails closed`() {
        // A tag outside the vocabulary.
        assertNull(SpanishParadigm.label(listOf(f("te", "oblique"))))
        assertNull(SpanishParadigm.label(listOf(f("hablo", "first-person", "indicative", "present", "singular", "Mexico"))))
        // Contradictions: formal and voseo, vos in the plural, an informal third person.
        assertNull(SpanishParadigm.label(listOf(
            f("hable", "formal", "imperative", "second-person", "singular", "with-vos"),
        )))
        assertNull(SpanishParadigm.label(listOf(
            f("habláis", "indicative", "plural", "present", "second-person", "vos-form"),
        )))
        assertNull(SpanishParadigm.label(listOf(
            f("habla", "indicative", "informal", "present", "singular", "third-person"),
        )))
        // A verb form with no person, a tense on an imperative, `past` off a participle.
        assertNull(SpanishParadigm.label(listOf(f("vaya a saber", "formal", "second-person"))))
        assertNull(SpanishParadigm.label(listOf(f("habla", "imperative", "present", "second-person", "singular"))))
        assertNull(SpanishParadigm.label(listOf(f("habló", "indicative", "past", "singular", "third-person"))))
        // A clitic the tags allow but the surface does not carry.
        assertNull(SpanishParadigm.label(listOf(
            f("háblale", "combined-form", "dative", "imperative", "informal", "object-first-person", "object-singular",
              "second-person", "singular", "with-tú"),
        )))
        // A noun's or adjective's form with a person on it.
        assertNull(SpanishParadigm.label(listOf(f("a mis solas", "first-person", "form-of", "singular"))))
    }

    @Test
    fun `clitic-attached forms are not cells`() {
        val forms = hablar() +
            f("háblame", "combined-form", "dative", "imperative", "informal",
              "object-first-person", "object-singular", "second-person", "singular", "with-tú") +
            f("háblale", "form-of", "imperative", "object-singular", "object-third-person", "second-person", "singular")
        val imp = SpanishParadigm.table("hablar", forms, highlight = "háblame")!!.section("Imperativo")
        assertEquals(listOf("habla"), imp.cell("tú", "afirmativo").forms)
    }

    @Test
    fun `a label caps at three parts`() {
        val label = SpanishParadigm.label(listOf(
            f("x", "first-person", "indicative", "present", "singular"),
            f("x", "indicative", "preterite", "second-person", "singular"),
            f("x", "imperfect", "indicative", "plural", "third-person"),
            f("x", "first-person", "future", "indicative", "plural"),
        ))
        assertEquals("yo · presente; tú · pretérito; ellos, ellas, ustedes · imperfecto", label)
    }

    // ── table ───────────────────────────────────────────────────────────

    /** hablar's table in the pack's shape, cut down to what the assertions read. */
    private fun hablar(): List<InflectionForm> = listOf(
        f("hablar", "infinitive"),
        f("hablando", "gerund"),
        f("hablado", "masculine", "participle", "past", "singular"),
        f("hablada", "feminine", "participle", "past", "singular"),
        f("hablo", "first-person", "indicative", "present", "singular"),
        f("hablas", "indicative", "informal", "present", "second-person", "singular"),
        f("hablás", "indicative", "informal", "present", "second-person", "singular", "vos-form"),
        f("habla", "indicative", "present", "singular", "third-person"),
        f("hablamos", "first-person", "indicative", "plural", "present"),
        f("habláis", "indicative", "plural", "present", "second-person"),
        f("hablan", "indicative", "plural", "present", "third-person"),
        f("hablé", "first-person", "indicative", "preterite", "singular"),
        f("hablamos", "first-person", "indicative", "plural", "preterite"),
        f("hablaría", "conditional", "first-person", "indicative", "singular"),
        f("hable", "first-person", "present", "singular", "subjunctive"),
        f("hablara", "first-person", "imperfect", "singular", "subjunctive"),
        f("hablase", "first-person", "imperfect", "imperfect-se", "singular", "subjunctive"),
        f("hablare", "first-person", "future", "singular", "subjunctive"),
        f("habla", "imperative", "informal", "second-person", "singular"),
        f("hablá", "imperative", "informal", "second-person", "singular", "vos-form"),
        f("hable", "formal", "imperative", "second-person-semantically", "singular", "third-person"),
        f("hable", "imperative", "singular", "third-person"),
        f("hables", "imperative", "negative", "second-person", "singular"),
        f("hablemos", "first-person", "imperative", "plural"),
        f("hablad", "imperative", "plural", "second-person"),
        f("hablen", "formal", "imperative", "plural", "second-person-semantically", "third-person"),
    )

    private fun ConjugationTable.section(title: String) = sections.first { it.title == title }
    private fun ConjugationSection.cell(row: String, column: String) =
        rows.first { it.label == row }.cells[columns.indexOf(column)]

    @Test
    fun `the grid has the four sections in order with pronouns down and tenses across`() {
        val table = SpanishParadigm.table("hablar", hablar(), highlight = null)!!
        assertEquals(listOf("Indicativo", "Subjuntivo", "Imperativo", "No personales"), table.sections.map { it.title })
        val ind = table.section("Indicativo")
        assertEquals(listOf("presente", "pretérito", "imperfecto", "futuro", "condicional"), ind.columns)
        assertEquals(
            listOf("yo", "tú", "él, ella, usted", "nosotros", "vosotros", "ellos, ellas, ustedes"),
            ind.rows.map { it.label },
        )
        assertEquals(listOf("hablo"), ind.cell("yo", "presente").forms)
        assertEquals(listOf("hablamos"), ind.cell("nosotros", "pretérito").forms)
        assertEquals(listOf("hablaría"), ind.cell("yo", "condicional").forms)
        assertTrue(ind.cell("tú", "futuro").isEmpty)
        assertEquals(0, table.openSection)
    }

    @Test
    fun `vos sits under tú as the companion form`() {
        val table = SpanishParadigm.table("hablar", hablar(), highlight = null)!!
        val tu = table.section("Indicativo").cell("tú", "presente")
        assertEquals(listOf("hablas"), tu.forms)
        assertEquals(listOf("hablás"), tu.secondary)
        assertTrue(table.section("Indicativo").cell("yo", "presente").secondary.isEmpty())
        assertEquals(listOf("hablá"), table.section("Imperativo").cell("tú", "afirmativo").secondary)
    }

    @Test
    fun `every voseo form is a companion under tú whatever tag said vos, never a main form`() {
        // The table tags Rioplatense vos `vos-form`; the Chilean forms come from
        // pages the pack appends tagged `with-voseo` (ser: eris, soi beside sos).
        val forms = hablar() +
            f("hablái", "form-of", "indicative", "present", "second-person", "singular", "with-voseo") +
            f("habléis", "form-of", "present", "second-person", "singular", "subjunctive", "with-voseo")
        val table = SpanishParadigm.table("hablar", forms, highlight = "hablái")!!
        val present = table.section("Indicativo").cell("tú", "presente")
        assertEquals(listOf("hablas"), present.forms)
        assertEquals(listOf("hablás", "hablái"), present.secondary)
        assertTrue("the arrival form lights its cell from the companion slot too", present.highlighted)
        // The fixture has no tú subjunctive row: the cell is the companion alone.
        assertTrue(table.section("Subjuntivo").cell("tú", "presente").forms.isEmpty())
        assertEquals(listOf("habléis"), table.section("Subjuntivo").cell("tú", "presente").secondary)
        // The line and the grid read the same classification.
        assertEquals("vos · presente", SpanishParadigm.label(listOf(forms.first { it.surface == "hablái" })))
        assertEquals(true, SpanishParadigm.classify(forms.first { it.surface == "hablái" })!!.voseo)
        assertEquals(false, SpanishParadigm.classify(forms.first { it.surface == "hablas" })!!.voseo)
    }

    @Test
    fun `a line the classifier does not read is no cell`() {
        val forms = hablar() + f("hablas", "indicative", "informal", "present", "second-person", "singular", "Mexico")
        val tu = SpanishParadigm.table("hablar", forms, highlight = null)!!.section("Indicativo").cell("tú", "presente")
        assertEquals(listOf("hablas"), tu.forms)  // once, from the table row; the unknown-tagged line adds nothing
        assertNull(SpanishParadigm.classify(forms.last()))
    }

    @Test
    fun `the subjunctive splits the two imperfects and dims the future`() {
        val sub = SpanishParadigm.table("hablar", hablar(), highlight = null)!!.section("Subjuntivo")
        assertEquals(listOf("presente", "imperfecto (-ra)", "imperfecto (-se)", "futuro"), sub.columns)
        assertEquals(listOf("hablara"), sub.cell("yo", "imperfecto (-ra)").forms)
        assertEquals(listOf("hablase"), sub.cell("yo", "imperfecto (-se)").forms)
        assertEquals(listOf("hablare"), sub.cell("yo", "futuro").forms)
        assertEquals(setOf(3), sub.rareColumns)
        assertEquals("raro", sub.rareMarker)
    }

    @Test
    fun `the imperative has no yo row, folds usted's duplicate listing, and writes the negative with no`() {
        val imp = SpanishParadigm.table("hablar", hablar(), highlight = null)!!.section("Imperativo")
        assertEquals(listOf("tú", "usted", "nosotros", "vosotros", "ustedes"), imp.rows.map { it.label })
        assertEquals(listOf("afirmativo", "negativo"), imp.columns)
        assertEquals(listOf("habla"), imp.cell("tú", "afirmativo").forms)
        assertEquals(listOf("hable"), imp.cell("usted", "afirmativo").forms)  // listed twice by kaikki
        assertEquals(listOf("no hables"), imp.cell("tú", "negativo").forms)
        assertEquals(listOf("hablemos"), imp.cell("nosotros", "afirmativo").forms)
        assertEquals(listOf("hablen"), imp.cell("ustedes", "afirmativo").forms)
    }

    @Test
    fun `the non-finite forms are a list`() {
        val nf = SpanishParadigm.table("hablar", hablar(), highlight = null)!!.section("No personales")
        assertTrue(nf.isList)
        assertEquals(listOf("infinitivo", "gerundio", "participio"), nf.rows.map { it.label })
        assertEquals(listOf("hablado"), nf.rows[2].cells[0].forms)  // masculine singular, not hablada
    }

    @Test
    fun `alternate participles are listed side by side and either one opens the list`() {
        // imprimir: imprimido and impreso are both its participle (25 such verbs
        // in es-v5: frito, electo, bendito…); the agreeing forms are not listed.
        val forms = hablar().filterNot { "participle" in it.tags } +
            f("imprimido", "masculine", "participle", "past", "singular") +
            f("impreso", "masculine", "participle", "past", "singular") +
            f("impresa", "feminine", "participle", "past", "singular")
        val table = SpanishParadigm.table("imprimir", forms, highlight = "impreso")!!
        val list = table.section("No personales")
        assertEquals(listOf("imprimido", "impreso"), list.rows.first { it.label == "participio" }.cells[0].forms)
        assertTrue(list.rows.first { it.label == "participio" }.cells[0].highlighted)
        assertEquals(3, table.openSection)
    }

    @Test
    fun `the arrival form lights its cells and picks the section to open`() {
        val table = SpanishParadigm.table("hablar", hablar(), highlight = "hablamos")!!
        val ind = table.section("Indicativo")
        assertTrue(ind.cell("nosotros", "presente").highlighted)
        assertTrue(ind.cell("nosotros", "pretérito").highlighted)
        assertFalse(ind.cell("yo", "presente").highlighted)
        assertEquals(0, table.openSection)

        val onNegative = SpanishParadigm.table("hablar", hablar(), highlight = "hables")!!
        assertTrue(onNegative.section("Imperativo").cell("tú", "negativo").highlighted)
        assertEquals(2, onNegative.openSection)

        // Case from the source text does not matter.
        assertTrue(SpanishParadigm.table("hablar", hablar(), highlight = "Hablamos")!!
            .section("Indicativo").cell("nosotros", "presente").highlighted)
    }

    @Test
    fun `a pronominal lemma keeps its two-word forms and a plain lemma drops the bundled ones`() {
        val quejarse = listOf(
            f("quejarse", "infinitive"),
            f("me quejo", "first-person", "indicative", "present", "singular"),
            f("te quejas", "indicative", "informal", "present", "second-person", "singular"),
            f("se queja", "indicative", "present", "singular", "third-person"),
            f("nos quejamos", "first-person", "indicative", "plural", "present"),
            f("os quejáis", "indicative", "plural", "present", "second-person"),
            f("se quejan", "indicative", "plural", "present", "third-person"),
            f("me quejé", "first-person", "indicative", "preterite", "singular"),
        )
        val table = SpanishParadigm.table("quejarse", quejarse, highlight = "quejo")!!
        val cell = table.section("Indicativo").cell("yo", "presente")
        assertEquals(listOf("me quejo"), cell.forms)
        assertTrue("the verb of a two-word form matches the arrival surface", cell.highlighted)

        // ir's table bundles irse: the plain lemma shows voy, not me voy, and
        // irse's single-word imperative (vete, tagged table-3 by the builder)
        // stays out of ir's cell.
        val ir = listOf(
            f("ir", "infinitive"),
            f("voy", "first-person", "indicative", "present", "singular"),
            f("me voy", "first-person", "indicative", "present", "singular", "table-2", "pronominal"),
            f("ve", "imperative", "informal", "second-person", "singular"),
            f("vete", "imperative", "informal", "second-person", "singular", "table-2", "pronominal"),
            f("vas", "indicative", "informal", "present", "second-person", "singular"),
            f("va", "indicative", "present", "singular", "third-person"),
            f("vamos", "first-person", "indicative", "plural", "present"),
            f("vais", "indicative", "plural", "present", "second-person"),
            f("van", "indicative", "plural", "present", "third-person"),
        )
        val irTable = SpanishParadigm.table("ir", ir, highlight = null)!!
        assertEquals(listOf("voy"), irTable.section("Indicativo").cell("yo", "presente").forms)
        assertEquals(listOf("ve"), irTable.section("Imperativo").cell("tú", "afirmativo").forms)

        // acostar: a second one-word table is the lemma's own; its forms are alternates.
        val acostar = listOf(
            f("acostar", "infinitive"),
            f("acuesta", "indicative", "present", "singular", "third-person"),
            f("acosta", "indicative", "present", "singular", "table-2", "third-person"),
        ) + hablar().drop(1).map { f(it.surface.replace("habl", "acost"), *it.tags.toTypedArray()) }
        assertEquals(
            listOf("acuesta", "acosta"),
            SpanishParadigm.table("acostar", acostar, highlight = null)!!.section("Indicativo").cell("él, ella, usted", "presente").forms,
        )
    }

    @Test
    fun `nouns and stub tables get no card`() {
        assertNull(SpanishParadigm.table("casa", listOf(f("casas", "plural")), highlight = null))
        // A verb the pack knows only from its headline forms (three lines).
        assertNull(SpanishParadigm.table("abarcar", listOf(
            f("abarco", "first-person", "present", "singular"),
            f("abarqué", "first-person", "preterite", "singular"),
            f("abarcado", "participle", "past"),
        ), highlight = null))
        assertNotNull(SpanishParadigm.table("hablar", hablar(), highlight = null))
    }
}
