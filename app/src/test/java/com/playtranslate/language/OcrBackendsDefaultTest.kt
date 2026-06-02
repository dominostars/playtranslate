package com.playtranslate.language

import com.playtranslate.ocr.registry.selectionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the per-language default OCR backend (= first in the priority list).
 * Headline case: Vietnamese defaults to ML Kit (not the Paddle latin recognizer),
 * while still offering Paddle as a secondary option.
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

    @Test fun otherLatinLanguagesStillDefaultToPaddle() {
        // Control: only Vietnamese flipped — other Latin scripts keep Paddle first.
        assertEquals("paddle", backends(SourceLangId.FR).first().selectionToken)
        assertEquals("paddle", backends(SourceLangId.ES).first().selectionToken)
    }

    @Test fun japaneseStillDefaultsToMeiki() {
        assertEquals("meiki", backends(SourceLangId.JA).first().selectionToken)
    }
}
