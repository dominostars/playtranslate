package com.playtranslate

import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

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
    /** [state] with each value paired with the one the same collection received
     *  just before it, null for a collection's first. The previous value lives
     *  inside each collection, so a new collector, the same collector after a
     *  lifecycle STOP/START, and another session all start without one: what a
     *  collector compares a state against ([CaptureState.Translating.fillsBoxesOf])
     *  is always this session's last state as that collector handled it, never
     *  an earlier capture's. */
    val stateWithPrevious: Flow<Pair<CaptureState?, CaptureState>> = flow {
        var previous: CaptureState? = null
        state.collect { current ->
            emit(previous to current)
            previous = current
        }
    }

    /** Cancel the underlying capture pipeline. The state flow stops
     *  receiving updates; whatever terminal state has already been
     *  written remains observable. */
    fun cancel() { job.cancel() }
}

/**
 * Everything needed to paint a one-shot result as in-place overlay boxes over
 * the game (the capture panel's collapsed "show on screen" state): color-sampled
 * [boxes] plus the crop/screen geometry [com.playtranslate.ui.TranslationOverlayView]
 * maps them with. Built at pipeline time, since the raw frame the colors come from
 * is recycled before any state lands. On [CaptureState.Translating] a box whose
 * translation has not arrived yet is a SKELETON (empty [TextBox.translatedText],
 * which the overlay view renders as pulsing placeholder lines); each later
 * Translating of the same session carries the same boxes with more of them
 * translated. On [CaptureState.Done] they carry the translations, except on a
 * deferred Done ([com.playtranslate.model.TranslationResult.pendingTranslation]
 * non-null), which keeps the skeletons until the deferred completion fills them
 * via [fillOneShotOverlayData].
 */
data class OneShotOverlayData(
    val boxes: List<TextBox>,
    val cropLeft: Int,
    val cropTop: Int,
    val screenshotW: Int,
    val screenshotH: Int,
    /** The OCR crop's size: the capture region the boxes came from, which
     *  their minimum-text-size growth stays inside. */
    val cropWidth: Int,
    val cropHeight: Int,
    /** The drawn rects of every OCR group the capture read, boxed or not, in
     *  the boxes' crop coordinates, so growth stays clear of text whose box
     *  was dropped (a blank translation, [fillOneShotOverlayData]). */
    val text: List<android.graphics.Rect>,
) {
    /** Growth limits for painting these boxes: the capture region, in the
     *  boxes' own crop coordinates, and clear of the read text no box covers. */
    val growthLimits: com.playtranslate.ui.GrowthLimits
        get() = com.playtranslate.ui.GrowthLimits(
            bounds = android.graphics.Rect(0, 0, cropWidth, cropHeight),
            avoid = com.playtranslate.ui.GrowthLimits.unboxedText(text, boxes),
        )
}

/** Zip per-group translated [texts] into [skeleton]'s index-aligned boxes and
 *  drop the ones that came back blank. Null when nothing survives (null/absent
 *  skeleton, count mismatch, every translation blank) — callers then keep/clear
 *  their presentation rather than paint empty boxes. Shared by the capture
 *  pipeline (translations arrive with the cycle) and the deferred-translation
 *  completion (translations arrive when the user reveals the section). */
internal fun fillOneShotOverlayData(
    skeleton: OneShotOverlayData?,
    texts: List<String>,
): OneShotOverlayData? {
    if (skeleton == null || skeleton.boxes.size != texts.size) return null
    val filled = skeleton.boxes.mapIndexed { idx, box ->
        box.copy(translatedText = texts[idx])
    }.filter { it.translatedText.isNotBlank() }
    if (filled.isEmpty()) return null
    return skeleton.copy(boxes = filled)
}

/** [skeleton] with a translation filled in progress: box i takes `known[i]` as
 *  its text where that is known and not blank, and every other box stays as it
 *  is, a skeleton until its translation arrives or whatever an earlier fill gave
 *  it (unlike [fillOneShotOverlayData], which drops what is still blank once the
 *  translations are final). [skeleton] unchanged when it is null, when the sizes
 *  differ, or when nothing is known. Feeds [CaptureState.Translating]'s boxes:
 *  the first paint, over the bare skeletons, and each fill before Done, over the
 *  first paint, so what the first paint showed stays where a fill names nothing. */
