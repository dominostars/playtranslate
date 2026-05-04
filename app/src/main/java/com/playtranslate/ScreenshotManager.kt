package com.playtranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "ScreenshotManager"

/**
 * Centralized manager for all `takeScreenshot` calls. Serializes access
 * to the Android rate-limited API (platform constant 333 ms; we enforce
 * 500 ms conservatively via [MIN_SCREENSHOT_INTERVAL_MS]). Manages overlay
 * hide/show for clean captures and provides JPEG file saving.
 *
 * All callers go through [requestClean] or [requestRaw] instead of
 * calling `takeScreenshot` directly. The manager tracks the exact time
 * of each call and `delay()`s the precise remaining cooldown, eliminating
 * guessing, retries, and wasted attempts.
 *
 * Multi-display: the rate limit is enforced GLOBALLY per service (AOSP's
 * AbstractAccessibilityServiceConnection tracks the last-screenshot
 * timestamp in a single field, not per-display). [captureMutex] reflects
 * that constraint — every code path that calls
 * `takeScreenshot` (clean and raw) acquires it. With N selected displays
 * each running its own [Loop], the loops contend on the mutex naturally;
 * effective per-display fps = 1 / (N × interval). [Loop] hoists the
 * formerly-singleton loop state per displayId so each loop has its own
 * `cleanRequested` flag and the "next clean frame is mine" contract holds.
 */
class ScreenshotManager(private val a11y: PlayTranslateAccessibilityService) {

    /** Single-thread executor for HardwareBuffer → software Bitmap copies. */
    private val bitmapExecutor = Executors.newSingleThreadExecutor()

    /** Serializes every `takeScreenshot` call (clean AND raw paths) so
     *  prepare/restore lifecycles don't overlap and no two raw captures
     *  race past [awaitScreenshotInterval] simultaneously and lose to
     *  AOSP's global timestamp (errorCode 3 / INTERVAL_TIME_SHORT).
     *  Held by [requestClean], [requestRaw], and the loop's per-frame
     *  clean and raw branches. */
    private val captureMutex = Mutex()

    // ── Rate limit tracking ──────────────────────────────────────────────

    /** Timestamp of the most recent `takeScreenshot` call (success or failure). */
    private var lastCaptureTimeMs = 0L

    /** Absolute minimum between takeScreenshot calls (Android API rate limit). */
    internal val MIN_SCREENSHOT_INTERVAL_MS = 500L

