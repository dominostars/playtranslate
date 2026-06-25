package com.playtranslate

import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-shot capture session's cancellation contract: every session must land
 * on a terminal state (Done / NoText / Failed / Cancelled) — including when it's
 * cancelled mid-translation while showing the OCR-ready [CaptureState.Translating]
 * (the slow window). Exercises [cancelledStateOrNull] (Layer D's decision) and
 * [CaptureState.isTerminal] directly, away from the Android-heavy pipeline.
 */
class CaptureStateTest {

    private val result = TranslationResult(
        originalText = "ソース",
        segments = emptyList(),
        translatedText = "source",
        timestamp = "00:00:00",
    )

    // ─── isTerminal ─────────────────────────────────────────────────────

    @Test fun `in-flight states are not terminal`() {
        assertFalse(CaptureState.InProgress("Capturing").isTerminal)
        assertFalse(CaptureState.Translating("ソース", emptyList()).isTerminal)
    }

    @Test fun `finished states are terminal`() {
        assertTrue(CaptureState.Done(result).isTerminal)
        assertTrue(CaptureState.NoText("none").isTerminal)
        assertTrue(CaptureState.Failed("boom").isTerminal)
        assertTrue(CaptureState.Cancelled.isTerminal)
    }

    // ─── cancelledStateOrNull (Layer D decision) ────────────────────────

    @Test fun `cancel while Translating lands on Cancelled`() {
        // The regression: the slow post-OCR translation window must still cancel
        // cleanly, not leave the session stuck on Translating forever.
        assertEquals(
            CaptureState.Cancelled,
            cancelledStateOrNull(CancellationException(), CaptureState.Translating("ソース", emptyList())),
        )
    }

    @Test fun `cancel while InProgress lands on Cancelled`() {
        assertEquals(
            CaptureState.Cancelled,
            cancelledStateOrNull(CancellationException(), CaptureState.InProgress("OCR")),
        )
    }

    @Test fun `cancel does not overwrite an already-terminal state`() {
        assertNull(cancelledStateOrNull(CancellationException(), CaptureState.Done(result)))
        assertNull(cancelledStateOrNull(CancellationException(), CaptureState.Cancelled))
    }

    @Test fun `non-cancellation completion leaves the state alone`() {
        assertNull(cancelledStateOrNull(RuntimeException("boom"), CaptureState.Translating("ソース", emptyList())))
        assertNull(cancelledStateOrNull(null, CaptureState.Translating("ソース", emptyList())))
    }
}
