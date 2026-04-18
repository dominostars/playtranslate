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

    /** Translator for source→target headword translation (Tier 2). */
    fun get(sourceLangTranslationCode: String, targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        val key = sourceLangTranslationCode to targetLang
        return cache.computeIfAbsent(key) { TranslationManager(sourceLangTranslationCode, targetLang) }
    }

    /** Translator for EN→target definition translation. */
    fun getEnToTarget(targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        val key = "en" to targetLang
        return cache.computeIfAbsent(key) { TranslationManager("en", targetLang) }
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
