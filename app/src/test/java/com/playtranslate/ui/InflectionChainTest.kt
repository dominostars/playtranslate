package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.R
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.InflectionForm
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.ParadigmReading
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.inflection.InflectionParadigms
import com.playtranslate.model.DictionaryEntry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [InflectionChain]: the one line format every surface draws (surface,
 * " · ", tags joined with " « " through [InflectionTag.label]) and how a
 * lookup's deinflection chain and a token's tags compose into it.
 */
@RunWith(RobolectricTestRunner::class)
class InflectionChainTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `format joins the labels with guillemets after the surface`() {
        val form = InflectedForm(
            "飲んでいなかった",
            listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.NEGATIVE, InflectionTag.TA),
        )
        val negative = ctx.getString(R.string.inflection_negative)
        assertEquals("飲んでいなかった · -て « -いる « $negative « -た", InflectionChain.format(ctx, form))
    }

    @Test
    fun `format uses each tag's label, localized names included`() {
        val form = InflectedForm("言わせて", listOf(InflectionTag.CAUSATIVE, InflectionTag.TE))
        assertEquals(
            "言わせて · " + InflectionTag.CAUSATIVE.label(ctx) + " « -て",
            InflectionChain.format(ctx, form),
        )
        assertEquals(ctx.getString(R.string.inflection_causative), InflectionTag.CAUSATIVE.label(ctx))
    }

    @Test
    fun `compose is null when neither chain has a step`() {
        assertNull(InflectionChain.compose("食べる", emptyList(), emptyList()))
    }

    @Test
    fun `compose puts the lookup's deinflection before the token's tags`() {
        val form = InflectionChain.compose(
            "弾けた",
            deinflection = listOf(InflectionTag.POTENTIAL),
            tokenTags = listOf(InflectionTag.TA),
        )
        assertEquals(InflectedForm("弾けた", listOf(InflectionTag.POTENTIAL, InflectionTag.TA)), form)
    }

    @Test
    fun `compose keeps either chain alone`() {
        assertEquals(
            InflectedForm("食べた", listOf(InflectionTag.TA)),
            InflectionChain.compose("食べた", emptyList(), listOf(InflectionTag.TA)),
        )
        assertEquals(
            InflectedForm("食べません", listOf(InflectionTag.MASU, InflectionTag.NEGATIVE)),
            InflectionChain.compose("食べません", listOf(InflectionTag.MASU, InflectionTag.NEGATIVE), emptyList()),
        )
    }

    // ── Wiktionary-pack paradigm readings ─────────────────────────────

    private val hablamos = listOf(
        InflectionForm("hablamos", setOf("first-person", "indicative", "plural", "present")),
        InflectionForm("hablamos", setOf("first-person", "indicative", "plural", "preterite")),
    )

    @Test
    fun `format renders a paradigm reading through the language's paradigm`() {
        val form = InflectedForm("hablamos", emptyList(), ParadigmReading(SourceLangId.ES, hablamos))
        assertEquals("hablamos · nosotros · presente / pretérito", InflectionChain.format(ctx, form))
    }

    @Test
    fun `compose keeps a paradigm reading alone`() {
        val reading = ParadigmReading(SourceLangId.ES, hablamos)
        assertEquals(
            InflectedForm("hablamos", emptyList(), reading),
            InflectionChain.compose("hablamos", emptyList(), emptyList(), reading),
        )
        assertNull(InflectionChain.compose("hablamos", emptyList(), emptyList(), null))
    }

    @Test
    fun `a reading of another entry names its lemma, and lines draws one line per reading`() {
        val venir = ParadigmReading(
            SourceLangId.ES,
            listOf(InflectionForm("vino", setOf("indicative", "preterite", "singular", "third-person"))),
            lemma = "venir",
        )
        assertEquals(
            "vino · venir · él, ella, usted · pretérito",
            InflectionChain.format(ctx, InflectedForm("vino", emptyList(), venir)),
        )
        val own = ParadigmReading(SourceLangId.ES, hablamos)
        assertEquals(
            listOf(InflectedForm("hablamos", emptyList(), own), InflectedForm("hablamos", emptyList(), venir)),
            InflectionChain.lines("hablamos", emptyList(), emptyList(), listOf(own, venir)),
        )
        // No paradigm: the Japanese chain line, or nothing.
        assertEquals(
            listOf(InflectedForm("食べた", listOf(InflectionTag.TA))),
            InflectionChain.lines("食べた", emptyList(), listOf(InflectionTag.TA), emptyList()),
        )
        assertEquals(emptyList<InflectedForm>(), InflectionChain.lines("casa", emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `readingsOf covers every entry that reads the surface and names the non-headword ones`() {
        val vinoNoun = DictionaryEntry(
            slug = "vino", isCommon = true, tags = emptyList(), jlpt = emptyList(),
            headwords = emptyList(), senses = emptyList(),
        )
        val preterite = listOf(InflectionForm("vino", setOf("indicative", "preterite", "singular", "third-person")))
        val venir = vinoNoun.copy(slug = "venir", formReadings = preterite)
        assertEquals(
            listOf(ParadigmReading(SourceLangId.ES, preterite, lemma = "venir")),
            InflectionParadigms.readingsOf(SourceLangId.ES, listOf(vinoNoun, venir), headword = "vino"),
        )
        // The headword's own entry carries no lemma.
        assertEquals(
            listOf(ParadigmReading(SourceLangId.ES, preterite, lemma = null)),
            InflectionParadigms.readingsOf(SourceLangId.ES, listOf(venir), headword = "venir"),
        )
        assertEquals(emptyList<ParadigmReading>(), InflectionParadigms.readingsOf(SourceLangId.VI, listOf(venir), "venir"))
    }

    @Test
    fun `readingOf builds a reading only for a paradigm language with readings`() {
        val entry = DictionaryEntry(
            slug = "hablar", isCommon = true, tags = emptyList(), jlpt = emptyList(),
            headwords = emptyList(), senses = emptyList(), formReadings = hablamos,
        )
        assertEquals(ParadigmReading(SourceLangId.ES, hablamos), InflectionParadigms.readingOf(SourceLangId.ES, entry))
        assertNull(InflectionParadigms.readingOf(SourceLangId.VI, entry))
        assertNull(InflectionParadigms.readingOf(SourceLangId.ES, entry.copy(formReadings = emptyList())))
        // A reading from a page's later table (vete under ir) is not a reading of the lemma.
        val secondary = InflectionForm("vete", setOf("imperative", "informal", "second-person", "singular", "table-2", "pronominal"))
        assertNull(InflectionParadigms.readingOf(SourceLangId.ES, entry.copy(formReadings = listOf(secondary))))
        assertEquals(
            ParadigmReading(SourceLangId.ES, hablamos),
            InflectionParadigms.readingOf(SourceLangId.ES, entry.copy(formReadings = hablamos + secondary)),
        )
        assertNull(InflectionParadigms.readingOf(SourceLangId.ES, null))
        assertNotNull(InflectionParadigms[SourceLangId.ES])
        assertNull(InflectionParadigms[SourceLangId.JA])
    }
}