    /** User-configurable poll interval for the loop. Read from Prefs on each cycle. */
    private fun pollIntervalMs(): Long {
        val userMs = Prefs(a11y).captureIntervalMs
        return maxOf(userMs, MIN_SCREENSHOT_INTERVAL_MS)
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Request a clean screenshot with all overlays hidden.
     *
     * Suspends until the rate limit clears, hides overlays, waits for the
     * compositor to flush the overlay-free frame, captures, and restores
     * overlays. The caller owns the returned [Bitmap] and must recycle it.
     */
    suspend fun requestClean(displayId: Int): Bitmap? = captureMutex.withLock {
        awaitScreenshotInterval()

        val hideStart = System.currentTimeMillis()
        val state = a11y.prepareForCleanCapture(displayId)
        try {
            // Wait 2 vsync frames for the compositor to flush the overlay-free frame.
            waitVsync(2)

            var bitmap = doTakeScreenshot(displayId) {
                // Fast-path restore: triggered as soon as the screenshot
                // buffer is captured, so overlays come back during the
                // bitmap copy. The finally below is the safety net.
                a11y.restoreAfterCapture(state)
                android.util.Log.d("DetectionLog", "OVERLAY HIDDEN for ${System.currentTimeMillis() - hideStart}ms (requestClean)")
            }

            if (bitmap == null) {
                DetectionLog.log("Clean capture failed, retrying...")
                awaitScreenshotInterval()
                val retryState = a11y.prepareForCleanCapture(displayId)
                val retry = try {
                    waitVsync(2)
                    doTakeScreenshot(displayId) {
                        a11y.restoreAfterCapture(retryState)
                    }
                } finally {
                    a11y.restoreAfterCapture(retryState)
                }
                bitmap = retry
                if (retry == null) DetectionLog.log("Clean capture retry also failed")
            }
            bitmap
        } finally {
            // Belt-and-suspenders: the takeScreenshot callback can fail to
            // fire (coroutine cancellation discards the OS-side request, OS
            // hang past timeout, etc.) which would otherwise leave overlays
            // permanently blanked. Always restore before returning.
            // restoreAfterCapture is idempotent (always writes alpha=1) so
            // a double-restore on the success path is harmless. The mutex
            // around this whole block guarantees no other capture sees the
            // intermediate alpha=0 state.
            a11y.restoreAfterCapture(state)
        }
    }

    /**
     * Request a raw screenshot with overlays visible.
     *
     * Used for scene-change detection where we compare non-overlay pixels.
     * Suspends until the rate limit clears. No overlay management.
     * The caller owns the returned [Bitmap] and must recycle it.
     *
     * Does NOT retry on failure. The previous transparent-retry logic was
     * unsafe when [onCaptured] mutates UI state (e.g. alpha restoration) —
     * the callback fires once on first-attempt failure, so any retry would
     * capture with the restored UI visible, contaminating the bitmap. Callers
     * that need retry must re-prepare UI state and call again.
     */
    suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)? = null): Bitmap? = captureMutex.withLock {
        awaitScreenshotInterval()
        val bitmap = doTakeScreenshot(displayId, onCaptured)
        if (bitmap == null) DetectionLog.log("Raw capture failed")
        bitmap
    }

    /**
     * Save a bitmap to the screenshot cache directory as JPEG, keyed on
     * [displayId]. Returns the file path. Uses JPEG for speed (~10-30 ms vs
     * PNG's 50-200 ms).
     *
     * The cache stays bounded to one file per (display × writer): this
     * manager writes `capture-d{displayId}.jpg` per display, and the
     * accessibility-service `precapture.jpg` / drag-flow `drag.jpg` paths
     * each own their own filenames. Per-display files prevent a concurrent
     * capture on display B from clobbering display A's screenshot before
     * the user opens its detail view or saves to Anki.
     *
     * Callers MUST use the returned path; there's no global "last clean
     * path" accessor anymore — it would inherently lose the per-display
     * binding the moment a second display fires a capture.
     */
    fun saveToCache(bitmap: Bitmap, displayId: Int): String? {
        return try {
            val dir = File(a11y.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "capture-d$displayId.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveToCache failed: ${e.message}")
            null
        }
    }

    // ── Continuous poll loop (live mode) ─────────────────────────────────

    /**
     * Per-display loop state. One instance per running [startLoop] target;
     * each loop owns its own [cleanRequested] flag so callers' "the next
     * frame is mine" contract still holds when N loops are running. The
     * shared [captureMutex] serializes their `takeScreenshot` calls at the
     * platform rate limit.
     */
    private class Loop(
        val displayId: Int,
        var job: Job? = null,
        @Volatile var cleanRequested: Boolean = false,
    )

    private val loops: MutableMap<Int, Loop> = mutableMapOf()

    /**
     * Start a continuous screenshot loop for [displayId]. Each frame is
     * delivered to [onCleanFrame] or [onRawFrame] depending on whether a
     * clean capture was requested for THIS display via [requestCleanCapture].
     * Multiple loops on different displays can coexist; the global
     * [captureMutex] enforces serialization at the platform rate limit.
     */
    fun startLoop(
        displayId: Int,
        scope: CoroutineScope,
        onCleanFrame: (Bitmap) -> Unit,
        onRawFrame: (Bitmap) -> Unit
    ) {
        stopLoop(displayId)
        // First frame is always clean — every caller wants a clean
        // baseline before [onRawFrame] starts producing diffs against
        // [cleanRef]. Bake it into the loop's initial state instead of
        // making callers chain requestCleanCapture(displayId) before
        // every startLoop, which silently no-ops because the loop entry
        // doesn't exist yet at that point.
        val loop = Loop(displayId = displayId, cleanRequested = true)
        loops[displayId] = loop
        loop.job = scope.launch {
            while (isActive) {
                // Outer pacing: user's poll interval (floored at the platform
                // rate limit). Inside the mutex below we re-check the interval
                // — that gates the actual capture and applies even when the
                // outer pacing already passed (e.g. another loop just took a
                // frame and bumped lastCaptureTimeMs).
                val elapsed = System.currentTimeMillis() - lastCaptureTimeMs
                val waitMs = pollIntervalMs() - elapsed
                if (waitMs > 0) delay(waitMs)
                val isClean = loop.cleanRequested
                if (isClean) {
                    loop.cleanRequested = false
                    DetectionLog.log("Loop[$displayId]: taking clean screenshot...")
                    val hideStart = System.currentTimeMillis()
                    val bitmap = captureMutex.withLock {
                        awaitScreenshotInterval()
                        val state = a11y.prepareForCleanCapture(displayId)
                        try {
                            waitVsync(2)
                            doTakeScreenshot(displayId) {
                                a11y.restoreAfterCapture(state)
                                android.util.Log.d("DetectionLog", "OVERLAY HIDDEN for ${System.currentTimeMillis() - hideStart}ms (loop[$displayId])")
                            }
                        } finally {
                            // See comment in requestClean — guarantees restore
                            // even if the screenshot callback never fires.
                            a11y.restoreAfterCapture(state)
                        }
                    }
                    if (bitmap != null) {
                        DetectionLog.log("Loop[$displayId]: clean frame captured (${bitmap.width}x${bitmap.height})")
                        onCleanFrame(bitmap)
                    } else {
                        DetectionLog.log("Loop[$displayId]: clean capture failed")
                    }
                } else {
                    val bitmap = captureMutex.withLock {
                        awaitScreenshotInterval()
                        doTakeScreenshot(displayId)
                    }
                    if (bitmap != null) {
                        onRawFrame(bitmap)
                    }
                    // null = timeout or failure, logged by doTakeScreenshot
                }
            }
        }
    }

    /** Flag the next loop iteration on [displayId] to take a clean capture. */
    fun requestCleanCapture(displayId: Int) {
        loops[displayId]?.cleanRequested = true
    }

    /** Flag the next loop iteration on every running display to take a
     *  clean capture. Used by callers that don't track per-display loops. */
    fun requestCleanCaptureAll() {
        loops.values.forEach { it.cleanRequested = true }
    }

    /** Stop the loop for [displayId]. No-op if no loop is running there. */
    fun stopLoop(displayId: Int) {
        loops.remove(displayId)?.job?.cancel()
    }

    /** Stop every running loop. */
    fun stopAllLoops() {
        loops.values.forEach { it.job?.cancel() }
        loops.clear()
    }

    /** True iff a loop is running for [displayId]. */
    fun isLoopRunning(displayId: Int): Boolean =
        loops[displayId]?.job?.isActive == true

    /** True iff any loop is running across all displays. */
    val hasAnyLoop: Boolean get() = loops.values.any { it.job?.isActive == true }

    fun destroy() {
        stopAllLoops()
        bitmapExecutor.shutdown()
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Suspend until enough time has passed since the last `takeScreenshot`
     * call to avoid the Android rate limit (error code 3).
     */
    private suspend fun awaitScreenshotInterval() {
        val elapsed = System.currentTimeMillis() - lastCaptureTimeMs
        val waitMs = MIN_SCREENSHOT_INTERVAL_MS - elapsed
        if (waitMs > 0) {
            delay(waitMs)
        }
    }

    /**
     * Bridge `takeScreenshot` to a suspend function. Copies the
     * HardwareBuffer to a software ARGB_8888 bitmap on [bitmapExecutor].
     *
     * @param onCaptured Optional callback invoked on the main thread the instant
     *   the screenshot buffer is captured, BEFORE the bitmap copy. Use this to
     *   restore overlays as early as possible — the copy runs in the background.
     */
    private suspend fun doTakeScreenshot(displayId: Int, onCaptured: (() -> Unit)? = null): Bitmap? {
        lastCaptureTimeMs = System.currentTimeMillis()
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
            a11y.takeScreenshot(
                displayId,
                a11y.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        // Restore overlays immediately — buffer is captured, copy can happen with overlay visible
                        onCaptured?.invoke()
                        bitmapExecutor.execute {
                            val hwBitmap = Bitmap
                                .wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            val bmp = hwBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                            hwBitmap?.recycle()
                            screenshot.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bmp)
                            else bmp?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed on display $displayId, code=$errorCode")
                        onCaptured?.invoke()
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
        }.also { if (it == null) DetectionLog.log("Screenshot timed out (3s)") }
    }

    /** Suspend for [frames] vsync frames (~16 ms each at 60 Hz). */
    private suspend fun waitVsync(frames: Int) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }
}
