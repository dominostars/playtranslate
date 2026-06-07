package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test fun `displayLabel is script-qualified, not bare Chinese`() {
        val tw = ChineseScriptVariant.TRADITIONAL_TW.displayLabel(Locale.ENGLISH)
        assertTrue("expected Chinese in '$tw'", tw.contains("Chinese"))
        assertTrue("expected Taiwan in '$tw'", tw.contains("Taiwan"))
        val simp = ChineseScriptVariant.SIMPLIFIED.displayLabel(Locale.ENGLISH)
        assertTrue("expected Simplified in '$simp'", simp.contains("Simplified"))
    }

    @Test fun `targetDisplayName uses variant for Chinese and plain name otherwise`() {
        val zhTw = ChineseScriptVariant.targetDisplayName("zh", ChineseScriptVariant.TRADITIONAL_TW, Locale.ENGLISH)
        assertTrue("expected Taiwan in '$zhTw'", zhTw.contains("Taiwan"))
        // Non-Chinese target ignores the (stale) variant and shows the language name.
        assertEquals("French", ChineseScriptVariant.targetDisplayName("fr", ChineseScriptVariant.TRADITIONAL_TW, Locale.ENGLISH))
    }
}
