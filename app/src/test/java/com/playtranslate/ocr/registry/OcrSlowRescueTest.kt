package com.playtranslate.ocr.registry

import com.playtranslate.language.OcrBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the slow-OCR rescue rule ([OcrModelManager.slowOcrRescue]): floored
 * languages keep offering the ML Kit floor (flat latency vs text density —
 * the right property for the text-heavy captures that trip the threshold);
 * no-floor languages offer the Paddle FAST tier, the only faster option they
 * have; and there is never an offer when the selection already IS the rescue.
 */
class OcrSlowRescueTest {

    private val accurate = OcrBackend.Paddle("paddle-rec-cyrillic")
    private val fast = OcrBackend.Paddle("paddle-rec-cyrillic", fast = true)
    private val noFloorList = listOf(accurate, fast)

    @Test fun flooredLanguageOffersTheFloor() {
        assertEquals(
            OcrBackend.MLKitLatin,
            OcrModelManager.slowOcrRescue(
                available = listOf(OcrBackend.Paddle("paddle-rec-unified"),
                    OcrBackend.Paddle("paddle-rec-unified", fast = true), OcrBackend.MLKitLatin),
                selected = OcrBackend.Paddle("paddle-rec-unified"),
                mlKitFloor = OcrBackend.MLKitLatin,
            ),
        )
    }

    @Test fun flooredLanguageAlreadyOnTheFloorGetsNoOffer() {
        assertNull(
            OcrModelManager.slowOcrRescue(
                available = listOf(OcrBackend.MLKitLatin),
                selected = OcrBackend.MLKitLatin,
                mlKitFloor = OcrBackend.MLKitLatin,
            ),
        )
    }

    @Test fun noFloorLanguageOffersTheFastTier() {
        assertEquals(
            fast,
            OcrModelManager.slowOcrRescue(
                available = noFloorList, selected = accurate, mlKitFloor = null,
            ),
        )
    }

    @Test fun noFloorLanguageAlreadyOnFastGetsNoOffer() {
        assertNull(
            OcrModelManager.slowOcrRescue(
                available = noFloorList, selected = fast, mlKitFloor = null,
            ),
        )
    }

    @Test fun noFloorLanguageWithoutAFastTierGetsNoOffer() {
        assertNull(
            OcrModelManager.slowOcrRescue(
                available = listOf(accurate), selected = accurate, mlKitFloor = null,
            ),
        )
    }
}
