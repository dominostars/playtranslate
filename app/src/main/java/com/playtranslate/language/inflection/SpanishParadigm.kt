package com.playtranslate.language.inflection

import com.playtranslate.language.InflectionForm
import com.playtranslate.language.inflection.KaikkiTags.ACCUSATIVE
import com.playtranslate.language.inflection.KaikkiTags.AUGMENTATIVE
import com.playtranslate.language.inflection.KaikkiTags.COMBINED_FORM
import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.CONDITIONAL
import com.playtranslate.language.inflection.KaikkiTags.DATIVE
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FIRST
import com.playtranslate.language.inflection.KaikkiTags.FORMAL
import com.playtranslate.language.inflection.KaikkiTags.FUTURE
import com.playtranslate.language.inflection.KaikkiTags.GERUND
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.IMPERFECT
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.INFORMAL
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEGATIVE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_FIRST
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_PLURAL
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_SECOND
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_SINGULAR
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_THIRD
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PAST
import com.playtranslate.language.inflection.KaikkiTags.PLURAL
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.PRETERITE
import com.playtranslate.language.inflection.KaikkiTags.REFLEXIVE
import com.playtranslate.language.inflection.KaikkiTags.SECOND
import com.playtranslate.language.inflection.KaikkiTags.SINGULAR
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import com.playtranslate.language.inflection.KaikkiTags.THIRD
import java.util.Locale

/**
 * Spanish: labels and the conjugation table over kaikki's Spanish tags. Tense
 * and mood names are the ones every Spanish reference and classroom uses
 * (presente, pretérito, imperfecto de subjuntivo), pronouns are the standard
 * paradigm rows with vos as a companion form under tú, and the table follows
 * SpanishDict's arrangement: pronouns down, tenses across, one mood per
 * section, both imperfect subjunctives, the imperative as afirmativo /
 * negativo without a yo row, the non-finite forms as a list. The future
 * subjunctive is drawn dimmed (Wiktionary's "rare" footnote), not hidden.
 *
 * A form's tags are read in ONE place, [classify], and both the line and the
 * grid are drawn from what it returns, so they cannot disagree (the grid once
 * read `vos-form` itself and put the pages' `with-voseo` forms, eris and soi,
 * in ser's tú cell while the line called them vos). The classification is
 * total over a closed vocabulary: every tag the es pack's tagsets carry is
 * listed below in the one role it plays here (person, number, tense, mood,
 * polarity, register, voseo, non-finite, agreement, degree, clitic, or
 * explicitly ignored), and a form is classified only when all its tags are
 * listed and resolve without contradiction; anything else gets no label and
 * no cell, and the entry keeps its `[inflected]` marker. Nothing falls through
 * to a default: the pronoun is a lookup on (person, number, address) over the
 * eleven cells Spanish has, and an attached pronoun is read off the surface
 * among those the tags allow. [SpanishTagsetGoldenTest] runs every tagset of
 * the pack through [label] against a reviewed golden file, and the oracle
 * judges the grid by [classify], so a kaikki convention this object does not
 * handle shows up as an unlabelled tagset with a count, never as a plausible
 * wrong label or a misplaced cell.
 *
 * kaikki's conventions this encodes (the es-v4 extract, 2026-10), three for
 * the same facts: the plain table tags usted `third-person formal
 * second-person-semantically` (and lists it again without the register), the
 * clitic table tags it `second-person formal`, and a form-of page says
 * `third-person`; vos is `vos-form` in the plain table, `with-vos` in the
 * clitic table and `with-voseo` on a page; an attached se is `reflexive` on a
 * page and an untagged `object-third-person` in the clitic table (háblese
 * shares its tagset with háblele and háblelo), hence the surface. The
 * conditional carries `conditional` with `indicative`; the -se imperfect
 * subjunctive `imperfect-se` beside `imperfect`; a headline form (alisto) and
 * some pages name a tense with no mood, which is the indicative; negative
 * imperatives are tagged `negative` on the bare verb (kaikki drops the "no");
 * a pronominal lemma's finite forms are two words (`me quejo`); ir's table
 * bundles irse's rows, which the builder tags `pronominal` and this object
 * keeps out of ir's grid.
 */
object SpanishParadigm : InflectionParadigm {

