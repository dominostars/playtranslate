package com.playtranslate.translation

import android.os.SystemClock
import android.util.Log
import com.playtranslate.diagnostics.TranslationDiag
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One active cooldown as seen by [TranslationBackendRegistry.earliestCooldownEnd]:
 *  when the backend becomes available again, and why it is down. [cause]
 *  is null for implementations predating cause tracking — consumers
 *  treat that as the transient message class. */
data class ActiveCooldown(val retryAt: Long, val cause: CooldownCause?)

/**
 * Holds the ordered list of [TranslationBackend]s and runs the
 * translation waterfall.
 *
 * Lifecycle: [init] is called once from [com.playtranslate.PlayTranslateApplication.onCreate]
 * and the registry then lives for the rest of the process. Backends are
 * pair-agnostic singletons — they share one set of HTTP clients / ML Kit
 * model instances across every CaptureService restart.
 *
 * Ordering: default order is by ascending [TranslationBackend.priority]
 * (id ascending as tiebreaker for determinism). [setOrder] supplies an
 * explicit list of ids that takes precedence; ids not in the override
 * are appended in default order. Pass null to [setOrder] to restore the
 * default. The override is in-memory only — not persisted across
 * process restarts. There is no UI today that calls [setOrder]; the
 * seam is in place for a future user-sortable backends feature.
 *
 * Thread safety: [init], [setOrder], and [close] are expected on the
 * main thread (settings UI, app lifecycle). [orderedBackends],
 * [preferredOnlineId], and [translate] may be called from any
 * coroutine. The override list is `@Volatile`; backend list is
 * effectively final after [init].
 */
object TranslationBackendRegistry {

    private const val TAG = "TranslationBackendRegistry"

    /** Dedicated support-forensics tag (same family as HomeRoute /
     *  DisplayDump): one line per DEGRADED waterfall outcome — which
     *  backends were skipped/failed and why, plus the connectivity
     *  summary. Quiet on healthy passes so the live loop stays clean. */
    private const val DIAG_TAG = "TranslateDiag"

    private fun logWaterfall(label: String, trail: List<String>) {
        Log.i(
            DIAG_TAG,
            "waterfall($label): ${trail.joinToString(" -> ")} | ${TranslationDiag.connectivitySummary()}"
        )
    }

    @Volatile private var backends: List<TranslationBackend> = emptyList()
    @Volatile private var orderOverride: List<BackendId>? = null

    /**
     * Receives each waterfall pass's online outcomes: one [OnlineAttempt]
     * per online service ([TranslationBackend.requiresInternet]) the pass
     * actually called, in waterfall order. Cooldown-skipped and unusable
     * services are absent, since they weren't asked. Offline tiers are
     * never reported. Called on the translating thread when the pass ends,
     * whether it returned, failed outright, or was cancelled: whatever
     * completed before a cancellation is still a fact about those services.
     * Passes overlap and a slow one reports after a faster, newer one, so
     * each attempt carries when its request went out
     * ([OnlineAttempt.sentAtMs]), stamped before the backend reads its
     * config. Installed once by [TranslationErrors.install]; null in unit
     * tests unless a test sets it.
     */
    @Volatile var onlineAttemptListener: ((List<OnlineAttempt>) -> Unit)? = null

    /** Hands [attempts] to [onlineAttemptListener]. Every waterfall pass
     *  reports through here, the cloud backend's calls among them, and so
     *  does PlayTranslate Cloud's frame reader
     *  ([com.playtranslate.cloud.PtCloudService.report]), whose frames never
     *  pass through the waterfall but whose failures show on the same pill,
     *  judged by the same tracker. */
    fun reportOnlineAttempts(attempts: List<OnlineAttempt>) {
        if (attempts.isEmpty()) return
        onlineAttemptListener?.invoke(attempts.toList())
    }

    /** Register the set of backends to use. Production wiring lives in
     *  [com.playtranslate.PlayTranslateApplication.onCreate]; tests can
     *  supply fakes. Idempotent — a repeat call replaces the prior
     *  registration after closing the existing backends. */
    fun init(backends: List<TranslationBackend>) {
        if (this.backends.isNotEmpty()) {
            Log.w(TAG, "init() called twice — closing prior backends and rebuilding")
            close()
        }
        this.backends = backends
    }

    /** Returns the registered backends in the active order: explicit
     *  override (when set) followed by remaining backends in default
     *  priority order. Default order is `(priority ascending, id ascending)`. */
    fun orderedBackends(): List<TranslationBackend> {
        val all = backends
        val byId = all.associateBy { it.id }
        val defaultOrder = all.sortedWith(compareBy({ it.priority }, { it.id }))
        val override = orderOverride ?: return defaultOrder

        val seen = HashSet<BackendId>()
        val out = ArrayList<TranslationBackend>(all.size)
        for (id in override) {
            val backend = byId[id] ?: continue
            if (seen.add(id)) out.add(backend)
        }
        for (backend in defaultOrder) {
            if (seen.add(backend.id)) out.add(backend)
        }
        return out
    }

