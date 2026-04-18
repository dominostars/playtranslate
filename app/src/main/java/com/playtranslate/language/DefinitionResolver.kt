package com.playtranslate.language

import android.util.Log
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse

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

    /** Target-language definition from the downloaded pack. */
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

        // Tier 1: target-pack native definition
        if (targetGlossDb != null && targetLang != "en") {
            val sourceLang = engine.profile.id.packId.code
            val headwords = buildSet {
                response.entries.firstOrNull()?.let { entry ->
                    entry.headwords.forEach { hw ->
                        hw.written?.let { add(it) }
                    }
                    add(entry.slug)
                }
                add(word)
            }
            Log.d(TAG, "  Tier 1: sourceLang=$sourceLang, headwords=$headwords, reading=$reading")
            for (hw in headwords) {
                val senses = targetGlossDb.lookup(sourceLang, hw, reading)
                Log.d(TAG, "  Tier 1: lookup($sourceLang, $hw, $reading) -> ${senses?.size ?: "null"}")
                if (senses != null) {
                    Log.d(TAG, "  -> Native (${senses.first().source}, ${senses.size} senses)")
                    return DefinitionResult.Native(response, senses, senses.first().source)
                }
            }
            Log.d(TAG, "  Tier 1: no match in target DB")
        } else {
            Log.d(TAG, "  Tier 1: skipped (targetGlossDb=${targetGlossDb != null}, targetLang=$targetLang)")
        }

        val entry = response.entries.firstOrNull()

        // Tier 2: ML Kit single-word headword translation
        if (mlKitTranslator != null && targetLang != "en") {
            val headword = entry?.headwords?.firstOrNull()?.written
                ?: entry?.slug ?: word
            try {
                val translated = mlKitTranslator.translate(headword)
                Log.d(TAG, "  Tier 2: translate($headword) -> $translated")
                if (translated.isNotBlank() && translated.lowercase() != headword.lowercase()) {
                    val translatedDefs = entry?.let { translateDefinitions(it) }
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
        val translatedDefs = entry?.let { translateDefinitions(it) }
        Log.d(TAG, "  -> EnglishFallback (translatedDefs=${translatedDefs?.size})")
        return DefinitionResult.EnglishFallback(response, translatedDefs)
    }

    /**
     * Translates each sense's English definitions to the target language.
     * Returns null if no EN→target translator is available.
     */
    private suspend fun translateDefinitions(entry: DictionaryEntry): List<String>? {
        if (enToTargetTranslator == null || targetLang == "en") return null
        return entry.senses.map { sense ->
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
