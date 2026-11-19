package com.playtranslate.ui

import android.content.Context
import android.graphics.BitmapFactory
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.fillsBoxesOf
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a results page that follows one capture at a time, and paints no
 * boxes over the game, does with that capture: the session's states written
 * to the page's [TranslationResultViewModel] ([follow]), the translation a
 * hidden section put off ([completeDeferred]), and the re-read of the frame
 * on show with another OCR tool ([reOcr]). One implementation for the hosts
 * of that shape, the standalone [TranslationResultActivity] and the detached
 * window's page; each hands in what only it knows: how long it collects
 * ([collectWhileShown]), a screenshot it was opened with
 * ([pinnedScreenshotPath]), who completes a deferred sentence that is not a
 * capture ([onSentencePending]), and what a capture cancelled from outside
 * means for it ([onCancelled]).
 *
 * MainActivity keeps a mapping of its own: its page also carries the
 * two-screen boxes, reads the original aloud and cancels its edit job from
 * the same collector, on the activity's lifecycle.
 *
 * Main-thread only, like the view-model it writes.
 */
class CapturePageDriver(
    private val vm: TranslationResultViewModel,
    private val appCtx: Context,
    /** Where the deferred completion and the re-read's decode run. */
    private val scope: CoroutineScope,
    private val service: () -> CaptureService?,
    /** Runs the session's collector for as long as the host shows the page:
     *  the activity repeats it over its STARTED lifecycle, so a collector
     *  that comes back re-reads the session's last state. */
    private val collectWhileShown: (suspend () -> Unit) -> Job,
    private val pinnedScreenshotPath: () -> String? = { null },
    private val onSentencePending: () -> Unit = {},
    /** The capture this page followed was cancelled from outside it: live
     *  mode started, another capture took the service's one capture job, a
     *  region changed, the service is going. [shownBefore] is what the page
     *  showed before this capture, or the run of captures it replaced, wrote
     *  to it. */
    private val onCancelled: (shownBefore: ResultState) -> Unit,
) {
    private var session: CaptureSession? = null
    private var collectJob: Job? = null

    /** A followed capture has written to the page and not reached its end. */
    private var inFlight = false

    /** The page's state when the capture in flight (or the first of a run
     *  that replaced one another) began. */
    private var shownBefore: ResultState = ResultState.Idle

    private var reOcrJob: Job? = null

    /** In-flight completion state: [deferredPending] is the pending the
     *  running job is completing. A repeat for the SAME pending is dropped
     *  (the render funnel re-fires on every Ready re-render); a DIFFERENT
     *  pending means a newer deferred result superseded the one in flight,
     *  so the stale job is cancelled and the new one launches and the new
     *  result's completion is never silently dropped. */
    private var deferredJob: Job? = null
    private var deferredPending: PendingTranslation? = null

    /** [session] is the page's capture from now on; one it followed before
     *  is no longer read (its states cannot land over this one's). */
    fun follow(session: CaptureSession) {
        collectJob?.cancel()
        if (!inFlight) shownBefore = vm.result.value
        inFlight = true
        this.session = session
        collectJob = collectWhileShown {
            session.stateWithPrevious.collect { (previous, state) -> apply(previous, state) }
        }
    }

    private fun apply(previous: CaptureState?, state: CaptureState) {
        when (state) {
            is CaptureState.InProgress -> vm.showStatus(state.message)
            // OCR done: reveal the source + "Translating…" placeholder; Done
            // replaces it with the finished translation. A box fill changes
            // nothing this page shows (it paints no boxes), and re-showing
            // the placeholder would restart the word lookups.
            is CaptureState.Translating -> if (!state.fillsBoxesOf(previous)) {
                vm.showTranslatingPlaceholder(state.originalText, state.segments, appCtx, state.ocrProvenance)
            }
            is CaptureState.Done -> {
                inFlight = false
                vm.displayResult(state.result, appCtx)
            }
            is CaptureState.NoText -> {
                inFlight = false
                vm.showStatus(
                    state.message,
                    ocrProvenance = state.ocrProvenance,
                    screenshotPath = state.screenshotPath,
                )
            }
            is CaptureState.Failed -> {
                inFlight = false
                vm.showError(state.message)
            }
            CaptureState.Cancelled -> {
                inFlight = false
                onCancelled(shownBefore)
            }
        }
    }

    /** Stop following and cancel the capture: something of the page's own
     *  replaced what it was reading (an edit of the source, a change of
     *  language). Its Cancelled is not delivered. */
    fun abandon() {
        collectJob?.cancel()
        collectJob = null
        session?.cancel()
        session = null
        inFlight = false
    }

    /** Re-OCR target = engine/region/screenshot of whatever's on screen: a
     *  Ready result or a "no text detected" status. Both prefer the stable
     *  screenshot the host was opened with ([pinnedScreenshotPath]) over the
     *  overwriteable per-display cache file. Null when neither offers
     *  re-OCR, or its frame can't be re-read ([OcrProvenance.canReRead]: one
     *  that carries our own overlays, so the gear isn't offered for it). */
    fun reOcrTarget(): Pair<OcrProvenance, String>? =
        when (val s = vm.result.value) {
            is ResultState.Ready ->
                (s.result.ocrProvenance?.takeIf { it.canReRead } ?: return null) to
                    (pinnedScreenshotPath() ?: s.result.screenshotPath ?: return null)
            is ResultState.Status ->
                (s.ocrProvenance?.takeIf { it.canReRead } ?: return null) to
                    (pinnedScreenshotPath() ?: s.screenshotPath ?: return null)
            else -> null
        }

    /** Re-run the capture pipeline on the frame on show so the page walks the
     *  normal loading stages; [follow] supersedes the session before it. */
    fun reOcr(deviceOnly: Boolean) {
        val svc = service() ?: return
        val (prov, path) = reOcrTarget() ?: return
        reOcrJob?.cancel()
        reOcrJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } ?: return@launch
            follow(
                svc.processScreenshot(
                    CapturedFrame(
                        bmp, includesSystemUi = prov.frameIncludesSystemUi ?: true,
                        includesOwnOverlays = prov.frameIncludesOwnOverlays ?: false,
                        geometry = prov.frameGeometry,
                    ),
                    prov.displayId, prov.region, prov.sourceLangId,
                    deviceOnly = deviceOnly,
                ),
            )
        }
    }

    /** The translation the result on show put off ([PendingTranslation]),
     *  fetched now. Only the one-shot capture shape is this driver's: its
     *  batch translate and session-scoped History attach are the service's.
     *  A deliberate sentence's is its host's ([onSentencePending]). */
    fun completeDeferred() {
        // No service yet: keep the pending; the Ready re-render after the
        // host binds retries.
        val svc = service() ?: return
        val ready = vm.result.value as? ResultState.Ready ?: return
        val pending = ready.result.pendingTranslation ?: return
        if (!pending.isCapture) {
            onSentencePending()
            return
        }
        if (deferredJob?.isActive == true) {
            if (deferredPending == pending) return
            deferredJob?.cancel()
        }
        deferredPending = pending
        deferredJob = scope.launch {
            try {
                val perGroup = svc.completeDeferredTranslation(pending)
                val joined = perGroup.joinToString("\n\n") { it.text }
                vm.applyDeferredTranslation(
                    pending,
                    if (joined.isBlank()) "—" else joined,
                    perGroup.mapNotNull { it.note }.firstOrNull(),
                    perGroup.mapNotNull { it.backendDisplayName }.firstOrNull(),
                )
            } catch (e: CancellationException) {
                // Superseded (or the host is going away): the newer job
                // owns the state now; never land "—" for a cancelled run.
                throw e
            } catch (_: Exception) {
                // Ran and failed: land terminal. A visible blank + pending
                // renders a stuck "Translating…". Mirror of the edit path's
                // "—". Identity-guarded, so this can't damage a newer result.
                vm.applyDeferredTranslation(pending, "—", null, null)
            }
        }
    }
}
