package com.playtranslate.translation

import android.content.Context
import android.os.Process
import com.playtranslate.translation.bergamot.BergamotModelManager
import com.playtranslate.translation.bergamot.BergamotTranslator

/**
 * Offline NMT backend: Firefox Translations (Bergamot) via slimt built with the
 * gemmology int8 backend — correct on arm64 (avoids the ruy garbage bug) and
 * ~18 ms/sentence on a Snapdragon 8 Gen 2.
 *
 * The everyday "fast offline" tier: priority 28, just above ML Kit (30) and
 * below the heavy on-device LLMs (25–27). [isUsable] returns false (so the
 * waterfall falls through to ML Kit) when the pair is unsupported by Mozilla's
 * xx↔en model set (the ~14 long-tail targets) or the model for the current pair
 * isn't downloaded.
 */
class BergamotBackend(
    context: Context,
    private val enabledProvider: () -> Boolean,
) : TranslationBackend, BatchTranslator {

    private val appContext = context.applicationContext
    val manager = BergamotModelManager(appContext)
    private val translator get() = BergamotTranslator.getInstance(appContext)

    override val id: BackendId = "bergamot"
    override val displayName: String = "Firefox Translations"
    override val priority: Int = 28
    override val requiresInternet: Boolean = false
    override val isDegradedFallback: Boolean = false
    override val qualityStars: StarRating = 2.0f
    override val speedStars: StarRating = 5.0f

    /** Fast single-hop en→target — the preferred dictionary definition-gloss
     *  tier when its model is installed (see DefinitionGlossTranslators). */
    override val usableForDefinitionGloss: Boolean = true

    /** arm64-only (the .so) — gates the backend off 32-bit, like the MNN tier. */
    fun supportsRequiredAbi(): Boolean = Process.is64Bit()

    override fun isUsable(source: String, target: String): Boolean {
        if (!supportsRequiredAbi()) return false
        if (!enabledProvider()) return false
        if (source.equals(target, ignoreCase = true)) return false
        return manager.isInstalled(source, target) // implies supportsPair
    }

    override suspend fun translate(text: String, source: String, target: String): String {
        val dirs = manager.requiredDirections(source, target)
            ?: throw IllegalStateException("Bergamot: unsupported pair $source->$target")
        return if (dirs.size == 1) {
            val f = manager.filesFor(dirs[0])
                ?: throw IllegalStateException("Bergamot: ${dirs[0]} not installed")
            translator.translateSingle(f, text)
        } else {
            val f1 = manager.filesFor(dirs[0])
                ?: throw IllegalStateException("Bergamot: ${dirs[0]} not installed")
            val f2 = manager.filesFor(dirs[1])
                ?: throw IllegalStateException("Bergamot: ${dirs[1]} not installed")
            translator.translatePivot(f1, f2, text)
        }
    }

    /**
     * The engine is single-threaded behind one mutex, so a batch is translated
     * sequentially. slimt batches *sentences within a single call*, so each
     * element (often multi-sentence) is already internally batched; cross-element
     * native batching is a future optimization (plan §7).
     */
    override suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String> = texts.map { translate(it, source, target) }

    override fun close() {
        // No-op by design — mirrors OnDeviceLlmBackend.close(). [translator] is a
        // shared process singleton ([BergamotTranslator.getInstance]) that lives
        // for the process and outlives individual BergamotBackend instances,
        // which the registry replaces on TranslationBackendRegistry.init()
        // re-inits. A backend being swapped out must not tear down the engine
        // every other backend/warm-up shares. Its native memory is reclaimed at
        // process death; a test needing deterministic teardown builds its own
        // instance via BergamotTranslator.createForTest(ctx) and closes that —
        // never the shared singleton.
    }
}
