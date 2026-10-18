package com.playtranslate.cloud

import android.os.SystemClock
import android.util.Log
import com.playtranslate.PtJson
import com.playtranslate.net.PtHttp
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.HttpTimingLogger
import com.playtranslate.translation.OnlineAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What reading the lines after part 1 produced. Never an exception: the
 *  reader turns each case into a [TranslationOutcome] through
 *  [PtCloudErrors.classifyPart2]. */
sealed interface Part2Result {
    /** The final part (`"part":"translation"`), decoded. */
    data class Line(val part: TranslationPart) : Part2Result
    /** The body ended before the final part: right after part 1, or after
     *  item lines. */
    data object Ended : Part2Result
    /** The read or the decode failed; [cause]'s class is loggable, its
     *  message is not (it can echo the body). */
    data class Failed(val cause: Throwable) : Part2Result
}

/**
 * A frame whose part 1 arrived. [translation] reads the lines after part 1,
 * up to the final part, as a child of the coroutine that called
 * [PtCloudClient.postFrame]: that caller's job waits for it and cancelling
 * that job cancels the HTTP call; a caller that will not use part 2 cancels
 * [translation]. It resolves to a [Part2Result] and never throws, apart from
 * [CancellationException]. Item lines on the way are timed for the clock
 * line and handed on as they arrive through [items]; what is done with them
 * is the reader's business.
 *
 * The two stamps are taken right before the request went out, on two clocks
 * kept apart on purpose: [sentAtMs] on [SystemClock.elapsedRealtime] for the
 * [OnlineAttempt] (the error tracker orders reports by it), and
 * [attemptStartedAtMs] on the wall clock for
 * [com.playtranslate.translation.CooldownState.recordSuccess], which compares
 * it with the wall-clock time a cooldown was set. Mixing them would never
 * clear a cooldown.
 */
class FrameResponse(
    val ocr: OcrPart,
    val translation: Deferred<Part2Result>,
    /** The item lines read so far after part 1, by group id (the last one,
     *  should a group repeat): grows as the read passes each item, and keeps
     *  its last value once the read ends. Provisional: the final part is
     *  authoritative. Empty when the frame asked for none or the server sent
     *  none. */
    val items: StateFlow<Map<Int, String>>,
    val sentAtMs: Long,
    val attemptStartedAtMs: Long,
)

/** `GET /health`, for the settings page only; capture never polls it. */
sealed interface HealthStatus {
    /** 200: ready. */
    data object Running : HealthStatus
    /** 503: the pod is up but OCR is not (warming, or its sidecar died). */
    data object Starting : HealthStatus
    /** 404: the service is switched off. */
    data object NotRunning : HealthStatus
    /** No answer (DNS, connect, TLS, timeout). */
    data object Unreachable : HealthStatus
    data class Other(val code: Int) : HealthStatus
}

/**
 * The HTTP client for PlayTranslate Cloud's `POST /v1/frame`,
 * `POST /v1/translate`, `GET /v1/warm` and `GET /health`, all under
 * [BASE_URL] (the contract: docs/api.md and docs/client-integration-brief.md
 * in the playtranslate-cloud repository).
 *
 * On the frame route reporting is split at part 1. [postFrame] records the
 * cooldown and reports the [OnlineAttempt] of any failure before part 1
 * itself, then throws [PtCloudReadException]. Once part 1 is back it reports
 * nothing: the reader decides how the frame went (part 2's outcome, or a
 * caller that let part 2 go), so recording the success and reporting the
 * attempt are its job, with [FrameResponse]'s stamps. On the translate route
 * [postTranslate] records every failure and reports nothing: its caller is a
 * registry backend ([PtCloudBackend]), and the registry reports an attempt
 * for every online backend it calls and records the success.
 *
 * A cancelled call (the user released the hold, a newer capture superseded
 * this one) records nothing and reports nothing: it is not a fact about the
 * service. Cancellation is read from the calling coroutine, never from
 * `Call.isCanceled()`: OkHttp's own call timeout cancels the call too, and a
 * timeout is a failure (TIMEOUT), not the user's gesture.
 *
 * Logs carry the frame id the caller chose, status codes and names, byte,
 * text and group counts, sizes, timings and exception class names only;
 * never the key, the URL, a body, or recognized or translated text. Each
 * frame that reaches part 1 logs two clock lines ([PtCloudClocks]): one at
 * part 1 and one when the read after it ends, unless that read was
 * cancelled; each translate call that was not cancelled logs one.
 */
