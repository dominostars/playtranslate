package com.playtranslate.language.inflection

import com.playtranslate.language.inflection.KaikkiTags.AUGMENTATIVE
import com.playtranslate.language.inflection.KaikkiTags.COMPARATIVE
import com.playtranslate.language.inflection.KaikkiTags.CONDITIONAL
import com.playtranslate.language.inflection.KaikkiTags.DIMINUTIVE
import com.playtranslate.language.inflection.KaikkiTags.FEMININE
import com.playtranslate.language.inflection.KaikkiTags.FORMAL
import com.playtranslate.language.inflection.KaikkiTags.FUTURE
import com.playtranslate.language.inflection.KaikkiTags.INFORMAL
import com.playtranslate.language.inflection.KaikkiTags.GERUND
import com.playtranslate.language.inflection.KaikkiTags.IMPERATIVE
import com.playtranslate.language.inflection.KaikkiTags.IMPERFECT
import com.playtranslate.language.inflection.KaikkiTags.INDICATIVE
import com.playtranslate.language.inflection.KaikkiTags.INFINITIVE
import com.playtranslate.language.inflection.KaikkiTags.MASCULINE
import com.playtranslate.language.inflection.KaikkiTags.NEUTER
import com.playtranslate.language.inflection.KaikkiTags.PARTICIPLE
import com.playtranslate.language.inflection.KaikkiTags.PLUPERFECT
import com.playtranslate.language.inflection.KaikkiTags.PRESENT
import com.playtranslate.language.inflection.KaikkiTags.PRETERITE
import com.playtranslate.language.inflection.KaikkiTags.SUBJUNCTIVE
import com.playtranslate.language.inflection.KaikkiTags.SUPERLATIVE
import java.util.Locale

/**
 * Portuguese: labels and the conjugation table over kaikki's Portuguese tags,
 * on [RomanceParadigm]. Names are the ones Portuguese grammars use (presente,
 * pretérito perfeito, pretérito mais-que-perfeito, futuro do subjuntivo);
 * pronouns are eu, tu, ele/ela/você, nós, vós, eles/elas/vocês. Sections:
 * Indicativo (six tenses, the conditional among them), Subjuntivo (presente,
 * imperfeito, futuro; the future subjunctive is everyday Portuguese, not
 * rare), Imperativo (afirmativo / negativo), Infinitivo pessoal (the
 * inflected infinitive, one column), and the nominal forms as a list.
 *
 * kaikki's Portuguese conventions this encodes (the pt extract, 2026-10): the
 * conditional carries no mood; the personal infinitive is `infinitive` with a
 * person and number (falares) and the impersonal one `impersonal infinitive`;
 * negative imperatives are complete surfaces ("não fales", the "não" in the
 * pack, so the grid writes nothing); a third-person imperative addresses você;
 * `Brazil` and `Portugal` mark spelling variants listed side by side (falámos
 * and falamos), `short-form` and `long-form` the two participles of verbs
 * that have both (aceito, aceitado); there are no clitic rows in the tables.
 * The usage tags the rows carry (`archaic` and `proscribed` on a defective
 * verb's negative imperative, `uncommon`, `poetic` and `humorous` on nouns'
 * feminines and plurals) are shown as arcaico, proscrito, pouco comum,
 * poético and humorístico; `informal` on a noun's feminine (chefa) is its
 * register.
 */
object PortugueseParadigm : RomanceParadigm() {

    override val locale: Locale = Locale("pt")

    override val indicativeTenses = linkedMapOf(
        setOf(PRESENT) to "presente", setOf(PRETERITE) to "pretérito perfeito", setOf(IMPERFECT) to "pretérito imperfeito",
        setOf(PLUPERFECT) to "pretérito mais-que-perfeito", setOf(FUTURE) to "futuro", setOf(CONDITIONAL) to "condicional",
    )
    override val subjunctiveTenses = linkedMapOf(
        setOf(PRESENT) to "presente do subjuntivo", setOf(IMPERFECT) to "imperfeito do subjuntivo", setOf(FUTURE) to "futuro do subjuntivo",
    )
    override val imperativeName = "imperativo"
    override val negativeImperativeName = "imperativo negativo"
    override val personalInfinitiveName = "infinitivo pessoal"
    override val nonFiniteForms = linkedMapOf(setOf(INFINITIVE) to "infinitivo", setOf(GERUND) to "gerúndio", setOf(PARTICIPLE) to "particípio")

