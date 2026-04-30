package com.playtranslate

import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * A single one-shot capture pipeline (capture/process screenshot →
 * OCR → translate). Returned by [CaptureService.captureOnce] and
 * [CaptureService.processScreenshot]. The caller owns this session
 * for as long as it cares about the outcome; the StateFlow walks
 * through [CaptureState.InProgress] entries and lands on a terminal
 * [CaptureState.Done], [CaptureState.NoText], or
 * [CaptureState.Failed].
 *
 * Sessions exist instead of a service-global "latest result"
 * StateFlow so a fresh consumer (e.g. a per-capture
 * [com.playtranslate.ui.TranslationResultActivity]) can't observe
 * the previous capture's output before its own emissions land —
 * each session's StateFlow is born with the cycle and discarded
 * with it.
 */
class CaptureSession internal constructor(
    val state: StateFlow<CaptureState>,
    private val job: Job,
) {
    /** Cancel the underlying capture pipeline. The state flow stops
     *  receiving updates; whatever terminal state has already been
     *  written remains observable. */
    fun cancel() { job.cancel() }
}

sealed class CaptureState {
    /** Pipeline is in flight. [message] is the user-facing status
     *  text for this stage (Capturing / OCR / Translating). */
    data class InProgress(val message: String) : CaptureState()

    /** Pipeline finished with a translation. */
    data class Done(val result: TranslationResult) : CaptureState()

    /** Pipeline finished without producing usable text (OCR found
     *  nothing recognisable). Not an error — [message] is shown as
     *  a status string. */
    data class NoText(val message: String) : CaptureState()

    /** Pipeline failed (screenshot couldn't be taken, ML Kit threw,
     *  service not configured, etc.). [message] is shown formatted
     *  as an error. */
    data class Failed(val message: String) : CaptureState()
}
