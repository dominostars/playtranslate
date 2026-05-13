package com.playtranslate.translation

import android.util.Log
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.translategemma.TranslateGemmaTransientException
import kotlinx.coroutines.CancellationException

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
     *  pair (which is the backend the cache should treat as "preferred"
     *  for its identity check), or `"none"` if no online backend is
     *  configured. Returned to [com.playtranslate.TranslationCache.reconcilePreferredBackend]
     *  by [com.playtranslate.CaptureService] before each translation. */
    fun preferredOnlineId(source: String, target: String): BackendId =
        orderedBackends()
            .firstOrNull { !it.isDegradedFallback && it.isUsable(source, target) }
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
        // First on-device LLM (if any) that threw TranslateGemmaTransientException
        // during this call. Propagated into the eventual WaterfallResult so the
        // caller can skip caching the fallback's output — without this, a
        // single low-memory moment would freeze a lower-quality result in the
        // cache until the next pref change.
        var displacedLlmId: BackendId? = null
        for (backend in ordered) {
            if (!backend.isUsable(source, target)) continue
            try {
                val translated = backend.translate(text, source, target)
                return WaterfallResult(
                    text = translated,
                    backend = backend,
                    isDegraded = backend.isDegradedFallback,
                    displacedLlmId = displacedLlmId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is TranslateGemmaTransientException && backend is OnDeviceLlmBackend) {
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

    fun close() {
        val toClose = backends
        backends = emptyList()
        orderOverride = null
        toClose.forEach { runCatching { it.close() } }
    }
}
