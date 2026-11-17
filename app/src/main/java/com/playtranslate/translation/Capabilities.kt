package com.playtranslate.translation

import kotlinx.serialization.json.JsonObject

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
 * implements this; ML Kit (offline) and Google's keyless endpoint
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

    /** Typed cause of the current cooldown, for user-facing message
     *  selection (the description above is hardcoded English and must
     *  never reach localized UI). Null when [unavailableUntil] is null
     *  or the implementation predates cause tracking; consumers treat
     *  null as the transient class. */
    fun unavailableCause(): CooldownCause? = null

    /** Called by [TranslationBackendRegistry] when this backend wins
     *  the waterfall. [attemptStartedAtMs] is the epoch-ms time at
     *  which the registry began the winning translate call. The
     *  implementation MUST refuse to clear a cooldown whose `setAt`
     *  is newer than [attemptStartedAtMs] — otherwise a stale
     *  in-flight success from a parallel waterfall pass could erase a
     *  cooldown recorded by a later failure. Idempotent for the
     *  already-clean case. */
    fun recordSuccess(attemptStartedAtMs: Long)

    /** The device regained a network (fanned out by
     *  [TranslationBackendRegistry.onConnectivityRestored] from
     *  [com.playtranslate.net.NetworkConnectivity]). A connection failure
     *  describes the device, not the provider, so the implementation drops
     *  an active [CooldownCause.CONNECTION_FAILED] cooldown together with
     *  the network ladder's rung and the forgiven-first-failure tracker,
     *  and leaves rate-limit / server-error / quota / billing cooldowns
     *  alone. Returns true when an active cooldown was cleared (the
     *  registry names the backend in its forensics log). Called off the
     *  main thread, from the ConnectivityManager callback. */
    fun onConnectivityRestored(): Boolean

    /** Clean slate on a user gesture that means "try it again now": the
     *  services-page toggle, a credentials change, deletion. Drops the
     *  active cooldown whatever its cause and resets both ladders. A
     *  quota / billing cooldown reset this way costs one re-probe that
     *  records the same state again, which is the price of honouring
     *  the gesture. */
    fun resetCooldown()
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
 * catches this and retries the texts one at a time on the same backend
 * ([TranslationBackendRegistry.translateBatch]), so it is not a failure of
 * the pass by itself; classified as [BackendFailureKind.BAD_RESPONSE] in
 * case one ever ends a call. [serverAnswered] is false only for a refusal
 * before anything was sent (DeepL's cap); every other throw follows a
 * reply, which proves the service's server was reached
 * ([OnlineAttempt.reachedServer]).
 */
class BatchParseException(
    message: String,
    cause: Throwable? = null,
    override val serverAnswered: Boolean = true,
) : java.io.IOException(message, cause), ClassifiedFailure {
    override val failure = BackendFailure(BackendFailureKind.BAD_RESPONSE)
}

/**
 * Thrown by translation backends when a response is deliberately
 * rejected for a non-transport reason — HTTP 4xx the user has to fix
 * (bad model id, malformed request), a 5xx, a structurally empty
 * response body, or a missing required field. The cooldown layer's
 * outer IOException catch keys off the type to skip recordNetworkFailure
 * for these throws; only OkHttp-originated connection failures advance
 * the network ladder.
 *
 * [failure] is required, not defaulted: every throw site states what went
 * wrong, because the translation-error pill words it for the user and a
 * default would silently mislabel any site that forgot.
 *
 * Subclass of [java.io.IOException] so the registry's existing
 * fall-through-to-next-backend behaviour still applies — the
 * registry doesn't need to know the difference.
 */
class StructuralFailureException(
    message: String,
    override val failure: BackendFailure,
    cause: Throwable? = null,
    /** False for a refusal made before any request went out
     *  ([ClassifiedFailure.serverAnswered]); the default, true, is every
     *  refusal a reply earned. */
    override val serverAnswered: Boolean = true,
) : java.io.IOException(message, cause), ClassifiedFailure

/**
 * Backends that can answer a free-form prompt: a system message and a user
 * message in, the model's reply out, untouched. What the Ask section runs
 * on, beside the services that take a question's parts instead
 * ([SentenceAnswerer]). It is a capability of chat LLMs alone: a
 * machine-translation service does not implement it, and neither does a
 * model fine-tuned for translation only.
 *
 * A [complete] call differs from a translation in what its failure may do
 * to the service's standing with the translation waterfall. What the
 * provider ANSWERED counts exactly as it would for a translation (a rate
 * limit, a billing refusal, a server error are facts about the account and
 * the service). A failure of the transport does not (no connection, a
 * timeout): an answer is long and user-started, and its trouble getting
 * through says nothing a translation's own attempt will not find out.
 *
 * **Cancellation.** A cancelled call stops where the backend can stop it
 * (an HTTP call is cancelled; an on-device generation runs to its end) and
 * throws [kotlinx.coroutines.CancellationException], never a failure, and
 * records nothing.
 */
interface PromptCompleter {
    /** Switched on and set up enough to try: a key where one is needed, or
     *  the model on disk and a device that can run it. Not a promise of an
     *  answer (a service in a cooldown still says true): it is what "the
     *  user has an LLM" means. */
    fun canComplete(): Boolean

    /** The model's reply to [user] under [system]. Throws as a translation
     *  call does ([ClassifiedFailure] where the failure is understood). */
    suspend fun complete(system: String, user: String): String

    companion object {
        /** How long an online completer waits for an answer. A reply that
         *  is not streamed sends nothing until it is whole, so this is the
         *  whole generation, reasoning included: far longer than a
         *  translation's wait, which is why answers do not ride the
         *  translation calls' timeouts. */
        const val ANSWER_TIMEOUT_SECONDS = 120L
    }
}

/**
 * A question about a sentence, as the Ask section puts it to the user's
 * services ([TranslationBackendRegistry.complete]), in the two forms a
 * service takes one: its parts, for a service that writes the prompt itself
 * ([SentenceAnswerer]), and the two messages made of those parts, for a
 * model that is put a prompt ([PromptCompleter]). One value, so that both
 * forms are always the same question.
 */
data class SentenceQuestion(
    /** The sentence's language, as the code the app holds ("ja"), never a
     *  display name. */
    val source: String,
    /** The language the answer is written in, as the code the app holds,
     *  with its script where the language has two ("zh-Hant"). */
    val target: String,
    val sentence: String,
    /** The question's wording without the sentence in it: a ready-made
     *  question's, or the user's own words. */
    val question: String,
    /** The prompt made of the parts above. */
    val system: String,
    val user: String,
)

/** What a service said to a [SentenceQuestion]. */
sealed interface SentenceAnswer {
    /** The answer, as the model wrote it. [cut] says the service stopped
     *  the model at its limit on an answer's length, so the text ends
     *  before the answer does; false where the answer ended by itself, and
     *  where the service cannot say (a [PromptCompleter]'s reply is only
     *  its text). */
    data class Text(val text: String, val cut: Boolean = false) : SentenceAnswer

    /** The service judged the question to be about something other than the
     *  sentence, and answers no such question. Its answer, not a failure:
     *  asking again would get the same, and it is the service's word on the
     *  question, so no other service is asked in its place. */
    data object OutOfScope : SentenceAnswer
}

/**
 * Backends that answer a question about a sentence from its parts
 * ([SentenceQuestion]'s pair, sentence and question) and write the prompt
 * themselves: the Ask section's other kind of answerer, beside the chat LLMs
 * that are put a prompt ([PromptCompleter]). PlayTranslate Cloud is one
 * (`POST /v1/ask`). Unlike a prompt completer it answers within a scope of
 * its own and can say a question is outside it ([SentenceAnswer.OutOfScope]).
 *
 * What a failure of [answer] does to the service's standing with the
 * translation waterfall is the backend's own business, as it is for
 * [PromptCompleter.complete]. **Cancellation** is that contract's too: a
 * cancelled call is stopped, throws
 * [kotlinx.coroutines.CancellationException], never a failure, and records
 * nothing.
 */
interface SentenceAnswerer {
    /** Switched on and set up enough to try, as
     *  [PromptCompleter.canComplete] says of a completer. */
    fun canAnswer(): Boolean

    /** The epoch-ms time until which this service takes no question, for a
     *  reason that is its answers' alone (a cap on how many it gives), or
     *  null when there is none. Apart from [Cooldownable.unavailableUntil],
     *  which says when the service as a whole is down, its translations
     *  included: this one keeps a question from being put, a retry's apart
     *  ([TranslationBackendRegistry.complete]), and nothing else. */
    fun answersUnavailableUntil(): Long?

    /** The service's answer to [question]. Throws as a translation call
     *  does ([ClassifiedFailure] where the failure is understood). */
    suspend fun answer(question: SentenceQuestion): SentenceAnswer
}

/**
 * Thrown by [TranslationBackendRegistry.complete] when no service answered.
 * [backend] is the first one that was tried and [cause] what it threw, which
 * is the failure worth telling the user about (their first-choice service);
 * both are null when nothing was tried (every one in a cooldown, or none
 * set up).
 */
class CompletionFailedException(
    val backend: TranslationBackend?,
    cause: Throwable?,
) : Exception("No backend answered the prompt", cause)

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
 * Backends whose provider's model catalog states each model's thinking
 * options ([ThinkingCatalog]). [reportedThinking] asks it about one model:
 * the [ReportedThinking.facts] for it, or null where the provider publishes
 * none or said nothing about this model. Throws [java.io.IOException] on a
 * network, auth or parse failure; [ReportedThinkingRefresher] keeps what it
 * had then.
 */
interface ThinkingReporter {
    suspend fun reportedThinking(model: String): JsonObject?
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
    /** [overrideBaseUrl] (OpenAI-compatible backends only) validates a
     *  user-typed-but-unsaved endpoint; ignored by providers with a fixed URL. */
    suspend fun validateKey(overrideKey: String? = null, overrideBaseUrl: String? = null): KeyStatus
}
