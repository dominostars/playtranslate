package com.playtranslate.ui

import android.content.Context
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.SourceLanguageEngine
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.headwordDisplay
import com.playtranslate.model.orderedReadingRows
import com.playtranslate.model.selectHeadword
import com.playtranslate.translation.ChineseScriptConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Outcome of [resolveWordRows]: the rendered word [rows] plus the supporting
 * span/reading data the translation-result surface needs for furigana and
 * word-tap positioning.
 *
 *  - [tokenSpans]: the per-occurrence tokens the rows were built from (carries
 *    the tokenizer's surface info; the dictionary-search screen passes its
 *    candidate list here and simply doesn't use it for span mapping).
 *  - [lookupToReading]: maps both lookupForm and surface form to the resolved
 *    reading, so conjugated surfaces still get furigana.
 *  - [surfaces]: displayWord → surface, when the two differ.
 */
data class LookupData(
    val rows: List<RowState>,
    val tokenSpans: List<TokenSpan>,
    val lookupToReading: Map<String, String>,
    val surfaces: Map<String, String>,
)

/**
 * Immutable snapshot of the source/target settings one lookup runs against,
 * captured by the caller BEFORE tokenizing. Threading it through
 * [resolveWordRows] keeps tokenization and resolution pinned to the same
 * [engine] and target language even if the user changes preferences while the
 * lookup is in flight — otherwise an in-flight resolve could re-read globals
 * and resolve one language's tokens through another language's engine, or
 * render them against a different target. The caller MUST tokenize with this
 * same [engine].
 */
data class WordLookupContext(
    val engine: SourceLanguageEngine,
    val targetLang: String,
    val targetChineseVariant: ChineseScriptVariant,
)

/** [phraseAwareRowTokens]'s result: the annotation's plain tokenize
 *  projection ([wordTokens] — tap spans and other word-only consumers),
 *  the row-pipeline input with phrase rows interleaved ([rowTokens]), and
 *  the detected [phrases] themselves (tap-span member anchoring —
 *  [SourceWordLookup.computeTapSpans]). */
data class PhraseAwareTokens(
    val wordTokens: List<TokenSpan>,
    val rowTokens: List<TokenSpan>,
    val phrases: List<com.playtranslate.language.PhraseOccurrence>,
)

/**
 * THE single producer of phrase-aware row-token lists: [annotation]'s word
 * tokens with every detected multi-word expression
 * ([SourceLanguageEngine.phrasesIn]) inserted as a row token ahead of its
 * first member word. Both words-payload producers — the result screen's
 * lookup pipeline (TranslationResultViewModel) and the sentence cache's
 * ([LastSentenceCache.lookupWords], which feeds the capture overlay's Anki
 * payloads) — MUST build their rows from [PhraseAwareTokens.rowTokens], so
 * the words panel and every Anki words table agree on phrase policy by
 * construction. [PhraseAwareTokens.wordTokens] stays phrase-free: tap
 * spans consume tokens sequentially against the displayed text, and a
 * phrase span would swallow its members' ranges.
 */
suspend fun phraseAwareRowTokens(
    engine: SourceLanguageEngine,
    text: String,
    annotation: com.playtranslate.language.SentenceAnnotation,
): PhraseAwareTokens {
    val wordSpans = annotation.spans.filter { it.lookupForm != null }
    val wordTokens = wordSpans.map {
        TokenSpan(it.surface, it.lookupForm!!, it.lookupHint, it.inflections)
    }
    val phrases = withContext(Dispatchers.IO) { engine.phrasesIn(text) }
    if (phrases.isEmpty()) return PhraseAwareTokens(wordTokens, wordTokens, phrases)
    val rowTokens = buildList {
        var p = 0
        wordSpans.forEachIndexed { i, s ->
            val wordStart = if (s.start >= 0) s.start else Int.MAX_VALUE
            while (p < phrases.size && phrases[p].range.first <= wordStart) {
                add(TokenSpan(phrases[p].surface, phrases[p].lookupForm))
                p++
            }
            add(wordTokens[i])
        }
        while (p < phrases.size) {
            add(TokenSpan(phrases[p].surface, phrases[p].lookupForm))
            p++
        }
    }
    return PhraseAwareTokens(wordTokens, rowTokens, phrases)
}

/**
 * Resolve a list of already-tokenized [tokens] into renderable word [rows],
 * against the caller-owned [context] snapshot.
 *
 * This is the shared token → [RowState] pipeline used by both the translation
 * result surface ([TranslationResultViewModel], which feeds it
 * `context.engine.tokenize(text)`) and the standalone dictionary-search screen
 * ([DictionaryLookupViewModel], which feeds it either segmented tokens or
 * prefix-completion candidates). Row order follows the dedup order of
 * [tokens], so callers control ranking by ordering the tokens they pass in.
 *
 * Per-token dictionary lookups fan out in parallel on IO; per-row failures
 * drop to null and are filtered out. Honors coroutine cancellation so a newer
 * query can cancel an in-flight resolve.
 */
suspend fun resolveWordRows(
    appCtx: Context,
    context: WordLookupContext,
    tokens: List<TokenSpan>,
): LookupData {
    val engine = context.engine
    val targetLang = context.targetLang
    val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, targetLang)
    val resolver = DefinitionResolver(
        engine, targetGlossDb,
        OfflineFallbackTranslators.forPair(engine.profile.translationCode, targetLang), targetLang,
        OfflineFallbackTranslators.forTarget(targetLang),
        ChineseScriptConverter.forTarget(targetLang, context.targetChineseVariant),
    )

    val allTokens = tokens

    val seen = mutableSetOf<String>()
    val uniqueTokens = allTokens.filter { seen.add(it.lookupForm) }
    val resolveTokens = uniqueTokens.map { it.lookupForm }

    if (resolveTokens.isEmpty()) {
        return LookupData(
            rows = emptyList(),
            tokenSpans = allTokens,
            lookupToReading = emptyMap(),
            surfaces = emptyMap(),
        )
    }

    val surfaceByToken = uniqueTokens.associate { it.lookupForm to it.surface }
    val readingByToken = uniqueTokens.associate { it.lookupForm to it.reading }
    // All distinct inflected forms per lemma — keyed off ALL occurrences, not
    // the deduped first one, so a lemma seen in several forms keeps them all.
    val inflectionForms = inflectedFormsByLemma(allTokens)

    // Fan out per-token lookups in parallel on IO. Per-row failures produce
    // nulls that we filter out below.
    data class Row(
        val rowState: RowState,
        val surfaceMapping: Pair<String, String>?,  // displayWord → surface, when they differ
    )

    val results: List<Row?> = withContext(Dispatchers.IO) {
        coroutineScope {
            resolveTokens.map { word ->
                async {
                    try {
                        val defResult = resolver.lookup(word, readingByToken[word])
                        val response = defResult?.response
                        if (response == null || response.entries.isEmpty()) return@async null
                        val entry = response.entries.first()
                        val flatSenses = response.entries.flatMap { it.senses }
                        val primary = entry.selectHeadword(
                            surfaceByToken[word], word, readingByToken[word],
                        )
                        // headwordDisplay swaps the kanji for the kana on JMdict
                        // uk-tagged entries (e.g. なぜ over 何故), suppressing the
                        // reading column since it would just duplicate the
                        // headword — UNLESS the source text actually showed the
                        // kanji (surfaceByToken[word] matches a written form), in
                        // which case we honor what the user saw.
                        val display = entry.headwordDisplay(
                            primary,
                            surfaceByToken[word],
                        )
                        val displayWord = display.written
                        val reading = display.reading ?: ""
                        val freqScore = entry.freqScore

                        // Imported term-dictionary lines lead the flat
                        // definition string (one per line, source in parens),
                        // numbered continuously with the pack's lines below.
                        // Built BEFORE the empty-check so an imported-only
                        // word (pack-miss synthesis) still gets a row.
                        val importedLines = importedFlatLines(entry.importedSenses)
                        var headerLine: String? = null
                        val packLines: List<String> = when (defResult) {
                            is DefinitionResult.Native -> {
                                val targetSensesSorted = defResult.targetSenses.sortedBy { it.senseOrd }
                                val isTargetDriven = targetLang != "en" && targetSensesSorted.isNotEmpty()
                                if (isTargetDriven) {
                                    targetSensesSorted.map { it.glosses.joinToString("; ") }
                                } else {
                                    val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                                    flatSenses.mapIndexed { i, sense ->
                                        targetByOrd[i]?.glosses?.joinToString("; ")
                                            ?: sense.targetDefinitions.joinToString("; ")
                                    }
                                }
                            }
                            is DefinitionResult.MachineTranslated -> {
                                val defs = defResult.translatedDefinitions
                                if (defs == null) headerLine = defResult.translatedHeadword
                                flatSenses.mapIndexed { i, sense ->
                                    defs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                        ?: sense.targetDefinitions.joinToString("; ")
                                }
                            }
                            is DefinitionResult.EnglishFallback -> {
                                val defs = defResult.translatedDefinitions
                                flatSenses.mapIndexed { i, sense ->
                                    defs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                        ?: sense.targetDefinitions.joinToString("; ")
                                }
                            }
                        }
                        val rawLines = importedLines + packLines.filter { it.isNotEmpty() }
                        val numbered =
                            if (rawLines.size > 1) rawLines.mapIndexed { i, l -> "${i + 1}. $l" }
                            else rawLines
                        val meaning =
                            (listOfNotNull(headerLine) + numbered).joinToString("\n")
                        if (meaning.isEmpty()) return@async null
                        // Structured senses for the cell's numbered, POS-grouped
                        // definitions, built once via the shared tier logic the
                        // lens popup also uses.
                        val senses = buildSenseDisplays(defResult, response.entries, targetLang)
                        val ankiPos = entry.senses.firstOrNull()?.partsOfSpeech
                            ?.filter { it.isNotBlank() }?.joinToString(" · ") ?: ""
                        val surface = surfaceByToken[word] ?: word
                        Row(
                            rowState = RowState(
                                displayWord = displayWord,
                                reading = reading,
                                meaning = meaning,
                                senses = senses,
                                freqScore = freqScore,
                                isCommon = entry.isCommon == true,
                                surface = surface,
                                ankiPos = ankiPos,
                                pitch = display.pitch,
                                frequencies = display.frequencies,
                                inflectedForms = inflectionForms[word].orEmpty(),
                                // Same ordering the word detail page uses; bold the
                                // occurrence (the selected headword's reading).
                                readingRows = entry.orderedReadingRows(primary?.reading),
                            ),
                            surfaceMapping = if (surface != displayWord) {
                                displayWord to surface
                            } else null,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
    }

    val resolvedRows = results.filterNotNull().map { it.rowState }
    val surfaces = results.filterNotNull()
        .mapNotNull { it.surfaceMapping }
        .toMap()

    val lookupToReading = mutableMapOf<String, String>()
    results.forEachIndexed { idx, row ->
        if (row != null && row.rowState.reading.isNotEmpty()) {
            lookupToReading[resolveTokens[idx]] = row.rowState.reading
            val surface = surfaceByToken[resolveTokens[idx]]
            if (surface != null && surface != resolveTokens[idx]) {
                lookupToReading[surface] = row.rowState.reading
            }
        }
    }

    return LookupData(
        rows = resolvedRows,
        tokenSpans = allTokens,
        lookupToReading = lookupToReading,
        surfaces = surfaces,
    )
}

/**
 * Group every source occurrence by lemma and collect the DISTINCT inflected
 * forms each appeared as (surface + tags), in first-seen order, dropping
 * uninflected occurrences. Keyed off ALL tokens — not the lemma-deduped row set
 * — so a verb that shows up as 食べたい and 食べられない keeps both forms instead of
 * collapsing to whichever came first. Pure; unit-tested in WordRowResolverTest.
 */
internal fun inflectedFormsByLemma(tokens: List<TokenSpan>): Map<String, List<InflectedForm>> =
    tokens.groupBy { it.lookupForm }
        .mapValues { (_, occ) ->
            occ.filter { it.inflections.isNotEmpty() }
                .map { InflectedForm(it.surface, it.inflections) }
                .distinct()
        }

/** Max distinct inflected-form lines a single word row shows before overflow. */
const val MAX_INFLECTION_LINES = 3

/**
 * Display cap for a row's inflected forms: returns the forms to show (at most
 * [max], first-seen order) and how many are hidden beyond that. Keeps a common
 * lemma in long OCR/clipboard input from expanding one row without bound — the
 * caller renders the remainder as a compact "+N more" line. Pure; tested.
 */
internal fun capInflectionForms(
    forms: List<InflectedForm>,
    max: Int = MAX_INFLECTION_LINES,
): Pair<List<InflectedForm>, Int> {
    val shown = forms.take(max)
    return shown to (forms.size - shown.size)
}
