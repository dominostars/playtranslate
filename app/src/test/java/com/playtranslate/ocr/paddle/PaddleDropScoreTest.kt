package com.playtranslate.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [PaddleRecognizer.DROP_SCORE] and its gate. The threshold was calibrated
 * on device over two sessions (see the constant's kdoc): sprite-cluster junk
 * topped out at 0.56, real words started at 0.79. A change to the constant must
 * be a deliberate re-calibration, so the value itself is asserted, not just the
 * comparison.
 */
class PaddleDropScoreTest {

    @Test
    fun thresholdIsCalibratedValue() {
        assertEquals(0.6f, PaddleRecognizer.DROP_SCORE, 0f)
    }

    @Test
    fun junkBandIsDropped() {
        // The observed junk band, top specimens included: 东 / 61 at 0.48 cleared
        // nothing; 年日元日 / のEト at 0.56 cleared upstream's 0.5 and were translated.
        assertFalse(PaddleRecognizer.passesDropScore(0.08f))
        assertFalse(PaddleRecognizer.passesDropScore(0.26f))
        assertFalse(PaddleRecognizer.passesDropScore(0.48f))
        assertFalse(PaddleRecognizer.passesDropScore(0.56f))
        assertFalse(PaddleRecognizer.passesDropScore(0.59f))
    }

    @Test
    fun thresholdIsInclusive() {
        assertTrue(PaddleRecognizer.passesDropScore(0.6f))
    }

    @Test
    fun realReadsSurvive() {
        // The real-word floor (0.79, a mid-typewriter partial) and typical dialogue.
        assertTrue(PaddleRecognizer.passesDropScore(0.79f))
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
