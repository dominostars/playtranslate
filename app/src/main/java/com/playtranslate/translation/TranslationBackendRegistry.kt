package com.playtranslate.translation

import android.util.Log
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    @Volatile private var backends: List<TranslationBackend> = emptyList()
    @Volatile private var orderOverride: List<BackendId>? = null

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
        orderedBackends()
            .firstOrNull { backend ->
                if (backend.isDegradedFallback) return@firstOrNull false
                if (!backend.isUsable(source, target)) return@firstOrNull false
                val cool = (backend as? Cooldownable)?.unavailableUntil()
                cool == null || cool <= System.currentTimeMillis()
            }
            ?.id
            ?: "none"

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
        // First on-device LLM (if any) that threw OnDeviceLlmTransientException
        // during this call. Propagated into the eventual WaterfallResult so the
        // caller can skip caching the fallback's output — without this, a
        // single low-memory moment would freeze a lower-quality result in the
        // cache until the next pref change.
        var displacedLlmId: BackendId? = null
        for (backend in ordered) {
            if (!backend.isUsable(source, target)) continue
            // Cooldown skip: backends in a parsed/ladder cooldown stay out
            // of rotation until retryAt elapses. The cache layer doesn't
            // need a per-result signal — [preferredOnlineId] excludes
            // cooled-down backends, so the cache's preferred-backend
            // reconcile invalidates stale entries on cooldown enter/exit.
            //
            // `now` is read per-iteration: an earlier backend may have
            // hung for many seconds before failing, during which a
            // shorter cooldown on this one could have elapsed.
            val now = System.currentTimeMillis()
            val coolDown = (backend as? Cooldownable)?.unavailableUntil()
            if (coolDown != null && coolDown > now) {
                Log.d(TAG, "Backend ${backend.id} skipped (cooldown ${coolDown - now}ms remaining)")
                continue
            }
            val attemptStartedAt = System.currentTimeMillis()
            try {
                val translated = backend.translate(text, source, target)
                (backend as? Cooldownable)?.recordSuccess(attemptStartedAt)
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
            }
        }
        throw IllegalStateException("All translation backends failed")
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

        val results = arrayOfNulls<WaterfallResult>(texts.size)
        var pendingIndices: List<Int> = texts.indices.toList()
        // Per-index displacement signal: when a non-batching on-device
        // LLM throws transient on this batch, every text that subsequently
        // succeeds at a fallback backend carries this id so the caller
        // can skip caching the fallback output.
        var displacedLlmId: BackendId? = null

        for (backend in ordered) {
            if (pendingIndices.isEmpty()) break
            if (!backend.isUsable(source, target)) continue
            val coolDown = (backend as? Cooldownable)?.unavailableUntil()
            if (coolDown != null && coolDown > System.currentTimeMillis()) {
                Log.d(TAG, "Backend ${backend.id} skipped (cooldown ${coolDown - System.currentTimeMillis()}ms remaining)")
                continue
            }

            val pendingTexts = pendingIndices.map { texts[it] }

            if (backend is BatchTranslator && pendingTexts.size > 1) {
                val batchStartedAt = System.currentTimeMillis()
                try {
                    val translated = backend.translateBatch(pendingTexts, source, target)
                    if (translated.size != pendingTexts.size) {
                        throw BatchParseException(
                            "Backend ${backend.id} returned ${translated.size} translations for ${pendingTexts.size} inputs"
                        )
                    }
                    (backend as? Cooldownable)?.recordSuccess(batchStartedAt)
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
                    // The provider responded but the shape didn't match —
                    // size mismatch, malformed JSON, undocumented endpoint
                    // changed (relevant for Lingva's gtx multi-q path).
                    // The backend's per-text translate() path is unrelated
                    // to the batch parse and usually still works, so fall
                    // through to the per-text branch below on THIS backend
                    // (no `continue`) instead of skipping to a degraded
                    // fallback like ML Kit. The no-thrashing rule for
                    // rate limits still holds — those throw typed
                    // *RateLimitException, not BatchParseException, and
                    // are caught by the broader Exception branch.
                    Log.w(
                        TAG,
                        "Backend ${backend.id} batch parse failed (${e.message}), retrying per-text on same backend"
                    )
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Backend ${backend.id} batch failed (${e.javaClass.simpleName}: ${e.message}), falling back"
                    )
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
            var transientHit = false
            val perBackend = coroutineScope {
                pendingTexts.map { t ->
                    async {
                        if (transientHit) null else {
                            try {
                                backend.translate(t, source, target)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (e is OnDeviceLlmTransientException && backend is OnDeviceLlmBackend) {
                                    transientHit = true
                                    if (displacedLlmId == null) displacedLlmId = backend.id
                                }
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

            // If memory pressure forced a transient bailout, treat the
            // whole backend as failed for THIS batch — drop any results
            // it produced before the throw and fall through with the
            // full pending list. Otherwise, slot successes into results
            // and shrink the pending list to just the failures.
            if (transientHit) {
                continue
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

        if (pendingIndices.isNotEmpty()) {
            throw IllegalStateException(
                "All translation backends failed for ${pendingIndices.size} of ${texts.size} texts"
            )
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<WaterfallResult>
    }

    fun close() {
        val toClose = backends
        backends = emptyList()
        orderOverride = null
        toClose.forEach { runCatching { it.close() } }
    }
}
