package com.playtranslate.capture

import android.content.Context
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs

/**
 * Single source of truth for whether PlayTranslate is "active" — its capture
 * system is running — and for the activate / deactivate operations behind the
 * Settings "Turn On / Turn Off" button and the Quick Settings tile.
 *
 * "active" is derived where possible:
 *  - MediaProjection backend → the explicit
 *    [CaptureService.mediaProjectionActivated] flag: set by Turn On, cleared
 *    only by Turn Off. Deliberately NOT held consent — the consent token is
 *    single-use on API 34+ and dies with every status-bar-chip revoke / lock
 *    auto-stop, which must not read as the user turning PlayTranslate off
 *    (the floating controls stay up; the next capture re-prompts — see
 *    [MediaProjectionController.onProjectionLost]). Still runtime state: it
 *    dies with the service, so a process restart comes up inactive.
 *  - Accessibility backend, dual-screen → always true (the service is the
 *    capture path — there is nothing to start).
 *  - Accessibility backend, single-screen → the floating icon is shown.
 */
object CaptureLifecycle {

    /** Whether PlayTranslate's capture system is currently running. */
    fun isActive(ctx: Context): Boolean {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            return CaptureService.instance?.mediaProjectionActivated == true
        }
        if (!Prefs.isSingleScreen(ctx)) return true
        return Prefs(ctx).showOverlayIcon && PlayTranslateAccessibilityService.isEnabled(ctx)
    }

    /** Whether the Settings screen should surface the Turn On / Turn Off button.
     *  False only for the accessibility backend on dual-screen, where "active"
     *  is always true and the button would do nothing. */
    fun hasActivateControl(ctx: Context): Boolean =
        !CaptureBackendResolver.active().requiresAccessibilityService ||
            Prefs.isSingleScreen(ctx)

    /** Stop capture and tear the floating controls down. Synchronous; safe to
     *  call from any context. */
    fun deactivate(ctx: Context) {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            CaptureService.instance?.let { svc ->
                // Clear the activation flag BEFORE reconciling below — the
                // icon's canShowControls gate reads it.
                svc.mediaProjectionActivated = false
                if (svc.isLive) svc.stopLive()
                svc.mediaProjectionCaptureSource.destroy()
            }
            CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
            PlayTranslateTileService.TileSync.refresh(ctx)
        } else {
            // Accessibility — reuse the canonical "PlayTranslate goes inactive" path.
            PlayTranslateAccessibilityService.disable(ctx, "capture_lifecycle_stop")
        }
        // Session off ⇒ the game-audio gate closes. The projection teardown
        // above already stopped the recorder via its teardown listener; this
        // makes the stop deterministic for both backend branches.
        CaptureService.instance?.reconcileGameAudio()
    }

    /** Accessibility-backend activate: show the floating icon. Returns false —
     *  the caller should prompt for the service — when it isn't enabled. */
    fun activateAccessibility(ctx: Context): Boolean {
        if (!PlayTranslateAccessibilityService.isEnabled(ctx)) return false
        Prefs(ctx).showOverlayIcon = true
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        PlayTranslateTileService.TileSync.refresh(ctx)
        // Session on ⇒ recording may start (if a warm consent token exists —
        // the recorder never prompts).
        CaptureService.instance?.reconcileGameAudio()
        return true
    }

    /** MediaProjection-backend activate: obtain screen-record consent — capture
     *  stays lazy, no projection is created here — and on grant bring the
     *  floating controls up. No flag write here: the grant itself marks the
     *  backend activated ([MediaProjectionController.onConsentResult] owns
     *  that write, for every grant path — not just this one), and a
     *  short-circuiting ensureConsent implies the flag is already set
     *  (hasConsent ⇒ activated). Returns whether consent is now held. */
    suspend fun activateMediaProjection(): Boolean {
        val controller = CaptureService.instance?.mediaProjectionController ?: return false
        if (!controller.ensureConsent()) return false
        // Don't touch showOverlayIcon — that's the independent "show the
        // floating icon" preference. Whether the icon appears is settled by
        // reconcileFloatingIcons (active + the preference / single-screen).
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        CaptureService.instance?.let {
            PlayTranslateTileService.TileSync.refresh(it.applicationContext)
        }
        return true
    }
}
