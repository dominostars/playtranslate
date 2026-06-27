package com.playtranslate.language

import com.playtranslate.TranslationManager

/**
 * Thread-safe singleton that caches [TranslationManager] instances by
 * `(sourceLang, targetLang)` key. Instances are kept alive so in-flight
 * translations are never interrupted by a language switch — only
 * [close] tears them down.
 */
object TranslationManagerProvider {
    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, TranslationManager>()

    /** Always returns a non-null [TranslationManager] for the (source, target)
     *  pair. Pure infrastructure — no UI policy. [com.playtranslate.translation.MlKitBackend]
     *  is the sole caller: every ML Kit translation — the sentence waterfall and the
     *  dictionary offline-fallback path alike — now routes through that backend, so this
     *  is the single point that constructs ML Kit translators. */
    fun getOrCreate(source: String, target: String): TranslationManager {
        val key = source to target
        return cache.computeIfAbsent(key) { TranslationManager(source, target) }
    }

    /** Closes and removes every cached manager whose source or target is [lang]
     *  (in translationCode space). Used when [lang]'s ML Kit model is reclaimed,
     *  so the next translate reconstructs the Translator and re-downloads rather
     *  than holding a handle to a now-deleted model. Other pairs are untouched. */
    fun evictLanguage(lang: String) {
        cache.keys
            .filter { it.first == lang || it.second == lang }
            .forEach { key -> cache.remove(key)?.close() }
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
