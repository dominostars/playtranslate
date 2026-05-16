package com.playtranslate.capture

import com.playtranslate.OverlayUiController
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.overlay.OverlayHost

/**
 * The capture backend backed by [PlayTranslateAccessibilityService]: it
 * captures via `takeScreenshot(displayId)` and hosts overlays as
 * `TYPE_ACCESSIBILITY_OVERLAY`. Supports every app function, live mode included.
 *
 * Both properties forward to the live service instance and degrade to null
 * when the service isn't connected — preserving the null-safety the capture
 * call sites already had when they reached for `instance?.screenshotManager`.
 */
object AccessibilityCaptureBackend : CaptureBackend {
    override val captureSource: CaptureSource?
        get() = PlayTranslateAccessibilityService.instance?.screenshotManager

    override val overlayHost: OverlayHost?
        get() = PlayTranslateAccessibilityService.instance?.overlayHost

    override val overlayUi: OverlayUiController?
        get() = PlayTranslateAccessibilityService.instance?.overlayUiController

    override val supportsLiveMode: Boolean get() = true
}
