package com.playtranslate.language

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-global Yomitan import generation. Imported term dictionaries
 * change mid-session with no engine eviction (install/update/delete/reorder
 * commit through YomitanDictionaryStore while engines stay cached), so a
 * cached annotation is valid only while its [SentenceAnnotation.importGeneration]
 * matches the current generation — the store bumps it on every
 * content-affecting mutation. Over-invalidation is harmless (one re-annotate);
 * under-invalidation would freeze pre-import readings on screen, the exact
 * staleness class the refactor doc §6 forbids the cache from introducing.
 */
object AnnotationGenerations {
    private val gen = AtomicInteger(0)
    fun current(): Int = gen.get()
    fun bump() { gen.incrementAndGet() }
}

/**
 * Engine-scoped annotation LRU for the live overlay's FULL-depth flips: live
 * capture re-OCRs the same lines cycle after cycle, and the reconciler
 * migrates same-text boxes, so hits dominate on settled screens (typewriter
 * animation produces new strings per frame — the measurement corpus must
 * include it; refactor doc §6). Keyed by exact text; language is implicit
 * (one cache per engine) and pack swaps clear via the engine's close().
 */
internal class AnnotationCache(private val maxEntries: Int = 128) {
    private val lru = object : LinkedHashMap<String, SentenceAnnotation>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SentenceAnnotation>,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(text: String): SentenceAnnotation? {
        val hit = lru[text] ?: return null
        if (hit.importGeneration != AnnotationGenerations.current()) {
            lru.remove(text)
            return null
        }
        return hit
    }

    @Synchronized
    fun put(annotation: SentenceAnnotation) {
        lru[annotation.text] = annotation
    }

    @Synchronized
    fun clear() = lru.clear()
}