class PtCloudClient(
    private val config: PtCloudConfig,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
) {

    private val client: OkHttpClient by lazy(clientFactory)

    /**
     * The translate route's client: [client] rebuilt with the route's own
     * deadlines, so it keeps the frame client's connection pool, dispatcher
     * and timing listener (a translate call and a frame reuse one
     * connection), and the frame client's timeouts stay as they are. The
     * route's server deadline scales with the call's size up to 20 s
     * ("Translation only" in docs/api.md), where the frame client's 8 s read
     * would cut a long call off as a transport TIMEOUT and climb the network
     * ladder for both routes: the 22 s read and the 25 s whole call sit
     * above that cap.
     */
    private val translateClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(TRANSLATE_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TRANSLATE_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** When the last [warm] call went out ([SystemClock.elapsedRealtime]), 0
     *  when none has since the previous frame. Each frame takes it and
     *  clears it in one step, so a warm sent while a frame reads it is kept
     *  for the next frame instead of lost. */
    private val warmSentAtMs = AtomicLong(0L)

    /**
     * Uploads [frame] with [meta] and returns once part 1 (the OCR groups)
     * has arrived and decoded; the lines after it keep reading in
     * [FrameResponse.translation], a child of the calling coroutine's job,
     * which adds each item line to [FrameResponse.items] as it passes it.
     * Call this from the job meant to own part 2, not inside a scoped
     * builder (`withContext`, `coroutineScope`, `withTimeout`): a scope
     * waits for its children, so it would return only after part 2.
     *
     * Throws [PtCloudReadException] for any failure before part 1, after
     * recording its cooldown on [PtCloudService.cooldown] and reporting its
     * attempt through [PtCloudService.report] (the failure table:
     * [PtCloudErrors.classifyHttp], [PtCloudErrors.classifyTransport],
     * [PtCloudErrors.badPart1]). Throws [CancellationException], with nothing
     * recorded or reported, when the calling coroutine was cancelled.
     */
    suspend fun postFrame(frame: EncodedFrame, meta: FrameMeta): FrameResponse {
        // Captured here, outside any withContext: part 2's reader is a child
        // of the CALLER's job, so the caller waits for it and cancels it.
        val callerContext = currentCoroutineContext()
        val cooldown = PtCloudService.cooldown
        val clock = HttpCallClock()
        val call = client.newCall(frameRequest(frame, meta, clock))
        // A response read to part 1 when the caller was cancelled on the way
        // back must still be closed; this holds it until it is handed off.
        var opened: Opened? = null
        val sentAtMs = SystemClock.elapsedRealtime()
        val attemptStartedAtMs = System.currentTimeMillis()
        val warmAtMs = warmSentAtMs.getAndSet(0L)
        val part1: Opened = try {
            withContext(Dispatchers.IO) {
                blockingOn(call) { openPart1(call).also { opened = it } }
            }
        } catch (e: CancellationException) {
            opened?.response?.close()
            call.cancel()
            throw e
        } catch (e: IOException) {
            if (!callerContext.isActive) {
                throw CancellationException("PlayTranslate Cloud frame cancelled").apply { initCause(e) }
            }
            val refused = e as? Refused
            val classified = refused?.classified ?: PtCloudErrors.classifyTransport(e)
            classified.record(cooldown)
            PtCloudService.report(
                OnlineAttempt(
                    PtCloudService.ID, PtCloudService.DISPLAY_NAME, classified.failure, sentAtMs,
                    reachedServer = refused != null,
                ),
            )
            Log.w(
                TAG,
                "frame failed kind=${classified.failure.kind} code=${classified.failure.httpCode} " +
                    "bytes=${frame.bytes.size} ms=${SystemClock.elapsedRealtime() - sentAtMs} " +
                    "(${(refused?.cause ?: e).javaClass.simpleName})",
            )
            throw PtCloudReadException(classified.failure, "PlayTranslate Cloud ${classified.failure.kind}", e)
        }
        Log.i(
            TAG,
            PtCloudClocks.part1Line(
                frameId = meta.frameId,
                format = frame.format,
                bytes = frame.bytes.size,
                width = frame.width,
                height = frame.height,
                encodeMs = frame.encodeMs,
                headersMs = part1.headersAtMs - sentAtMs,
                part1Ms = part1.part1AtMs - sentAtMs,
                groups = part1.part.groups.size,
                translated = part1.part.groups.count { !it.translation.isNullOrBlank() },
                connectionReused = clock.connectionReused,
                uploadMs = clock.uploadMs,
                warmLeadMs = if (warmAtMs == 0L) null else sentAtMs - warmAtMs,
                edgeUploadHeader = part1.edgeUploadHeader,
                edgeHeader = part1.edgeHeader,
                timings = part1.part.timings,
            ),
        )

        val response = part1.response
        val items = MutableStateFlow<Map<Int, String>>(emptyMap())
        val translation = CoroutineScope(callerContext + Dispatchers.IO).async {
            val itemLines = ItemLines()
            try {
                val result = try {
                    blockingOn(call) { readFinalPart(response.body.source(), itemLines, items, sentAtMs) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Never fail this child: a failed async would cancel the
                    // caller's job (on the hold path, crash the app).
                    if (!isActive) {
                        throw CancellationException("PlayTranslate Cloud part 2 cancelled").apply { initCause(t) }
                    }
                    Part2Result.Failed(t)
                }
                // Reached only by a read that ended by itself: a cancelled one
                // threw above and logs nothing. finalLine is total, so this
                // cannot fail the child.
                Log.i(
                    TAG,
                    PtCloudClocks.finalLine(
                        meta.frameId, result, itemLines, items.value, SystemClock.elapsedRealtime() - sentAtMs,
                    ),
                )
                result
            } finally {
                response.close()
            }
        }
        // A caller cancelled between part 1 and this launch cancels the
        // child before its body runs, so the finally above never would.
        translation.invokeOnCompletion { response.close() }
        return FrameResponse(part1.part, translation, items.asStateFlow(), sentAtMs, attemptStartedAtMs)
    }

    /**
     * `POST {BASE_URL}/v1/translate` ("Translation only" in docs/api.md):
     * [request]'s texts in, one [TranslationPart] out, its translations
     * keyed by each text's position. Returns only
     * [TranslationOutcome.Translated] (whether it names every position is
     * the caller's check) or [TranslationOutcome.Skipped].
     *
     * The one recorder of this route's failures: a non-200
     * ([PtCloudErrors.classifyHttp]), a transport failure
     * ([PtCloudErrors.classifyTransport]), a 200 that does not decode
     * ([PtCloudErrors.unreadableAnswer]) and a `deferred` or `failed` answer
     * ([PtCloudErrors.classifyTranslation]) are each recorded on
     * [PtCloudService.cooldown] here, the state the frame route records on
     * (the server counts frames and translate calls against one budget and
     * one rate bucket, and a refused key or a stopped pod is one fact for
     * both), then thrown as [PtCloudReadException]. A success records
     * nothing here: the registry's `recordSuccess` does, with its own
     * attempt stamp. Unlike [postFrame], this reports no [OnlineAttempt]:
     * the registry reports one for every online backend it calls, so a
     * report here would count the call twice.
     *
     * The response is read and closed inside the call's blocking step, so
     * none is ever left open. Throws [CancellationException], with nothing
     * recorded, when the calling coroutine was cancelled. Logs one clock line
     * per call that was not cancelled ([PtCloudClocks.translateLine]).
     */
    suspend fun postTranslate(request: TranslateRequest): TranslationOutcome {
        val callerContext = currentCoroutineContext()
        val cooldown = PtCloudService.cooldown
        val clock = HttpCallClock()
        val call = translateClient.newCall(translateRequest(request, clock))
        val sentAtMs = SystemClock.elapsedRealtime()
        val part: TranslationPart = try {
            withContext(Dispatchers.IO) {
                blockingOn(call) { call.execute().use(::readTranslation) }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            if (!callerContext.isActive) {
                throw CancellationException("PlayTranslate Cloud translate cancelled").apply { initCause(e) }
            }
            val refused = e as? Refused
            val classified = refused?.classified ?: PtCloudErrors.classifyTransport(e)
            val status = refused?.let { r -> r.classified.failure.httpCode?.let { "http$it" } ?: UNREADABLE }
                ?: e.javaClass.simpleName
            logTranslate(request, sentAtMs, status, clock)
            throw recordedFailure(classified, cooldown, e)
        }
        logTranslate(request, sentAtMs, part.translationStatus, clock)
        val outcome = PtCloudErrors.classifyTranslation(part)
        if (outcome is TranslationOutcome.Failed) throw recordedFailure(outcome.classified, cooldown, null)
        return outcome
    }

    /** `GET {BASE_URL}/health`, no auth. [HealthStatus.Unreachable] for a
     *  transport failure; [CancellationException] when the caller was
     *  cancelled. */
    suspend fun health(): HealthStatus = withContext(Dispatchers.IO) {
        val url = BASE_URL.newBuilder().addPathSegments("health").build()
        val call = client.newCall(Request.Builder().url(url).get().build())
        val code = try {
            blockingOn(call) { call.execute().use { it.code } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!isActive) {
                throw CancellationException("PlayTranslate Cloud health cancelled").apply { initCause(e) }
            }
            Log.i(TAG, "health unreachable (${e.javaClass.simpleName})")
            return@withContext HealthStatus.Unreachable
        }
        Log.i(TAG, "health status=$code")
        when (code) {
            200 -> HealthStatus.Running
            503 -> HealthStatus.Starting
            404 -> HealthStatus.NotRunning
            else -> HealthStatus.Other(code)
        }
    }

    /**
     * `GET {BASE_URL}/v1/warm` ("Pre-warm" in docs/api.md): wakes the radio,
     * the pooled connection, the edge's path to the origin and the origin's
     * translator connection before a frame is uploaded. Fire and forget: it
     * returns at once, on the same client (and so the same connection pool)
     * as [postFrame], and its answer is only closed and logged. A 204, a 404
     * (the service off), a 401 or a transport failure all record no cooldown
     * and report no attempt: the frame that follows is what says how the
     * service is.
     *
     * Sends nothing when [PtCloudConfig.warmEnabled] is off or when there is
     * no usable key ([PtCloudConfig.hasKey]). Otherwise stamps when it went
     * out, so the next frame's clock line reports the head start. Reading
     * the key decrypts it, so call this off the main thread.
     */
    fun warm() {
        if (!config.warmEnabled) return
        if (!config.hasKey) return
        val request = Request.Builder()
            .url(BASE_URL.newBuilder().addPathSegments("v1/warm").build())
            .bearer()
            .get()
            .build()
        val sentAtMs = SystemClock.elapsedRealtime()
        warmSentAtMs.set(sentAtMs)
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val code = response.use { it.code }
                Log.i(TAG, "warm status=$code ms=${SystemClock.elapsedRealtime() - sentAtMs}")
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.i(TAG, "warm failed (${e.javaClass.simpleName})")
            }
        })
    }

    /** The multipart request exactly as the brief specifies it: `frame` a
     *  file part named and typed by the frame's [FrameFormat], `meta` a
     *  plain form field (a file part named `meta` is ignored and the server
     *  answers 422). Both parts are byte arrays of known length, so OkHttp
     *  sends the `Content-Length` the edge requires (411 without one).
     *  [clock] rides along as the request's tag for the HTTP stack to fill. */
    private fun frameRequest(frame: EncodedFrame, meta: FrameMeta, clock: HttpCallClock): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "frame", frame.format.filename, frame.bytes.toRequestBody(frame.format.mediaType.toMediaType()),
            )
            .addFormDataPart("meta", PtJson.lenient.encodeToString(FrameMeta.serializer(), meta))
            .build()
        return Request.Builder()
            .url(BASE_URL.newBuilder().addPathSegments("v1/frame").build())
            .bearer()
            .header("Accept", NDJSON)
            .tag(HttpCallClock::class.java, clock)
            .post(body)
            .build()
    }

    /** The translate request: [translateBody] typed `application/json`
     *  (any other type is a 415), a byte array, so its length is known. No
     *  `Accept`: the route then answers one JSON object, never item lines.
     *  [clock] rides along as the request's tag for the HTTP stack to fill,
     *  as on a frame. */
    private fun translateRequest(request: TranslateRequest, clock: HttpCallClock): Request {
        val body = translateBody(request).toRequestBody(JSON.toMediaType())
        return Request.Builder()
            .url(BASE_URL.newBuilder().addPathSegments("v1/translate").build())
            .bearer()
            .tag(HttpCallClock::class.java, clock)
            .post(body)
            .build()
    }

    /** The client key as the bearer token, the one place every
     *  authenticated request ([frameRequest], [translateRequest], [warm])
     *  takes it from. Reading the key decrypts it. */
    private fun Request.Builder.bearer(): Request.Builder =
        header("Authorization", "Bearer ${config.clientKey}")

    /** Reads a translate call's answer, blocking: the decoded body of a 200,
     *  else [Refused] with its classification (a non-200 by its status, a
     *  200 that is not a [TranslationPart] as [PtCloudErrors.unreadableAnswer]).
     *  The caller closes [response]. */
    private fun readTranslation(response: Response): TranslationPart {
        if (response.code != 200) throw httpRefusal(response)
        val body = response.body.string()
        return try {
            PtJson.lenient.decodeFromString<TranslationPart>(body)
        } catch (e: IllegalArgumentException) {
            // kotlinx's SerializationException is one.
            throw Refused(PtCloudErrors.unreadableAnswer, "answer did not decode", e)
        }
    }

    /** Records [classified] on [cooldown] and returns the exception the
     *  translate route throws for it; its message names the kind and the
     *  code only. */
    private fun recordedFailure(classified: Classified, cooldown: CooldownState, cause: Throwable?): PtCloudReadException {
        classified.record(cooldown)
        val failure = classified.failure
        val code = failure.httpCode?.let { " $it" }.orEmpty()
        return PtCloudReadException(failure, "PlayTranslate Cloud translate ${failure.kind}$code", cause)
    }

    private fun logTranslate(request: TranslateRequest, sentAtMs: Long, status: String, clock: HttpCallClock) {
        Log.i(
            TAG,
            PtCloudClocks.translateLine(
                frameId = request.frameId,
                texts = request.texts.size,
                codePoints = request.texts.sumOf(PtCloudBatches::codePoints),
                totalMs = SystemClock.elapsedRealtime() - sentAtMs,
                status = status,
                connectionReused = clock.connectionReused,
            ),
        )
    }

    /** Sends the request and reads part 1, blocking. A non-200 status or an
     *  unreadable part 1 throws [Refused] with its classification; the
     *  response is closed on every path except success, where it is handed
     *  back open for part 2, with the moments the headers and part 1
     *  arrived and the edge's two clock headers. */
    private fun openPart1(call: Call): Opened {
        val response = call.execute()
        val headersAtMs = SystemClock.elapsedRealtime()
        var handedOff = false
        try {
            if (response.code != 200) throw httpRefusal(response)
            val line = response.body.source().readUtf8LineStrict()
            val part = try {
                PtJson.lenient.decodeFromString<OcrPart>(line)
            } catch (e: IllegalArgumentException) {
                // kotlinx's SerializationException is one.
                throw Refused(PtCloudErrors.badPart1, "part 1 did not decode", e)
            }
            if (!PtCloudErrors.isOcrPart(part)) throw Refused(PtCloudErrors.badPart1, "not a schema 1 part 1")
            val part1AtMs = SystemClock.elapsedRealtime()
            handedOff = true
            return Opened(
                response, part, headersAtMs, part1AtMs,
                edgeUploadHeader = response.header(EDGE_UPLOAD_HEADER),
                edgeHeader = response.header(EDGE_HEADER),
            )
        } finally {
            if (!handedOff) response.close()
        }
    }

    /**
     * Reads the lines after part 1 until the final part, blocking:
     *  - `"part":"translation"`: decoded and returned as [Part2Result.Line];
     *  - `"part":"translation_item"`: its arrival is stamped into
     *    [itemLines], its text goes into [items] under its group id (in place
     *    of an earlier item for the same group), and the read goes on; an
     *    item that does not decode (no `id`, say) is skipped like an unknown
     *    part, since items are provisional and one cannot fail the frame;
     *  - a JSON object with any other `part`, or none: skipped, the
     *    contract's rule for a line this client does not know;
     *  - the body ending first: [Part2Result.Ended].
     * A line that is not a JSON object, a final part that does not decode and
     * a failed read throw; the caller turns that into [Part2Result.Failed].
     */
    private fun readFinalPart(
        source: BufferedSource,
        itemLines: ItemLines,
        items: MutableStateFlow<Map<Int, String>>,
        sentAtMs: Long,
    ): Part2Result {
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict()
            when (PtJson.lenient.decodeFromString<WirePartTag>(line).part) {
                PtCloudErrors.PART_TRANSLATION ->
                    return Part2Result.Line(PtJson.lenient.decodeFromString<TranslationPart>(line))
                PtCloudErrors.PART_TRANSLATION_ITEM -> {
                    val atMs = SystemClock.elapsedRealtime() - sentAtMs
                    val item = try {
                        PtJson.lenient.decodeFromString<TranslationItem>(line)
                    } catch (e: IllegalArgumentException) {
                        // kotlinx's SerializationException is one.
                        null
                    }
                    if (item != null) {
                        itemLines.add(atMs)
                        items.update { it + (item.id to item.translation) }
                    }
                }
                else -> Unit
            }
        }
        return Part2Result.Ended
    }

    private class Opened(
        val response: Response,
        val part: OcrPart,
        val headersAtMs: Long,
        val part1AtMs: Long,
        val edgeUploadHeader: String?,
        val edgeHeader: String?,
    )

    /** A non-200 answer as [Refused], classified by its status, its error
     *  body (read up to [ERROR_BODY_LIMIT], never logged) and its
     *  `Retry-After`. Both routes' one reading of an HTTP error. */
    private fun httpRefusal(response: Response): Refused = Refused(
        PtCloudErrors.classifyHttp(
            response.code, response.peekBody(ERROR_BODY_LIMIT).string(), response.header("Retry-After"),
        ),
        "HTTP ${response.code}",
    )

    /** The server answered, but not with a usable part 1 or translate
     *  answer. */
    private class Refused(
        val classified: Classified,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    companion object {
        private const val TAG = "PtCloud"
        private const val NDJSON = "application/x-ndjson"
        private const val JSON = "application/json"

        /** The clock line's status for a 200 that did not decode. */
        private const val UNREADABLE = "unreadable"

        /** The translate route's read and whole-call timeouts
         *  ([translateClient]): above the server's 20 s cap on the route's
         *  deadline, the 25 s call the brief sets for the backend. */
        private const val TRANSLATE_READ_TIMEOUT_S = 22L
        private const val TRANSLATE_CALL_TIMEOUT_S = 25L

        /** The service's root: the Cloudflare Worker in front of it, at the
         *  same address whether the service is running or not. Fixed: there
         *  is no setting for it. */
        private val BASE_URL: HttpUrl = "https://api.playtranslate.com".toHttpUrl()

        /** The edge's clocks (docs/api.md, "Clocks"): arrival to the last
         *  body byte handed to the origin, and arrival to the origin's
         *  response headers. */
        private const val EDGE_UPLOAD_HEADER = "x-pt-edge-upload-ms"
        private const val EDGE_HEADER = "x-pt-edge-ms"

        /** An error body is read only to tell the stopped service's empty
         *  404 from a routing 404, and a 403 `no_entitlement` from any other
         *  403; its text is never logged. */
        private const val ERROR_BODY_LIMIT = 4096L

        /** One client for the process, so consecutive captures reuse the
         *  connection (its TLS handshake is most of a cold call). https only
         *  ([PtHttp.clientBuilder]), as every client but the OpenAI custom
         *  endpoint: [BASE_URL] is https. The server's timings, from the
         *  brief: part 1 normally within 2 s, part 2 at most about 5.5 s
         *  later (the server's own deadline). The 8 s read
         *  is the longest any mode waits on a silent connection, the wait for
         *  the translations after part 1 included: the same bound the
         *  waterfall's services put on a translation ([com.playtranslate.translation.GeminiBackend]'s
         *  call cap, [com.playtranslate.translation.LingvaBackend]'s read),
         *  and above the server's deadline, so it ends a dead connection and
         *  never a translation the server is still going to send. The 12 s
         *  whole-call budget covers the upload, part 1 and that wait. These
         *  are the frame route's; the translate route's client is built from
         *  this one with its own deadlines ([translateClient]). */
        private val shared: OkHttpClient by lazy {
            PtHttp.clientBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                // Happy Eyeballs, as every other client here.
                .fastFallback(true)
                .eventListenerFactory(HttpTimingLogger.factory(TAG))
                .build()
        }

        fun defaultClient(): OkHttpClient = shared

        /** A translate call's body exactly as sent: [request] as one JSON
         *  object in UTF-8, encoded by [PtJson.lenient] (a null persona or
         *  context is left out, and non-ASCII is written as it is, never as
         *  `\u` escapes). The one encoding, so the size [PtCloudBackend]
         *  measures against the body cap is the size that goes out. */
        internal fun translateBody(request: TranslateRequest): ByteArray =
            PtJson.lenient.encodeToString(TranslateRequest.serializer(), request).toByteArray(Charsets.UTF_8)

        /**
         * Runs [read], a blocking step of [call], so that cancelling the
         * calling coroutine cancels [call] at once: OkHttp then fails the
         * blocked read, and the continuation, already cancelled, throws
         * [CancellationException]. A `Job.invokeOnCompletion` hook could not
         * do this: it fires only after the blocked read returns by itself.
         */
        private suspend fun <T> blockingOn(call: Call, read: () -> T): T =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                cont.resumeWith(runCatching(read))
            }
    }
}
