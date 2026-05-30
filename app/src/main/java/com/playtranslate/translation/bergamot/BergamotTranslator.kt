package com.playtranslate.translation.bergamot

import android.content.Context
import android.util.Log
import com.playtranslate.bergamot.BergamotNative
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-singleton wrapper around the slimt (Bergamot) engine. One Blocking
 * service, one [Mutex] (the engine is single-threaded), and a bounded
 * access-ordered cache of loaded per-direction models (native handles).
 *
 * **Native-handle lifecycle is the sharp edge.** Every cached value is a native
 * Model that MUST be destroyed on eviction and at [close]; the service handle
 * likewise. All access is mutex-serialized so a translate can't race an evict.
 */
class BergamotTranslator private constructor(private val context: Context) {

    private val mutex = Mutex()

    @Volatile private var serviceHandle: Long = 0L

    // direction -> native Model handle. accessOrder=true so iteration is
    // eldest-first (the LRU victims). Bounded by MAX_RESIDENT (>= 2 so a pivot
    // pair fits without evicting a model the current call still needs).
    private val models = LinkedHashMap<String, Long>(8, 0.75f, /* accessOrder = */ true)

    suspend fun translateSingle(files: BergamotModelFiles, text: String): String =
        mutex.withLock {
            ensureService()
            val model = ensureModel(files, keep = setOf(files.direction))
            BergamotNative.translate(serviceHandle, model, text)
                ?: throw IllegalStateException("Bergamot translate returned null (${files.direction})")
        }

    suspend fun translatePivot(
        first: BergamotModelFiles,
        second: BergamotModelFiles,
        text: String,
    ): String = mutex.withLock {
        ensureService()
        val keep = setOf(first.direction, second.direction)
        val m1 = ensureModel(first, keep)
        val m2 = ensureModel(second, keep)
        BergamotNative.pivot(serviceHandle, m1, m2, text)
            ?: throw IllegalStateException(
                "Bergamot pivot returned null (${first.direction}->${second.direction})"
            )
    }

    /** Free the cached native model for [direction] (called on uninstall). */
    suspend fun evictDirection(direction: String): Unit = mutex.withLock {
        models.remove(direction)?.let { BergamotNative.destroyModel(it) }
        Unit
    }

    /** Best-effort full teardown. Safe at app teardown. */
    fun close() {
        runCatching {
            for (h in models.values) BergamotNative.destroyModel(h)
            models.clear()
            if (serviceHandle != 0L) {
                BergamotNative.destroyService(serviceHandle)
                serviceHandle = 0L
            }
        }.onFailure { Log.w(TAG, "close() encountered $it (ignored)") }
    }

    private fun ensureService() {
        if (serviceHandle != 0L) return
        BergamotNative.ensureLoaded()
        serviceHandle = BergamotNative.createService(CACHE_SIZE)
        check(serviceHandle != 0L) { "Bergamot service creation failed" }
    }

    /**
     * Return the cached model handle for [files.direction], loading it if
     * absent. Evicts eldest entries not in [keep] to stay within MAX_RESIDENT.
     * Caller MUST hold [mutex].
     */
    private fun ensureModel(files: BergamotModelFiles, keep: Set<String>): Long {
        models[files.direction]?.let { return it }
        // Evict eldest-first (snapshot of keys is in access order), never a
        // direction the current call needs, until there's room for one more.
        val victims = ArrayList(models.keys).filter { it !in keep }
        var i = 0
        while (models.size >= MAX_RESIDENT && i < victims.size) {
            val k = victims[i++]
            models.remove(k)?.let { handle ->
                Log.i(TAG, "Evicting Bergamot model $k")
                BergamotNative.destroyModel(handle)
            }
        }
        val handle = BergamotNative.loadModel(
            files.modelPath,
            files.vocabPath,
            files.shortlistPath,
            files.encoderLayers,
            files.decoderLayers,
            files.feedForwardDepth,
            files.numHeads,
            "",
        )
        check(handle != 0L) { "Bergamot loadModel failed for ${files.direction}" }
        models[files.direction] = handle
        return handle
    }

    companion object {
        private const val TAG = "BergamotTranslator"
        private const val CACHE_SIZE = 1024L
        // >= 2 for a pivot pair; a couple extra to keep recently-used directions warm.
        private const val MAX_RESIDENT = 4

        @Volatile private var INSTANCE: BergamotTranslator? = null
        fun getInstance(context: Context): BergamotTranslator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BergamotTranslator(context.applicationContext).also { INSTANCE = it }
            }
    }
}
