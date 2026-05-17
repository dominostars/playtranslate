package com.playtranslate.capture

import android.graphics.Bitmap
import android.view.Choreographer
import com.playtranslate.CaptureService
import com.playtranslate.DetectionLog
import com.playtranslate.Prefs
import com.playtranslate.R
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

    /** No platform rate limit applies (unlike AccessibilityService) — this is
     *  only the floor that keeps a misconfigured pref from spinning the loop. */
    override val minCaptureIntervalMs: Long get() = MIN_LOOP_INTERVAL_MS

    // ── One-shot capture ─────────────────────────────────────────────────

    override suspend fun requestClean(displayId: Int): Bitmap? =
        captureMutex.withLock { cleanCapture(displayId) }

    override suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)?): Bitmap? =
        captureMutex.withLock {
            // MediaProjection exposes no separate "buffer captured" moment —
            // captureFrame returns the finished bitmap — so fire onCaptured
            // right after it returns. Looser timing than the accessibility
            // path, but acceptable: the callback only restores overlay alpha.
            controller.captureFrame(displayId).also {
                onCaptured?.invoke()
                // PinholeOverlayMode drives its own cycle via requestRaw (not
                // startLoop), so the loop's consent guard wouldn't cover it.
                checkConsentLost(it)
            }
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
        val host = CaptureBackendResolver.active().overlayHost
        val state = host?.prepareForCleanCapture(displayId)
        return try {
            waitVsync(2)
            controller.captureFrame(displayId)
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
        onCleanFrame: (Bitmap) -> Unit,
        onRawFrame: (Bitmap) -> Unit,
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
                val waitMs = pollIntervalMs() - (System.currentTimeMillis() - lastCaptureMs)
                if (waitMs > 0) delay(waitMs)
                lastCaptureMs = System.currentTimeMillis()

                val isClean = loop.cleanRequested
                if (isClean) loop.cleanRequested = false
                val bitmap = captureMutex.withLock {
                    if (isClean) cleanCapture(displayId)
                    else controller.captureFrame(displayId)
                }

                when {
                    bitmap != null ->
                        if (isClean) onCleanFrame(bitmap) else onRawFrame(bitmap)
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

    /** Loop poll interval — the user's pref, floored at [MIN_LOOP_INTERVAL_MS].
     *  No platform rate limit applies (unlike AccessibilityService). */
    private fun pollIntervalMs(): Long {
        val ctx = CaptureService.instance ?: return MIN_LOOP_INTERVAL_MS
        return maxOf(Prefs(ctx).captureIntervalMs, MIN_LOOP_INTERVAL_MS)
    }

    /** If [captureResult] is a failed capture (null) because MediaProjection
     *  consent is gone — denied at the dialog, or revoked mid-session — and
     *  live mode is running, tear all of live mode down and tell the user.
     *  Staying live would re-launch the consent dialog on the next capture.
     *  Returns true when it handled a consent loss. A null result with consent
     *  still held is a transient failure and is left for the caller to retry. */
    private fun checkConsentLost(captureResult: Bitmap?): Boolean {
        if (captureResult != null || controller.hasConsent) return false
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
