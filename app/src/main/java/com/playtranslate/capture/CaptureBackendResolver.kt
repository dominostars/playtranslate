package com.playtranslate.capture

import android.content.Context
import android.provider.Settings
import com.playtranslate.CaptureService
import com.playtranslate.OverlayUiController
import com.playtranslate.PlayTranslateAccessibilityService

/**
 * The single place that decides which [CaptureBackend] is active. Consumers
 * (CaptureService and the capture/overlay call sites) route through [active]
 * and [activeOverlayUi] and never read the backend preference themselves — so
 * the MediaProjection-vs-accessibility split stays contained here.
 *
 * The active backend is swapped only by [reresolve], which derives it from the
 * granted permissions. [active] reads a cached flag, so it stays cheap on the
 * hot path.
 */
object CaptureBackendResolver {

    @Volatile
    private var useMediaProjection = false

    /** The capture backend the app should use right now. */
    fun active(): CaptureBackend =
        if (useMediaProjection) MediaProjectionCaptureBackend else AccessibilityCaptureBackend

    /** Convenience: the active backend's overlay UI controller, or null while
     *  it isn't ready. Overlay-producing call sites route through this. */
    val activeOverlayUi: OverlayUiController?
        get() = active().overlayUi

    /** Convenience: the active backend's [LiveCaptureSource], or null when the
     *  backend can't drive live mode / isn't ready. Live-mode drivers route
     *  capture through this. */
    val activeLiveCaptureSource: LiveCaptureSource?
        get() = active().liveCaptureSource

    /**
     * Re-derive the active backend from the granted permissions and swap if it
     * changed: the accessibility service being enabled selects the
     * accessibility backend; otherwise "display over other apps" being granted
     * selects MediaProjection; with neither, the accessibility backend stands
     * (onboarding asks for a permission). Called at app start and from
     * MainActivity.checkOnboardingState so a permission granted in system
     * Settings is picked up on the next resume. Stops live mode and hides the
     * outgoing backend's overlays before the swap, then brings up the incoming
     * backend's floating icon(s).
     */
    fun reresolve(context: Context) {
        // Accessibility takes precedence: when its service is enabled, use it
        // even if "display over other apps" is also granted.
        val want = !PlayTranslateAccessibilityService.isEnabled(context) &&
            Settings.canDrawOverlays(context)
        if (want == useMediaProjection) return
        CaptureService.instance?.let { if (it.isLive) it.stopLive() }
        active().overlayUi?.hideAll()
        useMediaProjection = want
        active().overlayUi?.reconcileFloatingIcons()
    }
}
