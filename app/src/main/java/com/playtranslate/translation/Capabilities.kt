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
 * Backends that participate in the waterfall's skip-when-down policy.
 * When the provider signals it's unavailable (429 rate limit, 456
 * monthly quota, OpenAI `insufficient_quota`, persistent network
 * failure), the backend records a future `retryAt` timestamp via its
 * own [com.playtranslate.translation.CooldownState] and the registry
 * waterfall skips this backend in [TranslationBackendRegistry.translate]
 * / [TranslationBackendRegistry.translateBatch] until then.
 *
 * Auth failures (401/403) are NOT cooldown — they stay on the existing
 * "Invalid Key" path because the user has to fix something, not wait.
 *
 * Implementations are pair-agnostic singletons (same as
 * [TranslationBackend]); the state machine lives in [CooldownState]
 * which mirrors to SharedPreferences so a future `retryAt` survives
 * process restarts (no wasted call on every cold launch).
 */
interface Cooldownable {
    /** The epoch-ms timestamp until which the waterfall should skip
     *  this backend, or null when the backend is ready. The registry
     *  checks `it > System.currentTimeMillis()`. */
    fun unavailableUntil(): Long?

    /** Short human-readable reason for the current cooldown
     *  ("Rate limited", "Billing exhausted", "Monthly quota used", etc.).
     *  Returned alongside [unavailableUntil] for the Settings renderer;
     *  null when [unavailableUntil] is null. */
    fun unavailableDescription(): String?

    /** Called by [TranslationBackendRegistry] when this backend wins
     *  the waterfall. [attemptStartedAtMs] is the epoch-ms time at
     *  which the registry began the winning translate call. The
     *  implementation MUST refuse to clear a cooldown whose `setAt`
     *  is newer than [attemptStartedAtMs] — otherwise a stale
     *  in-flight success from a parallel waterfall pass could erase a
     *  cooldown recorded by a later failure. Idempotent for the
     *  already-clean case. */
    fun recordSuccess(attemptStartedAtMs: Long)
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
 * Thrown by translation backends when a response is deliberately
 * rejected for a non-transport reason — HTTP 4xx the user has to fix
 * (bad model id, malformed request), a structurally empty response
 * body, or a missing required field. The cooldown layer's outer
 * IOException catch keys off the type to skip recordNetworkFailure
 * for these throws; only OkHttp-originated connection failures
 * advance the network ladder.
 *
 * Subclass of [java.io.IOException] so the registry's existing
 * fall-through-to-next-backend behaviour still applies — the
 * registry doesn't need to know the difference.
 */
class StructuralFailureException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

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

/**
 * Backends that can verify whether a given API key is valid against
 * their provider — typically via a cheap auth-gated endpoint that
 * doesn't bill tokens (OpenAI's `/v1/models`, Gemini's
 * `/v1beta/models`, etc.).
 *
 * [overrideKey] lets the caller validate a key that hasn't been
 * persisted to prefs yet — e.g. the user just typed it into the
 * settings sub-screen but hasn't hit Save. When null, implementations
 * fall back to their configured keyProvider.
 *
 * Returns:
 *  - [KeyStatus.Ok] when the provider accepts the key
 *  - [KeyStatus.Invalid] when the provider explicitly rejects it
 *    (401/403, or 400 with a key-invalid marker for Gemini)
 *  - [KeyStatus.Unreachable] when we can't tell (network down,
 *    timeout, 5xx, anything that isn't a definitive Invalid). The
 *    settings save path should treat this as "save anyway" rather
 *    than blocking the user — we couldn't *prove* it's wrong.
 */
interface KeyValidator {
    suspend fun validateKey(overrideKey: String? = null): KeyStatus
}
