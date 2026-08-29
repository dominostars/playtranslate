package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.PhraseCandidate
import com.playtranslate.dictionary.DictionaryManager.Companion.Suspicion
import com.playtranslate.dictionary.DictionaryManager.Companion.admissiblePhraseCandidates
import com.playtranslate.dictionary.DictionaryManager.Companion.phraseCandidatesFor
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobTokens
import com.playtranslate.language.InflectionTag
import org.junit.Assert.assertEquals
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
    ) = JaToken(
        surface = surface, begin = 0, end = surface.length, category = cat,
        dictionaryForm = dict, normalizedForm = norm, reading = reading, isOov = false,
        inflectionForm = infl, isConjunctiveParticle = conj, isPunctuation = punct,
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
        assertEquals(listOf(InflectionTag.PAST), result[1].inflections)
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
        assertEquals(listOf(InflectionTag.PAST), result[0].inflections)
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
        assertEquals(listOf(InflectionTag.PAST), result[1].inflections)
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
        assertEquals(listOf(InflectionTag.PAST), result[0].inflections)
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
    // morpheme's 活用形. These assert the analyzer's mapping/ordering/dedup
    // LOGIC over morpheme shapes taken from UniDic convention and the corpus
    // above; JapaneseInflectionSurveyTest confirms the shapes on-device.

    @Test
    fun `causative te-form yields ordered tags`() {
        // 言わせて = 言わ(言う) + せ(せる) + て
        val tokens = listOf(
            jaToken("言わ", JaCategory.VERB, dict = "言う"),
            jaToken("せ", JaCategory.AUX, dict = "せる"),
            jaToken("て", JaCategory.PARTICLE),
        )
        val r = glob(tokens, knownForms = setOf("言う"))
        assertEquals("言わせて", r[0].surface)
        assertEquals("言う", r[0].lookupForm)
        assertEquals(listOf(InflectionTag.CAUSATIVE, InflectionTag.TE_FORM), r[0].inflections)
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
            listOf(InflectionTag.PAST),
            glob(
                listOf(jaToken("食べ", JaCategory.VERB, dict = "食べる"), jaToken("た", JaCategory.AUX)),
                knownForms = setOf("食べる"),
            )[0].inflections,
        )
    }

    @Test
    fun `polite negative past collapses the doubled politeness`() {
        // 食べませんでした = 食べ + ませ(ます) + ん(ぬ) + でし(です) + た:
        // ます and でし→です both map to Polite; distinct() keeps one.
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("ませ", JaCategory.AUX, dict = "ます"),
            jaToken("ん", JaCategory.AUX, dict = "ぬ"),
            jaToken("でし", JaCategory.AUX, dict = "です"),
            jaToken("た", JaCategory.AUX),
        )
        assertEquals(
            listOf(InflectionTag.POLITE, InflectionTag.NEGATIVE, InflectionTag.PAST),
            glob(tokens, knownForms = setOf("食べる"))[0].inflections,
        )
    }

    @Test
    fun `causative passive past stack stays in morpheme order`() {
        // 食べさせられた = 食べ + させ(させる) + られ(られる) + た
        val tokens = listOf(
            jaToken("食べ", JaCategory.VERB, dict = "食べる"),
            jaToken("させ", JaCategory.AUX, dict = "させる"),
            jaToken("られ", JaCategory.AUX, dict = "られる"),
            jaToken("た", JaCategory.AUX),
        )
        assertEquals(
            listOf(InflectionTag.CAUSATIVE, InflectionTag.PASSIVE, InflectionTag.PAST),
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
            listOf(InflectionTag.CONDITIONAL),
            glob(tokens, knownForms = setOf("言う"))[0].inflections,
        )
    }

    @Test
    fun `non-conjugational particle in the span is not a tag`() {
        // 言わせては: the trailing は folds into the surface span but the
        // analyzer's allow-list ignores it — labels stay correct.
        val tokens = listOf(
            jaToken("言わ", JaCategory.VERB, dict = "言う"),
            jaToken("せ", JaCategory.AUX, dict = "せる"),
            jaToken("て", JaCategory.PARTICLE),
            jaToken("は", JaCategory.PARTICLE),
        )
        val r = glob(tokens, knownForms = setOf("言う"))
        assertEquals("言わせては", r[0].surface)
        assertEquals(listOf(InflectionTag.CAUSATIVE, InflectionTag.TE_FORM), r[0].inflections)
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
        assertEquals(listOf(InflectionTag.PAST), r[0].inflections)
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
    fun `volitional is deferred - bare yo-u lemma is not tagged`() {
        // 〜う/よう is shared by volitional (食べよう), conjecture (だろう) and likeness
        // (ようだ), so it stays unlabeled until the Phase 0 survey disambiguates it.
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
        // いただい's 連用形-イ音便. The join severs the conjugation.
        val tokens = listOf(
            jaToken("いただい", JaCategory.VERB, dict = "いただく", infl = "連用形-イ音便"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("おり", JaCategory.VERB, dict = "おる", infl = "連用形-一般"),
            jaToken("ます", JaCategory.AUX, infl = "終止形-一般"),
        )
        assertEquals(Suspicion.CONJUGATION_CUT, exactSuspicion(tokens, "ており"))

        // End to end: even with ており in the membership set (JMdict has it),
        // admissibility drops the candidate and the parse comes out right.
        val admissible = admissiblePhraseCandidates(
            phraseCandidatesFor(tokens), headwords = emptySet(), kanaNativeReadings = emptySet(),
        )
        assertTrue(admissible.none { it.lookupForm == "ており" })
        val r = reglobTokens(tokens, admissible, setOf("ており"), setOf("いただく", "おる"))
        assertEquals(listOf("いただく", "おる"), r.map { it.lookupForm })
        assertEquals("いただいて", r[0].surface)
        assertEquals("おります", r[1].surface)
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
        val tokens = listOf(
            jaToken("こと", JaCategory.NOUN),
            jaToken("し", JaCategory.VERB, dict = "する", infl = "連用形-一般"),
            jaToken("て", JaCategory.PARTICLE, conj = true),
            jaToken("くる", JaCategory.VERB, infl = "終止形-一般"),
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
    fun `conjugation cut with a headword match stays admissible`() {
        // The veto blocks reading coincidences, not written forms: a candidate
        // whose joined surface matches a HEADWORD is admissible regardless.
        val tokens = listOf(
            jaToken("勝利", JaCategory.NOUN),
            jaToken("し", JaCategory.VERB, dict = "する", infl = "連用形-一般"),
            jaToken("た", JaCategory.AUX, infl = "終止形-一般"),
        )
        val candidates = phraseCandidatesFor(tokens)
        val admissible = admissiblePhraseCandidates(candidates, setOf("した"), emptySet())
        assertTrue(admissible.any { it.lookupForm == "した" })
    }
}
