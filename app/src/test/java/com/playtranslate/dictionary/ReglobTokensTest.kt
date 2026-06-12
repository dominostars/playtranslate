package com.playtranslate.dictionary

import com.playtranslate.dictionary.DictionaryManager.Companion.PhraseCandidate
import com.playtranslate.dictionary.DictionaryManager.Companion.phraseCandidatesFor
import com.playtranslate.dictionary.DictionaryManager.Companion.reglobTokens
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
    ) = JaToken(
        surface = surface, begin = 0, end = surface.length, category = cat,
        dictionaryForm = dict, normalizedForm = norm, reading = reading, isOov = false,
    )

    private fun glob(
        tokens: List<JaToken>,
        knownPhrases: Set<String> = emptySet(),
        knownForms: Set<String> = emptySet(),
    ) = reglobTokens(tokens, phraseCandidatesFor(tokens), knownPhrases, knownForms)

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
}
