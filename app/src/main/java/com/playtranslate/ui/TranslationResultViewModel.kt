package com.playtranslate.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playtranslate.OneShotOverlayData
import com.playtranslate.Prefs
import com.playtranslate.language.InflectedForm
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.ReadingRow
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 *   - the [wordLookups] pipeline in its two tiers (see [WordLookupsState]):
 *     the analysis every result runs, and the dictionary rows only a
 *     visible Words card asks for ([requestWordRows]); both coroutines run
 *     on [viewModelScope] so rotation mid-lookup preserves progress
 *   - the [liveHint] state for live-mode UI hints
 *
 * Activities mutate state through this VM's methods; the fragment
 * is a renderer + event emitter (no public mutator methods of its
 * own). [TranslationResultActivity] also uses VM state to feed the
 * embedded [WordDetailBottomSheet] via [SentenceContextProvider], and the
 * floating workspace's lookup page feeds its word page the same way
 * ([sentenceContext]).
 *
 * Two owners: the Activities get theirs from a ViewModelStore (the no-arg
 * constructor; cleared with the store), and a workspace page constructs its
 * own with the page's scope, so the lookup pipeline dies with the page.
 * Either way [viewModelScope] IS the scope handed to the platform
 * constructor, and it is cancelled when that owner ends.
 */
class TranslationResultViewModel(scope: CoroutineScope) : ViewModel(scope) {

    /** Store-owned: the platform default scope, so a factory-created VM
     *  behaves exactly as before. */
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val _result = MutableStateFlow<ResultState>(ResultState.Idle)
    val result: StateFlow<ResultState> = _result.asStateFlow()

    private val _wordLookups = MutableStateFlow<WordLookupsState>(WordLookupsState.Idle)
    val wordLookups: StateFlow<WordLookupsState> = _wordLookups.asStateFlow()

    /** The analysis tier's coroutine ([startWordLookups]). */
    private var analysisJob: Job? = null

    /** The row tier's coroutine ([requestWordRows]); active only while the
     *  current analysis's rows resolve (a new text or leaving the results
     *  cancels it). */
    private var rowsJob: Job? = null

    /** The text handed to the latest [startWordLookups]: the lookup on record
     *  covers it (analysis in flight, Analyzed, rows in flight, Settled),
     *  which is what lets [displayResult] skip a restart on a same-text
     *  promotion. Null after [showStatus] / [showError], and after a
     *  failure of either tier, so a same-text promotion re-runs a failed
     *  lookup. */
    private var lookupText: String? = null

    /** The settled analysis of the latest [startWordLookups] text, kept for
     *  the row tier (a rows failure clears [lookupText] but not this): the
     *  rows resolve [Analysis.tokens]' row tokens through the SAME language
     *  snapshot that tokenized them (see [WordLookupContext]). Set exactly
     *  when [WordLookupsState.Analyzed] is emitted, cleared with the jobs. */
    private var analysis: Analysis? = null

    private class Analysis(
        val text: String,
        val context: WordLookupContext,
        /** The source language of the same prefs snapshot as [context]; keys
         *  the styled payload's dictionary query. */
        val sourceLang: com.playtranslate.language.SourceLangId,
        val tokens: PhraseAwareTokens,
        val annotation: com.playtranslate.language.SentenceAnnotation,
    )

    /** The most recent settled word-lookup paired with the source text it ran
     *  against. The [LastSentenceCache] words half needs BOTH this and a Ready
     *  translation for the same text (the translation half needs only the
     *  Ready result), and the two land in either order (the local dictionary
     *  lookup often settles before a network translation). We hold the settled
     *  lookup here so whichever lands second can write the full snapshot —
     *  see [writeLastSentenceCache]. Null until the rows of the
     *  current text settle, which happens only once they were requested
     *  ([requestWordRows]): a text whose rows nobody asked for is never
     *  cached here. */
    private var settledLookup: SettledLookup? = null

