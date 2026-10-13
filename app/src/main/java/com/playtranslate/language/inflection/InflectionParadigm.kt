package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import com.playtranslate.language.ParadigmReading
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.DictionaryEntry

/**
 * How one language turns the pack's tagged forms ([InflectionForm], kaikki's
 * tags verbatim) into what the user sees: the one-line label under a looked-up
 * form ("nosotros · presente / pretérito") and the lemma's conjugation table.
 * The pack is language-neutral and rebuilt once; everything a language knows
 * about its own grammar (which tags mean "nosotros", which tenses make a grid,
 * what to call them) lives in its paradigm object, registered in
 * [InflectionParadigms]. A language without one shows today's `[inflected]`
 * marker and no table, even on a pack that carries the data.
 *
 * Labels and table titles are in the SOURCE language (presente, Indicativo),
 * the way the language's own references and classrooms name them, so they are
 * plain strings here rather than UI string resources; only the card's group
 * header is UI text.
 */
interface InflectionParadigm {

    /** The line for a looked-up form from its [readings] (every pack line whose
     *  surface is the one looked up): "nosotros · presente / pretérito",
     *  "gerundio", "femenino plural", "second-person singular present
     *  (archaic)". Null when the readings say nothing worth a line (a noun's
     *  own masculine singular).
     *
     *  The format every paradigm keeps, since the gates parse it: groups are
     *  joined by "; ", a group is "pronoun · name" or "name", pronouns and
     *  names are each joined by " / ", and a name never contains " · ". A
     *  name's parentheses hold only its usage notes, the words for kaikki's
     *  usage tags (archaic, rare, colloquial, dialectal) in the language's own
     *  terms, so a player reading "speakest · second-person singular present
     *  (archaic)" learns the register as well as the grammar; the oracle
     *  compares the grammar with the parentheses stripped. */
    fun label(readings: List<InflectionForm>): String?

    /** The table for [lemma]'s whole set of pack [forms], or null when they
     *  do not fill a grid this language draws (a noun, a stub table).
     *  [highlight] is the surface the user arrived from, build-lowercased;
     *  its cells come back highlighted and the section holding the first of
     *  them is the one to open. */
    fun table(lemma: String, forms: List<InflectionForm>, highlight: String?): ConjugationTable?

    /** How this paradigm reads one pack line: the one classification both
     *  [label] and [table] are drawn from, so the gates
     *  (PackInflectionOracleTest) judge the grid by the same reading the grid
     *  was built from. Null when the line gets no label. */
    fun classify(form: InflectionForm): FormClass?

    /** Whether [tag] is in this paradigm's vocabulary. A form carrying a tag
     *  outside it gets no label; the gates tell such a line from one whose
     *  known tags say nothing or contradict each other. */
    fun isKnown(tag: String): Boolean
}

/**
 * One pack line as a paradigm reads it, language-neutral: what the line's
 * [label] says, and the facts the grid places it by. Everything a paradigm
 * shows for a form comes from one of these, read from the tags in one place.
 */
data class FormClass(
    /** A verb form, finite or not, rather than a noun's or adjective's agreement. */
    val verbal: Boolean,
    /** A person-and-number form, which the grid places in a cell. */
    val finite: Boolean,
    /** A pronoun is attached (háblame): labelled, never a cell. */
    val clitic: Boolean,
    /** Addresses vos: a cell's companion form, never its main one. */
    val voseo: Boolean,
    /** A noun's, adjective's or participle's agreement words ("femenino plural"); null for a form
     *  carrying none, which for a non-finite form is the bare one the table lists. */
    val agreement: String?,
    val label: String,
)

/** The per-language registry. Spanish first; every later language is one
 *  object and one line here, no pack change. */
object InflectionParadigms {

    operator fun get(lang: SourceLangId): InflectionParadigm? = when (lang) {
        SourceLangId.ES -> SpanishParadigm
        SourceLangId.PT -> PortugueseParadigm
        SourceLangId.FR -> FrenchParadigm
        SourceLangId.DE -> GermanParadigm
        SourceLangId.EN -> EnglishParadigm
        else -> null
    }

    /** The paradigm reading for [entry] in [lang]: null unless the language
     *  has a paradigm and the entry carries readings of the looked-up surface
     *  ([DictionaryEntry.formReadings]). The one constructor of
     *  [ParadigmReading], so an unsupported language never gets a line. */
    fun readingOf(lang: SourceLangId, entry: DictionaryEntry?): ParadigmReading? {
        if (entry == null || get(lang) == null) return null
        val readings = entry.formReadings.filterNot { it.fromPronominalVariant }
        if (readings.isEmpty()) return null
        return ParadigmReading(lang, readings)
    }

