package com.playtranslate.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
     *  Regenerates [segments] from [newText] via the shared [TextSegments]
     *  helper so the fragment's [tvOriginal.setSegments] renders
     *  the edited string. Without this, the OCR-derived segments from
     *  before the edit stay on screen even though originalText,
     *  translation, and lookups all shift to the new value. */
    fun updateOriginalText(newText: String, appCtx: Context) {
        val newSegments = TextSegments.ofText(newText)
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
     *  caller-supplied [translated] becomes the result's translation.
     *  [backendDisplayName] replaces the backend identity so a re-translate
     *  via a different backend doesn't leave the previous "Translated by …"
     *  label glued to the new text. Defaults to null so error-path callers
     *  ("" / "—") naturally clear the stale label that no longer matches. */
    fun updateTranslation(translated: String, backendDisplayName: String? = null) {
        when (val cur = _result.value) {
            is ResultState.Ready -> {
                _result.value = ResultState.Ready(
                    cur.result.copy(
                        translatedText = translated,
                        backendDisplayName = backendDisplayName,
                    )
                )
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
                        backendDisplayName = backendDisplayName,
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
                LastSentenceCache.setFromTranslationResult(
                    original = ready?.result?.originalText,
                    translation = ready?.result?.translatedText,
                    translationSource = ready?.result?.backendDisplayName,
                    wordResults = data.rows.toLegacyMap(),
                    surfaceForms = data.surfaces,
                )
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
        // Snapshot source/target prefs ONCE, before tokenizing, so the whole
        // lookup (tokenize + resolve) runs against one consistent language pair
        // even if the user changes settings mid-flight (see [WordLookupContext]).
        val prefs = Prefs(appCtx)
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val context = WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant)
        val allTokens = withContext(Dispatchers.IO) { engine.tokenize(text) }
        // Hand the per-occurrence tokens to the shared resolver; it owns the
        // dedup → parallel-lookup → RowState pipeline (see [resolveWordRows]).
        // tokenSpans round-trips back so the fragment can derive word spans
        // against the displayed text.
        return resolveWordRows(appCtx, context, allTokens)
    }
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
    /** Flattened, newline-joined definition string. Kept for the Anki field
     *  builders consumed via [toLegacyMap]. */
    val meaning: String,
    /** Structured senses (pos + gloss) driving the word cell's numbered,
     *  POS-grouped definitions. */
    val senses: List<SenseDisplay>,
    val freqScore: Int,
    val isCommon: Boolean,
    val surface: String,
    /** Promoted part-of-speech for the word's Anki card (first sense's POS),
     *  so the cell can build the card without re-resolving the entry. */
    val ankiPos: String = "",
)

/** Convert the row list into the legacy `Map<String, Triple<...>>`
 *  shape that [WordDetailBottomSheet] / [WordAnkiReviewSheet]
 *  consume for Anki field building. */
fun List<RowState>.toLegacyMap(): Map<String, Triple<String, String, Int>> =
    associate { it.displayWord to Triple(it.reading, it.meaning, it.freqScore) }

/** Surface-form map paired with [toLegacyMap]. Both extensions read
 *  the same in-memory [RowState] list, so callers that snapshot both
 *  in a single pass keep word→surface alignment intact — important
 *  for one-tap card sends, which can't rely on reading
 *  `LastSentenceCache.surfaceForms` separately (the cache is
 *  process-global and may have rotated to a different sentence by
 *  the time a downstream consumer reads it). */
fun List<RowState>.toSurfaceMap(): Map<String, String> =
    associate { it.displayWord to it.surface }
