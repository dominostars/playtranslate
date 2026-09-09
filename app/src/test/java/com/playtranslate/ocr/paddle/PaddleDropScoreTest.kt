package com.playtranslate.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PaddleRecognizer.DROP_SCORE] and its gate. The threshold is PaddleOCR's
 * `drop_score` default and was calibrated on device (see the constant's kdoc):
 * sprite-cluster junk topped out at 0.48, real words started at 0.86. A change
 * to the constant must be a deliberate re-calibration, so the value itself is
 * asserted, not just the comparison.
 */
class PaddleDropScoreTest {

    @Test
    fun thresholdIsUpstreamDefault() {
        assertEquals(0.5f, PaddleRecognizer.DROP_SCORE, 0f)
    }

    @Test
    fun junkBandIsDropped() {
        // The observed junk band, top specimen included (东 / 61 at 0.48).
        assertFalse(PaddleRecognizer.passesDropScore(0.08f))
        assertFalse(PaddleRecognizer.passesDropScore(0.26f))
        assertFalse(PaddleRecognizer.passesDropScore(0.48f))
        assertFalse(PaddleRecognizer.passesDropScore(0.49f))
    }

    @Test
    fun thresholdIsInclusive() {
        assertTrue(PaddleRecognizer.passesDropScore(0.5f))
    }

    @Test
    fun realReadsSurvive() {
        // Short menu labels that sat just above the line (TP/LV 0.57-0.59) and
        // the real-word floor (0.86).
        assertTrue(PaddleRecognizer.passesDropScore(0.57f))
        assertTrue(PaddleRecognizer.passesDropScore(0.86f))
        assertTrue(PaddleRecognizer.passesDropScore(1.0f))
    }

    @Test
    fun emptyDecodeIsDropped() {
        // decodeCtc reports 0 when nothing fired; blank text is already rejected
        // upstream of the gate, so this only documents the boundary.
        assertFalse(PaddleRecognizer.passesDropScore(0f))
    }
}
