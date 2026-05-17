package com.playtranslate.capture

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
}
