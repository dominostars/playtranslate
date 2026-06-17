package com.playtranslate.yomitan

import com.playtranslate.language.SourceLangId
import org.junit.Assert.assertEquals
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
    fun `undeclared source language is a wildcard matching every language`() {
        // Consulted whatever the app's source language is; a wrong-language
        // lookup just finds nothing, so it is never silently hidden.
        assertTrue(dict(null).matchesSourceLanguage("ja"))
        assertTrue(dict(null).matchesSourceLanguage("ru"))
        assertTrue(dict(null).matchesSourceLanguage("zh"))
        assertTrue(dict(null).matchesSourceLanguage("en"))
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

    @Test
    fun `both sides reduce to the primary subtag`() {
        // A caller may pass a region variant (e.g. ZH_HANT's "zh-Hant"); it and
        // the dict's declared language both collapse to the primary subtag.
        assertTrue(dict("zh-Hans").matchesSourceLanguage("zh-Hant"))
        assertTrue(dict("zh").matchesSourceLanguage("zh-Hant"))
        assertTrue(dict("ja").matchesSourceLanguage("ja-JP"))
        assertTrue(dict("en").matchesSourceLanguage("EN"))
        assertFalse(dict("ko").matchesSourceLanguage("zh-Hant"))
    }

    @Test
    fun `yomitanConsumingLang is the case-folded primary subtag`() {
        // ZH and ZH_HANT collapse to one "zh" cache key / filter argument.
        assertEquals("zh", SourceLangId.ZH.yomitanConsumingLang())
        assertEquals("zh", SourceLangId.ZH_HANT.yomitanConsumingLang())
        assertEquals("ja", SourceLangId.JA.yomitanConsumingLang())
        assertEquals("ar", SourceLangId.AR.yomitanConsumingLang())
        assertEquals("ru", SourceLangId.RU.yomitanConsumingLang())
    }
}
