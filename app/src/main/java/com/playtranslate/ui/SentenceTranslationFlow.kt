package com.playtranslate.ui

import android.content.Context
import com.playtranslate.CaptureService
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.translationlog.TranslationHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Translating a DELIBERATE sentence — one the user looked up (a lens drag,
 * a History row tap), not a capture — into a [TranslationResultViewModel].
 * The one owner of the shape every such surface used to carry itself:
 * MainActivity's dual-screen drag sentence, [TranslationResultActivity]'s
 * sentence mode, and the floating workspace's Sentence tab
 * ([WorkspaceSentencePage]).
 *
 * [show] lands the sentence three ways, in this order:
 *  - a [Cached] translation (the drag flow already produced it) binds a Ready
 *    result directly — no backend call, no "Translating…" flash, and no
 *    transient ML Kit reload a re-translation could fail on;
 *  - with the translation section hidden there is no consumer for the
 *    backend call, so a Ready lands carrying a [PendingTranslation]; the
 *    reveal (or an Anki flow that needs the sentence) calls
 *    [completeDeferred], which keeps the History rules below. The free
 *    source == target bypass never defers;
 *  - otherwise the "Translating…" placeholder (which starts the word
 *    lookups in parallel), then the backend, then the Ready promotion.
 *
 * History: the lookup itself was recorded translation-less at the moment it
 * happened (drag release, History tap), so a translation ATTACHES to that
 * entry instead of racing it into the dedupe gate — by key for a lookup, or
 * to EXACTLY the tapped row for a History tap ([historyRow]), and only when
 * the pair the translation ran under matches the row's stored pair (a
 * cross-pair result stays display-only rather than corrupting a row that
 * claims a different pair).
 *
 * ONE snapshot per translation: [show] reads the [TranslationLangContext]
 * and the two recording opt-ins (History, LLM context) once, before the
 * backend call, and that snapshot is what the backend translates under
 * ([Backend.translate] takes the pair explicitly), what the History attach
 * records under and is gated by, and what the displayed
 * [TranslationResult.langContext] claims. A mid-flight change can then
 * neither relabel the attach, nor leave a result displayed under a pair it
 * was not translated for (the surfaces' staleness sweeps compare that
 * context to the current prefs), nor record a lookup the user had opted
 * out of when they made it — a feature enabled during a slow online call
 * takes effect from the next lookup (the recorder still ANDs the snapshot
 * with the current pref, so a feature disabled mid-flight is respected
 * either way). A deferred completion runs under the pair the
 * [PendingTranslation] stored at lookup time (the result's own context,
 * variant included) and the pending's eligibility snapshot, never
 * reveal-time prefs — so the reveal fills the rows recorded under that
 * pair, and never silently replaces an old-pair result with a new-pair
 * translation.
 *
 * Failure lands "—" on the bound result (the same terminal the in-place
 * edit and every deferred completion use): a visible blank would render a
 * stuck "Translating…", and a full-screen error would throw away the source
 * and the word list, which are still useful without the translation.
 *
 * Supersession is by generation, not cancellation: a newer [show] lets the
 * older backend call finish and drops its DISPLAY, so [Backend.translate]
 * never has to be cancellation-safe mid-call; the older outcome still
 * attaches to its own sentence's History row (the attach is keyed by the
 * sentence, and that lookup was recorded too). The VM's own identity
 * guards ([TranslationResultViewModel.applyDeferredTranslation]) back the
 * deferred path the same way.
 */
