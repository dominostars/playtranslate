package com.playtranslate.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.R

/** The system "add PlayTranslate to Quick Settings" dialog, shared by the
 *  Hotkeys row, the end-of-onboarding prompt and the Keep-running page. */
object QuickTile {

    /** Shows the dialog over [activity]; [onAdded] runs when the tile is
     *  added or was already there. An error or a decline leaves the caller's
     *  row in place for a retry. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAdd(activity: Activity, onAdded: () -> Unit) {
        val statusBarManager = activity.getSystemService(StatusBarManager::class.java) ?: return
        val component = ComponentName(activity, PlayTranslateTileService::class.java)
        val icon = Icon.createWithResource(activity, R.drawable.ic_qs_tile)
        statusBarManager.requestAddTileService(
            component,
            activity.getString(R.string.tile_label),
            icon,
            ContextCompat.getMainExecutor(activity),
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> onAdded()
            }
        }
    }
}