    /** Override the default ordering with an explicit list of backend ids.
     *  Unknown ids are silently skipped; missing ids are appended in
     *  default priority order. Pass null to restore the default. */
    fun setOrder(orderedIds: List<BackendId>?) {
        orderOverride = orderedIds
    }

    fun byId(id: BackendId): TranslationBackend? = backends.firstOrNull { it.id == id }

    /** Register a store-driven online backend after [init] (user added a
     *  service instance). Copy-on-write swap of the `@Volatile` list —
     *  same discipline as [init]/[close], so an in-flight [translate]
     *  iterating the prior snapshot is unaffected. Main-thread only,
     *  like every other mutator here. */
    fun addOnlineBackend(backend: TranslationBackend) {
        if (backends.any { it.id == backend.id }) {
            Log.w(TAG, "addOnlineBackend(${backend.id}) — id already registered, replacing")
            removeOnlineBackend(backend.id)
        }
        backends = backends + backend
    }

    /** Deregister an online backend (user deleted a service instance, or
     *  a config save is rebuilding it). Closes the removed backend —
     *  in-flight calls on the prior list snapshot still hold a valid
     *  reference; close() only shuts pooled HTTP resources down, which
     *  OkHttp handles gracefully for stragglers. No-op for unknown ids. */
    fun removeOnlineBackend(id: BackendId) {
        val target = backends.firstOrNull { it.id == id } ?: return
        backends = backends - target
        runCatching { target.close() }
    }

    /** Returns the id of the first non-degraded usable backend for the
     *  pair that is NOT currently in a cooldown — this is the backend
     *  the cache should treat as "preferred" for its identity check.
     *  Returns `"none"` if no online backend is configured.
     *
     *  Cooldown awareness here means [TranslationCache.reconcilePreferredBackend]
     *  flips the preferred id when a cooldown is entered or exited,
     *  invalidating cache entries naturally so a recovered backend
     *  doesn't have its translations shadowed by stale fallback entries.
     *  Trade-off: cache thrashes on cooldown cycles, but cooldowns are
     *  rare and the cache rebuilds quickly. */
    fun preferredOnlineId(source: String, target: String): BackendId =
        preferredBackend(source, target)?.id ?: "none"

    /** The backend [preferredOnlineId] resolves to — the first non-degraded
     *  usable backend for the pair not currently in a cooldown, i.e. the one
     *  the waterfall would engage first. Null when none is configured. Used by
     *  the short-text route's LLM bypass, which keys on WHO would serve. */
    fun preferredBackend(source: String, target: String): TranslationBackend? =
        orderedBackends()
            .firstOrNull { backend ->
                if (backend.isDegradedFallback) return@firstOrNull false
                if (!backend.isUsable(source, target)) return@firstOrNull false
                val cool = (backend as? Cooldownable)?.unavailableUntil()
                cool == null || cool <= System.currentTimeMillis()
            }

    /** Run the waterfall: try each [orderedBackends] entry in order,
     *  skipping those whose [TranslationBackend.isUsable] is false, and
     *  fall through on exception. Returns on first success.
     *
     *  Cancellation propagates: a [CancellationException] from any
     *  backend re-throws so the caller's coroutine reaches its terminal
     *  Cancelled state without wasted fallback work. */
    suspend fun translate(text: String, source: String, target: String): WaterfallResult {
        val ordered = orderedBackends()
        if (ordered.isEmpty()) {
            throw IllegalStateException("TranslationBackendRegistry has no backends — was init() called?")
        }
        return singlePass(text, source, target, ordered, chosen = false)
    }

