package com.playtranslate.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared "when does the service come back" label for the user-facing
 * rate-limit messaging (results note, floating-icon pill, camera note)
 * and the settings pages' cooldown line ([cooldownLine]): a cooldown
 * ending within ~24h ([isWithinDay]) renders as a locale-formatted time
 * ("3:42 PM"), a longer one (DeepL monthly quota) as a short date
 * ("Sep 1").
 */
object CooldownTimeLabel {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** True when [retryAt] is less than 24h after [nowMs]: the label is a
     *  time, not a date. */
    fun isWithinDay(retryAt: Long, nowMs: Long): Boolean = retryAt - nowMs < DAY_MS

    fun format(context: Context, retryAt: Long, nowMs: Long = System.currentTimeMillis()): String =
        if (isWithinDay(retryAt, nowMs)) {
            android.text.format.DateFormat.getTimeFormat(context).format(Date(retryAt))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(retryAt))
        }
}
