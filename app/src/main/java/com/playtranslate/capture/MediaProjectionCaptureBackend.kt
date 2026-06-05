package com.playtranslate.capture

import android.view.Display
import com.playtranslate.CaptureService
import com.playtranslate.OverlayUiController
import com.playtranslate.Prefs
import com.playtranslate.overlay.OverlayHost

/**
 * The MediaProjection capture backend: captures via a mirrored VirtualDisplay
 * and hosts overlays as `TYPE_APPLICATION_OVERLAY`.
 *
 * Like [AccessibilityCaptureBackend], the properties forward to state owned by
 * the live [CaptureService] and degrade to null when the service isn't bound.
 */
object MediaProjectionCaptureBackend : CaptureBackend {
    override val captureSource: CaptureSource?
        get() = CaptureService.instance?.mediaProjectionCaptureSource

    override val overlayHost: OverlayHost?
        get() = CaptureService.instance?.mediaProjectionOverlayHost

    override val overlayUi: OverlayUiController?
        get() = CaptureService.instance?.mediaProjectionOverlayUi

    override val supportsLiveMode: Boolean get() = true

    override val requiresAccessibilityService: Boolean get() = false

    /** Capture never prompts — it fails closed until consent is held — so a
     *  passive capture is meaningful only once consent exists. */
    override val canCaptureWithoutPrompting: Boolean
        get() = CaptureService.instance?.mediaProjectionController?.hasConsent == true

    /** Obtain screen-record consent, prompting if needed — the up-front
     *  consent gate `startLive()` awaits before building the live-mode loop. */
    override suspend fun ensureCaptureReady(): Boolean =
        CaptureService.instance?.mediaProjectionController?.ensureConsent() ?: false

    /** MediaProjection mirrors only the default display (see
     *  [MediaProjectionController.projectedDisplayId]). */
    override fun canCapture(displayId: Int): Boolean =
        displayId == Display.DEFAULT_DISPLAY

    /** The default display is the universal capture target — if a saved
     *  selection has no capturable overlap (e.g., a persisted accessibility-
     *  mode `{1}` after switching to this backend), start-path callers
     *  fall back to it rather than silently no-op. */
    override val fallbackDisplay: Int = Display.DEFAULT_DISPLAY

    override fun startInputMonitoring(displayId: Int, onGameInput: () -> Unit) {
        // Touch is this backend's only refresh trigger (no gamepad path), so
        // the "Touches refresh translation" gate wraps the sentinel callback
        // itself. Read at touch-time; defaults to refreshing if the service is
        // somehow gone (preserving the prior always-refresh behavior).
        overlayHost?.addTouchSentinel(displayId) {
            val refresh = CaptureService.instance
                ?.let { Prefs(it).touchesRefreshTranslation } ?: true
            if (refresh) onGameInput()
        }
    }

    override fun stopInputMonitoring(displayId: Int) {
        overlayHost?.removeTouchSentinel(displayId)
    }
}
