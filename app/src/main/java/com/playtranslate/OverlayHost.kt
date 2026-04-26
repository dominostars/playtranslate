package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.TranslationOverlayView

/**
 * State returned by [OverlayHost.prepareForCleanCapture], passed to
 * [OverlayHost.restoreAfterCapture]. Top-level so both the accessibility
 * service and the projection host can share a single representation.
 */
data class OverlayState(
    val hadTranslation: Boolean,
    val hadDebug: Boolean,
    val hadRegionIndicator: Boolean,
)

/**
 * Cross-mode interface for the on-screen overlays managed by PlayTranslate.
 *
 * Two implementations:
 *  - [PlayTranslateAccessibilityService] hosts overlays via
 *    `TYPE_ACCESSIBILITY_OVERLAY` and uses `AccessibilityService.takeScreenshot`.
 *  - [ProjectionOverlayHost] (used in Share-Screen mode) hosts overlays via
 *    `TYPE_APPLICATION_OVERLAY` from a regular service context and uses
 *    `MediaProjection`+`VirtualDisplay`+`ImageReader` for screen capture.
 *
 * The static [current] dispatch returns whichever host is currently active —
 * `null` if neither mode is initialised yet. Consumers in
 * [CaptureService] route through [current] so the same business logic works
 * unchanged across modes.
 */
interface OverlayHost {
    /** Floating action icon, when shown. */
    val floatingIcon: FloatingOverlayIcon?

    /** Lazily create / reposition the floating icon. Idempotent. */
    fun ensureFloatingIcon()

    /** Tear down the floating icon. [reason] is logged for diagnostics. */
    fun hideFloatingIcon(reason: String = "unspecified")

    /** Remove + re-add the icon so it draws above newly added overlays. */
    fun bringFloatingIconToFront()

    /** Bounding rect of the floating icon in screen coordinates, or null. */
    fun getFloatingIconRect(): Rect?

    /** Show / refresh the live translation overlay on [display]. */
    fun showTranslationOverlay(
        display: Display,
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean = false,
    )

    /** Hide the translation overlay (no-op if not visible). */
    fun hideTranslationOverlay()

    /** Remove specific boxes without rebuilding the entire view. */
    fun removeOverlayBoxes(toRemove: List<TranslationOverlayView.TextBox>)

    /** Briefly flash the capture-region indicator on [display]. */
    fun showRegionIndicator(display: Display, region: RegionEntry, persistent: Boolean = false)

    /** Hide the region indicator. [force] also dismisses persistent indicators. */
    fun hideRegionIndicator(force: Boolean = false)

    /** Hide overlay views before a clean screenshot. Returns state to restore. */
    fun prepareForCleanCapture(): OverlayState

    /** Restore overlay views previously hidden by [prepareForCleanCapture]. */
    fun restoreAfterCapture(state: OverlayState)

    companion object {
        /**
         * Process-wide currently-active overlay host.
         *
         * Returns the projection host if a media-projection session is live;
         * otherwise the accessibility service (which is non-null only while
         * the user has the service enabled and connected). Returns `null` if
         * neither mode is currently usable — callers should null-check.
         */
        @JvmStatic
        val current: OverlayHost?
            get() = ProjectionOverlayHost.instance
                ?: PlayTranslateAccessibilityService.instance
    }
}

/**
 * Cross-mode screenshot source. The Accessibility-mode implementation is
 * provided by [ScreenshotManager]; the projection-mode implementation is
 * [com.playtranslate.MediaProjectionScreenshotProvider].
 *
 * Mirrors the existing [ScreenshotManager] API surface to avoid touching
 * downstream live-mode loops (PinholeOverlayMode, FuriganaMode, etc.).
 */
interface ScreenshotProvider {
    /** Path of the most recently saved JPEG (clean) screenshot, if any. */
    val lastCleanPath: String?

    /**
     * Request a clean screenshot of [displayId] with overlays hidden.
     * Suspends through any rate-limit window. Caller owns the bitmap.
     */
    suspend fun requestClean(displayId: Int): Bitmap?

    /**
     * Request a raw screenshot of [displayId] (overlays visible).
     * [onCaptured] fires the instant the buffer is captured but before
     * the bitmap copy completes (used to restore UI state with minimal
     * latency). Caller owns the bitmap.
     */
    suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)? = null): Bitmap?

    /** Persist [bitmap] to the screenshot cache directory as JPEG. */
    fun saveToCache(bitmap: Bitmap): String?

    /**
     * Start a continuous capture loop. [onCleanFrame] receives frames
     * captured under [requestCleanCapture], [onRawFrame] receives the rest.
     */
    fun startLoop(
        displayId: Int,
        scope: kotlinx.coroutines.CoroutineScope,
        onCleanFrame: (Bitmap) -> Unit,
        onRawFrame: (Bitmap) -> Unit,
    )

    /** Flag the next loop iteration to be a clean capture. */
    fun requestCleanCapture()

    /** Stop the continuous loop. Safe to call when no loop is running. */
    fun stopLoop()

    /** True iff [startLoop] has been invoked and not yet stopped. */
    val isLoopRunning: Boolean

    /** Release any backing resources. */
    fun destroy()
}
