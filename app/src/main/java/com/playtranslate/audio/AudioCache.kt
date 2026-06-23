package com.playtranslate.audio

import android.content.Context
import com.playtranslate.PtJson
import com.playtranslate.language.PackIntegrity
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Best-effort on-disk cache for fetched recordings, under [Context.cacheDir]
 * (OS-evictable — clips are always re-fetchable, so they do NOT belong in
 * noBackupFilesDir). Bounded by an LRU size cap; "no candidates" results are
 * remembered with a TTL so a missing word isn't re-queried on every tap, while
 * coverage still improves as Commons grows (the marker expires).
 *
 * Writes commit atomically via [PackIntegrity.atomicReplace] (temp on the same
 * filesystem). Attribution travels in a `.json` sidecar beside each clip.
 */
class AudioCache(context: Context, private val maxBytes: Long = MAX_BYTES) {

    private val appCtx = context.applicationContext
    private val root: File get() = File(appCtx.cacheDir, DIR).apply { mkdirs() }

    private fun sourceDir(sourceId: String) = File(root, safe(sourceId)).apply { mkdirs() }
    private fun sidecar(clip: File) = File(clip.parentFile, clip.name + ".json")

    /** Stable on-disk path for a clip [key] (e.g. a Commons filename). */
    fun clipFile(sourceId: String, key: String): File {
        val ext = key.substringAfterLast('.', "").lowercase().takeIf { it.matches(EXT) } ?: "audio"
        return File(sourceDir(sourceId), "${hash(key)}.$ext")
    }

    /** Cached clip if present (touched for LRU), else null. */
    suspend fun getClip(sourceId: String, key: String): File? = withContext(Dispatchers.IO) {
        clipFile(sourceId, key).takeIf { it.exists() && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Atomically write [bytes] as the clip for [key] (+ optional attribution
     *  sidecar), evict to budget, return the committed file. */
    suspend fun putClip(sourceId: String, key: String, bytes: ByteArray, attribution: Attribution?): File =
        withContext(Dispatchers.IO) {
            val dest = clipFile(sourceId, key)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.writeBytes(bytes)
            PackIntegrity.atomicReplace(tmp, dest)
            if (attribution != null) {
                runCatching {
                    sidecar(dest).writeText(PtJson.lenient.encodeToString(Attribution.serializer(), attribution))
                }
            }
            trimToBudget()
            dest
        }

    suspend fun readAttribution(sourceId: String, key: String): Attribution? = withContext(Dispatchers.IO) {
        val side = sidecar(clipFile(sourceId, key)).takeIf { it.exists() } ?: return@withContext null
        runCatching { PtJson.lenient.decodeFromString(Attribution.serializer(), side.readText()) }.getOrNull()
    }

    // --- negative (no-result) cache with TTL ---

    fun isNegativeFresh(sourceId: String, lang: SourceLangId, surface: String): Boolean {
        val f = negFile(sourceId, lang, surface)
        if (!f.exists()) return false
        val fresh = System.currentTimeMillis() - f.lastModified() < NEGATIVE_TTL_MS
        if (!fresh) f.delete()
        return fresh
    }

    fun markNegative(sourceId: String, lang: SourceLangId, surface: String) {
        runCatching { negFile(sourceId, lang, surface).writeText("1") }
    }

    private fun negDir(sourceId: String) = File(sourceDir(sourceId), "neg").apply { mkdirs() }
    private fun negFile(sourceId: String, lang: SourceLangId, surface: String) =
        File(negDir(sourceId), hash("${lang.code}|$surface") + ".neg")

    /** LRU eviction over clip files only (sidecars/markers/temps excluded from the budget). */
    private fun trimToBudget() {
        val clips = root.walkTopDown().filter { it.isFile && it.extension !in NON_CLIP }.toList()
        var total = clips.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in clips.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
            sidecar(f).delete()
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")
    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    companion object {
        private const val DIR = "audioCache"
        private const val MAX_BYTES = 64L * 1024 * 1024            // 64 MB clip budget
        private const val NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private val EXT = Regex("[a-z0-9]{1,5}")
        private val NON_CLIP = setOf("json", "neg", "tmp")
    }
}
