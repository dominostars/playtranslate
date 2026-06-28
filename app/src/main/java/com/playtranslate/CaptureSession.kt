package com.playtranslate

import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
//  RESULT-SURFACE CHANNELS — orientation
//
//  CaptureService surfaces translation activity to the UI through two
//  deliberately distinct channels that exist for different reasons:
//
//  1. CaptureSession (defined in this file).
//     A bounded, per-cycle session for a single user-initiated one-shot
//     capture. captureOnce() / processScreenshot() return a fresh
//     CaptureSession whose StateFlow walks through InProgress messages
//     and lands on exactly one terminal state (Done / NoText / Failed /
//     Cancelled). The session is born with the launched coroutine and
//     dies with it — no service-global cache for late subscribers, so a
//     freshly-launched per-capture TranslationResultActivity literally
//     cannot observe the previous capture's output.
//
//  2. PanelState (also in this file), exposed via
//     CaptureService.panelState: StateFlow<PanelState>.
//     A continuous, sticky stream for background result producers (live
//     mode, hold-to-preview, FuriganaMode) feeding MainActivity's panel.
//     Each emission overwrites the last; STOP→START reattach delivers
//     the current value to the new subscriber so the user sees the
//     latest live result on resume. The VM's identity dedup
//     (lastSeenServiceResult) prevents that replay from displacing a
//     local update like a drag-sentence result.
//
//  Why two channels rather than one. CaptureSession's lifecycle is
//  terminal (a one-shot ends and is gone), and the TranslationResultActivity
//  observer is per-launch, so per-session ownership is the natural shape.
//  PanelState's lifecycle is open-ended (live mode runs until the user
//  stops it, hold-to-preview lingers until something replaces it), and
//  MainActivity's observer is long-lived, so a sticky StateFlow with
//  VM-side identity dedup is the natural shape there.
//
//  This split traces to the *current* product UX (foreground per-capture
//  surface vs. background passive panel). If the UX ever unifies — e.g.
//  a persistent panel that owns one-shot results too — these channels
//  would collapse. Worth knowing before extending either.
//
//  Cancellation correctness for one-shot sessions is a four-layer
//  defense; see CaptureService.attachCancellationTerminal for the full
//  architecture.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single one-shot capture pipeline (capture/process screenshot →
 * OCR → translate). Returned by [CaptureService.captureOnce] and
 * [CaptureService.processScreenshot]. The caller owns this session
 * for as long as it cares about the outcome; the StateFlow walks
 * through [CaptureState.InProgress] entries and lands on a terminal
 * [CaptureState.Done], [CaptureState.NoText], [CaptureState.Failed],
 * or [CaptureState.Cancelled].
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
     *  text for this stage (Capturing / OCR). */
    data class InProgress(val message: String) : CaptureState()

    /** OCR finished — the source ([originalText] / [segments]) is ready and shown
     *  while the translation runs; followed by [Done] (or [Cancelled] / [Failed]).
     *  Lets the UI reveal the page on OCR rather than waiting for the translation. */
    data class Translating(
        val originalText: String,
        val segments: List<TextSegment>,
        /** Provenance of the OCR that produced [originalText], so the source
         *  "Scanned by …" attribution can show as soon as OCR finishes — before the
         *  translation lands. Null for non-OCR placeholders (drag/sentence/edit). */
        val ocrProvenance: OcrProvenance? = null,
    ) : CaptureState()

    /** Pipeline finished with a translation. */
    data class Done(val result: TranslationResult) : CaptureState()

    /** Pipeline finished without producing usable text (OCR found
     *  nothing recognisable). Not an error — [message] is shown as
     *  a status string. [ocrProvenance] + [screenshotPath] pin the engine,
     *  language, region, and exact screenshot that produced this no-text result,
     *  so the inline "switch OCR tool" gear can re-OCR THAT capture (not a later
     *  one). Null when no OCR engine/screenshot is available. */
    data class NoText(
        val message: String,
        val ocrProvenance: OcrProvenance? = null,
        val screenshotPath: String? = null,
    ) : CaptureState()

    /** Pipeline failed (screenshot couldn't be taken, ML Kit threw,
     *  service not configured, etc.). [message] is shown formatted
     *  as an error. */
    data class Failed(val message: String) : CaptureState()

    /** Job was externally cancelled (e.g. by [CaptureService.startLive]
     *  or by a subsequent one-shot replacing this session) before
     *  reaching a natural terminal state. Activities treat this as
     *  silent — no VM update, just clear the session reference — so
     *  cancellation never surfaces as a flashed error or a stuck
     *  "Capturing" status on lifecycle reattach. */
    object Cancelled : CaptureState()

    /** True for the four states a one-shot session ENDS on (Done / NoText /
     *  Failed / Cancelled); false while still in flight (InProgress / Translating).
     *  The cancellation safety net writes [Cancelled] only over a non-terminal
     *  state, so a new in-flight state must be reflected here. */
    val isTerminal: Boolean
        get() = this is Done || this is NoText || this is Failed || this is Cancelled
}

/** Layer D of the cancellation contract (see [CaptureService]): the state to
 *  write when a one-shot job completes with [completionCause] while [current] is
 *  showing — [CaptureState.Cancelled] if a still in-flight (non-terminal) session
 *  was cancelled, else null (leave whatever terminal state the pipeline already
 *  wrote). Pure, so the contract is unit-tested without the Android-heavy pipeline. */
internal fun cancelledStateOrNull(
    completionCause: Throwable?,
    current: CaptureState,
): CaptureState? =
    if (completionCause is CancellationException && !current.isTerminal) {
        CaptureState.Cancelled
    } else {
        null
    }

/**
 * Background result-stream state — covers live mode, hold-to-preview,
 * and service-level "Idle" signals. The service exposes a single
 * `panelState: StateFlow<PanelState>`; producers update it; the
 * activity observes it once. STOP→START reattach replays the
 * StateFlow's current value, but the VM dedupes service-emitted
 * results separately from local updates (drag-sentence) so the
 * replay can't displace whatever the VM is now showing.
 *
 * Distinct from the per-cycle [CaptureSession] used for a single
 * user-initiated one-shot capture: a [CaptureSession]'s state has
 * terminal entries (Done/NoText/Failed) and dies with the cycle,
 * while the panel state is continuous and lives for the service's
 * lifetime.
 */
sealed class PanelState {
    /** No background activity has produced anything, or the panel
     *  was just invalidated (e.g. region change). */
    object Idle : PanelState()

    /** Live mode (or hold-to-preview) is running but the most recent
     *  cycle found no source-language text. */
    object Searching : PanelState()

    /** Most recent successful background result. */
    data class Result(val result: TranslationResult) : PanelState()

    /** Most recent background cycle failed. Live mode keeps running
     *  — the next cycle may produce [Result] or [Searching]. */
    data class Error(val message: String) : PanelState()
}
