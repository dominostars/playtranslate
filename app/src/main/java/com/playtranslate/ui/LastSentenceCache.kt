package com.playtranslate.ui

import android.content.Context
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.headwordFor
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.DefinitionResult
import com.playtranslate.language.WordTranslator
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.TranslationManagerProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * In-memory cache for the most recent sentence's translation + word
 * lookups. The Activity, AccessibilityService, and CaptureService all
 * share one process, so plain object state is safe as long as
 * concurrent field reads/writes are serialized — which is what [lock]
 * is for.
 *
 * Two reasons to go through [awaitOrStartTranslation] /
 * [awaitOrStartWordLookups] instead of touching the fields directly:
 *
 * 1. **Staleness gate.** When the active sentence changes, the cache
 *    atomically clears all derived fields and cancels any pending jobs
 *    keyed to the old sentence. Callers that try to read
 *    [translation]/[wordResults] mid-transition get a consistent
 *    snapshot.
 * 2. **In-flight coalescing.** If a translation/word-lookup is already
 *    in flight for the same sentence, the helper returns the existing
 *    [Deferred] instead of firing a duplicate request. The sheet
 *    re-opening for the same sentence joins the in-flight job rather
 *    than re-hitting the translation backend.
 *
 * The internal [cacheScope] outlives any individual caller, so a
 * caller whose coroutine is cancelled mid-await leaves the underlying
 * Deferred running — the next caller for the same sentence joins it.
 */
object LastSentenceCache {

    private const val TAG = "LastSentenceCache"

    /** Java-monitor lock works in both suspend and non-suspend callers
     *  (kotlinx Mutex would force suspend everywhere, including the
     *  cache reads in [WordAnkiReviewActivity.onCreate] and
     *  [MainActivity]). Sections under the lock are short bookkeeping —
     *  the actual translation / lookup work runs outside. */
    private val lock = Any()

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Cached fields ────────────────────────────────────────────────
    // All reads and writes go through [lock]. Direct external reads
    // are tolerated as best-effort snapshots — they're only used by
    // callers that already gate on `original == <expected sentence>`.

    var original: String? = null
        private set
    var translation: String? = null
        private set

    /** Display name of the backend that produced [translation], surfaced as
     *  "Translated by …" on the drag-flow cached path so the lens → sentence
     *  tab transition keeps the same bottom label. Null when the writer
     *  doesn't track backend identity (e.g. legacy paths). */
    var translationSource: String? = null
        private set
    var wordResults: Map<String, Triple<String, String, Int>>? = null
        private set
    /** Maps display-word → surface form as it appears in the sentence (e.g. 分かる → 分からない). */
    var surfaceForms: Map<String, String>? = null
        private set

    // ── In-flight tracking ───────────────────────────────────────────

    private data class Pending<T>(val sentence: String, val job: Deferred<T>)

    private var translationPending: Pending<TranslationOutcome?>? = null
    private var wordsPending: Pending<WordsPayload>? = null

    /** Result of a single sentence translation, including which backend
     *  produced it so the "Translated by …" label can be surfaced. */
    data class TranslationOutcome(val text: String, val backendDisplayName: String?)

    /** Bundles the two halves of a word-lookup pass so they can be
     *  written into the cache atomically. */
    data class WordsPayload(
        val results: Map<String, Triple<String, String, Int>>,
        val surfaces: Map<String, String>,
    )

    fun clear() {
        synchronized(lock) {
            original = null
            translation = null
            translationSource = null
            wordResults = null
            surfaceForms = null
            translationPending?.job?.cancel()
            wordsPending?.job?.cancel()
            translationPending = null
            wordsPending = null
        }
    }

    /**
     * Atomic multi-field write used by [TranslationResultViewModel]
     * after a full capture → translate → tokenize pass completes. Keeps
     * the five fields in sync without going through the
     * await-or-start helpers (which are wired for the on-demand path).
     */
    fun setFromTranslationResult(
        original: String?,
        translation: String?,
        translationSource: String?,
        wordResults: Map<String, Triple<String, String, Int>>?,
        surfaceForms: Map<String, String>?,
    ) {
        synchronized(lock) {
            if (this.original != original) {
                evictPendingForOtherSentence(original)
            }
            this.original = original
            this.translation = translation
            this.translationSource = translationSource
            this.wordResults = wordResults
            this.surfaceForms = surfaceForms
        }
    }

    // ── Public helpers ───────────────────────────────────────────────

