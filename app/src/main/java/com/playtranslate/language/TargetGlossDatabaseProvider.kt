package com.playtranslate.language

import android.content.Context

/**
 * Thread-safe singleton that caches [TargetGlossDatabase] instances by target
 * language. Instances stay alive so in-flight lookups are never interrupted
 * by a language switch — only [close] tears them down.
 */
object TargetGlossDatabaseProvider {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, TargetGlossDatabase>()

    fun get(ctx: Context, targetLang: String): TargetGlossDatabase? {
        if (targetLang == "en") return null
        cache[targetLang]?.let { return it }
        val file = LanguagePackStore.targetGlossDbFor(ctx.applicationContext, targetLang)
        val db = TargetGlossDatabase.open(file) ?: return null
        val existing = cache.putIfAbsent(targetLang, db)
        if (existing != null) { db.close(); return existing }
        return db
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
