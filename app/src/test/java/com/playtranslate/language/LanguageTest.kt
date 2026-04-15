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

    @Test fun `fromCode rejects unknown code`() {
        // Phase 1 only has JA; "en" lands in Phase 3.
        assertNull(SourceLangId.fromCode("en"))
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
}
