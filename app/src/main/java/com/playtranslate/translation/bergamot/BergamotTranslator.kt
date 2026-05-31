package com.playtranslate.translation.bergamot

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.bergamot.BergamotNative
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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

    // The slimt engine is single-threaded and its loadModel/translate/pivot calls
    // BLOCK for hundreds of ms. Run them all on one dedicated background thread so
    // they never execute on the caller's thread — the capture pipeline invokes
    // translate from the main thread, which would jank the UI / risk an ANR.
    // Single-threaded ⇒ inherently serialized; the mutex stays as defense for the
    // evict/close lifecycle paths.
    private val engineExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "bergamot-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    @Volatile private var serviceHandle: Long = 0L

    // direction -> native Model handle. accessOrder=true so iteration is
    // eldest-first (the LRU victims). Bounded by MAX_RESIDENT (>= 2 so a pivot
    // pair fits without evicting a model the current call still needs).
    private val models = LinkedHashMap<String, Long>(8, 0.75f, /* accessOrder = */ true)

    suspend fun translateSingle(files: BergamotModelFiles, text: String): String =
        withContext(engineDispatcher) {
            mutex.withLock {
                ensureService()
                val model = ensureModel(files, keep = setOf(files.direction))
                BergamotNative.translate(serviceHandle, model, text)
                    ?: throw IllegalStateException("Bergamot translate returned null (${files.direction})")
            }
        }

    suspend fun translatePivot(
        first: BergamotModelFiles,
        second: BergamotModelFiles,
        text: String,
    ): String = withContext(engineDispatcher) {
        mutex.withLock {
            ensureService()
            val keep = setOf(first.direction, second.direction)
            val m1 = ensureModel(first, keep)
            val m2 = ensureModel(second, keep)
            BergamotNative.pivot(serviceHandle, m1, m2, text)
                ?: throw IllegalStateException(
                    "Bergamot pivot returned null (${first.direction}->${second.direction})"
                )
        }
    }

    /** Free the cached native model for [direction] (called on uninstall). */
    suspend fun evictDirection(direction: String): Unit = withContext(engineDispatcher) {
        mutex.withLock {
            models.remove(direction)?.let { BergamotNative.destroyModel(it) }
        }
        Unit
    }

    /**
     * Best-effort teardown of this instance's native handles + engine thread.
     * Intended for tests that build their own instance via [createForTest]; the
     * shared [getInstance] singleton is never closed in production (it lives for
     * the process). The [INSTANCE] reset at the end is only a failsafe.
     */
    fun close() {
        runCatching {
            for (h in models.values) BergamotNative.destroyModel(h)
            models.clear()
            if (serviceHandle != 0L) {
                BergamotNative.destroyService(serviceHandle)
                serviceHandle = 0L
            }
        }.onFailure { Log.w(TAG, "close() encountered $it (ignored)") }
        engineExecutor.shutdown()
        // Failsafe only. By design nothing closes the shared singleton —
        // production never does, and tests own their own instance via
        // createForTest (for which INSTANCE !== this, so this is a no-op). But if
        // anything ever closes the cached singleton, drop it so a later
        // getInstance() rebuilds instead of returning this shut-down instance.
        synchronized(Companion) {
            if (INSTANCE === this) INSTANCE = null
        }
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
            files.targetVocabPath,
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

        /** The shared process engine — the production path. Never closed: it
         *  lives for the process, and the OS reclaims its native memory at
         *  process death. */
        fun getInstance(context: Context): BergamotTranslator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BergamotTranslator(context.applicationContext).also { INSTANCE = it }
            }

        /** A standalone instance for tests that need deterministic teardown
         *  (e.g. freeing native models between cases) without touching the
         *  shared [getInstance] singleton. Production must use [getInstance]. */
        @VisibleForTesting
        fun createForTest(context: Context): BergamotTranslator =
            BergamotTranslator(context.applicationContext)
    }
}