class SentenceTranslationFlow(
    private val appCtx: Context,
    private val vm: TranslationResultViewModel,
    private val scope: CoroutineScope,
    /** Read per call, never cached: an Activity's service binding comes and
     *  goes, and the overlay side reads the process singleton. Null = no
     *  translator right now — [show] lands "—", [completeDeferred] keeps the
     *  pending so the next trigger retries (the Activities' pre-bind rule). */
    private val backend: () -> Backend?,
    /** A History row tap: attach the outcome to this exact row (pair-matched).
     *  Null (a lookup) attaches by key. */
    private val historyRow: HistoryRow? = null,
) {
    /** One translation outcome — the subset of the service's GroupTranslation
     *  a sentence surface binds. */
    data class Outcome(val text: String, val note: String?, val backendDisplayName: String?)

    /** The translator + History seam. [CaptureService.sentenceTranslationBackend]
     *  is the production implementation; tests hand in a stub. */
    interface Backend {
        /** Translate [text] under EXACTLY this pair (never the prefs at call
         *  time). */
        suspend fun translate(text: String, sourceLangId: SourceLangId, targetLang: String): Outcome

        /** By-key attach to the translation-less lookup entry (or a fresh
         *  record when the row is unknown). */
        fun attachLookup(
            source: String,
            translation: String,
            sourceLang: String,
            targetLang: String,
            backendDisplayName: String?,
            historyEligible: Boolean,
            contextEligible: Boolean,
        )

        /** Exact-row attach for a History tap. The tapped row already
         *  exists, so its recording consent predates this: no history
         *  override. */
        fun attachHistoryRow(
            rowId: Long,
            source: String,
            translation: String,
            sourceLang: String,
            targetLang: String,
            backendDisplayName: String?,
            contextEligible: Boolean,
        )
    }

    /** The History row a translation must attach to, with the pair the row
     *  was stored under (null = unknown, which never matches). */
    data class HistoryRow(val id: Long, val sourceLang: String?, val targetLang: String?)

    /** A translation the caller already holds for the sentence. */
    data class Cached(val text: String, val backendDisplayName: String?)

    /** Bumped per [show]; a backend call that outlives its generation drops
     *  its result instead of clobbering the newer sentence's state. */
    private var showGeneration = 0
    private var translateJob: Job? = null

    private var completionJob: Job? = null
    private var completionPending: PendingTranslation? = null

    /** The sentence currently shown by this flow (the last [show] argument);
     *  null before the first. */
    var sentence: String? = null
        private set

    fun show(sentence: String, screenshotPath: String?, cached: Cached? = null) {
        this.sentence = sentence
        val generation = ++showGeneration
        val segments = TextSegments.ofText(sentence)
        val prefs = Prefs(appCtx)
        // The one snapshot for this show (see the class doc): the pair, and
        // the recording opt-ins at LOOKUP time.
        val langContext = prefs.langContext()
        val historyEligible = prefs.translationHistoryEnabled
        val contextEligible = prefs.llmContextEnabled

        if (cached != null) {
            vm.displayResult(
                TranslationResult(
                    originalText = sentence,
                    segments = segments,
                    translatedText = cached.text,
                    timestamp = timestamp(),
                    screenshotPath = screenshotPath,
                    note = null,
                    backendDisplayName = cached.backendDisplayName,
                    langContext = langContext,
                ),
                appCtx,
            )
            return
        }

        val sourceId = langContext.sourceLangId
        if (prefs.hideTranslationSection &&
            SourceLanguageProfiles[sourceId].translationCode != langContext.targetLang
        ) {
            vm.displayResult(
                TranslationResult(
                    originalText = sentence,
                    segments = segments,
                    translatedText = "",
                    timestamp = timestamp(),
                    screenshotPath = screenshotPath,
                    pendingTranslation = PendingTranslation(
                        groupTexts = listOf(sentence),
                        sourceLangId = sourceId,
                        targetLang = langContext.targetLang,
                        // Logging eligibility at LOOKUP time — the completion
                        // honors this snapshot, not reveal-time prefs.
                        historyEligible = historyEligible,
                        contextEligible = contextEligible,
                    ),
                    langContext = langContext,
                ),
                appCtx,
            )
            return
        }

        vm.showTranslatingPlaceholder(sentence, segments, appCtx)
        val b = backend()
        if (b == null) {
            vm.updateTranslation(FAILED_TRANSLATION, appCtx = appCtx)
            return
        }
        translateJob = scope.launch {
            val outcome = try {
                translateAttachingHistory(
                    b, sentence, langContext,
                    historyEligible = historyEligible,
                    contextEligible = contextEligible,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (generation != showGeneration) return@launch
            if (outcome == null) {
                vm.updateTranslation(FAILED_TRANSLATION, appCtx = appCtx)
                return@launch
            }
            vm.displayResult(
                TranslationResult(
                    originalText = sentence,
                    segments = segments,
                    translatedText = outcome.text,
                    timestamp = timestamp(),
                    screenshotPath = screenshotPath,
                    note = outcome.note,
                    backendDisplayName = outcome.backendDisplayName,
                    langContext = langContext,
                ),
                appCtx,
            )
        }
    }

    /**
     * Run the translation a deferred SENTENCE result skipped, landing it
     * through [TranslationResultViewModel.applyDeferredTranslation] (identity-
     * guarded: a stale completion can't land on a newer result). Returns
     * false when the bound result carries no sentence-shaped pending — no
     * pending at all, or a capture's ([PendingTranslation.isCapture]), whose
     * batch translate + session History attach the capture service owns.
     * Must tolerate repeat calls while a completion is in flight: the same
     * pending is one batch only; a different pending supersedes.
     */
    fun completeDeferred(): Boolean {
        val ready = vm.result.value as? ResultState.Ready ?: return false
        val pending = ready.result.pendingTranslation ?: return false
        if (pending.isCapture) return false
        // No translator right now — keep the pending; the next trigger retries.
        val b = backend() ?: return true
        if (completionJob?.isActive == true) {
            if (completionPending == pending) return true
            completionJob?.cancel()
        }
        completionPending = pending
        // The pair the pending stored at lookup time; the variant rides the
        // result's own context (built from the same prefs read).
        val langContext = TranslationLangContext(
            pending.sourceLangId, pending.targetLang, ready.result.langContext.chineseVariant,
        )
        completionJob = scope.launch {
            try {
                val outcome = translateAttachingHistory(
                    b,
                    pending.groupTexts.firstOrNull() ?: ready.result.originalText,
                    langContext,
                    historyEligible = pending.historyEligible,
                    contextEligible = pending.contextEligible,
                )
                vm.applyDeferredTranslation(
                    pending,
                    outcome.text.ifBlank { FAILED_TRANSLATION },
                    outcome.note,
                    outcome.backendDisplayName,
                )
            } catch (e: CancellationException) {
                // Superseded (or the owner is going away) — the newer job
                // owns the state now; never land "—" for a cancelled run.
                throw e
            } catch (_: Exception) {
                // Ran and failed: land terminal — a visible blank + pending
                // renders a stuck "Translating…". Identity-guarded, so this
                // can't damage a newer result.
                vm.applyDeferredTranslation(pending, FAILED_TRANSLATION, null, null)
            }
        }
        return true
    }

    private suspend fun translateAttachingHistory(
        b: Backend,
        text: String,
        langContext: TranslationLangContext,
        historyEligible: Boolean,
        contextEligible: Boolean,
    ): Outcome {
        // The caller's snapshot is the pair the backend translates under AND
        // the pair the attach records under — never prefs read here.
        val sourceLang = SourceLanguageProfiles[langContext.sourceLangId].translationCode
        val targetLang = langContext.targetLang
        val outcome = b.translate(text, langContext.sourceLangId, targetLang)
        if (outcome.text.isNotEmpty()) {
            val row = historyRow
            if (row != null) {
                if (row.sourceLang == sourceLang && row.targetLang == targetLang) {
                    b.attachHistoryRow(
                        row.id, text, outcome.text, sourceLang, targetLang,
                        outcome.backendDisplayName, contextEligible,
                    )
                }
            } else {
                b.attachLookup(
                    text, outcome.text, sourceLang, targetLang,
                    outcome.backendDisplayName, historyEligible, contextEligible,
                )
            }
        }
        return outcome
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        /** The terminal text for a translation that ran and failed (or had
         *  no translator): what the in-place edit and the deferred
         *  completions land, so a bound result never shows a stuck
         *  placeholder. */
        const val FAILED_TRANSLATION = "—"
    }
}

/** The production [SentenceTranslationFlow.Backend]: the capture service's
 *  translate-once path (self-heals its language managers; needs no
 *  display/region configuration) plus its History recorder. */
fun CaptureService.sentenceTranslationBackend(): SentenceTranslationFlow.Backend =
    object : SentenceTranslationFlow.Backend {
        override suspend fun translate(
            text: String,
            sourceLangId: SourceLangId,
            targetLang: String,
        ): SentenceTranslationFlow.Outcome {
            val gt = translateOnce(text, sourceLangId, targetLang)
            return SentenceTranslationFlow.Outcome(gt.text, gt.note, gt.backendDisplayName)
        }

        override fun attachLookup(
            source: String,
            translation: String,
            sourceLang: String,
            targetLang: String,
            backendDisplayName: String?,
            historyEligible: Boolean,
            contextEligible: Boolean,
        ) {
            translationLogRecorder.onDeliberateTranslation(
                source, translation, sourceLang, targetLang,
                TranslationHistoryStore.PROVENANCE_LOOKUP, backendDisplayName,
                historyEligible = historyEligible,
                contextEligible = contextEligible,
            )
        }

        override fun attachHistoryRow(
            rowId: Long,
            source: String,
            translation: String,
            sourceLang: String,
            targetLang: String,
            backendDisplayName: String?,
            contextEligible: Boolean,
        ) {
            translationLogRecorder.onHistoryEntryTranslated(
                rowId, source, translation, sourceLang, targetLang,
                backendDisplayName, contextEligible = contextEligible,
            )
        }
    }
