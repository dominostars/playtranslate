package com.playtranslate.translation

import android.util.Log
import com.playtranslate.diagnostics.TranslationDiag
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        // Trail lists only backends the pass ENGAGED (attempted, failed,
        // or cooldown-skipped) — unusable tiers are omitted to keep the
        // line short. Logged only when something EVENTFUL degraded the
        // pass (a failure or a cooldown skip): a user whose only backend
        // IS the degraded fallback would otherwise emit one line per live
        // cycle, rolling logcat past the diagnostics we want to keep.
        val trail = ArrayList<String>(ordered.size)
        var eventful = false
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
                trail.add("${backend.displayName}[cooldown ${(coolDown - now) / 1000}s]")
                eventful = true
                continue
            }
            val attemptStartedAt = System.currentTimeMillis()
            try {
                val translated = backend.translate(text, source, target)
                (backend as? Cooldownable)?.recordSuccess(attemptStartedAt)
                trail.add("${backend.displayName}[ok]")
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
            }
        }
        logWaterfall("single ALL FAILED", trail)
        throw IllegalStateException("All translation backends failed")
    }

    /** Push one backend failure into [TranslationDiag]'s persisted ring.
     *  Content-free by construction: class name, HTTP code, and cooldown
     *  timestamp only — never the exception MESSAGE (transport messages
     *  embed request URLs, and Lingva URLs carry captured text). */
    private fun recordFailureDiag(backend: TranslationBackend, e: Exception) {
        TranslationDiag.recordFailure(
            backendName = backend.displayName,
            exceptionClass = e.javaClass.simpleName,
            httpCode = (e as? LingvaRateLimitException)?.httpCode,
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

        val results = arrayOfNulls<WaterfallResult>(texts.size)
        var pendingIndices: List<Int> = texts.indices.toList()
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
            if (!backend.isUsable(source, target)) continue
            val coolDown = (backend as? Cooldownable)?.unavailableUntil()
            if (coolDown != null && coolDown > System.currentTimeMillis()) {
                Log.d(TAG, "Backend ${backend.id} skipped (cooldown ${coolDown - System.currentTimeMillis()}ms remaining)")
                trail.add("${backend.displayName}[cooldown ${(coolDown - System.currentTimeMillis()) / 1000}s]")
                eventful = true
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
                    trail.add("${backend.displayName}[ok]")
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
                    trail.add("${backend.displayName}[batch-parse, per-text retry]")
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Backend ${backend.id} batch failed (${e.javaClass.simpleName}: ${e.message}), falling back"
                    )
                    trail.add("${backend.displayName}[batch ${e.javaClass.simpleName}]")
                    eventful = true
                    recordFailureDiag(backend, e)
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
            val firstFailure = AtomicReference<Exception?>(null)
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
                                firstFailure.compareAndSet(null, e)
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
            logWaterfall("batch ${texts.size}", trail)
        }

        if (pendingIndices.isNotEmpty()) {
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
