package com.playtranslate.language

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SourceLangId.fromCode] and the JA entry in
 * [SourceLanguageProfiles]. Pure JUnit — no Android classes touched, no
 * Robolectric needed.
 */
class LanguageTest {

    @Test fun `fromCode accepts lowercase primary`() {
        assertEquals(SourceLangId.JA, SourceLangId.fromCode("ja"))
    }

    @Test fun `fromCode accepts uppercase`() {
        assertEquals(SourceLangId.JA, SourceLangId.fromCode("JA"))
    }

    @Test fun `fromCode strips region suffix`() {
        assertEquals(SourceLangId.JA, SourceLangId.fromCode("ja-JP"))
    }

    @Test fun `fromCode resolves EN added in Phase 3`() {
        assertEquals(SourceLangId.EN, SourceLangId.fromCode("en"))
        assertEquals(SourceLangId.EN, SourceLangId.fromCode("EN"))
        assertEquals(SourceLangId.EN, SourceLangId.fromCode("en-US"))
    }

    @Test fun `fromCode resolves ZH added in Phase 4`() {
        assertEquals(SourceLangId.ZH, SourceLangId.fromCode("zh"))
        assertEquals(SourceLangId.ZH, SourceLangId.fromCode("ZH"))
    }

    @Test fun `fromCode rejects unknown code`() {
        // "xx" is ISO 639-2 private-use; guaranteed never valid.
        assertNull(SourceLangId.fromCode("xx"))
    }

    @Test fun `fromCode resolves Arabic`() {
        assertEquals(SourceLangId.AR, SourceLangId.fromCode("ar"))
    }

    @Test fun `fromCode handles null and blank`() {
        assertNull(SourceLangId.fromCode(null))
        assertNull(SourceLangId.fromCode(""))
        assertNull(SourceLangId.fromCode("   "))
    }

