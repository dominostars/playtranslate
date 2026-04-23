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
    }

    @Test fun `fromCode rejects unknown code`() {
        // AR is deferred pending Tesseract OCR (Phase 5).
        assertNull(SourceLangId.fromCode("ar"))
        // "xx" is ISO 639-2 private-use; guaranteed never valid.
        assertNull(SourceLangId.fromCode("xx"))
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
        assertEquals(false, profile.preferTraditional)
        assertEquals(SourceLangId.ZH.displayName(), profile.id.displayName())
    }

    @Test fun `ZH_HANT profile shares ZH traits but prefers traditional`() {
        val profile = SourceLanguageProfiles[SourceLangId.ZH_HANT]
        assertEquals(TranslateLanguage.CHINESE, profile.translationCode)
        assertEquals(OcrBackend.MLKitChinese, profile.ocrBackend)
        assertEquals(HintTextKind.PINYIN, profile.hintTextKind)
        assertEquals(true, profile.preferTraditional)
        assertEquals(SourceLangId.ZH_HANT.displayName(), profile.id.displayName())
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
