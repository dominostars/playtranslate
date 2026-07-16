package com.playtranslate.ocr.registry

import com.playtranslate.language.OcrBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Settings-trash safety rule around shared packs ([OcrModelManager.canOfferOcrDelete]):
 * since the Paddle speed tiers, a language's OWN backends can share one pack
 * (accurate + fast over the same recognizer), so the delete affordance must key
 * off pack INTERSECTION with the selected backend, not row identity. Regression
 * coverage for the review finding that an unselected tier row could delete the
 * selected tier's files out from under a live capture.
 */
class OcrDeleteGuardTest {

    private val accurate = OcrBackend.Paddle("paddle-rec-cyrillic")
    private val fast = OcrBackend.Paddle("paddle-rec-cyrillic", fast = true)

    @Test fun siblingTierCannotDeleteTheSelectedTiersPack() {
        assertFalse(
            "fast row must not offer delete while accurate is selected",
            OcrModelManager.canOfferOcrDelete(row = fast, selected = accurate),
        )
        assertFalse(
            "accurate row must not offer delete while fast is selected",
            OcrModelManager.canOfferOcrDelete(row = accurate, selected = fast),
        )
        assertFalse(
            "the selected row itself is never deletable",
            OcrModelManager.canOfferOcrDelete(row = accurate, selected = accurate),
        )
    }

    @Test fun disjointBackendsRemainDeletable() {
        // ML Kit selected (no packs): both tier rows may offer delete.
        assertTrue(OcrModelManager.canOfferOcrDelete(row = accurate, selected = OcrBackend.MLKitLatin))
        assertTrue(OcrModelManager.canOfferOcrDelete(row = fast, selected = OcrBackend.MLKitLatin))
        // Meiki selected for ja: the Paddle rows' pack is not resolved by it.
        assertTrue(
            OcrModelManager.canOfferOcrDelete(
                row = OcrBackend.Paddle("paddle-rec-unified"),
                selected = OcrBackend.Meiki("meiki-ja"),
            ),
        )
        // Nothing resolvable (no selection at all): deletable.
        assertTrue(OcrModelManager.canOfferOcrDelete(row = accurate, selected = null))
    }
}
