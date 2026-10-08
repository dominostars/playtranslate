package com.playtranslate.ui

import android.content.Context
import android.os.Build
import com.playtranslate.R

/**
 * The one sentence every "enable the accessibility service" prompt owes
 * the user on Android 13 and later: the service's switch is greyed out for
 * an APK installed from a downloaded file (the README's install route), and
 * the way through is to tap the greyed switch once, then App info's
 * overflow menu, Allow restricted settings. Read in AOSP main on
 * 2026-10-07: the installer tags the package, the greyed switch's dialog
 * marks the app op IGNORED, and App info shows the menu item only in that
 * state. Appended by the four enable prompts and the Keep-running card.
 * The stuck-service alert does not use it, since a service that was once
 * enabled has already been allowed, and neither does the tile, whose
 * accessibility branch is reachable only with the service enabled.
 */
object AccessibilityHelp {

    /** The restricted-settings step, or null below Android 13, where the
     *  block does not exist. */
    fun restrictedSettingsStep(ctx: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.getString(R.string.a11y_restricted_settings_addendum)
        } else {
            null
        }

    /** [message] with [restrictedSettingsStep] as a closing paragraph when
     *  there is one. */
    fun withRestrictedSettingsStep(ctx: Context, message: String): String {
        val step = restrictedSettingsStep(ctx) ?: return message
        return "$message\n\n$step"
    }
}
