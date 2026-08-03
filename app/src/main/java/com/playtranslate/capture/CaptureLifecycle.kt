package com.playtranslate.capture

import android.content.Context
import android.content.Intent
import android.util.Log
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
 *  - Accessibility backend → the floating controls have been summoned this
 *    process lifetime ([floatingIconSuppressed] lifted by app-open / Turn
 *    On), or live mode is actually running; single-screen additionally
 *    requires the icon preference and the service enabled, because the icon
 *    is the only control surface there.
 */
object CaptureLifecycle {

    private const val TAG = "CaptureLifecycle"

    /** Process-lifetime suppression of the accessibility-backend floating
     *  icon. Starts true so a boot — the system rebinding the enabled
     *  accessibility service — never places the icon on its own; it appears
     *  once the user opens the app (MainActivity onResume) or explicitly
     *  turns PlayTranslate on ([activateAccessibility]: Settings power
     *  button / QS tile). "Hide for Now" re-suppresses, which is what makes
     *  that dialog's "back next time you open PlayTranslate" promise hold:
     *  every icon-resurrect trigger (display hot-plug, service reconnect,
     *  backend reresolve) funnels through [com.playtranslate
     *  .OverlayUiController.reconcileFloatingIcons], whose gate reads this
     *  flag. Deliberately in-memory, not a pref: process death resets to
     *  suppressed, which IS the wanted boot behavior, and after a mid-session
     *  crash the icon returns on the next app visit rather than resurrecting
     *  unasked. MediaProjection ignores it — that backend's icon is already
     *  gated by [CaptureService.mediaProjectionActivated], runtime state that
     *  dies with the process the same way. */
    var floatingIconSuppressed: Boolean = true
        private set

    /** Single write path for [floatingIconSuppressed]. The flag feeds
     *  [isActive], and the QS tile is ACTIVE_TILE — it re-renders ONLY on a
     *  [PlayTranslateTileService.TileSync.refresh] push, never on shade open
     *  — so a bare flag write leaves the tile displaying the old state
     *  indefinitely (field report 2026-08-03: "Hide for Now" left the tile
     *  active). Compiler-enforced via `private set` so a future flip site
     *  can't skip the push. */
    fun setFloatingIconSuppressed(ctx: Context, suppressed: Boolean) {
        floatingIconSuppressed = suppressed
        PlayTranslateTileService.TileSync.refresh(ctx)
    }

    /** Whether PlayTranslate's capture system is currently running. */
    fun isActive(ctx: Context): Boolean {
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            return CaptureService.instance?.mediaProjectionActivated == true
        }
        // Accessibility: active = the user summoned the controls this
        // process ([floatingIconSuppressed] lifted), OR live mode is actually
        // running — "Hide for Now" deliberately leaves a running live session
        // alive, and the tile must render that truth (ON; its tap stops the
        // session). This is the CONTROL-SURFACE truth: the game-audio gate
        // reads [isSessionActive] instead, so icon visibility never stops
        // the recording ring.
        val summonedOrLive = !floatingIconSuppressed ||
            CaptureService.instance?.isLive == true
        if (!Prefs.isSingleScreen(ctx)) return summonedOrLive
        return summonedOrLive && Prefs(ctx).showOverlayIcon &&
            PlayTranslateAccessibilityService.isEnabled(ctx)
    }

    /** The capture session's own truth, WITHOUT the floating-icon visibility
     *  composition [isActive] adds for control surfaces. Consumed by the
     *  game-audio gate ([GameAudioRecorder]): recording ends on explicit
     *  levers — Turn Off, the Anki settings toggle, consent death, the
     *  status-bar capture chip — never because the icon is merely off
     *  screen. The deciding story (audit 2026-08-03): Hide for Now + hotkey
     *  card-making keeps mining audio for those cards. Keeping
     *  [floatingIconSuppressed] out of this predicate also keeps the gate
     *  honest about its push-points — it only moves on events that already
     *  trigger a [CaptureService.reconcileGameAudio]. */
    fun isSessionActive(ctx: Context): Boolean {
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
            // A borrowed MediaProjection session (startLive's
            // wantMpStreamConsent grant, which the game-audio recorder may
            // also be riding) does NOT die with the accessibility session —
            // without this stop, the system's capture indicator outlives Turn
            // Off until process death. Burns the single-use consent token, so
            // the next borrow re-prompts: state honesty over prompt
            // avoidance, the same call as the status-bar-chip revoke
            // handling. Gated on realization — accessibility sessions that
            // never borrowed have no controller to tear down.
            CaptureService.instance?.mediaProjectionControllerIfInitialized?.destroy()
        }
        // Session off ⇒ the game-audio gate closes. The projection teardown
        // above already stopped the recorder via its teardown listener; this
        // makes the stop deterministic for both backend branches.
        CaptureService.instance?.reconcileGameAudio()
    }

    /** Accessibility-backend activate: show the floating icon. Gates on the
     *  service being BOUND, not merely enabled in system Settings: the icon
     *  is hosted by the bound service's OverlayUiController, and after a
     *  force stop Android leaves the service enabled-but-unbound (the
     *  accessibility settings page reports it as "malfunctioning") and never
     *  rebinds it until the user toggles it off and on. Gating on the
     *  Settings string made Turn On claim success in that state while
     *  showing nothing. Returns false when the icon cannot be shown; callers
     *  split the repair prompt on [PlayTranslateAccessibilityService
     *  .isEnabled] — false means prompt to enable, true means the service is
     *  stuck and needs a re-toggle. */
    fun activateAccessibility(ctx: Context): Boolean {
        val connected = PlayTranslateAccessibilityService.isConnected
        Log.i(
            TAG,
            "activateAccessibility: connected=$connected " +
                "enabled=${PlayTranslateAccessibilityService.isEnabled(ctx)}"
        )
        if (!connected) return false
        // Cold-start CaptureService when this summon is the first thing to
        // happen this boot (QS tile tap without ever opening the app): the
        // icon is a11y-hosted, but every feature behind it lives on
        // CaptureService, whose other starters (MainActivity, the MP tile
        // branch's ACTION_MP_ACTIVATE) haven't run — leaving the menu's
        // actions as silent no-ops on a null instance (field report
        // 2026-08-03). A plain intent is enough: onStartCommand handles the
        // foreground promotion/demotion, onCreate re-wires the hotkey
        // callbacks, and the menu actions lazy-configure via configureSaved.
        // Background-start is covered by the tile-click tempAllowList grant
        // (the mechanism the MP branch documents), with SYSTEM_ALERT_WINDOW
        // as the backstop for non-tile callers.
        if (CaptureService.instance == null) {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java)
            )
        }
        Prefs(ctx).showOverlayIcon = true
        // Turn On is an explicit summon — lift the boot / Hide-for-Now
        // suppression before reconciling, or the gate below would eat it.
        // The setter's tile push also covers the pref write above (the tile
        // reads both at bind time), so no standalone refresh here.
        setFloatingIconSuppressed(ctx, false)
        CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
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
