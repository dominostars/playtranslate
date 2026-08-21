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
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.ReadingRow
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

    /** The most recent settled word-lookup paired with the source text it ran
     *  against. The [LastSentenceCache] write needs BOTH this and a Ready
     *  translation for the same text, and the two land in either order (the
     *  local dictionary lookup often settles before a network translation). We
     *  hold the settled lookup here so whichever lands second can complete the
     *  write — see [writeLastSentenceCache]. Null while a lookup is in flight. */
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
        // job and flashes the word list Settled → Loading → Settled (definitions
        // show, vanish, reappear). A refined/changed source text, or a path with
        // no placeholder (live mode, cached drag result), still re-runs lookups.
        val sameTextPlaceholder =
            (_result.value as? ResultState.Translating)?.originalText == result.originalText
        // Only skip if that placeholder lookup actually covers this text — still
        // in flight, or settled successfully (settledLookup set). A FAILED
        // placeholder lookup settles empty with no settledLookup (and its job
        // completes, so isActive is false), so it falls through and re-runs —
        // otherwise a transient lookup failure would leave the word list empty
        // and the cache unwritten for an otherwise-successful translation.
        val placeholderLookupCoversText =
            lookupJob?.isActive == true || settledLookup?.text == result.originalText
        _result.value = ResultState.Ready(result, onScreenBoxes)
        if (!(sameTextPlaceholder && placeholderLookupCoversText)) {
            startWordLookups(result.originalText, appCtx)
        }
        // The translation just landed; if the (skipped) placeholder lookup has
        // already settled, this is the second half — write the full cache. If it
        // hasn't, the lookup's own settle will write it. Either order works.
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
        lookupJob?.cancel()
        settledLookup = null
        _wordLookups.value = WordLookupsState.Idle
        _result.value = ResultState.Status(message, showHint, ocrProvenance, screenshotPath)
    }

    /** Show an error. Fragment formats with the status_error string
     *  resource. Cancels any in-flight lookup. */
    fun showError(message: String) {
        lookupJob?.cancel()
        settledLookup = null
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
     * Write [LastSentenceCache] once BOTH halves are known for the SAME source
     * text: a settled word lookup ([settledLookup]) and a Ready translation.
     * Called from both the lookup-settle path and the Ready transitions, so
     * whichever lands second triggers the write. No-op until they agree — this
     * is what stops a lookup that outruns the translation from caching a
     * snapshot with a null sentence/translation (the bug this guards).
     */
    private fun writeLastSentenceCache() {
        val ready = _result.value as? ResultState.Ready ?: return
        val settled = settledLookup ?: return
        if (settled.text != ready.result.originalText) return
        // A blank translation must never reach the cache: LastSentenceCache
        // treats a cached "" as a HIT (awaitOrStartTranslation), which would
        // poison every lazy Anki translation fill. Blank here means a deferred
        // result (pendingTranslation) or an error-path updateTranslation("");
        // the eventual real translation re-triggers this write.
        if (ready.result.translatedText.isBlank()) return
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
     * Run the tokenize → dictionary-lookup pipeline for [text] on
     * [viewModelScope]. Cancels any in-flight lookup. Emits
     * [WordLookupsState.Loading] immediately and
     * [WordLookupsState.Settled] when complete.
     *
     * On settle, records [settledLookup] and writes the
     * [LastSentenceCache] (via [writeLastSentenceCache]) so the cache stays in
     * sync with this VM's understanding of the result.
     */
    fun startWordLookups(text: String, appCtx: Context) {
        lookupJob?.cancel()
        // Invalidate the prior settled lookup until this one lands, so a Ready
        // transition mid-flight can't pair the cache write with stale word data.
        settledLookup = null
        _wordLookups.value = WordLookupsState.Loading
        lookupJob = viewModelScope.launch {
            try {
                val (data, annotation) = performLookups(appCtx, text)
                _wordLookups.value = WordLookupsState.Settled(
                    rows = data.rows,
                    tokenSpans = data.tokenSpans,
                    lookupToReading = data.lookupToReading,
                    annotation = annotation,
                )
                // Pair the settled lookup with its source text and (re)write the
                // cache. If the translation has already landed (Ready, same text),
                // this completes the snapshot now; if not, the Ready transition
                // will. writeLastSentenceCache no-ops until both agree, so a lookup
                // that outran the translation never caches a null sentence.
                settledLookup = SettledLookup(text, data, annotation)
                writeLastSentenceCache()
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

    private suspend fun performLookups(
        appCtx: Context,
        text: String,
    ): Pair<LookupData, com.playtranslate.language.SentenceAnnotation> {
        // Snapshot source/target prefs ONCE, before analyzing, so the whole
        // lookup runs against one consistent language pair even if the user
        // changes settings mid-flight (see [WordLookupContext]).
        val prefs = Prefs(appCtx)
        val engine = SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val context = WordLookupContext(engine, prefs.targetLang, prefs.targetChineseVariant)
        // ONE analysis: the same FULL-depth annotation the furigana display
        // renders — its spans project the per-occurrence tokens the shared
        // resolver hydrates (dedup → parallel-lookup → RowState, see
        // [resolveWordRows]); tokenSpans round-trips so the fragment can
        // derive word spans against the displayed text.
        val annotation = withContext(Dispatchers.IO) { engine.annotate(text) }
        val wordSpans = annotation.spans.filter { it.lookupForm != null }
        val allTokens = wordSpans.map {
            com.playtranslate.language.TokenSpan(
                it.surface, it.lookupForm!!, it.lookupHint, it.inflections,
            )
        }
        // Sentence-level phrase sweep: known multi-word expressions become
        // their own word-list rows, each inserted ahead of its first member
        // word. ROWS ONLY — the returned tokenSpans stays the word tokens,
        // because the fragment's tap spans consume tokens sequentially
        // against the displayed text (a phrase span would swallow its
        // members' ranges and single words must stay the tap targets).
        val phrases = withContext(Dispatchers.IO) { engine.phrasesIn(text) }
        val rowTokens = if (phrases.isEmpty()) allTokens else buildList {
            var p = 0
            wordSpans.forEachIndexed { i, s ->
                val wordStart = if (s.start >= 0) s.start else Int.MAX_VALUE
                while (p < phrases.size && phrases[p].range.first <= wordStart) {
                    add(com.playtranslate.language.TokenSpan(phrases[p].surface, phrases[p].lookupForm))
                    p++
                }
                add(allTokens[i])
            }
            while (p < phrases.size) {
                add(com.playtranslate.language.TokenSpan(phrases[p].surface, phrases[p].lookupForm))
                p++
            }
        }
        val data = resolveWordRows(appCtx, context, rowTokens)
        // Tap spans: the word tokens PLUS synthetic spans for single-letter
        // phrase members ("a" in "a great deal") — tokenize drops sub-2-char
        // words, but a member of a detected phrase must stay tappable or the
        // phrase is reachable from every member except one.
        return data.copy(
            tokenSpans = SourceWordLookup.tapTokensWithPhraseMembers(text, allTokens, phrases),
        ) to annotation
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
        /** The analysis the rows were projected from; rides into hand-built
         *  one-tap WordsPayloads so isTrustedFor can prove freshness. */
        val annotation: com.playtranslate.language.SentenceAnnotation? = null,
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
