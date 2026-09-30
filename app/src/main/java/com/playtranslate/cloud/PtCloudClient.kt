package com.playtranslate.cloud

import android.os.SystemClock
import android.util.Log
import com.playtranslate.PtJson
import com.playtranslate.net.PtHttp
import com.playtranslate.translation.HttpTimingLogger
import com.playtranslate.translation.OnlineAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What reading part 2 produced. Never an exception: the reader turns each
 *  case into a [TranslationOutcome] through [PtCloudErrors.classifyPart2]. */
sealed interface Part2Result {
    /** The second line, decoded. */
    data class Line(val part: TranslationPart) : Part2Result
    /** The body ended after part 1, with no second line. */
    data object Ended : Part2Result
    /** The read or the decode failed; [cause]'s class is loggable, its
     *  message is not (it can echo the body). */
    data class Failed(val cause: Throwable) : Part2Result
}

/**
 * A frame whose part 1 arrived. [translation] reads part 2 as a child of the
 * coroutine that called [PtCloudClient.postFrame]: that caller's job waits
 * for it and cancelling that job cancels the HTTP call; a caller that will
 * not use part 2 cancels [translation]. It resolves to a [Part2Result] and
 * never throws, apart from [CancellationException].
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
    /** Nothing was sent: the stored server address is not usable
     *  ([PtCloudConfig.endpoint] is null: malformed, or cleartext http to a
     *  public host). */
    data object BadUrl : HealthStatus
    data class Other(val code: Int) : HealthStatus
}

/**
 * The HTTP client for PlayTranslate Cloud's `POST /v1/frame` and
 * `GET /health` (the contract: docs/api.md and
 * docs/client-integration-brief.md in the playtranslate-cloud repository).
 *
 * Reporting is split at part 1. [postFrame] records the cooldown and
 * reports the [OnlineAttempt] of any failure before part 1 itself, then
 * throws [PtCloudReadException]. Once part 1 is back it reports nothing: the
 * reader decides how the frame went (part 2's outcome, or a caller that let
 * part 2 go), so recording the success and reporting the attempt are its job,
 * with [FrameResponse]'s stamps.
 *
 * A cancelled call (the user released the hold, a newer capture superseded
 * this one) records nothing and reports nothing: it is not a fact about the
 * service. Cancellation is read from the calling coroutine, never from
 * `Call.isCanceled()`: OkHttp's own call timeout cancels the call too, and a
 * timeout is a failure (TIMEOUT), not the user's gesture.
 *
 * Logs carry status codes, byte and group counts, timings and exception
 * class names only; never the key, the URL, a body or recognized text.
 */
