package com.playtranslate.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.Prefs
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TokenSpan
import com.playtranslate.language.TranslationManagerProvider
import com.playtranslate.language.WordTranslator
import com.playtranslate.model.TranslationResult
import com.playtranslate.model.headwordFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State container for the translation-result surface, scoped per
 * activity. Owns the word-lookup pipeline coroutine on
 * [viewModelScope] so rotation mid-lookup preserves progress, and
 * publishes settled state via [wordLookups] for the fragment's
 * renderer plus [TranslationResultActivity]'s [SentenceContextProvider]
 * fallback path.
 *
 * The fragment retains its display logic (view bindings, popups,
 * furigana spans, status/edit/copy plumbing) and its render of
 * [result] / [wordLookups]. Subsequent passes can move more of the
 * fragment's plumbing into events + state observation; this pass
 * targets the lookup coroutine because that's where rotation-loss
 * and view/data tangle were worst.
 */
class TranslationResultViewModel : ViewModel() {

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result.asStateFlow()

    private val _wordLookups = MutableStateFlow<WordLookupsState>(WordLookupsState.Idle)
    val wordLookups: StateFlow<WordLookupsState> = _wordLookups.asStateFlow()

    private var lookupJob: Job? = null

    /** Fragment mirrors its `displayResult` into the VM. */
    fun recordResult(result: TranslationResult) {
        _result.value = ResultState.Ready(result)
    }

    /** Reset to initial state — used when the fragment goes back to
     *  status / error / idle (so the embedded sheet doesn't keep
     *  reading stale Ready/Settled values). */
    fun recordIdle() {
        _result.value = ResultState.Idle
        _wordLookups.value = WordLookupsState.Idle
        lookupJob?.cancel()
        lookupJob = null
    }

    /** Patch the current Ready result's translation/originalText —
     *  used by edit-original commit and live-mode incremental updates.
     *  Idempotent if [result] is not Ready (no-op). */
    fun patchResult(transform: (TranslationResult) -> TranslationResult) {
        val current = _result.value as? ResultState.Ready ?: return
        _result.value = ResultState.Ready(transform(current.result))
    }

    /**
     * Run the tokenize → dictionary-lookup pipeline for [text] on
     * [viewModelScope]. Cancels any in-flight lookup. Emits
     * [WordLookupsState.Loading] immediately and
     * [WordLookupsState.Settled] when complete.
     *
     * Pulls translation/original from the current [result] for the
     * [LastSentenceCache] write at the end so the cache stays in
     * sync with this VM's understanding of the result.
     */
    fun startWordLookups(text: String, appCtx: Context) {
        lookupJob?.cancel()
        _wordLookups.value = WordLookupsState.Loading
        lookupJob = viewModelScope.launch {
            try {
                val data = performLookups(appCtx, text)
                _wordLookups.value = WordLookupsState.Settled(
                    rows = data.rows,
                    tokenSpans = data.tokenSpans,
                    lookupToReading = data.lookupToReading,
                )
                // LastSentenceCache stays in sync — same write target as
                // before the hoist; only the writer changed (was fragment).
                val ready = _result.value as? ResultState.Ready
                LastSentenceCache.original = ready?.result?.originalText
                LastSentenceCache.translation = ready?.result?.translatedText
                LastSentenceCache.wordResults = data.rows.toLegacyMap()
                LastSentenceCache.surfaceForms = data.surfaces
            } catch (e: CancellationException) {
                // Caller cancelled (e.g. new text arrived) — let the next
                // emission drive state. Don't write Settled here.
                throw e
            } catch (_: Exception) {
                // Unexpected pipeline failure — stop the spinner with an
                // empty result so the UI doesn't hang on Loading forever.
                _wordLookups.value = WordLookupsState.Settled(
                    rows = emptyList(),
                    tokenSpans = emptyList(),
                    lookupToReading = emptyMap(),
                )
            }
        }
    }

    private suspend fun performLookups(appCtx: Context, text: String): LookupData {
        val prefs = Prefs(appCtx)
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang)
        val mlKit = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
        val enToTarget = TranslationManagerProvider.getEnToTarget(prefs.targetLang)
        val resolver = DefinitionResolver(
            engine, targetGlossDb,
            mlKit?.let { WordTranslator(it::translate) }, prefs.targetLang,
            enToTarget?.let { WordTranslator(it::translate) },
        )

        val allTokens = withContext(Dispatchers.IO) { engine.tokenize(text) }
        // [allTokens] is already `List<TokenSpan>` — pass straight through
        // for the fragment's wordSpan derivation against displayed text.

        val seen = mutableSetOf<String>()
        val uniqueTokens = allTokens.filter { seen.add(it.lookupForm) }
        val tokens = uniqueTokens.map { it.lookupForm }

        if (tokens.isEmpty()) {
            return LookupData(
                rows = emptyList(),
                tokenSpans = allTokens,
                lookupToReading = emptyMap(),
                surfaces = emptyMap(),
            )
        }

        val surfaceByToken = uniqueTokens.associate { it.lookupForm to it.surface }
        val readingByToken = uniqueTokens.associate { it.lookupForm to it.reading }

