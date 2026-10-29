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
import com.playtranslate.translation.parseIso8601
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
 * A frame the router sent to PlayTranslate Cloud that the reader kept off the
 * wire: the router's own question ([PtCloudService.refusal]), asked again by
 * [PtCloudFrameReader.read] once the frame is encoded and before anything is
 * sent, found [refusal] against it. Not a failure of the service, so no
 * cooldown is recorded and no attempt reported; an IOException, so
 * [com.playtranslate.ocr.FallbackFrameReader] reads the frame on the device.
 * The message names the refusal and nothing else.
 */
class PtCloudRefusedException(val refusal: CloudRefusal) :
    IOException("PlayTranslate Cloud not used for this read: $refusal")

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

    /** The service's own failure (a 500, a 502 `ocr_unavailable`, or the
     *  translator's): the RateLimit ladder, as a server error. */
    data object ServerErrorLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, SERVER_ERROR, CooldownCause.SERVER_ERROR)
    }

    /** The service is switched off (its pod is stopped) or cannot be reached
     *  behind the edge (a 502 that is not the OCR's): the RateLimit
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
     *  ([PtCloudService.saveClientKey]). */
    data object BadKeyLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, BAD_KEY, CooldownCause.AUTH)
    }

    /** The key is good but its account has no membership (the edge's 403
     *  `no_entitlement`, a trial that ended or a membership inactive): the
     *  RateLimit ladder, as billing (the account class, shown without a retry
     *  time). Only the account fixes it; either switch, a key save and an
     *  accepted check of the stored key ([PtCloudService.onKeyAccepted])
     *  reset the cooldown. */
    data object NoMembershipLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, NO_MEMBERSHIP, CooldownCause.BILLING)
    }

    /** The service has closed for good (a `410 service_closed`): the
     *  RateLimit ladder, as closed (the account class, shown without a
     *  retry time). It is the one answer that says the service will not come
     *  back by itself, so every reader stays on the device until the ladder's
     *  step runs out, a gesture resets the cooldown, or the cloud page's
     *  health check finds the service running again
     *  ([PtCloudService.onHealthRunning]). The server's message is never
     *  recorded: it is the page's to show. */
    data object ClosedLadder : CooldownAction {
        override fun applyTo(cooldown: CooldownState) =
            cooldown.recordLadderFailure(CooldownLadder.RateLimit, CLOSED, CooldownCause.CLOSED)
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
        const val CLOSED = "Closed"
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
 * call), and the one reading of the cloud page's calls: the key check
 * ([classifyKeyCheck]), the health check ([classifyHealth]) and the two
 * sign-in calls ([classifyRequestCode], [classifyVerifyCode]). Every row of
 * the plan's tables is one branch of one of these pure functions, so each is
 * reachable by a test that asserts what it maps to (on the two routes, the
 * kind, the status code and the cooldown it records).
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
     * except where one status carries two answers, told apart by the body's
     * `error` word ([EdgeError]):
     *  - 401: the client key is wrong or missing. The RateLimit ladder as a
     *    bad key ([CooldownAction.BadKeyLadder]): a key does not fix itself,
     *    and a read per capture or per camera scene while it holds would be
     *    waste; saving a key resets the cooldown.
     *  - 403 `no_entitlement`: the key is good but its account has no active
     *    entitlement. TRIAL_ENDED when the one that ended last is the trial
     *    ([EdgeError.lastSource] is [SOURCE_TRIAL]), else MEMBERSHIP_INACTIVE
     *    (another, none, or a Worker that sent none), both BILLING on the
     *    RateLimit ladder ([CooldownAction.NoMembershipLadder]), the account
     *    class. Any other 403 (a body that is not that, or none) is
     *    [httpStatusFailure] with no cooldown, as below.
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
     *  - 410 `service_closed`: the service has closed for good. CLOSED on the
     *    RateLimit ladder ([CooldownAction.ClosedLadder]), the account class;
     *    the operator's message is not read here, since the pill never shows
     *    it. Any other 410 is [httpStatusFailure] with no cooldown, as below:
     *    a 410 is closed only with the server's word.
     *  - 429: the edge's per-key rate limit, with `Retry-After`.
     *  - 500: the service failed, the RateLimit ladder.
     *  - 502 `ocr_unavailable`: the OCR sidecar failed on a frame, the
     *    RateLimit ladder as a server error. Any other 502 is the host being
     *    off (the edge's `origin unreachable`, or the tunnel's own 502):
     *    NOT_RUNNING on the RateLimit ladder, as an empty 404 is (ruled
     *    2026-10-28).
     *  - 503: the pod is starting; waiting fixes it, so no cooldown.
     *  - anything else: [httpStatusFailure], no cooldown. That includes 411,
     *    the edge refusing an upload with no `Content-Length` (a multipart
     *    body built from byte arrays always has one, so it is a bug), which
     *    reads as REJECTED.
     */
    fun classifyHttp(code: Int, body: String, retryAfter: String?): Classified = when (code) {
        401 -> Classified(BackendFailure(BackendFailureKind.AUTH, code), CooldownAction.BadKeyLadder)
        403 -> {
            val error = edgeError(body)
            if (error?.error == NO_ENTITLEMENT) {
                val kind = if (error.lastSource == SOURCE_TRIAL) {
                    BackendFailureKind.TRIAL_ENDED
                } else {
                    BackendFailureKind.MEMBERSHIP_INACTIVE
                }
                Classified(BackendFailure(kind, code), CooldownAction.NoMembershipLadder)
            } else {
                Classified(httpStatusFailure(code), CooldownAction.None)
            }
        }
        404 -> if (body.isBlank()) {
            Classified(BackendFailure(BackendFailureKind.NOT_RUNNING, code), CooldownAction.NotRunningLadder)
        } else {
            Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        }
        410 -> if (errorWord(body) == SERVICE_CLOSED) {
            Classified(BackendFailure(BackendFailureKind.CLOSED, code), CooldownAction.ClosedLadder)
        } else {
            Classified(httpStatusFailure(code), CooldownAction.None)
        }
        413, 415, 422 -> Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        429 -> Classified(BackendFailure(BackendFailureKind.RATE_LIMITED, code), CooldownAction.RetryAfter(retryAfter))
        500 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.ServerErrorLadder)
        502 -> if (errorWord(body) == OCR_UNAVAILABLE) {
            Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.ServerErrorLadder)
        } else {
            Classified(BackendFailure(BackendFailureKind.NOT_RUNNING, code), CooldownAction.NotRunningLadder)
        }
        503 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.None)
        else -> Classified(httpStatusFailure(code), CooldownAction.None)
    }

    /**
     * `GET /v1/key`'s answer to the key it carried ("Key check" in
     * docs/api.md), for the cloud page: the stored key's check on a resume
     * and on Check again. [body] is read only for the three answers whose body
     * says something:
     *  - 200: [KeyCheck.Accepted] with the account's `source` and
     *    `entitled_until`; a body without them (an older Worker, a failed
     *    read) or that does not decode is `Accepted(null, null)`, since the
     *    status alone proves the key good.
     *  - 401: [KeyCheck.Refused], whatever the body.
     *  - 403 `no_entitlement`: [KeyCheck.Inactive] with its `last_source` and
     *    `ended_at`, both null when the Worker left them out.
     *  - 410 `service_closed`: [KeyCheck.Closed] with the operator's message.
     *  - anything else, a 403 or a 410 without its word included:
     *    [KeyCheck.NotChecked], no verdict on the key.
     * Dates that do not parse ([parseIso8601]) read as null.
     */
    fun classifyKeyCheck(code: Int, body: () -> String): KeyCheck = when (code) {
        200 -> decoded<KeyOkBody>(body()).let { ok ->
            KeyCheck.Accepted(ok?.source, parseIso8601(ok?.entitledUntil))
        }
        401 -> KeyCheck.Refused
        403 -> edgeError(body())?.takeIf { it.error == NO_ENTITLEMENT }
            ?.let { KeyCheck.Inactive(it.lastSource, parseIso8601(it.endedAt)) }
            ?: KeyCheck.NotChecked
        410 -> edgeError(body())?.takeIf { it.error == SERVICE_CLOSED }
            ?.let { KeyCheck.Closed(it.message) }
            ?: KeyCheck.NotChecked
        else -> KeyCheck.NotChecked
    }

    /**
     * `GET /health`'s answer ("Health" in docs/api.md), for the cloud page's
     * status row: 200 [HealthStatus.Running], 503 [HealthStatus.Starting],
     * 404 [HealthStatus.NotRunning], 502 [HealthStatus.Offline] (the edge
     * cannot reach the host), a 410 `service_closed` [HealthStatus.Closed]
     * with the operator's message, and anything else, a 410 without its word
     * included, [HealthStatus.Other]. [body] is read for the 410 alone.
     */
    fun classifyHealth(code: Int, body: () -> String): HealthStatus = when (code) {
        200 -> HealthStatus.Running
        503 -> HealthStatus.Starting
        404 -> HealthStatus.NotRunning
        502 -> HealthStatus.Offline
        410 -> edgeError(body())?.takeIf { it.error == SERVICE_CLOSED }
            ?.let { HealthStatus.Closed(it.message) }
            ?: HealthStatus.Other(code)
        else -> HealthStatus.Other(code)
    }

    /**
     * `POST /v1/signin`'s answer ("Sign-in" in docs/api.md), row for row:
     * the 200's windows as [CodeRequestResult.Sent] ([SignInSentBody]'s
     * defaults for a field it leaves out, and [SignInAnswer.Unavailable] with
     * no retry time for a 200 that does not decode), the 429
     * `too_many_codes` as [CodeRequestResult.TooManyCodes], the 503
     * `mail_failed` as [CodeRequestResult.MailFailed], and every other answer
     * as verify reads it too ([sharedAnswer]). [retryAfter] is the answer's
     * `Retry-After` header ([retryAfterSeconds]).
     */
    fun classifyRequestCode(code: Int, body: String, retryAfter: String?): CodeRequestResult {
        val error = edgeError(body)
        val retryAfterS = retryAfterSeconds(retryAfter)
        return when {
            code == 200 -> decoded<SignInSentBody>(body)
                ?.let { CodeRequestResult.Sent(it.expiresIn, it.resendAfter) }
                ?: SignInAnswer.Unavailable(null)
            code == 429 && error?.error == TOO_MANY_CODES -> CodeRequestResult.TooManyCodes(retryAfterS)
            code == 503 && error?.error == MAIL_FAILED -> CodeRequestResult.MailFailed(retryAfterS)
            else -> sharedAnswer(code, error, retryAfterS)
        }
    }

    /**
     * `POST /v1/signin/verify`'s answer ("Sign-in" in docs/api.md), row for
     * row: the 200 as [VerifyResult.SignedIn] ([signedIn]), or
     * [VerifyResult.BadAnswer] when it does not decode or its key is not a
     * usable bearer value; the 400 `bad request` (a code or a label the app
     * never sends) as [VerifyResult.BadAnswer]; the 401 `code_invalid` as
     * [VerifyResult.CodeInvalid] with its tries left (0 when it says none);
     * the 403 `account_disabled` as [VerifyResult.Disabled]; and every other
     * answer as the request reads it too ([sharedAnswer]).
     */
    fun classifyVerifyCode(code: Int, body: String, retryAfter: String?): VerifyResult {
        val error = edgeError(body)
        val retryAfterS = retryAfterSeconds(retryAfter)
        return when {
            code == 200 -> signedIn(body) ?: VerifyResult.BadAnswer
            code == 400 && error?.error == BAD_REQUEST -> VerifyResult.BadAnswer
            code == 401 && error?.error == CODE_INVALID -> VerifyResult.CodeInvalid(error.attemptsLeft ?: 0)
            code == 403 && error?.error == ACCOUNT_DISABLED -> VerifyResult.Disabled
            else -> sharedAnswer(code, error, retryAfterS)
        }
    }

    /** The rows the two sign-in routes share: a 410 `service_closed`, any 404
     *  (a Worker from before the routes), any other 429 (the per-address
     *  limit and the request's `cooldown`), a 400 `invalid_email`, a 503
     *  `service_offline`, and anything else, the 503 `auth unavailable`, the
     *  request's `daily_cap` and a 413 or 415 among them, as
     *  [SignInAnswer.Unavailable] with its retry time when it has one. */
    private fun sharedAnswer(code: Int, error: EdgeError?, retryAfterS: Int?): SignInAnswer = when {
        code == 410 && error?.error == SERVICE_CLOSED -> SignInAnswer.Closed(error.message)
        code == 404 -> SignInAnswer.NotAvailable
        code == 429 -> SignInAnswer.RateLimited(retryAfterS)
        code == 400 && error?.error == INVALID_EMAIL -> SignInAnswer.BadAddress
        code == 503 && error?.error == SERVICE_OFFLINE -> SignInAnswer.Offline(retryAfterS)
        else -> SignInAnswer.Unavailable(retryAfterS)
    }

    /** A verify 200 as [VerifyResult.SignedIn], or null when it does not
     *  decode or its key is not a usable bearer value
     *  ([PtCloudConfig.isUsableKey]). The account is read as the key check
     *  reads it: `entitled` is [KeyCheck.Accepted] with the entitlement that
     *  runs longest, else [KeyCheck.Inactive] with the one that ended last. */
    private fun signedIn(body: String): VerifyResult.SignedIn? {
        val ok = decoded<VerifyOkBody>(body) ?: return null
        if (!PtCloudConfig.isUsableKey(ok.key)) return null
        val account = if (ok.entitled) {
            KeyCheck.Accepted(ok.source, parseIso8601(ok.entitledUntil))
        } else {
            KeyCheck.Inactive(ok.lastSource, parseIso8601(ok.endedAt))
        }
        return VerifyResult.SignedIn(ok.key, account)
    }

    /** A `Retry-After` header as the sign-in routes send it, whole seconds,
     *  read as given; null when it is missing or not a number. */
    fun retryAfterSeconds(header: String?): Int? = header?.toIntOrNull()

    /** The `error` word of the edge's JSON error body, or null when [body]
     *  is not one; the only part of a body a log line may carry (a sign-in
     *  refusal's, [PtCloudClient]). */
    fun errorWord(body: String): String? = edgeError(body)?.error

    /** [body] as the edge's JSON error object, or null when it is not one. */
    private fun edgeError(body: String): EdgeError? = decoded(body)

    /** [body] decoded leniently as [T], or null when it does not decode. */
    private inline fun <reified T> decoded(body: String): T? = try {
        PtJson.lenient.decodeFromString<T>(body)
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

    /** Any route's 410 `error` while the service is closed. */
    private const val SERVICE_CLOSED = "service_closed"

    /** A frame's 502 `error` when the OCR sidecar failed; any other 502 is
     *  the host being off. */
    private const val OCR_UNAVAILABLE = "ocr_unavailable"

    /** The sign-in routes' `error` words that the tables tell apart. */
    private const val TOO_MANY_CODES = "too_many_codes"
    private const val MAIL_FAILED = "mail_failed"
    private const val INVALID_EMAIL = "invalid_email"
    private const val SERVICE_OFFLINE = "service_offline"
    private const val BAD_REQUEST = "bad request"
    private const val CODE_INVALID = "code_invalid"
    private const val ACCOUNT_DISABLED = "account_disabled"

    private fun failed(failure: BackendFailure, action: CooldownAction) =
        TranslationOutcome.Failed(Classified(failure, action))
}