    private const val VOS_FORM = "vos-form"
    private const val WITH_VOS = "with-vos"
    private const val WITH_VOSEO = "with-voseo"
    private const val WITH_TU = "with-tú"
    private const val IMPERFECT_SE = "imperfect-se"
    private const val SEMANTIC_SECOND = "second-person-semantically"
    private const val OBJECT_MASCULINE = "object-masculine"
    private const val OBJECT_FEMININE = "object-feminine"
    private const val TABLE_PREFIX = "table-"

    private val locale = Locale("es")

    // ── the vocabulary ──────────────────────────────────────────────────

    private val PERSONS = linkedMapOf(FIRST to 1, SECOND to 2, THIRD to 3)
    private val NUMBERS = mapOf(SINGULAR to false, PLURAL to true)
    private val MOODS = setOf(INDICATIVE, SUBJUNCTIVE, IMPERATIVE)
    /** Paradigm order, which is the order tenses are named in ("presente / pretérito"). */
    private val TENSE_NAMES = linkedMapOf(
        PRESENT to "presente", PRETERITE to "pretérito", IMPERFECT to "imperfecto", FUTURE to "futuro", CONDITIONAL to "condicional",
    )
    private val SUBJUNCTIVE_NAMES = mapOf(
        PRESENT to "presente de subjuntivo", IMPERFECT to "imperfecto de subjuntivo", FUTURE to "futuro de subjuntivo",
    )
    private val NON_FINITE_NAMES = mapOf(INFINITIVE to "infinitivo", GERUND to "gerundio", PARTICIPLE to "participio")
    private val FORMAL_TAGS = setOf(FORMAL, SEMANTIC_SECOND)
    private val INFORMAL_TAGS = setOf(INFORMAL, WITH_TU)
    private val VOSEO_TAGS = setOf(VOS_FORM, WITH_VOS, WITH_VOSEO)
    private val GENDER_NAMES = mapOf(FEMININE to "femenino", MASCULINE to "masculino", NEUTER to "neutro")
    private val DEGREE_NAMES = linkedMapOf(
        SUPERLATIVE to "superlativo", COMPARATIVE to "comparativo", DIMINUTIVE to "diminutivo", AUGMENTATIVE to "aumentativo",
    )
    /** The tags of an attached pronoun; stripped to label the verb form under it. */
    private val CLITIC_TAGS = setOf(
        COMBINED_FORM, OBJECT_FIRST, OBJECT_SECOND, OBJECT_THIRD, OBJECT_SINGULAR, OBJECT_PLURAL,
        OBJECT_MASCULINE, OBJECT_FEMININE, DATIVE, ACCUSATIVE, REFLEXIVE,
    )
    /** Tags that change nothing the label says: the builder's bookkeeping, the
     *  -se spelling of the imperfect subjunctive (the grid tells them apart,
     *  the line names both "imperfecto de subjuntivo"), a region, a usage note. */
    private val IGNORED = setOf(
        "form-of", InflectionForm.PRONOMINAL_TAG, IMPERFECT_SE,
        "archaic", "obsolete", "uncommon",
        "proximal", "medial", "distal",
        "Argentina", "El-Salvador", "Latin-America", "Spain", "Uruguay",
    )
    private val KNOWN: Set<String> = PERSONS.keys + NUMBERS.keys + MOODS + TENSE_NAMES.keys + NEGATIVE +
        FORMAL_TAGS + INFORMAL_TAGS + VOSEO_TAGS + NON_FINITE_NAMES.keys + PAST + GENDER_NAMES.keys +
        DEGREE_NAMES.keys + CLITIC_TAGS + IGNORED

    private enum class Address { PLAIN, FORMAL, VOSEO }

