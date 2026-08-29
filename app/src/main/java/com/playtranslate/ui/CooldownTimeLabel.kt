package com.playtranslate.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared "when does the service come back" label for the user-facing
 * rate-limit messaging (results note, floating-icon pill, camera note).
 * Mirrors the Settings row's choice in OnlineServicesController: a
 * cooldown ending within ~24h renders as a locale-formatted time
 * ("3:42 PM"), a longer one (DeepL monthly quota) as a short date
 * ("Sep 1") — both slot into an "until %1$s" sentence.
 */
object CooldownTimeLabel {
    fun format(context: Context, retryAt: Long): String {
        val withinDay = retryAt - System.currentTimeMillis() < 24L * 60 * 60 * 1000
        return if (withinDay) {
            android.text.format.DateFormat.getTimeFormat(context).format(Date(retryAt))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(retryAt))
        }
    }
}
