package com.playtranslate.language

import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the per-language default OCR backend (= first in the priority list).
 * Headline cases: Vietnamese and Turkish default to ML Kit (not the Paddle latin
 * recognizer), while still offering Paddle as a secondary option.
 */
class OcrBackendsDefaultTest {

    private fun backends(id: SourceLangId) = SourceLanguageProfiles[id].ocrBackends

    @Test fun vietnameseDefaultsToMlKitButStillOffersPaddle() {
        val vi = backends(SourceLangId.VI)
        assertEquals("ML Kit is Vietnamese's default", OcrBackend.MLKitLatin, vi.first())
        assertTrue(
            "Paddle latin stays available as a secondary option for Vietnamese",
            vi.any { it is OcrBackend.Paddle && it.recPackKey == "paddle-rec-latin" },
        )
    }

    @Test fun turkishDefaultsToMlKitButStillOffersPaddle() {
        val tr = backends(SourceLangId.TR)
        assertEquals("ML Kit is Turkish's default", OcrBackend.MLKitLatin, tr.first())
        assertTrue(
            "Paddle latin stays available as a secondary option for Turkish",
            tr.any { it is OcrBackend.Paddle && it.recPackKey == "paddle-rec-latin" },
        )
    }

    @Test fun otherLatinLanguagesStillDefaultToPaddle() {
        // Control: only VI/TR flipped — other Latin scripts keep Paddle first.
        assertEquals("paddle", backends(SourceLangId.FR).first().selectionToken)
        assertEquals("paddle", backends(SourceLangId.ES).first().selectionToken)
    }

    @Test fun japaneseStillDefaultsToMeiki() {
        assertEquals("meiki", backends(SourceLangId.JA).first().selectionToken)
    }

    @Test fun russianHasOnlyTheCyrillicPaddleRecognizerAndNoMlKitFloor() {
        val ru = backends(SourceLangId.RU)
        assertEquals("Cyrillic Paddle is Russian's only backend", 1, ru.size)
        assertTrue(ru.any { it is OcrBackend.Paddle && it.recPackKey == "paddle-rec-cyrillic" })
        assertFalse(
            "Russian has no ML Kit floor (no ML Kit Cyrillic recognizer)",
            OcrModelManager.hasMlKitFloor(SourceLangId.RU),
        )
    }

    @Test fun flooredLanguagesReportAnMlKitFloor() {
        assertTrue(OcrModelManager.hasMlKitFloor(SourceLangId.JA))
        assertTrue(OcrModelManager.hasMlKitFloor(SourceLangId.EN))
    }

    // ── Native-runtime (arm64/MNN) gate ──────────────────────────────────
    // The app ships an armeabi-v7a slice (installs on 32-bit) but :mnn is
    // arm64-only, so the MNN-backed OCR engines must be runtime-incompatible on
    // a 32-bit process — otherwise setup/Settings would offer, download, and
    // select an engine that can't load and silently drops to ML Kit.

    @Test fun mnnBackedEnginesDeclareTheNativeRequirement() {
        assertTrue(OcrBackend.Meiki("meiki-ja").requiresMnn)
        assertTrue(OcrBackend.Paddle("paddle-rec-cjk").requiresMnn)
        // ML Kit + Tesseract don't touch the MNN runtime.
        assertFalse(OcrBackend.MLKitJapanese.requiresMnn)
        assertFalse(OcrBackend.MLKitLatin.requiresMnn)
        assertFalse(OcrBackend.Tesseract("ara").requiresMnn)
    }

    @Test fun on32BitOnlyMlKitEnginesAreRuntimeCompatible() {
        assertFalse(OcrModelManager.isRuntimeCompatible(OcrBackend.Meiki("meiki-ja"), mnnAvailable = false))
        assertFalse(OcrModelManager.isRuntimeCompatible(OcrBackend.Paddle("paddle-rec-cjk"), mnnAvailable = false))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.MLKitJapanese, mnnAvailable = false))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.MLKitLatin, mnnAvailable = false))
    }

    @Test fun on64BitMnnBackedEnginesAreRuntimeCompatible() {
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.Meiki("meiki-ja"), mnnAvailable = true))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.Paddle("paddle-rec-cjk"), mnnAvailable = true))
    }
}