    /** The eleven pronoun cells, by (person, plural, address). Anything else
     *  (vos in the plural, a formal first person) is no cell and no label. */
    private val PRONOUNS: Map<Triple<Int, Boolean, Address>, String> = mapOf(
        Triple(1, false, Address.PLAIN) to "yo",
        Triple(1, true, Address.PLAIN) to "nosotros",
        Triple(2, false, Address.PLAIN) to "tú",
        Triple(2, false, Address.VOSEO) to "vos",
        Triple(2, false, Address.FORMAL) to "usted",
        Triple(2, true, Address.PLAIN) to "vosotros",
        Triple(2, true, Address.FORMAL) to "ustedes",
        Triple(3, false, Address.PLAIN) to "él, ella, usted",
        Triple(3, false, Address.FORMAL) to "usted",
        Triple(3, true, Address.PLAIN) to "ellos, ellas, ustedes",
        Triple(3, true, Address.FORMAL) to "ustedes",
    )
    private val PRONOUN_ORDER = listOf(
        "yo", "tú", "vos", "él, ella, usted", "usted", "nosotros", "vosotros", "ellos, ellas, ustedes", "ustedes",
    )
    private val CLITICS = setOf("me", "te", "se", "nos", "os")
    /** A word that is a bare pronoun, which a phrasal lemma may lead with (lo pasado, le puesto). */
    private val BARE_PRONOUNS = CLITICS + setOf("lo", "la", "los", "las", "le", "les")

    /** Finite cells a table must fill before it is worth a card. */
    private const val MIN_FINITE_CELLS = 6
    private const val MAX_LABEL_PARTS = 3

    // ── the classification ─────────────────────────────────────────────

    private enum class Kind { FINITE, NON_FINITE, NOMINAL }

    private data class Reading(val pronoun: String?, val name: String)

    /** One pack line as read here, the only reading of its tags: the line is
     *  [readings], the grid places it by the rest. */
    private data class Classified(
        val form: InflectionForm,
        val kind: Kind,
        /** The attached pronoun(s) as written (me, se, "me lo"); null when none. */
        val clitic: String?,
        /** Finite: the persons tagged, one for a table row, two when a form-of
         *  page merges them (alistara: 1 and 3). */
        val persons: List<Int>,
        val plural: Boolean?,
        val address: Address,
        /** Finite: INDICATIVE, SUBJUNCTIVE or IMPERATIVE; the indicative when kaikki left the mood unsaid. */
        val mood: String?,
        /** Finite: the tenses tagged, in paradigm order. */
        val tenses: List<String>,
        val negative: Boolean,
        val imperfectSe: Boolean,
        /** Non-finite: INFINITIVE, GERUND or PARTICIPLE. */
        val nonFinite: String?,
        /** Nominal, or a participle: the agreement words ("femenino plural"); null when none. */
        val agreement: String?,
        val readings: List<Reading>,
    )

    override fun isKnown(tag: String): Boolean = tag in KNOWN || tag.startsWith(TABLE_PREFIX)

    override fun classify(form: InflectionForm): FormClass? {
        val c = classified(form) ?: return null
        return FormClass(
            verbal = c.kind != Kind.NOMINAL,
            finite = c.kind == Kind.FINITE,
            clitic = c.clitic != null,
            voseo = c.address == Address.VOSEO,
            agreement = c.agreement,
            label = labelOf(c.readings) ?: return null,
        )
    }

