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

/**
 * English: the line under a looked-up form, and no card. English has no
 * person paradigm to draw (speaks is the one agreeing form), so what a
 * learner needs is the name of the form in the dictionary's own words: spoke ·
 * past, spoken · past participle, speaks · third-person singular present,
 * children · plural, bigger · comparative; and for the few verbs kaikki
 * conjugates in full (be, and the archaic -est and -eth rows), the person and
 * number before the tense (am · first-person singular present, were · plural
 * past / past subjunctive).
 *
 * One reader of the tags, [classify]: a total classification over the en
 * pack's closed vocabulary (73 tags), failing closed on an unknown tag, two
 * tenses, or a form with nothing to say (a lemma's own singular). A tense name
 * is built from the parts present, in a fixed order: person, number, then the
 * tense or mood; a participle is "past participle" or "present participle"
 * whatever else is tagged beside it. US and UK spellings (penciled, pencilled)
 * are both the form and read the same. A usage tag follows the name in
 * parentheses, in kaikki's own word (speakest · second-person singular present
 * (archaic); sitten · past participle (archaic, dialectal); citri · plural
 * (formal)), since a player meeting thou speakest in a game wants the
 * register as much as the grammar; a named region (US, Scotland) says
 * nothing.
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

    override fun isKnown(tag: String): Boolean = tag in KNOWN || tag.startsWith(TABLE_PREFIX)

    override fun classify(form: InflectionForm): FormClass? {
        val names = namesOf(form.tags)
        if (names.isEmpty()) return null
        val tags = form.tags
        val verbal = tags.any { it == PARTICIPLE || it == GERUND || it == INFINITIVE || it == IMPERATIVE || it in TENSE_WORDS }
        return FormClass(verbal = verbal, finite = false, clitic = false, voseo = false, agreement = null, label = names.joinToString(" / "))
    }

    override fun label(readings: List<InflectionForm>): String? {
        val names = readings.flatMap { namesOf(it.tags) }.distinct()
        if (names.isEmpty()) return null
        return names.take(MAX_LABEL_PARTS).joinToString(" / ")
    }

    /** No conjugation card for English. */
    override fun table(lemma: String, forms: List<InflectionForm>, highlight: String?): ConjugationTable? = null

    /** The form's names: one, or one per person when a page merges persons
     *  (was: first-person and third-person singular past), each followed by
     *  the form's usage notes. Empty when a tag is outside the vocabulary, the
     *  tags contradict each other, or they say nothing (a lemma's own
     *  singular; a usage note alone names no form). */
    private fun namesOf(tags: Set<String>): List<String> {
        if (tags.any { !isKnown(it) }) return emptyList()
        val usage = USAGE_WORDS.filter { it in tags }.let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" }
        val persons = PERSON_WORDS.filterKeys { it in tags }.values.toList()
        val numbers = NUMBER_WORDS.filterKeys { it in tags }.values
        val tenses = TENSE_WORDS.filterKeys { it in tags }.values
        if (persons.size > 1 && tenses.size == 1 && numbers.size == 1 && SUBJUNCTIVE !in tags && IMPERATIVE !in tags &&
            PARTICIPLE !in tags && GERUND !in tags && INFINITIVE !in tags
        ) {
            return persons.map { "$it ${numbers.single()} ${tenses.single()}$usage" }
        }
        return listOfNotNull(nameOf(tags)?.let { it + usage })
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
                    PAST in tags -> "past participle"
                    PRESENT in tags || GERUND in tags -> "present participle"
                    else -> null
                }
            }
            INFINITIVE in tags -> if (tenses.isNotEmpty() || persons.isNotEmpty() || numbers.isNotEmpty()) null else "infinitive"
            IMPERATIVE in tags -> if (PAST in tags || persons.isNotEmpty() || SUBJUNCTIVE in tags) null else "imperative"
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
