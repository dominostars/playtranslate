package com.playtranslate.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The names the app gives a language: the qualified name wherever the two
 * Chinese sources or the four Chinese target variants are told apart
 * ([SourceLangId.displayName], [ChineseScriptVariant.displayLabel]), and the
 * language alone for the narrow places ([SourceLangId.languageName],
 * [ChineseScriptVariant.targetLanguageName]).
 *
 * The qualified names are "Chinese (Simplified)", never "Chinese (Simplified
 * Han)". That longer form is what `java.util.Locale.getDisplayName` gives on a
 * device, and a host JVM gives the short one for that same call, so a test of
 * that call passes while the device shows the long name. These names come from
 * the platform's ICU ([localeDisplayName]), which this class runs on the
 * framework's copy of.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageNamesTest {

    private val english = Locale.ENGLISH

    @Test fun `the two Chinese sources carry their script, with no Han`() {
        assertEquals("Chinese (Simplified)", SourceLangId.ZH.displayName(english))
        assertEquals("Chinese (Traditional)", SourceLangId.ZH_HANT.displayName(english))
    }

    @Test fun `the four target variants carry their script and region`() {
        assertEquals("Chinese (Simplified)", ChineseScriptVariant.SIMPLIFIED.displayLabel(english))
        assertEquals("Chinese (Traditional)", ChineseScriptVariant.TRADITIONAL.displayLabel(english))
        assertEquals("Chinese (Traditional, Taiwan)", ChineseScriptVariant.TRADITIONAL_TW.displayLabel(english))
        // The region's own wording is the data's: "Hong Kong" in the framework's
        // copy, "Hong Kong SAR China" in stock CLDR.
        val hongKong = ChineseScriptVariant.TRADITIONAL_HK.displayLabel(english)
        assertTrue(hongKong, hongKong.startsWith("Chinese (Traditional, Hong Kong") && hongKong.endsWith(")"))
    }

    @Test fun `a name is written in the language asked for, its first letter capital`() {
        assertEquals("Chino (simplificado)", SourceLangId.ZH.displayName(Locale.forLanguageTag("es")))
        assertEquals("中国語 (簡体字)", SourceLangId.ZH.displayName(Locale.JAPANESE))
        // The picker's endonym: the name in the language's own locale.
        assertEquals("中文（简体）", SourceLangId.ZH.displayName(SourceLangId.ZH.locale))
    }

    @Test fun `the two Chinese sources are told apart in every source language's words`() {
        for (id in SourceLangId.entries) {
            val simplified = SourceLangId.ZH.displayName(id.locale)
            val traditional = SourceLangId.ZH_HANT.displayName(id.locale)
            assertNotEquals("in ${id.code}", simplified, traditional)
            assertFalse("blank in ${id.code}", simplified.isBlank() || traditional.isBlank())
        }
    }

    @Test fun `the language alone is Chinese for both sources and the whole name for every other`() {
        assertEquals("Chinese", SourceLangId.ZH.languageName(english))
        assertEquals("Chinese", SourceLangId.ZH_HANT.languageName(english))
        for (id in SourceLangId.entries) {
            if (id == SourceLangId.ZH || id == SourceLangId.ZH_HANT) continue
            assertEquals(id.code, id.displayName(english), id.languageName(english))
        }
        assertEquals("Japanese", SourceLangId.JA.languageName(english))
    }

    @Test fun `a target's name carries the variant only where the whole name is asked for`() {
        val taiwan = ChineseScriptVariant.TRADITIONAL_TW
        assertEquals("Chinese (Traditional, Taiwan)", ChineseScriptVariant.targetDisplayName("zh", taiwan, english))
        assertEquals("Chinese", ChineseScriptVariant.targetLanguageName("zh", english))
        // A target that is not Chinese ignores the (stale) variant.
        assertEquals("French", ChineseScriptVariant.targetDisplayName("fr", taiwan, english))
        assertEquals("French", ChineseScriptVariant.targetLanguageName("fr", english))
    }
}
