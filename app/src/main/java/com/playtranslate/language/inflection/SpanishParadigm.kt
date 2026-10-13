package com.playtranslate.language.inflection

import com.playtranslate.language.inflection.KaikkiTags.ACCUSATIVE
import com.playtranslate.language.inflection.KaikkiTags.AUGMENTATIVE
import com.playtranslate.language.inflection.KaikkiTags.COMBINED_FORM
import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.CONDITIONAL
import com.playtranslate.language.inflection.KaikkiTags.DATIVE
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FORMAL
import com.playtranslate.language.inflection.KaikkiTags.FUTURE
import com.playtranslate.language.inflection.KaikkiTags.GERUND
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.IMPERFECT
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.INFORMAL
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_FIRST
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_PLURAL
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_SECOND
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_SINGULAR
import com.playtranslate.language.inflection.KaikkiTags.OBJECT_THIRD
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.PRETERITE
import com.playtranslate.language.inflection.KaikkiTags.REFLEXIVE
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import java.util.Locale

/**
 * Spanish: labels and the conjugation table over kaikki's Spanish tags, on
 * [RomanceParadigm]. Tense and mood names are the ones every Spanish reference
 * and classroom uses (presente, pretérito, imperfecto de subjuntivo), pronouns
 * are the standard paradigm rows with vos as a companion form under tú, and
 * the table follows SpanishDict's arrangement: pronouns down, tenses across,
 * one mood per section, both imperfect subjunctives, the imperative as
 * afirmativo / negativo without a yo row, the non-finite forms as a list. The
 * future subjunctive is drawn dimmed (Wiktionary's "rare" footnote), not
 * hidden.
 *
 * kaikki's Spanish conventions this encodes (the es-v4 extract, 2026-10),
 * three for the same facts: the plain table tags usted `third-person formal
 * second-person-semantically` (and lists it again without the register), the
 * clitic table tags it `second-person formal`, and a form-of page says
 * `third-person`; vos is `vos-form` in the plain table, `with-vos` in the
 * clitic table and `with-voseo` on a page; an attached se is `reflexive` on a
 * page and an untagged `object-third-person` in the clitic table (háblese
 * shares its tagset with háblele and háblelo), hence the surface. The -se
 * imperfect subjunctive carries `imperfect-se` beside `imperfect`; negative
 * imperatives are tagged `negative` on the bare verb (kaikki drops the "no",
 * the grid writes it); a pronominal lemma's finite forms are two words
 * (`me quejo`). The usage tags the rows carry are `archaic` and `obsolete` on
 * a few clitic imperatives (pléganos) and `uncommon` on a phrase's plural,
 * shown as arcaico, obsoleto and poco común; `informal` and `formal` on a
 * page's noun or adjective (tetaza, simplicísimo) are its register.
 */
object SpanishParadigm : RomanceParadigm() {

    private const val VOS_FORM = "vos-form"
    private const val WITH_VOS = "with-vos"
    private const val WITH_VOSEO = "with-voseo"
    private const val WITH_TU = "with-tú"
    private const val IMPERFECT_SE = "imperfect-se"
    private const val OBJECT_MASCULINE = "object-masculine"
    private const val OBJECT_FEMININE = "object-feminine"

    override val locale: Locale = Locale("es")

    override val indicativeTenses = linkedMapOf(
        setOf(PRESENT) to "presente", setOf(PRETERITE) to "pretérito", setOf(IMPERFECT) to "imperfecto",
        setOf(FUTURE) to "futuro", setOf(CONDITIONAL) to "condicional",
    )
    override val subjunctiveTenses = linkedMapOf(
        setOf(PRESENT) to "presente de subjuntivo", setOf(IMPERFECT) to "imperfecto de subjuntivo", setOf(FUTURE) to "futuro de subjuntivo",
    )
    override val imperativeName = "imperativo"
    override val negativeImperativeName = "imperativo negativo"
    override val nonFiniteForms = linkedMapOf(setOf(INFINITIVE) to "infinitivo", setOf(GERUND) to "gerundio", setOf(PARTICIPLE) to "participio")

