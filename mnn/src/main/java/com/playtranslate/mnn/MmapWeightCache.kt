package com.playtranslate.mnn

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * App-side lifecycle of the per-model mmap weight cache (`<modelDir>/.mmap-cache`).
 *
 * MNN validates that cache by nothing but the presence of a `sync.static`
 * marker: it checks neither the model, nor the runtime config, nor the MNN
 * build that wrote it (its cache-file prefix is `<precision>_<memory>_<power>`
 * only). The layout stamp kept here is the app's own version of that check. It
 * is bumped whenever something on OUR side changes what a cached `.static`
 * file contains, and a cache carrying any other stamp, or none, is deleted
 * before MNN can map it, so the next load starts cold instead of serving
 * stale bytes.
 *
 * Stamp history:
 *  - none (3.0.x–3.1.1): the multimodal encoders of the Gemma 4 E2B and Qwen
 *    3.5 bundles were loaded through a second runtime with the SAME cache
 *    prefix as the LLM, overwrote the LLM's cached weights from offset 0 and
 *    left a marker the audio encoder later trusted (SIGSEGV on warm load).
 *  - 2: encoders are no longer loaded (`is_visual` / `is_audio` false in
 *    `mnn_chat.cpp`); the cache holds the LLM's weights only.
 */
object MmapWeightCache {
    /** Bump when the app-side runtime config or the MNN build changes the
     *  contents of a cached `.static` file. */
    const val LAYOUT_VERSION = 2
    const val LAYOUT_FILE_NAME = "layout"
    private const val TAG = "MmapWeightCache"

    fun dir(modelDir: File): File = File(modelDir, MMAP_CACHE_DIR_NAME)

    /**
     * Deletes a cache written under a different (or no) layout stamp. Must run
     * before any warm/cold decision that keys on the cache's `sync.static`
     * marker, because a stale cache still carries that marker.
     *
     * @return true when no stale cache remains (none existed, it is current, or
     *   it was deleted); false when a stale cache exists and could not be
     *   deleted, in which case the caller must not map it.
     */
    fun dropStaleLayout(modelDir: File): Boolean {
        val cache = dir(modelDir)
        if (!cache.exists()) return true
        val stamp = readStamp(cache)
        if (stamp == LAYOUT_VERSION) return true
        // An empty, unstamped dir (the fit check creates it ahead of a cold
        // load) holds nothing stale; prepareDir stamps it in place.
        if (stamp == null && cache.list().isNullOrEmpty()) return true
        val gone = cache.deleteRecursively() || !cache.exists()
        Log.i(
            TAG,
            "mmap cache at $cache has layout ${stamp ?: "unstamped"}, current is $LAYOUT_VERSION; " +
                if (gone) "deleted" else "DELETE FAILED",
        )
        return gone
    }

    /**
     * The directory MNN should write into for this load: stale layouts are
     * dropped, the directory exists, and it carries the current stamp.
     *
     * @return null if a stale cache could not be removed, or the directory
     *   could not be created or stamped.
     */
    fun prepareDir(modelDir: File): File? {
        if (!dropStaleLayout(modelDir)) return null
        val cache = dir(modelDir)
        if (!cache.exists() && !cache.mkdirs()) {
            Log.w(TAG, "mmap cache dir mkdirs failed for $cache")
            return null
        }
        if (readStamp(cache) != LAYOUT_VERSION) {
            try {
                File(cache, LAYOUT_FILE_NAME).writeText(LAYOUT_VERSION.toString())
            } catch (e: IOException) {
                Log.w(TAG, "mmap cache layout stamp failed for $cache: ${e.message}")
                return null
            }
        }
        return cache
    }

    private fun readStamp(cache: File): Int? {
        val f = File(cache, LAYOUT_FILE_NAME)
        if (!f.isFile) return null
        return try {
            f.readText().trim().toIntOrNull()
        } catch (e: IOException) {
            null
        }
    }
}
