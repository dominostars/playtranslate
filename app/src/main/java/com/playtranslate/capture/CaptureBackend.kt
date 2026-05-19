package com.playtranslate.capture

import com.playtranslate.OverlayUiController
import com.playtranslate.overlay.OverlayHost

/**
 * A complete screen-capture + overlay-hosting backend. The app reaches the
 * active backend through [CaptureBackendResolver]; consumers ask the backend
 * for a [captureSource] / [overlayHost] / [overlayUi] and never branch on
 * which backend it actually is.
 *
 * [supportsLiveMode] is what `CaptureService.startLive()` checks to decide
 * whether to surface an error, so live-mode callers stay oblivious to the
 * backend.
 */
interface CaptureBackend {
    /** Screen-capture source, or null while the backend isn't ready — e.g.
     *  the accessibility service hasn't connected, or MediaProjection consent
     *  hasn't been granted. Callers null-handle exactly as they did when they
     *  reached for `instance?.screenshotManager` directly. */
    val captureSource: CaptureSource?

    /** [captureSource] as a [LiveCaptureSource] when this backend can drive
     *  the continuous frame loop, else null. Live-mode drivers route capture
     *  through this instead of reaching for the accessibility service. */
    val liveCaptureSource: LiveCaptureSource?
        get() = captureSource as? LiveCaptureSource

    /** Overlay-window host for this backend, or null while not ready. */
    val overlayHost: OverlayHost?

    /** Game-screen overlay UI (floating icon, menu, translation overlay,
     *  region UI) for this backend, or null while not ready. */
    val overlayUi: OverlayUiController?

    /** Whether this backend can drive the continuous capture loop that live
     *  mode depends on. */
    val supportsLiveMode: Boolean

    /** Whether this backend depends on the accessibility service being
     *  connected. The MediaProjection backend does not — it watches
     *  outside-touch through its overlay host, forgoing only key-event
     *  monitoring (which would need the service). */
    val requiresAccessibilityService: Boolean

    /**
     * Watch for user interaction with the game screen on [displayId], running
     * [onGameInput] on each event. The accessibility backend reports gamepad
     * keys and outside-touch; MediaProjection reports outside-touch only.
     */
    fun startInputMonitoring(displayId: Int, onGameInput: () -> Unit)

    /** Stop the [startInputMonitoring] watch for [displayId]. */
    fun stopInputMonitoring(displayId: Int)
}
