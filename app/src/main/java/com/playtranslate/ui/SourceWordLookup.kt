package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.PhraseOccurrence
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.isExpressionEntry
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
 * surface builds its own [MagnifierLens] around this and wires its own
 * open-detail / Anki / speak actions.
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
        /** Every entry the lookup returned ([entry] first). [data]'s sense
         *  rows flatten across them (POS-split packs), so an Anki card built
         *  from this resolution must span them too. */
        val entries: List<DictionaryEntry> = listOfNotNull(entry),
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
            if (idx < 0) continue
            val range = idx until (idx + tok.surface.length)
            val reading = lookupToReading[tok.lookupForm]
                ?: lookupToReading[tok.surface]
                ?: tok.reading
                ?: ""
            spans.add(Triple(range, tok.lookupForm, reading))
            searchFrom = idx + tok.surface.length
        }
        return spans
    }

    /**
     * Tap spans for [displayedText]: [computeSpans] over the tokenizer's
     * words PLUS a span for each single-letter word inside a detected
     * phrase occurrence. tokenize drops sub-2-char words as words-panel
     * policy, but a member of a known expression must stay tappable — "a"
     * in "a great deal" anchors the containment probe ([resolveAt]) like
     * any other member, and without a span the tap silently does nothing
     * while every longer member shows the phrase.
     *
     * Member positions derive from the PHRASE's own displayed range — a
     * whitespace-tolerant, in-order match of the occurrence surface (so
     * display-only OCR newlines inside the phrase don't hide it) — never
     * from re-searching the letter itself: the token list doesn't tile
     * single-letter occurrences, so a bare indexOf("a") binds to any
     * earlier stray duplicate ("cat a a great deal") and leaves the real
     * member dead. A phrase the tolerant match can't locate contributes no
     * member spans (degrades to longer-members-only, never misbinds).
     * Result is sorted by range start. Member spans exist ONLY for the tap
     * pipeline — they never join the words-panel rows.
     */
    fun computeTapSpans(
        displayedText: String,
        tokenSpans: List<TokenSpan>,
        lookupToReading: Map<String, String>,
        phrases: List<PhraseOccurrence>,
    ): List<Triple<IntRange, String, String>> {
        val spans = computeSpans(displayedText, tokenSpans, lookupToReading)
        if (phrases.isEmpty()) return spans
        val extra = mutableListOf<Triple<IntRange, String, String>>()
        var searchFrom = 0
        for (occ in phrases) {
            val range = whitespaceTolerantRange(displayedText, occ.surface, searchFrom) ?: continue
            searchFrom = range.last + 1
            val slice = displayedText.substring(range.first, range.last + 1)
            for (m in SINGLE_LETTER_WORD.findAll(slice)) {
                val at = range.first + m.range.first
                extra += Triple(at..at, m.value, "")
            }
        }
        if (extra.isEmpty()) return spans
        return (spans + extra).sortedBy { it.first.first }
    }

    /** The [displayedText] range matching [surface] with every whitespace
     *  run treated as "any whitespace run", searched from [from]. Null on
     *  miss. */
    private fun whitespaceTolerantRange(displayedText: String, surface: String, from: Int): IntRange? {
        val pattern = surface.split(WHITESPACE_RUN)
            .filter { it.isNotEmpty() }
            .joinToString("\\s+") { Regex.escape(it) }
        if (pattern.isEmpty()) return null
        val match = Regex(pattern).find(displayedText, from) ?: return null
        return match.range
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val SINGLE_LETTER_WORD = Regex("(?<=^|\\s)\\p{L}(?=\\s|$)")

    /** [resolveAt]'s result: the tapped unit's resolution — the popup's
     *  identity — plus its secondary resolutions for the lens's split
     *  body: [phrase] when the engine designates a multi-word expression
     *  CONTAINING the tapped word (space-delimited languages — renders
     *  above the word), or [members] when the tapped unit is itself a
     *  fused expression (JA — every qualifying member word, in expression
     *  order, rendered below it). At most one of the two is populated. */
    data class ResolvedAt(
        val word: Resolved,
        val phrase: Resolved? = null,
        val members: List<Resolved> = emptyList(),
    )

    /**
     * Phrase-aware [resolve] for word taps: resolves the tapped unit itself,
     * and ADDITIONALLY probes the engine in both directions —
     * [com.playtranslate.language.SourceLanguageEngine.longestPhraseAt] for
     * a multi-word expression containing the tap ("door" in "open the
     * door"), and, when none, [com.playtranslate.language.SourceLanguageEngine.memberWordsOf]
     * for the member words of an engine-fused expression (気になった →
     * 気). ALL qualifying members resolve — tap position inside the fused
     * span deliberately doesn't matter: position-dependent members proved
     * undiscoverable on device, and one unresolvable member (手当たり has
     * no JMdict entry) must not blank the whole feature. Member strictness
     * follows the entry's POS class (see
     * [com.playtranslate.language.SourceLanguageEngine.memberWordsOf]:
     * expressions loose; transparent compounds — 放送番組, 国内向け —
     * need every unit accounted for, a ≥2-char kanji word or an excused
     * katakana word — ペース配分 offers 配分, 図書館 stays whole),
     * and every secondary drops unless its lookup lands a real entry
     * distinct from the tapped unit's headword. Both tap surfaces route
     * through here so behavior can't drift between them.
     * [spanStart] is the tapped span's start offset in [displayedText] —
     * the same text the spans were computed against.
     */
    suspend fun resolveAt(
        appCtx: Context,
        displayedText: String,
        spanStart: Int,
        lookupForm: String,
        reading: String,
    ): ResolvedAt {
        val engine = SourceLanguageEngines.get(appCtx, Prefs(appCtx).sourceLangId)
        val phraseKey = withContext(Dispatchers.IO) { engine.longestPhraseAt(displayedText, spanStart) }
        val word = resolve(appCtx, lookupForm, reading)
        // Members for any entry-backed fused unit; the engine's policy
        // decides how strictly (expressions loose, transparent compounds
        // need every unit accounted for: kanji words render, katakana words
        // are excused — 放送番組/国内向け/ペース配分 decompose, 図書館
        // stays whole).
        val wordEntry = word.entry
        val memberSpans = if (phraseKey == null && wordEntry != null) {
            withContext(Dispatchers.IO) {
                engine.memberWordsOf(word.word, expressionClass = wordEntry.isExpressionEntry())
            }
        } else {
            emptyList()
        }
        return ResolvedAt(
            word = word,
            phrase = phraseKey?.let { resolve(appCtx, it, "") }?.takeIf { it.entry != null },
            members = memberSpans
                .map { resolve(appCtx, it.lookupForm, it.reading.orEmpty()) }
                .filter { it.entry != null && it.word != word.word }
                .distinctBy { it.word },
        )
    }

    /** Resolve [lookupForm] (+ optional disambiguating [reading]) into lens data,
     *  using the same resolver + tier branching as the in-app results page. */
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
            entries = entries,
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
