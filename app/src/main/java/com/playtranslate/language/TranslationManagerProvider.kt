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
    @Volatile private var enTargetLang: String? = null
    @Volatile private var enTm: TranslationManager? = null

    /** Translator for source→target headword translation (Tier 2). */
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

    /** Translator for EN→target definition translation. */
    fun getEnToTarget(targetLang: String): TranslationManager? {
        if (targetLang == "en") return null
        if (targetLang == enTargetLang && enTm != null) return enTm
        synchronized(this) {
            if (targetLang == enTargetLang && enTm != null) return enTm
            enTm?.close()
            enTm = TranslationManager("en", targetLang)
            enTargetLang = targetLang
            return enTm
        }
    }

    fun close() = synchronized(this) {
        tm?.close(); tm = null; currentKey = null
        enTm?.close(); enTm = null; enTargetLang = null
    }
}
