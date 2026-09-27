package com.playtranslate.translation

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Why one online service's translate call failed, in the terms the user is
 * told: the translation-error pill words each kind ([TranslationErrors]).
 * Set where the failure is understood, at the backend's throw site through
 * [ClassifiedFailure], never guessed later from an exception message. The
 * HTTP stack's own transport exceptions carry no kind of ours;
 * [classifyBackendFailure] maps those by type.
 */
enum class BackendFailureKind {
    /** The service rejected the key, or none is set. */
    AUTH,
    /** The account has no credit left: OpenAI's `insufficient_quota`,
     *  Anthropic's credit-balance and spend-limit 400s. */
    BILLING,
    /** A per-day quota is used up (Gemini). */
    DAILY_QUOTA,
    /** A per-month quota is used up (DeepL's 456). */
    MONTHLY_QUOTA,
    /** Too many requests: a 429, or Lingva's 403 block. */
    RATE_LIMITED,
    /** The service's servers failed (5xx). */
    SERVER_ERROR,
    /** Any other refusal: a 4xx the user has to fix (a model id the
     *  service doesn't know, a malformed request, a key not scoped to a
     *  workspace). */
    REJECTED,
    /** The service answered with nothing usable: an empty or unparsable
     *  body, or no translation in it. */
    BAD_RESPONSE,
    /** No answer in time. */
    TIMEOUT,
    /** No connection to the server: DNS, refused, TLS, dropped. */
    UNREACHABLE;

    /** True for the two kinds where the service never answered, which a
     *  dead network produces too. Only these can be the device's fault
     *  rather than the service's, so only these are ever attributed to the
     *  connection ([TranslationErrorTracker]). */
    val isTransport: Boolean get() = this == TIMEOUT || this == UNREACHABLE
}

/** One classified failure: its [kind], plus the HTTP status when the
 *  service answered with one ([BackendFailureKind.REJECTED]'s message
 *  shows it; the other kinds only log it). */
data class BackendFailure(val kind: BackendFailureKind, val httpCode: Int? = null) {
    companion object {
        /** Shorthand for the throw sites that got an answer with nothing
         *  usable in it; no status worth showing. */
        val BAD_RESPONSE = BackendFailure(BackendFailureKind.BAD_RESPONSE)
    }
}

/** The failure for an HTTP error status that no more specific branch of a
 *  backend claimed (auth, rate limit, quota, billing have their own): a
 *  5xx is the service's own fault, anything else a refusal. */
fun httpStatusFailure(code: Int): BackendFailure = BackendFailure(
    if (code >= 500) BackendFailureKind.SERVER_ERROR else BackendFailureKind.REJECTED,
    code,
)

/**
 * Implemented by every exception an online backend throws on purpose, so
 * the failure's kind travels with it instead of being inferred from its
 * message downstream. OkHttp's transport exceptions don't implement it;
 * [classifyBackendFailure] covers those.
 */
interface ClassifiedFailure {
    val failure: BackendFailure
}

/**
 * The [BackendFailure] for whatever a backend's translate call threw.
 * Ours carry their own ([ClassifiedFailure]). An [InterruptedIOException]
 * is a [BackendFailureKind.TIMEOUT]: OkHttp's call timeout throws it, and
 * [java.net.SocketTimeoutException] is its subclass. Any other
 * [IOException] can only come from the transport now that every
 * deliberate throw is classified (DNS, connect, TLS, a reset connection),
 * so it is [BackendFailureKind.UNREACHABLE]. Anything else, such as a
 * JSON parser's exception on a garbled body, is
 * [BackendFailureKind.BAD_RESPONSE].
 */
fun classifyBackendFailure(e: Throwable): BackendFailure = when (e) {
    is ClassifiedFailure -> e.failure
    is InterruptedIOException -> BackendFailure(BackendFailureKind.TIMEOUT)
    is IOException -> BackendFailure(BackendFailureKind.UNREACHABLE)
    else -> BackendFailure(BackendFailureKind.BAD_RESPONSE)
}

/**
 * One online service's result in one waterfall pass, as
 * [TranslationBackendRegistry] reports it to
 * [TranslationBackendRegistry.onlineAttemptListener]. [failure] is null
 * when the service translated every text the pass gave it; a pass where it
 * translated some and failed others reports a failure, the same rule the
 * registry's cooldown clearing follows (a service is healthy only when it
 * answered every call), preferring one the server answered (a 429 over a
 * sibling's timeout).
 */
data class OnlineAttempt(
    val serviceId: BackendId,
    /** The user-facing name ([TranslationBackend.displayName]). */
    val serviceName: String,
    val failure: BackendFailure?,
    /** When the request was sent, on [android.os.SystemClock.elapsedRealtime]
     *  (monotonic: a changed device clock can't reorder it). Reports can
     *  arrive out of order (passes overlap, a slow request outlives a
     *  settings change), so [TranslationErrorTracker] orders its evidence by
     *  this, never by arrival. */
    val sentAtMs: Long,
    /** The service's server answered something in this pass: a translation,
     *  or an error reply. Separate from [failure] because a pass can mix
     *  both: one text translated and another timed out still proves the
     *  connection worked. */
    val reachedServer: Boolean,
)
