package com.playtranslate.yomitan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YomitanSourceLanguageTest {

    private fun dict(sourceLanguage: String?) = YomitanDictionary(
        id = "x",
        title = "t",
        revision = null,
        description = null,
        author = null,
        format = 3,
        categories = listOf(YomitanCategory.TERMS),
        sizeBytes = 0,
        importedAtMs = 0,
        sourceLanguage = sourceLanguage,
    )

    @Test
    fun `undeclared source language defaults to Japanese`() {
        assertTrue(dict(null).matchesSourceLanguage("ja"))
        assertFalse(dict(null).matchesSourceLanguage("ru"))
    }

    @Test
    fun `primary subtag matches across region variants and case`() {
        assertTrue(dict("ja").matchesSourceLanguage("ja"))
        assertTrue(dict("ja-JP").matchesSourceLanguage("ja"))
        assertTrue(dict("ja_JP").matchesSourceLanguage("ja"))
        assertTrue(dict("JA").matchesSourceLanguage("ja"))
    }

    @Test
    fun `other languages do not match`() {
        assertFalse(dict("zh-Hans").matchesSourceLanguage("ja"))
        assertFalse(dict("ko").matchesSourceLanguage("ja"))
        assertFalse(dict("en").matchesSourceLanguage("ja"))
    }
}
