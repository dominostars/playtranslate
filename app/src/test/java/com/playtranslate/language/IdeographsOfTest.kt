package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ideographsOf]: the characters a word's page breaks down, for Japanese
 * and Chinese alike. CJK Unified Ideographs and Extension A, each once, in
 * the word's order; kana, the iteration mark, Latin and punctuation are no
 * ideographs, nor are the code points on either side of the two blocks.
 * Pure JVM.
 */
class IdeographsOfTest {

    // Mutations: Extension A is left out; a repeated character is listed
    // twice; the blocks' neighbours are let in.
    @Test fun `the distinct ideographs of a word, in its order`() {
        assertEquals(listOf('学', '生'), ideographsOf("学生"))
        assertEquals(listOf('生', '学'), ideographsOf("生きる学生"))
        assertEquals(listOf('妈'), ideographsOf("妈妈"))
        // Extension A: 㑺, a variant of 俊, and the block's two ends.
        assertEquals(listOf('㑺', '俊'), ideographsOf("㑺俊"))
        assertEquals(listOf('㐀', '䶿', '一', '鿿'), ideographsOf("㐀䶿一鿿"))
        // Not ideographs: kana, 々, Latin, punctuation, and the code points
        // beside the two blocks.
        assertEquals(emptyList<Char>(), ideographsOf("かもしれない"))
        assertEquals(listOf('時'), ideographsOf("時々"))
        assertEquals(emptyList<Char>(), ideographsOf("OK, 3!・ー"))
        assertEquals(emptyList<Char>(), ideographsOf("㏿䷀䷿ꀀ"))
        assertEquals(emptyList<Char>(), ideographsOf(""))
    }
}
