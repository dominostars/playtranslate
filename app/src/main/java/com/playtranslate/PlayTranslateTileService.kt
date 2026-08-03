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
 * Declared with `ACTIVE_TILE` meta-data in the manifest so external pref-write
 * sites can push the tile state via [TileSync.refresh] even when the QS shade
 * isn't visible.
 */
class PlayTranslateTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        renderState()
    }

    override fun onStartListening() {
        super.onStartListening()
        // Pick up permission grants the user made outside the app since the
        // tile last bound — most commonly "Display over other apps" granted
        // from this tile's own activation flow. Without this the cached
        // [CaptureBackendResolver.useMediaProjection] stays on its old value
        // until MainActivity resumes, and the tile keeps acting on the wrong
        // backend.
        CaptureBackendResolver.reresolve(this)
        renderState()
    }

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
                renderState()
            } else if (!Settings.canDrawOverlays(this)) {
                // The floating controls need "Display over other apps".
                openOverlayPermissionSettings()
            } else {
                // Activate routes through the service (ACTION_MP_ACTIVATE) so
                // it works even from a cold start.
                //
                // Android 15+ FGS-BG-start: this call is the foreground-credited
                // path. A tile click is recorded by the platform as a foreground
                // UI event and the system extends a 15-second `tempAllowList`
                // window to subsequent FGS starts from the same UID — verified
                // on the API 36 emulator at targetSdk=36:
                //   ActivityManager: Background started FGS: Allowed
                //     callingPackage: com.playtranslate
                //     intent: com.playtranslate.action.MP_ACTIVATE
                //     tempAllowListReason:<tile onclick, duration:15000>
                //     targetSdkVersion:36
                // No SYSTEM_ALERT_WINDOW exemption is involved at this step —
                // SAW only kicks in when [MediaProjectionConsentActivity]
                // launches itself from the now-foreground service (the BAL
                // exemption documented in that activity).
                startForegroundService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_MP_ACTIVATE)
                )
                renderState()
            }
            return
        }
        val a11y = PlayTranslateAccessibilityService.instance
        when {
            a11y != null -> {
                if (a11yControlsOn()) {
                    PlayTranslateAccessibilityService.disable(this, "tile_turn_off")
                } else {
                    // Canonical activate — shared with the Settings power
                    // button, so the tile also opens the game-audio gate
                    // instead of a hand-rolled pref write that skipped it.
                    // Also the post-boot summon path: suppressed icon +
                    // persisted pref lands here, not in disable.
                    CaptureLifecycle.activateAccessibility(this)
                }
                renderState()
            }
            // Not bound: either never enabled, or enabled but force-stopped —
            // the system then reports the service as malfunctioning and never
            // rebinds it until the user toggles it off and on. Both repairs
            // live on the accessibility settings screen, so send the user
            // there rather than dropping the tap. (A tap landing in the tiny
            // healthy window between process start and bind also ends up
            // here; the user backs out and the next tap works.)
            else -> {
                Log.i(
                    "PlayTranslateTile",
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

    private fun renderState() {
        val tile = qsTile ?: return
        if (!CaptureBackendResolver.active().requiresAccessibilityService) {
            // MediaProjection — the tile reflects whether capture is active.
            tile.state = if (CaptureLifecycle.isActive(this)) Tile.STATE_ACTIVE
                         else Tile.STATE_INACTIVE
            tile.subtitle = null
            tile.updateTile()
            return
        }
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled(this)
        val showing = a11yControlsOn()
        tile.state = if (a11yEnabled && showing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (!a11yEnabled) getString(R.string.tile_subtitle_a11y_required) else null
        tile.updateTile()
    }

    object TileSync {
        fun refresh(ctx: Context) {
            TileService.requestListeningState(
                ctx,
                ComponentName(ctx, PlayTranslateTileService::class.java)
            )
        }
    }
}
