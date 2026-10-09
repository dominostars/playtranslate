package com.playtranslate.dictionary

import com.playtranslate.language.InflectionTag

/**
 * Derives the [InflectionTag]s a conjugated Japanese surface expresses from its
 * morphemes: the content stem plus the ordered glue that
 * [DictionaryManager.Companion.reglobTokens] folds into its span. Pure and
 * Sudachi-independent. Every row is pinned by JapaneseInflectionAnalyzerTest
 * with chains copied from the S0 harness's survey dump
 * (seg-runs/baseline-s1/survey.json), except where that test names the corpus
 * dump or the Sudachi command line on the same dictionary as its source.
 *
 * Tags come in morpheme order, dictionary form outward, and a repeated step is
 * kept: 食べてみて reads -て, -みる, -て.
 *
 * The stem speaks first. POTENTIAL when Sudachi lexicalized a potential, giving
 * the potential as the dictionary form and the base verb as the normalized form
 * (泳げ: 泳げる, 泳ぐ; 見れ: 見れる, 見る; いけ: いける, 行く; see
 * [isPotentialLexeme]), unless the span is looked up under that potential itself,
 * so the word shown already is the potential (見れた under 見れる); 泳げた looked
 * up under 泳ぐ keeps it.
 *
 * Then each glue morpheme, by kind:
 *  - particle, by dictionary form: て/で only as a 接続助詞 → -て (ては and ちゃ are
 *    morphemes of their own and read nothing); ば → -ば; たり/だり → -たり.
 *  - auxiliary (助動詞), by normalized form, because the written form varies
 *    (じゃっ is ちゃう, でる is てる, ん is ず, and the past after a euphonic stem
 *    is written だ but normalizes to た): see [addAuxiliaryTags].
 *  - auxiliary-capable verb (動詞 非自立可能), by normalized form: [AUX_VERB_TAGS],
 *    plus POTENTIAL after its step when the verb is itself a potential (頂け).
 *  - auxiliary-capable adjective 無い → negative (高くない).
 *  - auxiliary stem そう → -そう (食べそうだ).
 * Everything else reads nothing: も, よ, か, ばかり, だろう, よう, いい.
 *
 * VOLITIONAL follows any morpheme, stem or glue, whose 活用形 is 意志推量形,
 * except the copula だ or です (だろう, でしょう): the stem (食べよう and 行こう are
 * one morpheme each), ます (ましょう), a contracted auxiliary (寄っ|てこう) or an
 * auxiliary verb (探っ|て|みよう).
 *
 * Last, IMPERATIVE when the last morpheme that has a 活用形 is in 命令形 (食べろよ,
 * 教えてください, くださいませ).
 */
object JapaneseInflectionAnalyzer {

    /**
     * Auxiliary-capable verb (動詞 非自立可能) after て/で, keyed on its normalized
     * form because the written form varies (いる and おる both normalize to 居る,
     * おり to おる). The fold reads the keys as its allow-list
     * ([DictionaryManager.Companion.isTeAuxiliary]).
     */
    internal val AUX_VERB_TAGS: Map<String, InflectionTag> = mapOf(
        "居る" to InflectionTag.IRU,
        "おる" to InflectionTag.IRU,
        "有る" to InflectionTag.ARU,
        "仕舞う" to InflectionTag.SHIMAU,
        "おく" to InflectionTag.OKU,
        "置く" to InflectionTag.OKU,
        "見る" to InflectionTag.MIRU,
        "行く" to InflectionTag.IKU,
        "来る" to InflectionTag.KURU,
        "上げる" to InflectionTag.AGERU,
        "呉れる" to InflectionTag.KURERU,
        "貰う" to InflectionTag.MORAU,
        "遣る" to InflectionTag.YARU,
        "下さる" to InflectionTag.KUDASARU,
        "頂く" to InflectionTag.ITADAKU,
    )

    /** A verb's final kana and the え-row kana its potential takes before る. */
    private val E_ROW: Map<Char, Char> = mapOf(
        'う' to 'え', 'く' to 'け', 'ぐ' to 'げ', 'す' to 'せ', 'つ' to 'て',
        'ぬ' to 'ね', 'ぶ' to 'べ', 'む' to 'め', 'る' to 'れ',
    )

    /** Potentials written in kana whose normalized form is the kanji base verb. */
    private val KANA_POTENTIALS = setOf("いける", "いただける", "もらえる")

    /** Conjugation types whose れる/られる reads potential or passive. */
    private val ICHIDAN_OR_KAHEN = listOf("上一段", "下一段", "カ行変格")

    /** The copula auxiliaries, whose 意志推量形 (だろう, でしょう) is not volitional. */
    private val COPULAS = setOf("だ", "です")

