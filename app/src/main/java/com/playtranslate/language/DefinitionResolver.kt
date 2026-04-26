package com.playtranslate.language

import android.util.Log
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Single-string translation abstraction, extracted for testability. */
fun interface WordTranslator {
    suspend fun translate(text: String): String
}

/**
 * Result of a word-tap definition lookup through the multi-tier fallback chain.
 * Every variant carries the source-language [response] from the engine; the
 * subtype indicates which tier resolved the target-language definition.
 */
sealed interface DefinitionResult {
    val response: DictionaryResponse

    /**
     * Target-language definition from the downloaded pack. The renderer
     * iterates [targetSenses] directly (target-driven mode) — JMdict's
     * non-English sense blocks don't reliably align with English sense
     * ordinals, so by-ordinal merging with the source entry is gone.
     * Source-language meanings are not surfaced when this variant is
     * returned; English is hidden when the user picked a non-English
     * target. See WordDetailBottomSheet.setupPanel for the render path.
     */
    data class Native(
        override val response: DictionaryResponse,
        val targetSenses: List<TargetSense>,
        val source: String,
    ) : DefinitionResult

    /** ML Kit translated the headword. Definitions may also be translated. */
    data class MachineTranslated(
        override val response: DictionaryResponse,
        val translatedHeadword: String,
        /** Per-sense translated definitions (index-parallel to response.entries[0].senses). Null if translation unavailable. */
        val translatedDefinitions: List<String>? = null,
    ) : DefinitionResult

    /** No headword translation available. Definitions may be translated or English. */
    data class EnglishFallback(
        override val response: DictionaryResponse,
        /** Per-sense translated definitions. Null = show English as-is. */
        val translatedDefinitions: List<String>? = null,
    ) : DefinitionResult
}

/**
 * Centralizes the word-tap definition fallback chain:
 *
 * 1. **Native** — target-language pack definition (JMdict/Wiktionary/CFDICT)
 * 2. **MachineTranslated** — ML Kit headword translation + translated definitions
 * 3. **EnglishFallback** — English definitions (translated to target when possible)
 *
 * All word-tap UI paths use this resolver instead of calling
 * [SourceLanguageEngine.lookup] directly.
 */
