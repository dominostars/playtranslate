package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.PhraseCandidate
import com.playtranslate.dictionary.DictionaryManager.Companion.Suspicion
import com.playtranslate.dictionary.DictionaryManager.Companion.admissiblePhraseCandidates
import com.playtranslate.dictionary.DictionaryManager.Companion.phraseCandidatesFor
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobSpans
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobTokens
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.memberUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the extracted n-gram re-glob core: candidate
 * generation ([phraseCandidatesFor]) and the greedy matcher + single-token
 * fallback ([reglobTokens]). All dictionary knowledge is injected as
 * membership sets — no Sudachi dict, no SQLite.
 */
class ReglobTokensTest {

    private fun jaToken(
        surface: String,
        cat: JaCategory,
        dict: String = surface,
        norm: String = dict,
        reading: String? = null,
        infl: String? = null,
        conj: Boolean = false,
        punct: Boolean = false,
        conjType: String? = null,
        aux: Boolean = false,
        auxStem: Boolean = false,
    ) = JaToken(
        surface = surface, begin = 0, end = surface.length, category = cat,
        dictionaryForm = dict, normalizedForm = norm, reading = reading, isOov = false,
        inflectionForm = infl, isConjunctiveParticle = conj, isPunctuation = punct,
        conjugationType = conjType, isAuxiliaryCapable = aux, isAuxiliaryStem = auxStem,
    )

    private fun glob(
        tokens: List<JaToken>,
        knownPhrases: Set<String> = emptySet(),
        knownForms: Set<String> = emptySet(),
    ) = reglobTokens(tokens, phraseCandidatesFor(tokens), knownPhrases, knownForms)

    // ── Phrase reading (homograph narrowing hint) ────────────────────────
    // Exact-join phrases carry the hiragana concat of their members'
    // readings so lookup() can narrow to the entry the tokenizer sided
    // with (彼+等 → かれら entry, not rank-first あれら). Sandhi compounds
    // emit their dictionary-invalid concat (いちはく) on purpose — the
    // narrowed query misses and the rank fallback picks いっぱく exactly
    // as before the hint existed.

    @Test
    fun `exact phrase carries hiragana concat of member readings`() {
        val tokens = listOf(
            jaToken("彼", JaCategory.PRONOUN, reading = "カレ"),
            jaToken("等", JaCategory.NOUN, reading = "ラ"),
        )
        val result = glob(tokens, knownPhrases = setOf("彼等"))
        assertEquals(1, result.size)
        assertEquals("彼等", result[0].lookupForm)
        assertEquals("かれら", result[0].reading)
    }

    @Test
    fun `sandhi compound carries its raw concat unchanged`() {
        val tokens = listOf(
            jaToken("一", JaCategory.NOUN, reading = "イチ"),
            jaToken("泊", JaCategory.NOUN, reading = "ハク"),
        )
        val result = glob(tokens, knownPhrases = setOf("一泊"))
        assertEquals("いちはく", result[0].reading)
    }

