package com.playtranslate.translation

import com.playtranslate.PtJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * HTTP fakes for the online backends' tests, beyond the canned-reply
 * interceptor the older backend tests build inline: a recording one, one
 * that holds a call open until it is cancelled, one that answers a call's
 * headers and stalls its body until then, and a loopback server whose
 * slow reply lets a client's own timeouts fire on a real socket. No real
 * network: the interceptors answer before any connection is made, and the
 * server listens on loopback only.
 */

/** One request an interceptor saw, with its body as text. */
internal class SeenRequest(val request: Request, val body: String) {
    val json: JsonObject get() = PtJson.lenient.parseToJsonElement(body).jsonObject
}

/** What a canned interceptor answers: [body] as JSON text, or [bytes] as
 *  they are when set (an audio file, which is no text in any charset). */
internal class CannedReply(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val bytes: ByteArray? = null,
)

/** A client whose interceptor records each request into [seen] and answers
 *  it with [reply], which may look at the request (a batch body, say). */
internal fun recordingClient(
    seen: MutableList<SeenRequest> = mutableListOf(),
    reply: (SeenRequest) -> CannedReply,
): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
    val buffer = Buffer()
    chain.request().body?.writeTo(buffer)
    val request = SeenRequest(chain.request(), buffer.readUtf8())
    seen += request
    val canned = reply(request)
    Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(canned.code)
        .message(if (canned.code in 200..299) "OK" else "Error")
        .body(
            canned.bytes?.toResponseBody("application/octet-stream".toMediaType())
                ?: canned.body.toResponseBody("application/json".toMediaType()),
        )
        .apply { canned.headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()
}.build()

/** A client whose every call fails in transport, with no HTTP reply. */
internal fun transportFailingClient(failure: () -> IOException = { IOException("synthetic connection failure") }): OkHttpClient =
    OkHttpClient.Builder().addInterceptor { throw failure() }.build()

/**
 * Holds every call at the "server", unanswered, until the call is
 * cancelled: [held] receives each call as it arrives, [cancelled] each one
 * the hold saw cancelled ([Call.isCanceled]). A call nobody cancels is
 * released with an IOException after [giveUpMs], so a regression shows up
 * as a failed assertion and not as a hung test.
 */
internal class HoldUntilCancelled(private val giveUpMs: Long = 5_000) : Interceptor {
    val held = LinkedBlockingQueue<Call>()
    val cancelled = LinkedBlockingQueue<Call>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        held.put(call)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(giveUpMs)
        while (!call.isCanceled()) {
            if (System.nanoTime() > deadline) throw IOException("held call was never cancelled")
            Thread.sleep(5)
        }
        cancelled.put(call)
        throw IOException("Canceled")
    }
}

/**
 * Answers every call's status and headers at once and then stalls its body,
 * as a service does that sends its headers before the reply is written: the
 * first read of the body puts the call on [reading] and blocks until the
 * call is cancelled ([Call.isCanceled]), which puts it on [cancelled] and
 * fails the read, as OkHttp fails a read on a cancelled call. The other half
 * of [HoldUntilCancelled], which holds a call before any reply. A body
 * nobody cancels fails its read after [giveUpMs], so a regression shows up
 * as a failed assertion and not as a hung test.
 */
internal class StallBodyUntilCancelled(
    private val code: Int = 200,
    private val giveUpMs: Long = 5_000,
) : Interceptor {
    val reading = LinkedBlockingQueue<Call>()
    val cancelled = LinkedBlockingQueue<Call>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val stalled = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.put(call)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(giveUpMs)
                while (!call.isCanceled()) {
                    if (System.nanoTime() > deadline) throw IOException("stalled body was never cancelled")
                    Thread.sleep(5)
                }
                cancelled.put(call)
                throw IOException("Canceled")
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(stalled.buffer().asResponseBody("application/json".toMediaType()))
            .build()
    }
}

/**
 * A loopback HTTP/1.1 server that answers every request with a 200 and
 * [body], [delayMs] after reading it. An interceptor can only hand a reply
 * back late; it cannot make a socket read or a whole call time out, which
 * is what the backends' timeouts are about, so their tests need a real
 * connection. Each reply closes its connection.
 */
internal class DelayedReplyServer(private val delayMs: Long, private val body: String) : Closeable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val requests = AtomicInteger(0)

    init {
        thread(isDaemon = true, name = "delayed-reply-server") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    return@thread
                }
                thread(isDaemon = true, name = "delayed-reply-connection") { answer(client) }
            }
        }
    }

    private fun answer(client: Socket) {
        client.use {
            try {
                val input = client.getInputStream()
                skip(input, readContentLength(input))
                requests.incrementAndGet()
                Thread.sleep(delayMs)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                val out = client.getOutputStream()
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                // The client gave up first (its timeout fired) and hung up.
            } catch (e: InterruptedException) {
                // Shutting down.
            }
        }
    }

    /** Reads the request line and headers; returns the body's length. */
    private fun readContentLength(input: InputStream): Int {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw EOFException("request ended inside its headers")
            head.append(b.toChar())
        }
        return head.lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    }

    private fun skip(input: InputStream, length: Int) {
        val buffer = ByteArray(4096)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) return
            left -= n
        }
    }

    override fun close() = socket.close()
}
