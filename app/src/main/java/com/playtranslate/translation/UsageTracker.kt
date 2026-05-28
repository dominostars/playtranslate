package com.playtranslate.translation

import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.content.edit

/**
 * Per-backend daily token counter for cloud LLM translation backends.
 *
 * Neither the OpenAI nor the Gemini API exposes a monthly-remaining
 * endpoint for regular user keys, so the meter has to be accumulated
 * client-side. The counter resets at device-local midnight; the
 * [addTokens] write path performs the rollover, and both [todayTotal]
 * and [todayString] independently check the stored date so an idle
 * resume on a new day shows 0 rather than yesterday's number.
 *
 * Writes are serialized through an internal [Mutex] — without it, two
 * concurrent translate() calls reading the same stored value before
 * either writes back would silently lose one increment. The lock is
 * deliberately not held across the read paths (a slight read-during-
 * write race is harmless: the reader sees the pre- or post-increment
 * value, both equally truthful at that instant).
 *
 * [apply] is the right choice inside the critical section because the
 * mutex already provides the cross-call atomicity that [commit] would
 * also provide; [commit] would add a synchronous disk write inside the
 * lock for no gain.
 */
class UsageTracker(
    private val prefs: SharedPreferences,
    backendId: String,
) {
    private val keyDay = "usage_${backendId}_day"
    private val keyTokens = "usage_${backendId}_tokens"
    private val mutex = Mutex()

    suspend fun addTokens(promptTokens: Long, completionTokens: Long) = mutex.withLock {
        val today = isoToday()
        val storedDay = prefs.getString(keyDay, null)
        val base = if (storedDay == today) prefs.getLong(keyTokens, 0L) else 0L
        val newTotal = base + promptTokens.coerceAtLeast(0) + completionTokens.coerceAtLeast(0)
        prefs.edit {
            putString(keyDay, today)
            putLong(keyTokens, newTotal)
        }
    }

    fun todayTotal(): Long {
        val today = isoToday()
        val storedDay = prefs.getString(keyDay, null) ?: return 0L
        if (storedDay != today) return 0L
        return prefs.getLong(keyTokens, 0L)
    }

    /** Formatted total for the Settings status row, e.g. "12,345" in en-US. */
    fun todayString(): String = NumberFormat.getNumberInstance(Locale.getDefault()).format(todayTotal())

    private fun isoToday(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
