package com.playtranslate.ui

import android.content.Context
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Shared "when does the service come back" label for the user-facing
 * rate-limit messaging (results note, floating-icon pill, camera note)
 * and the settings pages' cooldown line ([cooldownLine]): a cooldown
 * ending within ~24h ([isWithinDay]) renders as a locale-formatted time
 * ("3:42 PM"), a longer one (DeepL monthly quota) as a short date
 * ("Sep 1"). The PlayTranslate Cloud page reads the account's future dates
 * (a trial or a membership until) through [formatDateTime], a date with the
 * time of day, and its past ones (a trial that ended, a membership inactive
 * since) through [formatDate]; the Settings digest's trial end reads as a
 * cooldown does, through [format].
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

    /** A past instant [atMs] as a short date in the device's locale and
     *  zone: "Nov 3", or "Nov 3, 2025" when it is not in [nowMs]'s year (the
     *  locale's own order for month, day and year). For the account's ended
     *  dates; its future ones read through [formatDateTime]. */
    fun formatDate(atMs: Long, nowMs: Long = System.currentTimeMillis()): String = formatSkeleton(atMs, nowMs, "")

    /** A future instant [atMs] as [formatDate]'s date with the time of day,
     *  in the device's 12 or 24 hour setting: "Nov 3, 3:42 PM", "Nov 3,
     *  15:42", or "Nov 3, 2027, 3:42 PM" when it is not in [nowMs]'s year,
     *  the date and the time in one pattern of the locale's. For the
     *  account's trial and membership ends on the PlayTranslate Cloud page:
     *  each ends at an instant, not at the end of its day. */
    fun formatDateTime(context: Context, atMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        formatSkeleton(atMs, nowMs, if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm")

    /** [atMs] in the device's locale and zone, through the locale's best
     *  pattern for the month and day, the year only when it is not [nowMs]'s
     *  (read in the device's zone), followed by [timeSkeleton] (empty for a
     *  date alone). */
    private fun formatSkeleton(atMs: Long, nowMs: Long, timeSkeleton: String): String {
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val thisYear = Instant.ofEpochMilli(atMs).atZone(zone).year == Instant.ofEpochMilli(nowMs).atZone(zone).year
        val dateSkeleton = if (thisYear) "MMMd" else "yMMMd"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, dateSkeleton + timeSkeleton)
        return SimpleDateFormat(pattern, locale).format(Date(atMs))
    }
}
