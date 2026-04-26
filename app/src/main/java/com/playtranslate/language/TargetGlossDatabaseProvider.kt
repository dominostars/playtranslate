package com.playtranslate.language

import android.content.Context

/**
 * Thread-safe singleton that caches per-target [TargetGlossLookup] instances.
 * Instances stay alive so in-flight lookups are never interrupted by a
 * language switch — only [release] tears them down.
 */
object TargetGlossDatabaseProvider {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, FstTargetGlossDatabase>()

    fun get(ctx: Context, targetLang: String): TargetGlossLookup? {
        if (targetLang == "en") return null
        cache[targetLang]?.let { return it }
        val packDir = LanguagePackStore.targetDirFor(ctx.applicationContext, targetLang)
        val db = FstTargetGlossDatabase.open(packDir) ?: return null
        val existing = cache.putIfAbsent(targetLang, db)
        if (existing != null) { db.close(); return existing }
        return db
    }

    /** Evicts the cached pack for [targetLang] (if any) and closes its handle.
     *  Call before deleting the underlying pack files so future lookups open
     *  a fresh reader rather than query a handle to a deleted file. */
    fun release(targetLang: String) {
        cache.remove(targetLang)?.close()
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
