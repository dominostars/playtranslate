package com.playtranslate.cloud

import com.playtranslate.PtJson
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.ClassifiedFailure
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.classifyBackendFailure
import com.playtranslate.translation.httpStatusFailure
import com.playtranslate.translation.nextMidnightPacific
import kotlinx.serialization.decodeFromString
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A PlayTranslate Cloud call that failed before a usable answer arrived: an
 * HTTP error, a transport failure, a part 1 this client cannot read
 * ([PtCloudClient.postFrame]), or a translate call whose answer was not a
 * translation ([PtCloudClient.postTranslate]). The capture falls back to the
 * device reader, a waterfall pass to its next backend; [failure] is what the
 * translation-error pill words. By the time this is thrown the failure's
 * cooldown is recorded (and, on the frame route, its attempt reported; the
 * registry reports the translate route's). The message names the kind and
 * the code only, since the registry logs it: never a text, the key, the
 * address or a body.
 */
class PtCloudReadException(
    override val failure: BackendFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause), ClassifiedFailure

/**
 * What one outcome records on the service's [CooldownState]. The
 * descriptions are [com.playtranslate.translation.GeminiBackend]'s, word for
 * word ("Rate limited", "Server error", "Daily quota used"), since the
 * cooldown UI and forensics read them the same way for every backend.
 */
sealed interface CooldownAction {
    fun applyTo(cooldown: CooldownState)

    /** Nothing to record: a refusal the user or the developer fixes, or a
     *  state (pod starting, deadline) that is not the service failing. */
    data object None : CooldownAction {
        override fun applyTo(cooldown: CooldownState) = Unit
    }

    /** The edge's per-key rate limit: its `Retry-After`, else the
     *  RateLimit ladder. */
    data class RetryAfter(val header: String?) : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordRetryAfterFailure(header, RATE_LIMITED)
    }

    /** A rate limit with no usable retry time: the RateLimit ladder. */
    data object RateLimitLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, RATE_LIMITED)
    }

    /** The service's own failure (a 500 or 502, or the translator's): the
     *  RateLimit ladder, as a server error. */
    data object ServerErrorLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, SERVER_ERROR, CooldownCause.SERVER_ERROR)
    }

    /** The service is switched off (its pod is stopped): the RateLimit
     *  ladder, as not running. It does not start by itself, so every reader
     *  (a capture, a live tier, the camera's acquires) stays on the device
     *  until the ladder's step runs out, the user's gestures reset the
     *  cooldown, or the cloud page's health check finds it running
     *  ([PtCloudService.onHealthRunning]). */
    data object NotRunningLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, NOT_RUNNING, CooldownCause.NOT_RUNNING)
    }

    /** The client key is wrong or missing: the RateLimit ladder, as a bad
     *  key (the account class, shown without a retry time). A key does not
     *  fix itself; saving one resets the cooldown
     *  ([PtCloudService.onCredentialsSaved]). */
    data object BadKeyLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, BAD_KEY, CooldownCause.AUTH)
    }

    /** The key is good but its account has no membership (the edge's 403
     *  `no_entitlement`): the RateLimit ladder, as billing (the account
     *  class, shown without a retry time, in the BILLING kind's existing
     *  wording). Only the account fixes it; the switch and a key save reset
     *  the cooldown, as for a bad key. */
    data object NoMembershipLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, NO_MEMBERSHIP, CooldownCause.BILLING)
    }

    /** A retry time the server stated. */
    data class Until(val retryAtMs: Long, val description: String, val cause: CooldownCause) : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordParsedFailure(retryAtMs, description, cause)
    }

    /** No connection or no answer in time: the forgiving network path
     *  (the first failure in a while is ignored). */
    data class Network(val description: String) : CooldownAction {
        override fun applyTo(cooldown: CooldownState) = cooldown.recordNetworkFailure(description)
    }

    private companion object {
        const val RATE_LIMITED = "Rate limited"
        const val SERVER_ERROR = "Server error"
        const val NOT_RUNNING = "Not running"
        const val BAD_KEY = "Bad key"
        const val NO_MEMBERSHIP = "No membership"
    }
}

/** A classified failure: what the pill says ([failure]) and what the
 *  cooldown records ([cooldown]). */
