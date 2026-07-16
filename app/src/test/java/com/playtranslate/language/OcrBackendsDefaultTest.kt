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
            "PaddleOCR stays available as a secondary option for Vietnamese",
            vi.any { it is OcrBackend.Paddle && it.recPackKey == "paddle-rec-unified" },
        )
    }

    @Test fun turkishDefaultsToMlKitButStillOffersPaddle() {
        val tr = backends(SourceLangId.TR)
        assertEquals("ML Kit is Turkish's default", OcrBackend.MLKitLatin, tr.first())
        assertTrue(
            "PaddleOCR stays available as a secondary option for Turkish",
            tr.any { it is OcrBackend.Paddle && it.recPackKey == "paddle-rec-unified" },
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

    @Test fun russianHasOnlyTheCyrillicPaddleTiersAndNoMlKitFloor() {
        val ru = backends(SourceLangId.RU)
        assertEquals(
            "Russian's backends are exactly the two cyrillic Paddle tiers, accurate first",
            listOf(
                OcrBackend.Paddle("paddle-rec-cyrillic"),
                OcrBackend.Paddle("paddle-rec-cyrillic", fast = true),
            ),
            ru,
        )
        assertFalse(
            "Russian has no ML Kit floor (no ML Kit Cyrillic recognizer)",
            OcrModelManager.hasMlKitFloor(SourceLangId.RU),
        )
    }

    @Test fun thaiHasOnlyTheThaiPaddleTiersAndNoMlKitFloor() {
        val th = backends(SourceLangId.TH)
        assertEquals(
            "Thai's backends are exactly the two thai Paddle tiers, accurate first",
            listOf(
                OcrBackend.Paddle("paddle-rec-thai"),
                OcrBackend.Paddle("paddle-rec-thai", fast = true),
            ),
            th,
        )
        assertFalse(
            "Thai has no ML Kit floor (no ML Kit Thai recognizer)",
            OcrModelManager.hasMlKitFloor(SourceLangId.TH),
        )
    }

    // ── Paddle speed tiers ────────────────────────────────────────────────
    // Every Paddle recognizer is offered as accurate + fast over the SAME pack:
    // the tier is runtime configuration, not a second download. The accurate
    // tier keeps the legacy "paddle" token so pre-tier selections are unchanged.

    @Test fun paddleLanguagesOfferBothTiersOverOneSharedPack() {
        for (id in listOf(SourceLangId.FR, SourceLangId.ZH, SourceLangId.KO, SourceLangId.AR)) {
            val paddles = backends(id).filterIsInstance<OcrBackend.Paddle>()
            assertEquals("$id offers exactly two Paddle tiers", 2, paddles.size)
            assertFalse("$id: accurate tier first", paddles[0].fast)
            assertTrue("$id: fast tier second", paddles[1].fast)
            assertEquals("$id: tiers share one pack", paddles[0].recPackKey, paddles[1].recPackKey)
        }
    }

    @Test fun tierTokensAreDistinctAndAccurateKeepsTheLegacyToken() {
        assertEquals("paddle", OcrBackend.Paddle("paddle-rec-unified").selectionToken)
        assertEquals("paddle-fast", OcrBackend.Paddle("paddle-rec-unified", fast = true).selectionToken)
        // Tokens stay unique within a language's list (the picker's identity key).
        val fr = backends(SourceLangId.FR)
        assertEquals(fr.size, fr.map { it.selectionToken }.toSet().size)
    }

    @Test fun hindiHasOnlyTheMlKitDevanagariFloorAndNoPaddle() {
        val hi = backends(SourceLangId.HI)
        // ML Kit Devanagari is the floor and the ONLY backend in v1: the
        // DEVANAGARI -> {} branch stays empty, so paddle-rec-devanagari is dormant.
        assertEquals("ML Kit Devanagari is Hindi's only backend", listOf(OcrBackend.MLKitDevanagari), hi)
        assertTrue(OcrModelManager.hasMlKitFloor(SourceLangId.HI))
        assertFalse("paddle-rec-devanagari stays dormant in v1", hi.any { it is OcrBackend.Paddle })
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
        assertTrue(OcrBackend.Paddle("paddle-rec-unified").requiresMnn)
        // ML Kit + Tesseract don't touch the MNN runtime.
        assertFalse(OcrBackend.MLKitJapanese.requiresMnn)
        assertFalse(OcrBackend.MLKitLatin.requiresMnn)
        assertFalse(OcrBackend.Tesseract("ara").requiresMnn)
    }

    @Test fun on32BitOnlyMlKitEnginesAreRuntimeCompatible() {
        assertFalse(OcrModelManager.isRuntimeCompatible(OcrBackend.Meiki("meiki-ja"), mnnAvailable = false))
        assertFalse(OcrModelManager.isRuntimeCompatible(OcrBackend.Paddle("paddle-rec-unified"), mnnAvailable = false))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.MLKitJapanese, mnnAvailable = false))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.MLKitLatin, mnnAvailable = false))
    }

    @Test fun on64BitMnnBackedEnginesAreRuntimeCompatible() {
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.Meiki("meiki-ja"), mnnAvailable = true))
        assertTrue(OcrModelManager.isRuntimeCompatible(OcrBackend.Paddle("paddle-rec-unified"), mnnAvailable = true))
    }
}