    /**
     * [translate]'s pass over [ordered] for one text. [chosen] is a run the
     * user asked one service for ([translateWith], whose [ordered] is that
     * one service, already found set up for the pair): the usable gate and
     * the cooldown skip are not applied, nothing is reported to
     * [onlineAttemptListener], and a failure is thrown as
     * [ChosenServiceFailedException] with what the service threw. Everything
     * else is the waterfall's own: the call, the success it records.
     */
    private suspend fun singlePass(
        text: String,
        source: String,
        target: String,
        ordered: List<TranslationBackend>,
        chosen: Boolean,
    ): WaterfallResult {
        // What the chosen service threw, for the caller to tell the user.
        var chosenFailure: Exception? = null
        // First on-device LLM (if any) that threw OnDeviceLlmTransientException
        // during this call. Propagated into the eventual WaterfallResult so the
        // caller can skip caching the fallback's output — without this, a
        // single low-memory moment would freeze a lower-quality result in the
        // cache until the next pref change.
        var displacedLlmId: BackendId? = null
        // Trail lists only backends the pass ENGAGED (attempted, failed,
        // or cooldown-skipped) — unusable tiers are omitted to keep the
        // line short. Logged only when something EVENTFUL degraded the
        // pass (a failure or a cooldown skip): a user whose only backend
        // IS the degraded fallback would otherwise emit one line per live
        // cycle, rolling logcat past the diagnostics we want to keep.
        val trail = ArrayList<String>(ordered.size)
        var eventful = false
        val attempts = ArrayList<OnlineAttempt>(2)
        try {
            for (backend in ordered) {
                if (!chosen && !backend.isUsable(source, target)) continue
                // Cooldown skip: backends in a parsed/ladder cooldown stay out
                // of rotation until retryAt elapses. The cache layer doesn't
                // need a per-result signal — [preferredOnlineId] excludes
                // cooled-down backends, so the cache's preferred-backend
                // reconcile invalidates stale entries on cooldown enter/exit.
                // A chosen service is asked past its cooldown: only the skip
                // is lifted, as for a question asked again ([complete]).
                //
                // `now` is read per-iteration: an earlier backend may have
                // hung for many seconds before failing, during which a
                // shorter cooldown on this one could have elapsed.
                val now = System.currentTimeMillis()
                val coolDown = (backend as? Cooldownable)?.unavailableUntil()
                if (!chosen && coolDown != null && coolDown > now) {
                    Log.d(TAG, "Backend ${backend.id} skipped (cooldown ${coolDown - now}ms remaining)")
                    trail.add("${backend.displayName}[cooldown ${(coolDown - now) / 1000}s]")
                    eventful = true
                    continue
                }
                val attemptStartedAt = System.currentTimeMillis()
                // The monotonic twin of attemptStartedAt (wall clock, which
                // the cooldowns persist): orders this attempt's report.
                val sentAt = SystemClock.elapsedRealtime()
                try {
                    val translated = backend.translate(text, source, target)
                    if (chosen) requireAnswered(text, translated)
                    (backend as? Cooldownable)?.recordSuccess(attemptStartedAt)
                    trail.add("${backend.displayName}[ok]")
                    if (backend.requiresInternet) {
                        attempts += OnlineAttempt(backend.id, backend.displayName, null, sentAt, reachedServer = true)
                    }
                    if (backend.isDegradedFallback && eventful) logWaterfall("single", trail)
                    return WaterfallResult(
                        text = translated,
                        backend = backend,
                        isDegraded = backend.isDegradedFallback,
                        displacedLlmId = displacedLlmId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is OnDeviceLlmTransientException && backend is OnDeviceLlmBackend) {
                        // Record the *first* displaced LLM in this call so the
                        // caller can skip caching the fallback's output. Multi-
                        // LLM displacement in the same call is rare; the first
                        // one is the most useful signal.
                        if (displacedLlmId == null) displacedLlmId = backend.id
                    }
                    Log.w(TAG, "Backend ${backend.id} failed (${e.javaClass.simpleName}: ${e.message}), falling back")
                    trail.add("${backend.displayName}[${e.javaClass.simpleName}]")
                    eventful = true
                    recordFailureDiag(backend, e)
                    if (backend.requiresInternet) attempts += failedAttempt(backend, e, sentAt)
                    chosenFailure = e
                }
            }
            if (chosen) {
                logWaterfall("chosen FAILED", trail)
                throw ChosenServiceFailedException(ordered.single(), chosenFailure)
            }
            logWaterfall("single ALL FAILED", trail)
            throw IllegalStateException("All translation backends failed")
        } finally {
            if (!chosen) reportOnlineAttempts(attempts)
        }
    }

    /** The backends that could translate the pair if asked now, whatever
     *  their switches and cooldowns say ([TranslationBackend.isSetUpFor]),
     *  in the waterfall's order: what the user picks one service from. */
    fun setUpFor(source: String, target: String): List<TranslationBackend> =
        orderedBackends().filter { it.isSetUpFor(source, target) }

    /**
     * Translate [texts] with the one service [id], which the user picked
     * for these texts: no other service is tried. The waterfall's own
     * passes run over that one service ([singlePass], [batchWaterfall]),
     * so the request is the one a capture would send it (one batch call
     * where it takes one, the per-text retry after a reply the batch could
     * not use), with three differences. It is asked whether or not it is
     * switched on, provided it is set up for the pair
     * ([TranslationBackend.isSetUpFor]). It is asked past a cooldown: only
     * the skip is lifted, so an answer clears the cooldown as any answer
     * does and a failure is recorded by the backend as on any call, where a
     * [CooldownState] leaves a cooldown that is still running as it is. An
     * answer with no text in it, for a text that has some, is a failure and
     * clears nothing ([requireAnswered]).
     * And nothing is reported to [onlineAttemptListener]: a failure is
     * said where the service was picked, as a question's is ([complete]).
     *
     * All or nothing: one result per text, in order, each naming the
     * service; when any text is left untranslated (the service threw for
     * it, or answered it with nothing), or the service is gone
     * or not set up for the pair (then nothing is sent), it throws
     * [ChosenServiceFailedException]. Cancellation re-throws.
     *
     * An on-device model that is switched off is released when the run
     * ends, however it ends
     * ([OnDeviceLlmBackend.releaseAfterChosenRun]).
     */
    suspend fun translateWith(
        id: BackendId,
        texts: List<String>,
        source: String,
        target: String,
    ): List<WaterfallResult> {
        if (texts.isEmpty()) return emptyList()
        val backend = byId(id)
        if (backend == null || !backend.isSetUpFor(source, target)) {
            throw ChosenServiceFailedException(backend, null)
        }
        val only = listOf(backend)
        try {
            return if (texts.size == 1) {
                listOf(singlePass(texts[0], source, target, only, chosen = true))
            } else {
                // The attempts are the pass's bookkeeping; a chosen run
                // reports none.
                batchWaterfall(texts, source, target, only, ArrayList(), chosen = true)
            }
        } finally {
            if (backend is OnDeviceLlmBackend) {
                withContext(NonCancellable) { backend.releaseAfterChosenRun() }
            }
        }
    }

