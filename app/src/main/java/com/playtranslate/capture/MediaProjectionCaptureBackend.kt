package com.playtranslate.capture

import android.view.Display
import com.playtranslate.CaptureService
import com.playtranslate.OverlayUiController
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

    /** MediaProjection mirrors only the default display (see
     *  [MediaProjectionController.projectedDisplayId]) — it resolves to that
     *  display regardless of [selected], which has no meaning on this backend
     *  (the picker is gated; a persisted multi-display selection is a stale
     *  accessibility-mode artifact). */
    override fun capturableDisplays(selected: Set<Int>): Set<Int> =
        setOf(Display.DEFAULT_DISPLAY)

    override fun startInputMonitoring(displayId: Int, onGameInput: () -> Unit) {
        overlayHost?.addTouchSentinel(displayId, onGameInput)
    }

    override fun stopInputMonitoring(displayId: Int) {
        overlayHost?.removeTouchSentinel(displayId)
    }
}
