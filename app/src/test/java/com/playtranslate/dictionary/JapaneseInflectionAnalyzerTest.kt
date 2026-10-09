package com.playtranslate.dictionary

import com.playtranslate.dictionary.JaCategory.ADJ_I
import com.playtranslate.dictionary.JaCategory.ADJ_NA
import com.playtranslate.dictionary.JaCategory.AUX
import com.playtranslate.dictionary.JaCategory.PARTICLE
import com.playtranslate.dictionary.JaCategory.VERB
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.InflectionTag.AGERU
import com.playtranslate.language.InflectionTag.ARU
import com.playtranslate.language.InflectionTag.BA
import com.playtranslate.language.InflectionTag.CAUSATIVE
import com.playtranslate.language.InflectionTag.CHAU
import com.playtranslate.language.InflectionTag.CHIMAU
import com.playtranslate.language.InflectionTag.DESU
import com.playtranslate.language.InflectionTag.GARU
import com.playtranslate.language.InflectionTag.IKU
import com.playtranslate.language.InflectionTag.IMPERATIVE
import com.playtranslate.language.InflectionTag.IRU
import com.playtranslate.language.InflectionTag.ITADAKU
import com.playtranslate.language.InflectionTag.KUDASARU
import com.playtranslate.language.InflectionTag.KURERU
import com.playtranslate.language.InflectionTag.KURU
import com.playtranslate.language.InflectionTag.MASU
import com.playtranslate.language.InflectionTag.MIRU
import com.playtranslate.language.InflectionTag.MORAU
import com.playtranslate.language.InflectionTag.N
import com.playtranslate.language.InflectionTag.NARA
import com.playtranslate.language.InflectionTag.NEGATIVE
import com.playtranslate.language.InflectionTag.NU
import com.playtranslate.language.InflectionTag.OKU
import com.playtranslate.language.InflectionTag.PASSIVE
import com.playtranslate.language.InflectionTag.POTENTIAL
import com.playtranslate.language.InflectionTag.POTENTIAL_OR_PASSIVE
import com.playtranslate.language.InflectionTag.SHIMAU
import com.playtranslate.language.InflectionTag.SOU
import com.playtranslate.language.InflectionTag.TA
import com.playtranslate.language.InflectionTag.TAI
import com.playtranslate.language.InflectionTag.TARA
import com.playtranslate.language.InflectionTag.TARI
import com.playtranslate.language.InflectionTag.TE
import com.playtranslate.language.InflectionTag.VOLITIONAL
import com.playtranslate.language.InflectionTag.YARU
import com.playtranslate.language.InflectionTag.ZU
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [JapaneseInflectionAnalyzer] over real Sudachi morpheme chains. Unless a
 * comment names another source, each chain is copied morpheme by morpheme from
 * seg-runs/baseline-s1/survey.json, the S0 harness's survey dump (Sudachi
 * 0.7.4, split mode A, the pack's system_core.dic): surface, dictionaryForm,
 * normalizedForm, inflectionForm (pos[5]) and conjugationType (pos[4]), with
 * conj, aux and auxStem derived from pos[0..1] the way SudachiJapaneseTokenizer
 * derives them. "corpus" names seg-runs/baseline-s1/corpus.json; "CLI" names
 * Sudachi's own command line run on the same dictionary file, for rows the
 * survey has no specimen of.
 *
 * Chains whose auxiliary verb, 無い or そう sits in the glue are what the
 * re-glob's fold hands the analyzer; ReglobTokensTest drives them end to end.
 */
class JapaneseInflectionAnalyzerTest {

    private fun tok(
        surface: String,
        category: JaCategory,
        dictionaryForm: String,
        normalizedForm: String,
        inflectionForm: String?,
        conjugationType: String?,
        conj: Boolean = false,
        aux: Boolean = false,
        auxStem: Boolean = false,
    ) = JaToken(
        surface = surface, begin = 0, end = surface.length, category = category,
        dictionaryForm = dictionaryForm, normalizedForm = normalizedForm, reading = null,
        isOov = false, inflectionForm = inflectionForm, isConjunctiveParticle = conj,
        conjugationType = conjugationType, isAuxiliaryCapable = aux, isAuxiliaryStem = auxStem,
    )

