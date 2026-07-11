package com.playtranslate.capture

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Display
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

    /** The capture backend the app should use right now. Below API 30 the
     *  accessibility `takeScreenshot` path doesn't exist, so MediaProjection is
     *  the only possible backend regardless of the cached flag. */
    fun active(): CaptureBackend =
        if (useMediaProjection || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            MediaProjectionCaptureBackend
        else
            AccessibilityCaptureBackend

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
     * The capture source live TRANSLATION mode should pull pixels from for
     * [displayId]: the MediaProjection mirrored stream when it can serve this
     * display and screen-record consent is already held — even while the
     * accessibility backend is otherwise active, because the stream's
     * [DeliverySignal] is what the delivery-gated cycle runs on — otherwise
     * the active backend's source (accessibility screenshots; also every
     * non-default display, which MediaProjection cannot mirror).
     *
     * Fallback is emergent from `hasConsent`: consent declined at the dialog,
     * revoked mid-session, or never requested all land on the accessibility
     * path with no extra state. Overlay hosting and input monitoring are NOT
     * affected — they stay with [active] (the window type, its alpha cell,
     * and gamepad keys follow the overlay host, not the capture source).
     */
    fun liveCaptureSourceFor(displayId: Int): LiveCaptureSource? {
        if (displayId == Display.DEFAULT_DISPLAY &&
            CaptureService.instance?.mediaProjectionController?.hasConsent == true
        ) {
            MediaProjectionCaptureBackend.liveCaptureSource?.let { return it }
        }
        return active().liveCaptureSource
    }

    /**
     * Re-derive the active backend from the granted permissions and swap if it
     * changed: the accessibility service being enabled selects the
     * accessibility backend; otherwise "display over other apps" being granted
     * selects MediaProjection; with neither, the accessibility backend stands
     * (onboarding asks for a permission). Called at app start and from
     * MainActivity.refreshReadiness so a permission granted in system
     * Settings is picked up on the next resume. Stops live mode, releases the
     * outgoing MediaProjection session, and hides the outgoing backend's
     * overlays before the swap, then brings up the incoming backend's floating
     * icon(s).
     */
    fun reresolve(context: Context) {
        // Below API 30 MediaProjection is the only capture backend (no
        // accessibility takeScreenshot) — select it unconditionally so no surface
        // ever offers the impossible accessibility upgrade. Overlay permission
        // gates *readiness* (see OnboardingViewModel / requestMediaProjectionControls),
        // not backend identity, so it's deliberately not part of this choice.
        // On API 30+: accessibility takes precedence when its service is enabled,
        // even if "display over other apps" is also granted.
        val want = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            (!PlayTranslateAccessibilityService.isEnabled(context) &&
                Settings.canDrawOverlays(context))
        if (want == useMediaProjection) return
        CaptureService.instance?.let { svc ->
            if (svc.isLive) svc.stopLive()
            // A backend swap is a fresh capture start in BOTH directions.
            // Outgoing MediaProjection: release the session (consent token,
            // VirtualDisplay, ImageReader) so a stale projection doesn't
            // linger — and keep the service foreground — under the now-
            // inactive backend. Outgoing accessibility: the same destroy
            // drops any BORROWED stream session (startLive's
            // wantMpStreamConsent) — a warm borrowed token surviving into
            // the incoming MediaProjection backend would let captures run
            // without ever re-prompting while the lifecycle reads Off
            // (adversarial-review round 3). The activation flag clears with
            // it: swapping back to MediaProjection later requires an
            // explicit Turn On (round 1). teardown() is the release
            // onDestroy / the off switch use, and a no-op when no session
            // exists; stopLive() above already stopped any capture loops.
            svc.mediaProjectionActivated = false
            svc.mediaProjectionController.destroy()
        }
        active().overlayUi?.hideAll()
        useMediaProjection = want
        active().overlayUi?.reconcileFloatingIcons()
        // Backend swap: the controller.destroy() above already stopped the
        // game-audio recorder via its teardown listener; re-evaluate under
        // the incoming backend (it restarts only once that backend is active
        // with fresh consent).
        CaptureService.instance?.reconcileGameAudio()
    }
}
