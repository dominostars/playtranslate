package com.playtranslate.ui

import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.ChineseScriptVariant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [WelcomeDefaults.computeDefaultTarget]: the device locale's
 * language when ML Kit supports it and it differs from the source, else
 * English; for a Chinese device the script follows the locale too.
 */
class WelcomeDefaultsTest {

    private fun target(sourceCode: String, device: String) =
        WelcomeDefaults.computeDefaultTarget(sourceCode, Locale.forLanguageTag(device))

    @Test fun `device locale English with JA source stays English`() {
        assertEquals(TranslateLanguage.ENGLISH, target("ja", "en").code)
    }

    @Test fun `device locale Spanish with JA source picks Spanish`() {
        assertEquals("es", target("ja", "es").code)
        assertEquals(ChineseScriptVariant.SIMPLIFIED, target("ja", "es").chineseVariant)
    }

    @Test fun `device locale French with ZH source picks French`() {
        assertEquals("fr", target("zh", "fr").code)
    }

    // ── Chinese devices: the script follows the locale ──────────────────

    @Test fun `Mainland device picks Simplified`() {
        val t = target("ja", "zh-CN")
        assertEquals("zh", t.code)
        assertEquals(ChineseScriptVariant.SIMPLIFIED, t.chineseVariant)
    }

    @Test fun `Taiwan device picks Traditional Taiwan`() {
        val t = target("ja", "zh-TW")
        assertEquals("zh", t.code)
        assertEquals(ChineseScriptVariant.TRADITIONAL_TW, t.chineseVariant)
        assertEquals(ChineseScriptVariant.TRADITIONAL_TW, target("ja", "zh-Hant-TW").chineseVariant)
    }

    @Test fun `Hong Kong and Macau devices pick Traditional Hong Kong`() {
        assertEquals(ChineseScriptVariant.TRADITIONAL_HK, target("ja", "zh-HK").chineseVariant)
        assertEquals(ChineseScriptVariant.TRADITIONAL_HK, target("ja", "zh-MO").chineseVariant)
    }

    @Test fun `script-only Traditional device picks plain Traditional`() {
        assertEquals(ChineseScriptVariant.TRADITIONAL, target("ja", "zh-Hant").chineseVariant)
    }

    @Test fun `a Traditional region on a non-Chinese language leaves the variant alone`() {
        // en-HK is English: the variant is meaningless there and stays Simplified.
        val t = target("ja", "en-HK")
        assertEquals("en", t.code)
        assertEquals(ChineseScriptVariant.SIMPLIFIED, t.chineseVariant)
    }

    // ── Source-equals-locale exclusion ───────────────────────────────────

    @Test fun `JA device locale with JA source falls back to English`() {
        assertEquals(TranslateLanguage.ENGLISH, target("ja", "ja").code)
    }

    @Test fun `ZH device locale with ZH source falls back to English`() {
        assertEquals(TranslateLanguage.ENGLISH, target("zh", "zh-TW").code)
    }

    @Test fun `KO device locale with KO source falls back to English`() {
        assertEquals(TranslateLanguage.ENGLISH, target("ko", "ko").code)
    }

    // ── Unsupported locale fallback ──────────────────────────────────────

    @Test fun `ML Kit-unsupported device locale falls back to English`() {
        // "xx" is not a real ML Kit-supported target.
        assertEquals(TranslateLanguage.ENGLISH, target("ja", "xx").code)
    }
}