internal fun partlyFillOneShotOverlayData(
    skeleton: OneShotOverlayData?,
    known: List<String?>,
): OneShotOverlayData? {
    if (skeleton == null || skeleton.boxes.size != known.size) return skeleton
    if (known.none { !it.isNullOrBlank() }) return skeleton
    return skeleton.copy(
        boxes = skeleton.boxes.mapIndexed { i, box ->
            val text = known[i]
            if (text.isNullOrBlank()) box else box.copy(translatedText = text)
        },
    )
}

sealed class CaptureState {
    /** Pipeline is in flight. [message] is the user-facing status
     *  text for this stage (Capturing / OCR). */
    data class InProgress(val message: String) : CaptureState()

    /** OCR finished: the source ([originalText] / [segments]) is ready and shown
     *  while the translation runs; followed by [Done] (or [Cancelled] / [Failed]).
     *  Lets the UI reveal the page on OCR rather than waiting for the translation.
     *  A session can emit it more than once as translations arrive group by
     *  group: every later one repeats the source and provenance and only fills
     *  more of [overlayData]'s boxes ([fillsBoxesOf]), so a collector updates
     *  the boxes alone. */
    data class Translating(
        val originalText: String,
        val segments: List<TextSegment>,
        /** Provenance of the OCR that produced [originalText], so the source
         *  "Scanned by …" attribution can show as soon as OCR finishes, before the
         *  translation lands. Null for non-OCR placeholders (drag/sentence/edit). */
        val ocrProvenance: OcrProvenance? = null,
        /** Overlay boxes (colors + bounds sampled) for the capture panel's
         *  on-screen presentation, so an auto-collapse can show the capture over
         *  the game while the translation runs: a box is a pulsing skeleton until
         *  its translation arrives, and even the first Translating may carry some
         *  translated. Null when there's nothing paintable. */
        val overlayData: OneShotOverlayData? = null,
    ) : CaptureState()

    /** Pipeline finished with a translation. [overlayData] carries the
     *  on-screen overlay boxes for the capture panel's "show on screen"
     *  presentation — null when the pipeline couldn't build them (no
     *  groups, count mismatch, every translation blank). */
    data class Done(
        val result: TranslationResult,
        val overlayData: OneShotOverlayData? = null,
    ) : CaptureState()

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

/** Whether this state differs from [previous], the state the same collection
 *  handled just before it, only in its boxes: the same capture, with more of
 *  them filled. A collector then updates the boxes and leaves everything else as
 *  the first Translating set it. [previous] must come from
 *  [CaptureSession.stateWithPrevious], which makes it null for a collection's
 *  first state: a second capture of an identical screen compares equal here, so
 *  only that scoping keeps it from passing for a box fill of the first. */
internal fun CaptureState.Translating.fillsBoxesOf(previous: CaptureState?): Boolean =
    previous is CaptureState.Translating && previous.copy(overlayData = null) == copy(overlayData = null)

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

    /** Live mode is STARTING — it has been asked to run but no cycle has
     *  landed yet, so nothing is known about the screen. A cycle that does
     *  run and finds nothing lands on [NoText] instead: "we haven't looked
     *  yet" and "we looked and there was nothing" are different facts and
     *  the panel says different things about them. */
    object Searching : PanelState()

    /** The most recent live (or hold-to-preview) cycle found no
     *  source-language text. Renders as the same status the one-shot's
     *  [CaptureState.NoText] does — source-language name tappable to change
     *  it, OCR-switch gear — but carries no pinned screenshot: while live
     *  mode runs, a gear tap is acted on by the LOOP (next look, new engine)
     *  rather than by re-reading a frozen frame. See
     *  [CaptureService.emitLiveNoText]. */
    data class NoText(
        val message: String,
        val ocrProvenance: OcrProvenance? = null,
    ) : PanelState()

    /** Most recent successful background result. */
    data class Result(val result: TranslationResult) : PanelState()

    /** Most recent background cycle failed. Live mode keeps running
     *  — the next cycle may produce [Result] or [NoText]. */
    data class Error(val message: String) : PanelState()
}
