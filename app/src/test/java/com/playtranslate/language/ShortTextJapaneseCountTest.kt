package com.playtranslate.language

import com.playtranslate.dictionary.JaCategory
import com.playtranslate.dictionary.JaToken
import com.playtranslate.dictionary.JapaneseTokenizer
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.worksap.nlp.sudachi.Tokenizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [ShortTextTokenCounters.japanese]'s counting contract against
 * hand-built tokens via [SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest]
 * (no packaged `.dic` needed): content categories count, particles/aux/
 * punctuation don't, and an empty token list (dict missing) is NO SIGNAL,
 * never zero. Also pins that the new SplitMode-taking [analyze] overload
 * still honors the test override (which deliberately ignores the mode).
 */
class ShortTextJapaneseCountTest {

    @After fun tearDown() {
        SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = null
    }

    private fun install(tokens: List<JaToken>) {
        SudachiJapaneseTokenizer.Provider.tokenizerOverrideForTest = object : JapaneseTokenizer {
            override fun analyze(text: String): List<JaToken> = tokens
        }
    }

    private fun tok(
        surface: String,
        category: JaCategory,
        punctuation: Boolean = false,
        properNoun: Boolean = false,
        oov: Boolean = false,
    ) = JaToken(
        surface = surface,
        begin = 0,
        end = surface.length,
        category = category,
        dictionaryForm = surface,
        normalizedForm = surface,
        reading = null,
        isOov = oov,
        isPunctuation = punctuation,
        isProperNoun = properNoun,
    )

    @Test fun `particles aux and punctuation are excluded from the count`() {
        // 装備を変更！ → 装備(N) を(P) 変更(N) ！(punct-categorized OTHER)
        install(
            listOf(
                tok("装備", JaCategory.NOUN),
                tok("を", JaCategory.PARTICLE),
                tok("変更", JaCategory.NOUN),
                tok("！", JaCategory.OTHER, punctuation = true),
            ),
        )
        assertEquals(2, ShortTextTokenCounters.japanese().count("装備を変更！"))
    }

    @Test fun `conjugated verb with aux counts its content stems only`() {
        // 行ってきます → 行っ(V) て(P) き(V) ます(AUX) = 2 content
        install(
            listOf(
                tok("行っ", JaCategory.VERB),
                tok("て", JaCategory.PARTICLE),
                tok("き", JaCategory.VERB),
                tok("ます", JaCategory.AUX),
            ),
        )
        assertEquals(2, ShortTextTokenCounters.japanese().count("行ってきます"))
    }

    @Test fun `three content tokens count as three`() {
        install(
            listOf(
                tok("難易", JaCategory.NOUN),
                tok("度", JaCategory.NOUN),
                tok("選択", JaCategory.NOUN),
            ),
        )
        assertEquals(3, ShortTextTokenCounters.japanese().count("難易度選択"))
    }

    @Test fun `proper noun vetoes the whole text - name veto`() {
        // 火村さん (speaker nameplate): a kanji personal name through a small
        // NMT becomes semantic garbage ("fire village") — must go online.
        install(
            listOf(
                tok("火村", JaCategory.NOUN, properNoun = true),
                tok("さん", JaCategory.OTHER),
            ),
        )
        assertNull(ShortTextTokenCounters.japanese().count("火村さん"))
    }

    @Test fun `content-bearing OOV token vetoes - invented names`() {
        install(
            listOf(
                tok("ジュナイパー", JaCategory.NOUN, oov = true),
            ),
        )
        assertNull(ShortTextTokenCounters.japanese().count("ジュナイパー"))
    }

    @Test fun `non-content OOV does not veto`() {
        // An OOV glyph classified as punctuation/OTHER shouldn't kill an
        // otherwise-ordinary menu item.
        install(
            listOf(
                tok("装備", JaCategory.NOUN),
                tok("★", JaCategory.OTHER, punctuation = true, oov = true),
            ),
        )
        assertEquals(1, ShortTextTokenCounters.japanese().count("装備★"))
    }

    @Test fun `laundered-name parse vetoes - non-punct OTHER token`() {
        // 無達 ("Mutatsu", P3 monk): UniDic has no entry, so the lattice
        // yields 無(ADJ_I) + 達(denylisted suffix → OTHER) — no PROPER, no
        // OOV. The non-punctuation OTHER token is the fictional-name
        // signature (probe-verified against the real dict); Bergamot
        // rendered this one "Untred".
        install(
            listOf(
                tok("無", JaCategory.ADJ_I),
                tok("達", JaCategory.OTHER),
            ),
        )
        assertNull(ShortTextTokenCounters.japanese().count("無達"))
    }

    @Test fun `prefix OTHER vetoes too - bounded quota cost`() {
        // 全回復: 全 is a prefix → OTHER. Goes online — fail-safe direction.
        install(
            listOf(
                tok("全", JaCategory.OTHER),
                tok("回復", JaCategory.NOUN),
            ),
        )
        assertNull(ShortTextTokenCounters.japanese().count("全回復"))
    }

    @Test fun `punctuation-flagged OTHER is exempt from the laundered-name veto`() {
        // はい！: ！ is OTHER but isPunctuation — stays offline.
        install(
            listOf(
                tok("はい", JaCategory.INTERJECTION),
                tok("！", JaCategory.OTHER, punctuation = true),
            ),
        )
        assertEquals(1, ShortTextTokenCounters.japanese().count("はい！"))
    }

    @Test fun `empty token list is no signal, not zero`() {
        install(emptyList())
        assertNull(ShortTextTokenCounters.japanese().count("こんにちは"))
    }

    @Test fun `all-punctuation tokens count zero`() {
        install(
            listOf(
                tok("…", JaCategory.OTHER, punctuation = true),
                tok("！", JaCategory.OTHER, punctuation = true),
            ),
        )
        assertEquals(0, ShortTextTokenCounters.japanese().count("…！"))
    }

    @Test fun `mode-taking Provider analyze honors the test override`() {
        install(listOf(tok("はい", JaCategory.INTERJECTION)))
        val viaModeParam =
            SudachiJapaneseTokenizer.Provider.analyze("はい", Tokenizer.SplitMode.C)
        assertEquals(listOf("はい"), viaModeParam.map { it.surface })
    }
}
