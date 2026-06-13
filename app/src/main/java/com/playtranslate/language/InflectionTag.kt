package com.playtranslate.language

import androidx.annotation.StringRes
import com.playtranslate.R

/**
 * A grammatical form a conjugated Japanese surface expresses, derived from the
 * trailing auxiliary/particle chain (and the stem's own inflection form) by
 * [com.playtranslate.dictionary.JapaneseInflectionAnalyzer]. Rides on
 * [TokenSpan.inflections] and renders comma-joined under the dictionary form in
 * the word-result cell (e.g. 言わせて → 言う · Causative, Te-form).
 *
 * Each tag maps to a localized [labelRes] (pattern mirrors
 * [com.playtranslate.ui.AccentColor]). Deliberately atomic and ordered: the
 * analyzer emits them in morpheme order so stacks read naturally
 * (食べさせられた → Causative, Passive/Potential, Past).
 *
 * Scope notes (see the feature plan):
 *  - [PASSIVE] covers passive AND potential — れる/られる share a form and Sudachi
 *    assigns one lemma, so the sense isn't recoverable here.
 *  - No PROGRESSIVE tag: 〜ている's いる is a separate 動詞 the surface fold can't
 *    reach (documented gap), so it can't be detected from the glue chain.
 *  - [VOLITIONAL] is defined but NOT currently emitted — 〜う/よう is shared with
 *    conjecture (だろう) and likeness (ようだ); deferred until the Phase 0 survey
 *    disambiguates it. See [com.playtranslate.dictionary.JapaneseInflectionAnalyzer].
 */
enum class InflectionTag(@StringRes val labelRes: Int) {
    CAUSATIVE(R.string.inflection_causative),
    PASSIVE(R.string.inflection_passive),
    NEGATIVE(R.string.inflection_negative),
    PAST(R.string.inflection_past),
    POLITE(R.string.inflection_polite),
    TE_FORM(R.string.inflection_te_form),
    DESIDERATIVE(R.string.inflection_desiderative),
    CONDITIONAL(R.string.inflection_conditional),
    VOLITIONAL(R.string.inflection_volitional),
    IMPERATIVE(R.string.inflection_imperative),
}

/**
 * One distinct inflected occurrence of a lemma in the source: the [surface] as
 * found plus the [tags] it expresses. A single lemma can surface in several
 * forms within one passage (食べたい / 食べられない), so a word-result row carries a
 * list of these rather than collapsing to the first occurrence's form.
 */
data class InflectedForm(val surface: String, val tags: List<InflectionTag>)