    override val pronouns: Map<Triple<Int, Boolean, Address>, String> = mapOf(
        Triple(1, false, Address.PLAIN) to "eu",
        Triple(1, true, Address.PLAIN) to "nós",
        Triple(2, false, Address.PLAIN) to "tu",
        Triple(2, true, Address.PLAIN) to "vós",
        Triple(3, false, Address.PLAIN) to "ele, ela, você",
        Triple(3, false, Address.FORMAL) to "você",
        Triple(3, true, Address.PLAIN) to "eles, elas, vocês",
        Triple(3, true, Address.FORMAL) to "vocês",
    )
    override val pronounOrder = listOf("eu", "tu", "ele, ela, você", "você", "nós", "vós", "eles, elas, vocês", "vocês")
    override val genderNames = mapOf(FEMININE to "feminino", MASCULINE to "masculino", NEUTER to "neutro")
    override val degreeNames = linkedMapOf(SUPERLATIVE to "superlativo", COMPARATIVE to "comparativo", DIMINUTIVE to "diminutivo", AUGMENTATIVE to "aumentativo")
    override val pluralWord = "plural"
    /** `personal` is what a page calls the personal infinitive, which the person tags already say. */
    override val usageNames = linkedMapOf(
        "archaic" to "arcaico", "uncommon" to "pouco comum", "poetic" to "poético", "humorous" to "humorístico", "proscribed" to "proscrito",
    )
    override val registerNames = mapOf(INFORMAL to "informal", FORMAL to "formal")
    /** `sometimes` is the connective of "sometimes proscribed". */
    override val ignored = setOf(
        "impersonal", "personal", "Brazil", "Portugal", "short-form", "long-form", "positive", "sometimes",
    )

    private val PERSON_ROWS = listOf(
        PersonRow("eu", 1, false), PersonRow("tu", 2, false), PersonRow("ele, ela, você", 3, false),
        PersonRow("nós", 1, true), PersonRow("vós", 2, true), PersonRow("eles, elas, vocês", 3, true),
    )
    private val IMPERATIVE_ROWS = listOf(
        PersonRow("tu", 2, false), PersonRow("você", 3, false), PersonRow("nós", 1, true),
        PersonRow("vós", 2, true), PersonRow("vocês", 3, true),
    )
    override val gridSections = listOf(
        GridSection("Indicativo", PERSON_ROWS, listOf(
            Column("presente", INDICATIVE, setOf(PRESENT)), Column("pretérito perfeito", INDICATIVE, setOf(PRETERITE)),
            Column("imperfeito", INDICATIVE, setOf(IMPERFECT)), Column("mais-que-perfeito", INDICATIVE, setOf(PLUPERFECT)),
            Column("futuro", INDICATIVE, setOf(FUTURE)), Column("condicional", INDICATIVE, setOf(CONDITIONAL)),
        )),
        GridSection("Subjuntivo", PERSON_ROWS, listOf(
            Column("presente", SUBJUNCTIVE, setOf(PRESENT)), Column("imperfeito", SUBJUNCTIVE, setOf(IMPERFECT)),
            Column("futuro", SUBJUNCTIVE, setOf(FUTURE)),
        )),
        GridSection("Imperativo", IMPERATIVE_ROWS, listOf(
            Column("afirmativo", IMPERATIVE, negative = false), Column("negativo", IMPERATIVE, negative = true),
        )),
        GridSection("Infinitivo pessoal", PERSON_ROWS, listOf(Column("infinitivo pessoal", INFINITIVE))),
    )
    override val nonFiniteTitle = "Formas nominais"
    override fun isPronominalLemma(lemma: String) = lemma.endsWith("-se")
}