        // Fan out per-token lookups in parallel on IO. Per-row failures
        // produce nulls that we filter out below — same shape as the
        // pre-hoist code, just driven from VM scope so rotation
        // doesn't kill the in-flight job.
        data class Row(
            val rowState: RowState,
            val surfaceMapping: Pair<String, String>?,  // displayWord → surface, when they differ
        )

        val results: List<Row?> = withContext(Dispatchers.IO) {
            coroutineScope {
                tokens.map { word ->
                    async {
                        try {
                            val defResult = resolver.lookup(word, readingByToken[word])
                            val response = defResult?.response
                            if (response == null || response.entries.isEmpty()) return@async null
                            val entry = response.entries.first()
                            val flatSenses = response.entries.flatMap { it.senses }
                            val primary = entry.headwordFor(surfaceByToken[word])
                                ?: entry.headwordFor(word)
                                ?: entry.headwords.firstOrNull()
                            val displayWord = primary?.written ?: primary?.reading ?: word
                            val reading = primary?.reading?.takeIf { it != primary.written } ?: ""
                            val freqScore = entry.freqScore

                            val meaning = when (defResult) {
                                is DefinitionResult.Native -> {
                                    val targetSensesSorted = defResult.targetSenses.sortedBy { it.senseOrd }
                                    val isTargetDriven = prefs.targetLang != "en" && targetSensesSorted.isNotEmpty()
                                    if (isTargetDriven) {
                                        targetSensesSorted.mapIndexed { i, target ->
                                            val glosses = target.glosses.joinToString("; ")
                                            if (targetSensesSorted.size > 1) "${i + 1}. $glosses" else glosses
                                        }.joinToString("\n")
                                    } else {
                                        val targetByOrd = targetSensesSorted.associateBy { it.senseOrd }
                                        flatSenses.mapIndexed { i, sense ->
                                            val target = targetByOrd[i]
                                            val glosses = target?.glosses?.joinToString("; ")
                                                ?: sense.targetDefinitions.joinToString("; ")
                                            if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                        }.joinToString("\n")
                                    }
                                }
                                is DefinitionResult.MachineTranslated -> {
                                    val defs = defResult.translatedDefinitions
                                    if (defs != null) {
                                        flatSenses.mapIndexed { i, sense ->
                                            val glosses = defs.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                            if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                        }.joinToString("\n")
                                    } else {
                                        val translatedLine = defResult.translatedHeadword
                                        val englishLines = flatSenses.mapIndexed { i, sense ->
                                            val glosses = sense.targetDefinitions.joinToString("; ")
                                            if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                        }.joinToString("\n")
                                        "$translatedLine\n$englishLines"
                                    }
                                }
                                is DefinitionResult.EnglishFallback -> {
                                    val defs = defResult.translatedDefinitions
                                    flatSenses.mapIndexed { i, sense ->
                                        val glosses = defs?.getOrElse(i) { sense.targetDefinitions.joinToString("; ") }
                                            ?: sense.targetDefinitions.joinToString("; ")
                                        if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                                    }.joinToString("\n")
                                }
                            }
                            if (meaning.isEmpty()) return@async null
                            val surface = surfaceByToken[word] ?: word
                            Row(
                                rowState = RowState(
                                    displayWord = displayWord,
                                    reading = reading,
                                    meaning = meaning,
                                    freqScore = freqScore,
                                    isCommon = entry.isCommon == true,
                                    surface = surface,
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
                lookupToReading[tokens[idx]] = row.rowState.reading
                val surface = surfaceByToken[tokens[idx]]
                if (surface != null && surface != tokens[idx]) {
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

    private data class LookupData(
        val rows: List<RowState>,
        val tokenSpans: List<TokenSpan>,
        val lookupToReading: Map<String, String>,
        val surfaces: Map<String, String>,
    )
}

sealed class ResultState {
    object Idle : ResultState()
    data class Ready(val result: TranslationResult) : ResultState()
}

sealed class WordLookupsState {
    object Idle : WordLookupsState()
    object Loading : WordLookupsState()
    /** Final lookup results. [tokenSpans] carries the tokenizer's
     *  per-occurrence info so the fragment can compute character
     *  ranges in the displayed text (which may have OCR newlines
     *  inserted) for furigana + word-tap popup positioning.
     *  [lookupToReading] maps both the lookupForm and the surface
     *  form to the resolved reading, so conjugated forms get furigana
     *  too. */
    data class Settled(
        val rows: List<RowState>,
        val tokenSpans: List<TokenSpan>,
        val lookupToReading: Map<String, String>,
    ) : WordLookupsState()
}

/** Per-row data the fragment needs to render a word row + the
 *  embedded sheet needs to construct an Anki card. */
data class RowState(
    val displayWord: String,
    val reading: String,
    val meaning: String,
    val freqScore: Int,
    val isCommon: Boolean,
    val surface: String,
)

/** Convert the row list into the legacy `Map<String, Triple<...>>`
 *  shape that [WordDetailBottomSheet] / [WordAnkiReviewSheet]
 *  consume for Anki field building. */
fun List<RowState>.toLegacyMap(): Map<String, Triple<String, String, Int>> =
    associate { it.displayWord to Triple(it.reading, it.meaning, it.freqScore) }
