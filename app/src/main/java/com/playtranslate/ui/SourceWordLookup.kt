package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.selectHeadword
import com.playtranslate.translation.ChineseScriptConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared word-tap resolution for the source text: compute char-range spans from
 * the tokenizer, and resolve a tapped lemma into the lens popup data
 * (definition / reading / pitch / frequency / sense tiers). Used by BOTH the
 * in-app results page ([TranslationResultFragment]) and the over-game capture
 * panel ([CaptureResultOverlay]), so the displayed lookup can't drift. Each
 * surface builds its own [MagnifierLens] around this — the in-app lens adds the
 * Anki chip + open-detail tap; the panel is display + speak only.
 */
object SourceWordLookup {

    /** A resolved word, ready to feed a [MagnifierLens] via [WordDefinitionData]. */
    data class Resolved(
        val word: String,
        /** Reading as resolved by headwordDisplay (raw; for the speak chip / Anki).
         *  The lens data already drops it when equal to [word]. */
        val reading: String?,
        val label: String?,
        val data: WordDefinitionData,
        /** The dictionary entry, when matched — drives the in-app Anki/open path.
         *  Null for a no-match (the lens shows the shared empty-state). */
        val entry: DictionaryEntry?,
    )

    /**
     * Char-range → (lookupForm, reading) spans over [displayedText], derived from
     * the tokenizer's per-occurrence [tokenSpans] plus the resolved
     * [lookupToReading]. The JMdict-resolved reading wins, then the surface-keyed
     * reading, then the tokenizer's own reading as a last fallback so
     * out-of-dictionary tokens still carry a reading into the tap popup.
     */
    fun computeSpans(
        displayedText: String,
        tokenSpans: List<TokenSpan>,
        lookupToReading: Map<String, String>,
    ): List<Triple<IntRange, String, String>> {
        val spans = mutableListOf<Triple<IntRange, String, String>>()
        var searchFrom = 0
        for (tok in tokenSpans) {
            val idx = displayedText.indexOf(tok.surface, searchFrom)
            // Fused phrase spans contain whitespace, and the displayed text
            // may have OCR line breaks the tokenized text lacked — retry the
            // match with each whitespace run wild ("a great deal" still maps
            // when the display wrapped it as "a great\ndeal"). Single words
            // keep the exact-match-only behavior.
            val range = if (idx >= 0) {
                idx until (idx + tok.surface.length)
            } else if (tok.surface.any(Char::isWhitespace)) {
                whitespaceTolerantRange(displayedText, tok.surface, searchFrom) ?: continue
            } else {
                continue
            }
            val reading = lookupToReading[tok.lookupForm]
                ?: lookupToReading[tok.surface]
                ?: tok.reading
                ?: ""
            spans.add(Triple(range, tok.lookupForm, reading))
            searchFrom = range.last + 1
        }
        return spans
    }

    /** The [displayedText] range matching [surface] with every whitespace run
     *  treated as "any whitespace run", searched from [from]. Null on miss. */
    private fun whitespaceTolerantRange(displayedText: String, surface: String, from: Int): IntRange? {
        val pattern = surface.split(WHITESPACE_RUN)
            .filter { it.isNotEmpty() }
            .joinToString("\\s+") { Regex.escape(it) }
        if (pattern.isEmpty()) return null
        val match = Regex(pattern).find(displayedText, from) ?: return null
        return match.range
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /** Resolve [lookupForm] (+ optional disambiguating [reading]) into lens data,
     *  using the same resolver + tier branching as the in-app results page.
     *  Multi-word expressions arrive here pre-fused: [computeSpans] spans come
     *  from the engine's tokenize, whose phrase re-glob makes a whole known
     *  expression ("a great deal") one span — so its lookupForm and tap range
     *  already cover the phrase. */
    suspend fun resolve(appCtx: Context, lookupForm: String, reading: String): Resolved {
        val prefs = Prefs(appCtx)
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang)
        val resolver = DefinitionResolver(
            engine, targetGlossDb,
            OfflineFallbackTranslators.forPair(engine.profile.translationCode, prefs.targetLang), prefs.targetLang,
            OfflineFallbackTranslators.forTarget(prefs.targetLang),
            ChineseScriptConverter.forTarget(prefs.targetLang, prefs.targetChineseVariant),
        )
        val defResult = withContext(Dispatchers.IO) {
            resolver.lookup(lookupForm, reading.ifEmpty { null })
        }
        val entries = defResult?.response?.entries.orEmpty()
        val entry = entries.firstOrNull()

        // Reading shown beneath the headword comes from headwordDisplay (which
        // suppresses it for JMdict uk entries) rather than the span's tokenizer
        // reading, so kana-only rows don't render reading=ナゼ under word=なぜ.
        val word: String
        val popupReading: String?
        val popupLabel: String?
        val freqScore: Int
        val isCommon: Boolean
        val popupPitch: List<Int>
        val popupFrequencies: List<FrequencyTag>
        when {
            entry != null && defResult is DefinitionResult.MachineTranslated -> {
                val display = entry.headwordDisplay(
                    entry.selectHeadword(lookupForm, lookupForm, reading), lookupForm,
                )
                word = display.written
                popupReading = display.reading
                popupLabel = MACHINE_TRANSLATED_LABEL
                freqScore = entry.freqScore
                isCommon = entry.isCommon == true
                popupPitch = display.pitch
                popupFrequencies = display.frequencies
            }
            entry != null && defResult is DefinitionResult.EnglishFallback && defResult.translatedDefinitions != null -> {
                val display = entry.headwordDisplay(
                    entry.selectHeadword(lookupForm, lookupForm, reading), lookupForm,
                )
                word = display.written
                popupReading = display.reading
                popupLabel = MACHINE_TRANSLATED_LABEL
                freqScore = entry.freqScore
                isCommon = entry.isCommon == true
                popupPitch = display.pitch
                popupFrequencies = display.frequencies
            }
            entry != null -> {
                val display = entry.headwordDisplay(
                    entry.selectHeadword(lookupForm, lookupForm, reading), lookupForm,
                )
                word = display.written
                popupReading = display.reading
                popupLabel = null
                freqScore = entry.freqScore
                isCommon = entry.isCommon == true
                popupPitch = display.pitch
                popupFrequencies = display.frequencies
            }
            else -> {
                // No dictionary entry — keep the popup up with an empty sense list
                // so the shared "No definitions found." placeholder renders.
                word = lookupForm
                popupReading = reading.ifEmpty { null }
                popupLabel = null
                freqScore = 0
                isCommon = false
                popupPitch = emptyList()
                popupFrequencies = emptyList()
            }
        }
        val senses: List<SenseDisplay> = if (entry != null) {
            buildSenseDisplays(defResult!!, entries, prefs.targetLang)
        } else {
            emptyList()
        }
        // Styled-path payload rides the same suspend resolution, so the lens
        // bind stays synchronous. Null = flat tier (the common case).
        val importedGroups = entry?.importedSenses.orEmpty()
        val styled = fetchYomitanStyledData(
            appCtx, prefs.sourceLangId.yomitanConsumingLang(), importedGroups,
        )

        return Resolved(
            word = word,
            reading = popupReading,
            label = popupLabel,
            data = WordDefinitionData(
                word = word,
                reading = popupReading?.takeIf { it != word },
                senses = senses,
                freqScore = freqScore,
                isCommon = isCommon,
                pitch = popupPitch,
                frequencies = popupFrequencies,
                importedGroups = importedGroups,
                styled = styled,
            ),
            entry = entry,
        )
    }

    private const val MACHINE_TRANSLATED_LABEL = "⚠ Machine translated"
}