data class Classified(val failure: BackendFailure, val cooldown: CooldownAction) {
    fun record(state: CooldownState) = cooldown.applyTo(state)
}

/** How a frame's part 2, or a translate call's one answer, settled:
 *  [failure] is null on success. */
sealed interface TranslationOutcome {
    val failure: BackendFailure?

    /** Records this outcome on [cooldown]: a success clears a cooldown
     *  set before [attemptStartedAtMs] (wall clock, the scale the cooldown
     *  keeps), a failure records its action. */
    fun record(cooldown: CooldownState, attemptStartedAtMs: Long)

    /** Status `ok`: the translation of each group the server named, by
     *  group id (on the translate route, by the text's position in the
     *  request). A group it did not name has no translation. */
    data class Translated(val byGroup: Map<Int, String>) : TranslationOutcome {
        override val failure: BackendFailure? get() = null
        override fun record(cooldown: CooldownState, attemptStartedAtMs: Long) =
            cooldown.recordSuccess(attemptStartedAtMs)
    }

    /** Status `skipped`: nothing to translate (no groups, or source equal
     *  to target). The service worked. */
    data object Skipped : TranslationOutcome {
        override val failure: BackendFailure? get() = null
        override fun record(cooldown: CooldownState, attemptStartedAtMs: Long) =
            cooldown.recordSuccess(attemptStartedAtMs)
    }

    /** No translations for this frame; the caller translates locally. */
    data class Failed(val classified: Classified) : TranslationOutcome {
        override val failure: BackendFailure get() = classified.failure
        override fun record(cooldown: CooldownState, attemptStartedAtMs: Long) =
            classified.cooldown.applyTo(cooldown)
    }
}

/**
 * The one failure table for PlayTranslate Cloud, for both of its routes:
 * `POST /v1/frame` ([PtCloudClient.postFrame]) and `POST /v1/translate`
 * ([PtCloudClient.postTranslate], the registry backend [PtCloudBackend]'s
 * call). Every row of the plan's table is one branch of one of these pure
 * functions, so each is reachable by a test that asserts the kind, the
 * status code and the cooldown it records.
 *
 * On the frame route, before part 1 ([classifyHttp], [classifyTransport],
 * [badPart1]) the capture falls back to the device reader; after part 1
 * ([classifyTranslation], [classifyPart2]) the OCR is kept and only the
 * translation falls back. On the translate route [classifyHttp],
 * [classifyTransport] and [classifyTranslation] classify the one answer,
 * and the waterfall falls to its next backend on any failure.
 */
object PtCloudErrors {

