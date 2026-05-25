package com.playtranslate.translation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Minimal parser for Go-style duration strings, the format OpenAI uses
 * in its rate-limit reset headers (`x-ratelimit-reset-requests`,
 * `x-ratelimit-reset-tokens`). Examples:
 *
 *   "1s"          → 1 second
 *   "6m0s"        → 6 minutes
 *   "4m12.172s"   → 4 minutes, 12.172 seconds
 *   "1h30m"       → 1 hour, 30 minutes
 *
 * Supports `h` / `m` / `s` / `ms` / `us` / `µs` / `ns` components in
 * any combination, matching Go's `time.ParseDuration` for the subset
 * OpenAI emits. Returns null on any parse failure; callers fall through
 * to the [CooldownState] ladder when that happens.
 */
object GoDuration {

    fun parse(s: String?): Duration? {
        val trimmed = s?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        var total = 0.0
        var matched = false
        var pos = 0
        val len = trimmed.length

        while (pos < len) {
            val numStart = pos
            while (pos < len && (trimmed[pos].isDigit() || trimmed[pos] == '.')) pos++
            if (pos == numStart) return null
            val num = trimmed.substring(numStart, pos).toDoubleOrNull() ?: return null

            val unitStart = pos
            while (pos < len && !trimmed[pos].isDigit() && trimmed[pos] != '.') pos++
            val unit = trimmed.substring(unitStart, pos)
            val unitMs = unitToMilliseconds(unit) ?: return null

            total += num * unitMs
            matched = true
        }

        return if (matched) total.milliseconds else null
    }

    private fun unitToMilliseconds(unit: String): Double? = when (unit) {
        "ns"        -> 0.000_001
        "us", "µs"  -> 0.001
        "ms"        -> 1.0
        "s"         -> 1_000.0
        "m"         -> 60_000.0
        "h"         -> 3_600_000.0
        else        -> null
    }
}
