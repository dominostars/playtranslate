package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseScriptVariantTest {

    @Test fun `fromCode maps known codes`() {
        assertEquals(ChineseScriptVariant.SIMPLIFIED, ChineseScriptVariant.fromCode("zh-Hans"))
        assertEquals(ChineseScriptVariant.TRADITIONAL, ChineseScriptVariant.fromCode("zh-Hant"))
        assertEquals(ChineseScriptVariant.TRADITIONAL_TW, ChineseScriptVariant.fromCode("zh-Hant-TW"))
        assertEquals(ChineseScriptVariant.TRADITIONAL_HK, ChineseScriptVariant.fromCode("zh-Hant-HK"))
    }

    @Test fun `fromCode falls back to SIMPLIFIED for unknown or null`() {
        // Fail-safe: a stale or bad pref value must read as Simplified, never throw.
        assertEquals(ChineseScriptVariant.SIMPLIFIED, ChineseScriptVariant.fromCode(null))
        assertEquals(ChineseScriptVariant.SIMPLIFIED, ChineseScriptVariant.fromCode(""))
        assertEquals(ChineseScriptVariant.SIMPLIFIED, ChineseScriptVariant.fromCode("garbage"))
        assertEquals(ChineseScriptVariant.SIMPLIFIED, ChineseScriptVariant.fromCode("zh"))
    }

    @Test fun `isChineseTarget only for the zh backend code`() {
        assertTrue(ChineseScriptVariant.isChineseTarget("zh"))
        assertFalse(ChineseScriptVariant.isChineseTarget("en"))
        assertFalse(ChineseScriptVariant.isChineseTarget("fr"))
        // The variant codes themselves are never the targetLang (that stays "zh").
        assertFalse(ChineseScriptVariant.isChineseTarget("zh-Hant-TW"))
    }

    @Test fun `backendCode is zh and there are four variants`() {
        assertEquals("zh", ChineseScriptVariant.BACKEND_CODE)
        assertEquals(4, ChineseScriptVariant.all.size)
    }

    // The variants' display names are in LanguageNamesTest: they are composed by
    // the platform's ICU, which a plain JVM test cannot call.
}