    private data class SettledLookup(
        val text: String,
        val data: LookupData,
        /** The analysis the tokens were projected from — forwarded into the
         *  cache write so an Anki send finds a matching annotation without
         *  re-annotating. */
        val annotation: com.playtranslate.language.SentenceAnnotation? = null,
    )

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
    fun displayResult(
        result: TranslationResult,
        appCtx: Context,
        onScreenBoxes: OnScreenBoxes? = null,
    ) {
        if (result === lastSeenResult) return
        lastSeenResult = result
        // If a Translating placeholder for this same source text is on screen,
        // it already started the identical lookup this capture cycle
        // (showTranslatingPlaceholder → displayResult, one capture). Promote to
        // Ready but DON'T restart the pipeline: re-running cancels the in-flight
        // analysis or rows job and flashes the word list back to Loading
        // (definitions show, vanish, reappear; the tap spans drop with them). A
        // refined/changed source text, or a path with no placeholder (live
        // mode, cached drag result), still re-runs lookups.
        val sameTextPlaceholder =
            (_result.value as? ResultState.Translating)?.originalText == result.originalText
        // Only skip if that placeholder lookup actually covers this text:
        // lookupText is the text of the lookup on record, whichever tier it
        // has reached (analysis in flight, Analyzed with the rows never
        // requested, rows in flight, Settled). settledLookup can't answer
        // this: the rows are lazy, so it stays null for a text whose card is
        // hidden, and keying on it would re-run the analysis on every
        // promotion and cancel a rows job mid-flight. A FAILED lookup of
        // either tier clears lookupText, so it falls through and re-runs —
        // otherwise a transient failure would leave the word list (and, for
        // an analysis failure, the tap spans) empty and the cache unwritten
        // for an otherwise-successful translation.
        val placeholderLookupCoversText = lookupText == result.originalText
        _result.value = ResultState.Ready(result, onScreenBoxes)
        if (!(sameTextPlaceholder && placeholderLookupCoversText)) {
            startWordLookups(result.originalText, appCtx)
        }
        // The translation just landed: cache it now (if non-blank). If the
        // (skipped) placeholder lookup's rows have already settled, this is
        // the second half — write the full cache. If they haven't, the rows'
        // own settle adds the words (if they are ever requested). Either
        // order works.
        writeLastSentenceCache()
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

    /** Show a status message. Cancels any in-flight lookup. A non-null
     *  [ocrProvenance] (+ [screenshotPath]) marks the "no text detected" status so
     *  the surface shows a tappable OCR-switch gear inline that can re-OCR that exact
     *  capture; both null for every other status. */
    fun showStatus(
        message: String,
        showHint: Boolean = false,
        ocrProvenance: OcrProvenance? = null,
        screenshotPath: String? = null,
    ) {
        resetWordLookups()
        _result.value = ResultState.Status(message, showHint, ocrProvenance, screenshotPath)
    }

    /** Show an error. Fragment formats with the status_error string
     *  resource. Cancels any in-flight lookup. */
    fun showError(message: String) {
        resetWordLookups()
        _result.value = ResultState.Error(message)
    }

    /** Leaving the results: both tiers' jobs cancelled, nothing on record
     *  (so the next result for any text runs its own lookup), state Idle. */
    private fun resetWordLookups() {
        analysisJob?.cancel()
        rowsJob?.cancel()
        analysis = null
        lookupText = null
        settledLookup = null
        _wordLookups.value = WordLookupsState.Idle
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
        ocrProvenance: com.playtranslate.model.OcrProvenance? = null,
        onScreenBoxes: OnScreenBoxes? = null,
    ) {
        _result.value = ResultState.Translating(originalText, segments, ocrProvenance, onScreenBoxes)
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
                        // Edited source is no longer the OCR output — drop provenance
                        // so the "Scanned by …" row + gear hide and re-OCR (which would
                        // discard the edit) is disabled.
                        ocrProvenance = null,
                        // The edit's own re-translate lands via updateTranslation — a
                        // surviving pending would let a later reveal clobber it with
                        // the OLD source's translation.
                        pendingTranslation = null,
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
    fun updateTranslation(translated: String, backendDisplayName: String? = null, appCtx: Context) {
        when (val cur = _result.value) {
            is ResultState.Ready -> {
                _result.value = ResultState.Ready(
                    cur.result.copy(
                        translatedText = translated,
                        backendDisplayName = backendDisplayName,
                        // A caller-supplied translation supersedes a deferred one.
                        pendingTranslation = null,
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
                        langContext = Prefs(appCtx).langContext(),
                    )
                )
            }
            else -> { /* No-op for Idle/Status/Error */ }
        }
        // Translation (or a re-translate) just landed on a Ready result — refresh
        // the cache so its translation/backend match, pairing with the already
        // settled lookup if there is one. No-op while still pending or non-Ready.
        writeLastSentenceCache()
    }

    /** Deferred-translation completion landing on the current Ready result:
     *  patch translation + note + backend, clear the pending, and swap in the
     *  freshly filled [onScreenBoxes] (null keeps the existing ones). Unlike
     *  [updateTranslation] it carries the note and preserves the boxes; unlike
     *  [displayResult] it never restarts word lookups — the source text is
     *  unchanged, and a restart would flash the settled word list.
     *
     *  [expected] is the pending the async completion was LAUNCHED for, and
     *  the guard is identity against it — not "some pending exists". A newer
     *  deferred result (recapture, fresh lookup) carries a different pending;
     *  a stale completion landing on it would show translation A for source B
     *  and burn B's pending so B never completes. */
    fun applyDeferredTranslation(
        expected: PendingTranslation,
        translated: String,
        note: String?,
        backendDisplayName: String?,
        onScreenBoxes: OnScreenBoxes? = null,
    ) {
        val cur = _result.value as? ResultState.Ready ?: return
        if (cur.result.pendingTranslation != expected) return
        _result.value = ResultState.Ready(
            cur.result.copy(
                translatedText = translated,
                note = note,
                backendDisplayName = backendDisplayName,
                pendingTranslation = null,
            ),
            onScreenBoxes ?: cur.onScreenBoxes,
        )
        writeLastSentenceCache()
    }

    /**
     * Write [LastSentenceCache] from the Ready result, each half as soon as it
     * is known. The translation half is cached once a non-blank translation is
     * Ready ([LastSentenceCache.setTranslation]), so the drag lens's
     * open-in-app is served from the cache whether or not the rows ever settle
     * (the rows are lazy: a hidden Words card never asks for them). The words
     * half joins once the rows of the SAME source text settle
     * ([settledLookup]), as one full snapshot
     * ([LastSentenceCache.setFromTranslationResult]).
     *
     * Called from both the rows-settle path ([requestWordRows]) and the Ready
     * transitions, so the two land in either order: rows that outrun the
     * translation write nothing until the Ready transition (no Ready, no
     * write, so the cache never holds a null sentence/translation), and a
     * translation that lands first is cached without rows of its own (words
     * the cache already holds for that sentence stay) until the settle writes
     * the full snapshot. A blank translation never writes either half.
     */
    private fun writeLastSentenceCache() {
        val ready = _result.value as? ResultState.Ready ?: return
        // A blank translation must never reach the cache: LastSentenceCache
        // treats a cached "" as a HIT (awaitOrStartTranslation), which would
        // poison every lazy Anki translation fill. Blank here means a deferred
        // result (pendingTranslation) or an error-path updateTranslation("");
        // the eventual real translation re-triggers this write.
        if (ready.result.translatedText.isBlank()) return
        val settled = settledLookup
        if (settled == null || settled.text != ready.result.originalText) {
            LastSentenceCache.setTranslation(
                original = ready.result.originalText,
                translation = ready.result.translatedText,
                translationSource = ready.result.backendDisplayName,
            )
            return
        }
        LastSentenceCache.setFromTranslationResult(
            original = ready.result.originalText,
            translation = ready.result.translatedText,
            translationSource = ready.result.backendDisplayName,
            wordResults = settled.data.rows.toLegacyMap(),
            surfaceForms = settled.data.surfaces,
            wordEnrichment = settled.data.rows.toEnrichmentMap(),
            annotation = settled.annotation,
        )
    }

    /**
     * Run the analysis tier for [text] on [viewModelScope]: tokenize +
     * phrase detection, no dictionary. Cancels any in-flight lookup of
     * either tier. Emits [WordLookupsState.Loading] immediately and
     * [WordLookupsState.Analyzed] when the analysis lands; the rows follow
     * only through [requestWordRows].
     */
    fun startWordLookups(text: String, appCtx: Context) {
        analysisJob?.cancel()
        rowsJob?.cancel()
        analysis = null
        // Invalidate the prior settled lookup until this text's rows land, so
        // a Ready transition mid-flight can't pair the cache write with stale
        // word data.
        settledLookup = null
        lookupText = text
        _wordLookups.value = WordLookupsState.Loading
        analysisJob = viewModelScope.launch {
            try {
                val a = analyze(appCtx, text)
                analysis = a
                _wordLookups.value = WordLookupsState.Analyzed(
                    tokenSpans = a.tokens.wordTokens,
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                )
            } catch (e: CancellationException) {
                // Caller cancelled (e.g. new text arrived) — let the next
                // emission drive state. Don't write Settled here.
                throw e
            } catch (_: Exception) {
                // Unexpected analysis failure — stop the spinner with an
                // empty result so the UI doesn't hang on Loading forever, and
                // drop the text from the record so displayResult re-runs it.
                lookupText = null
                _wordLookups.value = WordLookupsState.Settled(
                    rows = emptyList(),
                    tokenSpans = emptyList(),
                    lookupToReading = emptyMap(),
                )
            }
        }
    }

    /**
     * Run the row tier for the current analysis: one dictionary lookup per
     * unique row token (with the machine-translation fallback tiers) plus
     * the Words card's styled payload, then [WordLookupsState.Settled].
     * Called by the Words card whenever it is visible and may need rows
     * (see [WordRowsBinder.onRowsWanted]), so it is idempotent: a no-op
     * unless the state is [WordLookupsState.Analyzed] and no rows job is
     * active for it (Idle / Loading / Settled, or a repeat call while the
     * rows resolve).
     *
     * On settle, records [settledLookup] and writes the [LastSentenceCache]
     * (via [writeLastSentenceCache]) so the cache stays in sync with this
     * VM's understanding of the result.
     */
    fun requestWordRows(appCtx: Context) {
        // Analyzed is emitted only beside [analysis], and every path that
        // clears it leaves Analyzed in the same step.
        val a = analysis ?: return
        if (_wordLookups.value !is WordLookupsState.Analyzed || rowsJob?.isActive == true) return
        val wordTokens = a.tokens.wordTokens
        rowsJob = viewModelScope.launch {
            try {
                // The SAME snapshot that tokenized resolves (see
                // [WordLookupContext]): a settings change since the analysis
                // must not resolve its tokens through another engine.
                val data = resolveWordRows(appCtx, a.context, a.tokens.rowTokens)
                // The Words card's styled payload, fetched HERE beside the
                // rows and under the same language snapshot: one query (the
                // sentence sheet's shape, not the detail page's one-per-row),
                // so the card binds styled-or-flat in a single pass and no
                // view launches a fetch of its own. Covers only the rows the
                // card can style — the first styledWordRowCap structured rows,
                // the same cap its renderer pool is built with — so a long
                // list never inflates glossaries for rows that bind flat. Free
                // when styling is off or nothing is structured (no rowids, no
                // query), skipped outright where the card can hold no
                // renderer (a low-RAM device: cap 0); a datastore failure
                // costs the styling, never the rows.
                val styledCap = styledWordRowCap(appCtx)
                val styled = if (styledCap == 0) null else fetchYomitanStyledData(
                    appCtx, a.sourceLang.yomitanConsumingLang(), styledCandidateGroups(data.rows, styledCap),
                )
                // Tap spans project from the word tokens (phrase-free, see
                // [PhraseAwareTokens]); the phrase occurrences ride to the
                // fragment so its span computation can add tap targets for
                // single-letter phrase members ("a" in "a great deal")
                // anchored by the PHRASE's displayed range
                // (SourceWordLookup.computeTapSpans).
                val settledData = data.copy(tokenSpans = wordTokens)
                _wordLookups.value = WordLookupsState.Settled(
                    rows = data.rows,
                    tokenSpans = wordTokens,
                    lookupToReading = data.lookupToReading,
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                    styled = styled,
                )
                // Pair the settled rows with their source text and (re)write
                // the cache. If the translation has already landed (Ready,
                // same text), this completes the snapshot now; if not, the
                // Ready transition will. writeLastSentenceCache no-ops without
                // a Ready, non-blank translation, so rows that outran the
                // translation never cache a null sentence.
                settledLookup = SettledLookup(a.text, settledData, a.annotation)
                writeLastSentenceCache()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unexpected row failure — stop the spinner with no rows but
                // KEEP the analysis: unlike an analysis failure (which has no
                // tokens to offer and settles fully empty), the tap spans are
                // already derivable and stay tappable. No settledLookup, so
                // only the translation half is cached for this text (by the
                // Ready transition), not its words; and the text leaves the
                // record, as on an analysis failure, so a same-text promotion
                // retries the lookup (displayResult).
                lookupText = null
                _wordLookups.value = WordLookupsState.Settled(
                    rows = emptyList(),
                    tokenSpans = wordTokens,
                    lookupToReading = emptyMap(),
                    annotation = a.annotation,
                    phrases = a.tokens.phrases,
                )
            }
        }
    }

    /**
     * The sentence context an embedded word surface hands its Anki card:
     * every text field reads the VM first and [fallback] second, so a host
     * whose result hasn't settled yet (the results activity before its
     * pipeline lands, the lookup page before its Sentence tab was ever
     * opened, a result whose hidden Words card never requested its rows)
     * still supplies the launch-time sentence. The settled rows are
     * snapshotted ONCE so the legacy map, the surfaces and the enrichment
     * come from the same emission (a second read could straddle a fresh
     * emission, and the process-global cache rotates under live mode). A
     * fallback has no surfaces: null lets the one-tap helper await the
     * per-sentence cache, which is atomic. The pending rides the VM result
     * only: it is meaningful solely beside its own result's text.
     */
    fun sentenceContext(fallback: SentenceContext?): SentenceContext {
        val ready = _result.value as? ResultState.Ready
        val settledRows = (_wordLookups.value as? WordLookupsState.Settled)?.rows
        return SentenceContext(
            original = ready?.result?.originalText ?: fallback?.original,
            translation = ready?.result?.translatedText ?: fallback?.translation,
            wordResults = settledRows?.toLegacyMap() ?: fallback?.wordResults,
            surfaceForms = settledRows?.toSurfaceMap() ?: fallback?.surfaceForms,
            wordEnrichment = settledRows?.toEnrichmentMap() ?: fallback?.wordEnrichment,
            pending = ready?.result?.pendingTranslation,
        )
    }

    /** The analysis tier: one prefs snapshot, the engine's annotation of
     *  [text] and the phrase-aware tokens projected from it. No dictionary
     *  work; [requestWordRows] resolves the rows from the result. */
    private suspend fun analyze(appCtx: Context, text: String): Analysis {
        // Snapshot source/target prefs ONCE, before analyzing, so both tiers
        // run against one consistent language pair even if the user changes
        // settings mid-flight (see [WordLookupContext]).
        val prefs = Prefs(appCtx)
        val sourceLang = prefs.sourceLangId
        val engine = SourceLanguageEngines.get(appCtx, sourceLang)
        val context = WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant)
        // ONE analysis: the same FULL-depth annotation the furigana display
        // renders — its spans project the per-occurrence tokens the shared
        // resolver hydrates (dedup → parallel-lookup → RowState, see
        // [resolveWordRows]); tokenSpans round-trips so the fragment can
        // derive word spans against the displayed text.
        val annotation = withContext(Dispatchers.IO) { engine.annotate(text) }
        // Phrase-aware row tokens come from the SHARED producer — the
        // sentence cache's lookupWords builds from the same one, so the
        // words panel and every Anki words payload agree on phrase policy
        // by construction.
        val tokens = phraseAwareRowTokens(engine, text, annotation)
        return Analysis(text, context, sourceLang, tokens, annotation)
    }
}

/**
 * Paintable "show on screen" boxes for the current in-app result (the
 * dual-screen counterpart of the capture panel's header toggle): the one-shot
 * pipeline's overlay geometry plus the display the capture came from. Carried
 * ONLY by the one-shot capture collector's Translating (skeletons) / Ready
 * (translated) writes — every other write path defaults it to null, which is
 * what makes "any new content dismisses the on-screen boxes" enforceable in
 * the fragment's single render funnel instead of at N call sites.
 */
data class OnScreenBoxes(
    val data: OneShotOverlayData,
    val displayId: Int,
)

sealed class ResultState {
    object Idle : ResultState()
    /** Waiting / informational message; [showHint] toggles the
     *  "press X to start" hint line under the message. */
    data class Status(
        val message: String,
        val showHint: Boolean = false,
        /** OCR provenance + screenshot for the "no text detected" status, so the
         *  inline OCR-switch gear can re-OCR THAT exact capture. Both null for
         *  idle/error/searching/etc. (no gear). */
        val ocrProvenance: OcrProvenance? = null,
        val screenshotPath: String? = null,
    ) : ResultState()
    /** Drag-sentence placeholder: original text is set, translation
     *  is in flight ("Translating..." in the UI). */
    data class Translating(
        val originalText: String,
        val segments: List<TextSegment>,
        /** OCR provenance when this placeholder came from a capture (drives the
         *  source "Scanned by …" row during translation); null for drag/sentence/edit. */
        val ocrProvenance: com.playtranslate.model.OcrProvenance? = null,
        /** Skeleton boxes for the dual-screen "show on screen" toggle — see
         *  [OnScreenBoxes]. Null for drag/sentence/edit placeholders. */
        val onScreenBoxes: OnScreenBoxes? = null,
    ) : ResultState()
    data class Ready(
        val result: TranslationResult,
        /** Translated boxes for the dual-screen "show on screen" toggle — see
         *  [OnScreenBoxes]. Null for every non-one-shot-capture source. */
        val onScreenBoxes: OnScreenBoxes? = null,
    ) : ResultState()
    /** Translation/capture error; fragment formats with
     *  [com.playtranslate.R.string.status_error]. */
    data class Error(val message: String) : ResultState()
}


/**
 * The result's word lookup, in two tiers with different costs and different
 * readers:
 *  - the ANALYSIS (tokenize + phrase detection, one engine call cached per
 *    text) runs for every result: the source text's tap spans need only it,
 *    and the tap-a-word lens resolves its own word on tap;
 *  - the dictionary ROWS (a parallel dictionary lookup per unique word, the
 *    machine-translation fallback tiers for a non-English target, the
 *    styled payload, then one Words cell per row with its deck query) are
 *    built only when the Words card asks for them
 *    ([TranslationResultViewModel.requestWordRows]). The card can be hidden
 *    (the user's eye, or live mode's auto-hide), and in dual-screen live mode
 *    a result lands on every panel emission, so paying the rows for a list
 *    nobody reads was most of the lookup's cost.
 *
 * One text moves Loading → [Analyzed] → (only if the rows are requested)
 * [Settled]. An analysis failure goes straight to an empty [Settled].
 */
sealed class WordLookupsState {
    object Idle : WordLookupsState()
    object Loading : WordLookupsState()
    /** The analysis settled and the rows were NOT built: the tap spans are
     *  derivable ([tokenSpans] + [phrases]; each span's reading is the
     *  token's own lookup hint ([TokenSpan.reading]), which the lens passes
     *  to its resolve as the reading hint; the rows' resolved readings
     *  replace it when [Settled] lands), the Words card is not. Same field
     *  semantics as the matching [Settled] fields. */
    data class Analyzed(
        val tokenSpans: List<TokenSpan>,
        val annotation: com.playtranslate.language.SentenceAnnotation,
        val phrases: List<com.playtranslate.language.PhraseOccurrence>,
    ) : WordLookupsState()
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
        /** The analysis the rows were projected from; rides into hand-built
         *  one-tap WordsPayloads so isTrustedFor can prove freshness. */
        val annotation: com.playtranslate.language.SentenceAnnotation? = null,
        /** Detected multi-word expression occurrences — the fragment's span
         *  computation anchors single-letter phrase members' tap targets on
         *  these ([SourceWordLookup.computeTapSpans]). */
        val phrases: List<com.playtranslate.language.PhraseOccurrence> = emptyList(),
        /** One styled payload (structured glossaries + dictionary CSS) for
         *  every row's [RowState.importedGroups], fetched with the rows so
         *  the Words card binds styled-or-flat in one pass and the cells own
         *  no coroutines; each takes its share via
         *  [YomitanStyledData.forGroups]. Null = the flat tier throughout
         *  (styling off, nothing structured retained). In-process only. */
        val styled: YomitanStyledData? = null,
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
    /** The imported Yomitan groups BEHIND this row's flattened imported
     *  senses — the structured form the styled (WebView) renderer needs.
     *  Carried so a host that renders styled cells doesn't have to re-look-up
     *  the entry; the flat hosts ignore it. In-process only, like
     *  [WordDefinitionData.importedGroups]. */
    val importedGroups: List<ImportedSenseGroup> = emptyList(),
    val freqScore: Int,
    val isCommon: Boolean,
    val surface: String,
    /** Promoted part-of-speech for the word's Anki card (first sense's POS),
     *  so the cell can build the card without re-resolving the entry. */
    val ankiPos: String = "",
    /** Pitch-accent downstep variants for the displayed headword (empty
     *  when unknown); rides from HeadwordDisplay into the word cell. */
    val pitch: List<Int> = emptyList(),
    /** Per-dictionary frequency chips for the displayed headword; rides
     *  from HeadwordDisplay into the word cell like [pitch]. */
    val frequencies: List<FrequencyTag> = emptyList(),
    /** Distinct inflected forms this lemma appeared as in the source, each with
     *  its conjugation tags (e.g. 食べたい·Desiderative, 食べられない·Passive/Neg).
     *  Empty for uninflected words / non-Japanese sources. */
    val inflectedForms: List<InflectedForm> = emptyList(),
    /** Every reading of the entry in common-use order — the SAME source the word
     *  detail page uses ([DictionaryEntry.orderedReadingRows]) — with the
     *  occurrence reading flagged bolded. The cell lists these below the title
     *  when there's more than one or the inline reading won't fit. Empty for
     *  non-JA / no-reading rows. */
    val readingRows: List<ReadingRow> = emptyList(),
    /** The dictionary form the row was resolved under, which the word-detail
     *  page must resolve with ([WordDetailBinder.Args.lookupForm]). It can
     *  differ from [displayWord]: a usually-kana entry resolved under its
     *  kanji form and seen in kana displays as the kana. The default is for
     *  rows built without one. */
    val lookupForm: String = displayWord,
    /** The reading that narrowed [lookupForm]'s lookup, null when none
     *  ([WordDetailBinder.Args.lookupReading]). */
    val lookupReading: String? = null,
)

/** Convert the row list into the legacy `Map<String, Triple<...>>`
 *  shape that [WordDetailBottomSheet] / [WordAnkiReviewSheet]
 *  consume for Anki field building. */
fun List<RowState>.toLegacyMap(): Map<String, Triple<String, String, Int>> =
    associate { it.displayWord to Triple(it.reading, it.meaning, it.freqScore) }

/** A freshly rendered words list's order: visible words first, hidden words
 *  after, each group in lookup order (stable sort). Applied only when a list
 *  is rendered anew; a hide/show toggle re-stubs its cell in place and never
 *  moves it. The same instance when nothing in the list is hidden. */
fun List<RowState>.hiddenLast(hidden: Set<String>): List<RowState> =
    if (hidden.isEmpty() || none { it.displayWord in hidden }) this
    else sortedBy { it.displayWord in hidden }

/** Surface-form map paired with [toLegacyMap]. Both extensions read
 *  the same in-memory [RowState] list, so callers that snapshot both
 *  in a single pass keep word→surface alignment intact — important
 *  for one-tap card sends, which can't rely on reading
 *  `LastSentenceCache.surfaceForms` separately (the cache is
 *  process-global and may have rotated to a different sentence by
 *  the time a downstream consumer reads it). */
fun List<RowState>.toSurfaceMap(): Map<String, String> =
    associate { it.displayWord to it.surface }

/** Pitch + per-dictionary frequencies map paired with [toLegacyMap] /
 *  [toSurfaceMap] (same atomic-snapshot rationale — read together, not via the
 *  process-global cache, to keep word→data aligned). Feeds the sentence-card
 *  pitch/frequency Anki fields via [WordEnrichment]. */
fun List<RowState>.toEnrichmentMap(): Map<String, WordEnrichment> =
    associate {
        it.displayWord to WordEnrichment(it.pitch, it.frequencies, it.isCommon, it.senses)
    }

/** The sole resolved word when [sourceText] is exactly one token
 *  (whitespace-insensitive), else null. Drives the single-word Anki
 *  shortcut: a one-word result opens the word card directly instead of
 *  the sentence sheet. Compares the row's [RowState.surface] (the form
 *  as it appeared in the text) rather than [RowState.displayWord], so a
 *  lone inflected word (surface 使わない / lemma 使う) still matches while a
 *  word + particle (猫 in 猫は) or a repeat (猫 in 猫猫) does not. */
fun WordLookupsState.Settled.singleWordRow(sourceText: String): RowState? {
    val row = rows.singleOrNull() ?: return null
    if (row.surface.isBlank()) return null
    fun bare(s: String) = s.filterNot(Char::isWhitespace)
    return row.takeIf { bare(it.surface) == bare(sourceText) }
}
