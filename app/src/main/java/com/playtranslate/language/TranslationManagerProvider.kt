package com.playtranslate.language

import com.playtranslate.TranslationManager

/**
 * Thread-safe singleton that caches one [TranslationManager] per
 * `(sourceLang, targetLang)` pair. Prevents the per-tap leak that would
 * occur if each word-tap lookup created a fresh ML Kit translator instance.
 * Closes and recreates when the language pair changes.
 */
object TranslationManagerProvider {
    @Volatile private var currentKey: Pair<String, String>? = null
    @Volatile private var tm: TranslationManager? = null

    fun get(sourceLangTranslationCode: String, targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        val key = sourceLangTranslationCode to targetLang
        if (key == currentKey && tm != null) return tm
        synchronized(this) {
            if (key == currentKey && tm != null) return tm
            tm?.close()
            tm = TranslationManager(sourceLangTranslationCode, targetLang)
            currentKey = key
            return tm
        }
    }

    fun close() = synchronized(this) { tm?.close(); tm = null; currentKey = null }
}
