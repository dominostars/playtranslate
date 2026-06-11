package com.playtranslate.yomitan

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Encoding helpers for the `kanji` table. Pure JVM so the two spots where a
 * silent data bug could hide — the reading split and the meanings
 * round-trip — are unit-testable without SQLite.
 */
internal object KanjiData {

    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    /** kanji_bank on/kun fields are space-separated strings where "" means
     *  "no readings" — must yield an empty list, never `[""]`. */
    fun splitReadings(raw: String): List<String> =
        raw.split(' ', '　').map { it.trim() }.filter { it.isNotEmpty() }

    /** Meanings are arbitrary strings (commas, quotes, separators all
     *  legal), so they're stored as a JSON array rather than a joined
     *  string a delimiter could collide with. */
    fun encodeMeanings(meanings: List<String>): String = gson.toJson(meanings)

    fun decodeMeanings(encoded: String): List<String> = try {
        gson.fromJson<List<String>>(encoded, stringListType).orEmpty().filterNotNull()
    } catch (_: Exception) {
        emptyList()
    }
}
