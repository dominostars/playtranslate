package com.playtranslate.capture

import android.graphics.Bitmap
import android.view.Choreographer
import com.playtranslate.CaptureService
import com.playtranslate.DetectionLog
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.overlay.WindowChurnGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * [LiveCaptureSource] backed by MediaProjection — one-shot clean/raw captures
 * and the continuous frame loop live mode depends on, all sourced from the
 * mirrored [MediaProjectionController] VirtualDisplay.
 *
 * The loop mirrors [com.playtranslate.ScreenshotManager]'s per-display `Loop`
 * design (a `cleanRequested` flag, first frame clean, a shared [captureMutex])
 * minus the platform rate limit — MediaProjection streams, so there is no
 * `takeScreenshot`-style 500 ms floor, only [MIN_LOOP_INTERVAL_MS] to keep a
 * misconfigured poll-interval pref from spinning the VirtualDisplay.
 */
class MediaProjectionCaptureSource(
    private val controller: MediaProjectionController,
) : LiveCaptureSource {

    /** Serializes every capture (one-shot AND loop, clean AND raw). The
     *  controller's VirtualDisplay / ImageReader are shared mutable state, so
     *  captures must not interleave — mirrors ScreenshotManager.captureMutex. */
    private val captureMutex = Mutex()

    /** Delivery-seq clock handed to [WindowChurnGate] so every defuse records
     *  the anchor the ghost-only clean-capture proof waits against (see
     *  [cleanCapture]). Kept as a field so [destroy] only clears the gate's
     *  clock if it is still OURS — a backend swap may construct the new
     *  source before destroying this one. */
    private val ghostAnchorClock: () -> Long = { controller.deliverySeqNow }

    init {
        WindowChurnGate.deliverySeqClock = ghostAnchorClock
    }

    /** A task-scoped ("single app") mirror contains no system UI at all;
     *  whole-display mirrors do. See [CaptureSource.framesIncludeSystemUi]. */
    override val framesIncludeSystemUi: Boolean
        get() = controller.streamKind != StreamKind.CLEAN

    /** No platform rate limit applies (unlike AccessibilityService) — this is
     *  only the floor that keeps a misconfigured pref from spinning the loop. */
    override val minCaptureIntervalMs: Long get() = MIN_LOOP_INTERVAL_MS

    /** Frame deliveries from the mirrored VirtualDisplay — the streaming
     *  backend's "screen changed" signal (see [DeliverySignal]). */
    override val deliverySignal: DeliverySignal get() = controller.deliverySignal

    override val contentVisible get() = controller.contentVisible

    // ── One-shot capture ─────────────────────────────────────────────────

    /** Wrap a just-captured bitmap with the facts as they are AT SERVE
     *  TIME — the single stamping point for this source; consumers read the
     *  frame, never the live properties (see [CapturedFrame]).
     *  [ownOverlaysBlanked]: whether this serve path blanked our overlay
     *  windows pre-grab ([cleanCapture]). On a CLEAN task mirror the answer
     *  is moot — our windows never composite in, so the frame is
     *  own-overlay-free either way ([framesIncludeSystemUi] false). */
    private fun stamp(bitmap: Bitmap, ownOverlaysBlanked: Boolean): CapturedFrame =
        CapturedFrame(
            bitmap,
            includesSystemUi = framesIncludeSystemUi,
            includesOwnOverlays = framesIncludeSystemUi && !ownOverlaysBlanked,
        )

    override suspend fun requestClean(displayId: Int): CapturedFrame? {
        // One-shot capture: prompt for consent up front so first-use paths
        // (Translate button, drag-lookup, region OCR, scene-detect's
        // captureScreen) work before screen-record has been granted via the
        // activate flow. Returning null without prompting would make these
        // entry points silently fail on a fresh MediaProjection session.
        //
        // The continuous loop ([startLoop]) and Pinhole's [requestRaw] cycle
        // deliberately do NOT prompt: a consent dialog launched while a
        // capture loop is running has its Cancel tap caught by the 1×1
        // outside-touch sentinel as game input, creating an unbreakable
        // re-prompt cycle. One-shots have no such race — no sentinel exists
        // unless live mode is running, and live mode running means consent
        // is already held so this [ensureConsent] short-circuits.
        if (!controller.ensureConsent()) return null
        // One-shot capture can be the session's FIRST capture — the consent
        // dialog above may have just offered the single-app choice. Resolve
        // the stream kind before the gated clean path runs: on a task-scoped
        // stream the blank-and-await-repaint protocol can never be satisfied
        // (our windows don't composite into the mirror), so cleanCapture
        // must know to short-circuit. Cached per session; instant thereafter.
        controller.resolveStreamKind()
        return captureMutex.withLock { cleanCapture(displayId) }
            ?.let { stamp(it, ownOverlaysBlanked = true) }
    }

    override suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)?): CapturedFrame? =
        captureMutex.withLock {
            // MediaProjection exposes no separate "buffer captured" moment —
            // captureFrame returns the finished bitmap — so fire onCaptured
            // right after it returns. Looser timing than the accessibility
            // path, but acceptable: the callback only restores overlay alpha.
            captureProjectedFrame(displayId).also {
                onCaptured?.invoke()
                // PinholeOverlayMode drives its own cycle via requestRaw (not
                // startLoop), so the loop's consent guard wouldn't cover it.
                checkConsentLost(it)
            }?.let { stamp(it, ownOverlaysBlanked = false) }
        }

    /**
     * Clean capture: blank this backend's overlays so they don't appear in the
     * mirror, wait for the compositor to flush, capture, restore.
     *
     * MUST be called while holding [captureMutex] — [requestClean] and the
     * loop's clean branch both wrap it. The mutex is not reentrant; this
     * helper never re-locks.
     */
    private suspend fun cleanCapture(displayId: Int): Bitmap? {
        // Task-scoped ("single app") stream: this backend's overlay windows
        // are structurally absent from the mirror — there is nothing to
        // blank — and our own window mutations never composite into it, so
        // a post-blank freshness gate would only burn its budget waiting for
        // a repaint delivery that cannot come. Serve the current frame —
        // but only when its geometry is proven (see refuseUnprovenGeometry).
        if (controller.streamKind == StreamKind.CLEAN) {
            if (refuseUnprovenGeometry()) return null
            warnIfNotProjected(displayId)
            return controller.captureFrameUngated()
        }
        val host = CaptureBackendResolver.active().overlayHost
        // Anchor BEFORE the blank. The blank's own repaint must satisfy the
        // freshness predicate — anchoring after the blank was submitted let
        // the repaint land under the marker on fast commits and starved the
        // wait on static screens (2026-07-10 review finding). The converse
        // hazard — a pre-blank game frame delivered after this anchor —
        // shares the shipped app's take-latest tolerance: the vsync wait
        // below covers the blank's commit latency, and
        // [MediaProjectionController.captureFrameNewerThan] serves the
        // NEWEST frame above the anchor (see its kdoc for why a stricter
        // quiescence proof was removed).
        val seqBefore = controller.deliverySeqNow
        val state = host?.prepareForCleanCapture(displayId)
        return try {
            if (state == null || (!state.blankedAnything && !state.uncompositedGhost)) {
                // Nothing of ours can reach a frame: no visible overlay on
                // this display and no defused-ghost repaint in flight —
                // gating on the anchor would burn the whole freshness budget
                // on every quiet-screen one-shot and then serve the same
                // frame it could have served immediately. Take the current
                // frame, without advancing the live delivery-gate cursor.
                warnIfNotProjected(displayId)
                controller.captureFrameUngated()
            } else if (!state.blankedAnything) {
                // Ghost only. Its repaint was PRODUCED (it was visible when
                // defused — see OverlayState.uncompositedGhost) but the
                // defuse ran before this call, so seqBefore is useless as an
                // anchor (the repaint may already sit below it). The gate
                // recorded the delivery seq BEFORE each defuse was submitted:
                // the repaint is guaranteed to be delivered above that
                // anchor, so waiting on it terminates AND proves the served
                // frame postdates the defuse, with the same take-newest
                // tolerance as the blanked branch below. Anchor null means
                // either every ghost composited since prepare (current frame
                // is fine) or a ghost predates the projection's clock
                // (near-unreachable — projection startup dwarfs the ghost
                // composite window); the heuristic keeps both from failing.
                val anchor = WindowChurnGate.ghostDefuseAnchorOn(displayId)
                waitVsync(2)
                warnIfNotProjected(displayId)
                if (anchor != null) {
                    controller.captureFrameNewerThan(anchor)
                } else {
                    controller.captureFrameNewerThan(seqBefore)
                        ?: controller.captureFrameUngated()
                }
            } else {
                // Something was blanked (any ghost rides along — its defuse
                // committed even earlier than the blank). The blank's repaint
                // is guaranteed to come and lands above seqBefore, so the
                // anchored wait terminates; the vsync wait just keeps the
                // freshness loop from spinning through commit latency.
                waitVsync(2)
                warnIfNotProjected(displayId)
                controller.captureFrameNewerThan(seqBefore)
            }
        } finally {
            if (host != null && state != null) host.restoreAfterCapture(state)
        }
    }

    override fun saveToCache(bitmap: Bitmap, displayId: Int): String? {
        val ctx = CaptureService.instance ?: return null
        return CaptureCache.save(ctx, bitmap, displayId)
    }

    override fun destroy() {
        stopAllLoops()
        controller.destroy()
        // Only clear the gate's clock if it is still ours — a replacement
        // source may already have installed its own.
        if (WindowChurnGate.deliverySeqClock === ghostAnchorClock) {
            WindowChurnGate.deliverySeqClock = null
        }
    }

    // ── Continuous poll loop (live mode) ─────────────────────────────────

    /** Per-display loop state, mirroring ScreenshotManager.Loop. */
    private class Loop(
        val displayId: Int,
        var job: Job? = null,
        @Volatile var cleanRequested: Boolean = false,
    )

    private val loops: MutableMap<Int, Loop> = mutableMapOf()

    override fun startLoop(
        displayId: Int,
        scope: CoroutineScope,
        onCleanFrame: (CapturedFrame) -> Unit,
        onRawFrame: (CapturedFrame) -> Unit,
    ) {
        stopLoop(displayId)
        // First frame is always clean — every caller wants a clean baseline
        // before raw diffs begin (matches ScreenshotManager.startLoop).
        val loop = Loop(displayId = displayId, cleanRequested = true)
        loops[displayId] = loop
        DetectionLog.log("MP Loop[$displayId]: started")
        loop.job = scope.launch {
            var lastCaptureMs = 0L
            while (isActive) {
                // Pace by elapsed-since-last-capture so the poll interval is
                // the capture period, not interval + capture duration.
                val waitMs = pollIntervalMs(displayId) - (System.currentTimeMillis() - lastCaptureMs)
                if (waitMs > 0) delay(waitMs)
                lastCaptureMs = System.currentTimeMillis()

                val isClean = loop.cleanRequested
                if (isClean) loop.cleanRequested = false
                val bitmap = captureMutex.withLock {
                    if (isClean) cleanCapture(displayId)
                    else captureProjectedFrame(displayId)
                }

                when {
                    bitmap != null ->
                        if (isClean) onCleanFrame(stamp(bitmap, ownOverlaysBlanked = true))
                        else onRawFrame(stamp(bitmap, ownOverlaysBlanked = false))
                    checkConsentLost(bitmap) -> {
                        // Consent denied or revoked — checkConsentLost stopped
                        // live mode; exit before the next captureFrame would
                        // re-launch the consent dialog.
                        DetectionLog.log("MP Loop[$displayId]: consent lost, loop exiting")
                        break
                    }
                    else -> DetectionLog.log("MP Loop[$displayId]: capture failed (transient), skipping frame")
                }
            }
        }
    }

    override fun requestCleanCapture(displayId: Int) {
        loops[displayId]?.cleanRequested = true
    }

    override fun requestCleanCaptureAll() {
        loops.values.forEach { it.cleanRequested = true }
    }

    override fun stopLoop(displayId: Int) {
        loops.remove(displayId)?.job?.cancel()
    }

    override fun stopAllLoops() {
        loops.values.forEach { it.job?.cancel() }
        loops.clear()
    }

    override fun isLoopRunning(displayId: Int): Boolean =
        loops[displayId]?.job?.isActive == true

    override val hasAnyLoop: Boolean
        get() = loops.values.any { it.job?.isActive == true }

    // ── Internal ─────────────────────────────────────────────────────────

    /** [MediaProjectionController.captureFrame], guarded. MediaProjection can
     *  only mirror its projected display, so a capture requested for any other
     *  display is an upstream routing bug — log it loudly. The frame still
     *  comes from the projected display regardless. */
    private suspend fun captureProjectedFrame(requestedDisplayId: Int): Bitmap? {
        if (refuseUnprovenGeometry()) return null
        warnIfNotProjected(requestedDisplayId)
        return controller.captureFrame()
    }

    /** The single geometry choke point for CLEAN (task-scoped) streams: no
     *  frame is served while [MediaProjectionController.frameGeometryProven]
     *  is false, because EVERY consumer of these frames — live modes,
     *  one-shot translate, lens/drag lookups, furigana — maps coordinates
     *  assuming frame == screen, and a letterboxed task's on-screen offset
     *  has no public API (refusal is the honest behavior, not a shortcut).
     *  One user-facing message per unproven episode; callers see a null
     *  capture, which every path already handles. */
    private fun refuseUnprovenGeometry(): Boolean {
        if (controller.streamKind != StreamKind.CLEAN) return false
        if (controller.frameGeometryProven()) {
            nonIdentityNotified = false
            return false
        }
        if (!nonIdentityNotified) {
            nonIdentityNotified = true
            DetectionLog.log("MP: CLEAN frame refused — task geometry unproven or non-fullscreen")
            CaptureService.instance?.let {
                it.emitError(it.getString(R.string.error_single_app_not_fullscreen))
            }
        }
        return true
    }

    @Volatile private var nonIdentityNotified = false

    private fun warnIfNotProjected(requestedDisplayId: Int) {
        if (requestedDisplayId != controller.projectedDisplayId) {
            DetectionLog.log(
                "MP: WARNING — capture requested for display $requestedDisplayId; " +
                    "MediaProjection only mirrors display ${controller.projectedDisplayId}"
            )
        }
    }

    /** Loop poll interval — the user's pref, floored at [MIN_LOOP_INTERVAL_MS]
     *  (no platform rate limit applies, unlike AccessibilityService), and
     *  floored while a [pokeFastPoll] window is open. Read once per
     *  iteration BEFORE the park — the mode's receipt-time poke exists so
     *  the floor is visible at that read (see ScreenshotManager's note). */
    private fun pollIntervalMs(displayId: Int): Long {
        if (pacing.floorActive(displayId, System.currentTimeMillis())) {
            return MIN_LOOP_INTERVAL_MS
        }
        val ctx = CaptureService.instance ?: return MIN_LOOP_INTERVAL_MS
        return maxOf(Prefs(ctx).captureIntervalMs, MIN_LOOP_INTERVAL_MS)
    }

    private val pacing = PacingWindow()

    override fun pokeFastPoll(displayId: Int, windowMs: Long) {
        pacing.poke(displayId, System.currentTimeMillis(), windowMs)
    }

    /** If [captureResult] is a failed capture (null) because MediaProjection
     *  consent is gone — denied at the dialog, or revoked mid-session — and
     *  live mode is running, tear all of live mode down and tell the user.
     *  Staying live would re-launch the consent dialog on the next capture.
     *  Returns true when it handled a consent loss. A null result with consent
     *  still held is a transient failure and is left for the caller to retry. */
    private fun checkConsentLost(captureResult: Bitmap?): Boolean {
        if (captureResult != null || controller.hasConsent) return false
        // Accessibility-backend users only borrow this stream for live
        // capture; on consent loss they fall back to accessibility capture
        // (liveCaptureSourceFor keys off hasConsent) instead of live mode
        // dying. Without this, the outcome would depend on WHERE the revoke
        // landed: mid-capture → stop, mid-park → seamless fallback via the
        // teardown wake. Deterministic fallback for both. MediaProjection-only
        // users keep today's stop — they have nothing to fall back to.
        if (CaptureBackendResolver.active().requiresAccessibilityService) return false
        val svc = CaptureService.instance ?: return false
        if (!svc.isLive) return false
        DetectionLog.log("MP: screen-capture consent lost, stopping live mode")
        svc.emitError(svc.getString(R.string.error_screen_capture_denied))
        svc.stopLive()
        return true
    }

    private suspend fun waitVsync(frames: Int) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private companion object {
        /** Minimum loop poll interval. MediaProjection has no platform rate
         *  limit; this only guards against a misconfigured poll-interval pref. */
        const val MIN_LOOP_INTERVAL_MS = 250L
    }
}
