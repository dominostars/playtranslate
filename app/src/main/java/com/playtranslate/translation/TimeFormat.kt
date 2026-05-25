package com.playtranslate.translation

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Lenient ISO-8601 → epoch-millis parser. Returns null on any parse
 * failure — callers (e.g. [DeepLBackend]'s `/v2/usage` end_time) treat
 * unparseable timestamps as "unknown reset" and fall through to a
 * synthesized retry boundary.
 */
fun parseIso8601(s: String?): Long? = s?.let {
    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Epoch-millis of the next midnight in America/Los_Angeles, the
 * documented reset boundary for Gemini's free-tier per-day request
 * quotas. Using device TZ here would be wrong: Gemini's RPD resets at
 * Pacific midnight regardless of where the user is. [now] is injectable
 * for unit tests.
 */
fun nextMidnightPacific(now: Long = System.currentTimeMillis()): Long {
    val pacific = ZoneId.of("America/Los_Angeles")
    val today = Instant.ofEpochMilli(now).atZone(pacific).toLocalDate()
    return today.plusDays(1).atStartOfDay(pacific).toInstant().toEpochMilli()
}

/**
 * Epoch-millis of 00:00 UTC on the first day of the next month — the
 * synthesized retry boundary for DeepL Free's monthly quota, since
 * `/v2/usage` doesn't expose an `end_time` on Free keys. Real reset
 * may be a few hours off (DeepL anchors per-key, not per-calendar);
 * the existing `currentQuota()` re-probe at expiry catches the drift.
 */
fun firstOfNextMonthUtc(now: Long = System.currentTimeMillis()): Long {
    val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
    return today.withDayOfMonth(1).plusMonths(1)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
