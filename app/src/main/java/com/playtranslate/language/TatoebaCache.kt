package com.playtranslate.language

/**
 * In-memory LRU for Tatoeba sentence-pair lookups, shared across the
 * whole process via [TatoebaClient]'s singleton. Access-order
 * `LinkedHashMap` matches [com.playtranslate.TranslationCache] — when
 * the map exceeds [capacity] the least-recently-used key is evicted.
 *
 * No disk persistence (same policy as [com.playtranslate.ui.LastSentenceCache]).
 * The API is fast enough on cache miss that crossing process boundaries
 * isn't worth the disk bookkeeping.
 */
internal class TatoebaCache(private val capacity: Int = 200) {

    private val lru = object :
        LinkedHashMap<String, List<TatoebaClient.SentencePair>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<TatoebaClient.SentencePair>>?
        ): Boolean = size > capacity
    }

    @Synchronized
    fun get(key: String): List<TatoebaClient.SentencePair>? = lru[key]

    @Synchronized
    fun put(key: String, value: List<TatoebaClient.SentencePair>) {
        lru[key] = value
    }
}
