package com.playtranslate.ui

import android.content.Context
import com.playtranslate.R
import com.playtranslate.model.MiscVocabulary
import com.playtranslate.model.MiscVocabulary.MiscCode

/**
 * Render-side authority for misc register tags. [renderMisc] is filter +
 * localizer in one: a token is **localized** if it's a known register/dialect
 * code, **passed through raw** if it's an allowlisted domain or gazetteer
 * region, and **dropped** otherwise. Cleanliness therefore does NOT depend on a
 * pack being rebuilt — an un-filtered pack (grammatical tags, kaikki
 * categories, freeform `s_inf` sentences) still renders clean.
 *
 * `Proverb` / `Abbreviation` are owned by [com.playtranslate.model.PosVocabulary]
 * and are not [MiscCode]s, so they fall through to "drop" here and only ever
 * surface as a part-of-speech (no double-render).
 *
 * For Anki export do NOT use this — call [MiscVocabulary.englishLabel] so cards
 * stay English/portable (mirrors the `localizePos` rule).
 *
 * The exhaustive `when` in [stringRes] is a compile-time tripwire: adding a
 * [MiscCode] without a string resource won't build.
 */
fun Context.renderMisc(tokens: List<String>): List<String> {
    if (tokens.isEmpty()) return emptyList()
    return tokens.mapNotNull { token ->
        MiscVocabulary.canonical(token)?.let { getString(it.stringRes()) }
            ?: if (MiscVocabulary.isPassthrough(token)) token else null
    }.distinct()
}

/** Convenience for the existing " · "-joined render sites. Null when nothing
 *  survives the filter, so callers can keep their `?.let { addView(...) }`. */
fun Context.renderMiscText(tokens: List<String>): String? =
    renderMisc(tokens).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private fun MiscCode.stringRes(): Int = when (this) {
    MiscCode.KANA_ONLY -> R.string.misc_kana_only
    MiscCode.KANJI_ONLY -> R.string.misc_kanji_only
    MiscCode.COLLOQUIAL -> R.string.misc_colloquial
    MiscCode.INFORMAL -> R.string.misc_informal
    MiscCode.FORMAL -> R.string.misc_formal
    MiscCode.LITERARY -> R.string.misc_literary
    MiscCode.HONORIFIC -> R.string.misc_honorific
    MiscCode.HUMBLE -> R.string.misc_humble
    MiscCode.POLITE -> R.string.misc_polite
    MiscCode.FAMILIAR -> R.string.misc_familiar
    MiscCode.ENDEARING -> R.string.misc_endearing
    MiscCode.CHILDRENS -> R.string.misc_childrens
    MiscCode.FEMALE_SPEECH -> R.string.misc_female_speech
    MiscCode.MALE_SPEECH -> R.string.misc_male_speech
    MiscCode.ARCHAIC -> R.string.misc_archaic
    MiscCode.OBSOLETE -> R.string.misc_obsolete
    MiscCode.DATED -> R.string.misc_dated
    MiscCode.HISTORICAL -> R.string.misc_historical
    MiscCode.RARE -> R.string.misc_rare
    MiscCode.NEOLOGISM -> R.string.misc_neologism
    MiscCode.SLANG -> R.string.misc_slang
    MiscCode.INTERNET_SLANG -> R.string.misc_internet_slang
    MiscCode.MANGA_SLANG -> R.string.misc_manga_slang
    MiscCode.IDIOMATIC -> R.string.misc_idiomatic
    MiscCode.FIGURATIVE -> R.string.misc_figurative
    MiscCode.ONOMATOPOEIA -> R.string.misc_onomatopoeia
    MiscCode.POETIC -> R.string.misc_poetic
    MiscCode.HUMOROUS -> R.string.misc_humorous
    MiscCode.DEROGATORY -> R.string.misc_derogatory
    MiscCode.OFFENSIVE -> R.string.misc_offensive
    MiscCode.VULGAR -> R.string.misc_vulgar
    MiscCode.SLUR -> R.string.misc_slur
    MiscCode.SENSITIVE -> R.string.misc_sensitive
    MiscCode.EUPHEMISTIC -> R.string.misc_euphemistic
    MiscCode.SARCASTIC -> R.string.misc_sarcastic
    MiscCode.NONSTANDARD -> R.string.misc_nonstandard
    MiscCode.YOJIJUKUGO -> R.string.misc_yojijukugo
    MiscCode.DIALECTAL -> R.string.misc_dialectal
}
