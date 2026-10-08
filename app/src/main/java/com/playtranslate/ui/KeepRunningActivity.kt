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
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.BugReport

/**
 * "Fix disappearing icon": the settings that make it less likely the phone
 * closes PlayTranslate while it is on, or that bring it back when it does,
 * one card per [KeepRunningItems.Id]. Every "Open settings" row tries the
 * ROM's own screen first and falls back to the app's App info page with a
 * toast saying so: none of the ROM screens can be verified here, and a
 * silent landing on App info after a card promised another screen would be
 * a dead end, so the toast sends the user to search Settings for the
 * setting the card title names. Instruction-only rows have no action. When
 * every setting is already made, a centered label says so in the cards'
 * place. Under the cards, always, the Report a bug row from Settings →
 * Support with its words and both gestures ([BugReport]), so a user with
 * nothing left to change has the next step on the same page. Opened from
 * the Support section and from the kill notice, which stops showing once
 * this page has been seen. The words on every card are sourced in the
 * string comments.
 */
class KeepRunningActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_keep_running

    private lateinit var items: ViewGroup
    private lateinit var empty: TextView

    /** See [render]: the accessibility service is enabled but not bound. */
    private var accessibilityStuck = false

    override fun onContentCreated(savedInstanceState: Bundle?) {
        items = findViewById(R.id.keepRunningItems)
        empty = findViewById(R.id.tvKeepRunningEmpty)
        bindReportBugRow()
        Prefs(this).keepRunningPageSeen = true
    }

    /** The Settings Support row's Report a bug, same strings, same two
     *  gestures: tap emails the logs to support, hold opens the share
     *  sheet. Static in the layout, so it shows whatever [render] decides. */
    private fun bindReportBugRow() {
        val row = findViewById<View>(R.id.rowReportBug)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.settings_support_report_bug_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.settings_support_report_bug_subtitle)
            isVisible = true
        }
        row.setOnClickListener { BugReport.email(this, lifecycleScope) }
        row.setOnLongClickListener { BugReport.share(this, lifecycleScope); true }
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
        // Enabled in Settings but not bound: a kill whose restart was
        // blocked leaves the service listed as enabled, and Android does
        // not re-bind it until the user toggles it off and on (the state
        // Settings' "needs a restart" alert repairs). The card stays,
        // worded as that repair.
        val accessibilityEnabled = PlayTranslateAccessibilityService.isEnabled(this)
        accessibilityStuck = accessibilityEnabled && !PlayTranslateAccessibilityService.isConnected
        val ids = KeepRunningItems.ids(
            rom = KeepRunningItems.detectRom(Build.MANUFACTURER, Build.BRAND),
            batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(packageName) == true,
            accessibilityOn = accessibilityEnabled && !accessibilityStuck,
            tileAdded = prefs.quickTileAdded,
        )
        empty.isVisible = ids.isEmpty()
        val inflater = LayoutInflater.from(this)
        for (id in ids) {
            val card = inflater.inflate(R.layout.keep_running_item, items, false)
            val row = card.findViewById<View>(R.id.rowKeepRunningItem)
            row.findViewById<TextView>(R.id.tvRowTitle).setText(titleOf(id))
            row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
                text = lineOf(id)
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
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> R.string.keep_running_oppo_auto_launch_title
        KeepRunningItems.Id.VIVO_AUTOSTART -> R.string.keep_running_vivo_autostart_title
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> R.string.keep_running_samsung_never_sleeping_title
        KeepRunningItems.Id.BATTERY -> R.string.keep_running_battery_title
        KeepRunningItems.Id.ACCESSIBILITY ->
            if (accessibilityStuck) R.string.a11y_stuck_title else R.string.keep_running_accessibility_title
        KeepRunningItems.Id.QUICK_TILE -> R.string.keep_running_tile_title
    }

    /** The line under the title. The accessibility card's carries the
     *  Android 13+ restricted-settings step ([AccessibilityHelp]) when the
     *  service still has to be enabled; a stuck service was enabled once,
     *  so it was already allowed. */
    private fun lineOf(id: KeepRunningItems.Id): CharSequence = when (id) {
        KeepRunningItems.Id.XIAOMI_AUTOSTART -> getString(R.string.keep_running_xiaomi_autostart_line)
        KeepRunningItems.Id.XIAOMI_BATTERY -> getString(R.string.keep_running_xiaomi_battery_line)
        KeepRunningItems.Id.XIAOMI_LOCK_RECENTS -> getString(R.string.keep_running_xiaomi_lock_line)
        KeepRunningItems.Id.HUAWEI_APP_LAUNCH -> getString(R.string.keep_running_huawei_app_launch_line)
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> getString(R.string.keep_running_oppo_auto_launch_line)
        KeepRunningItems.Id.VIVO_AUTOSTART -> getString(R.string.keep_running_vivo_autostart_line)
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> getString(R.string.keep_running_samsung_never_sleeping_line)
        KeepRunningItems.Id.BATTERY -> getString(R.string.keep_running_battery_line)
        KeepRunningItems.Id.ACCESSIBILITY ->
            if (accessibilityStuck) {
                getString(R.string.keep_running_accessibility_restart_line)
            } else {
                AccessibilityHelp.withRestrictedSettingsStep(
                    this, getString(R.string.keep_running_accessibility_line),
                )
            }
        KeepRunningItems.Id.QUICK_TILE -> getString(R.string.keep_running_tile_line)
    }

    /** The row's tap, or null for an instruction-only row. */
    private fun actionOf(id: KeepRunningItems.Id): (() -> Unit)? = when (id) {
        KeepRunningItems.Id.XIAOMI_AUTOSTART -> openFirst(RomScreens.xiaomiAutostart())
        KeepRunningItems.Id.XIAOMI_BATTERY -> openFirst(RomScreens.xiaomiBatterySaver(this))
        KeepRunningItems.Id.XIAOMI_LOCK_RECENTS -> null
        KeepRunningItems.Id.HUAWEI_APP_LAUNCH -> openFirst(RomScreens.huaweiAppLaunch())
        KeepRunningItems.Id.OPPO_AUTO_LAUNCH -> openFirst(RomScreens.oppoAutoLaunch())
        KeepRunningItems.Id.VIVO_AUTOSTART -> openFirst(RomScreens.vivoAutostart())
        KeepRunningItems.Id.SAMSUNG_NEVER_SLEEPING -> openFirst(RomScreens.samsungNeverSleepingApps())
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

    /** Starts the first of [candidates] the platform can launch. When none
     *  can, says so in a toast and opens the app's App info page, so the
     *  user knows the screen they were promised is not the one in front of
     *  them and goes looking for the setting by the card's name. */
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
        if (!launched) {
            Toast.makeText(this, R.string.keep_running_screen_unavailable, Toast.LENGTH_LONG).show()
            startActivity(RomScreens.appDetails(this))
        }
    }

    /** The ROM-specific settings screens. Samsung's is the one documented
     *  entry point (developer.samsung.com/mobile/app-management.html, read
     *  2026-10-07: action com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY
     *  on package com.samsung.android.lool with extra activity_type 2 for
     *  Never sleeping apps); its older BatteryActivity component follows
     *  as a fallback. The other component names are the ROMs' own as
     *  carried by community lists of this kind; none was verified on a
     *  device, and they change across versions, so every list is tried in
     *  order and the caller falls back to [appDetails] with a toast. */
    internal object RomScreens {
        private fun component(pkg: String, cls: String) =
            Intent().setComponent(ComponentName(pkg, cls))

        fun appDetails(ctx: Context) = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The one-tap "Let app always run in background?" dialog, which
         *  needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission the
         *  manifest declares; the app's system page is the fallback. */
        fun batteryExemption(ctx: Context) = listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ),
        )

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

        fun samsungNeverSleepingApps() = listOf(
            Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool")
                .putExtra("activity_type", 2),
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
    }

    private companion object {
        const val TAG = "KeepRunning"
    }
}
