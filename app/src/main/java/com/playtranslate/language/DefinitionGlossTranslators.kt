package com.playtranslate.language

import android.util.Log
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Chooses the en→target [WordTranslator] used to gloss dictionary definitions
 * into the user's target language: [DefinitionResolver] Tier 3 (the English
 * fallback definitions) and the word-detail example fallback.
 *
 * The gloss path is a **waterfall over the backends that opt into**
 * [com.playtranslate.translation.TranslationBackend.usableForDefinitionGloss]
 * — currently the fast offline NMT tier (Bergamot / "Firefox Translations") and
 * the ML Kit fallback, never the slow on-device LLM tiers or the online backends
 * — tried in registry priority order. Bergamot (priority 28) wins whenever its
 * en→target model is installed (en→target is a single hop, its strength, and it
 * scores higher on both quality and speed than ML Kit); ML Kit (priority 30) is
 * the floor. Each [WordTranslator.translate] tries each usable candidate and
 * falls through on failure; if all fail (or the category is empty), it throws
 * and [DefinitionResolver.translateDefinitions] surfaces the original English.
 *
 * No backend is special-cased. ML Kit participates as the lowest-priority member
 * of the category and is invoked through its own backend like any other, so
 * dropping it from a build — or adding another fast offline NMT tier — needs no
 * change here. One consequence: routing through MlKitBackend means a gloss can
 * trigger an on-demand ML Kit model download, exactly as the main translation
 * waterfall already does for its offline fallback.
 *
 * The candidate set is resolved once per lookup (lazy, off-main) so Bergamot's
 * install probe in `isUsable` runs at most once per lookup, not per sense. Every
 * [DefinitionResolver] call site dispatches its lookup to Dispatchers.IO, so the
 * probe never runs on the main thread, and it is skipped entirely when a word
 * resolves from the native target pack (Tier 1) and nothing is glossed.
 *
 * Returns null only when [target] is "en" (definitions are already English).
 */
object DefinitionGlossTranslators {
    private const val TAG = "DefGlossTranslators"

    fun forTarget(target: String): WordTranslator? {
        if (target == "en") return null
        val candidates = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            TranslationBackendRegistry.orderedBackends()
                .filter { it.usableForDefinitionGloss && it.isUsable("en", target) }
        }
        return WordTranslator { text -> waterfall(candidates.value, text, target) }
    }

    /** Tries each gloss-capable candidate (priority order) for en→[target],
     *  falling through on failure. Throws if none succeed — the caller's catch
     *  then surfaces the original English. Cancellation always propagates. */
    private suspend fun waterfall(
        candidates: List<TranslationBackend>,
        text: String,
        target: String,
    ): String {
        var lastError: Exception? = null
        for (backend in candidates) {
            try {
                return backend.translate(text, "en", target)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "gloss ${backend.id} failed for en->$target; trying next", e)
            }
        }
        throw lastError ?: IllegalStateException("no gloss backend for en->$target")
    }
}