    /** A chosen service's [translated] for [text] must say something: an
     *  answer with no text in it, for a text that has some, is the service
     *  failing that text, thrown before the answer is counted
     *  ([Cooldownable.recordSuccess]) as a question's empty answer is
     *  ([complete]). A chosen run is asked past a cooldown, and an answer
     *  that translated nothing is no sign that the cooldown's cause is
     *  over. Only a chosen run's rule: the waterfall's own passes take what
     *  a service returns, as they did, and never ask a cooling service. */
    private fun requireAnswered(text: String, translated: String) {
        if (translated.isBlank() && text.isNotBlank()) {
            throw StructuralFailureException("Empty translation", BackendFailure.BAD_RESPONSE)
        }
    }

    /** What [complete] returns: the answer, and who gave it. */
    class Completion(val answer: SentenceAnswer, val backend: TranslationBackend)

    /** Whether the user has a service that can be put a question about a
     *  sentence: any registered backend that takes one and is switched on
     *  and set up ([asking]). The Ask section asks this before it takes a
     *  question. */
    fun hasCompleter(): Boolean = orderedBackends().any { asking(it) != null }

    /**
     * How [backend] is put a question about a sentence, or null when it
     * takes none or is not set up to ([SentenceAnswerer.canAnswer],
     * [PromptCompleter.canComplete]): a [SentenceAnswerer] is given the
     * question whole and reads its parts, a [PromptCompleter] the two
     * messages made of them, its reply being the answer. The one statement
     * of who answers and how, for [hasCompleter] and [complete].
     */
    private fun asking(backend: TranslationBackend): (suspend (SentenceQuestion) -> SentenceAnswer)? = when {
        backend is SentenceAnswerer -> if (backend.canAnswer()) backend::answer else null
        backend is PromptCompleter && backend.canComplete() ->
            { question -> SentenceAnswer.Text(backend.complete(question.system, question.user)) }
        else -> null
    }

    /**
     * The order a question goes round in ([complete]): the order a
     * translation tries its backends ([orderedBackends]: the online services
     * in the user's order, then the on-device models), with the models made
     * for translation ([PromptCompleter.madeForTranslation]) after everyone
     * else, in that same order among themselves. Such a model leads the
     * on-device translations it was made for and answers a question worse
     * than a chat model, so with both switched on the chat model is asked,
     * and the translation model only when no other answers.
     */
    private fun askingOrder(): List<TranslationBackend> =
        orderedBackends().sortedBy { (it as? PromptCompleter)?.madeForTranslation == true }

