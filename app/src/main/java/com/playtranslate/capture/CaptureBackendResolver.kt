package com.playtranslate.capture

import android.content.Context
import com.playtranslate.CaptureService
import com.playtranslate.OverlayUiController
import com.playtranslate.Prefs

/**
 * The single place that decides which [CaptureBackend] is active. Consumers
 * (CaptureService and the capture/overlay call sites) route through [active]
 * and [activeOverlayUi] and never read the backend preference themselves — so
 * the MediaProjection-vs-accessibility split stays contained here.
 *
 * The active backend is swapped only by [reresolve], which reads the DEBUG
 * `captureBackendMediaProjection` pref. [active] reads a cached flag, so it
 * stays cheap on the hot path.
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
     * Re-read the DEBUG MediaProjection-backend pref and swap backends if it
     * changed. Called at app start and whenever the debug toggle flips. Stops
     * live mode and hides the outgoing backend's overlays before the swap,
     * then brings up the incoming backend's floating icon(s).
     */
    fun reresolve(context: Context) {
        val want = Prefs(context).captureBackendMediaProjection
        if (want == useMediaProjection) return
        CaptureService.instance?.let { if (it.isLive) it.stopLive() }
        active().overlayUi?.hideAll()
        useMediaProjection = want
        active().overlayUi?.reconcileFloatingIcons()
    }
}