class DefinitionResolver(
    private val engine: SourceLanguageEngine,
    private val targetGlossDb: TargetGlossLookup?,
    private val mlKitTranslator: WordTranslator?,
    private val targetLang: String,
    private val enToTargetTranslator: WordTranslator? = null,
) {
    suspend fun lookup(word: String, reading: String?): DefinitionResult? {
        val response = engine.lookup(word, reading)
        if (response == null) {
            Log.d(TAG, "lookup($word, $reading): engine returned null")
            return null
        }
        Log.d(TAG, "lookup($word, $reading): engine returned ${response.entries.size} entries, targetLang=$targetLang, targetGlossDb=${targetGlossDb != null}, mlKit=${mlKitTranslator != null}")

        val entry = response.entries.firstOrNull()

        // Tier 1: target-pack native definition
        if (targetGlossDb != null && targetLang != "en") {
            val sourceLang = engine.profile.id.packId.code
            val headwords = buildSet {
                entry?.let { e ->
                    e.headwords.forEach { hw ->
                        hw.written?.let { add(it) }
                    }
                    add(e.slug)
                }
                add(word)
            }
            Log.d(TAG, "  Tier 1: sourceLang=$sourceLang, headwords=$headwords, reading=$reading")
            for (hw in headwords) {
                val senses = targetGlossDb.lookup(sourceLang, hw, reading)
                Log.d(TAG, "  Tier 1: lookup($sourceLang, $hw, $reading) -> ${senses?.size ?: "null"}")
                if (senses != null) {
                    // Native pack hit → renderer iterates target senses
                    // directly (target-driven mode). No per-sense MT
                    // fallback computed; we save N ML Kit calls per word
                    // tap and don't pretend non-English senses align with
                    // English ordinals (they don't — see the long
                    // discussion when this path was added).
                    Log.d(TAG, "  -> Native target-driven (${senses.first().source}, ${senses.size} target senses, sourceLang=$sourceLang, targetLang=$targetLang)")
                    return DefinitionResult.Native(response, senses, senses.first().source)
                }
            }
            Log.d(TAG, "  Tier 1: no match in target DB")
        } else {
            Log.d(TAG, "  Tier 1: skipped (targetGlossDb=${targetGlossDb != null}, targetLang=$targetLang)")
        }

        // Tier 2: ML Kit single-word headword translation
        if (mlKitTranslator != null && targetLang != "en") {
            val headword = entry?.headwords?.firstOrNull()?.written
                ?: entry?.slug ?: word
            try {
                val translated = mlKitTranslator.translate(headword)
                Log.d(TAG, "  Tier 2: translate($headword) -> $translated")
                if (translated.isNotBlank() && !translated.equals(headword, ignoreCase = true)) {
                    val translatedDefs = translateDefinitions(response)
                    Log.d(TAG, "  -> MachineTranslated (translatedDefs=${translatedDefs?.size})")
                    return DefinitionResult.MachineTranslated(response, translated, translatedDefs)
                }
                Log.d(TAG, "  Tier 2: identity translation, falling through")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "  Tier 2: ML Kit failed: ${e.message}")
            }
        } else {
            Log.d(TAG, "  Tier 2: skipped (mlKit=${mlKitTranslator != null}, targetLang=$targetLang)")
        }

        // Tier 3: English fallback (with translated definitions when possible)
        val translatedDefs = translateDefinitions(response)
        Log.d(TAG, "  -> EnglishFallback (translatedDefs=${translatedDefs?.size})")
        return DefinitionResult.EnglishFallback(response, translatedDefs)
    }

    /**
     * Translates each example's source text into the target language,
     * parallel to the flat sense list `response.entries.flatMap { it.senses }`
     * — same flattening WordDetailBottomSheet uses to render senses across
     * the (often multiple) entries Wiktionary-derived packs return for one
     * surface. Deliberately SEPARATE from [lookup] because word-panel /
     * drag-to-lookup flows resolve dozens of tokens per sentence and
     * never surface examples — callers that need translated examples
     * (the word-detail sheet) call this explicitly after [lookup] resolves.
     *
     * Returns null when translation would be a no-op (targetLang == "en"
     * — the UI falls back to `Example.translation` which is already
     * English) or when no source→target translator is available.
     *
     * Fires all ML Kit calls in parallel via `async` because the
     * translator is thread-safe for concurrent requests.
     */
    suspend fun translateExamples(response: DictionaryResponse): List<List<String>>? {
        if (targetLang == "en") return null
        val translator = mlKitTranslator ?: return null
        val flatSenses = response.entries.flatMap { it.senses }
        if (flatSenses.isEmpty()) return null
        if (flatSenses.none { it.examples.isNotEmpty() }) return null

        return coroutineScope {
            flatSenses.map { sense ->
                sense.examples.map { ex ->
                    async {
                        if (ex.text.isBlank()) return@async ""
                        try {
                            translator.translate(ex.text)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Example translation failed: ${e.message}")
                            ex.translation  // stored English — wrong language but better than nothing
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Translates each sense's English definitions to the target language,
     * parallel to `response.entries.flatMap { it.senses }` — same flat
     * ordering WordDetailBottomSheet uses to render senses across the
     * (often multiple) entries Wiktionary-derived packs return for one
     * surface. Returns null if no EN→target translator is available.
     */
    private suspend fun translateDefinitions(response: DictionaryResponse): List<String>? {
        if (enToTargetTranslator == null || targetLang == "en") return null
        val flatSenses = response.entries.flatMap { it.senses }
        if (flatSenses.isEmpty()) return null
        return flatSenses.map { sense ->
            val english = sense.targetDefinitions.joinToString("; ")
            if (english.isBlank()) ""
            else try {
                enToTargetTranslator.translate(english)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Definition translation failed", e)
                english
            }
        }
    }

    companion object {
        private const val TAG = "DefinitionResolver"
    }
}
