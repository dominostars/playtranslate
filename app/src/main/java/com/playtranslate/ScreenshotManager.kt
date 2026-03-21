package com.playtranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "ScreenshotManager"

/**
 * Centralized manager for all `takeScreenshot` calls. Serializes access
 * to the Android rate-limited API (~1 s cooldown), manages overlay
 * hide/show for clean captures, and provides JPEG file saving.
 *
 * All callers go through [requestClean] or [requestRaw] instead of
 * calling `takeScreenshot` directly. The manager tracks the exact time
 * of each call and `delay()`s the precise remaining cooldown, eliminating
 * guessing, retries, and wasted attempts.
 */
class ScreenshotManager(private val a11y: PlayTranslateAccessibilityService) {

    /** Single-thread executor for HardwareBuffer → software Bitmap copies. */
    private val bitmapExecutor = Executors.newSingleThreadExecutor()

    // ── Rate limit tracking ──────────────────────────────────────────────

    /** Timestamp of the most recent `takeScreenshot` call (success or failure). */
    private var lastCaptureTimeMs = 0L

    /** Minimum time between any two takeScreenshot calls. Balances
     *  responsiveness against Android's rate limit and device performance. */
    private val MIN_SCREENSHOT_INTERVAL_MS = 500L

    // ── File cache ───────────────────────────────────────────────────────

    /** Path to the most recently saved clean screenshot (JPEG). */
    var lastCleanPath: String? = null
        private set

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Request a clean screenshot with all overlays hidden.
     *
     * Suspends until the rate limit clears, hides overlays, waits for the
     * compositor to flush the overlay-free frame, captures, and restores
     * overlays. The caller owns the returned [Bitmap] and must recycle it.
     */
    suspend fun requestClean(displayId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        awaitScreenshotInterval()

        val state = a11y.prepareForCleanCapture()
        // Always wait at least 1 vsync frame to ensure the compositor has
        // a clean frame (e.g. after popup dismiss, icon mode change).
        // Wait 2 frames if we just hid overlays.
        waitVsync(if (state.hadAnyOverlay) 2 else 1)

        var bitmap = doTakeScreenshot(displayId)

        // Retry once on failure (e.g. transient OS error)
        if (bitmap == null) {
            DetectionLog.log("Clean capture failed, retrying...")
            awaitScreenshotInterval()
            bitmap = doTakeScreenshot(displayId)
            if (bitmap == null) DetectionLog.log("Clean capture retry also failed")
        }

        a11y.restoreAfterCapture(state)
        return bitmap
    }

    /**
     * Request a raw screenshot with overlays visible.
     *
     * Used for scene-change detection where we compare non-overlay pixels.
     * Suspends until the rate limit clears. No overlay management.
     * The caller owns the returned [Bitmap] and must recycle it.
     */
    suspend fun requestRaw(displayId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        awaitScreenshotInterval()
        return doTakeScreenshot(displayId) ?: run {
            DetectionLog.log("Raw capture failed, retrying...")
            awaitScreenshotInterval()
            val retry = doTakeScreenshot(displayId)
            if (retry == null) DetectionLog.log("Raw capture retry also failed")
            retry
        }
    }

    /**
     * Save a bitmap to the screenshot cache directory as JPEG.
     * Returns the file path. Uses JPEG for speed (~10-30 ms vs PNG's
     * 50-200 ms). Keeps up to 5 files, rotating oldest.
     */
    fun saveToCache(bitmap: Bitmap): String? {
        return try {
            val dir = File(a11y.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            lastCleanPath = file.absolutePath
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveToCache failed: ${e.message}")
            null
        }
    }

    fun destroy() {
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
     */
    private suspend fun doTakeScreenshot(displayId: Int): Bitmap? {
        lastCaptureTimeMs = System.currentTimeMillis()
        return withTimeoutOrNull(3000L) {
            suspendCancellableCoroutine { cont ->
            a11y.takeScreenshot(
                displayId,
                a11y.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
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
