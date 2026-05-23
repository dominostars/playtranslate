package com.playtranslate.translation

/**
 * Side interfaces that backends opt into to declare extra capabilities
 * beyond the base [TranslationBackend] contract.
 *
 * The capability-interface pattern lets new metadata land without
 * disturbing existing backends — code that wants the capability does a
 * smart-cast (`val q = backend as? QuotaAware`) and skips backends that
 * don't implement it.
 *
 * Future capabilities will land here following the same pattern.
 * For example, a `Downloadable` interface will surface backends with
 * downloadable assets (model files, ngram tables, etc.) along with
 * progress reporting and a `ensureDownloadedFor(source, target)`
 * method. ML Kit's download story stays internal to MlKitBackend for
 * now; if/when the UI needs to surface model-download progress, the
 * interface lands then.
 */

/**
 * A snapshot of API quota usage for a backend (e.g. DeepL's monthly
 * character limit on the free tier). Implementers read this from
 * the provider's usage endpoint; values may be slightly stale.
 *
 * @param used      Characters / units consumed in the current period.
 * @param limit     Quota cap for the current period.
 * @param resetEpochMs Wall-clock millis when the quota resets, or null
 *                  if the provider doesn't expose a reset boundary.
 */
data class QuotaStatus(
    val used: Long,
    val limit: Long,
    val resetEpochMs: Long?,
)

/**
 * Backends with a queryable quota / rate limit. Today only DeepL
 * implements this; ML Kit (offline) and Google's gtx endpoint
 * have no quota concept exposed to clients.
 */
interface QuotaAware {
    /** Returns the current quota snapshot, or null if the provider's
     *  usage endpoint is not yet wired up or the call fails. Callers
     *  should treat null as "unknown" — not as "unlimited". */
    suspend fun currentQuota(): QuotaStatus?
}

/**
 * Backends that can translate a list of strings in ONE remote call.
 * Mirrors the [QuotaAware] side-interface pattern — implementations are
 * smart-cast at call sites (`backend as? BatchTranslator`).
 *
 * Motivated by real-world rate-limit thrashing on parallel fan-out:
 * Gemini's free tier throttles simultaneous requests so a 2-group
 * screen capture often loses ~half its translations to 429s when both
 * fire at once. Batching means one HTTP request → one rate-limit slot
 * → all groups complete together.
 *
 * **All-or-nothing contract.** Implementations MUST return a list whose
 * size equals [texts] and whose order matches it. Any deviation
 * (response parse failure, size mismatch, blank elements where a real
 * result is required, oversize input, HTTP error) MUST throw — the
 * registry catches the throw as "this backend failed for this batch"
 * and falls to the next backend with the full input list. Partial
 * results are not represented; callers depend on positional ordering.
 *
 * **Cancellation.** [CancellationException] from the HTTP call MUST be
 * re-thrown without wrapping, matching the existing single-text
 * [TranslationBackend.translate] contract.
 */
interface BatchTranslator {
    suspend fun translateBatch(
        texts: List<String>,
        source: String,
        target: String,
    ): List<String>
}

/**
 * Thrown by [BatchTranslator] implementations when the remote response
 * could not be parsed in the expected shape, when the returned list
 * length does not match the request, or when a structural input
 * constraint is violated (e.g. DeepL's 50-string cap). The registry
 * catches this and falls through to the next backend.
 */
class BatchParseException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/**
 * Backends that can return the list of models the configured API key
 * is allowed to call. Used by the LLM model-picker activity to render
 * a list of selectable options without baking a hardcoded list per
 * provider. Each implementation hits its own provider's models
 * endpoint and returns bare model ids (no display names, no
 * provider-specific metadata) — the picker only needs the id to save
 * back to prefs.
 *
 * Throws [java.io.IOException] (or any subclass) on network / auth /
 * parse failure. The picker catches these and falls back to a minimal
 * "Custom…"-only list so the user can still type an id manually.
 */
interface ModelLister {
    suspend fun listModels(): List<String>
}
