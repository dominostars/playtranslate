package com.playtranslate.language

import android.util.Log
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Resolves "the offline fallback translator" for a (source, target) pair — the
 * single entry point every word-tap / definition path uses instead of reaching
 * for ML Kit directly. Opaque to consumers: they never learn whether Bergamot or
 * ML Kit answered.
 *
 * Used for BOTH directions a lookup needs:
 *  - headword + example translation (source→target): [DefinitionResolver] Tier 2
 *    and [DefinitionResolver.translateExamples];
 *  - definition glossing (en→target): [DefinitionResolver] Tier 3 and the
 *    word-detail example fallback — just [forTarget], i.e. `forPair("en", target)`.
 *
 * The fallback is a **waterfall over the backends that opt into**
 * [com.playtranslate.translation.TranslationBackend.usableAsOfflineFallback] — the
 * fast on-device NMT tier (Bergamot / "Firefox Translations") and the ML Kit floor,
 * never the slow on-device LLM tiers (Gemma/HyMt/Qwen) or the online backends —
 * tried in registry priority order. Bergamot (priority 28) wins whenever its model
 * for the pair is installed (single hop or English pivot — see
 * [com.playtranslate.translation.BergamotBackend.isUsable]); ML Kit (priority 30) is
 * the floor and is always usable (it lazily downloads on first translate). Each
 * [WordTranslator.translate] tries each usable candidate and falls through on
 * failure; if all fail (or the category is empty) it throws, and the caller's catch
 * surfaces the original text.
 *
 * No backend is special-cased — dropping ML Kit from a build, or adding another fast
 * offline tier, needs no change here. One consequence: routing through MlKitBackend
 * means a fallback translate can trigger an on-demand ML Kit model download, exactly
 * as the main translation waterfall already does.
 *
 * The candidate set is resolved once per returned translator (lazy, SYNCHRONIZED) so
 * Bergamot's on-disk install probe in `isUsable` runs at most once per lookup, not
 * per sense. Every [DefinitionResolver] call site dispatches its lookup to
 * Dispatchers.IO, so the probe never runs on the main thread, and it is skipped
 * entirely when a word resolves from the native target pack (Tier 1) and nothing is
 * translated.
 */
object OfflineFallbackTranslators {
    private const val TAG = "OfflineFallbackXlt"

    /** The offline fallback translator for [source]→[target], or null when the two
     *  are equal (nothing to translate). The returned [WordTranslator] waterfalls over
     *  the offline-fallback backends in priority order (Bergamot first, ML Kit floor;
     *  on-device LLMs and online backends excluded), so consumers never learn which
     *  engine answered. */
    fun forPair(source: String, target: String): WordTranslator? {
        if (source.equals(target, ignoreCase = true)) return null
        val candidates = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            offlineFallbackCandidates(source, target)
        }
        return WordTranslator { text -> waterfall(candidates.value, text, source, target) }
    }

    /** Definition-gloss direction (English definitions → [target]). Equivalent to
     *  `forPair("en", target)`. */
    fun forTarget(target: String): WordTranslator? = forPair("en", target)

    /** Tries each offline-fallback candidate (priority order) for [source]→[target],
     *  falling through on failure. Throws if none succeed — the caller's catch then
     *  surfaces the original text. Cancellation always propagates. */
    private suspend fun waterfall(
        candidates: List<TranslationBackend>,
        text: String,
        source: String,
        target: String,
    ): String {
        var lastError: Exception? = null
        for (backend in candidates) {
            try {
                return backend.translate(text, source, target)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "offline fallback ${backend.id} failed for $source->$target; trying next", e)
            }
        }
        throw lastError ?: IllegalStateException("no offline fallback backend for $source->$target")
    }
}

/**
 * The offline-fallback backend candidates for [source]→[target], in registry
 * priority order (Bergamot before the ML Kit floor). The ONE definition of the
 * candidate set, shared by [OfflineFallbackTranslators] (opaque word-tap
 * waterfall, download-on-demand allowed) and
 * [com.playtranslate.translation.ShortTextOfflineRoute] (named backend,
 * downloads forbidden) so the two policies can't drift on membership.
 * Bergamot's `isUsable` is an on-disk probe — call once per operation, never
 * per text.
 */
internal fun offlineFallbackCandidates(source: String, target: String): List<TranslationBackend> =
    TranslationBackendRegistry.orderedBackends()
        .filter { it.usableAsOfflineFallback && it.isUsable(source, target) }