class PtCloudClient(
    private val config: PtCloudConfig,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
) {

    private val client: OkHttpClient by lazy(clientFactory)

    /**
     * Uploads [frame] with [meta] and returns once part 1 (the OCR groups)
     * has arrived and decoded; part 2 keeps reading in
     * [FrameResponse.translation], a child of the calling coroutine's job.
     * Call this from the job meant to own part 2, not inside a scoped
     * builder (`withContext`, `coroutineScope`, `withTimeout`): a scope
     * waits for its children, so it would return only after part 2.
     *
     * Throws [PtCloudReadException] for any failure before part 1, after
     * recording its cooldown on [PtCloudService.cooldown] and reporting its
     * attempt through [PtCloudService.report] (the failure table:
     * [PtCloudErrors.classifyHttp], [PtCloudErrors.classifyTransport],
     * [PtCloudErrors.badPart1]). Throws [CancellationException], with nothing
     * recorded or reported, when the calling coroutine was cancelled. Throws
     * [IllegalStateException], with nothing sent, when the config has no
     * usable [PtCloudConfig.endpoint], which the router never lets happen.
     */
    suspend fun postFrame(frame: EncodedFrame, meta: FrameMeta): FrameResponse {
        // Captured here, outside any withContext: part 2's reader is a child
        // of the CALLER's job, so the caller waits for it and cancels it.
        val callerContext = currentCoroutineContext()
        val cooldown = PtCloudService.cooldown
        val call = client.newCall(frameRequest(frame, meta))
        // A response read to part 1 when the caller was cancelled on the way
        // back must still be closed; this holds it until it is handed off.
        var opened: Opened? = null
        val sentAtMs = SystemClock.elapsedRealtime()
        val attemptStartedAtMs = System.currentTimeMillis()
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
            "part1 groups=${part1.part.groups.size} bytes=${frame.bytes.size} " +
                "ms=${SystemClock.elapsedRealtime() - sentAtMs}",
        )

        val response = part1.response
        val translation = CoroutineScope(callerContext + Dispatchers.IO).async {
            try {
                blockingOn(call) {
                    val source = response.body.source()
                    if (source.exhausted()) {
                        Part2Result.Ended
                    } else {
                        Part2Result.Line(PtJson.lenient.decodeFromString<TranslationPart>(source.readUtf8LineStrict()))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Never fail this child: a failed async would cancel the
                // caller's job (on the hold path, crash the app).
                if (!isActive) {
                    throw CancellationException("PlayTranslate Cloud part 2 cancelled").apply { initCause(t) }
                }
                Part2Result.Failed(t)
            } finally {
                response.close()
            }
        }
        // A caller cancelled between part 1 and this launch cancels the
        // child before its body runs, so the finally above never would.
        translation.invokeOnCompletion { response.close() }
        return FrameResponse(part1.part, translation, sentAtMs, attemptStartedAtMs)
    }

    /** `GET {endpoint}/health`, no auth. [HealthStatus.BadUrl], with nothing
     *  sent, when the stored URL is unusable ([PtCloudConfig.endpoint] is
     *  null); [HealthStatus.Unreachable] for a transport failure;
     *  [CancellationException] when the caller was cancelled. */
    suspend fun health(): HealthStatus = withContext(Dispatchers.IO) {
        val endpoint = config.endpoint ?: return@withContext HealthStatus.BadUrl
        val url = endpoint.newBuilder().addPathSegments("health").build()
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

    /** The multipart request exactly as the brief specifies it: `frame` a
     *  file part with a filename and a media type, `meta` a plain form field
     *  (a file part named `meta` is ignored and the server answers 422).
     *  Throws [IllegalStateException] when [PtCloudConfig.endpoint] is null:
     *  the router never sends a frame here without one, so reaching this is
     *  a programming error, not a service failure. */
    private fun frameRequest(frame: EncodedFrame, meta: FrameMeta): Request {
        val endpoint = checkNotNull(config.endpoint) { "PlayTranslate Cloud frame with no usable endpoint" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("frame", FRAME_FILENAME, frame.bytes.toRequestBody(frame.mediaType.toMediaType()))
            .addFormDataPart("meta", PtJson.lenient.encodeToString(FrameMeta.serializer(), meta))
            .build()
        return Request.Builder()
            .url(endpoint.newBuilder().addPathSegments("v1/frame").build())
            .header("Authorization", "Bearer ${config.clientKey}")
            .header("Accept", NDJSON)
            .post(body)
            .build()
    }

    /** Sends the request and reads part 1, blocking. A non-200 status or an
     *  unreadable part 1 throws [Refused] with its classification; the
     *  response is closed on every path except success, where it is handed
     *  back open for part 2. */
    private fun openPart1(call: Call): Opened {
        val response = call.execute()
        var handedOff = false
        try {
            if (response.code != 200) {
                val body = response.peekBody(ERROR_BODY_LIMIT).string()
                throw Refused(
                    PtCloudErrors.classifyHttp(response.code, body, response.header("Retry-After")),
                    "HTTP ${response.code}",
                )
            }
            val line = response.body.source().readUtf8LineStrict()
            val part = try {
                PtJson.lenient.decodeFromString<OcrPart>(line)
            } catch (e: IllegalArgumentException) {
                // kotlinx's SerializationException is one.
                throw Refused(PtCloudErrors.badPart1, "part 1 did not decode", e)
            }
            if (!PtCloudErrors.isOcrPart(part)) throw Refused(PtCloudErrors.badPart1, "not a schema 1 part 1")
            handedOff = true
            return Opened(response, part)
        } finally {
            if (!handedOff) response.close()
        }
    }

    private class Opened(val response: Response, val part: OcrPart)

    /** The server answered, but not with a usable part 1. */
    private class Refused(
        val classified: Classified,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    companion object {
        private const val TAG = "PtCloud"
        private const val NDJSON = "application/x-ndjson"
        private const val FRAME_FILENAME = "frame.webp"

        /** An error body is read only to tell the stopped service's empty
         *  404 from a routing 404; its text is never logged. */
        private const val ERROR_BODY_LIMIT = 4096L

        /** One client for the process, so consecutive captures reuse the
         *  connection (its TLS handshake is most of a cold call). The custom
         *  endpoint builder allows http to a loopback or LAN address, for a
         *  local build of the service, and https anywhere. Timeouts from the
         *  brief: part 1 normally within 2 s, part 2 at most about 5.5 s
         *  later (the server's own deadline), so a 10 s read and a 12 s
         *  whole-call budget cover the server and anything longer is the
         *  network. */
        private val shared: OkHttpClient by lazy {
            PtHttp.customEndpointBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                // Happy Eyeballs, as every other client here.
                .fastFallback(true)
                .eventListenerFactory(HttpTimingLogger.factory(TAG))
                .build()
        }

        fun defaultClient(): OkHttpClient = shared

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
