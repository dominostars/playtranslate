package com.playtranslate.language

import android.content.Context

/**
 * Thread-safe singleton that caches one [TargetGlossDatabase] per target
 * language. Closes and reopens when the target language changes. Word taps
 * dispatch on [kotlinx.coroutines.Dispatchers.IO], so the double-checked
 * locking prevents races around language switches.
 */
object TargetGlossDatabaseProvider {
    @Volatile private var currentLang: String? = null
    @Volatile private var db: TargetGlossDatabase? = null

    fun get(ctx: Context, targetLang: String): TargetGlossDatabase? {
        if (targetLang == "en") return null
        if (targetLang == currentLang && db != null) return db
        synchronized(this) {
            if (targetLang == currentLang && db != null) return db
            db?.close()
            val file = LanguagePackStore.targetGlossDbFor(ctx.applicationContext, targetLang)
            db = TargetGlossDatabase.open(file)
            currentLang = targetLang
            return db
        }
    }

    fun close() = synchronized(this) { db?.close(); db = null; currentLang = null }
}