    /** Null when a tag is outside the vocabulary, the tags contradict each
     *  other, or they say nothing worth a line (a noun's own masculine
     *  singular). */
    private fun classified(form: InflectionForm): Classified? {
        val tags = form.tags
        if (tags.any { !isKnown(it) }) return null
        if (tags.any { it in CLITIC_TAGS }) {
            // A clitic-attached form (háblame, dímelo, háblese): the verb form's
            // reading plus the pronoun(s) written onto it.
            val base = classified(InflectionForm(form.surface, tags - CLITIC_TAGS)) ?: return null
            val attached = attachedPronouns(tags, form.surface) ?: return null
            return base.copy(form = form, clitic = attached, readings = base.readings.map { Reading(it.pronoun, "${it.name} + $attached") })
        }
        val nonFinite = NON_FINITE_NAMES.keys.filter { it in tags }
        val tenses = TENSE_NAMES.keys.filter { it in tags }
        val moods = MOODS.filter { it in tags }
        val persons = PERSONS.entries.filter { it.key in tags }.map { it.value }
        val numbers = NUMBERS.entries.filter { it.key in tags }.map { it.value }
        val agreementTagged = tags.any { it in GENDER_NAMES || it in DEGREE_NAMES }
        val register = tags.any { it in FORMAL_TAGS || it in INFORMAL_TAGS || it in VOSEO_TAGS }
        return when {
            nonFinite.isNotEmpty() -> {
                // One non-finite form, with no tense, mood, polarity or register
                // beside it; `past` only qualifies the participle, which alone
                // agrees in gender and number. Person and number say nothing of
                // an infinitive or gerund (a form-of page may carry them).
                if (nonFinite.size > 1 || tenses.isNotEmpty() || moods.isNotEmpty() || NEGATIVE in tags || register) return null
                val kind = nonFinite.single()
                if (kind != PARTICIPLE && (PAST in tags || agreementTagged)) return null
                val agreement = if (kind == PARTICIPLE) agreement(tags, participle = true) else null
                val name = listOfNotNull(NON_FINITE_NAMES.getValue(kind), agreement).joinToString(" ")
                Classified(
                    form, Kind.NON_FINITE, clitic = null, persons = emptyList(), plural = null, address = Address.PLAIN,
                    mood = null, tenses = emptyList(), negative = false, imperfectSe = false, nonFinite = kind,
                    agreement = agreement, readings = listOf(Reading(null, name)),
                )
            }
            tenses.isNotEmpty() || moods.isNotEmpty() -> {
                if (moods.size > 1 || agreementTagged || PAST in tags) return null
                finite(form, moods.singleOrNull(), tenses, persons, numbers)
            }
            else -> {
                // A noun's or adjective's form. A person or a polarity on it is
                // a page this does not read ("a mis solas": first-person
                // singular); a register is a usage note there (simplicísimo is
                // the `formal` superlative, grx the `informal` gracias) and
                // changes nothing the label says.
                if (persons.isNotEmpty() || numbers.size > 1 || NEGATIVE in tags || PAST in tags) return null
                if (tags.any { it in VOSEO_TAGS }) return null
                val agreement = agreement(tags, participle = false) ?: return null
                Classified(
                    form, Kind.NOMINAL, clitic = null, persons = emptyList(), plural = numbers.singleOrNull(), address = Address.PLAIN,
                    mood = null, tenses = emptyList(), negative = false, imperfectSe = false, nonFinite = null,
                    agreement = agreement, readings = listOf(Reading(null, agreement)),
                )
            }
        }
    }

    private fun finite(form: InflectionForm, moodTag: String?, tenses: List<String>, persons: List<Int>, numbers: List<Boolean>): Classified? {
        val tags = form.tags
        // The indicative, or no mood at all: kaikki's headline forms (alisto:
        // first-person singular present) and some pages name the tense alone,
        // and the indicative is the mood that goes unsaid.
        val mood = moodTag ?: INDICATIVE
        val negative = NEGATIVE in tags
        val names: List<String> = when (mood) {
            IMPERATIVE -> {
                if (tenses.isNotEmpty()) return null
                listOf(if (negative) "imperativo negativo" else "imperativo")
            }
            SUBJUNCTIVE -> {
                if (negative) return null
                tenses.map { SUBJUNCTIVE_NAMES[it] ?: return null }
            }
            else -> {
                if (negative) return null
                tenses.map { TENSE_NAMES.getValue(it) }
            }
        }
        if (names.isEmpty() || persons.isEmpty() || numbers.size != 1) return null
        val plural = numbers.single()
        val formal = tags.any { it in FORMAL_TAGS }
        val informal = tags.any { it in INFORMAL_TAGS }
        val voseo = tags.any { it in VOSEO_TAGS }
        if (formal && (informal || voseo)) return null
        if (informal && persons.any { it != 2 }) return null
        val address = when {
            voseo -> Address.VOSEO
            formal -> Address.FORMAL
            // A third-person imperative addresses usted or ustedes; there is no
            // él imperative. kaikki lists usted's twice, once without `formal`.
            mood == IMPERATIVE && persons == listOf(3) -> Address.FORMAL
            else -> Address.PLAIN
        }
        val pronouns = persons.map { PRONOUNS[Triple(it, plural, address)] ?: return null }
        return Classified(
            form, Kind.FINITE, clitic = null, persons = persons, plural = plural, address = address,
            mood = mood, tenses = tenses, negative = negative, imperfectSe = IMPERFECT_SE in tags, nonFinite = null,
            agreement = null, readings = pronouns.flatMap { pronoun -> names.map { Reading(pronoun, it) } },
        )
    }

    // ── the line ────────────────────────────────────────────────────────

    override fun label(readings: List<InflectionForm>): String? =
        labelOf(readings.mapNotNull { classified(it) }.flatMap { it.readings })

