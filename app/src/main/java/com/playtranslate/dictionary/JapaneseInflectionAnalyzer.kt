package com.playtranslate.dictionary

import com.playtranslate.language.InflectionTag

/**
 * Derives the [InflectionTag]s a conjugated Japanese surface expresses from its
 * morpheme decomposition: the content [stem] plus the ordered [glue] chain that
 * [DictionaryManager.Companion.reglobTokens] folds into the surface span. Pure
 * and Sudachi-independent — unit-tested with hand-authored [JaToken] chains.
 *
 * The label is a function of the chain, not a single field: causative lives in
 * the せる auxiliary, te-form in the て particle, past in た. Two sources combine:
 *  1. trailing auxiliary/conjunctive-particle DICTIONARY FORMS, via [AUX_TAGS] —
 *     a tighter allow-list than [JaCategory.isConjugationGlue], so the
 *     non-conjugational particles (は/を/が) that the fold also pulls into the
 *     surface span are ignored for labeling.
 *  2. the FINAL morpheme's inflectionForm (活用形) for the imperative (命令形),
 *     the one stem-internal form carried by no auxiliary.
 *
 * Tags are emitted in morpheme order and de-duplicated, so ませんでした's doubled
 * politeness (ます + でし→です) collapses to a single [InflectionTag.POLITE]
 * while the negation and past survive: [POLITE, NEGATIVE, PAST].
 *
 * Volitional is deliberately NOT emitted yet: 〜う/よう is one auxiliary shared by
 * volitional (食べよう), conjecture (〜だろう/でしょう) and likeness (〜ようだ/ように), so
 * it can't be labeled safely from the lemma or 意志推量形 alone — deferred until the
 * Phase 0 survey pins the disambiguating segmentation, then re-enabled with
 * negative tests for ように/ようだ/だろう.
 *
 * TODO(phase0): a few lemma spellings / segmentations below are taken from UniDic
 * convention and the reglob test corpus; reconcile against the
 * JapaneseInflectionSurveyTest dump (suru-verb causative split, exact ぬ/ず
 * negative lemma, て/で after euphonic ん, and the volitional re-enable rule)
 * before treating this table as final.
 */
object JapaneseInflectionAnalyzer {

    /**
     * Auxiliary / conjunctive-particle dictionary form → tag, keyed on the
     * lemma ([JaToken.dictionaryForm]) so 言わ+せ+て reads せ's lemma せる (not せ)
     * and 飲ん+だ reads だ's lemma た. Entries NOT here (case particles は/を/が/に,
     * etc.) are intentionally unlabeled even when folded into the surface.
     */
    private val AUX_TAGS: Map<String, InflectionTag> = mapOf(
        "せる" to InflectionTag.CAUSATIVE,
        "させる" to InflectionTag.CAUSATIVE,
        "れる" to InflectionTag.PASSIVE,   // passive AND potential — one form, one lemma
        "られる" to InflectionTag.PASSIVE,
        "ない" to InflectionTag.NEGATIVE,
        "ぬ" to InflectionTag.NEGATIVE,
        "ず" to InflectionTag.NEGATIVE,
        "た" to InflectionTag.PAST,
        "ます" to InflectionTag.POLITE,
        "です" to InflectionTag.POLITE,
        "たい" to InflectionTag.DESIDERATIVE,
        "たがる" to InflectionTag.DESIDERATIVE,
        // NOTE: 〜う/よう (volitional) intentionally absent — that lemma is shared
        // with conjecture (だろう) and likeness (ようだ), so labeling it from the
        // lemma alone misfires. Deferred to Phase 0; see the class doc.
        "て" to InflectionTag.TE_FORM,
        "で" to InflectionTag.TE_FORM,     // euphonic て after ん (読んで)
        "ば" to InflectionTag.CONDITIONAL,
    )

    /**
     * @param stem the content morpheme (only verbs / i-adjectives conjugate)
     * @param glue the trailing PARTICLE/AUX morphemes folded into its surface span
     */
    fun analyze(stem: JaToken, glue: List<JaToken>): List<InflectionTag> {
        if (!stem.category.startsConjugation) return emptyList()
        val tags = mutableListOf<InflectionTag>()
        for (g in glue) AUX_TAGS[g.dictionaryForm]?.let(tags::add)
        // Endings with no carrying auxiliary live in the LAST CONJUGATING
        // morpheme's 活用形. Scan stem+glue from the end, skipping trailing
        // particles (sentence-final よ/ね/さ carry no inflectionForm) so 食べろよ
        // still reads the 命令形 on 食べろ instead of stopping at the よ.
        val finalForm = (listOf(stem) + glue)
            .lastOrNull { it.inflectionForm != null }?.inflectionForm
        if (finalForm?.startsWith("命令形") == true) tags.add(InflectionTag.IMPERATIVE)
        return tags.distinct()
    }
}
