package com.playtranslate

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle

/**
 * Quick Settings tile that mirrors the accessibility session's lifecycle
 * truth ([CaptureLifecycle.isActive] + [Prefs.showOverlayIcon]) — tap to hide
 * the floating icon, tap again to show it. Not the raw pref alone: post-boot
 * the icon is suppressed until the app is opened ([CaptureLifecycle
 * .floatingIconSuppressed]) while the pref persists as true, and a tile
 * reading only the pref would render ACTIVE with no icon on screen — its
 * first tap then disabling when the user meant summon. Hide path delegates to
 * [PlayTranslateAccessibilityService.disable], which also stops live mode if
 * running (icon off ⇒ PlayTranslate considered disabled).
 *
 * Declared with `ACTIVE_TILE` meta-data in the manifest: SystemUI then binds
 * the tile on a click, on its first appearance and on request, never on a
 * shade open, so every lifecycle flip pushes its state through
 * [TileSync.refresh] (a direct render while listening, a listening request
 * otherwise, see there).
 */
class PlayTranslateTileService : TileService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // The two lifecycle callbacks are the platform's own word on whether the
    // tile is in Quick Settings, and fire for the in-app add request and for
    // the shade's edit mode alike (the app's request only ever learned about
    // its own adds: Codex, 2026-10-06). The preference feeds the Hotkeys
    // row, the end-of-onboarding prompt and the Fix-disappearing-icon page.
    override fun onTileAdded() {
        super.onTileAdded()
        Prefs(this).quickTileAdded = true
        renderState("added")
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Prefs(this).quickTileAdded = false
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        // Pick up permission grants the user made outside the app since the
        // tile last bound — most commonly "Display over other apps" granted
        // from this tile's own activation flow. Without this the cached
        // [CaptureBackendResolver.useMediaProjection] stays on its old value
        // until MainActivity resumes, and the tile keeps acting on the wrong
        // backend.
        CaptureBackendResolver.reresolve(this)
        renderState("start_listening")
    }

    override fun onStopListening() {
        listening = false
        super.onStopListening()
    }

    /** Mirrors the platform's own listening flag through the two callbacks
     *  that flip it (a tile removal and onDestroy route through
     *  onStopListening as well). Main thread only, like the callbacks. */
    private var listening = false

    override fun onClick() {
        super.onClick()
        // Defensive: re-resolve here too in case onStartListening's cached
        // resolution is stale by the time the user taps (e.g., a permission
        // toast was acted on between bind and tap). Cheap when nothing
        // changed — see [CaptureBackendResolver.reresolve].
        CaptureBackendResolver.reresolve(this)
        // No capture permission either way — the tile is a control surface,
        // not an onboarding surface. Defer the backend choice (accessibility
        // vs MediaProjection) to the app's onboarding flow in MainActivity
        // rather than baking it into the tile's no-permission fall-through.
        if (!PlayTranslateAccessibilityService.isEnabled(this) &&
            !Settings.canDrawOverlays(this)) {
            openMainActivityOnboarding()
            return
        }
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            // MediaProjection backend — the tile activates / deactivates the
            // capture lifecycle.
            if (CaptureLifecycle.isActive(this)) {
                CaptureLifecycle.deactivate(this)
                renderState("click:off")
            } else if (!Settings.canDrawOverlays(this)) {
                // The floating controls need "Display over other apps".
                openOverlayPermissionSettings()
            } else {
                // Activate hops through [TileActivateActivity] so SystemUI
                // collapses the shade first; the activity sends
                // ACTION_MP_ACTIVATE to the service (cold start included)
                // from the foreground, so no FGS background-start allowance
                // is needed. Until 2026-10-09 the tile started the service
                // itself under the click's 15-second tempAllowList, and the
                // consent dialog then stood over the open shade. SAW only
                // kicks in when [MediaProjectionConsentActivity] launches
                // itself from the service (the BAL exemption documented in
                // that activity); that step is unchanged.
                startActivityAndCollapseCompat(
                    TileActivateActivity.intent(this, TileActivateActivity.ACTION_MEDIA_PROJECTION)
                )
                renderState("click:on")
            }
            return
        }
        val a11y = PlayTranslateAccessibilityService.instance
        when {
            a11y != null -> {
                if (a11yControlsOn()) {
                    PlayTranslateAccessibilityService.disable(this, "tile_turn_off")
                } else {
                    // Canonical activate — the hop runs
                    // [CaptureLifecycle.activateAccessibility], shared with
                    // the Settings power button, so the tile also opens the
                    // game-audio gate instead of a hand-rolled pref write
                    // that skipped it. Also the post-boot summon path:
                    // suppressed icon + persisted pref lands here, not in
                    // disable. Through [TileActivateActivity] so the shade
                    // collapses, as on the MediaProjection branch.
                    startActivityAndCollapseCompat(
                        TileActivateActivity.intent(this, TileActivateActivity.ACTION_ACCESSIBILITY)
                    )
                }
                renderState("click:a11y")
            }
            // Not bound while enabled: the resolver picks this backend only
            // with the service enabled, and the nothing-granted case went to
            // onboarding above, so this is a kill whose restart was blocked.
            // The system then reports the service as malfunctioning and does
            // not rebind it until the user toggles it off and on, which
            // lives on the accessibility settings screen, so send the user
            // there rather than dropping the tap. (A tap landing in the
            // tiny healthy window between process start and bind also ends
            // up here; the user backs out and the next tap works.) The
            // enabled flag is logged so a field log can refute this.
            else -> {
                Log.i(
                    TAG,
                    "a11y tile tap while unbound: " +
                        "enabled=${PlayTranslateAccessibilityService.isEnabled(this)}"
                )
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapseCompat(intent)
            }
        }
    }

    /** Open the system "Display over other apps" screen for PlayTranslate and
     *  collapse the shade. The MediaProjection floating controls need it. */
    private fun openOverlayPermissionSettings() {
        val intent = overlayPermissionSettingsIntent()
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapseCompat(intent)
    }

    /** Open MainActivity (and collapse the shade) so the user can complete
     *  onboarding. Used when neither accessibility nor "Display over other
     *  apps" is granted — the backend choice belongs in the app's onboarding
     *  flow, not in the tile's no-permission fall-through. */
    private fun openMainActivityOnboarding() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivityAndCollapseCompat(intent)
    }

    // On API 34+ the PendingIntent overload signals SystemUI to dismiss
    // the shade. Pre-34 uses the deprecated Intent overload — it's the
    // only way to signal an explicit collapse on those API levels (the
    // PendingIntent overload only exists from API 34+, where the Intent
    // overload throws). Lint's StartActivityAndCollapseDeprecated check
    // is reachability-blind to SDK_INT guards and flags the literal call
    // regardless, hence the file-scoped suppression here.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** The accessibility-backend truth both tile surfaces share: the user
     *  wants the floating controls ([Prefs.showOverlayIcon]) AND the session
     *  is actually on ([CaptureLifecycle.isActive] — post-boot suppression,
     *  live-mode override). One method so [renderState] and [onClick] cannot
     *  drift — the tile must always display the direction its next tap will
     *  move. The pref keeps its own seat beside isActive because isActive's
     *  dual-screen arm deliberately ignores it (dual-screen in-app surfaces
     *  treat the session as on regardless of the icon toggle); the tile
     *  still reads OFF and tap-summons in that state. */
    private fun a11yControlsOn(): Boolean =
        Prefs(this).showOverlayIcon && CaptureLifecycle.isActive(this)

    /** Renders the lifecycle truth into [qsTile]. False when the service has
     *  no tile yet (created but not bound), so [TileSync.refresh] can fall
     *  back to a listening request. Logged with the caller's reason: the
     *  field log of 2026-10-09 could not say which renders ran. */
    private fun renderState(reason: String): Boolean {
        val tile = qsTile ?: return false
        val active: Boolean
        val subtitle: String?
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            // MediaProjection — the tile reflects whether capture is active.
            active = CaptureLifecycle.isActive(this)
            subtitle = null
        } else {
            val a11yEnabled = PlayTranslateAccessibilityService.isEnabled(this)
            active = a11yEnabled && a11yControlsOn()
            subtitle = if (!a11yEnabled) getString(R.string.tile_subtitle_a11y_required) else null
        }
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = subtitle
        tile.updateTile()
        Log.i(TAG, "render($reason): ${if (active) "ACTIVE" else "INACTIVE"}")
        return true
    }

    /** Pushes the lifecycle truth to the tile by the platform's two-mode
     *  protocol: while this service is listening the Tile handle is valid
     *  and the state is rendered directly; otherwise SystemUI is asked for a
     *  listening pass, which arrives as [onStartListening] and renders there.
     *
     *  One mode alone is not enough. The request used to be the whole push,
     *  and Android 16 broke that: SystemUI's
     *  `qs_custom_tile_click_guaranteed_bug_fix` (enabled in 16.0.0_r1) no
     *  longer ends the listening state an active tile's click began, so after
     *  a tap this service stays listening until the shade closes, and the
     *  platform TileService drops a request's onStartListening while already
     *  listening (MSG_START_LISTENING is guarded by !mListening), so
     *  [renderState] never ran. The MediaProjection consent dialog is a
     *  SystemUI dialog over the open shade, so the grant and its push both
     *  landed in that window and the tile kept the INACTIVE the click had
     *  rendered while the capture ran (Moto G 2025, 2026-10-09; a tap on the
     *  stale tile then turned PlayTranslate off). Before 16 every updateTile
     *  on an active tile ended the listening state, which is why the request
     *  alone worked on the Thor. The direct render stays inside the
     *  listening state although SystemUI would apply it for the 30 s the
     *  service stays bound after onStopListening (TileServices.updateQsTile,
     *  16.0.0_r1, checks no listening state): the Tile is documented valid
     *  only between onStartListening and onStopListening, and a request from
     *  outside that state is never dropped (Codex review, 2026-10-09).
     *
     *  Every caller is on the main thread (service callbacks, overlay work,
     *  the controller's main handler), as the tile's own callbacks are, so
     *  [listening] needs no synchronisation. */
    object TileSync {
        fun refresh(ctx: Context) {
            val bound = instance
            if (bound != null && bound.listening && bound.renderState("push")) return
            Log.i(TAG, "refresh: listening pass requested (bound=${bound != null})")
            TileService.requestListeningState(
                ctx,
                ComponentName(ctx, PlayTranslateTileService::class.java)
            )
        }
    }

    companion object {
        /** One tag for the tile flow: the service and [TileActivateActivity]. */
        const val TAG = "PlayTranslateTile"

        /** The live service between onCreate and onDestroy; [TileSync] renders
         *  through it while it is listening. */
        @Volatile
        private var instance: PlayTranslateTileService? = null
    }
}
