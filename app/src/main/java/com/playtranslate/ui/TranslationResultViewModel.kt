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
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.model.headwordDisplay
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
 * Source of truth for the translation-result surface, scoped per
 * activity. Owns:
 *   - the [result] state machine (Idle / Status / Translating /
 *     Ready / Error), which the fragment renders via observation
 *   - the [wordLookups] pipeline, including the lookup coroutine on
 *     [viewModelScope] so rotation mid-lookup preserves progress
 *   - the [liveHint] state for live-mode UI hints
 *
 * Activities mutate state through this VM's methods; the fragment
 * is a renderer + event emitter (no public mutator methods of its
 * own). [TranslationResultActivity] also uses VM state to feed the
 * embedded [WordDetailBottomSheet] via [SentenceContextProvider].
 */
class TranslationResultViewModel : ViewModel() {

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result.asStateFlow()

    private val _wordLookups = MutableStateFlow<WordLookupsState>(WordLookupsState.Idle)
    val wordLookups: StateFlow<WordLookupsState> = _wordLookups.asStateFlow()

    private var lookupJob: Job? = null

    // ── Dedup architecture (read this before changing displayResult) ────
    //
    // Two layers cooperate to prevent redundant work and UI flicker
    // when the same result is emitted multiple times (sticky StateFlow
    // replay on lifecycle reattach, etc.):
    //
    //   Layer 1 — VM identity dedup (`===`).
    //     [displayResult] / [displayServiceResult] early-return when
    //     handed the same TranslationResult INSTANCE they last
    //     consumed. Skips the lookup pipeline restart. Identity, not
    //     equality, is intentional: a fresh capture of the same source
    //     text under a different backend or dictionary should still
    //     re-trigger lookups. The contract is "fresh capture =
    //     new instance"; CaptureService honours this by constructing
    //     a new TranslationResult per cycle.
    //
    //   Layer 2 — StateFlow equality conflation.
    //     [_result] is a MutableStateFlow, which by contract drops
    //     value assignments equal (`==`) to the current value. With
    //     ResultState.Ready and TranslationResult both being data
    //     classes, a content-equal emission produces no observable
    //     change to the StateFlow's value. This catches what Layer 1
    //     misses (e.g. a `.copy()` round-trip with identical content)
    //     and prevents UI flicker — the lookup may re-run on a Layer 1
    //     miss, but the fragment doesn't re-render.
    //
    // Two trackers, not one. Service-emitted and locally-emitted
    // results have separate dedup state because they participate in
    // different replay scenarios:
    //
    //   - lastSeenResult tracks *anything* shown. Catches any duplicate
    //     `displayResult` call (e.g. rotation mid-Ready).
    //
    //   - lastSeenServiceResult tracks only what the SERVICE emitted
    //     (via [displayServiceResult]). A local update — drag-sentence
    //     calling [displayResult] directly — must NOT advance this
    //     tracker, or the next STOP→START reattach to the service's
    //     panel StateFlow would re-deliver the prior service result
    //     and clobber the local one. This split is the architectural
    //     fix for the drag-sentence-after-live-mode bug; the test
    //     `local displayResult does not poison service-replay dedup`
    //     pins it.
    //
    // See CaptureSession.kt for the surrounding "two channels" model
    // and CaptureService.attachCancellationTerminal for the cancellation
    // story.

    /** See "Dedup architecture" above. Last result instance that was
     *  passed to [displayResult] from any source. */
    private var lastSeenResult: TranslationResult? = null

    /** See "Dedup architecture" above. Last result the service emitted
     *  via [displayServiceResult]. Advanced ONLY from that entry point;
     *  a [displayResult] call from local code (drag-sentence, edit
     *  overlay) must not touch this. */
    private var lastSeenServiceResult: TranslationResult? = null

    /** Display a completed translation result from any source. Used
     *  by both the service collector (via [displayServiceResult]) and
     *  by local code paths that build a result on the activity's own.
     *  No-op if [result] is the same instance already shown — see
     *  "Dedup architecture" above. */
    fun displayResult(result: TranslationResult, appCtx: Context) {
        if (result === lastSeenResult) return
        lastSeenResult = result
        _result.value = ResultState.Ready(result)
        startWordLookups(result.originalText, appCtx)
    }

