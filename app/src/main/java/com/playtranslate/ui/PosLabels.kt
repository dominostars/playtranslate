package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.PosVocabulary.PosCode

/**
 * Localizes part-of-speech tokens for **display only**. Each known universal
 * POS (resolved via [PosVocabulary.canonical]) maps to its localized
 * [R.string] label; any token outside the universal set — Japanese
 * conjugation classes, archaic forms, imported-dictionary headers — falls
 * back to the raw English token.
 *
 * Never use this for Anki card content or any exported field: cards stay
 * English (portable). The exhaustive `when` in [stringRes] is a compile-time
 * tripwire — adding a [PosCode] without a resource won't build.
 */
fun Context.localizePos(token: String): String =
    PosVocabulary.canonical(token)?.let { getString(it.stringRes()) } ?: token

/** Localize each token and join with the standard " · " separator. */
fun Context.localizePos(tokens: List<String>): String =
    tokens.joinToString(" · ") { localizePos(it) }

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
}