    /**
     * @param stem the content morpheme; only a verb, i-adjective or 形状詞 conjugates,
     *   a 形状詞 only through the glue after it (静か|だっ|た reads -た, 静か|じゃ|ない negative)
     * @param glue the morphemes folded into its surface span, in order
     * @param lookupForm the form the span is looked up under, when known; the
     *   stem's POTENTIAL is dropped when it equals the stem's dictionary form
     */
    fun analyze(stem: JaToken, glue: List<JaToken>, lookupForm: String? = null): List<InflectionTag> {
        if (!stem.category.startsConjugation) return emptyList()
        val tags = mutableListOf<InflectionTag>()
        if (isPotentialLexeme(stem) && lookupForm != stem.dictionaryForm) tags += InflectionTag.POTENTIAL
        tags.addVolitional(stem)
        var previous = stem
        for (g in glue) {
            when (g.category) {
                JaCategory.PARTICLE -> particleTag(g)?.let(tags::add)
                JaCategory.AUX -> tags.addAuxiliaryTags(g, previous)
                JaCategory.VERB -> if (g.isAuxiliaryCapable) {
                    AUX_VERB_TAGS[g.normalizedForm]?.let { step ->
                        tags += step
                        if (isPotentialLexeme(g)) tags += InflectionTag.POTENTIAL
                    }
                }
                JaCategory.ADJ_I ->
                    if (g.isAuxiliaryCapable && g.normalizedForm == "無い") tags += InflectionTag.NEGATIVE
                else ->
                    if (g.isAuxiliaryStem && g.normalizedForm == "そう") tags += InflectionTag.SOU
            }
            tags.addVolitional(g)
            previous = g
        }
        // Scan from the end past morphemes with no 活用形 (よ in 食べろよ).
        val finalForm = (listOf(stem) + glue).lastOrNull { it.inflectionForm != null }?.inflectionForm
        if (finalForm.isForm("命令形")) tags += InflectionTag.IMPERATIVE
        return tags
    }

    private fun particleTag(particle: JaToken): InflectionTag? = when (particle.dictionaryForm) {
        "て", "で" -> InflectionTag.TE.takeIf { particle.isConjunctiveParticle }
        "ば" -> InflectionTag.BA
        "たり", "だり" -> InflectionTag.TARI
        else -> null
    }

    /**
     * た → -た, or -たら in 仮定形; ます → -ます (its volitional ましょう is
     * [addVolitional]'s); です → -です (でしょう too); だ → -なら in 仮定形, else nothing
     * (だろう, な, に); ない → negative; ず: the ん of ません → negative, any other
     * ん → -ん, ぬ → -ぬ, ず → -ず; たい → -たい; たがる → -たい, -がる; せる/させる →
     * causative; れる/られる by the morpheme before it (see [passiveOrPotential]);
     * the contractions ちゃう, ちまう, てる and とる (→ -いる), とく (→ -おく), てく
     * (→ -いく).
     */
    private fun MutableList<InflectionTag>.addAuxiliaryTags(aux: JaToken, previous: JaToken) {
        when (aux.normalizedForm) {
            "た" -> add(if (aux.inflectionForm.isForm("仮定形")) InflectionTag.TARA else InflectionTag.TA)
            "ます" -> add(InflectionTag.MASU)
            "です" -> add(InflectionTag.DESU)
            "だ" -> if (aux.inflectionForm.isForm("仮定形")) add(InflectionTag.NARA)
            "ない" -> add(InflectionTag.NEGATIVE)
            "ず" -> add(
                when {
                    aux.dictionaryForm == "ず" -> InflectionTag.ZU
                    previous.category == JaCategory.AUX && previous.normalizedForm == "ます" ->
                        InflectionTag.NEGATIVE
                    aux.surface == "ん" -> InflectionTag.N
                    else -> InflectionTag.NU
                },
            )
            "たい" -> add(InflectionTag.TAI)
            "たがる" -> {
                add(InflectionTag.TAI)
                add(InflectionTag.GARU)
            }
            "せる", "させる" -> add(InflectionTag.CAUSATIVE)
            "れる", "られる" -> add(passiveOrPotential(previous))
            "ちゃう" -> add(InflectionTag.CHAU)
            "ちまう" -> add(InflectionTag.CHIMAU)
            "てる", "とる" -> add(InflectionTag.IRU)
            "とく" -> add(InflectionTag.OKU)
            "てく" -> add(InflectionTag.IKU)
        }
    }

    /** VOLITIONAL after [morpheme]'s own step when it is in 意志推量形, unless it is the copula. */
    private fun MutableList<InflectionTag>.addVolitional(morpheme: JaToken) {
        if (!morpheme.inflectionForm.isForm("意志推量形")) return
        if (morpheme.category == JaCategory.AUX && morpheme.normalizedForm in COPULAS) return
        add(InflectionTag.VOLITIONAL)
    }

    /**
     * れる/られる read potential or passive after an ichidan or kahen morpheme
     * (食べられた; 食べさせられる, whose させ is 下一段-サ行) and passive otherwise
     * (書かれる after 五段, される after サ行変格). The class is the immediately
     * preceding morpheme's, stem or auxiliary.
     */
    private fun passiveOrPotential(previous: JaToken): InflectionTag {
        val type = previous.conjugationType.orEmpty()
        return if (ICHIDAN_OR_KAHEN.any(type::startsWith)) {
            InflectionTag.POTENTIAL_OR_PASSIVE
        } else {
            InflectionTag.PASSIVE
        }
    }

    /**
     * True when the morpheme is a lexicalized potential: its dictionary form is
     * the potential and its normalized form the base verb. Either the base's last
     * kana moves to the え row and る follows (泳げる of 泳ぐ, 頂ける of 頂く; for a
     * base ending in る this is also the ら抜き shape, 見れる of 見る), or it is a
     * kana potential of a kanji base ([KANA_POTENTIALS]). 弾ける normalizes to
     * itself and is not one.
     */
    private fun isPotentialLexeme(token: JaToken): Boolean {
        val potential = token.dictionaryForm
        val base = token.normalizedForm
        if (potential == base) return false
        if (potential in KANA_POTENTIALS) return true
        val eRow = E_ROW[base.last()] ?: return false
        return potential == "${base.dropLast(1)}${eRow}る"
    }

    private fun String?.isForm(prefix: String): Boolean = this?.startsWith(prefix) == true
}
