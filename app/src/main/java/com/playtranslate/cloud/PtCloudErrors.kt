package com.playtranslate.cloud

import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.ClassifiedFailure
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.classifyBackendFailure
import com.playtranslate.translation.httpStatusFailure
import com.playtranslate.translation.nextMidnightPacific
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A PlayTranslate Cloud call that failed before part 1 (the OCR groups)
 * arrived: an HTTP error, a transport failure, or a part 1 this client
 * cannot read. The capture falls back to the device reader; [failure] is
 * what the translation-error pill words. By the time this is thrown the
 * failure's cooldown is recorded and its attempt reported
 * ([PtCloudClient.postFrame]).
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
    }
}

/** A classified failure: what the pill says ([failure]) and what the
 *  cooldown records ([cooldown]). */
data class Classified(val failure: BackendFailure, val cooldown: CooldownAction) {
    fun record(state: CooldownState) = cooldown.applyTo(state)
}

/** How part 2 settled, for the reader: [failure] is null on success. */
sealed interface TranslationOutcome {
    val failure: BackendFailure?

    /** Records this outcome on [cooldown]: a success clears a cooldown
     *  set before [attemptStartedAtMs] (wall clock, the scale the cooldown
     *  keeps), a failure records its action. */
    fun record(cooldown: CooldownState, attemptStartedAtMs: Long)

    /** Status `ok`: the translation of each group the server named, by
     *  group id. A group it did not name has no translation. */
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
 * The one failure table for PlayTranslate Cloud. Every row of the plan's
 * table is one branch of one of these pure functions, so each is reachable
 * by a test that asserts the kind, the status code and the cooldown it
 * records; the future translation-only backend reuses them.
 *
 * Before part 1 ([classifyHttp], [classifyTransport], [badPart1]) the
 * capture falls back to the device reader. After part 1
 * ([classifyTranslation], [classifyPart2]) the OCR is kept and only the
 * translation falls back.
 */
object PtCloudErrors {

    /**
     * A non-200 answer to `POST /v1/frame`. Keyed on the status code (the
     * edge's and the origin's `error` strings differ):
     *  - 401: the client key is wrong or missing, a configuration error,
     *    never a cooldown.
     *  - 404 with an empty body: the service is switched off (its pod is
     *    stopped and the edge passes the pod host's bare 404 through). No
     *    cooldown and no retry: it answers again the moment it is started.
     *  - 404 with a body, 413, 415, 422: the request is wrong (a bug; a 413
     *    is a frame over the server's cap), shown with its code.
     *  - 429: the edge's per-key rate limit, with `Retry-After`.
     *  - 500, 502: the service or its OCR failed, the RateLimit ladder.
     *  - 503: the pod is starting; waiting fixes it, so no cooldown.
     *  - anything else: [httpStatusFailure], no cooldown.
     */
    fun classifyHttp(code: Int, body: String, retryAfter: String?): Classified = when (code) {
        401 -> Classified(BackendFailure(BackendFailureKind.AUTH, code), CooldownAction.None)
        404 -> if (body.isBlank()) {
            Classified(BackendFailure(BackendFailureKind.NOT_RUNNING, code), CooldownAction.None)
        } else {
            Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        }
        413, 415, 422 -> Classified(BackendFailure(BackendFailureKind.REJECTED, code), CooldownAction.None)
        429 -> Classified(BackendFailure(BackendFailureKind.RATE_LIMITED, code), CooldownAction.RetryAfter(retryAfter))
        500, 502 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.ServerErrorLadder)
        503 -> Classified(BackendFailure(BackendFailureKind.SERVER_ERROR, code), CooldownAction.None)
        else -> Classified(httpStatusFailure(code), CooldownAction.None)
    }

    /** The HTTP stack failed before part 1: a timeout is TIMEOUT, anything
     *  else (DNS, connect, TLS, a dropped connection) UNREACHABLE. Both take
     *  the forgiving network path. */
    fun classifyTransport(e: IOException): Classified {
        val failure = classifyBackendFailure(e)
        val description = if (e is InterruptedIOException) "Timed out" else "Connection failed"
        return Classified(failure, CooldownAction.Network(description))
    }

    /** Part 1 could not be read (not JSON, not `"part":"ocr"`, a schema
     *  other than [PT_CLOUD_SCHEMA_VERSION]): the server answered, with
     *  nothing usable. No cooldown. */
    val badPart1: Classified = Classified(BackendFailure.BAD_RESPONSE, CooldownAction.None)

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
     * Part 2's status, per the server's translation-error classes (brief
     * section 4):
     *  - a line that is not `"part":"translation"` at [PT_CLOUD_SCHEMA_VERSION]:
     *    a bad response, no cooldown.
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
     * Whatever part 2's read produced ([FrameResponse.translation]):
     *  - a line: [classifyTranslation];
     *  - the connection ended after part 1 with no second line: treated as
     *    `deferred`, TIMEOUT with no cooldown (the brief's rule);
     *  - the read failed on the transport (a timeout, a reset, a line cut
     *    off): the same, since the service answered part 1 and the frame is
     *    only missing its translations;
     *  - anything else (a second line that is not a translation part): a bad
     *    response, no cooldown.
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

    /** Part 1's and part 2's `part` values. */
    const val PART_OCR = "ocr"
    const val PART_TRANSLATION = "translation"

    private fun failed(failure: BackendFailure, action: CooldownAction) =
        TranslationOutcome.Failed(Classified(failure, action))
}
