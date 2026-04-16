package com.playtranslate.language

import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.dictionary.CustomDictionary
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents HanLP's segmentation behavior for Chinese game text. Pure JVM
 * (HanLP is a Java library, no Android classes needed).
 *
 * These tests verify the API contract Phase 4's [ChineseEngine] depends on.
 * If HanLP ever changes its segmentation output, these tests surface the
 * change before it reaches users.
 */
class ChineseEngineTokenizerTest {

    @Test fun `segments common Chinese sentence into words`() {
        val terms = HanLP.segment("今天天气很好")
        val words = terms.map { it.word }
        assertTrue("Expected '今天' in $words", words.contains("今天"))
        assertTrue("Expected '天气' in $words", words.contains("天气"))
    }

    @Test fun `custom dictionary keeps game terms intact`() {
        CustomDictionary.add("魔法石", "n 1")
        val terms = HanLP.segment("使用魔法石")
        val words = terms.map { it.word }
        assertTrue(
            "Expected '魔法石' as a single token in $words (custom dict entry)",
            words.contains("魔法石")
        )
    }

    @Test fun `handles traditional Chinese characters`() {
        val terms = HanLP.segment("學生們正在學習")
        val words = terms.map { it.word }
        // HanLP should keep "學生" or "學習" together rather than
        // splitting into individual characters.
        val hasCompound = words.any { it.length >= 2 }
        assertTrue("Expected at least one multi-char token in $words", hasCompound)
    }

    @Test fun `filters punctuation as separate tokens`() {
        val terms = HanLP.segment("你好！世界。")
        val words = terms.map { it.word }
        assertTrue("Expected '你好' in $words", words.contains("你好"))
        assertTrue("Expected '世界' in $words", words.contains("世界"))
    }
}
