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

/** True while this annotation's imported-dictionary snapshot is still the
 *  live one. EVERY holder of a stored annotation must gate on this before
 *  serving it (the engine LRU does; LastSentenceCache does) — a stale
 *  annotation must fail toward re-annotation, never toward rendering
 *  pre-import readings. */
fun SentenceAnnotation.isImportCurrent(): Boolean =
    importGeneration == AnnotationGenerations.current()

/**
 * Engine-scoped LRU gated on the import generation: an entry serves only
 * while the generation it was computed under is still the live one, and a
 * stale hit is evicted rather than retried. One implementation for every
 * memo of Yomitan-dependent work (annotations, per-word resolutions);
 * language is implicit (one per engine) and pack swaps clear via the
 * engine's close().
 */
internal class GenerationLru<K : Any, V : Any>(private val maxEntries: Int) {
    private class Entry<V>(val value: V, val generation: Int)

    private val lru = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val hit = lru[key] ?: return null
        if (hit.generation != AnnotationGenerations.current()) {
            lru.remove(key)
            return null
        }
        return hit.value
    }

    /** [generation] is the stamp captured BEFORE the work that produced
     *  [value] (stamp-at-capture; see the engines' annotate). */
    @Synchronized
    fun put(key: K, value: V, generation: Int) {
        lru[key] = Entry(value, generation)
    }

    @Synchronized
    fun clear() = lru.clear()
}

/**
 * Engine-scoped annotation LRU for the live overlay's FULL-depth flips: live
 * capture re-OCRs the same lines cycle after cycle, and the reconciler
 * migrates same-text boxes, so hits dominate on settled screens (typewriter
 * animation produces new strings per frame — the measurement corpus must
 * include it; refactor doc §6). Keyed by exact text, stamped with the
 * annotation's own generation.
 */
internal class AnnotationCache(maxEntries: Int = 128) {
    private val lru = GenerationLru<String, SentenceAnnotation>(maxEntries)
    fun get(text: String): SentenceAnnotation? = lru.get(text)
    fun put(annotation: SentenceAnnotation) = lru.put(annotation.text, annotation, annotation.importGeneration)
    fun clear() = lru.clear()
}