    private fun labelOf(readings: List<Reading>): String? {
        val parts = readings.distinct()
        if (parts.isEmpty()) return null
        // One group per pronoun with its tenses joined ("nosotros · presente /
        // pretérito"), then pronouns whose tense lists are identical merge
        // ("yo / él, ella, usted · imperfecto"); groups are separated by "; "
        // (hable: "yo / él, ella, usted · presente de subjuntivo; usted ·
        // imperativo / imperativo negativo"). A reading without a pronoun
        // (gerundio, plural) is named alone.
        val namesByPronoun: Map<String?, List<String>> = parts.groupBy { it.pronoun }
            .mapValues { (_, group) -> group.map { it.name }.distinct() }
        val pronounsByNames = LinkedHashMap<List<String>, MutableList<String?>>()
        for ((pronoun, names) in namesByPronoun) pronounsByNames.getOrPut(names) { mutableListOf() }.add(pronoun)
        val groups = pronounsByNames.map { (names, pronouns) ->
            // Paradigm order, not the table's: kaikki lists some persons out of
            // sequence, and "yo / él, ella, usted" must read the same every time.
            val who = pronouns.filterNotNull().sortedBy { PRONOUN_ORDER.indexOf(it) }.joinToString(" / ")
            listOfNotNull(who.ifEmpty { null }, names.joinToString(" / ")).joinToString(" · ")
        }
        return groups.take(MAX_LABEL_PARTS).joinToString("; ")
    }

    /** The pronoun(s) written onto a clitic form, as Spanish writes them: me,
     *  te, nos, os, le / les, lo / la / los / las, se. The tags say which
     *  persons, and where they say so which number, case and gender; the
     *  surface says the rest, since kaikki gives háblele, háblelo and háblese
     *  one tagset. The pronoun hangs off the verb, the first word of a phrasal
     *  form that is not itself a bare pronoun (démonos a luz; lo pasado under
     *  pasarlo). Two pronouns put the indirect one first (dímelo:
     *  "me lo"). Null when the surface ends in none of the pronouns the tags
     *  allow, which is a form this cannot read. */
    private fun attachedPronouns(tags: Set<String>, surface: String): String? {
        val verb = verbWord(surface)
        val persons = listOf(OBJECT_FIRST to 1, OBJECT_SECOND to 2, OBJECT_THIRD to 3).filter { it.first in tags }.map { it.second }
        val reflexive = REFLEXIVE in tags
        if (persons.isEmpty() && !reflexive) return null
        if (OBJECT_SINGULAR in tags && OBJECT_PLURAL in tags) return null
        val numbers = when {
            OBJECT_PLURAL in tags -> listOf(true)
            OBJECT_SINGULAR in tags -> listOf(false)
            else -> listOf(false, true)
        }
        val case = when {
            ACCUSATIVE in tags && DATIVE !in tags -> ACCUSATIVE
            DATIVE in tags && ACCUSATIVE !in tags -> DATIVE
            else -> null
        }
        fun pronouns(person: Int, plurals: List<Boolean>, direct: Boolean?): List<String> = plurals.flatMap { plural ->
            when (person) {
                1 -> listOf(if (plural) "nos" else "me")
                2 -> listOf(if (plural) "os" else "te")
                else -> buildList {
                    if (direct != true) add(if (plural) "les" else "le")
                    if (direct != false) {
                        if (OBJECT_FEMININE !in tags) add(if (plural) "los" else "lo")
                        if (OBJECT_MASCULINE !in tags) add(if (plural) "las" else "la")
                    }
                    add("se")
                }
            }
        }
        fun ending(candidates: List<String>, end: Int = verb.length): String? =
            candidates.filter { it.length < end && verb.regionMatches(end - it.length, it, 0, it.length) }.maxByOrNull { it.length }
        return when {
            persons.isEmpty() -> ending(listOf("se"))
            persons.size == 1 -> ending(pronouns(persons[0], numbers, when (case) { ACCUSATIVE -> true; DATIVE -> false; else -> null }))
            persons.size == 2 && 3 in persons -> {
                // The direct object is the third person; the tags' number is
                // its number. The indirect pronoun before it is me, te, nos or os.
                val direct = ending(pronouns(3, numbers, direct = true).filter { it != "se" }) ?: return null
                val indirect = ending(pronouns(persons.first { it != 3 }, listOf(false, true), direct = false), verb.length - direct.length)
                    ?: return null
                "$indirect $direct"
            }
            else -> null
        }
    }