    /**
     * Returns the translation for [sentence], either from the cache or
     * by invoking [translate]. Coalesces with any in-flight request for
     * the same sentence. Returns null if [translate] throws.
     *
     * Caller scope is not used — the work runs on [cacheScope] so
     * caller cancellation cancels its `.await()` without killing the
     * underlying job.
     */
    suspend fun awaitOrStartTranslation(
        sentence: String,
        translate: suspend (String) -> TranslationOutcome,
    ): TranslationOutcome? {
        val deferred: Deferred<TranslationOutcome?> = synchronized(lock) {
            ensureSentenceLocked(sentence)
            translationPending?.takeIf { it.sentence == sentence }?.let {
                Log.d(TAG, "joining in-flight translation for '${sentence.preview()}'")
                return@synchronized it.job
            }
            translation?.let { cached ->
                Log.d(TAG, "cache hit translation for '${sentence.preview()}'")
                return@synchronized CompletableDeferred(
                    TranslationOutcome(cached, translationSource)
                )
            }
            Log.d(TAG, "starting translation for '${sentence.preview()}'")
            val job = cacheScope.async<TranslationOutcome?> {
                val outcome = try {
                    translate(sentence)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "translation failed for '${sentence.preview()}': ${t.message}")
                    null
                }
                synchronized(lock) {
                    if (original == sentence && outcome != null) {
                        translation = outcome.text
                        translationSource = outcome.backendDisplayName
                        Log.d(TAG, "cache write translation for '${sentence.preview()}'")
                    } else if (original != sentence) {
                        Log.d(TAG, "stale-discard translation for '${sentence.preview()}'")
                    }
                    if (translationPending?.sentence == sentence) {
                        translationPending = null
                    }
                }
                outcome
            }
            translationPending = Pending(sentence, job)
            job
        }
        return try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Distinguish "the caller's coroutine got cancelled" (must
            // propagate) from "a fresh sentence cancelled THIS sentence's
            // Deferred out from under us" (treat as a benign failure so
            // the sheet can swap its placeholder for the error variant).
            coroutineContext.ensureActive()
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the per-word lookups for [sentence], either from the
     * cache or by running [lookupWords]. Coalesces with any in-flight
     * request for the same sentence.
     *
     * Returns the full [WordsPayload] (results + surfaces) so the caller
     * can render entries from the same atomic snapshot. Reading
     * [surfaceForms] separately after the await would race with
     * [setFromTranslationResult] / a fresh helper invocation for a
     * different sentence — the global field can belong to *another*
     * sentence by the time we look at it, even though our deferred
     * carried matching surfaces all along.
     */
    suspend fun awaitOrStartWordLookups(
        context: Context,
        sentence: String,
    ): WordsPayload {
        val appCtx = context.applicationContext
        val deferred: Deferred<WordsPayload> = synchronized(lock) {
            ensureSentenceLocked(sentence)
            wordsPending?.takeIf { it.sentence == sentence }?.let {
                Log.d(TAG, "joining in-flight words for '${sentence.preview()}'")
                return@synchronized it.job
            }
            wordResults?.let { cached ->
                Log.d(TAG, "cache hit words for '${sentence.preview()}'")
                return@synchronized CompletableDeferred(
                    WordsPayload(cached, surfaceForms.orEmpty())
                )
            }
            Log.d(TAG, "starting words for '${sentence.preview()}'")
            val job = cacheScope.async {
                val payload = try {
                    lookupWords(appCtx, sentence)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "word lookup failed for '${sentence.preview()}': ${t.message}")
                    WordsPayload(emptyMap(), emptyMap())
                }
                synchronized(lock) {
                    if (original == sentence) {
                        wordResults = payload.results
                        surfaceForms = payload.surfaces
                        Log.d(TAG, "cache write words for '${sentence.preview()}'")
                    } else {
                        Log.d(TAG, "stale-discard words for '${sentence.preview()}'")
                    }
                    if (wordsPending?.sentence == sentence) {
                        wordsPending = null
                    }
                }
                payload
            }
            wordsPending = Pending(sentence, job)
            job
        }
        return try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // See [awaitOrStartTranslation] for the rationale — if our
            // own scope is fine, the Deferred was cancelled externally
            // (only happens via [clear] now), so don't infect the
            // caller with the cancellation: return empty and let the
            // UI render its "no words" placeholder.
            coroutineContext.ensureActive()
            WordsPayload(emptyMap(), emptyMap())
        } catch (_: Throwable) {
            WordsPayload(emptyMap(), emptyMap())
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    /** Must be called under [lock]. If [sentence] differs from the
     *  current [original], evicts any pending jobs that no longer
     *  apply, clears the derived fields, and flips [original] to the
     *  new value. */
    private fun ensureSentenceLocked(sentence: String) {
        if (original == sentence) return
        val prev = original
        evictPendingForOtherSentence(sentence)
        translation = null
        translationSource = null
        wordResults = null
        surfaceForms = null
        original = sentence
        Log.d(TAG, "cache cleared: '${prev?.preview()}' → '${sentence.preview()}'")
    }

    /** Releases the [translationPending] / [wordsPending] slots when the
     *  active sentence changes. The underlying [Deferred]s are deliberately
     *  NOT cancelled — any caller already awaiting them (e.g., an open
     *  Anki sheet that opened for the old sentence) gets the result for
     *  the sentence they asked for. The on-completion staleness gate in
     *  each helper discards the result from the cache fields if [original]
     *  has since moved on, so leaving the work running doesn't pollute.
     *
     *  Cancelling here was a bug: when live mode rotated the cache to a
     *  new sentence behind an open Anki sheet, the sheet's await threw
     *  CancellationException and the catch block returned an empty result
     *  — indistinguishable from "no words found" — silently producing
     *  zero-word Anki cards. */
    private fun evictPendingForOtherSentence(keepSentence: String?) {
        translationPending?.let { p ->
            if (p.sentence != keepSentence) {
                translationPending = null
            }
        }
        wordsPending?.let { p ->
            if (p.sentence != keepSentence) {
                wordsPending = null
            }
        }
    }

    /**
     * Tokenizes [sentence] and looks up each token in the dictionary.
     * Returns ([WordsPayload]) — the caller (i.e. [awaitOrStartWordLookups])
     * is responsible for writing the results into the cache atomically
     * with the staleness gate.
     */
    suspend fun lookupWords(
        context: Context,
        sentence: String,
    ): WordsPayload = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = Prefs(appCtx)
        val engine = com.playtranslate.language.SourceLanguageEngines.get(appCtx, prefs.sourceLangId)
        val targetGlossDb = TargetGlossDatabaseProvider.get(appCtx, prefs.targetLang)
        val mlKitTranslator = TranslationManagerProvider.get(engine.profile.translationCode, prefs.targetLang)
        val enToTarget = TranslationManagerProvider.getEnToTarget(prefs.targetLang)
        val resolver = DefinitionResolver(engine, targetGlossDb,
            mlKitTranslator?.let { WordTranslator(it::translate) }, prefs.targetLang,
            enToTarget?.let { WordTranslator(it::translate) })
        val tokenResults = engine.tokenize(sentence)
        val results = linkedMapOf<String, Triple<String, String, Int>>()
        val surfaces = linkedMapOf<String, String>()
        for (tok in tokenResults) {
            try {
                val defResult = resolver.lookup(tok.lookupForm, tok.reading)
                val response = defResult?.response
                if (response != null && response.entries.isNotEmpty()) {
                    val entry      = response.entries.first()
                    // Wiktionary multi-POS lookups split into separate
                    // entries; flatten so cached meanings include verb /
                    // intj / etc. instead of dropping every non-primary
                    // sense.
                    val flatSenses = response.entries.flatMap { it.senses }
                    // Pick the headword that matches what the user actually
                    // saw — JMdict often groups variant kanji under one
                    // entry (無下/無気, 出会う/出逢う) and the primary form
                    // can differ from the surface in the source text. Try
                    // surface first (catches the variant case directly),
                    // then lookupForm (covers inflected surfaces that
                    // canonicalize to a non-primary headword), then the
                    // primary as the last-resort label.
                    val primary    = entry.headwordFor(tok.surface)
                        ?: entry.headwordFor(tok.lookupForm)
                        ?: entry.headwords.firstOrNull()
                    val displayWord = primary?.written ?: primary?.reading ?: tok.lookupForm
                    val reading = primary?.reading?.takeIf { it != primary.written } ?: ""
                    // Mirror the word panel's render cascade: target-driven
                    // for non-EN Native hits, entry-driven (target→MT→source)
                    // for everything else. Without this, sentence-mode word
                    // rows showed raw English to non-EN users whenever the
                    // drag-lookup cache missed and this path repopulated it.
                    val nativeTargetSenses = (defResult as? DefinitionResult.Native)
                        ?.targetSenses?.sortedBy { it.senseOrd }
                        ?.takeIf { it.isNotEmpty() }
                    val isTargetDriven = prefs.targetLang != "en" && nativeTargetSenses != null
                    val meaning = if (isTargetDriven) {
                        nativeTargetSenses!!.mapIndexed { i, target ->
                            val glosses = target.glosses.joinToString("; ")
                            if (nativeTargetSenses.size > 1) "${i + 1}. $glosses" else glosses
                        }.joinToString("\n")
                    } else {
                        val targetByOrd = (defResult as? DefinitionResult.Native)
                            ?.targetSenses?.associateBy { it.senseOrd }
                        // Native no longer carries per-sense MT fallback —
                        // it always renders target-driven, so reaching this
                        // entry-driven branch with Native is unreachable in
                        // practice (target=en + Native isn't returned by
                        // DefinitionResolver).
                        val mtDefs = when (defResult) {
                            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
                            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
                            else -> null
                        }
                        flatSenses.mapIndexed { i, sense ->
                            val glosses = targetByOrd?.get(i)?.glosses?.joinToString("; ")
                                ?: mtDefs?.getOrNull(i)?.takeIf { it.isNotBlank() }
                                ?: sense.targetDefinitions.joinToString("; ")
                            if (flatSenses.size > 1) "${i + 1}. $glosses" else glosses
                        }.joinToString("\n")
                    }
                    if (meaning.isNotEmpty()) {
                        results[displayWord] = Triple(reading, meaning, entry.freqScore)
                        if (tok.surface != displayWord) {
                            surfaces[displayWord] = tok.surface
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        WordsPayload(results, surfaces)
    }

    private fun String.preview(): String =
        if (length <= 24) this else substring(0, 24) + "…"
}
