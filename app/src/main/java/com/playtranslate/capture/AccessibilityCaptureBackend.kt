package com.playtranslate.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.playtranslate.OverlayUiController
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.isReachableDisplay
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

    override val requiresAccessibilityService: Boolean get() = true

    /** `takeScreenshot` never shows a prompt. */
    override val canCaptureWithoutPrompting: Boolean get() = true

    /** `takeScreenshot` can target any connected, non-private display
     *  ([isReachableDisplay]); a selection naming none resolves to
     *  [fallbackDisplay]. Until 2026-10-14 this was true for every id, so a
     *  saved display that no longer existed stayed the target of live start
     *  and the one-shot fan-out, which then captured nothing, silently,
     *  while the floating icon fell back to the default display on its own. */
    override fun canCapture(context: Context, displayId: Int): Boolean =
        context.getSystemService(DisplayManager::class.java)?.isReachableDisplay(displayId) == true

    override val fallbackDisplay: Int get() = Display.DEFAULT_DISPLAY

    override fun startInputMonitoring(displayId: Int, onGameInput: () -> Unit) {
        PlayTranslateAccessibilityService.instance
            ?.startInputMonitoring(displayId, onGameInput)
    }

    override fun stopInputMonitoring(displayId: Int) {
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring(displayId)
    }

    /** Routes to the service's own all-displays teardown, which drops the
     *  touch sentinels and clears key-event / touch tracking state. */
    override fun stopAllInputMonitoring() {
        PlayTranslateAccessibilityService.instance?.stopInputMonitoring()
    }
}
