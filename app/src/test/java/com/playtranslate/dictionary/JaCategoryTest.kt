package com.playtranslate.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UniDic `接尾辞` resolution. Sudachi promotes suffixes to their own level-1
 * POS (IPADIC filed them under the derived class), so without subtype
 * handling every suffix maps to OTHER and loses its tap target — the
 * post-Sudachi-swap regression for づらい/にくい/やすい, 向け/的/済み, and
 * ぶり/だらけ/めく. Suffixes take the derived class's category except the
 * closed honorific/pluralizer lemma family (denylist on NORMALIZED form,
 * so the stretched surface variants さ〜ん/ちゃーん/くーん are covered too).
 * The expectations mirror real lexicon rows from the DictionaryPrinter
 * census (surface, subtype, normalized form).
 */
class JaCategoryTest {

    private fun cat(sub: String, norm: String) = JaCategory.fromUniDic("接尾辞", sub, norm)

    @Test
    fun `adjectival suffixes are i-adjectives`() {
        for (n in listOf("辛い", "難い", "易い", "ぽい", "臭い", "らしい")) {
            assertEquals(n, JaCategory.ADJ_I, cat("形容詞的", n))
        }
    }

    @Test
    fun `nominal suffixes are nouns regardless of script`() {
        // Kanji-surface lemmas (向け/済み) and kana lemmas whose entries are
        // meaning-bearing (ぶり in 五年ぶり — the Codex-review find; つき/がてら
        // normalize to THEMSELVES, which is why a normalized-kanji gate was
        // rejected as reproducing the same hole).
        for (n in listOf("向け", "済み", "振り", "方", "毎", "つき", "がてら", "匹")) {
            assertEquals(n, JaCategory.NOUN, cat("名詞的", n))
        }
    }

    @Test
    fun `adjectival-noun and verbal suffixes take their class`() {
        for (n in listOf("だらけ", "がち", "的", "風")) {
            assertEquals(n, JaCategory.ADJ_NA, cat("形状詞的", n))
        }
        for (n in listOf("めく", "振る", "染みる", "がる")) {
            assertEquals(n, JaCategory.VERB, cat("動詞的", n))
        }
    }

    @Test
    fun `honorific and pluralizer lemmas stay non-content in both scripts`() {
        // Normalized forms — the lexicon's さ〜ん/ちゃぁん/くーん/たちゃあ surface
        // variants all normalize into these lemmas, as do the kanji spellings
        // 田中様/山田君/私達.
        for (n in listOf("さん", "ちゃん", "たん", "やん", "ちん", "様", "君", "達", "等", "共")) {
            assertEquals(n, JaCategory.OTHER, cat("名詞的", n))
        }
    }

    @Test
    fun `unknown suffix subtype stays non-content`() {
        assertEquals(JaCategory.OTHER, cat("", "め"))
        assertEquals(JaCategory.OTHER, JaCategory.fromUniDic("接尾辞"))
    }

    @Test
    fun `non-suffix POS ignores subtype and normalized form`() {
        assertEquals(JaCategory.NOUN, JaCategory.fromUniDic("名詞", "普通名詞", "海外"))
        assertEquals(JaCategory.AUX, JaCategory.fromUniDic("助動詞", "", "た"))
    }
}
