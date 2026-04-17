package com.playtranslate.language

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(SourceLangId.ZH, SourceLangId.fromCode("zh-TW"))
    }

    @Test fun `fromCode rejects unknown code`() {
        assertNull(SourceLangId.fromCode("fr"))
        assertNull(SourceLangId.fromCode("ar"))
    }

    @Test fun `fromCode handles null and blank`() {
        assertNull(SourceLangId.fromCode(null))
        assertNull(SourceLangId.fromCode(""))
        assertNull(SourceLangId.fromCode("   "))
    }

    @Test fun `JA profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.JA]
        assertEquals(TranslateLanguage.JAPANESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitJapanese, profile.ocrBackend)
        assertEquals(HintTextKind.FURIGANA, profile.hintTextKind)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(ScriptFamily.CJK_JAPANESE, profile.scriptFamily)
    }

    @Test fun `EN profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.EN]
        assertEquals(TranslateLanguage.ENGLISH, profile.translationCode)
        assertEquals(OcrBackend.MLKitLatin, profile.ocrBackend)
        assertEquals(HintTextKind.NONE, profile.hintTextKind)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(ScriptFamily.LATIN, profile.scriptFamily)
        assertEquals(true, profile.wordsSeparatedByWhitespace)
    }

    @Test fun `ZH profile has correct translation code and OCR backend`() {
        val profile = SourceLanguageProfiles[SourceLangId.ZH]
        assertEquals(TranslateLanguage.CHINESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitChinese, profile.ocrBackend)
        assertEquals(HintTextKind.PINYIN, profile.hintTextKind)
        assertEquals(TextDirection.LTR, profile.textDirection)
        assertEquals(ScriptFamily.CJK_CHINESE, profile.scriptFamily)
        assertEquals(false, profile.wordsSeparatedByWhitespace)
    }
}
