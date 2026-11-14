package com.playtranslate.audio.online

import com.playtranslate.audio.sources.CommonsClient
import com.playtranslate.net.PtHttp
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.ClassifiedFailure
import com.playtranslate.translation.blockingOn
import com.playtranslate.translation.classifyBackendFailure
import com.playtranslate.translation.httpStatusFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A voice service's request that did not give what was asked. [failure] is
 * its kind, in the app's one vocabulary of online failures. [detail] is the
 * start of what the service answered, when it answered in words: shown to the
 * user who is setting the service up, and never logged.
 *
 * The message is the kind's name and nothing else. An exception from below
 * (OkHttp's, the cleartext interceptor's) can quote a header's value or the
 * URL in its own message, and either can hold the key, so none of them is
 * kept as a cause or copied here.
 */
class OnlineTtsException(
    override val failure: BackendFailure,
    val detail: String? = null,
) : IOException(failure.kind.name), ClassifiedFailure

/**
 * Sends the requests [OnlineTtsRequest] makes: one that answers with audio,
 * and the GETs that answer with a list.
 *
 * The client follows **no redirect**. OkHttp drops only `Authorization` when
 * a redirect leaves the host, so a key sent under any other name
 * (`xi-api-key`) would travel to wherever a 3xx pointed, and on a 307 or 308
 * the text would be sent again with it. A 3xx is therefore a failure like
 * any other status that is not a success. The URL is held to
 * [com.playtranslate.net.CustomEndpointPolicy] at every hop by
 * [PtHttp.customEndpointBuilder]: https anywhere, http only on the user's own
 * network.
 *
 * Every call runs under [blockingOn], so cancelling the caller (a speaker
 * tap's 2.5 s budget, a card send's 20 s one, a second tap) ends the request
 * at once instead of leaving it to run to its own timeout.
 *
 * Nothing here logs: a URL, a header, a body and a reply can each hold the
 * key or the user's text.
 */
class OnlineTtsClient(private val client: OkHttpClient = defaultClient()) {

    /** The audio a service answered with, and the format its bytes are in. */
    class Clip(val bytes: ByteArray, val container: AudioContainer)

    /** The clip for [request]. Throws [OnlineTtsException] for every way of
     *  not getting one, a reply that is not audio among them. */
    suspend fun synthesize(request: OnlineTtsRequest.Rendered): Clip {
        val bytes = successBody(exchange(request))
        val container = AudioContainer.sniff(bytes)
            ?: throw OnlineTtsException(BackendFailure.BAD_RESPONSE, detailOf(bytes))
        return Clip(bytes, container)
    }

    /** The items of the list [request] fetches, read as [spec] says. Throws
     *  [OnlineTtsException]; a service with no such list answers 404, which
     *  is the failure's [BackendFailure.httpCode]. */
    suspend fun list(request: OnlineTtsRequest.Rendered, spec: OnlineTtsListSpec): List<OnlineTtsChoice> {
        val bytes = successBody(exchange(request))
        return OnlineTtsRequest.parseList(spec, bytes.toString(Charsets.UTF_8))
            ?: throw OnlineTtsException(BackendFailure.BAD_RESPONSE, detailOf(bytes))
    }

    /** A reply's status and its body, or a null body when it ran past
     *  [MAX_REPLY_BYTES]. */
    private class Reply(val code: Int, val bytes: ByteArray?)

    private fun successBody(reply: Reply): ByteArray {
        if (reply.code !in 200..299) throw OnlineTtsException(failureFor(reply.code), reply.bytes?.let(::detailOf))
        return reply.bytes ?: throw OnlineTtsException(BackendFailure.BAD_RESPONSE)
    }

    private suspend fun exchange(request: OnlineTtsRequest.Rendered): Reply = withContext(Dispatchers.IO) {
        try {
            val call = client.newCall(request.toOkHttp())
            blockingOn(call) {
                call.execute().use { response ->
                    Reply(response.code, CommonsClient.readCapped(response.body.byteStream(), MAX_REPLY_BYTES))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The kind only: see the class doc of OnlineTtsException.
            throw OnlineTtsException(classifyBackendFailure(e))
        }
    }

    private fun OnlineTtsRequest.Rendered.toOkHttp(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        // No media type on the body: with one, OkHttp writes its own
        // Content-Type over the request text's.
        return (if (body == null) builder.get() else builder.post(body.toRequestBody(null))).build()
    }

    companion object {
        /** A sentence of uncompressed 44.1 kHz stereo runs about 10 MB a
         *  minute; past this a reply is not a pronunciation clip. */
        const val MAX_REPLY_BYTES = 16L * 1024 * 1024

        private const val DETAIL_CHARS = 300

        /** How much of a reply is decoded to find those characters: a
         *  reply can be megabytes, and its words are at its start. */
        private const val DETAIL_BYTES = 4096

        /** A status that is not a success, as a failure. A key the service
         *  refuses and a spent rate are named; everything else is the
         *  service's own fault (5xx) or a refusal, with its status. */
        internal fun failureFor(code: Int): BackendFailure = when (code) {
            401, 403 -> BackendFailure(BackendFailureKind.AUTH, code)
            429 -> BackendFailure(BackendFailureKind.RATE_LIMITED, code)
            else -> httpStatusFailure(code)
        }

        /** The start of a reply that is words, on one line; null for an
         *  empty one. */
        internal fun detailOf(bytes: ByteArray): String? =
            String(bytes, 0, minOf(bytes.size, DETAIL_BYTES), Charsets.UTF_8)
                .replace(Regex("\\s+"), " ").trim()
                .take(DETAIL_CHARS)
                .takeIf { it.isNotEmpty() }

        private fun defaultClient(): OkHttpClient = PtHttp.customEndpointBuilder()
            .followRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