    @Test fun `JA profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.JA]
        assertEquals(TranslateLanguage.JAPANESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitJapanese, profile.mlKitFloor)
        assertEquals(HintTextKind.FURIGANA, profile.hintTextKind)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(ScriptFamily.CJK_JAPANESE, profile.scriptFamily)
    }

    @Test fun `EN profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.EN]
        assertEquals(TranslateLanguage.ENGLISH, profile.translationCode)
        assertEquals(OcrBackend.MLKitLatin, profile.mlKitFloor)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(ScriptFamily.LATIN, profile.scriptFamily)
        assertEquals(true, profile.wordsSeparatedByWhitespace)
    }

    @Test fun `ZH profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.ZH]
        assertEquals(TranslateLanguage.CHINESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitChinese, profile.mlKitFloor)
        assertEquals(HintTextKind.PINYIN, profile.hintTextKind)
        assertEquals(false, profile.preferTraditional)
        assertEquals(SourceLangId.ZH.displayName(), profile.id.displayName())
    }

    @Test fun `ZH_HANT profile shares ZH traits but prefers traditional`() {
        val profile = SourceLanguageProfiles[SourceLangId.ZH_HANT]
        assertEquals(TranslateLanguage.CHINESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitChinese, profile.mlKitFloor)
        assertEquals(HintTextKind.PINYIN, profile.hintTextKind)
        assertEquals(true, profile.preferTraditional)
        assertEquals(SourceLangId.ZH_HANT.displayName(), profile.id.displayName())
    }

    @Test fun `RU profile has no ML Kit floor and Cyrillic traits`() {
        val profile = SourceLanguageProfiles[SourceLangId.RU]
        assertEquals(TranslateLanguage.RUSSIAN, profile.translationCode)
        // First source language with no ML Kit OCR floor — its only recognizer
        // is the downloadable, arm64-only Cyrillic Paddle pack.
        assertNull(profile.mlKitFloor)
        assertEquals(ScriptFamily.CYRILLIC, profile.scriptFamily)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        assertEquals(true, profile.wordsSeparatedByWhitespace)
    }

    @Test fun `fromCode resolves Thai`() {
        assertEquals(SourceLangId.TH, SourceLangId.fromCode("th"))
        assertEquals(SourceLangId.TH, SourceLangId.fromCode("TH"))
        assertEquals(SourceLangId.TH, SourceLangId.fromCode("th-TH"))
    }

    @Test fun `TH profile has no ML Kit floor and Thai no-whitespace traits`() {
        val profile = SourceLanguageProfiles[SourceLangId.TH]
        assertEquals(TranslateLanguage.THAI, profile.translationCode)
        // No ML Kit Thai recognizer — only the downloadable, arm64-only Thai
        // Paddle pack (like RU/AR).
        assertNull(profile.mlKitFloor)
        assertEquals(ScriptFamily.THAI, profile.scriptFamily)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        // Thai has no inter-word spaces (like CJK) — drives the segmenter path
        // and skips OCR line-assembly/spacing.
        assertEquals(false, profile.wordsSeparatedByWhitespace)
        assertTrue(profile.isScriptChar('ก'))
        assertFalse(profile.isScriptChar('a'))
    }

    @Test fun `fromCode resolves Hindi`() {
        assertEquals(SourceLangId.HI, SourceLangId.fromCode("hi"))
        assertEquals(SourceLangId.HI, SourceLangId.fromCode("HI"))
        assertEquals(SourceLangId.HI, SourceLangId.fromCode("hi-IN"))
    }

    @Test fun `HI profile has an ML Kit Devanagari floor and whitespace traits`() {
        val profile = SourceLanguageProfiles[SourceLangId.HI]
        assertEquals(TranslateLanguage.HINDI, profile.translationCode)
        // Unlike RU/AR/TH, Hindi HAS an ML Kit floor (ML Kit ships a Devanagari recognizer).
        assertEquals(OcrBackend.MLKitDevanagari, profile.mlKitFloor)
        assertEquals(ScriptFamily.DEVANAGARI, profile.scriptFamily)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        // Hindi uses inter-word whitespace → LatinEngine path (no segmenter).
        assertEquals(true, profile.wordsSeparatedByWhitespace)
        assertTrue(profile.isScriptChar('क'))
        assertFalse(profile.isScriptChar('a'))
    }

    @Test fun `fromCode resolves Polish`() {
        assertEquals(SourceLangId.PL, SourceLangId.fromCode("pl"))
        assertEquals(SourceLangId.PL, SourceLangId.fromCode("PL"))
        assertEquals(SourceLangId.PL, SourceLangId.fromCode("pl-PL"))
    }

    @Test fun `PL profile has an ML Kit Latin floor and Polish diacritics`() {
        val profile = SourceLanguageProfiles[SourceLangId.PL]
        assertEquals(TranslateLanguage.POLISH, profile.translationCode)
        assertEquals(OcrBackend.MLKitLatin, profile.mlKitFloor)
        assertEquals(ScriptFamily.LATIN, profile.scriptFamily)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        assertEquals(true, profile.wordsSeparatedByWhitespace)
        // All nine diacritic letters — the reason PL declares its own profile
        // instead of reusing latinProfile (whose range covers only ó).
        for (c in "ąćęłńóśźż") assertTrue("missing $c", profile.isScriptChar(c))
        for (c in "ĄĆĘŁŃÓŚŹŻ") assertTrue("missing $c", profile.isScriptChar(c))
        assertFalse(profile.isScriptChar('д'))
    }

    @Test fun `PL defaults to the ML Kit recognizer like VI and TR`() {
        assertEquals(
            OcrBackend.MLKitLatin,
            SourceLanguageProfiles[SourceLangId.PL].ocrBackends.first(),
        )
    }

    @Test fun `every SourceLangId resolves to a profile`() {
        // SourceLanguageProfiles.all is a map, not an exhaustive when — a missing
        // profile fails SILENTLY via forCode (the path OcrModelManager.ALL_PACK_KEYS
        // uses), so guard every enum value here.
        val missing = SourceLangId.entries.filter { SourceLanguageProfiles.forCode(it.code) == null }
        assertTrue("SourceLangIds with no profile: $missing", missing.isEmpty())
    }

    @Test fun `ZH_HANT shares pack with ZH`() {
        assertEquals(SourceLangId.ZH, SourceLangId.ZH_HANT.packId)
        assertEquals(SourceLangId.ZH, SourceLangId.ZH.packId)
    }

    @Test fun `fromCode resolves zh-Hant to ZH_HANT`() {
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-Hant"))
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-hant"))
        assertEquals(SourceLangId.ZH, SourceLangId.fromCode("zh"))
    }

    @Test fun `fromCode maps traditional region codes to ZH_HANT`() {
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-TW"))
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-HK"))
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-MO"))
        assertEquals(SourceLangId.ZH_HANT, SourceLangId.fromCode("zh-Hant-TW"))
    }
}
