package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FIRST
import com.playtranslate.language.inflection.KaikkiTags.GERUND
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PAST
import com.playtranslate.language.inflection.KaikkiTags.PLURAL
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.REFLEXIVE
import com.playtranslate.language.inflection.KaikkiTags.SECOND
import com.playtranslate.language.inflection.KaikkiTags.SINGULAR
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import com.playtranslate.language.inflection.KaikkiTags.THIRD
import java.util.Locale

/**
 * English: the line under a looked-up form, and a Forms card. English has no
 * person paradigm to draw as a grid (speaks is the one agreeing form), so
 * what a reader needs is the name of the form in the dictionary's own words:
 * spoke · past, spoken · past participle, speaks · third-person singular
 * present, children · plural, bigger · comparative; and for the verbs kaikki
 * conjugates in full (450 common ones: be, have, do, walk, run, with their
 * archaic -est and -eth rows), the person and number before the tense (am ·
 * first-person singular present, were · plural past / past subjunctive).
 *
 * One reader of the tags, [read]: a total classification over the en pack's
 * closed vocabulary (73 tags), failing closed on an unknown tag, two tenses,
 * or a form with nothing to say (a lemma's own singular). A tense name is
 * built from the parts present, in a fixed order: person, number, then the
 * tense or mood; a participle is "past participle" or "present participle"
 * whatever else is tagged beside it. US and UK spellings (penciled, pencilled)
 * are both the form and read the same. A usage tag follows the name in
 * parentheses, in kaikki's own word (speakest · second-person singular present
 * (archaic); sitten · past participle (archaic, dialectal); citri · plural
 * (formal)), since a player meeting thou speakest in a game wants the
 * register as much as the grammar; a named region (US, Scotland) says
 * nothing.
 *
 * The card ([table]) is a list of the principal parts, drawn from the same
 * reading: for the 19,132 verbs whose pack rows are the four headline forms,
 * the four rows (third-person singular present, past, past participle,
 * present participle); for a fully conjugated verb the persons fold into
 * present and past (walk: infinitive walk; present walk, walkest;
 * third-person singular present walks, walketh; past walked, walkedst; past
 * participle walked; present participle walking), and a subjunctive or
 * imperative row is listed only when it brings a surface the other rows do
 * not (be: present am, are, art; past was, were, wast, wert; the subjunctive
 * be and were are those). Spellings and archaic alternates share the row.
 * Adjectives list comparative and superlative; a noun's one plural row is no
 * card, since a single row is what the line already says.
 */
object EnglishParadigm : InflectionParadigm {

    private val PERSON_WORDS = linkedMapOf(FIRST to "first-person", SECOND to "second-person", THIRD to "third-person")
    private val NUMBER_WORDS = linkedMapOf(SINGULAR to "singular", PLURAL to "plural")
    /** Finite tenses and moods, each by the tag that names it; one per form. */
    private val TENSE_WORDS = linkedMapOf(PRESENT to "present", PAST to "past")
    private val DEGREE_WORDS = linkedMapOf(COMPARATIVE to "comparative", SUPERLATIVE to "superlative", DIMINUTIVE to "diminutive")
    private val GENDER_WORDS = linkedMapOf(FEMININE to "feminine", MASCULINE to "masculine", NEUTER to "neuter")
    /** A pronoun's case forms (him, them, whom), in the names English grammars use. */
    private val CASE_WORDS = linkedMapOf(
        "nominative" to "subjective", "subjective" to "subjective",
        "accusative" to "objective", "objective" to "objective", "oblique" to "objective",
        "possessive" to "possessive", "genitive" to "genitive",
    )
    /** kaikki's usage tags the en pack's rows carry, shown after the name in display order. */
    private val USAGE_WORDS = listOf(
        "archaic", "dated", "rare", "uncommon", "colloquial", "informal", "formal", "literary", "poetic", "humorous", "childish",
        "nonstandard", "hypercorrect", "misconstruction", "dialectal", "regional",
    )
    /** Tags that change nothing the label says: a region or spelling, a template's connective, a lexical
     *  note (`gender-neutral` on a pronoun's form, `Early Modern` split in two: left silent). */
    private val IGNORED = setOf(
        "form-of", InflectionForm.PRONOMINAL_TAG, INDICATIVE,
        "US", "UK", "Canada", "Australia", "New-Zealand", "Scotland", "Commonwealth", "Southern-US", "Internet",
        "especially", "also", "gender-neutral", "common", "usually", "sometimes", "in-compounds", "modal", "suppletive", "Early", "Modern", "Lao",
    )
    private val KNOWN: Set<String> = PERSON_WORDS.keys + NUMBER_WORDS.keys + TENSE_WORDS.keys + DEGREE_WORDS.keys +
        GENDER_WORDS.keys + CASE_WORDS.keys + setOf(PARTICIPLE, GERUND, INFINITIVE, IMPERATIVE, SUBJUNCTIVE, REFLEXIVE) + USAGE_WORDS + IGNORED