    @Test
    fun `lemma variant phrase keeps null reading`() {
        // 気+に+なっ(+た) matches 気になる via the lemma swap. The final
        // token's reading is its inflected surface's (ナッ) — concatenating
        // would produce きになっ, which can never match the entry reading —
        // so variants emit no hint.
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN, reading = "キ"),
            jaToken("に", JaCategory.PARTICLE, reading = "ニ"),
            jaToken("なっ", JaCategory.VERB, dict = "なる", reading = "ナッ", infl = "連用形-促音便"),
            jaToken("た", JaCategory.AUX, reading = "タ"),
        )
        val result = glob(tokens, knownPhrases = setOf("気になる"))
        assertEquals("気になる", result[0].lookupForm)
        assertNull(result[0].reading)
    }

    @Test
    fun `phrase with reading-less member keeps null reading`() {
        val tokens = listOf(
            jaToken("彼", JaCategory.PRONOUN, reading = "カレ"),
            jaToken("等", JaCategory.NOUN),
        )
        val result = glob(tokens, knownPhrases = setOf("彼等"))
        assertNull(result[0].reading)
    }

    // ── Adjectival suffixes (づらい/にくい/やすい/がたい) ─────────────────
    // UniDic tags these 接尾辞,形容詞的; the tokenizer maps them to ADJ_I so
    // they emit their own span, fold their glue, and can end a lemma-variant
    // window. The whole-word entry, when one exists, still wins its window.

    @Test
    fun `adjectival suffix after a verb stem gets its own span`() {
        val tokens = listOf(
            jaToken("歩き", JaCategory.VERB, dict = "歩く", reading = "アルキ", infl = "連用形-一般"),
            jaToken("づらい", JaCategory.ADJ_I, norm = "辛い", reading = "ヅライ", infl = "終止形-一般"),
        )
        val result = glob(tokens, knownForms = setOf("歩く", "づらい", "辛い"))
        assertEquals(listOf("歩く", "づらい"), result.map { it.lookupForm })
        assertEquals("づらい", result[1].surface)
        assertEquals("づらい", result[1].reading)
    }

    @Test
    fun `inflected adjectival suffix folds its glue and labels past`() {
        val tokens = listOf(
            jaToken("歩き", JaCategory.VERB, dict = "歩く", infl = "連用形-一般"),
            jaToken("づらかっ", JaCategory.ADJ_I, dict = "づらい", norm = "辛い", infl = "連用形-促音便"),
            jaToken("た", JaCategory.AUX),
        )
        val result = glob(tokens, knownForms = setOf("歩く", "づらい"))
        assertEquals(listOf("歩く", "づらい"), result.map { it.lookupForm })
        assertEquals("づらかった", result[1].surface)
        assertEquals(listOf(InflectionTag.TA), result[1].inflections)
    }

    @Test
    fun `compound with its own entry still fuses over the suffix span`() {
        val tokens = listOf(
            jaToken("読み", JaCategory.VERB, dict = "読む", reading = "ヨミ", infl = "連用形-一般"),
            jaToken("づらい", JaCategory.ADJ_I, norm = "辛い", reading = "ヅライ", infl = "終止形-一般"),
        )
        val result = glob(
            tokens,
            knownPhrases = setOf("読みづらい"),
            knownForms = setOf("読む", "づらい"),
        )
        assertEquals(1, result.size)
        assertEquals("読みづらい", result[0].lookupForm)
        assertEquals("よみづらい", result[0].reading)
    }

    @Test
    fun `inflected compound reaches its entry through the suffix lemma`() {
        // 読みづらかった: the window ends at an inflected ADJ_I suffix, so the
        // lemma-variant candidate 読み+づらい fires and matches the entry —
        // before the fix, 接尾辞 mapped to OTHER and this window was skipped.
        val tokens = listOf(
            jaToken("読み", JaCategory.VERB, dict = "読む", infl = "連用形-一般"),
            jaToken("づらかっ", JaCategory.ADJ_I, dict = "づらい", norm = "辛い", infl = "連用形-促音便"),
            jaToken("た", JaCategory.AUX),
        )
        val result = glob(tokens, knownPhrases = setOf("読みづらい"), knownForms = setOf("読む", "づらい"))
        assertEquals(1, result.size)
        assertEquals("読みづらい", result[0].lookupForm)
        assertEquals("読みづらかった", result[0].surface)
        assertEquals(listOf(InflectionTag.TA), result[0].inflections)
    }

    // ── Kana nominal/verbal suffixes (ぶり, めく) ─────────────────────────
    // The Codex-review class: kana suffixes whose entries are meaning-bearing
    // must emit spans when the host compound is NOT its own entry.

    @Test
    fun `kana nominal suffix in a non-entry compound gets its own span`() {
        // 五年ぶり: not a JMdict entry, so nothing fuses — ぶり must survive
        // as a span resolving its own entry (振り【ぶり】 1361140).
        val tokens = listOf(
            jaToken("五", JaCategory.NOUN, dict = "5", reading = "ゴ"),
            jaToken("年", JaCategory.NOUN, reading = "ネン"),
            jaToken("ぶり", JaCategory.NOUN, norm = "振り", reading = "ブリ"),
        )
        val result = glob(tokens, knownForms = setOf("年", "ぶり", "振り"))
        assertEquals(listOf("年", "ぶり"), result.map { it.lookupForm }.takeLast(2))
        assertEquals("ぶり", result.last().surface)
        assertEquals("ぶり", result.last().reading)
    }

    @Test
    fun `kana verbal suffix conjugates like a verb`() {
        // 謎めいた: めい (dict めく) folds た and labels past.
        val tokens = listOf(
            jaToken("謎", JaCategory.NOUN, reading = "ナゾ"),
            jaToken("めい", JaCategory.VERB, dict = "めく", infl = "連用形-イ音便"),
            jaToken("た", JaCategory.AUX),
        )
        val result = glob(tokens, knownForms = setOf("謎", "めく"))
        assertEquals(listOf("謎", "めく"), result.map { it.lookupForm })
        assertEquals("めいた", result[1].surface)
        assertEquals(listOf(InflectionTag.TA), result[1].inflections)
    }

    @Test
    fun `inflected verbal-suffix compound reaches its whole-word entry`() {
        // When 謎めく IS an entry, the lemma-variant window fuses the whole
        // thing — possible only because めい is content and startsConjugation.
        val tokens = listOf(
            jaToken("謎", JaCategory.NOUN, reading = "ナゾ"),
            jaToken("めい", JaCategory.VERB, dict = "めく", infl = "連用形-イ音便"),
            jaToken("た", JaCategory.AUX),
        )
        val result = glob(tokens, knownPhrases = setOf("謎めく"), knownForms = setOf("謎", "めく"))
        assertEquals(1, result.size)
        assertEquals("謎めく", result[0].lookupForm)
        assertEquals("謎めいた", result[0].surface)
        assertEquals(listOf(InflectionTag.TA), result[0].inflections)
    }

    // ── Existing-behavior preservation ───────────────────────────────────

    @Test
    fun `kana idiom globs into one span consuming all tokens`() {
        val tokens = listOf(
            jaToken("か", JaCategory.PARTICLE),
            jaToken("も", JaCategory.PARTICLE),
            jaToken("しれ", JaCategory.VERB, dict = "しれる"),
            jaToken("ない", JaCategory.AUX),
        )
        val result = glob(tokens, knownPhrases = setOf("かもしれない"))
        assertEquals(1, result.size)
        assertEquals("かもしれない", result[0].surface)
        assertEquals("かもしれない", result[0].lookupForm)
        assertNull(result[0].reading)
    }

    @Test
    fun `single token lemma fallback folds trailing aux into surface`() {
        val tokens = listOf(
            jaToken("使わ", JaCategory.VERB, dict = "使う", reading = "ツカワ"),
            jaToken("ない", JaCategory.AUX),
        )
        val result = glob(tokens, knownForms = setOf("使う"))
        assertEquals(1, result.size)
        assertEquals("使わない", result[0].surface)
        assertEquals("使う", result[0].lookupForm)
        assertEquals("つかわ", result[0].reading)
    }

    @Test
    fun `normalizedForm wins when only it resolves`() {
        val tokens = listOf(jaToken("キミ", JaCategory.PRONOUN, dict = "キミ", norm = "君"))
        val result = glob(tokens, knownForms = setOf("君"))
        assertEquals("君", result[0].lookupForm)
    }

    @Test
    fun `dictionaryForm preferred over normalizedForm when both resolve`() {
        val tokens = listOf(jaToken("辿り", JaCategory.VERB, dict = "辿る", norm = "たどる"))
        val result = glob(tokens, knownForms = setOf("辿る", "たどる"))
        assertEquals("辿る", result[0].lookupForm)
    }

    @Test
    fun `kana run not globbed when absent from known phrases`() {
        val tokens = listOf(
            jaToken("ここ", JaCategory.PRONOUN),
            jaToken("の", JaCategory.PARTICLE),
        )
        val result = glob(tokens, knownForms = setOf("ここ"))
        assertEquals(listOf("ここ"), result.map { it.surface })
    }

    @Test
    fun `ordinary sentence emits content words and skips particles`() {
        val tokens = listOf(
            jaToken("私", JaCategory.PRONOUN),
            jaToken("は", JaCategory.PARTICLE),
            jaToken("本", JaCategory.NOUN),
            jaToken("を", JaCategory.PARTICLE),
            jaToken("読む", JaCategory.VERB),
        )
        val result = glob(tokens, knownForms = setOf("私", "本", "読む"))
        assertEquals(listOf("私", "本", "読む"), result.map { it.lookupForm })
    }

    @Test
    fun `longest phrase wins over shorter at same start`() {
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なる", JaCategory.VERB),
        )
        val result = glob(tokens, knownPhrases = setOf("気に", "気になる"))
        assertEquals(1, result.size)
        assertEquals("気になる", result[0].lookupForm)
    }

    @Test
    fun `ascii and single-hiragana tokens are not lookup-worthy`() {
        val tokens = listOf(
            jaToken("A", JaCategory.NOUN),
            jaToken("B", JaCategory.NOUN),
            jaToken("て", JaCategory.NOUN),
        )
        val result = glob(tokens)
        assertTrue(result.isEmpty())
        // The pure-ASCII join is filtered out of candidate generation too
        // (mixed joins like "Bて" stay — only all-ASCII is excluded).
        assertTrue(phraseCandidatesFor(tokens).none { it.lookupForm == "AB" })
    }

    @Test
    fun `exact candidates carry identity span bookkeeping`() {
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なる", JaCategory.VERB),
        )
        for (c in phraseCandidatesFor(tokens)) {
            assertEquals(c.lookupForm, c.surface)
            assertEquals(c.windowLen, c.tokensConsumed)
            assertEquals(false, c.isVariant)
        }
    }

    // ── Phase A: imported-dictionary phrase oracle ───────────────────────

    @Test
    fun `oracle-confirmed kanji phrase globs like a JMdict one`() {
        // The matcher is gate-agnostic: a phrase the oracle confirmed lands
        // in knownPhrases exactly like a JMdict hit.
        val tokens = listOf(
            jaToken("背", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("腹", JaCategory.NOUN),
        )
        val result = glob(tokens, knownPhrases = setOf("背に腹"))
        assertEquals(1, result.size)
        assertEquals("背に腹", result[0].lookupForm)
        assertNull(result[0].reading)
    }

    @Test
    fun `oracle eligibility requires kanji and a JMdict miss`() {
        val known = setOf("気になる")
        assertTrue(DictionaryManager.oracleEligible("背に腹", known))
        // Kana-only join: never offered (no rank_score analog on imports).
        assertEquals(false, DictionaryManager.oracleEligible("かもしれない", known))
        assertEquals(false, DictionaryManager.oracleEligible("には", known))
        // Already accepted by JMdict: nothing to ask the oracle.
        assertEquals(false, DictionaryManager.oracleEligible("気になる", known))
    }

    // ── Phase B: lemma-variant candidates for inflected expressions ──────

    private val kiNiNatta = listOf(
        jaToken("気", JaCategory.NOUN),
        jaToken("に", JaCategory.PARTICLE),
        jaToken("なっ", JaCategory.VERB, dict = "なる"),
        jaToken("た", JaCategory.AUX),
    )

    @Test
    fun `inflected expression matches headword via lemma variant`() {
        val result = glob(kiNiNatta, knownPhrases = setOf("気になる"))
        assertEquals(1, result.size)
        assertEquals("気になった", result[0].surface)
        assertEquals("気になる", result[0].lookupForm)
        assertNull(result[0].reading)
    }

    @Test
    fun `lemma variant folds multiple trailing glue tokens`() {
        // 気になっていた: なっ + て + い…? Model the glue chain as PARTICLE+AUX+AUX.
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なっ", JaCategory.VERB, dict = "なる"),
            jaToken("て", JaCategory.PARTICLE),
            jaToken("た", JaCategory.AUX),
        )
        val result = glob(tokens, knownPhrases = setOf("気になる"))
        assertEquals(1, result.size)
        assertEquals("気になってた", result[0].surface)
        assertEquals("気になる", result[0].lookupForm)
    }

    @Test
    fun `exact phrase beats lemma variant at equal window length`() {
        // Both 気になっ (exact, hypothetically listed) and 気になる (variant)
        // are known for the same 3-token window: exact must win.
        val result = glob(kiNiNatta, knownPhrases = setOf("気になっ", "気になる"))
        assertEquals("気になっ", result[0].lookupForm)
    }

    @Test
    fun `longer variant beats shorter exact at same start`() {
        // 気に (exact, n=2) vs 気になる (variant, n=3): longest window wins.
        val result = glob(kiNiNatta, knownPhrases = setOf("気に", "気になる"))
        assertEquals("気になる", result[0].lookupForm)
        assertEquals("気になった", result[0].surface)
    }

    @Test
    fun `bare inflected verb never becomes a phrase`() {
        // 食べた = 食べ(VERB) + た(AUX glue): no window ENDS at a content
        // token, so no variant candidate exists; single-token fallback runs.
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("た", JaCategory.AUX),
        )
        assertTrue(phraseCandidatesFor(tokens).none { it.isVariant })
        val result = glob(tokens, knownForms = setOf("食べる"))
        assertEquals(1, result.size)
        assertEquals("食べた", result[0].surface)
        assertEquals("食べる", result[0].lookupForm)
    }

    @Test
    fun `variant window must start at a content token`() {
        // 遠慮(は)いらない: a variant starting at the particle は would fuse
        // into はいる (入る) — a particle-swallowing misglob. No variant may
        // start mid-grammar; exact joins are unaffected.
        val tokens = listOf(
            jaToken("遠慮", JaCategory.NOUN),
            jaToken("は", JaCategory.PARTICLE),
            jaToken("いら", JaCategory.VERB, dict = "いる"),
            jaToken("ない", JaCategory.AUX),
        )
        assertTrue(phraseCandidatesFor(tokens)
            .none { it.isVariant && it.startIndex == 1 })
        val result = glob(tokens, knownPhrases = setOf("はいる"), knownForms = setOf("遠慮", "いる"))
        assertEquals(listOf("遠慮", "いる"), result.map { it.lookupForm })
        assertEquals(listOf("遠慮", "いらない"), result.map { it.surface })
    }

    @Test
    fun `stem-final window produces no variant`() {
        // 方が良さそうだ: 良さ is the 語幹 (bare stem) of 良い awaiting its
        // continuation そう. A lemma variant would emit 方が良い with a span
        // boundary inside the derived word 良さそう. Blocked on 活用形.
        val tokens = listOf(
            jaToken("方", JaCategory.NOUN),
            jaToken("が", JaCategory.PARTICLE),
            JaToken(
                surface = "良さ", begin = 0, end = 2, category = JaCategory.ADJ_I,
                dictionaryForm = "良い", normalizedForm = "良い", reading = "ヨサ",
                isOov = false, inflectionForm = "語幹-一般",
            ),
            jaToken("そう", JaCategory.ADJ_NA),
            jaToken("だ", JaCategory.AUX),
        )
        assertTrue(phraseCandidatesFor(tokens).none { it.isVariant })
        val result = glob(tokens, knownPhrases = setOf("方が良い"), knownForms = setOf("方", "良い"))
        assertTrue(result.none { it.lookupForm == "方が良い" })
    }

    @Test
    fun `complete inflection forms stay variant-eligible without glue`() {
        // 命令形 (戻ってこい) and 連用形 (思慮深く生き…) fold zero glue but are
        // complete usages — the 語幹 guard must not block them.
        val imperative = listOf(
            jaToken("戻っ", JaCategory.VERB, dict = "戻る"),
            jaToken("て", JaCategory.PARTICLE),
            JaToken(
                surface = "こい", begin = 0, end = 2, category = JaCategory.VERB,
                dictionaryForm = "くる", normalizedForm = "くる", reading = "コイ",
                isOov = false, inflectionForm = "命令形-一般",
            ),
        )
        assertEquals(
            "戻ってくる",
            glob(imperative, knownPhrases = setOf("戻ってくる"))[0].lookupForm,
        )
        val renyokei = listOf(
            jaToken("思慮", JaCategory.NOUN),
            JaToken(
                surface = "深く", begin = 0, end = 2, category = JaCategory.ADJ_I,
                dictionaryForm = "深い", normalizedForm = "深い", reading = "フカク",
                isOov = false, inflectionForm = "連用形-一般",
            ),
            jaToken("生き", JaCategory.VERB, dict = "生きる"),
        )
        val result = glob(renyokei, knownPhrases = setOf("思慮深い"), knownForms = setOf("生きる"))
        assertEquals(listOf("思慮深い", "生きる"), result.map { it.lookupForm })
        assertEquals("思慮深く", result[0].surface)
    }

    @Test
    fun `uninflected window end produces no variant`() {
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なる", JaCategory.VERB),
        )
        assertTrue(phraseCandidatesFor(tokens).none { it.isVariant })
    }

    @Test
    fun `variant consumes window plus glue when matched`() {
        // Tokens after the folded glue still get processed.
        val tokens = kiNiNatta + listOf(jaToken("理由", JaCategory.NOUN))
        val result = glob(tokens, knownPhrases = setOf("気になる"), knownForms = setOf("理由"))
        assertEquals(listOf("気になる", "理由"), result.map { it.lookupForm })
        assertEquals(listOf("気になった", "理由"), result.map { it.surface })
    }

    // ── Inflection labeling (feature B) ──────────────────────────────────
    // Tags ride on TokenWithReading.inflections, derived by
    // JapaneseInflectionAnalyzer from the folded glue chain + the final
    // morpheme's 活用形. These assert that the re-glob hands the analyzer the
    // right stem and glue; JapaneseInflectionAnalyzerTest pins the table itself
    // against chains copied from the JVM survey dump.

    @Test
    fun `causative te-form yields ordered tags`() {
        // 言わせて = 言わ(言う) + せ(せる) + て
        val tokens = listOf(
            jaToken("言わ", JaCategory.VERB, dict = "言う"),
            jaToken("せ", JaCategory.AUX, dict = "せる"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
        )
        val r = glob(tokens, knownForms = setOf("言う"))
        assertEquals("言わせて", r[0].surface)
        assertEquals("言う", r[0].lookupForm)
        assertEquals(listOf(InflectionTag.CAUSATIVE, InflectionTag.TE), r[0].inflections)
    }

    @Test
    fun `negative and past from a single aux`() {
        assertEquals(
            listOf(InflectionTag.NEGATIVE),
            glob(
                listOf(jaToken("使わ", JaCategory.VERB, dict = "使う"), jaToken("ない", JaCategory.AUX)),
                knownForms = setOf("使う"),
            )[0].inflections,
        )
        assertEquals(
            listOf(InflectionTag.TA),
            glob(
                listOf(jaToken("食べ", JaCategory.VERB, dict = "食べる"), jaToken("た", JaCategory.AUX)),
                knownForms = setOf("食べる"),
            )[0].inflections,
        )
    }

    @Test
    fun `polite negative past keeps each auxiliary in morpheme order`() {
        // 食べませんでした = 食べ + ませ(ます) + ん(ぬ, normalized ず) + でし(です) + た:
        // the ん after ます is the negative (-ます « negative), and ます and です are
        // distinct steps (-ます, -です).
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("ませ", JaCategory.AUX, dict = "ます"),
            jaToken("ん", JaCategory.AUX, dict = "ぬ", norm = "ず"),
            jaToken("でし", JaCategory.AUX, dict = "です"),
            jaToken("た", JaCategory.AUX),
        )
        assertEquals(
            listOf(InflectionTag.MASU, InflectionTag.NEGATIVE, InflectionTag.DESU, InflectionTag.TA),
            glob(tokens, knownForms = setOf("食べる"))[0].inflections,
        )
    }

    @Test
    fun `causative potential-or-passive past stack stays in morpheme order`() {
        // 食べさせられた = 食べ + させ(させる, 下一段-サ行) + られ(られる) + た: られる after
        // the ichidan させ reads potential or passive.
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("させ", JaCategory.AUX, dict = "させる", conjType = "下一段-サ行"),
            jaToken("られ", JaCategory.AUX, dict = "られる"),
            jaToken("た", JaCategory.AUX),
        )
        assertEquals(
            listOf(InflectionTag.CAUSATIVE, InflectionTag.POTENTIAL_OR_PASSIVE, InflectionTag.TA),
            glob(tokens, knownForms = setOf("食べる"))[0].inflections,
        )
    }

    @Test
    fun `bare imperative comes from the stem inflection form`() {
        // 食べろ = 命令形 with no auxiliary.
        val tokens = listOf(jaToken("食べろ", JaCategory.VERB, dict = "食べる", infl = "命令形-一般"))
        assertEquals(
            listOf(InflectionTag.IMPERATIVE),
            glob(tokens, knownForms = setOf("食べる"))[0].inflections,
        )
    }

    @Test
    fun `imperative survives a trailing sentence-final particle`() {
        // 食べろよ = 食べろ(命令形) + よ(sentence-final): the imperative is on the
        // stem, but the fold pulls よ into the glue chain. The analyzer must
        // scan past the untagged particle to the 命令形 rather than stop at よ.
        val tokens = listOf(
            jaToken("食べろ", JaCategory.VERB, dict = "食べる", infl = "命令形-一般"),
            jaToken("よ", JaCategory.PARTICLE),
        )
        val r = glob(tokens, knownForms = setOf("食べる"))
        assertEquals("食べろよ", r[0].surface)
        assertEquals(listOf(InflectionTag.IMPERATIVE), r[0].inflections)
    }

    @Test
    fun `conditional comes from the ba particle`() {
        val tokens = listOf(
            jaToken("言え", JaCategory.VERB, dict = "言う", infl = "仮定形-一般"),
            jaToken("ば", JaCategory.PARTICLE),
        )
        assertEquals(
            listOf(InflectionTag.BA),
            glob(tokens, knownForms = setOf("言う"))[0].inflections,
        )
    }

    @Test
    fun `non-conjugational particle in the span is not a tag`() {
        // 食べても = 食べ + て(接続助詞) + も: the trailing も folds into the surface
        // span but reads nothing. (Sudachi keeps ては whole, so 言わせては never
        // reaches the analyzer as て + は.)
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("も", JaCategory.PARTICLE),
        )
        val r = glob(tokens, knownForms = setOf("食べる"))
        assertEquals("食べても", r[0].surface)
        assertEquals(listOf(InflectionTag.TE), r[0].inflections)
    }

    @Test
    fun `uninflected content word has no tags`() {
        assertEquals(
            emptyList<InflectionTag>(),
            glob(listOf(jaToken("本", JaCategory.NOUN)), knownForms = setOf("本"))[0].inflections,
        )
    }

    @Test
    fun `variant phrase carries the trailing inflection`() {
        // 気になった → headword 気になる: the productive 〜た on the final verb is
        // tagged from the variant's stem + glue (previously emitted nothing).
        val r = glob(kiNiNatta, knownPhrases = setOf("気になる"))
        assertEquals("気になる", r[0].lookupForm)
        assertEquals("気になった", r[0].surface)
        assertEquals(listOf(InflectionTag.TA), r[0].inflections)
    }

    @Test
    fun `exact frozen phrase stays untagged`() {
        // かもしれない matches whole (exact, not a variant); its ない belongs to the
        // frozen idiom, not a productive negation — so no tag.
        val tokens = listOf(
            jaToken("か", JaCategory.PARTICLE),
            jaToken("も", JaCategory.PARTICLE),
            jaToken("しれ", JaCategory.VERB, dict = "しれる"),
            jaToken("ない", JaCategory.AUX),
        )
        val r = glob(tokens, knownPhrases = setOf("かもしれない"))
        assertEquals("かもしれない", r[0].lookupForm)
        assertEquals(emptyList<InflectionTag>(), r[0].inflections)
    }

    @Test
    fun `volitional comes from 意志推量形, not from a よう morpheme`() {
        // Sudachi keeps 食べよう whole, in 意志推量形; a separate よう morpheme
        // after a stem reads nothing.
        assertEquals(
            listOf(InflectionTag.VOLITIONAL),
            glob(
                listOf(jaToken("食べよう", JaCategory.VERB, dict = "食べる", infl = "意志推量形")),
                knownForms = setOf("食べる"),
            )[0].inflections,
        )
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("よう", JaCategory.AUX),
        )
        assertEquals(
            emptyList<InflectionTag>(),
            glob(tokens, knownForms = setOf("食べる"))[0].inflections,
        )
    }

    // ── Suspicion (conjugation-cut veto + function-run gate) ─────────────
    // Corpus-validated specimens (P5 500-line A/B, 2026-08-28): joins that
    // contradict Sudachi's parse must not fuse on a reading coincidence.

    private fun exactSuspicion(tokens: List<JaToken>, lookup: String): Suspicion? =
        phraseCandidatesFor(tokens).first { it.lookupForm == lookup && !it.isVariant }.suspicion

    @Test
    fun `glue after an incomplete stem is a conjugation cut - the teori specimen`() {
        // いただい|て|おり|ます — ており is 手織り's reading, but て is bound to
        // いただい's 連用形-イ音便. The join severs the conjugation. Chain from
        // seg-runs/s3/survey.json (いただいております): both verbs are 非自立可能,
        // and おり normalizes to おる.
        val tokens = listOf(
            jaToken("いただい", JaCategory.VERB, dict = "いただく", norm = "頂く", infl = "連用形-イ音便",
                conjType = "五段-カ行", aux = true),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("おり", JaCategory.VERB, dict = "おる", infl = "連用形-一般", conjType = "五段-ラ行", aux = true),
            jaToken("ます", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-マス"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "ており"))

        // End to end: even with ており in the membership set (JMdict has it),
        // admissibility drops the candidate, and the fold carries いただく
        // through the auxiliary おる to the end.
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = emptySet(), kanaNativeReadings = emptySet(),
        )
        assertTrue(admissible.none { it.lookupForm == "ており" })
        val r = reglobTokens(tokens, admissible, setOf("ており"), setOf("いただく", "おる"))
        assertEquals(listOf("いただく"), r.map { it.lookupForm })
        assertEquals("いただいております", r[0].surface)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.MASU), r[0].inflections)
    }

    @Test
    fun `join starting at a conjunctive particle is a conjugation cut`() {
        // 言われた|が|どう… — がどう (画道) must not steal the conjunctive が.
        val tokens = listOf(
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
            jaToken("が", JaCategory.PARTICLE, conj = true),
            jaToken("どう", JaCategory.ADVERB),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "がどう"))
    }

    @Test
    fun `conjunctive particle with no attachable left material is clean`() {
        // Postpositions can't attach across punctuation or a line start, so a
        // 接続助詞 there heads an expression: 、ていうか and line-initial
        // ていうか must stay fusable (JMdict 2848596).
        val afterComma = listOf(
            jaToken("、", JaCategory.OTHER, punct = true),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("いう", JaCategory.VERB, infl = "終止形-一般"),
            jaToken("か", JaCategory.PARTICLE),
        )
        assertNull(exactSuspicion(afterComma, "ていうか"))

        val lineInitial = afterComma.drop(1)
        assertNull(exactSuspicion(lineInitial, "ていうか"))
    }

    @Test
    fun `clipped fragment starting mid-conjugation is still vetoed via the mirror shape`() {
        // OCR can clip a wrapped sentence so the line STARTS at the て of
        // ております. The 接続助詞-start exemption must not reopen ており→手織り
        // there: おり is an incomplete stem with ます just outside the window,
        // which is a conjugation cut on its own.
        val tokens = listOf(
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("おり", JaCategory.VERB, dict = "おる", infl = "連用形-一般"),
            jaToken("ます", JaCategory.AUX, infl = "終止形-一般"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "ており"))
    }

    @Test
    fun `join starting inside a te-form glue run is a conjugation cut`() {
        // 言っ|て|た|な — たな (棚) starts right after the 接続助詞 て.
        val tokens = listOf(
            jaToken("言っ", JaCategory.VERB, dict = "言う", infl = "連用形-促音便"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
            jaToken("な", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "たな"))
    }

    @Test
    fun `incomplete stem plus its own glue is a conjugation cut`() {
        // 勝利|し|た — した (下/舌) is an inflection surface of する, not a word.
        val tokens = listOf(
            jaToken("勝利", JaCategory.NOUN),
            jaToken("し", JaCategory.VERB, dict = "する", infl = "連用形-一般"),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "した"))
        // The kanji-bearing 勝利した window is NOT suspect (content start, ends
        // at glue): it can still fuse, but only via a headword match.
        assertNull(exactSuspicion(tokens, "勝利した"))
    }

    @Test
    fun `stealing a stem from its upcoming glue is a conjugation cut - mirror shape`() {
        // こんな|こと|し|て|くる — ことし (今年) would strip し from its て.
        // The くる after て is the auxiliary 来る the fold attaches; the
        // window ends before it, so the suspicion is unchanged.
        val tokens = listOf(
            jaToken("こと", JaCategory.NOUN),
            jaToken("し", JaCategory.VERB, dict = "する", infl = "連用形-一般"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("くる", JaCategory.VERB, norm = "来る", infl = "終止形-一般", aux = true),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "ことし"))
    }

    @Test
    fun `kamoshirenai after a complete predicate is clean`() {
        // 言わ|れる|か|も|しれ|ない — れる is 終止形: nothing is severed, so the
        // expression stays matchable exactly as before the veto.
        val tokens = listOf(
            jaToken("言わ", JaCategory.VERB, dict = "言う", infl = "未然形-一般"),
            jaToken("れる", JaCategory.AUX, infl = "終止形-一般"),
            jaToken("か", JaCategory.PARTICLE),
            jaToken("も", JaCategory.PARTICLE),
            jaToken("しれ", JaCategory.VERB, dict = "しれる", infl = "未然形-一般"),
            jaToken("ない", JaCategory.AUX, infl = "終止形-一般"),
        )
        assertNull(exactSuspicion(tokens, "かもしれない"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = emptySet(), kanaNativeReadings = emptySet(),
        )
        val r = reglobTokens(tokens, admissible, setOf("かもしれない"), setOf("言う"))
        assertTrue(r.any { it.lookupForm == "かもしれない" })
    }

    @Test
    fun `function run fuses only as a kana-native entry`() {
        // だ|から|安心 — だから is all function morphemes. As a kanji-less JMdict
        // entry it fuses; the same shape matching a kanji word's reading
        // (と+の → 殿) must not.
        val tokens = listOf(
            jaToken("だ", JaCategory.AUX, infl = "終止形-一般"),
            jaToken("から", JaCategory.PARTICLE),
            jaToken("安心", JaCategory.NOUN),
        )
        assertEquals(Suspicion.FUNCTION_RUN, exactSuspicion(tokens, "だから"))
        val candidates = phraseCandidatesFor(tokens)
        val kanaNative = admissiblePhraseCandidates(candidates, emptySet(), setOf("だから"))
        assertTrue(kanaNative.any { it.lookupForm == "だから" })
        val notKanaNative = admissiblePhraseCandidates(candidates, emptySet(), emptySet())
        assertTrue(notKanaNative.none { it.lookupForm == "だから" })
    }

    @Test
    fun `lemma variants are never suspect`() {
        // 気になった → 気になる: the variant mechanism deliberately lemma-swaps
        // the final stem and folds its glue — that is not a cut. The EXACT
        // window 気になっ over the same tokens IS suspect (mirror shape).
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なっ", JaCategory.VERB, dict = "なる", infl = "連用形-促音便"),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
        )
        val variant = phraseCandidatesFor(tokens).first { it.isVariant && it.lookupForm == "気になる" }
        assertNull(variant.suspicion)
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "気になっ"))
    }

    @Test
    fun `conjugation cut (auxiliary glue) stays admissible on a bare headword`() {
        // した = し(する,連用形)+た(AUX) — auxiliary glue, not CONVERB_CUT.
        val tokens = listOf(
            jaToken("勝利", JaCategory.NOUN),
            jaToken("し", JaCategory.VERB, dict = "する", infl = "連用形-一般"),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "した"))
        val candidates = phraseCandidatesFor(tokens)
        val admissible = admissiblePhraseCandidates(candidates, headwords = setOf("した"), kanaNativeReadings = emptySet())
        assertTrue(admissible.any { it.lookupForm == "した" })
    }

    @Test
    fun `auxiliary-glue derived verbs keep fusing without a priority tag`() {
        // The priority floor must not reach 助動詞 chains deriving real words.
        val shiraseru = listOf(
            jaToken("知ら", JaCategory.VERB, dict = "知る", infl = "未然形-一般"),
            jaToken("せる", JaCategory.AUX, dict = "せる"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(shiraseru, "知らせる"))
        assertEquals(
            "知らせる",
            glob(shiraseru, knownPhrases = setOf("知らせる"))[0].lookupForm,
        )

        val kudaranai = listOf(
            jaToken("下ら", JaCategory.VERB, dict = "下る", infl = "未然形-一般"),
            jaToken("ない", JaCategory.AUX, dict = "ない"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(kudaranai, "下らない"))
        assertEquals(
            "下らない",
            glob(kudaranai, knownPhrases = setOf("下らない"))[0].lookupForm,
        )

        // 済み+ませ+ん: an AUX anywhere in the glue run stays CONJUGATION_CUT.
        val sumimasen = listOf(
            jaToken("済み", JaCategory.VERB, dict = "済む", infl = "連用形-一般"),
            jaToken("ませ", JaCategory.AUX, dict = "ます"),
            jaToken("ん", JaCategory.AUX, dict = "ぬ"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(sumimasen, "済みません"))
        assertEquals(
            "済みません",
            glob(sumimasen, knownPhrases = setOf("済みません"))[0].lookupForm,
        )
    }

    @Test
    fun `oshite fossilized adverb needs priority to override the te-form fallback`() {
        // 押し(連用形)+て is PARTICLE-only glue: CONVERB_CUT, needing priority.
        val tokens = listOf(
            jaToken("押し", JaCategory.VERB, dict = "押す", infl = "連用形-一般"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
        )
        assertEquals(Suspicion.CONVERB_CUT, exactSuspicion(tokens, "押して"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = setOf("押して"), kanaNativeReadings = emptySet(),
        )
        assertTrue("without a priority tag, 押して stays inadmissible",
            admissible.none { it.lookupForm == "押して" })
        val r = reglobTokens(tokens, admissible, setOf("押して"), setOf("押す"))
        assertEquals(listOf("押す"), r.map { it.lookupForm })
        assertEquals("押して", r[0].surface)
    }

    @Test
    fun `a priority-tagged converb headword clears the floor`() {
        val tokens = listOf(
            jaToken("従っ", JaCategory.VERB, dict = "従う", infl = "連用形-促音便"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
        )
        assertEquals(Suspicion.CONVERB_CUT, exactSuspicion(tokens, "従って"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = setOf("従って"), kanaNativeReadings = emptySet(),
            priorityHeadwords = setOf("従って"),
        )
        assertTrue(admissible.any { it.lookupForm == "従って" })
    }

    // ── Auxiliary fold ───────────────────────────────────────────────────
    // A verb span continues through an auxiliary verb after て/で, through the
    // adjective 無い after a 連用形 and through the appearance stem そう. Unless a
    // comment names another source, each chain is copied from
    // seg-runs/s3/survey.json (Sudachi 0.7.4, mode A, the pack's
    // system_core.dic); "CLI" names Sudachi's command line on the same
    // dictionary, which also gave the katakana readings where a test reads one.

    private fun spans(
        tokens: List<JaToken>,
        knownPhrases: Set<String> = emptySet(),
        knownForms: Set<String> = emptySet(),
        fold: Boolean = true,
    ) = reglobSpans(tokens, phraseCandidatesFor(tokens, fold), knownPhrases, knownForms, fold)

    /** 飲んでいなかった. */
    private val nondeInakatta = listOf(
        jaToken("飲ん", JaCategory.VERB, dict = "飲む", reading = "ノン", infl = "連用形-撥音便", conjType = "五段-マ行"),
        jaToken("で", JaCategory.PARTICLE, reading = "デ", conj = true),
        jaToken("い", JaCategory.VERB, dict = "いる", norm = "居る", reading = "イ", infl = "未然形-一般",
            conjType = "上一段-ア行", aux = true),
        jaToken("なかっ", JaCategory.AUX, dict = "ない", reading = "ナカッ", infl = "連用形-促音便", conjType = "助動詞-ナイ"),
        jaToken("た", JaCategory.AUX, reading = "タ", infl = "終止形-一般", conjType = "助動詞-タ"),
    )

    /** 持っていってしまった. */
    private val motteItteShimatta = listOf(
        jaToken("持っ", JaCategory.VERB, dict = "持つ", infl = "連用形-促音便", conjType = "五段-タ行"),
        jaToken("て", JaCategory.PARTICLE, conj = true),
        jaToken("いっ", JaCategory.VERB, dict = "いく", norm = "行く", infl = "連用形-促音便", conjType = "五段-カ行", aux = true),
        jaToken("て", JaCategory.PARTICLE, conj = true),
        jaToken("しまっ", JaCategory.VERB, dict = "しまう", norm = "仕舞う", infl = "連用形-促音便",
            conjType = "五段-ワア行", aux = true),
        jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
    )

    private val tabe = jaToken("食べ", JaCategory.VERB, dict = "食べる", infl = "連用形-一般", conjType = "下一段-バ行")
    private val ike = jaToken("いけ", JaCategory.VERB, dict = "いける", norm = "行く", infl = "未然形-一般",
        conjType = "下一段-カ行", aux = true)
    private val naiAux = jaToken("ない", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-ナイ")
    private val iruAux = jaToken("いる", JaCategory.VERB, norm = "居る", infl = "終止形-一般", conjType = "上一段-ア行", aux = true)
    private val te = jaToken("て", JaCategory.PARTICLE, conj = true)

    @Test
    fun `an auxiliary verb after て joins the verb's span with its own glue`() {
        val r = spans(nondeInakatta, knownForms = setOf("飲む", "いる"))
        val s = r.single()
        assertEquals(0, s.tokenStart)
        assertEquals(5, s.tokenCount)
        assertEquals("飲んでいなかった", s.surface)
        assertEquals("飲む", s.lookupForm)
        assertEquals("のん", s.reading)
        assertEquals(
            listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.NEGATIVE, InflectionTag.TA),
            s.inflections,
        )
        // Folding off (member mode) leaves the two spans of before.
        assertEquals(
            listOf("飲んで", "いなかった"),
            spans(nondeInakatta, knownForms = setOf("飲む", "いる"), fold = false).map { it.surface },
        )
    }

    @Test
    fun `ては is one morpheme and keeps the auxiliary its own word`() {
        val tokens = listOf(tabe, jaToken("ては", JaCategory.PARTICLE, conj = true), ike, naiAux)
        val r = spans(tokens, knownForms = setOf("食べる", "いける"))
        assertEquals(listOf("食べては", "いけない"), r.map { it.surface })
        // Looked up under いける itself, the auxiliary's span does not repeat
        // its potential.
        assertEquals(listOf(InflectionTag.NEGATIVE), r[1].inflections)
    }

    @Test
    fun `ちゃ normalizes to て but does not attach an auxiliary`() {
        // CLI: 食べちゃいけない. The predicate reads the particle's surface.
        val tokens = listOf(tabe, jaToken("ちゃ", JaCategory.PARTICLE, norm = "て", conj = true), ike, naiAux)
        assertEquals(
            listOf("食べちゃ", "いけない"),
            spans(tokens, knownForms = setOf("食べる", "いける")).map { it.surface },
        )
    }

    @Test
    fun `a particle between て and the auxiliary keeps them apart`() {
        // CLI: 食べてもいる.
        val temo = listOf(tabe, te, jaToken("も", JaCategory.PARTICLE), iruAux)
        assertEquals(listOf("食べても", "いる"), spans(temo, knownForms = setOf("食べる", "いる")).map { it.surface })
        // 食べてばかりいる (replaces 家にいる, where no fold ever starts).
        val tebakari = listOf(tabe, te, jaToken("ばかり", JaCategory.PARTICLE), iruAux)
        val r = spans(tebakari, knownForms = setOf("食べる", "いる"))
        assertEquals(listOf("食べてばかり", "いる"), r.map { it.surface })
        assertEquals(listOf(InflectionTag.TE), r[0].inflections)
    }

    @Test
    fun `an auxiliary-capable stem folds like any verb`() {
        // 見てみる: 見 is 非自立可能 too; the fold is positional.
        val tokens = listOf(
            jaToken("見", JaCategory.VERB, dict = "見る", infl = "連用形-一般", conjType = "上一段-マ行", aux = true),
            te,
            jaToken("みる", JaCategory.VERB, norm = "見る", infl = "終止形-一般", conjType = "上一段-マ行", aux = true),
        )
        val s = spans(tokens, knownForms = setOf("見る", "みる")).single()
        assertEquals("見てみる", s.surface)
        assertEquals("見る", s.lookupForm)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.MIRU), s.inflections)
    }

    @Test
    fun `the fold continues through a further て and auxiliary`() {
        val s = spans(motteItteShimatta, knownForms = setOf("持つ", "いく", "しまう")).single()
        assertEquals(6, s.tokenCount)
        assertEquals("持つ", s.lookupForm)
        assertEquals(
            listOf(InflectionTag.TE, InflectionTag.IKU, InflectionTag.TE, InflectionTag.SHIMAU, InflectionTag.TA),
            s.inflections,
        )
    }

    @Test
    fun `くださる in 命令形 closes the chain with imperative`() {
        // 教えてください
        val tokens = listOf(
            jaToken("教え", JaCategory.VERB, dict = "教える", infl = "連用形-一般", conjType = "下一段-ア行"),
            te,
            jaToken("ください", JaCategory.VERB, dict = "くださる", norm = "下さる", infl = "命令形",
                conjType = "五段-ラ行", aux = true),
        )
        val s = spans(tokens, knownForms = setOf("教える", "くださる")).single()
        assertEquals("教えてください", s.surface)
        assertEquals(
            listOf(InflectionTag.TE, InflectionTag.KUDASARU, InflectionTag.IMPERATIVE),
            s.inflections,
        )
    }

    @Test
    fun `an auxiliary-capable verb off the allow-list stays its own word`() {
        // やってみせる: みせる is 非自立可能 but not an auxiliary the fold knows.
        val tokens = listOf(
            jaToken("やっ", JaCategory.VERB, dict = "やる", norm = "遣る", infl = "連用形-促音便",
                conjType = "五段-ラ行", aux = true),
            te,
            jaToken("みせる", JaCategory.VERB, norm = "見せる", infl = "終止形-一般", conjType = "下一段-サ行", aux = true),
        )
        assertEquals(
            listOf("やって", "みせる"),
            spans(tokens, knownForms = setOf("やる", "みせる")).map { it.surface },
        )
    }

    @Test
    fun `a potential auxiliary keeps its potential inside the fold`() {
        // 来て頂けます: the span is looked up under 来る, so 頂ける's potential
        // is information the headword does not carry.
        val tokens = listOf(
            jaToken("来", JaCategory.VERB, dict = "来る", infl = "連用形-一般", conjType = "カ行変格", aux = true),
            te,
            jaToken("頂け", JaCategory.VERB, dict = "頂ける", norm = "頂く", infl = "連用形-一般",
                conjType = "下一段-カ行", aux = true),
            jaToken("ます", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-マス"),
        )
        val s = spans(tokens, knownForms = setOf("来る", "頂ける")).single()
        assertEquals("来る", s.lookupForm)
        assertEquals(
            listOf(InflectionTag.TE, InflectionTag.ITADAKU, InflectionTag.POTENTIAL, InflectionTag.MASU),
            s.inflections,
        )
    }

    @Test
    fun `a phrase never starts at a folded auxiliary - the いるか specimen`() {
        // 知っているか: いるか is 海豚's reading. Unfolded, it took the
        // auxiliary and the question particle.
        val tokens = listOf(
            jaToken("知っ", JaCategory.VERB, dict = "知る", infl = "連用形-促音便", conjType = "五段-ラ行"),
            te,
            iruAux,
            jaToken("か", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "いるか"))
        val r = spans(tokens, knownPhrases = setOf("いるか"), knownForms = setOf("知る", "いる"))
        assertEquals(listOf("知っているか"), r.map { it.surface })
        assertEquals("知る", r[0].lookupForm)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.IRU), r[0].inflections)
        assertTrue(r.none { it.tokenStart == 2 })
        assertEquals(
            listOf("知って", "いるか"),
            spans(tokens, knownPhrases = setOf("いるか"), knownForms = setOf("知る", "いる"), fold = false)
                .map { it.surface },
        )
    }

    @Test
    fun `only an emitted span claims its fold`() {
        // Constructed stem: no dump has a verb whose dictionary form is not
        // lookup-worthy, but the guard must not swallow an auxiliary that no
        // span covers. て and いる as in the survey.
        val tokens = listOf(
            jaToken("ggっ", JaCategory.VERB, dict = "gg", infl = "連用形-促音便"),
            te,
            iruAux,
        )
        val r = spans(tokens, knownForms = setOf("いる"))
        assertEquals(listOf("いる"), r.map { it.lookupForm })
        assertEquals(2, r[0].tokenStart)
    }

    @Test
    fun `a lemma variant folds the auxiliary after its stem`() {
        // CLI: 気になっていた.
        val tokens = listOf(
            jaToken("気", JaCategory.NOUN),
            jaToken("に", JaCategory.PARTICLE),
            jaToken("なっ", JaCategory.VERB, dict = "なる", norm = "成る", infl = "連用形-促音便",
                conjType = "五段-ラ行", aux = true),
            te,
            jaToken("い", JaCategory.VERB, dict = "いる", norm = "居る", infl = "連用形-一般",
                conjType = "上一段-ア行", aux = true),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
        )
        val s = spans(tokens, knownPhrases = setOf("気になる"), knownForms = setOf("気", "なる", "いる")).single()
        assertTrue(s.isPhrase)
        assertEquals("気になる", s.lookupForm)
        assertEquals("気になっていた", s.surface)
        assertEquals(6, s.tokenCount)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.IRU, InflectionTag.TA), s.inflections)
    }

    @Test
    fun `a variant ending at an auxiliary folds the auxiliaries after it`() {
        // 持っていってしまった with 持っていく a headword: the variant's stem is
        // いっ, and its fold takes て, しまっ and た.
        val s = spans(motteItteShimatta, knownPhrases = setOf("持っていく"), knownForms = setOf("持つ")).single()
        assertTrue(s.isPhrase)
        assertEquals("持っていく", s.lookupForm)
        assertEquals(6, s.tokenCount)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.SHIMAU, InflectionTag.TA), s.inflections)
    }

    @Test
    fun `member mode keeps the auxiliary a unit of its own`() {
        // 連れて行く decomposed into its members, the headword excluded.
        val tokens = listOf(
            jaToken("連れ", JaCategory.VERB, dict = "連れる", infl = "連用形-一般", conjType = "下一段-ラ行"),
            te,
            jaToken("行く", JaCategory.VERB, infl = "終止形-一般", conjType = "五段-カ行", aux = true),
        )
        val forms = setOf("連れる", "行く")
        val unfolded = spans(tokens, knownForms = forms, fold = false)
        assertEquals(listOf("連れて", "行く"), unfolded.map { it.surface })
        assertEquals(
            listOf("連れる", "行く"),
            memberUnits(tokens, unfolded, expressionClass = false).map { it.lookupForm },
        )
        assertEquals(listOf(0 to 3), spans(tokens, knownForms = forms).map { it.tokenStart to it.tokenCount })
    }

    @Test
    fun `a converb before an auxiliary is an auxiliary cut and the fold wins`() {
        // CLI: 従っている. 従って is a priority headword (rank 2,000,000).
        val tokens = listOf(
            jaToken("従っ", JaCategory.VERB, dict = "従う", infl = "連用形-促音便", conjType = "五段-ワア行"),
            te,
            iruAux,
        )
        assertEquals(Suspicion.AUXILIARY_CUT, exactSuspicion(tokens, "従って"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = setOf("従って"), kanaNativeReadings = emptySet(),
            priorityHeadwords = setOf("従って"),
        )
        assertTrue(admissible.none { it.lookupForm == "従って" })
        val s = reglobSpans(tokens, admissible, setOf("従って"), setOf("従う", "いる")).single()
        assertEquals("従う", s.lookupForm)
        assertEquals(3, s.tokenCount)
        assertEquals(listOf(InflectionTag.TE, InflectionTag.IRU), s.inflections)
        // Without folding the converb keeps its priority rule.
        val unfolded = phraseCandidatesFor(tokens, foldAuxiliaries = false)
        assertEquals(Suspicion.CONVERB_CUT, unfolded.first { it.lookupForm == "従って" && !it.isVariant }.suspicion)
    }

    @Test
    fun `a converb window starting at an auxiliary stays a converb cut`() {
        // CLI: 書いて置いて. [置い, て] starts at the auxiliary 置く; shape 2 sees
        // it first, so it needs a priority headword, not just any headword.
        val tokens = listOf(
            jaToken("書い", JaCategory.VERB, dict = "書く", infl = "連用形-イ音便", conjType = "五段-カ行"),
            te,
            jaToken("置い", JaCategory.VERB, dict = "置く", infl = "連用形-イ音便", conjType = "五段-カ行", aux = true),
            te,
        )
        assertEquals(Suspicion.CONVERB_CUT, exactSuspicion(tokens, "置いて"))
        val s = spans(tokens, knownForms = setOf("書く", "置く")).single()
        assertEquals(listOf(InflectionTag.TE, InflectionTag.OKU, InflectionTag.TE), s.inflections)
    }

    @Test
    fun `contracted auxiliaries are glue and fold with or without the flag`() {
        val chains = listOf(
            // 飲んでる
            listOf(
                jaToken("飲ん", JaCategory.VERB, dict = "飲む", infl = "連用形-撥音便", conjType = "五段-マ行"),
                jaToken("でる", JaCategory.AUX, norm = "てる", infl = "連体形-一般", conjType = "下一段-ダ行"),
            ) to listOf(InflectionTag.IRU),
            // 食べちゃった
            listOf(
                tabe,
                jaToken("ちゃっ", JaCategory.AUX, dict = "ちゃう", infl = "連用形-促音便", conjType = "五段-ワア行"),
                jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
            ) to listOf(InflectionTag.CHAU, InflectionTag.TA),
            // 持ってった
            listOf(
                jaToken("持っ", JaCategory.VERB, dict = "持つ", infl = "連用形-促音便", conjType = "五段-タ行"),
                jaToken("てっ", JaCategory.AUX, dict = "てく", infl = "連用形-促音便", conjType = "五段-カ行"),
                jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
            ) to listOf(InflectionTag.IKU, InflectionTag.TA),
            // CLI: 食べてて
            listOf(
                tabe,
                jaToken("て", JaCategory.AUX, dict = "てる", infl = "連用形-一般", conjType = "下一段-タ行"),
                te,
            ) to listOf(InflectionTag.IRU, InflectionTag.TE),
        )
        for ((tokens, tags) in chains) {
            for (fold in listOf(true, false)) {
                val s = spans(tokens, knownForms = setOf(tokens[0].dictionaryForm), fold = fold).single()
                assertEquals(tokens.size, s.tokenCount)
                assertEquals(tags, s.inflections)
            }
        }
    }

    @Test
    fun `potential is not repeated under the potential's own headword`() {
        // 見れた: looked up under 見れる the potential is the word itself;
        // looked up under 見る (only the normalized form resolves) it is the
        // first step of the chain.
        val tokens = listOf(
            jaToken("見れ", JaCategory.VERB, dict = "見れる", norm = "見る", infl = "連用形-一般",
                conjType = "下一段-ラ行", aux = true),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
        )
        val own = spans(tokens, knownForms = setOf("見れる")).single()
        assertEquals("見れる", own.lookupForm)
        assertEquals(listOf(InflectionTag.TA), own.inflections)
        val base = spans(tokens, knownForms = setOf("見る")).single()
        assertEquals("見る", base.lookupForm)
        assertEquals(listOf(InflectionTag.POTENTIAL, InflectionTag.TA), base.inflections)
    }

    // ── 無い after a 連用形 ──────────────────────────────────────────────

    private val takaku = jaToken("高く", JaCategory.ADJ_I, dict = "高い", infl = "連用形-一般", conjType = "形容詞")
    private fun naiAdj(surface: String = "ない", infl: String = "終止形-一般") =
        jaToken(surface, JaCategory.ADJ_I, dict = "ない", norm = "無い", infl = infl, conjType = "形容詞", aux = true)

    @Test
    fun `無い after an adjective's 連用形 folds as negative`() {
        val s = spans(listOf(takaku, naiAdj()), knownForms = setOf("高い", "ない")).single()
        assertEquals("高くない", s.surface)
        assertEquals("高い", s.lookupForm)
        assertEquals(listOf(InflectionTag.NEGATIVE), s.inflections)

        // 高くなかった: 無い's own glue follows it.
        val past = listOf(
            takaku, naiAdj("なかっ", "連用形-促音便"),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ"),
        )
        val p = spans(past, knownForms = setOf("高い", "ない")).single()
        assertEquals(3, p.tokenCount)
        assertEquals(listOf(InflectionTag.NEGATIVE, InflectionTag.TA), p.inflections)
    }

    @Test
    fun `無い after an auxiliary's 連用形 folds as negative`() {
        // 食べたくない
        val tokens = listOf(
            tabe,
            jaToken("たく", JaCategory.AUX, dict = "たい", infl = "連用形-一般", conjType = "助動詞-タイ"),
            naiAdj(),
        )
        val s = spans(tokens, knownForms = setOf("食べる", "ない")).single()
        assertEquals("食べる", s.lookupForm)
        assertEquals(listOf(InflectionTag.TAI, InflectionTag.NEGATIVE), s.inflections)
    }

    @Test
    fun `は between a 連用形 and 無い still folds`() {
        // CLI: 高くはない
        val tokens = listOf(takaku, jaToken("は", JaCategory.PARTICLE), naiAdj())
        val s = spans(tokens, knownForms = setOf("高い", "ない")).single()
        assertEquals(3, s.tokenCount)
        assertEquals(listOf(InflectionTag.NEGATIVE), s.inflections)
    }

    @Test
    fun `いい after ても is another adjective and stays its own word`() {
        // 食べてもいい
        val tokens = listOf(
            tabe, te, jaToken("も", JaCategory.PARTICLE),
            jaToken("いい", JaCategory.ADJ_I, norm = "良い", infl = "終止形-一般", conjType = "形容詞", aux = true),
        )
        assertEquals(
            listOf("食べても", "いい"),
            spans(tokens, knownForms = setOf("食べる", "いい")).map { it.surface },
        )
    }

    @Test
    fun `無い after a 形状詞's で and は folds, ahead of the ではない reading`() {
        // 静かではない: the 形状詞 folds its だ (here で) and は, and 無い after
        // them. ではない is a reading in the pack; starting at the 形状詞's own
        // 助動詞 it is a previous-word cut, which a reading clears only right
        // after a closed phrase, and 静か is a single-token span.
        val tokens = listOf(
            jaToken("静か", JaCategory.ADJ_NA),
            jaToken("で", JaCategory.AUX, dict = "だ", infl = "連用形-一般", conjType = "助動詞-ダ"),
            jaToken("は", JaCategory.PARTICLE),
            naiAdj(),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "ではない"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = emptySet(), kanaNativeReadings = setOf("ではない"),
        )
        val s = reglobSpans(tokens, admissible, setOf("ではない"), setOf("静か", "ない")).single()
        assertEquals("静かではない", s.surface)
        assertEquals("静か", s.lookupForm)
        assertEquals(listOf(InflectionTag.NEGATIVE), s.inflections)
    }

    // ── The appearance stem そう ────────────────────────────────────────

    private fun auxStem(surface: String) = jaToken(surface, JaCategory.ADJ_NA, auxStem = true)
    private val daFinal = jaToken("だ", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-ダ")

    @Test
    fun `そう after a 連用形 folds as -そう, ahead of the そうだ phrase`() {
        // 食べそうだ: そうだ (a JMdict reading) would win at the folded そう.
        val s = spans(
            listOf(tabe, auxStem("そう"), daFinal),
            knownPhrases = setOf("そうだ"), knownForms = setOf("食べる", "そう"),
        ).single()
        assertEquals("食べそうだ", s.surface)
        assertEquals("食べる", s.lookupForm)
        assertEquals(listOf(InflectionTag.SOU), s.inflections)
    }

    @Test
    fun `そう after an adjective's 語幹 folds as -そう`() {
        // 高そう
        val tokens = listOf(
            jaToken("高", JaCategory.ADJ_I, dict = "高い", infl = "語幹-一般", conjType = "形容詞"),
            auxStem("そう"),
        )
        val s = spans(tokens, knownForms = setOf("高い", "そう")).single()
        assertEquals("高い", s.lookupForm)
        assertEquals(listOf(InflectionTag.SOU), s.inflections)
    }

    @Test
    fun `hearsay そう and よう after a complete form stay split`() {
        // 食べるそうだ and 食べるようだ: 食べる is 連体形.
        val taberu = jaToken("食べる", JaCategory.VERB, infl = "連体形-一般", conjType = "下一段-バ行")
        assertEquals(
            listOf("食べる", "そうだ"),
            spans(listOf(taberu, auxStem("そう"), daFinal), knownPhrases = setOf("そうだ"), knownForms = setOf("食べる"))
                .map { it.surface },
        )
        assertEquals(
            listOf("食べる", "ようだ"),
            spans(listOf(taberu, auxStem("よう"), daFinal), knownPhrases = setOf("ようだ"), knownForms = setOf("食べる"))
                .map { it.surface },
        )
    }

    // ── Phrases inside a fold's tail ────────────────────────────────────
    // The phrase check still runs at folded glue (the かもしれない overlap,
    // pinned above by the kamoshirenai test). These windows start inside a
    // fold and must not take its tail on a reading.

    @Test
    fun `glue after a folded そう is a previous-word cut - the にない specimen`() {
        // corpus: 穏便に中へ入れそうにないわ。 にない is 担い's reading; before the
        // fold the longer そうにない took the window at そう.
        val tokens = listOf(
            jaToken("入れ", JaCategory.VERB, dict = "入れる", infl = "連用形-一般", conjType = "下一段-ラ行"),
            auxStem("そう"),
            jaToken("に", JaCategory.AUX, dict = "だ", infl = "連用形-ニ", conjType = "助動詞-ダ"),
            naiAdj(),
            jaToken("わ", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "にない"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = emptySet(), kanaNativeReadings = emptySet(),
        )
        val s = reglobSpans(tokens, admissible, setOf("にない"), setOf("入れる")).single()
        assertEquals("入れそうにないわ", s.surface)
        assertEquals("入れる", s.lookupForm)
        assertEquals(5, s.tokenCount)
        assertEquals(listOf(InflectionTag.SOU, InflectionTag.NEGATIVE), s.inflections)
    }

    @Test
    fun `a window starting at a folded 無い or そう is a previous-word cut`() {
        // 高くないか: ないか is a reading in the pack.
        val takakuNaika = listOf(takaku, naiAdj(), jaToken("か", JaCategory.PARTICLE))
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(takakuNaika, "ないか"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(takakuNaika), headwords = emptySet(), kanaNativeReadings = emptySet(),
        )
        val s = reglobSpans(takakuNaika, admissible, setOf("ないか"), setOf("高い", "ない")).single()
        assertEquals("高くないか", s.surface)
        assertEquals(listOf(InflectionTag.NEGATIVE), s.inflections)
        // 食べそうだ: the window [そう, だ] starts at the folded そう.
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(listOf(tabe, auxStem("そう"), daFinal), "そうだ"))
    }

    // ── 形状詞 (na-adjectives) ───────────────────────────────────────────
    // A 形状詞 starts a conjugation: its span takes the だ family and other
    // glue after it, as a verb's does. Chains from seg-runs/s3/survey.json
    // unless a comment names the corpus or the CLI.

    private val shizuka = jaToken("静か", JaCategory.ADJ_NA)
    private fun da(surface: String, infl: String) =
        jaToken(surface, JaCategory.AUX, dict = "だ", infl = infl, conjType = "助動詞-ダ")
    private fun desu(surface: String, infl: String) =
        jaToken(surface, JaCategory.AUX, dict = "です", infl = infl, conjType = "助動詞-デス")
    private val taFinal = jaToken("た", JaCategory.AUX, infl = "終止形-一般", conjType = "助動詞-タ")

    @Test
    fun `a 形状詞 folds the だ family and reads its steps`() {
        val cases = listOf(
            listOf(shizuka, da("だっ", "連用形-促音便"), taFinal) to listOf(InflectionTag.TA),
            listOf(shizuka, desu("です", "終止形-一般")) to listOf(InflectionTag.DESU),
            listOf(shizuka, desu("でし", "連用形-一般"), taFinal) to listOf(InflectionTag.DESU, InflectionTag.TA),
            listOf(shizuka, da("じゃ", "連用形-融合"), naiAdj()) to listOf(InflectionTag.NEGATIVE),
            listOf(shizuka, da("じゃ", "連用形-融合"), naiAdj("なかっ", "連用形-促音便"), taFinal) to
                listOf(InflectionTag.NEGATIVE, InflectionTag.TA),
            listOf(shizuka, da("なら", "仮定形-一般")) to listOf(InflectionTag.NARA),
            listOf(shizuka, da("に", "連用形-ニ")) to emptyList(),
            listOf(shizuka, da("な", "連体形-一般")) to emptyList(),
            listOf(shizuka, da("だろう", "意志推量形")) to emptyList(),
            listOf(shizuka, desu("でしょう", "意志推量形")) to listOf(InflectionTag.DESU),
        )
        for ((tokens, tags) in cases) {
            val s = spans(tokens, knownForms = setOf("静か")).single()
            assertEquals(tokens.joinToString("") { it.surface }, s.surface)
            assertEquals("静か", s.lookupForm)
            assertEquals(tokens.size, s.tokenCount)
            assertEquals(s.surface, tags, s.inflections)
        }
        // 綺麗でした: looked up under its dictionary form, not the normalized 奇麗.
        val kirei = listOf(jaToken("綺麗", JaCategory.ADJ_NA, norm = "奇麗"), desu("でし", "連用形-一般"), taFinal)
        val k = spans(kirei, knownForms = setOf("綺麗", "奇麗")).single()
        assertEquals("綺麗", k.lookupForm)
        assertEquals(listOf(InflectionTag.DESU, InflectionTag.TA), k.inflections)
    }

    @Test
    fun `a reading phrase cannot take a 形状詞's own 助動詞`() {
        // 静かだった: だった is a reading (rank 1,000,000) that the function-run
        // tier would admit; at the 形状詞's だっ it is a previous-word cut, and
        // 静か is a single-token span, not a closed phrase.
        val dattaTokens = listOf(shizuka, da("だっ", "連用形-促音便"), taFinal)
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(dattaTokens, "だった"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(dattaTokens), headwords = emptySet(), kanaNativeReadings = setOf("だった"),
        )
        val s = reglobSpans(dattaTokens, admissible, setOf("だった"), setOf("静か")).single()
        assertEquals("静かだった", s.surface)
        assertEquals(listOf(InflectionTag.TA), s.inflections)
    }

    @Test
    fun `a particle after a 形状詞 folds but a phrase may still start there`() {
        // corpus: ワイルドの力. The span absorbs の, as a verb's does.
        val wild = listOf(
            jaToken("ワイルド", JaCategory.ADJ_NA),
            jaToken("の", JaCategory.PARTICLE),
            jaToken("力", JaCategory.NOUN),
        )
        assertEquals(
            listOf("ワイルドの", "力"),
            spans(wild, knownForms = setOf("ワイルド", "力")).map { it.surface },
        )
        // CLI: 大丈夫かな. A particle may end a 形状詞 (静かね), so a window
        // starting at one is not a cut: the かな reading keeps fusing, as
        // after 行く.
        val daijoubuKana = listOf(
            jaToken("大丈夫", JaCategory.ADJ_NA),
            jaToken("か", JaCategory.PARTICLE),
            jaToken("な", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.FUNCTION_RUN, exactSuspicion(daijoubuKana, "かな"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(daijoubuKana), headwords = emptySet(), kanaNativeReadings = setOf("かな"),
        )
        assertTrue(admissible.any { it.lookupForm == "かな" })
    }

    @Test
    fun `a noun does not fold its copula`() {
        // 学生だった: 学生 is a noun; だった stays its own (function-run) phrase.
        val tokens = listOf(jaToken("学生", JaCategory.NOUN), da("だっ", "連用形-促音便"), taFinal)
        assertEquals(Suspicion.FUNCTION_RUN, exactSuspicion(tokens, "だった"))
        val r = spans(tokens, knownPhrases = setOf("だった"), knownForms = setOf("学生"))
        assertEquals(listOf("学生", "だった"), r.map { it.surface })
        assertEquals(1, r[0].tokenCount)
        // 元気を出す: 元気 is 名詞 形状詞可能, a noun, so を stays outside its span.
        val genki = listOf(
            jaToken("元気", JaCategory.NOUN),
            jaToken("を", JaCategory.PARTICLE),
            jaToken("出す", JaCategory.VERB, infl = "終止形-一般", conjType = "五段-サ行", aux = true),
        )
        assertEquals(
            listOf("元気", "出す"),
            spans(genki, knownForms = setOf("元気", "出す")).map { it.surface },
        )
        assertEquals("元気を出す", spans(genki, knownPhrases = setOf("元気を出す")).single().lookupForm)
    }

    @Test
    fun `a 形状詞 never makes a lemma variant or trips a stem shape`() {
        // Its surface is its dictionary form, so no window ending at one
        // swaps a lemma; it has no 活用形, so neither stem shape reads it as
        // incomplete. corpus: コイツが悪質なのは.
        val tokens = listOf(
            jaToken("コイツ", JaCategory.PRONOUN),
            jaToken("が", JaCategory.PARTICLE),
            jaToken("悪質", JaCategory.ADJ_NA),
            da("な", "連体形-一般"),
            jaToken("の", JaCategory.PARTICLE),
            jaToken("は", JaCategory.PARTICLE),
        )
        assertTrue(phraseCandidatesFor(tokens).none { it.isVariant })
        assertNull(exactSuspicion(tokens, "悪質な"))       // shape 2 would start here
        assertNull(exactSuspicion(tokens, "コイツが悪質"))  // the mirror shape would end here
        assertNull(exactSuspicion(tokens, "が悪質"))
    }

    // ── After a closed phrase ───────────────────────────────────────────
    // A previous-word cut protects the conjugation of the word before the
    // window. When the walk has just closed a phrase span there, nothing is
    // left to protect: an exact phrase folds nothing after its window.

    @Test
    fun `a previous-word cut fuses right after a closed phrase - the なんじゃないか specimen`() {
        // corpus: なんじゃないか. なんじゃ closes as a phrase; ない after じゃ is
        // a fold target, so ないか (JMdict 2210280, "isn't it") is a
        // previous-word cut. Before the walk decided, it was dropped and the
        // line read なんじゃ + ない.
        val tokens = listOf(
            jaToken("なん", JaCategory.PRONOUN, norm = "何"),
            da("じゃ", "連用形-融合"),
            naiAdj(),
            jaToken("か", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "ないか"))
        val candidates = phraseCandidatesFor(tokens)
        // Without the word before it, ないか is a clean join.
        assertNull(candidates.single { it.lookupForm == "ないか" }.residualSuspicion)
        val admissible = admissiblePhraseCandidates(
            candidates, headwords = setOf("なんじゃ"), kanaNativeReadings = setOf("ないか"),
        )
        // Not a headword: kept for the walk, marked, rather than dropped.
        assertTrue(admissible.single { it.lookupForm == "ないか" }.afterClosedPhraseOnly)
        // A headword admits unmarked, as a conjugation cut's does.
        assertFalse(
            admissiblePhraseCandidates(candidates, headwords = setOf("ないか"), kanaNativeReadings = emptySet())
                .single { it.lookupForm == "ないか" }.afterClosedPhraseOnly,
        )
        val r = reglobSpans(tokens, admissible, setOf("なんじゃ", "ないか"), setOf("何", "ない"))
        assertEquals(listOf("なんじゃ", "ないか"), r.map { it.lookupForm })
        assertTrue(r.all { it.isPhrase })
        assertEquals(listOf(0 to 2, 2 to 2), r.map { it.tokenStart to it.tokenCount })
    }

    @Test
    fun `a 形状詞's 助動詞 fuses right after a closed phrase - the 特徴的だが specimen`() {
        // corpus: 特徴的だが. 的 is a 形状詞 suffix, so だ after it is a
        // previous-word cut; 特徴的 closes as a phrase, so だが ("but") fuses.
        val tokens = listOf(
            jaToken("特徴", JaCategory.NOUN),
            jaToken("的", JaCategory.ADJ_NA),
            da("だ", "終止形-一般"),
            jaToken("が", JaCategory.PARTICLE, conj = true),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "だが"))
        val candidates = phraseCandidatesFor(tokens)
        // Without the word before it, だが is all function morphemes: the
        // kana-native tier judges it, and だが is a kana word.
        assertEquals(Suspicion.FUNCTION_RUN, candidates.single { it.lookupForm == "だが" }.residualSuspicion)
        val admissible = admissiblePhraseCandidates(
            candidates, headwords = setOf("特徴的"), kanaNativeReadings = setOf("だが"),
        )
        assertTrue(admissible.single { it.lookupForm == "だが" }.afterClosedPhraseOnly)
        val r = reglobSpans(tokens, admissible, setOf("特徴的", "だが"), setOf("特徴", "的"))
        assertEquals(listOf("特徴的", "だが"), r.map { it.lookupForm })
        assertTrue(r.all { it.isPhrase })
        assertEquals(listOf(0 to 2, 2 to 2), r.map { it.tokenStart to it.tokenCount })
    }

    @Test
    fun `after a closed phrase a held window is judged by its own shape`() {
        // The 特徴的だが tokens with だが a reading but not a kana-native one
        // (the との=殿 shape): as a function run it would not fuse in a clean
        // context, so it does not fuse after a closed phrase either. Dropped
        // at admissibility, not held for the walk.
        val tokens = listOf(
            jaToken("特徴", JaCategory.NOUN),
            jaToken("的", JaCategory.ADJ_NA),
            da("だ", "終止形-一般"),
            jaToken("が", JaCategory.PARTICLE, conj = true),
        )
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = setOf("特徴的"), kanaNativeReadings = emptySet(),
        )
        assertTrue(admissible.none { it.lookupForm == "だが" })
        val r = reglobSpans(tokens, admissible, setOf("特徴的", "だが"), setOf("特徴", "的"))
        assertEquals(listOf("特徴的"), r.map { it.lookupForm })
    }

    @Test
    fun `a previous-word cut needs a closed phrase ending right before it`() {
        // The fold case (高くないか) is pinned above: the fold claims ない, so
        // no window starts there. Here 特徴的 closes two tokens before ない,
        // and で|は between belong to no span. ではない is left out of the
        // membership so that ないか is the only window at stake.
        val tokens = listOf(
            jaToken("特徴", JaCategory.NOUN),
            jaToken("的", JaCategory.ADJ_NA),
            da("で", "連用形-一般"),
            jaToken("は", JaCategory.PARTICLE),
            naiAdj(),
            jaToken("か", JaCategory.PARTICLE),
        )
        assertEquals(Suspicion.PREVIOUS_WORD_CUT, exactSuspicion(tokens, "ないか"))
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = setOf("特徴的"), kanaNativeReadings = setOf("ないか"),
        )
        val r = reglobSpans(tokens, admissible, setOf("特徴的", "ないか"), setOf("特徴", "ない"))
        assertEquals(listOf("特徴的", "ない"), r.map { it.lookupForm })
        assertEquals(listOf(0 to 2, 4 to 2), r.map { it.tokenStart to it.tokenCount })

        // No span at all before the window: the stem is not lookup-worthy
        // (constructed, as in the emitted-span test), so いるか stays out.
        val noSpan = listOf(jaToken("ggっ", JaCategory.VERB, dict = "gg", infl = "連用形-促音便"), te, iruAux,
            jaToken("か", JaCategory.PARTICLE))
        val noSpanAdmissible = admissiblePhraseCandidates(
            phraseCandidatesFor(noSpan), headwords = emptySet(), kanaNativeReadings = setOf("いるか"),
        )
        assertEquals(
            listOf("いる"),
            reglobSpans(noSpan, noSpanAdmissible, setOf("いるか"), setOf("いる")).map { it.lookupForm },
        )
    }
}