    /** The first morpheme is the stem, the rest its glue, as the re-glob passes them. */
    private fun assertTags(expected: List<InflectionTag>, vararg chain: JaToken) =
        assertEquals(expected, JapaneseInflectionAnalyzer.analyze(chain.first(), chain.drop(1)))

    // ── Stem ───────────────────────────────────────────────────────────────

    @Test
    fun `a stem in 意志推量形 is volitional`() {
        assertTags(
            listOf(VOLITIONAL), // 食べよう
            tok("食べよう", VERB, "食べる", "食べる", "意志推量形", "下一段-バ行"),
        )
        assertTags(
            listOf(VOLITIONAL), // 行こう
            tok("行こう", VERB, "行く", "行く", "意志推量形", "五段-カ行", aux = true),
        )
    }

    @Test
    fun `a godan potential lexeme is potential, first`() {
        assertTags(
            listOf(POTENTIAL, TA), // 泳げた
            tok("泳げ", VERB, "泳げる", "泳ぐ", "連用形-一般", "下一段-ガ行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(POTENTIAL, TA), // 書けた
            tok("書け", VERB, "書ける", "書く", "連用形-一般", "下一段-カ行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `a ra-less potential lexeme is potential`() {
        assertTags(
            listOf(POTENTIAL, TA), // 見れた
            tok("見れ", VERB, "見れる", "見る", "連用形-一般", "下一段-ラ行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `a span looked up under the potential itself does not repeat it`() {
        val mire = tok("見れ", VERB, "見れる", "見る", "連用形-一般", "下一段-ラ行", aux = true)
        val ta = tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ")
        // 見れた shown as 見れる: the word is the potential.
        assertEquals(listOf(TA), JapaneseInflectionAnalyzer.analyze(mire, listOf(ta), lookupForm = "見れる"))
        // 見れた shown as 見る: the potential is the chain's first step.
        assertEquals(listOf(POTENTIAL, TA), JapaneseInflectionAnalyzer.analyze(mire, listOf(ta), lookupForm = "見る"))
    }

    @Test
    fun `弾ける normalizes to itself and is not a potential`() {
        assertTags(
            listOf(TA), // 弾けた
            tok("弾け", VERB, "弾ける", "弾ける", "連用形-一般", "下一段-カ行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `imperative comes from the last morpheme with a 活用形`() {
        assertTags(
            listOf(IMPERATIVE), // 来い
            tok("来い", VERB, "来る", "来る", "命令形", "カ行変格", aux = true),
        )
        assertTags(
            listOf(IMPERATIVE), // 食べろよ: よ has no 活用形
            tok("食べろ", VERB, "食べる", "食べる", "命令形", "下一段-バ行"),
            tok("よ", PARTICLE, "よ", "よ", null, null),
        )
        assertTags(
            listOf(MASU, IMPERATIVE), // くださいませ
            tok("ください", VERB, "くださる", "下さる", "連用形-イ音便", "五段-ラ行", aux = true),
            tok("ませ", AUX, "ます", "ます", "命令形", "助動詞-マス"),
        )
    }

    @Test
    fun `a stem that does not conjugate reads nothing`() {
        assertTags(
            emptyList(), // 静かだった
            tok("静か", ADJ_NA, "静か", "静か", null, null),
            tok("だっ", AUX, "だ", "だ", "連用形-促音便", "助動詞-ダ"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    // ── Particles ──────────────────────────────────────────────────────────

    @Test
    fun `て and で as 接続助詞 are -て`() {
        assertTags(
            listOf(CAUSATIVE, TE), // 言わせて
            tok("言わ", VERB, "言う", "言う", "未然形-一般", "五段-ワア行"),
            tok("せ", AUX, "せる", "せる", "連用形-一般", "下一段-サ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
        )
        assertTags(
            listOf(NEGATIVE, TE), // 食べなくて
            tok("食べ", VERB, "食べる", "食べる", "未然形-一般", "下一段-バ行"),
            tok("なく", AUX, "ない", "ない", "連用形-一般", "助動詞-ナイ"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
        )
        assertTags(
            listOf(NEGATIVE, TE), // 行かないで
            tok("行か", VERB, "行く", "行く", "未然形-一般", "五段-カ行", aux = true),
            tok("ない", AUX, "ない", "ない", "終止形-一般", "助動詞-ナイ"),
            tok("で", PARTICLE, "で", "で", null, null, conj = true),
        )
    }

    @Test
    fun `a で that is not a 接続助詞 is not -て`() {
        // Built, not copied: a 格助詞 で with the same forms as the 接続助詞.
        assertTags(
            emptyList(),
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("で", PARTICLE, "で", "で", null, null),
        )
    }

    @Test
    fun `ては and ちゃ are morphemes of their own and read nothing`() {
        assertTags(
            emptyList(), // 食べてはいけない: the span is 食べては
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("ては", PARTICLE, "ては", "ては", null, null, conj = true),
        )
        assertTags(
            emptyList(), // 食べちゃだめ: the span is 食べちゃ; ちゃ normalizes to て
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("ちゃ", PARTICLE, "ちゃ", "て", null, null, conj = true),
        )
    }

    @Test
    fun `ば is -ば`() {
        assertTags(
            listOf(NEGATIVE, BA), // 食べなければ
            tok("食べ", VERB, "食べる", "食べる", "未然形-一般", "下一段-バ行"),
            tok("なけれ", AUX, "ない", "ない", "仮定形-一般", "助動詞-ナイ"),
            tok("ば", PARTICLE, "ば", "ば", null, null, conj = true),
        )
        assertTags(
            listOf(BA), // 高ければ
            tok("高けれ", ADJ_I, "高い", "高い", "仮定形-一般", "形容詞"),
            tok("ば", PARTICLE, "ば", "ば", null, null, conj = true),
        )
    }

    @Test
    fun `たり and だり are -たり`() {
        // 食べたり飲んだり: two spans.
        assertTags(
            listOf(TARI),
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("たり", PARTICLE, "たり", "たり", null, null),
        )
        assertTags(
            listOf(TARI),
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("だり", PARTICLE, "だり", "たり", null, null),
        )
    }

    @Test
    fun `other particles read nothing`() {
        assertTags(
            listOf(TE), // 食べてばかりいる: the span is 食べてばかり
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("ばかり", PARTICLE, "ばかり", "ばかり", null, null),
        )
    }

    // ── Auxiliaries (助動詞) ─────────────────────────────────────────────────

    @Test
    fun `た is -た, and -たら in 仮定形`() {
        assertTags(
            listOf(TARA), // 食べたら
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("たら", AUX, "た", "た", "仮定形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(POTENTIAL_OR_PASSIVE, TA), // 食べられた
            tok("食べ", VERB, "食べる", "食べる", "未然形-一般", "下一段-バ行"),
            tok("られ", AUX, "られる", "られる", "連用形-一般", "助動詞-レル"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `the past written だ normalizes to た and is not the copula`() {
        assertTags(
            listOf(TA), // corpus: 結んだ (コープ関係を結んだ相手には)
            tok("結ん", VERB, "結ぶ", "結ぶ", "連用形-撥音便", "五段-バ行"),
            tok("だ", AUX, "だ", "た", "連体形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TA), // CLI: 飲んだ
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("だ", AUX, "だ", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TARA), // CLI: 飲んだら
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("だら", AUX, "だ", "た", "仮定形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `ます is -ます, and volitional in 意志推量形`() {
        assertTags(
            listOf(MASU, VOLITIONAL), // 行きましょう
            tok("行き", VERB, "行く", "行く", "連用形-一般", "五段-カ行", aux = true),
            tok("ましょう", AUX, "ます", "ます", "意志推量形", "助動詞-マス"),
        )
    }

    @Test
    fun `volitional follows any morpheme in 意志推量形 but the copula`() {
        assertTags(
            listOf(IKU, VOLITIONAL), // corpus: じゃ、ファミレスでも寄ってこうか。
            tok("寄っ", VERB, "寄る", "寄る", "連用形-促音便", "五段-ラ行"),
            tok("てこう", AUX, "てく", "てく", "意志推量形", "五段-カ行"),
            tok("か", PARTICLE, "か", "か", null, null),
        )
        assertTags(
            listOf(TE, MIRU, VOLITIONAL), // corpus: 中を探ってみようぜ!
            tok("探っ", VERB, "探る", "探る", "連用形-促音便", "五段-ラ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("みよう", VERB, "みる", "見る", "意志推量形", "上一段-マ行", aux = true),
            tok("ぜ", PARTICLE, "ぜ", "ぜ", null, null),
        )
        assertTags(
            listOf(TE, YARU, VOLITIONAL), // corpus: 絶対にやってやろうぜ!
            tok("やっ", VERB, "やる", "遣る", "連用形-促音便", "五段-ラ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("やろう", VERB, "やる", "遣る", "意志推量形", "五段-ラ行", aux = true),
            tok("ぜ", PARTICLE, "ぜ", "ぜ", null, null),
        )
        assertTags(
            listOf(CHIMAU, VOLITIONAL), // corpus: …解除しちまおうぜ!
            tok("し", VERB, "する", "為る", "連用形-一般", "サ行変格", aux = true),
            tok("ちまおう", AUX, "ちまう", "ちまう", "意志推量形", "五段-ワア行"),
            tok("ぜ", PARTICLE, "ぜ", "ぜ", null, null),
        )
        assertTags(
            emptyList(), // 食べるだろう: だ in 意志推量形 is the copula's
            tok("食べる", VERB, "食べる", "食べる", "終止形-一般", "下一段-バ行"),
            tok("だろう", AUX, "だ", "だ", "意志推量形", "助動詞-ダ"),
        )
    }

    @Test
    fun `です is -です, never volitional`() {
        assertTags(
            listOf(DESU), // 食べるでしょう
            tok("食べる", VERB, "食べる", "食べる", "終止形-一般", "下一段-バ行"),
            tok("でしょう", AUX, "です", "です", "意志推量形", "助動詞-デス"),
        )
    }

    @Test
    fun `だ in 仮定形 is -なら`() {
        assertTags(
            listOf(NARA), // corpus: 破るなら (ここを破るなら、サーバーとか...)
            tok("破る", VERB, "破る", "破る", "終止形-一般", "五段-ラ行"),
            tok("なら", AUX, "だ", "だ", "仮定形-一般", "助動詞-ダ"),
        )
    }

    @Test
    fun `だ outside 仮定形 reads nothing`() {
        assertTags(
            emptyList(), // 食べるだろう
            tok("食べる", VERB, "食べる", "食べる", "終止形-一般", "下一段-バ行"),
            tok("だろう", AUX, "だ", "だ", "意志推量形", "助動詞-ダ"),
        )
    }

    @Test
    fun `ない is negative`() {
        assertTags(
            listOf(NEGATIVE), // corpus: 話さねぇか (少し俺と話さねぇか?); ねぇ normalizes to ない
            tok("話さ", VERB, "話す", "話す", "未然形-一般", "五段-サ行"),
            tok("ねぇ", AUX, "ねぇ", "ない", "終止形-一般", "助動詞-ナイ"),
            tok("か", PARTICLE, "か", "か", null, null),
        )
    }

    @Test
    fun `the ん after ませ is negative`() {
        assertTags(
            listOf(MASU, NEGATIVE, DESU, TA), // 食べませんでした
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("ませ", AUX, "ます", "ます", "未然形-一般", "助動詞-マス"),
            tok("ん", AUX, "ぬ", "ず", "終止形-撥音便", "助動詞-ヌ"),
            tok("でし", AUX, "です", "です", "連用形-一般", "助動詞-デス"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `ん, ぬ and ず are -ん, -ぬ and -ず`() {
        assertTags(
            listOf(N), // 行かん
            tok("行か", VERB, "行く", "行く", "未然形-一般", "五段-カ行", aux = true),
            tok("ん", AUX, "ぬ", "ず", "終止形-撥音便", "助動詞-ヌ"),
        )
        assertTags(
            listOf(NU), // 行かぬ
            tok("行か", VERB, "行く", "行く", "未然形-一般", "五段-カ行", aux = true),
            tok("ぬ", AUX, "ぬ", "ず", "終止形-一般", "助動詞-ヌ"),
        )
        assertTags(
            listOf(ZU), // 行かず
            tok("行か", VERB, "行く", "行く", "未然形-一般", "五段-カ行", aux = true),
            tok("ず", AUX, "ず", "ず", "終止形-一般", "助動詞-ヌ"),
        )
    }

    @Test
    fun `たがる is -たい then -がる`() {
        assertTags(
            listOf(TAI, GARU), // 食べたがる
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("たがる", AUX, "たがる", "たがる", "終止形-一般", "五段-ラ行"),
        )
    }

    @Test
    fun `れる after a godan or suru morpheme is passive`() {
        assertTags(
            listOf(PASSIVE), // 書かれる
            tok("書か", VERB, "書く", "書く", "未然形-一般", "五段-カ行"),
            tok("れる", AUX, "れる", "れる", "終止形-一般", "助動詞-レル"),
        )
        assertTags(
            listOf(PASSIVE), // される
            tok("さ", VERB, "する", "為る", "未然形-サ", "サ行変格", aux = true),
            tok("れる", AUX, "れる", "れる", "終止形-一般", "助動詞-レル"),
        )
    }

    @Test
    fun `られる after an ichidan or kahen morpheme is potential or passive`() {
        assertTags(
            listOf(POTENTIAL_OR_PASSIVE), // 来られる
            tok("来", VERB, "来る", "来る", "未然形-一般", "カ行変格", aux = true),
            tok("られる", AUX, "られる", "られる", "終止形-一般", "助動詞-レル"),
        )
    }

    @Test
    fun `the class is the morpheme right before, an auxiliary's too`() {
        assertTags(
            listOf(CAUSATIVE, POTENTIAL_OR_PASSIVE), // 食べさせられる: させ is 下一段-サ行
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("させ", AUX, "させる", "させる", "未然形-一般", "下一段-サ行"),
            tok("られる", AUX, "られる", "られる", "終止形-一般", "助動詞-レル"),
        )
        assertTags(
            listOf(CAUSATIVE, POTENTIAL_OR_PASSIVE), // 言わせられる: 五段 stem, 下一段 させ
            tok("言わ", VERB, "言う", "言う", "未然形-一般", "五段-ワア行"),
            tok("せ", AUX, "せる", "せる", "未然形-一般", "下一段-サ行"),
            tok("られる", AUX, "られる", "られる", "終止形-一般", "助動詞-レル"),
        )
    }

    @Test
    fun `ちゃう and ちまう are -ちゃう and -ちまう`() {
        assertTags(
            listOf(CHAU, TA), // 食べちゃった
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("ちゃっ", AUX, "ちゃう", "ちゃう", "連用形-促音便", "五段-ワア行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(CHAU, TA), // 飲んじゃった
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("じゃっ", AUX, "じゃう", "ちゃう", "連用形-促音便", "五段-ワア行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(CHIMAU, TA), // 食べちまった
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("ちまっ", AUX, "ちまう", "ちまう", "連用形-促音便", "五段-ワア行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `てる and とる are -いる`() {
        assertTags(
            listOf(IRU), // 食べてる
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("てる", AUX, "てる", "てる", "終止形-一般", "下一段-タ行"),
        )
        assertTags(
            listOf(IRU), // 飲んでる
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("でる", AUX, "でる", "てる", "連体形-一般", "下一段-ダ行"),
        )
        assertTags(
            listOf(IRU, TA), // 食べてた
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", AUX, "てる", "てる", "連用形-一般", "下一段-タ行"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(IRU, NEGATIVE), // 食べてない
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", AUX, "てる", "てる", "未然形-一般", "下一段-タ行"),
            tok("ない", AUX, "ない", "ない", "終止形-一般", "助動詞-ナイ"),
        )
        assertTags(
            listOf(IRU), // CLI: 食べとる
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("とる", AUX, "とる", "とる", "終止形-一般", "五段-ラ行"),
        )
        assertTags(
            listOf(IRU), // CLI: 読んどる
            tok("読ん", VERB, "読む", "読む", "連用形-撥音便", "五段-マ行"),
            tok("どる", AUX, "どる", "とる", "終止形-一般", "五段-ラ行"),
        )
    }

    @Test
    fun `とく is -おく and てく is -いく`() {
        assertTags(
            listOf(OKU), // 買っとく
            tok("買っ", VERB, "買う", "買う", "連用形-促音便", "五段-ワア行"),
            tok("とく", AUX, "とく", "とく", "終止形-一般", "五段-カ行"),
        )
        assertTags(
            listOf(OKU), // 読んどく
            tok("読ん", VERB, "読む", "読む", "連用形-撥音便", "五段-マ行"),
            tok("どく", AUX, "どく", "とく", "終止形-一般", "五段-カ行"),
        )
        assertTags(
            listOf(IKU), // 持ってく
            tok("持っ", VERB, "持つ", "持つ", "連用形-促音便", "五段-タ行"),
            tok("てく", AUX, "てく", "てく", "終止形-一般", "五段-カ行"),
        )
    }

    // ── Auxiliary verbs after て/で (the fold stage's glue) ──────────────────

    @Test
    fun `居る and おる are -いる`() {
        assertTags(
            listOf(TE, IRU, NEGATIVE, TA), // 飲んでいなかった
            tok("飲ん", VERB, "飲む", "飲む", "連用形-撥音便", "五段-マ行"),
            tok("で", PARTICLE, "で", "で", null, null, conj = true),
            tok("い", VERB, "いる", "居る", "未然形-一般", "上一段-ア行", aux = true),
            tok("なかっ", AUX, "ない", "ない", "連用形-促音便", "助動詞-ナイ"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TE, IRU), // 書いておる: おる normalizes to 居る
            tok("書い", VERB, "書く", "書く", "連用形-イ音便", "五段-カ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("おる", VERB, "おる", "居る", "終止形-一般", "五段-ラ行", aux = true),
        )
        assertTags(
            listOf(TE, IRU, MASU), // 書いております: おり normalizes to おる
            tok("書い", VERB, "書く", "書く", "連用形-イ音便", "五段-カ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("おり", VERB, "おる", "おる", "連用形-一般", "五段-ラ行", aux = true),
            tok("ます", AUX, "ます", "ます", "終止形-一般", "助動詞-マス"),
        )
    }

    @Test
    fun `有る is -ある`() {
        assertTags(
            listOf(TE, ARU), // 置いてある
            tok("置い", VERB, "置く", "置く", "連用形-イ音便", "五段-カ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("ある", VERB, "ある", "有る", "終止形-一般", "五段-ラ行", aux = true),
        )
    }

    @Test
    fun `仕舞う is -しまう`() {
        assertTags(
            listOf(TE, SHIMAU, TA), // 食べてしまった
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("しまっ", VERB, "しまう", "仕舞う", "連用形-促音便", "五段-ワア行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `おく and 置く are -おく`() {
        assertTags(
            listOf(TE, OKU), // 買っておく
            tok("買っ", VERB, "買う", "買う", "連用形-促音便", "五段-ワア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("おく", VERB, "おく", "おく", "終止形-一般", "五段-カ行", aux = true),
        )
        assertTags(
            listOf(TE, OKU), // CLI: 置いて置く
            tok("置い", VERB, "置く", "置く", "連用形-イ音便", "五段-カ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("置く", VERB, "置く", "置く", "終止形-一般", "五段-カ行", aux = true),
        )
    }

    @Test
    fun `見る is -みる`() {
        assertTags(
            listOf(TE, MIRU, TA), // 見てみた
            tok("見", VERB, "見る", "見る", "連用形-一般", "上一段-マ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("み", VERB, "みる", "見る", "連用形-一般", "上一段-マ行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `行く is -いく and 来る is -くる`() {
        assertTags(
            listOf(TE, IKU, TA), // 持っていった
            tok("持っ", VERB, "持つ", "持つ", "連用形-促音便", "五段-タ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("いっ", VERB, "いく", "行く", "連用形-促音便", "五段-カ行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TE, KURU, TA), // 持ってきた
            tok("持っ", VERB, "持つ", "持つ", "連用形-促音便", "五段-タ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("き", VERB, "くる", "来る", "連用形-一般", "カ行変格", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `the benefactive verbs are their own forms`() {
        assertTags(
            listOf(TE, AGERU), // 教えてあげる
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("あげる", VERB, "あげる", "上げる", "終止形-一般", "下一段-ガ行", aux = true),
        )
        assertTags(
            listOf(TE, KURERU, TA), // 教えてくれた
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("くれ", VERB, "くれる", "呉れる", "連用形-一般", "下一段-ラ行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TE, MORAU, TA), // 教えてもらった
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("もらっ", VERB, "もらう", "貰う", "連用形-促音便", "五段-ワア行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
        assertTags(
            listOf(TE, YARU), // 教えてやる
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("やる", VERB, "やる", "遣る", "終止形-一般", "五段-ラ行", aux = true),
        )
        assertTags(
            listOf(TE, KUDASARU, IMPERATIVE), // 教えてください
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("ください", VERB, "くださる", "下さる", "命令形", "五段-ラ行", aux = true),
        )
        assertTags(
            listOf(TE, ITADAKU, MASU, TA), // 教えていただきました
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("いただき", VERB, "いただく", "頂く", "連用形-一般", "五段-カ行", aux = true),
            tok("まし", AUX, "ます", "ます", "連用形-一般", "助動詞-マス"),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    @Test
    fun `a potential auxiliary verb is its step, then potential`() {
        assertTags(
            listOf(TE, ITADAKU, POTENTIAL, MASU), // 来て頂けます
            tok("来", VERB, "来る", "来る", "連用形-一般", "カ行変格", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("頂け", VERB, "頂ける", "頂く", "連用形-一般", "下一段-カ行", aux = true),
            tok("ます", AUX, "ます", "ます", "終止形-一般", "助動詞-マス"),
        )
        assertTags(
            listOf(TE, ITADAKU, POTENTIAL, MASU), // 来ていただけますか
            tok("来", VERB, "来る", "来る", "連用形-一般", "カ行変格", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("いただけ", VERB, "いただける", "頂く", "連用形-一般", "下一段-カ行", aux = true),
            tok("ます", AUX, "ます", "ます", "終止形-一般", "助動詞-マス"),
            tok("か", PARTICLE, "か", "か", null, null),
        )
        assertTags(
            listOf(TE, MORAU, POTENTIAL), // CLI: 教えてもらえる
            tok("教え", VERB, "教える", "教える", "連用形-一般", "下一段-ア行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("もらえる", VERB, "もらえる", "貰う", "終止形-一般", "下一段-ア行", aux = true),
        )
    }

    @Test
    fun `an auxiliary-capable verb off the list reads nothing`() {
        assertTags(
            listOf(TE), // やってみせる
            tok("やっ", VERB, "やる", "遣る", "連用形-促音便", "五段-ラ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("みせる", VERB, "みせる", "見せる", "終止形-一般", "下一段-サ行", aux = true),
        )
    }

    @Test
    fun `a repeated step is kept`() {
        assertTags(
            listOf(TE, MIRU, TE), // 食べてみて
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("み", VERB, "みる", "見る", "連用形-一般", "上一段-マ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
        )
        assertTags(
            listOf(TE, IKU, TE, SHIMAU, TA), // 持っていってしまった
            tok("持っ", VERB, "持つ", "持つ", "連用形-促音便", "五段-タ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("いっ", VERB, "いく", "行く", "連用形-促音便", "五段-カ行", aux = true),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("しまっ", VERB, "しまう", "仕舞う", "連用形-促音便", "五段-ワア行", aux = true),
            tok("た", AUX, "た", "た", "終止形-一般", "助動詞-タ"),
        )
    }

    // ── 無い and そう ───────────────────────────────────────────────────────

    @Test
    fun `the adjective 無い is negative`() {
        assertTags(
            listOf(NEGATIVE), // 高くない
            tok("高く", ADJ_I, "高い", "高い", "連用形-一般", "形容詞"),
            tok("ない", ADJ_I, "ない", "無い", "終止形-一般", "形容詞", aux = true),
        )
    }

    @Test
    fun `another auxiliary-capable adjective reads nothing`() {
        assertTags(
            listOf(TE), // 食べてもいい
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("て", PARTICLE, "て", "て", null, null, conj = true),
            tok("も", PARTICLE, "も", "も", null, null),
            tok("いい", ADJ_I, "いい", "良い", "終止形-一般", "形容詞", aux = true),
        )
    }

    @Test
    fun `the auxiliary stem そう is -そう`() {
        assertTags(
            listOf(SOU), // 食べそうだ
            tok("食べ", VERB, "食べる", "食べる", "連用形-一般", "下一段-バ行"),
            tok("そう", ADJ_NA, "そう", "そう", null, null, auxStem = true),
            tok("だ", AUX, "だ", "だ", "終止形-一般", "助動詞-ダ"),
        )
        assertTags(
            listOf(SOU), // 高そう
            tok("高", ADJ_I, "高い", "高い", "語幹-一般", "形容詞"),
            tok("そう", ADJ_NA, "そう", "そう", null, null, auxStem = true),
        )
    }

    @Test
    fun `the auxiliary stem よう reads nothing`() {
        assertTags(
            emptyList(), // 食べるようだ
            tok("食べる", VERB, "食べる", "食べる", "連体形-一般", "下一段-バ行"),
            tok("よう", ADJ_NA, "よう", "よう", null, null, auxStem = true),
            tok("だ", AUX, "だ", "だ", "終止形-一般", "助動詞-ダ"),
        )
    }
}