    /**
     * A non-200 answer to `POST /v1/frame` or `POST /v1/translate`. Keyed on
     * the status code (the edge's and the origin's `error` strings differ),
     * except 403, the one status two edge answers share:
     *  - 401: the client key is wrong or missing. The RateLimit ladder as a
     *    bad key ([CooldownAction.BadKeyLadder]): a key does not fix itself,
     *    and a read per capture or per camera scene while it holds would be
     *    waste; saving a key resets the cooldown.
     *  - 403 whose body's `error` is `no_entitlement` ([EdgeError]): the key
     *    is good but its account has no membership. BILLING on the RateLimit
     *    ladder ([CooldownAction.NoMembershipLadder]), the account class. Any
     *    other 403 (a body that is not that, or none) is [httpStatusFailure]
     *    with no cooldown, as below.
     *  - 404 with an empty body: the service is switched off (its pod is
     *    stopped and the edge passes the pod host's bare 404 through). The
     *    RateLimit ladder as not running ([CooldownAction.NotRunningLadder]):
     *    the pod does not start by itself; the cloud page's health check
     *    finding it running resets the cooldown.
     *  - 404 with a body, 413, 415, 422: the request is wrong (a bug; a 413
     *    is a frame over the server's cap, a 415 a refusal of the request's
     *    content type, though a frame part is always a JPEG and a translate
     *    body always JSON, a 422 on the translate route a blank or oversize
     *    text, which [PtCloudBackend] never sends; a 404 with a body is also
     *    the edge's answer for a route it does not serve yet), shown with
     *    its code.
     *  - 429: the edge's per-key rate limit, with `Retry-After`.
     *  - 500, 502: the service or its OCR failed, the RateLimit ladder.
     *  - 503: the pod is starting; waiting fixes it, so no cooldown.
     *  - anything else: [httpStatusFailure], no cooldown. That includes 411,
     *    the edge refusing an upload with no `Content-Length` (a multipart
     *    body built from byte arrays always has one, so it is a bug), which
     *    reads as REJECTED.
     */
    fun classifyHttp(code: Int, body: String, retryAfter: String?): Classified = when (code) {
        401 -> Classified(BackendFailure(BackendFailureKind.AUTH, code), CooldownAction.BadKeyLadder)
        403 -> if (edgeError(body) == NO_ENTITLEMENT) {
            Classified(BackendFailure(BackendFailureKind.BILLING, code), CooldownAction.NoMembershipLadder)
        } else {
            Classified(httpStatusFailure(code), CooldownAction.None)
        }
        404 -> if (body.isBlank()) {
            Classified(BackendFailure(BackendFailureKind.NOT_RUNNING, code), CooldownAction.NotRunningLadder)
        } else {
            Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        }
        413, 415, 422 -> Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        429 -> Classified(BackendFailure(BackendFailureKind.RATE_LIMITED, code), CooldownAction.RetryAfter(retryAfter))
        500, 502 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.ServerErrorLadder)
        503 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.None)
        else -> Classified(httpStatusFailure(code), CooldownAction.None)
    }

    /** The edge's `error` string in [body], or null when [body] is not its
     *  JSON error object. */
    private fun edgeError(body: String): String? = try {
        PtJson.lenient.decodeFromString<EdgeError>(body).error
    } catch (e: IllegalArgumentException) {
        // kotlinx's SerializationException is one.
        null
    }

    /** The HTTP stack failed before an answer (on the frame route, before
     *  part 1): a timeout is TIMEOUT, anything else (DNS, connect, TLS, a
     *  dropped connection) UNREACHABLE. Both take the forgiving network
     *  path. */
    fun classifyTransport(e: IOException): Classified {
        val failure = classifyBackendFailure(e)
        val description = if (e is InterruptedIOException) "Timed out" else "Connection failed"
        return Classified(failure, CooldownAction.Network(description))
    }

    /** Part 1 could not be read (not JSON, not `"part":"ocr"`, a schema
     *  other than [PT_CLOUD_SCHEMA_VERSION]): the server answered, with
     *  nothing usable. No cooldown. */
    val badPart1: Classified = Classified(BackendFailure.BAD_RESPONSE, CooldownAction.None)

    /** A translate call's 200 whose body is not a [TranslationPart] (not
     *  JSON, or missing a required field): the server answered, with
     *  nothing usable. No cooldown. */
    val unreadableAnswer: Classified = Classified(BackendFailure.BAD_RESPONSE, CooldownAction.None)

    /** True when [part] is a part 1 (`"part":"ocr"`) in the one schema this
     *  client reads. */
    fun isOcrPart(part: OcrPart): Boolean =
        part.part == PART_OCR && part.schemaVersion == PT_CLOUD_SCHEMA_VERSION

    /** Throws the bad-response [PtCloudReadException] unless [isOcrPart]. */
    fun requireOcrPart(part: OcrPart) {
        if (!isOcrPart(part)) {
            throw PtCloudReadException(
                badPart1.failure, "unreadable part 1 (part ${part.part}, schema_version ${part.schemaVersion})",
            )
        }
    }

    /**
     * The final part's status, per the server's translation-error classes
     * (brief section 4):
     *  - a part that is not `"part":"translation"` at [PT_CLOUD_SCHEMA_VERSION]:
     *    a bad response, no cooldown. ([PtCloudClient] hands over only lines
     *    whose part is `translation`, so from there this is another schema.)
     *  - `ok`: the translations by group id; `translations` absent is a bad
     *    response. `skipped`: nothing to translate. Both are a success.
     *  - `deferred`: the server's translation deadline passed. TIMEOUT, no
     *    cooldown.
     *  - `failed` `rate_limited`: the stated `retry_after_s` when positive,
     *    else the RateLimit ladder. `quota`: DAILY_QUOTA until the stated
     *    time, else the next midnight Pacific. `server_error`: the RateLimit
     *    ladder as a server error. `deadline` (the server's translation
     *    deadline passed): TIMEOUT, no cooldown, as `deferred`. `bad_output`:
     *    BAD_RESPONSE, no cooldown. `config` (the server's own translator
     *    credentials): SERVER_ERROR, no cooldown, never the user's key.
     *  - any other status or class: BAD_RESPONSE, no cooldown.
     * [nowMs] is the wall clock the retry times are computed on.
     */
    fun classifyTranslation(part: TranslationPart, nowMs: Long = System.currentTimeMillis()): TranslationOutcome =
        if (part.part != PART_TRANSLATION || part.schemaVersion != PT_CLOUD_SCHEMA_VERSION) {
            failed(BackendFailure.BAD_RESPONSE, CooldownAction.None)
        } else when (part.translationStatus) {
            "ok" -> part.translations
                ?.let { list -> TranslationOutcome.Translated(list.associate { it.id to it.translation }) }
                ?: failed(BackendFailure.BAD_RESPONSE, CooldownAction.None)
            "skipped" -> TranslationOutcome.Skipped
            "deferred" -> failed(BackendFailure(BackendFailureKind.TIMEOUT), CooldownAction.None)
            "failed" -> classifyTranslationError(part.translationError, nowMs)
            else -> failed(BackendFailure.BAD_RESPONSE, CooldownAction.None)
        }

    private fun classifyTranslationError(err: WireTranslationError?, nowMs: Long): TranslationOutcome {
        val code = err?.httpStatus
        val retryAt = err?.retryAfterS?.takeIf { it > 0 }?.let { nowMs + (it * 1000).toLong() }
        return when (err?.cls) {
            "rate_limited" -> failed(
                BackendFailure(BackendFailureKind.RATE_LIMITED, code),
                if (retryAt != null) {
                    CooldownAction.Until(retryAt, "Rate limited", CooldownCause.RATE_LIMITED)
                } else {
                    CooldownAction.RateLimitLadder
                },
            )
            "quota" -> failed(
                BackendFailure(BackendFailureKind.DAILY_QUOTA, code),
                CooldownAction.Until(
                    retryAt ?: nextMidnightPacific(nowMs), "Daily quota used", CooldownCause.DAILY_QUOTA,
                ),
            )
            "server_error" -> failed(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.ServerErrorLadder)
            "deadline" -> failed(BackendFailure(BackendFailureKind.TIMEOUT, code), CooldownAction.None)
            "bad_output" -> failed(BackendFailure(BackendFailureKind.BAD_RESPONSE, code), CooldownAction.None)
            "config" -> failed(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.None)
            else -> failed(BackendFailure(BackendFailureKind.BAD_RESPONSE, code), CooldownAction.None)
        }
    }

    /**
     * Whatever the read after part 1 produced ([FrameResponse.translation]):
     *  - the final part: [classifyTranslation];
     *  - the body ended before the final part (right after part 1, or after
     *    item lines): treated as `deferred`, TIMEOUT with no cooldown (the
     *    brief's rule);
     *  - the read failed on the transport (a timeout, a reset, a line cut
     *    off): the same, since the service answered part 1 and the frame is
     *    only missing its translations;
     *  - anything else (a line that is not a JSON object, or a final part
     *    that does not decode): a bad response, no cooldown.
     */
    fun classifyPart2(result: Part2Result, nowMs: Long = System.currentTimeMillis()): TranslationOutcome =
        when (result) {
            is Part2Result.Line -> classifyTranslation(result.part, nowMs)
            Part2Result.Ended -> failed(BackendFailure(BackendFailureKind.TIMEOUT), CooldownAction.None)
            is Part2Result.Failed -> if (result.cause is IOException) {
                failed(BackendFailure(BackendFailureKind.TIMEOUT), CooldownAction.None)
            } else {
                failed(BackendFailure.BAD_RESPONSE, CooldownAction.None)
            }
        }

    /** The `part` values this client reads: part 1's, the final part's, and
     *  an item line's. */
    const val PART_OCR = "ocr"
    const val PART_TRANSLATION = "translation"
    const val PART_TRANSLATION_ITEM = "translation_item"

    /** The edge's 403 `error` for a key whose account has no membership. */
    private const val NO_ENTITLEMENT = "no_entitlement"

    private fun failed(failure: BackendFailure, action: CooldownAction) =
        TranslationOutcome.Failed(Classified(failure, action))
}
