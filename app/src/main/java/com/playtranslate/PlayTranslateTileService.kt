package com.playtranslate

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that mirrors [Prefs.showOverlayIcon] — tap to hide the
 * floating icon, tap again to show it. Hide path delegates to
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
        renderState()
    }

    override fun onClick() {
        super.onClick()
        val a11y = PlayTranslateAccessibilityService.instance
        when {
            a11y != null -> {
                val prefs = Prefs(this)
                if (prefs.showOverlayIcon) {
                    PlayTranslateAccessibilityService.disable(this, "tile_turn_off")
                } else {
                    prefs.showOverlayIcon = true
                    a11y.reconcileFloatingIcons()
                    TileSync.refresh(this)
                }
                renderState()
            }
            // Enabled in Settings but Android hasn't bound the service to our
            // process yet. Drop the tap rather than redirect to accessibility
            // settings — the user already granted, and the rebind is imminent.
            PlayTranslateAccessibilityService.isEnabled(this) -> {}
            else -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    val pi = PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pi)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
    }

    private fun renderState() {
        val tile = qsTile ?: return
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled(this)
        val showing = Prefs(this).showOverlayIcon
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
