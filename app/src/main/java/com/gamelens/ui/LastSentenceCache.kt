package com.gamelens.ui

import android.content.Context
import com.gamelens.dictionary.DictionaryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-memory cache for the most recent sentence translation result.
 * Written by TranslationResultFragment or DragLookupController when
 * word lookups complete; read by WordAnkiReviewActivity.
 *
 * Safe because the Activity and AccessibilityService share one process.
 */
object LastSentenceCache {
    var original: String? = null
    var translation: String? = null
    var wordResults: Map<String, Triple<String, String, Int>>? = null
    /** Maps display-word → surface form as it appears in the sentence (e.g. 分かる → 分からない). */
    var surfaceForms: Map<String, String>? = null

    fun clear() {
        original = null
        translation = null
        wordResults = null
        surfaceForms = null
    }

    /**
     * Tokenizes [sentence] and looks up each token in the dictionary.
     * Returns an ordered map of display-word → (reading, meaning, freqScore).
     * Also populates [surfaceForms] with the conjugated forms from the sentence.
     */
    suspend fun lookupWords(
        context: Context,
        sentence: String
    ): Map<String, Triple<String, String, Int>> = withContext(Dispatchers.IO) {
        val dict = DictionaryManager.get(context.applicationContext)
        val pairs = dict.tokenizeWithSurfaces(sentence)
        val results = linkedMapOf<String, Triple<String, String, Int>>()
        val surfaces = linkedMapOf<String, String>()
        for ((surface, lookupForm) in pairs) {
            try {
                val response = dict.lookup(lookupForm)
                if (response != null && response.data.isNotEmpty()) {
                    val entry = response.data.first()
                    val primary = entry.japanese.firstOrNull()
                    val displayWord = primary?.word ?: primary?.reading ?: lookupForm
                    val reading = primary?.reading?.takeIf { it != primary.word } ?: ""
                    val meaning = entry.senses.mapIndexed { i, sense ->
                        val glosses = sense.englishDefinitions.joinToString("; ")
                        if (entry.senses.size > 1) "${i + 1}. $glosses" else glosses
                    }.joinToString("\n")
                    if (meaning.isNotEmpty()) {
                        results[displayWord] = Triple(reading, meaning, entry.freqScore)
                        if (surface != displayWord) {
                            surfaces[displayWord] = surface
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        surfaceForms = surfaces
        results
    }
}