    /** Degree, gender and number words for a noun's or adjective's form, or
     *  for a participle's agreement (participle = true, where the masculine
     *  singular is the participle itself and says nothing). A bare masculine
     *  reading is a feminine lemma's counterpart (matrón under matrona) and
     *  reads "masculino". Null when the tags say nothing (a noun's own
     *  singular). */
    private fun agreement(tags: Set<String>, participle: Boolean): String? {
        val words = buildList {
            for ((tag, name) in DEGREE_NAMES) if (tag in tags) add(name)
            // An adjective with one plural for both genders (grandes) is tagged
            // feminine AND masculine: no gender word, just "plural".
            val bothGenders = FEMININE in tags && MASCULINE in tags
            when {
                bothGenders -> Unit
                FEMININE in tags -> add(GENDER_NAMES.getValue(FEMININE))
                MASCULINE in tags && (PLURAL in tags || !participle) -> add(GENDER_NAMES.getValue(MASCULINE))
                NEUTER in tags -> add(GENDER_NAMES.getValue(NEUTER))
            }
            if (PLURAL in tags) add("plural")
        }
        return words.joinToString(" ").ifEmpty { null }
    }

    // ── the table ───────────────────────────────────────────────────────

    private data class PersonRow(val label: String, val person: Int, val plural: Boolean)

    private val PERSON_ROWS = listOf(
        PersonRow("yo", 1, false),
        PersonRow("tú", 2, false),
        PersonRow("él, ella, usted", 3, false),
        PersonRow("nosotros", 1, true),
        PersonRow("vosotros", 2, true),
        PersonRow("ellos, ellas, ustedes", 3, true),
    )
    private val IMPERATIVE_ROWS = listOf(
        PersonRow("tú", 2, false),
        PersonRow("usted", 3, false),
        PersonRow("nosotros", 1, true),
        PersonRow("vosotros", 2, true),
        PersonRow("ustedes", 3, true),
    )

    /** A tense column: the mood and tense it holds, the imperfect's spelling
     *  and the imperative's polarity where they split a tense in two. */
    private class Column(
        val title: String,
        val mood: String,
        val tense: String? = null,
        val imperfectSe: Boolean? = null,
        val negative: Boolean? = null,
        val rare: Boolean = false,
    ) {
        fun holds(c: Classified): Boolean =
            c.mood == mood && (tense == null || tense in c.tenses) &&
                (imperfectSe == null || c.imperfectSe == imperfectSe) && (negative == null || c.negative == negative)
    }

    private val INDICATIVE_COLUMNS = listOf(
        Column("presente", INDICATIVE, PRESENT),
        Column("pretérito", INDICATIVE, PRETERITE),
        Column("imperfecto", INDICATIVE, IMPERFECT),
        Column("futuro", INDICATIVE, FUTURE),
        Column("condicional", INDICATIVE, CONDITIONAL),
    )
    private val SUBJUNCTIVE_COLUMNS = listOf(
        Column("presente", SUBJUNCTIVE, PRESENT),
        Column("imperfecto (-ra)", SUBJUNCTIVE, IMPERFECT, imperfectSe = false),
        Column("imperfecto (-se)", SUBJUNCTIVE, IMPERFECT, imperfectSe = true),
        Column("futuro", SUBJUNCTIVE, FUTURE, rare = true),
    )
    private val IMPERATIVE_COLUMNS = listOf(
        Column("afirmativo", IMPERATIVE, negative = false),
        Column("negativo", IMPERATIVE, negative = true),
    )