    /** The eleven pronoun cells by (person, plural, address). */
    override val pronouns: Map<Triple<Int, Boolean, Address>, String> = mapOf(
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
    override val pronounOrder = listOf("yo", "tú", "vos", "él, ella, usted", "usted", "nosotros", "vosotros", "ellos, ellas, ustedes", "ustedes")
    override val genderNames = mapOf(FEMININE to "femenino", MASCULINE to "masculino", NEUTER to "neutro")
    override val degreeNames = linkedMapOf(SUPERLATIVE to "superlativo", COMPARATIVE to "comparativo", DIMINUTIVE to "diminutivo", AUGMENTATIVE to "aumentativo")
    override val pluralWord = "plural"
    override val informalTags = setOf(INFORMAL, WITH_TU)
    override val voseoTags = setOf(VOS_FORM, WITH_VOS, WITH_VOSEO)
    override val cliticTags = setOf(
        COMBINED_FORM, OBJECT_FIRST, OBJECT_SECOND, OBJECT_THIRD, OBJECT_SINGULAR, OBJECT_PLURAL,
        OBJECT_MASCULINE, OBJECT_FEMININE, DATIVE, ACCUSATIVE, REFLEXIVE,
    )
    override val qualifiers = setOf(IMPERFECT_SE)
    override val usageNames = linkedMapOf("archaic" to "arcaico", "obsolete" to "obsoleto", "uncommon" to "poco común")
    override val registerNames = mapOf(INFORMAL to "informal", FORMAL to "formal")
    override val ignored = setOf(
        "proximal", "medial", "distal",
        "Argentina", "El-Salvador", "Latin-America", "Spain", "Uruguay",
    )

    private val PERSON_ROWS = listOf(
        PersonRow("yo", 1, false), PersonRow("tú", 2, false), PersonRow("él, ella, usted", 3, false),
        PersonRow("nosotros", 1, true), PersonRow("vosotros", 2, true), PersonRow("ellos, ellas, ustedes", 3, true),
    )
    private val IMPERATIVE_ROWS = listOf(
        PersonRow("tú", 2, false), PersonRow("usted", 3, false), PersonRow("nosotros", 1, true),
        PersonRow("vosotros", 2, true), PersonRow("ustedes", 3, true),
    )
    override val gridSections = listOf(
        GridSection("Indicativo", PERSON_ROWS, listOf(
            Column("presente", INDICATIVE, setOf(PRESENT)), Column("pretérito", INDICATIVE, setOf(PRETERITE)),
            Column("imperfecto", INDICATIVE, setOf(IMPERFECT)), Column("futuro", INDICATIVE, setOf(FUTURE)),
            Column("condicional", INDICATIVE, setOf(CONDITIONAL)),
        )),
        GridSection("Subjuntivo", PERSON_ROWS, listOf(
            Column("presente", SUBJUNCTIVE, setOf(PRESENT)),
            Column("imperfecto (-ra)", SUBJUNCTIVE, setOf(IMPERFECT), qualifier = IMPERFECT_SE to false),
            Column("imperfecto (-se)", SUBJUNCTIVE, setOf(IMPERFECT), qualifier = IMPERFECT_SE to true),
            Column("futuro", SUBJUNCTIVE, setOf(FUTURE), rare = true),
        ), rareMarker = "raro"),
        GridSection("Imperativo", IMPERATIVE_ROWS, listOf(
            Column("afirmativo", IMPERATIVE, negative = false), Column("negativo", IMPERATIVE, negative = true),
        )),
    )
    override val nonFiniteTitle = "No personales"
    override val negativePrefix = "no "
    override val cliticLedIsVariant = true
    override val leadingClitics = setOf("me", "te", "se", "nos", "os")
    override fun isPronominalLemma(lemma: String) = lemma.endsWith("se")

    /** A word that is a bare pronoun, which a phrasal lemma may lead with (lo pasado, le puesto). */
    private val BARE_PRONOUNS = leadingClitics + setOf("lo", "la", "los", "las", "le", "les")

    /** The pronoun(s) written onto a clitic form, as Spanish writes them: me,
     *  te, nos, os, le / les, lo / la / los / las, se. The tags say which
     *  persons, and where they say so which number, case and gender; the
     *  surface says the rest, since kaikki gives háblele, háblelo and háblese
     *  one tagset. The pronoun hangs off the verb, the first word of a phrasal
     *  form that is not itself a bare pronoun (démonos a luz; lo pasado under
     *  pasarlo). Two pronouns put the indirect one first (dímelo: "me lo").
     *  Null when the surface ends in none of the pronouns the tags allow, which
     *  is a form this cannot read. */
    override fun attachedPronouns(tags: Set<String>, surface: String): String? {
        val verb = surface.split(' ').firstOrNull { it !in BARE_PRONOUNS } ?: surface
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
}