    private const val TABLE_PREFIX = "table-"
    private const val MAX_LABEL_PARTS = 3

    /** The card's rows for a verb, in paradigm order; any other row (a noun's or
     *  adjective's) follows in the order the pack lists it. */
    private const val ROW_INFINITIVE = "infinitive"
    private const val ROW_PRESENT = "present"
    private const val ROW_THIRD = "third-person singular present"
    private const val ROW_PAST = "past"
    private const val ROW_IMPERATIVE = "imperative"
    private const val ROW_PRESENT_SUBJUNCTIVE = "present subjunctive"
    private const val ROW_PAST_SUBJUNCTIVE = "past subjunctive"
    private const val ROW_PAST_PARTICIPLE = "past participle"
    private const val ROW_PRESENT_PARTICIPLE = "present participle"
    private val ROW_ORDER = listOf(
        ROW_INFINITIVE, ROW_PRESENT, ROW_THIRD, ROW_PRESENT_SUBJUNCTIVE, ROW_IMPERATIVE,
        ROW_PAST, ROW_PAST_SUBJUNCTIVE, ROW_PAST_PARTICIPLE, ROW_PRESENT_PARTICIPLE,
    )
    /** Rows listed only when they bring a surface the other rows do not: for
     *  nearly every verb the subjunctive and the imperative are the base form. */
    private val FOLDED_ROWS = setOf(ROW_PRESENT_SUBJUNCTIVE, ROW_PAST_SUBJUNCTIVE, ROW_IMPERATIVE)
    private const val SECTION_TITLE = "Forms"
    /** A card lists at least this many rows; one row is what the line already says. */
    private const val MIN_ROWS = 2
    private val LOCALE = Locale.ENGLISH

    override fun isKnown(tag: String): Boolean = tag in KNOWN || tag.startsWith(TABLE_PREFIX)

    /** One pack line as read here, the only reading of its tags: the [names]
     *  the line says (one, or one per person when a page merges persons),
     *  each with the form's usage note; the card [row] the form sits in;
     *  whether it is a verb form. */
    private class Read(val names: List<String>, val row: String, val verbal: Boolean)

    override fun classify(form: InflectionForm): FormClass? {
        val read = read(form.tags) ?: return null
        return FormClass(verbal = read.verbal, finite = false, clitic = false, voseo = false, agreement = null, label = read.names.joinToString(" / "))
    }

    override fun label(readings: List<InflectionForm>): String? {
        val names = readings.mapNotNull { read(it.tags) }.flatMap { it.names }.distinct()
        if (names.isEmpty()) return null
        return names.take(MAX_LABEL_PARTS).joinToString(" / ")
    }

    /** The Forms card: one list, a row per principal part with every surface
     *  the pack gives it (spellings and archaic alternates side by side), the
     *  row holding the arrival form lit. Null for fewer than [MIN_ROWS] rows. */
    override fun table(lemma: String, forms: List<InflectionForm>, highlight: String?): ConjugationTable? {
        val target = highlight?.lowercase(LOCALE)
        val byRow = LinkedHashMap<String, MutableList<String>>()
        for (f in forms) {
            if (f.fromPronominalVariant) continue
            val read = read(f.tags) ?: continue
            val surfaces = byRow.getOrPut(read.row) { mutableListOf() }
            if (f.surface !in surfaces) surfaces += f.surface
        }
        val ordered = ROW_ORDER.filter { it in byRow } + byRow.keys.filter { it !in ROW_ORDER }
        val shownElsewhere = ordered.filter { it !in FOLDED_ROWS }.flatMapTo(HashSet()) { byRow.getValue(it) }
        val rows = ordered.filter { it !in FOLDED_ROWS || byRow.getValue(it).any { s -> s !in shownElsewhere } }
        if (rows.size < MIN_ROWS) return null
        val section = ConjugationSection(
            SECTION_TITLE, emptyList(),
            rows.map { name ->
                val surfaces = byRow.getValue(name)
                ConjugationRow(name, listOf(ConjugationCell(surfaces, highlighted = target != null && target in surfaces)))
            },
            isList = true,
        )
        return ConjugationTable(listOf(section), 0, TableKind.FORMS)
    }

