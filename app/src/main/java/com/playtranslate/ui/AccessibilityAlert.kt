package com.playtranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Shared "accessibility service required" alert for the settings surfaces whose
 * actions need the a11y service — hotkeys, the multi-display picker, and
 * enhanced auto-translate. The accent button opens system Accessibility
 * Settings; cancel just dismisses. Extracted from the old monolithic
 * SettingsBottomSheet so each migrated sub-page Activity can raise it directly
 * instead of routing back through a host callback.
 */
fun Activity.showAccessibilityRequiredAlert(requirement: AccessibilityRequirement) {
    // Below API 30 the accessibility takeScreenshot path doesn't exist (the
    // service is component-disabled — see res/values/bools.xml), so these
    // features can't be unlocked by enabling Accessibility. Tell the user they
    // need Android 11 instead of pointing at settings that can't help.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.requires_android_11_title))
            .setMessage(getString(R.string.requires_android_11_message))
            .addButton(
                getString(android.R.string.ok),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { /* informational — just dismiss */ }
            .show()
        return
    }
    val message = when (requirement) {
        AccessibilityRequirement.MULTI_DISPLAY ->
            getString(R.string.a11y_required_displays_message)
        AccessibilityRequirement.HOTKEY ->
            getString(R.string.a11y_required_hotkey_message)
        AccessibilityRequirement.ENHANCED_AUTO_TRANSLATE ->
            getString(R.string.a11y_required_enhanced_message)
    }
    OverlayAlert.Builder(this)
        .hideIcon()
        .setTitle(getString(R.string.a11y_required_alert_title))
        .setMessage(message)
        .addButton(
            getString(R.string.btn_open_a11y_settings),
            themeColor(R.attr.ptAccent),
            themeColor(R.attr.ptAccentOn),
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        .addCancelButton(getString(android.R.string.cancel))
        .show()
}