    /**
     * Put a question about a sentence to the user's services: one pass over
     * those that can answer one ([asking]: the LLMs, and a service that
     * writes the prompt itself) in [askingOrder], skipping one in a cooldown,
     * falling through on a failure, returning the first answer. A blank
     * reply is a failure. A service
     * saying the question is outside what it answers
     * ([SentenceAnswer.OutOfScope]) is an answer: the pass ends with it.
     *
     * A service is skipped while the cooldown its translations share is
     * active ([Cooldownable.unavailableUntil]) and while a pause of its
     * answers alone is ([SentenceAnswerer.answersUnavailableUntil]), except
     * on a pass [pastCooldowns], the user asking again after a question
     * failed (the Ask session's retry): the service in a cooldown may be
     * the very one whose failure the user is looking at (a 429 or a 5xx to
     * a question can start one), and a retry that skipped it would ask
     * nobody and fail at once. Only the skip is lifted: an answer clears
     * the cooldown as any answer does, and a failure is recorded by the
     * backend as on any question, where a [CooldownState] leaves a cooldown
     * that is still running as it is, so asking past one does not lengthen
     * it.
     *
     * [onAttempt] hears of each service as the pass starts asking it, so a
     * caller can say who is being asked: the first pick before the pass
     * first suspends, then the next one each time it falls through. One
     * skipped (unable, in a cooldown, or paused) is never named.
     *
     * A win clears the service's cooldown state as a translated text does
     * ([Cooldownable.recordSuccess]): it answered. What a failure does to
     * that state is the backend's own business ([PromptCompleter.complete],
     * [SentenceAnswerer.answer]). Nothing here is reported to
     * [onlineAttemptListener]: that feeds the translation error pill, and a
     * question that got no answer says so where it was asked.
     *
     * Throws [CompletionFailedException] when nobody answered, carrying the
     * first backend that was tried and what it threw (neither, when every
     * one was skipped or none exists). Cancellation re-throws.
     */
    suspend fun complete(
        question: SentenceQuestion,
        pastCooldowns: Boolean = false,
        onAttempt: (TranslationBackend) -> Unit = {},
    ): Completion {
        var firstTried: TranslationBackend? = null
        var firstFailure: Exception? = null
        for (backend in askingOrder()) {
            val ask = asking(backend) ?: continue
            val now = System.currentTimeMillis()
            val coolDown = listOfNotNull(
                (backend as? Cooldownable)?.unavailableUntil(),
                (backend as? SentenceAnswerer)?.answersUnavailableUntil(),
            ).maxOrNull()
            if (coolDown != null && coolDown > now) {
                if (!pastCooldowns) {
                    Log.d(TAG, "Completer ${backend.id} skipped (cooldown ${coolDown - now}ms remaining)")
                    continue
                }
                Log.d(TAG, "Completer ${backend.id} asked past its cooldown (${coolDown - now}ms remaining)")
            }
            onAttempt(backend)
            val attemptStartedAt = System.currentTimeMillis()
            try {
                val answer = ask(question)
                if (answer is SentenceAnswer.Text && answer.text.isBlank()) {
                    throw StructuralFailureException("Empty answer", BackendFailure.BAD_RESPONSE)
                }
                (backend as? Cooldownable)?.recordSuccess(attemptStartedAt)
                return Completion(answer, backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Completer ${backend.id} failed (${e.javaClass.simpleName}), falling back")
                if (firstTried == null) {
                    firstTried = backend
                    firstFailure = e
                }
            }
        }
        throw CompletionFailedException(firstTried, firstFailure)
    }

    /** The report for an online backend's call that threw [e]: the server
     *  was reached when it answered the failure ([serverAnswered]). */
    private fun failedAttempt(backend: TranslationBackend, e: Exception, sentAt: Long): OnlineAttempt {
        val failure = classifyBackendFailure(e)
        return OnlineAttempt(
            backend.id, backend.displayName, failure, sentAt,
            reachedServer = serverAnswered(e, failure),
        )
    }

    /** Whether the server answered the failure [e] classified as [failure]:
     *  what the exception says of itself when it is ours
     *  ([ClassifiedFailure.serverAnswered], false for a refusal made before
     *  any request), else not for a transport failure, which never heard
     *  back. */
    private fun serverAnswered(e: Throwable, failure: BackendFailure): Boolean =
        (e as? ClassifiedFailure)?.serverAnswered ?: !failure.kind.isTransport

    /** Push one backend failure into [TranslationDiag]'s persisted ring.
     *  Content-free by construction: class name, HTTP code, and cooldown
     *  timestamp only — never the exception MESSAGE (transport messages
     *  embed request URLs, and Lingva URLs carry captured text). */
    private fun recordFailureDiag(backend: TranslationBackend, e: Exception) {
        TranslationDiag.recordFailure(
            backendName = backend.displayName,
            exceptionClass = e.javaClass.simpleName,
            httpCode = (e as? ClassifiedFailure)?.failure?.httpCode,
            cooldownUntil = (backend as? Cooldownable)?.unavailableUntil(),
        )
    }

    /**
     * Batched waterfall: translate every entry in [texts] for the given
     * pair, returning one [WaterfallResult] per input in matching order.
     *
     * Per-backend semantics:
     *  - If the backend implements [BatchTranslator] and there's more
     *    than one pending text, try one all-or-nothing batched call.
     *    Success → fill in every pending slot with the same backend.
     *    Failure (any exception except [CancellationException]) → fall
     *    through to the next backend with the FULL pending list. Do NOT
     *    retry per-text within the same backend (that would re-introduce
     *    the parallel-call rate-limit thrashing this method exists to
     *    eliminate).
     *  - Otherwise (non-batching backend OR pending size == 1) → fan
     *    out per-text in parallel via [coroutineScope]/[async]. Each
     *    successful text removes its index from the pending list before
     *    moving to the next backend.
     *
     * Special handling for on-device LLMs: if any text on a non-batching
     * [OnDeviceLlmBackend] throws [OnDeviceLlmTransientException],
     * treat the WHOLE backend pass as failed for ALL remaining pending
     * texts. Memory pressure isn't per-text — retrying the rest on the
     * same backend under the same pressure would pin the engine longer
     * for no gain. Set [displacedLlmId] on the affected results so the
     * caller can skip caching that fall-through output.
     *
     * Single-text inputs short-circuit through the existing [translate]
     * path to avoid the batch-prompt overhead on 1-group captures.
     *
     * Cancellation re-throws to honor structured concurrency.
     */
    suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<WaterfallResult> {
        val ordered = orderedBackends()
        if (ordered.isEmpty()) {
            throw IllegalStateException("TranslationBackendRegistry has no backends — was init() called?")
        }
        if (texts.size == 1) {
            return listOf(translate(texts[0], source, target))
        }
        val attempts = ArrayList<OnlineAttempt>(2)
        try {
            return batchWaterfall(texts, source, target, ordered, attempts)
        } finally {
            reportOnlineAttempts(attempts)
        }
    }

    /** [translateBatch]'s waterfall proper, for two or more texts. Appends
     *  one [OnlineAttempt] to [attempts] per online backend it calls; the
     *  caller reports them once the pass ends, however it ends. [chosen]
     *  is [singlePass]'s: the one service of [ordered] is asked whatever
     *  its switch and cooldown say, and texts left untranslated are thrown
     *  as [ChosenServiceFailedException] with what it threw. */
    private suspend fun batchWaterfall(
        texts: List<String>,
        source: String,
        target: String,
        ordered: List<TranslationBackend>,
        attempts: MutableList<OnlineAttempt>,
        chosen: Boolean = false,
    ): List<WaterfallResult> {
        val results = arrayOfNulls<WaterfallResult>(texts.size)
        var pendingIndices: List<Int> = texts.indices.toList()
        // What the chosen service threw, for the caller to tell the user.
        var chosenFailure: Exception? = null
        // Per-index displacement signal: when a non-batching on-device
        // LLM throws transient on this batch, every text that subsequently
        // succeeds at a fallback backend carries this id so the caller
        // can skip caching the fallback output.
        var displacedLlmId: BackendId? = null
        val trail = ArrayList<String>(ordered.size)
        // Same eventful gate as [translate] — see the comment there.
        var eventful = false

        for (backend in ordered) {
            if (pendingIndices.isEmpty()) break
            if (!chosen && !backend.isUsable(source, target)) continue
            val coolDown = (backend as? Cooldownable)?.unavailableUntil()
            if (!chosen && coolDown != null && coolDown > System.currentTimeMillis()) {
                Log.d(TAG, "Backend ${backend.id} skipped (cooldown ${coolDown - System.currentTimeMillis()}ms remaining)")
                trail.add("${backend.displayName}[cooldown ${(coolDown - System.currentTimeMillis()) / 1000}s]")
                eventful = true
                continue
            }

            val pendingTexts = pendingIndices.map { texts[it] }
            // The batch call got a reply it couldn't use: the per-text retry
            // below reports this backend, and that reply already proves its
            // server was reached (not DeepL's cap, refused before sending).
            var batchAnswered = false

            if (backend is BatchTranslator && pendingTexts.size > 1) {
                val batchStartedAt = System.currentTimeMillis()
                val batchSentAt = SystemClock.elapsedRealtime()
                try {
                    val translated = backend.translateBatch(pendingTexts, source, target)
                    if (translated.size != pendingTexts.size) {
                        throw BatchParseException(
                            "Backend ${backend.id} returned ${translated.size} translations for ${pendingTexts.size} inputs"
                        )
                    }
                    if (chosen) pendingTexts.forEachIndexed { i, t -> requireAnswered(t, translated[i]) }
                    (backend as? Cooldownable)?.recordSuccess(batchStartedAt)
                    trail.add("${backend.displayName}[ok]")
                    if (backend.requiresInternet) {
                        attempts += OnlineAttempt(backend.id, backend.displayName, null, batchSentAt, reachedServer = true)
                    }
                    pendingIndices.forEachIndexed { i, origIdx ->
                        results[origIdx] = WaterfallResult(
                            text = translated[i],
                            backend = backend,
                            isDegraded = backend.isDegradedFallback,
                            displacedLlmId = displacedLlmId,
                        )
                    }
                    pendingIndices = emptyList()
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BatchParseException) {
                    // The provider responded but the batch shape didn't
                    // match — the LLM backends' JSON envelope malformed or
                    // short, DeepL's 50-string cap. For those the per-text
                    // translate() path is a different request (another
                    // prompt, one string) and usually still works, so fall
                    // through to the per-text branch below on THIS backend
                    // (no `continue`) instead of skipping to a degraded
                    // fallback like ML Kit. A backend whose per-text path
                    // shares the batch endpoint and parser (Lingva) must
                    // NOT throw this: its shape failures are
                    // StructuralFailureException, caught below, so one
                    // unusable response is not followed by N identical
                    // ones. The no-thrashing rule for rate limits still
                    // holds — those throw typed *RateLimitException, not
                    // BatchParseException, and are caught by the broader
                    // Exception branch.
                    Log.w(
                        TAG,
                        "Backend ${backend.id} batch parse failed (${e.message}), retrying per-text on same backend"
                    )
                    trail.add("${backend.displayName}[batch-parse, per-text retry]")
                    batchAnswered = e.serverAnswered
                    chosenFailure = e
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Backend ${backend.id} batch failed (${e.javaClass.simpleName}: ${e.message}), falling back"
                    )
                    trail.add("${backend.displayName}[batch ${e.javaClass.simpleName}]")
                    eventful = true
                    recordFailureDiag(backend, e)
                    if (backend.requiresInternet) attempts += failedAttempt(backend, e, batchSentAt)
                    chosenFailure = e
                    // Fall through to the next backend with the FULL
                    // pending list. Intentional: per-text retry inside
                    // the same backend would defeat the batching point
                    // when the failure was a rate limit / auth / HTTP
                    // error — the provider isn't healthy for this call.
                    continue
                }
            }

            // Per-text parallel fan-out for non-batching backends (or
            // size-1 pending). Mirrors today's single-text waterfall
            // behavior, with the addition of per-index displacement
            // tracking and the LLM-transient backend-wide bailout.
            val perTextStartedAt = System.currentTimeMillis()
            val perTextSentAt = SystemClock.elapsedRealtime()
            var transientHit = false
            val firstFailure = AtomicReference<Exception?>(null)
            // The first failure the server itself answered (a 429, a 5xx):
            // the pill names that over a sibling's timeout, and it proves
            // the server was reached even when nothing was translated.
            val firstServerFailure = AtomicReference<BackendFailure?>(null)
            val succeededSoFar = AtomicInteger(0)
            val perBackend = try {
                coroutineScope {
                    pendingTexts.map { t ->
                        async {
                            if (transientHit) null else {
                                try {
                                    backend.translate(t, source, target).also {
                                        if (chosen) requireAnswered(t, it)
                                        succeededSoFar.incrementAndGet()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    if (e is OnDeviceLlmTransientException && backend is OnDeviceLlmBackend) {
                                        transientHit = true
                                        if (displacedLlmId == null) displacedLlmId = backend.id
                                    }
                                    firstFailure.compareAndSet(null, e)
                                    val failure = classifyBackendFailure(e)
                                    if (serverAnswered(e, failure)) firstServerFailure.compareAndSet(null, failure)
                                    Log.w(
                                        TAG,
                                        "Backend ${backend.id} failed (${e.javaClass.simpleName}: ${e.message}), falling back"
                                    )
                                    null
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                // The pass was cancelled mid fan-out (a hold released, a
                // newer capture). A text that already failed is still a fact
                // about this service: report it, or a 429 that also started a
                // cooldown would go unreported until the cooldown ends. No
                // success is reported: the texts that never finished could
                // have failed.
                val failed = firstFailure.get()
                if (backend.requiresInternet && failed != null) {
                    attempts += OnlineAttempt(
                        backend.id, backend.displayName,
                        firstServerFailure.get() ?: classifyBackendFailure(failed), perTextSentAt,
                        reachedServer = succeededSoFar.get() > 0 || firstServerFailure.get() != null,
                    )
                }
                throw e
            }

            firstFailure.get()?.let { chosenFailure = it }

            // If memory pressure forced a transient bailout, treat the
            // whole backend as failed for THIS batch — drop any results
            // it produced before the throw and fall through with the
            // full pending list. Otherwise, slot successes into results
            // and shrink the pending list to just the failures.
            if (transientHit) {
                trail.add("${backend.displayName}[llm-transient]")
                eventful = true
                continue
            }
            val succeeded = perBackend.count { it != null }
            if (succeeded == perBackend.size) {
                trail.add("${backend.displayName}[ok]")
            } else {
                trail.add("${backend.displayName}[$succeeded/${perBackend.size} ok]")
                eventful = true
                // One ring entry per backend per pass — the batched
                // fan-out can fail N texts on the same underlying cause,
                // and N entries would flush the 20-slot ring for nothing.
                firstFailure.get()?.let { recordFailureDiag(backend, it) }
            }
            // Reported as failed unless it answered EVERY text, the same
            // health rule as the cooldown clear just below, naming a failure
            // the server answered over a transport one. Reached-server is
            // its own fact: the batch's unusable reply, any translated text
            // or any server-answered failure proves it. (A null result on an
            // online backend always comes with a firstFailure: only the
            // on-device transient bailout returns null without one, and that
            // branch has already moved on.)
            if (backend.requiresInternet) {
                val failure = if (succeeded == perBackend.size) null
                    else firstServerFailure.get()
                        ?: firstFailure.get()?.let(::classifyBackendFailure)
                        ?: BackendFailure.BAD_RESPONSE
                attempts += OnlineAttempt(
                    backend.id, backend.displayName, failure, perTextSentAt,
                    reachedServer = batchAnswered || succeeded > 0 || firstServerFailure.get() != null,
                )
            }
            // Only clear cooldown when EVERY attempted text succeeded.
            // Mixed results mean some sibling call recorded a cooldown
            // (429 / 5xx / etc.) — calling recordSuccess() there would
            // erase that signal and hammer the throttled provider on
            // the next waterfall pass. A backend is "healthy" only if
            // it answered every call in this batch. The start timestamp
            // additionally protects against a sibling waterfall pass
            // (different capture, different fan-out) racing this one.
            if (perBackend.all { it != null }) {
                (backend as? Cooldownable)?.recordSuccess(perTextStartedAt)
            }
            val stillPending = mutableListOf<Int>()
            pendingIndices.forEachIndexed { i, origIdx ->
                val translated = perBackend[i]
                if (translated != null) {
                    results[origIdx] = WaterfallResult(
                        text = translated,
                        backend = backend,
                        isDegraded = backend.isDegradedFallback,
                        displacedLlmId = displacedLlmId,
                    )
                } else {
                    stillPending.add(origIdx)
                }
            }
            pendingIndices = stillPending
        }

        val anyDegraded = results.any { it != null && it.isDegraded }
        if (eventful && (anyDegraded || pendingIndices.isNotEmpty())) {
            logWaterfall(if (chosen) "chosen ${texts.size}" else "batch ${texts.size}", trail)
        }

        if (pendingIndices.isNotEmpty()) {
            if (chosen) throw ChosenServiceFailedException(ordered.single(), chosenFailure)
            throw IllegalStateException(
                "All translation backends failed for ${pendingIndices.size} of ${texts.size} texts"
            )
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<WaterfallResult>
    }

    /**
     * The soonest-ending active cooldown among backends USABLE for the
     * pair, or null when none is active. Drives the user-facing
     * degraded messaging. The pair filter matters (DeepL's isUsable
     * excludes Thai, and a cooldown ticking on a DISABLED backend is
     * not the cause of the current degradation); the typed [CooldownCause]
     * rides along so quota and billing states aren't mislabeled as
     * transient rate limits.
     */
    fun earliestCooldownEnd(source: String, target: String): ActiveCooldown? {
        val now = System.currentTimeMillis()
        return orderedBackends().mapNotNull { backend ->
            if (!backend.isUsable(source, target)) return@mapNotNull null
            val cooldownable = backend as? Cooldownable ?: return@mapNotNull null
            val until = cooldownable.unavailableUntil()?.takeIf { it > now }
                ?: return@mapNotNull null
            ActiveCooldown(until, cooldownable.unavailableCause())
        }.minByOrNull { it.retryAt }
    }

    /**
     * Fan the regained-network signal out to every cooldown-capable
     * backend. All registered backends, not just the usable ones: a
     * "Connection failed" cooldown ticking on a disabled instance would
     * otherwise outlive the outage that caused it and greet the user
     * when they re-enable it. Called from the ConnectivityManager
     * callback thread — only [CooldownState]'s own synchronized
     * mutations run here, none of the main-thread-only mutators above.
     * Logs under the forensics tag only when a cooldown was actually
     * cleared, so a healthy network flap stays quiet.
     *
     * The shared translation cache's routing identity is not reconciled
     * here, for the same thread reason: it reconciles at the next waterfall
     * call ([com.playtranslate.translation.GroupTranslator]), the rule a
     * cooldown's natural expiry relies on too, so a frame the cache answers
     * in full can show the entries made while offline until some text
     * misses. A user gesture and the cloud page's health check reconcile at
     * once ([com.playtranslate.cloud.PtCloudService]).
     */
    fun onConnectivityRestored() {
        val cleared = backends.filter { (it as? Cooldownable)?.onConnectivityRestored() == true }
        if (cleared.isNotEmpty()) {
            Log.i(
                DIAG_TAG,
                "connectivity restored: cleared cooldown on " +
                    cleared.joinToString { it.displayName },
            )
        }
    }

    /**
     * Drop [id]'s cooldown state entirely (the services-page enable
     * toggle). Must go through the LIVE backend: a fresh
     * `CooldownState(context, id)` would clear the prefs mirror but
     * leave the registered backend's in-memory cooldown ticking, since
     * a toggle doesn't rebuild the backend. No-op for unknown ids and
     * for backends without cooldown participation.
     */
    fun resetCooldown(id: BackendId) {
        (byId(id) as? Cooldownable)?.resetCooldown()
    }

    /**
     * Backends currently sidelined by a cooldown, one line each — for
     * the log-export header, which stays EMPTY when nothing is wrong
     * (the export is shared by every support flow; translation gets
     * header space only when it has evidence to show). Content-free by
     * construction: display names and durations only — never API keys
     * or base URLs (a custom base URL can itself embed a token). See
     * [com.playtranslate.diagnostics.TranslationDiag].
     */
    fun activeCooldowns(): List<String> {
        val now = System.currentTimeMillis()
        return orderedBackends().mapNotNull { backend ->
            val cooldownable = backend as? Cooldownable ?: return@mapNotNull null
            val cool = cooldownable.unavailableUntil() ?: return@mapNotNull null
            if (cool <= now) return@mapNotNull null
            buildString {
                append(backend.displayName)
                append(": cooldown ").append((cool - now) / 1000).append("s remaining")
                cooldownable.unavailableDescription()?.let {
                    append(" (").append(it).append(')')
                }
            }
        }
    }

    fun close() {
        val toClose = backends
        backends = emptyList()
        orderOverride = null
        toClose.forEach { runCatching { it.close() } }
    }
}
