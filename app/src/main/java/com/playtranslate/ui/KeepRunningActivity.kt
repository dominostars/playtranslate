package com.playtranslate.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.R

/**
 * "Keep PlayTranslate running": the settings that make it less likely the
 * phone closes PlayTranslate while it is on, one card per
 * [KeepRunningItems.Id]. Every "Open settings" row tries the ROM's own
 * screen first and falls back to the app's system settings page, so no
 * row is ever dead; instruction-only rows have no action. Opened from the
 * Support section and from the kill notice, which stops showing once this
 * page has been seen.
 */
class KeepRunningActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_keep_running

    private lateinit var items: ViewGroup

    /** See [render]: the accessibility service is enabled but not bound. */
    private var accessibilityStuck = false

    override fun onContentCreated(savedInstanceState: Bundle?) {
        items = findViewById(R.id.keepRunningItems)
        Prefs(this).keepRunningPageSeen = true
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every return: a setting the user just changed drops
        // its card (battery, accessibility, the tile).
        render()
    }

    private fun render() {
        items.removeAllViews()
        val prefs = Prefs(this)
        val powerManager = getSystemService(PowerManager::class.java)
        // Enabled in Settings but not bound: after a force stop Android
        // leaves the service listed as enabled and never re-binds it until
        // the user toggles it off and on (the same state Settings' "needs a
        // restart" alert repairs). The card stays, worded as that repair.
        val accessibilityEnabled = PlayTranslateAccessibilityService.isEnabled(this)
        accessibilityStuck = accessibilityEnabled && !PlayTranslateAccessibilityService.isConnected
        val ids = KeepRunningItems.ids(
            rom = KeepRunningItems.detectRom(Build.MANUFACTURER, Build.BRAND),
            batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
            accessibilityOn = accessibilityEnabled && !accessibilityStuck,
            tileAdded = prefs.quickTileAdded,
        )
        val inflater = LayoutInflater.from(this)
        for (id in ids) {
            val card = inflater.inflate(R.layout.keep_running_item, items, false)
            val row = card.findViewById<View>(R.id.rowKeepRunningItem)
            row.findViewById<TextView>(R.id.tvRowTitle).setText(titleOf(id))
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                setText(lineOf(id))
                isVisible = true
            }
            val action = actionOf(id)
            val icon = row.findViewById<ImageView>(R.id.ivRowIcon)
            if (action == null) {
                icon.isVisible = false
                row.isClickable = false
                row.isFocusable = false
            } else {
                row.setOnClickListener { action() }
            }
            items.addView(card)
        }
    }

    private fun titleOf(id: KeepRunningItems.Id): Int = when (id) {
        KeepRunningItems.Id.XIAOMI_AUTOSTART -> R.string.keep_running_xiaomi_autostart_title
        KeepRunningItems.Id.XIAOMI_BATTERY -> R.string.keep_running_xiaomi_battery_title
        KeepRunningItems.Id.XIAOMI_LOCK_RECENTS -> R.string.keep_running_xiaomi_lock_title
        KeepRunningItems.Id.HUAWEI_APP_LAUNCH -> R.string.keep_running_huawei_app_launch_title
        KeepRunningItems.Id.HUAWEI_CLOSE_AFTER_LOCK -> R.string.keep_running_huawei_close_after_lock_title
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> R.string.keep_running_oppo_auto_launch_title
        KeepRunningItems.Id.VIVO_AUTOSTART -> R.string.keep_running_vivo_autostart_title
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> R.string.keep_running_samsung_never_sleeping_title
        KeepRunningItems.Id.BATTERY -> R.string.keep_running_battery_title
        KeepRunningItems.Id.ACCESSIBILITY ->
            if (accessibilityStuck) R.string.a11y_stuck_title else R.string.keep_running_accessibility_title
        KeepRunningItems.Id.QUICK_TILE -> R.string.keep_running_tile_title
    }

    private fun lineOf(id: KeepRunningItems.Id): Int = when (id) {
        KeepRunningItems.Id.XIAOMI_AUTOSTART -> R.string.keep_running_xiaomi_autostart_line
        KeepRunningItems.Id.XIAOMI_BATTERY -> R.string.keep_running_battery_line
        KeepRunningItems.Id.XIAOMI_LOCK_RECENTS -> R.string.keep_running_xiaomi_lock_line
        KeepRunningItems.Id.HUAWEI_APP_LAUNCH -> R.string.keep_running_huawei_app_launch_line
        KeepRunningItems.Id.HUAWEI_CLOSE_AFTER_LOCK -> R.string.keep_running_huawei_close_after_lock_line
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> R.string.keep_running_oppo_auto_launch_line
        KeepRunningItems.Id.VIVO_AUTOSTART -> R.string.keep_running_vivo_autostart_line
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> R.string.keep_running_samsung_never_sleeping_line
        KeepRunningItems.Id.BATTERY -> R.string.keep_running_battery_line
        KeepRunningItems.Id.ACCESSIBILITY ->
            if (accessibilityStuck) R.string.keep_running_accessibility_restart_line
            else R.string.keep_running_accessibility_line
        KeepRunningItems.Id.QUICK_TILE -> R.string.keep_running_tile_line
    }

    /** The row's tap, or null for an instruction-only row. */
    private fun actionOf(id: KeepRunningItems.Id): (() -> Unit)? = when (id) {
        KeepRunningItems.Id.XIAOMI_AUTOSTART -> openFirst(RomScreens.xiaomiAutostart())
        KeepRunningItems.Id.XIAOMI_BATTERY -> openFirst(RomScreens.xiaomiBatterySaver(this))
        KeepRunningItems.Id.XIAOMI_LOCK_RECENTS -> null
        KeepRunningItems.Id.HUAWEI_APP_LAUNCH -> openFirst(RomScreens.huaweiAppLaunch())
        KeepRunningItems.Id.HUAWEI_CLOSE_AFTER_LOCK -> openFirst(RomScreens.batterySettings())
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> openFirst(RomScreens.oppoAutoLaunch())
        KeepRunningItems.Id.VIVO_AUTOSTART -> openFirst(RomScreens.vivoAutostart())
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> openFirst(RomScreens.samsungDeviceCareBattery())
        KeepRunningItems.Id.BATTERY -> openFirst(RomScreens.batteryExemption(this))
        KeepRunningItems.Id.ACCESSIBILITY -> openFirst(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        KeepRunningItems.Id.QUICK_TILE -> {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    QuickTile.requestAdd(this) {
                        Prefs(this).quickTileAdded = true
                        render()
                    }
                }
            }
        }
    }

    /** Starts the first of [candidates] the platform can launch, and the
     *  app's own system settings page when none can. */
    private fun openFirst(candidates: List<Intent>): () -> Unit = {
        val launched = candidates.any { intent ->
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.d(TAG, "screen unavailable: ${intent.component ?: intent.action}")
                false
            }
        }
        if (!launched) startActivity(RomScreens.appDetails(this))
    }

    /** The ROM-specific settings screens, by component name. Component names
     *  are the ROMs' own and change across versions; every list is tried in
     *  order and the caller falls back to [appDetails]. Sources: the Xiaomi
     *  and Samsung pages of dontkillmyapp.com, and the component lists
     *  other overlay apps ship for the same purpose. */
    internal object RomScreens {
        private fun component(pkg: String, cls: String) =
            Intent().setComponent(ComponentName(pkg, cls))

        fun appDetails(ctx: Context) = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The one-tap "stop optimising battery for this app" dialog, which
         *  needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission the
         *  manifest declares; the app's system page is the fallback. */
        fun batteryExemption(ctx: Context) = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ),
        )

        /** The system battery screen; Huawei's "Close apps after screen
         *  lock" sits under its More battery settings. */
        fun batterySettings() = listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

        fun xiaomiAutostart() = listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )

        fun xiaomiBatterySaver(ctx: Context) = listOf(
            component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", ctx.packageName)
                .putExtra("package_label", ctx.getString(R.string.app_name)),
        )

        fun huaweiAppLaunch() = listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )

        fun oppoAutoLaunch() = listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )

        fun vivoAutostart() = listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )

        fun samsungDeviceCareBattery() = listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
    }

    private companion object {
        const val TAG = "KeepRunning"
    }
}
