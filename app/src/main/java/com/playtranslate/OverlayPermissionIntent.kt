package com.playtranslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Intent that opens the system "Display over other apps" permission screen
 * for this app. A caller launching it from a non-Activity context — e.g. a
 * TileService — must add [Intent.FLAG_ACTIVITY_NEW_TASK] itself.
 */
fun Context.overlayPermissionSettingsIntent(): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:$packageName".toUri(),
    )
