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
     *  pair. Pure infrastructure — no UI policy. The translation waterfall
     *  (MlKitBackend) calls this since it needs ML Kit even for `target == "en"`,
     *  unlike the dictionary tier-2 path which deliberately bypasses it. */
    fun getOrCreate(source: String, target: String): TranslationManager {
        val key = source to target
        return cache.computeIfAbsent(key) { TranslationManager(source, target) }
    }

    /** Translator for source→target headword translation (Tier 2).
     *  Returns null when [targetLang] == "en" because JMdict gloss is
     *  already English and ML Kit is not needed in that case. */
    fun get(sourceLangTranslationCode: String, targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        return getOrCreate(sourceLangTranslationCode, targetLang)
    }

    /** Translator for EN→target definition translation. */
    fun getEnToTarget(targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        return getOrCreate("en", targetLang)
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