    override fun table(lemma: String, forms: List<InflectionForm>, highlight: String?): ConjugationTable? {
        val pronominal = lemma.endsWith("se")
        // The lemma's own tables only: a page's bundled pronominal variant
        // (irse under ir) carries the lemma's tags and is not shown; a second
        // table of one-word forms (acostar's regular conjugation) is, its
        // forms alternates in the same cells. Clitic-attached forms are
        // labelled but are not cells; a plain lemma drops clitic-led two-word
        // forms. A line the classifier does not read is no cell.
        val classified = forms.filterNot { it.fromPronominalVariant }.mapNotNull { classified(it) }
        if (classified.none { it.kind != Kind.NOMINAL }) return null
        val usable = classified.filter { it.clitic == null && (pronominal || !isCliticLed(it.form.surface)) }
        val target = highlight?.lowercase(locale)

        val indicative = grid("Indicativo", PERSON_ROWS, INDICATIVE_COLUMNS, usable, target)
        val subjunctive = grid("Subjuntivo", PERSON_ROWS, SUBJUNCTIVE_COLUMNS, usable, target, rareMarker = "raro")
        val imperative = grid("Imperativo", IMPERATIVE_ROWS, IMPERATIVE_COLUMNS, usable, target, negativePrefix = "no ")
        val finiteCells = listOf(indicative, subjunctive, imperative)
            .sumOf { s -> s.rows.sumOf { r -> r.cells.count { it.forms.isNotEmpty() } } }
        if (finiteCells < MIN_FINITE_CELLS) return null

        val sections = listOf(indicative, subjunctive, imperative, nonFinite(usable, target))
        val open = sections.indexOfFirst { s -> s.rows.any { r -> r.cells.any { it.highlighted } } }
        return ConjugationTable(sections, if (open < 0) 0 else open)
    }

    private fun grid(
        title: String,
        rows: List<PersonRow>,
        columns: List<Column>,
        forms: List<Classified>,
        target: String?,
        rareMarker: String? = null,
        negativePrefix: String? = null,
    ): ConjugationSection {
        val built = rows.map { row ->
            ConjugationRow(row.label, columns.map { col ->
                val slot = forms.filter { it.kind == Kind.FINITE && col.holds(it) && row.person in it.persons && it.plural == row.plural }
                // Whatever tag said vos (the table's vos-form, a page's
                // with-voseo) puts the form under tú as a companion, never
                // beside the tú form: ser's cell is eres, with sos and the
                // Chilean soi under it.
                val main = slot.filter { it.address != Address.VOSEO }.map { it.form.surface }.distinct()
                val vos = slot.filter { it.address == Address.VOSEO }.map { it.form.surface }.distinct()
                val negative = negativePrefix != null && slot.any { it.negative }
                val shown = if (negative) main.map { if (it.startsWith(negativePrefix!!)) it else negativePrefix + it } else main
                ConjugationCell(
                    forms = shown,
                    secondary = vos,
                    highlighted = target != null && (main + vos).any { hits(it, target) },
                )
            })
        }
        val rare = columns.withIndex().filter { it.value.rare }.map { it.index }.toSet()
        return ConjugationSection(title, columns.map { it.title }, built, rare, rareMarker.takeIf { rare.isNotEmpty() })
    }

    private fun nonFinite(forms: List<Classified>, target: String?): ConjugationSection {
        // Every bare form of the kind, alternates side by side (imprimir's
        // participles imprimido and impreso, freír's freído and frito: 25 verbs
        // in es-v5); the participle's masculine singular is the bare one, its
        // agreeing forms are labelled, not listed.
        fun bare(kind: String): List<String> =
            forms.filter { it.nonFinite == kind && it.agreement == null }.map { it.form.surface }.distinct()
        val rows = listOf("infinitivo" to bare(INFINITIVE), "gerundio" to bare(GERUND), "participio" to bare(PARTICIPLE))
            .filter { (_, surfaces) -> surfaces.isNotEmpty() }
            .map { (label, surfaces) ->
                ConjugationRow(label, listOf(ConjugationCell(surfaces, highlighted = target != null && surfaces.any { hits(it, target) })))
            }
        return ConjugationSection("No personales", emptyList(), rows, isList = true)
    }

    /** The verb of a surface: its first word that is not a bare pronoun. */
    private fun verbWord(surface: String): String = surface.split(' ').firstOrNull { it !in BARE_PRONOUNS } ?: surface

    private fun isCliticLed(surface: String): Boolean =
        ' ' in surface && surface.substringBefore(' ') in CLITICS

    /** The arrival surface matches a cell form directly, or the verb of a
     *  two-word pronominal form ("quejo" in "me quejo"). */
    private fun hits(form: String, target: String): Boolean =
        form == target || (' ' in form && form.substringAfterLast(' ') == target)
}
