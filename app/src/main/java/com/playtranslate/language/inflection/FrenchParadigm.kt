package com.playtranslate.language.inflection

import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.CONDITIONAL
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FORMAL
import com.playtranslate.language.inflection.KaikkiTags.FUTURE
import com.playtranslate.language.inflection.KaikkiTags.INFORMAL
import com.playtranslate.language.inflection.KaikkiTags.GERUND
import com.playtranslate.language.inflection.KaikkiTags.HISTORIC
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.IMPERFECT
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PAST
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import java.util.Locale

/**
 * French: labels and the conjugation table over kaikki's French tags, on
 * [RomanceParadigm]. Names are the ones French grammars use (présent,
 * imparfait, passé simple, futur simple, conditionnel, subjonctif présent);
 * pronouns are je, tu, il/elle/on, nous, vous, ils/elles. Sections: Indicatif
 * (five tenses, the conditional among them), Subjonctif (présent, imparfait),
 * Impératif (tu, nous, vous), and the non-conjugated forms as a list.
 *
 * kaikki's French conventions this encodes (the fr extract, 2026-10): the
 * passé simple is `historic` with `past`; the conditional carries no mood;
 * the present participle is `gerund participle present` and the past
 * participle `participle past`, with no agreeing rows in the table (the pages
 * carry them: parlée); the compound tenses are `multiword-construction`
 * placeholders the builder drops; a reflexive verb's own rows carry
 * `reflexive` and a pronoun before the verb (m'absente, nous absentions), the
 * lemma's own forms and so cells, while another verb's bundled reflexive
 * table (se lever under lever) is tagged `pronominal` by the builder and
 * stays out; there is no negative imperative and no clitic row. The rows
 * carry two usage tags, `uncommon` on a plural (oufs) and `derogatory` on a
 * feminine (beurette), shown as rare and péjoratif; `informal` on a noun's
 * form is its register, familier.
 */
object FrenchParadigm : RomanceParadigm() {

    override val locale: Locale = Locale("fr")

    override val indicativeTenses = linkedMapOf(
        setOf(PRESENT) to "présent", setOf(IMPERFECT) to "imparfait", setOf(PAST, HISTORIC) to "passé simple",
        setOf(FUTURE) to "futur simple", setOf(CONDITIONAL) to "conditionnel",
    )
    override val subjunctiveTenses = linkedMapOf(setOf(PRESENT) to "subjonctif présent", setOf(IMPERFECT) to "subjonctif imparfait")
    override val imperativeName = "impératif"
    override val imperativeTenseTags = setOf(PRESENT)
    /** The table tags the present participle `gerund participle present` and
     *  the past participle `participle past`; a page says `participle present`
     *  or, for an agreeing past participle (aimées), `participle` alone. */
    override val nonFiniteForms = linkedMapOf(
        setOf(INFINITIVE) to "infinitif",
        setOf(GERUND, PARTICIPLE, PRESENT) to "participe présent", setOf(PARTICIPLE, PRESENT) to "participe présent",
        setOf(PARTICIPLE, PAST) to "participe passé", setOf(PARTICIPLE) to "participe passé",
    )

    override val pronouns: Map<Triple<Int, Boolean, Address>, String> = mapOf(
        Triple(1, false, Address.PLAIN) to "je",
        Triple(1, true, Address.PLAIN) to "nous",
        Triple(2, false, Address.PLAIN) to "tu",
        Triple(2, true, Address.PLAIN) to "vous",
        Triple(3, false, Address.PLAIN) to "il, elle, on",
        Triple(3, true, Address.PLAIN) to "ils, elles",
    )
    override val pronounOrder = listOf("je", "tu", "il, elle, on", "nous", "vous", "ils, elles")
    override val genderNames = mapOf(FEMININE to "féminin", MASCULINE to "masculin")
    override val degreeNames = linkedMapOf(SUPERLATIVE to "superlatif", COMPARATIVE to "comparatif", DIMINUTIVE to "diminutif")
    override val pluralWord = "pluriel"
    override val usageNames = linkedMapOf("uncommon" to "rare", "derogatory" to "péjoratif")
    override val registerNames = mapOf(INFORMAL to "familier", FORMAL to "soutenu")
    override val ignored = setOf(
        "reflexive", "before-vowel", "traditional", "suppletive",
        "Canada", "Louisiana", "Brazilian", "especially", "also",
    )

    private val PERSON_ROWS = listOf(
        PersonRow("je", 1, false), PersonRow("tu", 2, false), PersonRow("il, elle, on", 3, false),
        PersonRow("nous", 1, true), PersonRow("vous", 2, true), PersonRow("ils, elles", 3, true),
    )
    private val IMPERATIVE_ROWS = listOf(PersonRow("tu", 2, false), PersonRow("nous", 1, true), PersonRow("vous", 2, true))
    override val gridSections = listOf(
        GridSection("Indicatif", PERSON_ROWS, listOf(
            Column("présent", INDICATIVE, setOf(PRESENT)), Column("imparfait", INDICATIVE, setOf(IMPERFECT)),
            Column("passé simple", INDICATIVE, setOf(PAST, HISTORIC)), Column("futur simple", INDICATIVE, setOf(FUTURE)),
            Column("conditionnel", INDICATIVE, setOf(CONDITIONAL)),
        )),
        GridSection("Subjonctif", PERSON_ROWS, listOf(
            Column("présent", SUBJUNCTIVE, setOf(PRESENT)), Column("imparfait", SUBJUNCTIVE, setOf(IMPERFECT)),
        )),
        GridSection("Impératif", IMPERATIVE_ROWS, listOf(Column("présent", IMPERATIVE))),
    )
    override val nonFiniteTitle = "Formes non conjuguées"
}
