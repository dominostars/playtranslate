package com.playtranslate

/**
 * LRU cache of past translations keyed by the `(text, source, target)`
 * triple so cross-pair stale reads are impossible by construction. A
 * cached JA→EN gloss can never be served for a JA→ES lookup.
 *
 * The preferred backend (DeepL vs Lingva — chosen by whether a DeepL
 * key is configured) is tracked separately via [reconcilePreferredBackend].
 * A backend toggle clears the cache so the newly-preferred backend's
 * translations aren't shadowed by whatever was cached under the old
 * preference. Pair changes are NOT handled here — the key does that job.
 *
 * Not thread-safe. [com.playtranslate.CaptureService] mutates exclusively
 * from its `serviceScope` (Main-dispatched) with network calls dispatched
 * to IO via `withContext`, so read/write ordering on the cache is
 * implicitly serial.
 */
class TranslationCache(private val capacity: Int = 500) {

    data class Key(val text: String, val source: String, val target: String)

    /** Value is the translated text and an optional inline note. Note is
     *  non-null only for ML Kit fallback results; callers check
     *  `note == null` to decide whether an entry is cacheable
     *  (online-backend-sourced). */
    private val lru = object : LinkedHashMap<Key, Pair<String, String?>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Pair<String, String?>>?): Boolean =
            size > capacity
    }

    private var lastPreferredBackend: String? = null

    operator fun get(key: Key): Pair<String, String?>? = lru[key]

    operator fun set(key: Key, value: Pair<String, String?>) {
        lru[key] = value
    }

    operator fun contains(key: Key): Boolean = key in lru

    fun size(): Int = lru.size

    /**
     * Called at the top of each translation waterfall. If [preferredBackend]
     * differs from the last-seen value, the cache is cleared — a backend
     * toggle (adding or removing a DeepL key) shouldn't serve results from
     * the previous backend. No-op on first call and when the preference
     * hasn't changed.
     */
    fun reconcilePreferredBackend(preferredBackend: String) {
        if (lastPreferredBackend != null && lastPreferredBackend != preferredBackend) {
            lru.clear()
        }
        lastPreferredBackend = preferredBackend
    }
}
