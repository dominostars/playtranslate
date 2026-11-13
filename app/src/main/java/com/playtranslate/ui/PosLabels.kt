package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.PosVocabulary.PosCode

/**
 * The label of a part-of-speech token, in the app's language: the one place
 * a stored token becomes text a user reads, on screen and on an Anki card
 * alike. A token [PosVocabulary.canonical] knows shows its code's
 * [R.string] label, which is also what turns a description a pack stored
 * whole ("noun or participle which takes the aux. verb suru") into a short
 * one ("Suru verb"). Any other token (a Wiktionary category with no code,
 * an imported dictionary's header) shows as stored.
 *
 * The exhaustive `when` in [stringRes] is a compile-time tripwire: adding a
 * [PosCode] without a resource won't build.
 */
fun Context.localizePos(token: String): String =
    PosVocabulary.canonical(token)?.let { getString(it.stringRes()) } ?: token

/** Each token's label, joined with the standard " · " separator. A label is
 *  shown once: several tokens can share one (a sense tagged both "suffix"
 *  and "noun, used as a suffix"). */
fun Context.localizePos(tokens: List<String>): String =
    tokens.map { localizePos(it) }.distinct().joinToString(" · ")

/** The part-of-speech line of [entry]'s first sense: what a word card's
 *  PartOfSpeech field carries. */
fun Context.firstSensePosLabel(entry: DictionaryEntry): String =
    localizePos(entry.senses.firstOrNull()?.partsOfSpeech.orEmpty().filter { it.isNotBlank() })

private fun PosCode.stringRes(): Int = when (this) {
    PosCode.NOUN -> R.string.pos_noun
    PosCode.PRONOUN -> R.string.pos_pronoun
    PosCode.VERB -> R.string.pos_verb
    PosCode.ADJECTIVE -> R.string.pos_adjective
    PosCode.ADVERB -> R.string.pos_adverb
    PosCode.PARTICLE -> R.string.pos_particle
    PosCode.CONJUNCTION -> R.string.pos_conjunction
    PosCode.INTERJECTION -> R.string.pos_interjection
    PosCode.PREPOSITION -> R.string.pos_preposition
    PosCode.POSTPOSITION -> R.string.pos_postposition
    PosCode.DETERMINER -> R.string.pos_determiner
    PosCode.ARTICLE -> R.string.pos_article
    PosCode.PREFIX -> R.string.pos_prefix
    PosCode.SUFFIX -> R.string.pos_suffix
    PosCode.COUNTER -> R.string.pos_counter
    PosCode.NUMERAL -> R.string.pos_numeral
    PosCode.EXPRESSION -> R.string.pos_expression
    PosCode.PHRASE -> R.string.pos_phrase
    PosCode.PROVERB -> R.string.pos_proverb
    PosCode.ABBREVIATION -> R.string.pos_abbreviation
    PosCode.CONTRACTION -> R.string.pos_contraction
    PosCode.AUXILIARY -> R.string.pos_auxiliary
    PosCode.TRANSITIVE -> R.string.pos_transitive
    PosCode.INTRANSITIVE -> R.string.pos_intransitive
    PosCode.COPULA -> R.string.pos_copula
    PosCode.UNCLASSIFIED -> R.string.pos_unclassified
    PosCode.SURU_VERB -> R.string.pos_suru_verb
    PosCode.SU_VERB -> R.string.pos_su_verb
    PosCode.KURU_VERB -> R.string.pos_kuru_verb
    PosCode.ZURU_VERB -> R.string.pos_zuru_verb
    PosCode.ICHIDAN_VERB -> R.string.pos_ichidan_verb
    PosCode.ICHIDAN_VERB_SPECIAL -> R.string.pos_ichidan_verb_special
    PosCode.GODAN_VERB -> R.string.pos_godan_verb
    PosCode.GODAN_VERB_SPECIAL -> R.string.pos_godan_verb_special
    PosCode.GODAN_VERB_IRREGULAR -> R.string.pos_godan_verb_irregular
    PosCode.NIDAN_VERB -> R.string.pos_nidan_verb
    PosCode.YODAN_VERB -> R.string.pos_yodan_verb
    PosCode.NU_VERB -> R.string.pos_nu_verb
    PosCode.RI_VERB -> R.string.pos_ri_verb
    PosCode.I_ADJECTIVE -> R.string.pos_i_adjective
    PosCode.NA_ADJECTIVE -> R.string.pos_na_adjective
    PosCode.NO_ADJECTIVE -> R.string.pos_no_adjective
    PosCode.TARU_ADJECTIVE -> R.string.pos_taru_adjective
    PosCode.KU_ADJECTIVE -> R.string.pos_ku_adjective
    PosCode.SHIKU_ADJECTIVE -> R.string.pos_shiku_adjective
    PosCode.NARI_ADJECTIVE -> R.string.pos_nari_adjective
    PosCode.PRENOMINAL -> R.string.pos_prenominal
    PosCode.TO_ADVERB -> R.string.pos_to_adverb
}
