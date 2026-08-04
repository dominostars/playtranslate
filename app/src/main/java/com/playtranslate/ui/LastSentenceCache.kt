package com.playtranslate.ui

import android.content.Context
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.selectHeadword
import com.playtranslate.language.DefinitionResolver
import com.playtranslate.language.OfflineFallbackTranslators
import com.playtranslate.language.TargetGlossDatabaseProvider
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
 * Per-word pitch + frequencies carried alongside the lean word-lookup maps so
 * sentence-mode Anki sends can fill the pitch/frequency fields. One cohesive
 * value object (not two parallel maps) so the two can't skew apart; rides next
 * to [LastSentenceCache.wordResults] / [LastSentenceCache.surfaceForms].
 */
data class WordEnrichment(
    val pitch: List<Int> = emptyList(),
    val frequencies: List<FrequencyTag> = emptyList(),
    /** JMdict-style common-entry flag for the word's resolved entry; drives the
     *  Common pill in the sentence card's word cells. */
    val isCommon: Boolean = false,
    /** Structured senses for the word, captured where the dictionary entries
     *  are still in hand (the same [buildSenseDisplays] rows the lens shows).
     *  Empty when the lookup produced no entry — consumers fall back to the
     *  flattened meaning string. */
    val senses: List<SenseDisplay> = emptyList(),
    // Serializable so the sentence Anki review can carry per-word enrichment as
    // an atomic intent/args snapshot instead of re-reading the global cache
    // fields (which can belong to a different sentence by render time).
) : java.io.Serializable

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
    /** Maps display-word → its pitch + per-dictionary frequencies, for
     *  sentence-mode Anki sends. Rides atomically with [wordResults] /
     *  [surfaceForms]. */
    var wordEnrichment: Map<String, WordEnrichment>? = null

    /** The FULL-depth annotation the current sentence's words were projected
     *  from. Rotates with the sentence like the word maps. */
    var sentenceAnnotation: com.playtranslate.language.SentenceAnnotation? = null
        private set

    // ── In-flight tracking ───────────────────────────────────────────

    private data class Pending<T>(val sentence: String, val job: Deferred<T>)

    private var translationPending: Pending<TranslationOutcome?>? = null
    private var wordsPending: Pending<WordsPayload>? = null

    /** Result of a single sentence translation, including which backend
     *  produced it so the "Translated by …" label can be surfaced. */
    data class TranslationOutcome(val text: String, val backendDisplayName: String?)

    /** Bundles the two halves of a word-lookup pass so they can be
     *  written into the cache atomically — and read back atomically via
     *  [snapshotFor]. */
    data class WordsPayload(
        val results: Map<String, Triple<String, String, Int>>,
        val surfaces: Map<String, String>,
        val enrichment: Map<String, WordEnrichment>,
        /** The annotation the words were projected FROM — the single
         *  analysis the Anki renderers consume so card furigana, highlights,
         *  and word rows can never disagree. Null on legacy/empty payloads. */
        val annotation: com.playtranslate.language.SentenceAnnotation? = null,
    )

    /**
     * Locked, all-or-nothing snapshot of the word maps for [sentence]:
     * results + surfaces + enrichment read in one critical section, or
     * null when the cache belongs to another sentence or has no words
     * yet. The returned maps are the published references — the cache
     * swaps whole maps under [lock] and never mutates one after
     * publication — so the payload stays internally consistent no
     * matter how the global fields rotate afterwards.
     *
     * Use this instead of gated direct field reads whenever several
     * word maps must AGREE with each other, e.g. building intent extras
     * whose meaning slots are blanked against the enrichment shipped
     * beside them ([meaningForTransport]) — a mid-build rotation across
     * separate field reads could otherwise blank a meaning against
     * senses that never cross.
     */
    fun snapshotFor(sentence: String): WordsPayload? = synchronized(lock) {
        val results = wordResults ?: return null
        if (original != sentence || results.isEmpty()) return null
        WordsPayload(results, surfaceForms.orEmpty(), wordEnrichment.orEmpty(), sentenceAnnotation)
    }

    fun clear() {
        synchronized(lock) {
            original = null
            translation = null
            translationSource = null
            wordResults = null
            surfaceForms = null
            wordEnrichment = null
            sentenceAnnotation = null
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
        wordEnrichment: Map<String, WordEnrichment>?,
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
            this.wordEnrichment = wordEnrichment
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
                    WordsPayload(cached, surfaceForms.orEmpty(), wordEnrichment.orEmpty(), sentenceAnnotation)
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
                    WordsPayload(emptyMap(), emptyMap(), emptyMap())
                }
                synchronized(lock) {
                    if (original == sentence) {
                        wordResults = payload.results
                        surfaceForms = payload.surfaces
                        wordEnrichment = payload.enrichment
                        sentenceAnnotation = payload.annotation
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
            WordsPayload(emptyMap(), emptyMap(), emptyMap())
        } catch (_: Throwable) {
            WordsPayload(emptyMap(), emptyMap(), emptyMap())
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
        wordEnrichment = null
        sentenceAnnotation = null
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
        val resolver = DefinitionResolver(engine, targetGlossDb,
            OfflineFallbackTranslators.forPair(engine.profile.translationCode, prefs.targetLang), prefs.targetLang,
            OfflineFallbackTranslators.forTarget(prefs.targetLang),
            ChineseScriptConverter.forTarget(prefs.targetLang, prefs.targetChineseVariant))
        // ONE analysis for everything: the FULL-depth annotation resolves
        // display readings; the word rows below are its projection, and the
        // annotation itself rides the payload for the Anki renderers. Row
        // hydration re-uses each span's OWN lookup hint, so a row can never
        // land on a different entry than the annotation resolved (the
        // readings-only fast path that removes this second lookup per word
        // is the phase-4 live-cell optimization — refactor doc §6).
        val annotation = engine.annotate(sentence)
        val results = linkedMapOf<String, Triple<String, String, Int>>()
        val surfaces = linkedMapOf<String, String>()
        val enrichment = linkedMapOf<String, WordEnrichment>()
        for (tok in annotation.spans) {
            val lookupForm = tok.lookupForm ?: continue
            try {
                val defResult = resolver.lookup(lookupForm, tok.lookupHint)
                val response = defResult?.response
                if (response != null && response.entries.isNotEmpty()) {
                    val entry      = response.entries.first()
                    // Occurrence-validated headword pick — the same
                    // selectHeadword every other display surface uses; this
                    // call site had kept the pre-refactor written-form chain.
                    // The tokenizer's reading wins only when the entry lists
                    // that exact written↔reading pair (研究所 read
                    // けんきゅうしょ in context stays しょ, not the primary
                    // variant じょ); a concat that matches no pair — the
                    // sandhi case, 一泊/いちはく — falls through to the
                    // written-form chain, the prior behavior. That chain
                    // matters because JMdict often groups variant kanji
                    // under one entry (無下/無気, 出会う/出逢う), so the
                    // primary form can differ from the surface in the
                    // source text; lookupForm covers inflected surfaces
                    // that canonicalize to a non-primary headword.
                    val primary    = entry.selectHeadword(tok.surface, lookupForm, tok.lookupHint)
                    val displayWord = primary?.written ?: primary?.reading ?: lookupForm
                    val reading = primary?.reading?.takeIf { it != primary.written } ?: ""
                    // ONE construction of the definition content: the shared
                    // tier cascade (imported rows lead; target-driven for
                    // non-EN Native hits, entry-driven target→MT→source
                    // otherwise — the same rows the lens shows). The flat
                    // meaning string is DERIVED from the senses, so the two
                    // can't drift; this replaced a parallel hand-built
                    // cascade. defResult is non-null whenever response is —
                    // the null-check is for the compiler.
                    val senses = if (defResult != null) {
                        buildSenseDisplays(defResult, response.entries, prefs.targetLang)
                    } else emptyList()
                    val meaning = flatMeaningOf(senses)
                    if (meaning.isNotEmpty()) {
                        results[displayWord] = Triple(reading, meaning, entry.freqScore)
                        // primary is the headword we labelled the row with —
                        // its pitch/frequencies are what the sentence card's
                        // target-word fields want.
                        enrichment[displayWord] = WordEnrichment(
                            primary?.pitch.orEmpty(), primary?.frequencies.orEmpty(),
                            isCommon = entry.isCommon == true,
                            senses = senses,
                        )
                        if (tok.surface != displayWord) {
                            surfaces[displayWord] = tok.surface
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        WordsPayload(results, surfaces, enrichment, annotation)
    }

    private fun String.preview(): String =
        if (length <= 24) this else substring(0, 24) + "…"
}
