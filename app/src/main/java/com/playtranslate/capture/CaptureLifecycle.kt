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
 * "active" is derived, never stored:
 *  - MediaProjection backend → screen-record consent is currently held
 *    (consent doesn't survive a process restart, so this is runtime state).
 *  - Accessibility backend, dual-screen → always true (the service is the
 *    capture path — there is nothing to start).
 *  - Accessibility backend, single-screen → the floating icon is shown.
 */
object CaptureLifecycle {

    /** Whether PlayTranslate's capture system is currently running. */
    fun isActive(ctx: Context): Boolean {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            return CaptureService.instance?.mediaProjectionController?.hasConsent == true
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
                if (svc.isLive) svc.stopLive()
                svc.mediaProjectionCaptureSource.destroy()
            }
            CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
            PlayTranslateTileService.TileSync.refresh(ctx)
        } else {
            // Accessibility — reuse the canonical "PlayTranslate goes inactive" path.
            PlayTranslateAccessibilityService.disable(ctx, "capture_lifecycle_stop")
        }
    }

    /** Accessibility-backend activate: show the floating icon. Returns false —
     *  the caller should prompt for the service — when it isn't enabled. */
    fun activateAccessibility(ctx: Context): Boolean {
        if (!PlayTranslateAccessibilityService.isEnabled(ctx)) return false
        Prefs(ctx).showOverlayIcon = true
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
        PlayTranslateTileService.TileSync.refresh(ctx)
        return true
    }

    /** MediaProjection-backend activate: obtain screen-record consent — capture
     *  stays lazy, no projection is created here — and on grant bring the
     *  floating controls up. Returns whether consent is now held. */
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