    /** The one reading of a form's tags, or null when a tag is outside the
     *  vocabulary, the tags contradict each other, or they say nothing (a
     *  lemma's own singular; a usage note alone names no form). */
    private fun read(tags: Set<String>): Read? {
        if (tags.any { !isKnown(it) }) return null
        val usage = USAGE_WORDS.filter { it in tags }.let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" }
        val persons = PERSON_WORDS.filterKeys { it in tags }.values.toList()
        val numbers = NUMBER_WORDS.filterKeys { it in tags }.values
        val tenses = TENSE_WORDS.filterKeys { it in tags }.values
        // A page merging persons (was: first-person and third-person singular past) names each.
        val merged = persons.size > 1 && tenses.size == 1 && numbers.size == 1 && SUBJUNCTIVE !in tags && IMPERATIVE !in tags &&
            PARTICIPLE !in tags && GERUND !in tags && INFINITIVE !in tags
        val names = if (merged) persons.map { "$it ${numbers.single()} ${tenses.single()}" } else listOfNotNull(nameOf(tags))
        if (names.isEmpty()) return null
        val verbal = tags.any { it == PARTICIPLE || it == GERUND || it == INFINITIVE || it == IMPERATIVE || it in TENSE_WORDS }
        // The card's row: the participles, infinitive, imperative and
        // subjunctives are their own; the finite persons fold into present and
        // past, except the one agreeing form, speaks; anything else (plural,
        // comparative, objective) is its name.
        val row = when {
            PARTICIPLE in tags || GERUND in tags || INFINITIVE in tags || IMPERATIVE in tags || SUBJUNCTIVE in tags -> names.single()
            tenses.isNotEmpty() -> if (!merged && THIRD in tags && SINGULAR in tags && PRESENT in tags) ROW_THIRD else tenses.single()
            else -> names.single()
        }
        return Read(names.map { it + usage }, row, verbal)
    }

    private fun nameOf(tags: Set<String>): String? {
        val persons = PERSON_WORDS.filterKeys { it in tags }.values
        val numbers = NUMBER_WORDS.filterKeys { it in tags }.values
        val tenses = TENSE_WORDS.filterKeys { it in tags }.values
        val degrees = DEGREE_WORDS.filterKeys { it in tags }.values
        val genders = GENDER_WORDS.filterKeys { it in tags }.values
        val cases = CASE_WORDS.filterKeys { it in tags }.values.distinct()
        if (persons.size > 1 || numbers.size > 1 || tenses.size > 1 || cases.size > 1) return null
        return when {
            PARTICIPLE in tags || GERUND in tags -> {
                // spoken, speaking; a gerund is the present participle's form.
                if (persons.isNotEmpty() || numbers.isNotEmpty() || SUBJUNCTIVE in tags || IMPERATIVE in tags || INFINITIVE in tags) return null
                when {
                    PAST in tags -> ROW_PAST_PARTICIPLE
                    PRESENT in tags || GERUND in tags -> ROW_PRESENT_PARTICIPLE
                    else -> null
                }
            }
            INFINITIVE in tags -> if (tenses.isNotEmpty() || persons.isNotEmpty() || numbers.isNotEmpty()) null else ROW_INFINITIVE
            IMPERATIVE in tags -> if (PAST in tags || persons.isNotEmpty() || SUBJUNCTIVE in tags) null else ROW_IMPERATIVE
            SUBJUNCTIVE in tags -> if (tenses.isEmpty() || persons.isNotEmpty()) null else "${tenses.single()} subjunctive"
            tenses.isNotEmpty() -> {
                // speaks: third-person singular present; am: first-person singular
                // present; spoke: past; were: plural past.
                if (degrees.isNotEmpty() || genders.isNotEmpty() || cases.isNotEmpty()) return null
                (persons + numbers + tenses).joinToString(" ")
            }
            else -> {
                // A noun's, adjective's or pronoun's form: degree, gender, case, number
                // (the singular of a plural-only lemma is a form: current event).
                if (persons.isNotEmpty() && REFLEXIVE !in tags && cases.isEmpty()) return null
                val words = degrees.toList() + genders + cases + listOfNotNull("reflexive".takeIf { REFLEXIVE in tags }) +
                    listOfNotNull("plural".takeIf { PLURAL in tags })
                // The singular says something only on its own (the singular of a
                // plural-only lemma); beside a gender or a case it is the default.
                (words.ifEmpty { listOfNotNull("singular".takeIf { SINGULAR in tags }) }).joinToString(" ").ifEmpty { null }
            }
        }
    }
}