    /** Display a result that came from the service's panel state.
     *  Distinct from [displayResult] because it advances
     *  [lastSeenServiceResult] separately from [lastSeenResult] —
     *  this is what keeps a STOP→START reattach to the panel
     *  StateFlow from replaying a stale service result on top of
     *  a local update. See "Dedup architecture" above. */
    fun displayServiceResult(result: TranslationResult, appCtx: Context) {
        if (result === lastSeenServiceResult) return
        lastSeenServiceResult = result
        displayResult(result, appCtx)
    }

    /** Show a status message. Cancels any in-flight lookup. */
    fun showStatus(message: String, showHint: Boolean = false) {
        lookupJob?.cancel()
        _wordLookups.value = WordLookupsState.Idle
        _result.value = ResultState.Status(message, showHint)
    }

    /** Show an error. Fragment formats with the status_error string
     *  resource. Cancels any in-flight lookup. */
    fun showError(message: String) {
        lookupJob?.cancel()
        _wordLookups.value = WordLookupsState.Idle
        _result.value = ResultState.Error(message)
    }

    /** Patch the current Status's [showHint] flag. No-op if not
     *  currently in Status. */
    fun setStatusHintVisibility(visible: Boolean) {
        val cur = _result.value as? ResultState.Status ?: return
        _result.value = cur.copy(showHint = visible)
    }

    /** Show "translating..." placeholder for drag-sentence flows.
     *  Triggers word lookups against the original text in parallel
     *  with the host's translation request. */
    fun showTranslatingPlaceholder(
        originalText: String,
        segments: List<TextSegment>,
        appCtx: Context,
    ) {
        _result.value = ResultState.Translating(originalText, segments)
        startWordLookups(originalText, appCtx)
    }

    /** Edit-overlay commit: replace original text on the current
     *  Ready/Translating result, reset translation, re-run lookups.
     *  No-op for non-result states.
     *
     *  Regenerates [segments] from [newText] (one TextSegment per
     *  character) so the fragment's [tvOriginal.setSegments] renders
     *  the edited string. Without this, the OCR-derived segments from
     *  before the edit stay on screen even though originalText,
     *  translation, and lookups all shift to the new value. */
    fun updateOriginalText(newText: String, appCtx: Context) {
        val newSegments = newText.map { TextSegment(it.toString()) }
        when (val cur = _result.value) {
            is ResultState.Ready -> {
                _result.value = ResultState.Ready(
                    cur.result.copy(
                        originalText = newText,
                        translatedText = "",
                        segments = newSegments,
                    )
                )
            }
            is ResultState.Translating -> {
                _result.value = ResultState.Translating(newText, newSegments)
            }
            else -> return
        }
        startWordLookups(newText, appCtx)
    }

    /** Update the translation text on the current Ready result.
     *  Promotes Translating → Ready when the translation lands; the
     *  caller-supplied [translated] becomes the result's translation. */
    fun updateTranslation(translated: String) {
        when (val cur = _result.value) {
            is ResultState.Ready -> {
                _result.value = ResultState.Ready(cur.result.copy(translatedText = translated))
            }
            is ResultState.Translating -> {
                _result.value = ResultState.Ready(
                    TranslationResult(
                        originalText = cur.originalText,
                        segments = cur.segments,
                        translatedText = translated,
                        timestamp = "",
                        screenshotPath = null,
                        note = null,
                    )
                )
            }
            else -> { /* No-op for Idle/Status/Error */ }
        }
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
                            // headwordDisplay swaps the kanji for the kana
                            // on JMdict uk-tagged entries (e.g. なぜ over
                            // 何故), suppressing the reading column since
                            // it would just duplicate the headword — UNLESS
                            // the source text actually showed the kanji
                            // (surfaceByToken[word] matches a written form),
                            // in which case we honor what the user saw.
                            val display = entry.headwordDisplay(
                                primary,
                                surfaceByToken[word],
                            )
                            val displayWord = display.written
                            val reading = display.reading ?: ""
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
    /** Waiting / informational message; [showHint] toggles the
     *  "press X to start" hint line under the message. */
    data class Status(val message: String, val showHint: Boolean = false) : ResultState()
    /** Drag-sentence placeholder: original text is set, translation
     *  is in flight ("Translating..." in the UI). */
    data class Translating(val originalText: String, val segments: List<TextSegment>) : ResultState()
    data class Ready(val result: TranslationResult) : ResultState()
    /** Translation/capture error; fragment formats with
     *  [com.playtranslate.R.string.status_error]. */
    data class Error(val message: String) : ResultState()
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
