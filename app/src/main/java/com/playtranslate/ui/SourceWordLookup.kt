package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.PhraseOccurrence
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
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
        /** The tapped token's own text (飲んでいなかった for the lemma 飲む),
         *  carried to the word-detail page so its header can draw the same
         *  conjugation line; null for phrase and member resolutions, which
         *  are lemmas. */
        val surface: String?,
        val label: String?,
        val data: WordDefinitionData,
        /** The dictionary entry, when matched — drives the in-app Anki/open path.
         *  Null for a no-match (the lens shows the shared empty-state). */
        val entry: DictionaryEntry?,
        /** Every entry the lookup returned ([entry] first). [data]'s sense
         *  rows flatten across them (POS-split packs), so an Anki card built
         *  from this resolution must span them too. */
        val entries: List<DictionaryEntry> = listOfNotNull(entry),
        /** The line the lens draws above this unit's section header
         *  ([LensSection.caption]): "Also matches" on an alternative, null
         *  on every other resolution. */
        val caption: String? = null,
    )

    /** One tappable unit of the displayed source text: its char [range], the
     *  [lookupForm] and [reading] the lens resolves, and the [token] it came
     *  from, whose surface and conjugation tags ride into the lens's
     *  conjugation line and the word-detail page. A single-letter phrase
     *  member ([computeTapSpans]) carries a bare token of its own letter. */
    data class TapSpan(
        val range: IntRange,
        val lookupForm: String,
        val reading: String,
        val token: TokenSpan,
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
    ): List<TapSpan> {
        val spans = mutableListOf<TapSpan>()
        var searchFrom = 0
        for (tok in tokenSpans) {
            val idx = displayedText.indexOf(tok.surface, searchFrom)
            if (idx < 0) continue
            val range = idx until (idx + tok.surface.length)
            val reading = lookupToReading[tok.lookupForm]
                ?: lookupToReading[tok.surface]
                ?: tok.reading
                ?: ""
            spans.add(TapSpan(range, tok.lookupForm, reading, tok))
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
    ): List<TapSpan> {
        val spans = computeSpans(displayedText, tokenSpans, lookupToReading)
        if (phrases.isEmpty()) return spans
        val extra = mutableListOf<TapSpan>()
        var searchFrom = 0
        for (occ in phrases) {
            val range = whitespaceTolerantRange(displayedText, occ.surface, searchFrom) ?: continue
            searchFrom = range.last + 1
            val slice = displayedText.substring(range.first, range.last + 1)
            for (m in SINGLE_LETTER_WORD.findAll(slice)) {
                val at = range.first + m.range.first
                extra += TapSpan(at..at, m.value, "", TokenSpan(m.value, m.value))
            }
        }
        if (extra.isEmpty()) return spans
        return (spans + extra).sortedBy { it.range.first }
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
     *  above the word), or else [members] when the tapped unit is itself a
     *  fused expression (JA — every qualifying member word, in expression
     *  order, rendered below it) and [alternatives], the other dictionary
     *  entries the tapped unit could be
     *  ([com.playtranslate.language.SourceLanguageEngine.alternativesOf]),
     *  rendered below the members. With a [phrase], the other two are
     *  empty. [secondaries] is the one list the lens and its drill-ins
     *  read. */
    data class ResolvedAt(
        val word: Resolved,
        val phrase: Resolved? = null,
        val members: List<Resolved> = emptyList(),
        val alternatives: List<Resolved> = emptyList(),
        /** The secondaries held back from this resolution, resolved by
         *  [resolvePending]; empty when everything is resolved. */
        val pending: List<SecondaryKey> = emptyList(),
    ) {
        /** The lens's secondary sections in their list order (the order the
         *  drill-ins index into): the containing [phrase] alone, else the
         *  [members] followed by the [alternatives]. */
        fun secondaries(): List<Resolved> = phrase?.let { listOf(it) } ?: (members + alternatives)
    }

    /**
     * The alternative sections a lens shows for [alternatives]: one per
     * dictionary entry, never one of the tapped unit's own [primaryIds]. An
     * entry is identified by its pack id ([packIdOf]), so two homographs
     * (弾く read ひく and 弾く read はじく) are two sections although they
     * share a headword; an entry without a pack id (an imported-dictionary
     * synthesis) falls back to [fallbackKeyOf]. The first of each identity
     * stays, in order. Applied by [collectSecondaryKeys], the step the tap
     * path ([resolveAt]) and the drag lens share.
     */
    internal fun <T> distinctAlternatives(
        alternatives: List<T>,
        primaryIds: Set<Long>,
        packIdOf: (T) -> Long?,
        fallbackKeyOf: (T) -> Any,
    ): List<T> =
        alternatives
            .filter { alt -> packIdOf(alt)?.let { it !in primaryIds } ?: true }
            .distinctBy { alt -> packIdOf(alt) ?: fallbackKeyOf(alt) }

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
     * phrases — exp-tagged or glue-bearing — loose: 気になる → 気,
     * 瞬く間に → 瞬く and 間; transparent compounds — 放送番組, 国内向け —
     * need every unit accounted for, a ≥2-char kanji word, an excused
     * katakana word or an excused particle — ペース配分 offers 配分,
     * 図書館 stays whole),
     * and [collectSecondaryKeys], the step the drag lens shares, decides
     * the secondary sections: each lands a real entry, and a phrase or
     * member one that is not the tapped unit's own. Both tap surfaces
     * route through here so behavior can't drift between them.
     * [spanStart] is the tapped span's start offset in [displayedText] —
     * the same text the spans were computed against. [token] is the tapped
     * span's own token: its surface and conjugation tags give the tapped
     * unit its conjugation line, and the engine's alternatives
     * ([com.playtranslate.language.SourceLanguageEngine.alternativesOf])
     * theirs. With no containing phrase, the alternatives follow the
     * members, one per entry as [collectSecondaryKeys] keeps them, each
     * resolved under its own key on the tapped surface and captioned
     * [R.string.lens_also_matches]. Every section resolves before this
     * returns: [ResolvedAt.pending] is empty.
     */
    suspend fun resolveAt(
        appCtx: Context,
        displayedText: String,
        spanStart: Int,
        lookupForm: String,
        reading: String,
        token: TokenSpan,
    ): ResolvedAt {
        val prefs = Prefs(appCtx)
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val phraseKey = withContext(Dispatchers.IO) { engine.longestPhraseAt(displayedText, spanStart) }
        val word = resolve(appCtx, lookupForm, reading, surface = token.surface, tokenTags = token.inflections)
        // Members for any entry-backed fused unit; the engine's policy
        // decides how strictly (phrases — exp-tagged or glue-bearing —
        // loose: 気になる → 気, 瞬く間に → 瞬く and 間; transparent
        // compounds need every unit accounted for: kanji words render,
        // katakana words and particles are excused — 放送番組/国内向け/
        // ペース配分 decompose, 図書館 stays whole). The headword's reading
        // rides along so the members' hints align with it.
        val wordEntry = word.entry
        val memberSpans = if (phraseKey == null && wordEntry != null) {
            withContext(Dispatchers.IO) {
                engine.memberWordsOf(
                    word.word, expressionClass = wordEntry.isExpression, headwordReading = word.reading,
                )
            }
        } else {
            emptyList()
        }
        val primaryIds = word.entries.mapNotNullTo(mutableSetOf()) { it.packId }
        val alternativeSpans = if (phraseKey == null) {
            withContext(Dispatchers.IO) { engine.alternativesOf(token, primaryIds) }
        } else {
            emptyList()
        }
        val keys = collectSecondaryKeys(
            engine, newResolver(appCtx, prefs), word.entries, word.word, phraseKey, memberSpans, alternativeSpans,
        )
        // Every section resolves before the lens shows; nothing is held back.
        return assemble(
            word, keys.all, resolveKeys(appCtx, keys.all), appCtx.getString(R.string.lens_also_matches),
            pending = emptyList(),
        )
    }

    /** [word]'s lens result from its secondary [keys] and their [resolved]
     *  sections, index-parallel: a [SecondaryKind.PHRASE] key's section is
     *  the [ResolvedAt.phrase], the members and the alternatives keep
     *  [keys]' order, and each alternative carries [caption]. [pending]
     *  rides along unresolved. */
    internal fun assemble(
        word: Resolved,
        keys: List<SecondaryKey>,
        resolved: List<Resolved>,
        caption: String,
        pending: List<SecondaryKey>,
    ): ResolvedAt {
        val byKind = keys.zip(resolved).groupBy({ it.first.kind }, { it.second })
        return ResolvedAt(
            word = word,
            phrase = byKind[SecondaryKind.PHRASE]?.firstOrNull(),
            members = byKind[SecondaryKind.MEMBER].orEmpty(),
            alternatives = byKind[SecondaryKind.ALTERNATIVE].orEmpty().map { it.copy(caption = caption) },
            pending = pending,
        )
    }

    /** Resolves each of [keys] on the response it carries, under its own
     *  lookup form and reading, index-parallel to [keys]. An alternative
     *  keeps its span's surface and chain for its conjugation line; a
     *  phrase or member is a lemma and gets neither. */
    suspend fun resolveKeys(appCtx: Context, keys: List<SecondaryKey>): List<Resolved> =
        keys.map { key ->
            val alternative = key.kind == SecondaryKind.ALTERNATIVE
            resolve(
                appCtx, key.span.lookupForm, key.span.reading.orEmpty(),
                surface = key.span.surface.takeIf { alternative },
                tokenTags = key.span.inflections.takeIf { alternative }.orEmpty(),
                response = key.response,
            )
        }

    /** [at] with its [ResolvedAt.pending] secondaries resolved and placed
     *  after the sections it already has: the phrase from either, the
     *  members and the alternatives each existing first. Nothing is left
     *  pending. */
    suspend fun resolvePending(appCtx: Context, at: ResolvedAt): ResolvedAt {
        val loaded = assemble(
            at.word, at.pending, resolveKeys(appCtx, at.pending), appCtx.getString(R.string.lens_also_matches),
            pending = emptyList(),
        )
        return ResolvedAt(
            word = at.word,
            phrase = at.phrase ?: loaded.phrase,
            members = at.members + loaded.members,
            alternatives = at.alternatives + loaded.alternatives,
        )
    }

    /** Resolve [lookupForm] (+ optional disambiguating [reading]) into lens data,
     *  using the same resolver + tier branching as the in-app results page.
     *  [surface] is the tapped occurrence's text and [tokenTags] its chain
     *  from [lookupForm] (the tokenizer's for the tapped unit, the engine's
     *  for an alternative); with a surface the data carries the conjugation
     *  line [InflectionChain.compose] builds from the lookup's own
     *  deinflection chain and [tokenTags]. Phrase and member resolutions
     *  pass no surface and get no line. [response], when given, is the
     *  dictionary response a lookup of [lookupForm] and [reading] already
     *  returned ([collectSecondaryKeys]): the tier chain runs on it instead
     *  of looking the word up again. */
    suspend fun resolve(
        appCtx: Context,
        lookupForm: String,
        reading: String,
        surface: String? = null,
        tokenTags: List<InflectionTag> = emptyList(),
        response: DictionaryResponse? = null,
    ): Resolved {
        val prefs = Prefs(appCtx)
        val resolver = newResolver(appCtx, prefs)
        val defResult = withContext(Dispatchers.IO) {
            if (response != null) {
                resolver.resolve(response, lookupForm, reading.ifEmpty { null })
            } else {
                resolver.lookup(lookupForm, reading.ifEmpty { null })
            }
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
        val inflectedForms = surface?.let {
            listOfNotNull(
                InflectionChain.compose(it, defResult?.response?.deinflection.orEmpty(), tokenTags),
            )
        }.orEmpty()

        return Resolved(
            word = word,
            reading = popupReading,
            surface = surface,
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
                inflectedForms = inflectedForms,
            ),
            entry = entry,
        )
    }

    /** The resolver every resolution here runs: the source engine, target
     *  gloss pack, offline translators and Chinese script converter [prefs]
     *  select. */
    private fun newResolver(appCtx: Context, prefs: Prefs): DefinitionResolver {
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        return DefinitionResolver(
            engine, TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang),
            OfflineFallbackTranslators.forPair(engine.profile.translationCode, prefs.targetLang), prefs.targetLang,
            OfflineFallbackTranslators.forTarget(prefs.targetLang),
            ChineseScriptConverter.forTarget(prefs.targetLang, prefs.targetChineseVariant),
        )
    }

    private const val MACHINE_TRANSLATED_LABEL = "⚠ Machine translated"
}