    /** One reading per entry of a response that carries readings of the
     *  looked-up surface, in response order, each naming its lemma when that
     *  is not [headword] (the word the surface is shown under). A surface that
     *  is one entry's lemma and another's form (vino, habla, abierto: 3,037
     *  verb forms in the es pack) draws the other entry's line too. */
    fun readingsOf(lang: SourceLangId, entries: List<DictionaryEntry>, headword: String?): List<ParadigmReading> {
        if (get(lang) == null) return emptyList()
        return entries.mapNotNull { entry ->
            readingOf(lang, entry)?.copy(lemma = entry.slug.takeIf { it != headword })
        }
    }
}

/**
 * kaikki's tag vocabulary, shared across languages (the same strings tag
 * Spanish, French, Portuguese, Italian and German tables), so the tokens are
 * named once. Constants only: a paradigm reads a form's tags in exactly one
 * place, its classifier, and nothing else interprets them.
 */
object KaikkiTags {
    const val FIRST = "first-person"
    const val SECOND = "second-person"
    const val THIRD = "third-person"
    const val SINGULAR = "singular"
    const val PLURAL = "plural"
    const val MASCULINE = "masculine"
    const val FEMININE = "feminine"
    const val NEUTER = "neuter"

    const val PRESENT = "present"
    const val PRETERITE = "preterite"
    const val IMPERFECT = "imperfect"
    const val FUTURE = "future"
    const val CONDITIONAL = "conditional"
    const val PLUPERFECT = "pluperfect"
    const val HISTORIC = "historic"
    const val PERFECT = "perfect"
    const val PAST = "past"

    const val INDICATIVE = "indicative"
    const val SUBJUNCTIVE = "subjunctive"
    const val IMPERATIVE = "imperative"
    const val NEGATIVE = "negative"
    const val FORMAL = "formal"
    const val INFORMAL = "informal"

    const val INFINITIVE = "infinitive"
    const val GERUND = "gerund"
    const val PARTICIPLE = "participle"

    const val COMBINED_FORM = "combined-form"
    const val OBJECT_FIRST = "object-first-person"
    const val OBJECT_SECOND = "object-second-person"
    const val OBJECT_THIRD = "object-third-person"
    const val OBJECT_SINGULAR = "object-singular"
    const val OBJECT_PLURAL = "object-plural"
    const val DATIVE = "dative"
    const val ACCUSATIVE = "accusative"
    const val REFLEXIVE = "reflexive"

    const val COMPARATIVE = "comparative"
    const val SUPERLATIVE = "superlative"
    const val DIMINUTIVE = "diminutive"
    const val AUGMENTATIVE = "augmentative"
}

/** What a [ConjugationTable] is of, which names the card: a verb's
 *  conjugation, a noun's declension (German), or a word's forms as a plain
 *  list (English). The card's header is UI text, chosen by this. */
enum class TableKind { CONJUGATION, DECLENSION, FORMS }

/**
 * A rendered conjugation table, language-neutral: one [ConjugationSection]
 * per chip (a mood, or the non-finite forms), each a grid of
 * [ConjugationRow]s (a pronoun) by columns (a tense), or a two-column list
 * when [ConjugationSection.isList]. [openSection] is the chip to start on;
 * a table with one section shows no chips. [kind] names the card.
 */
data class ConjugationTable(
    val sections: List<ConjugationSection>,
    val openSection: Int,
    val kind: TableKind = TableKind.CONJUGATION,
)

data class ConjugationSection(
    val title: String,
    val columns: List<String>,
    val rows: List<ConjugationRow>,
    /** Column indexes to draw dimmed (a tense the language's references mark
     *  rare); their headers carry [rareMarker]. */
    val rareColumns: Set<Int> = emptySet(),
    val rareMarker: String? = null,
    /** A two-column label/form list (the non-finite forms) rather than a grid:
     *  [columns] is empty and every row has one cell. */
    val isList: Boolean = false,
)

data class ConjugationRow(val label: String, val cells: List<ConjugationCell>)

data class ConjugationCell(
    /** The form(s) in this cell, alternatives in order (two spellings of one
     *  slot). Empty for a slot the pack has no form for. */
    val forms: List<String>,
    /** Smaller companion forms under the main ones (Spanish vos under tú:
     *  Rioplatense sos, Chilean soi), in order. */
    val secondary: List<String> = emptyList(),
    /** Whether the surface the user arrived from sits in this cell. */
    val highlighted: Boolean = false,
) {
    val isEmpty: Boolean get() = forms.isEmpty() && secondary.isEmpty()
}
