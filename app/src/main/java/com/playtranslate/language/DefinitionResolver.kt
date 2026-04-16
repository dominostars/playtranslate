package com.playtranslate.language

import android.util.Log
import com.playtranslate.TranslationManager
import com.playtranslate.model.DictionaryResponse

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

    /** ML Kit translated the headword. English definition kept as context hint. */
    data class MachineTranslated(
        override val response: DictionaryResponse,
        val translatedHeadword: String,
    ) : DefinitionResult

    /** No target-language data. English definitions from source pack. */
    data class EnglishFallback(
        override val response: DictionaryResponse,
    ) : DefinitionResult
}

/**
 * Centralizes the word-tap definition fallback chain:
 *
 * 1. **Native** — target-language pack definition (JMdict/Wiktionary/CFDICT)
 * 2. **MachineTranslated** — ML Kit headword translation + English context
 * 3. **EnglishFallback** — English definitions from the source pack
 *
 * All word-tap UI paths use this resolver instead of calling
 * [SourceLanguageEngine.lookup] directly.
 */
class DefinitionResolver(
    private val engine: SourceLanguageEngine,
    private val targetGlossDb: TargetGlossDatabase?,
    private val mlKitTranslator: TranslationManager?,
    private val targetLang: String,
) {
    suspend fun lookup(word: String, reading: String?): DefinitionResult? {
        val response = engine.lookup(word, reading) ?: return null

        // Tier 1: target-pack native definition
        if (targetGlossDb != null && targetLang != "en") {
            val sourceLang = engine.profile.id.code
            // Fan out: first entry's headwords + tapped surface only.
            // Don't cross-match against later entries (archaic variants etc.)
            val headwords = buildSet {
                response.entries.firstOrNull()?.let { entry ->
                    entry.headwords.forEach { hw ->
                        hw.written?.let { add(it) }
                    }
                    add(entry.slug)
                }
                add(word)
            }
            for (hw in headwords) {
                val senses = targetGlossDb.lookup(sourceLang, hw, reading)
                if (senses != null) {
                    return DefinitionResult.Native(response, senses, senses.first().source)
                }
            }
        }

        // Tier 2: ML Kit single-word headword translation
        if (mlKitTranslator != null && targetLang != "en") {
            val headword = response.entries.firstOrNull()?.headwords?.firstOrNull()?.written
                ?: response.entries.firstOrNull()?.slug ?: word
            try {
                val translated = mlKitTranslator.translate(headword)
                if (translated.isNotBlank() && translated.lowercase() != headword.lowercase()) {
                    return DefinitionResult.MachineTranslated(response, translated)
                }
            } catch (e: Exception) {
                Log.d(TAG, "ML Kit headword translation unavailable", e)
            }
        }

        // Tier 3: English fallback
        return DefinitionResult.EnglishFallback(response)
    }

    companion object {
        private const val TAG = "DefinitionResolver"
    }
}
