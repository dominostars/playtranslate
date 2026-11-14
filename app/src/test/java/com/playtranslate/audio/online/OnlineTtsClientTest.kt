package com.playtranslate.audio.online

import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CannedReply
import com.playtranslate.translation.HoldUntilCancelled
import com.playtranslate.translation.SeenRequest
import com.playtranslate.translation.recordingClient
import com.playtranslate.translation.transportFailingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [OnlineTtsClient]: the request as [OnlineTtsRequest] made it, a reply
 * taken as a clip only when its bytes are audio, every other outcome as an
 * [OnlineTtsException] that carries a kind and none of the request, a
 * redirect never followed, and a cancelled caller ending the call.
 */
class OnlineTtsClientTest {

    private val config = OnlineTtsConfig(model = OnlineTtsChoice("gpt-4o-mini-tts"))
    private val values = OnlineTtsRequest.Values(
        text = "こんにちは", voice = "coral", model = "gpt-4o-mini-tts", key = "sk-SECRET",
    )
    private val speech = OnlineTtsRequest.render(config, values)!!
    private val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)

    private fun failure(client: OkHttpClient, request: OnlineTtsRequest.Rendered = speech): OnlineTtsException =
        try {
            runBlocking { OnlineTtsClient(client).synthesize(request) }
            throw AssertionError("expected a failure")
        } catch (e: OnlineTtsException) {
            e
        }

    private fun replying(code: Int, body: String = "", bytes: ByteArray? = null) =
        recordingClient { CannedReply(code, body, bytes = bytes) }

    // ── The request ──────────────────────────────────────────────────────

    @Test fun `a speech request is sent as rendered, with the request text's own content type`() = runBlocking {
        val seen = mutableListOf<SeenRequest>()
        val client = recordingClient(seen) { CannedReply(200, "", bytes = mp3) }
        OnlineTtsClient(client).synthesize(speech)
        val sent = seen.single()
        assertEquals("POST", sent.request.method)
        assertEquals("https://api.openai.com/v1/audio/speech", sent.request.url.toString())
        assertEquals("Bearer sk-SECRET", sent.request.header("Authorization"))
        assertEquals(listOf("application/json"), sent.request.headers("Content-Type"))
        assertEquals(speech.body, sent.body)
    }

    @Test fun `a list request is a GET with the key header and no body`() = runBlocking {
        val seen = mutableListOf<SeenRequest>()
        val client = recordingClient(seen) { CannedReply(200, """{"voices":["jf_alpha"]}""") }
        val spec = OnlineTtsPreset.OPENAI.spec.voices
        val voices = OnlineTtsClient(client).list(OnlineTtsRequest.renderList(config, spec, "sk-SECRET")!!, spec)
        assertEquals(listOf(OnlineTtsChoice("jf_alpha")), voices)
        val sent = seen.single()
        assertEquals("GET", sent.request.method)
        assertEquals("https://api.openai.com/v1/audio/voices", sent.request.url.toString())
        assertEquals("Bearer sk-SECRET", sent.request.header("Authorization"))
        assertEquals("", sent.body)
    }

    // ── The reply ────────────────────────────────────────────────────────

    @Test fun `a reply whose bytes are audio is a clip in that format`() = runBlocking {
        val clip = OnlineTtsClient(replying(200, bytes = mp3)).synthesize(speech)
        assertEquals(AudioContainer.MP3, clip.container)
        assertTrue(clip.bytes.contentEquals(mp3))
    }

    @Test fun `a success that is not audio is a bad response with the reply's words`() {
        val e = failure(replying(200, """{"audio_base64":"SUQzBAA="}"""))
        assertEquals(BackendFailureKind.BAD_RESPONSE, e.failure.kind)
        assertEquals("""{"audio_base64":"SUQzBAA="}""", e.detail)
    }

    @Test fun `a refused key, a spent rate, a server error and a refusal are told apart`() {
        fun kind(code: Int) = failure(replying(code, """{"error":"x"}""")).failure
        assertEquals(BackendFailureKind.AUTH, kind(401).kind)
        assertEquals(BackendFailureKind.AUTH, kind(403).kind)
        assertEquals(BackendFailureKind.RATE_LIMITED, kind(429).kind)
        assertEquals(BackendFailureKind.SERVER_ERROR, kind(503).kind)
        assertEquals(BackendFailureKind.REJECTED, kind(400).kind)
        assertEquals(400, kind(400).httpCode)
        assertEquals(404, kind(404).httpCode)
    }

    @Test fun `a failure carries the start of the service's words on one line`() {
        val long = "{\"error\":{\"message\":\"Invalid\n  voice\"}}" + "x".repeat(500)
        val e = failure(replying(400, long))
        assertTrue(e.detail!!.startsWith("""{"error":{"message":"Invalid voice"}}"""))
        assertEquals(300, e.detail!!.length)
        assertNull("an empty reply has no words", failure(replying(500, "")).detail)
    }

    @Test fun `a redirect status is a failure`() {
        val e = failure(recordingClient { CannedReply(307, "", headers = mapOf("Location" to "https://elsewhere.example/x")) })
        assertEquals(BackendFailureKind.REJECTED, e.failure.kind)
        assertEquals(307, e.failure.httpCode)
    }

    @Test fun `a reply past the size cap is a bad response, not a clip`() {
        val big = ByteArray((OnlineTtsClient.MAX_REPLY_BYTES + 1).toInt()).also { mp3.copyInto(it) }
        val e = failure(replying(200, bytes = big))
        assertEquals(BackendFailureKind.BAD_RESPONSE, e.failure.kind)
    }

    // ── What a failure may say ───────────────────────────────────────────

    @Test fun `a transport failure keeps its kind and nothing the exception said`() {
        val e = failure(
            transportFailingClient { IOException("blocked request to https://x.example/?key=sk-SECRET with Bearer sk-SECRET") },
        )
        assertEquals(BackendFailureKind.UNREACHABLE, e.failure.kind)
        assertFalse(e.message.orEmpty().contains("sk-SECRET"))
        assertNull("the exception from below is not kept as a cause", e.cause)
        assertNull(e.detail)
        assertEquals("UNREACHABLE", e.message)
    }

    @Test fun `a timeout is told apart from an unreachable server`() {
        val e = failure(transportFailingClient { java.net.SocketTimeoutException("timeout") })
        assertEquals(BackendFailureKind.TIMEOUT, e.failure.kind)
    }

    // ── Cancellation ─────────────────────────────────────────────────────

    @Test fun `cancelling the caller ends the request`() = runBlocking {
        val hold = HoldUntilCancelled()
        val client = OkHttpClient.Builder().addInterceptor(hold).build()
        val job = async(Dispatchers.Default) { OnlineTtsClient(client).synthesize(speech) }
        val held = hold.held.poll(5, TimeUnit.SECONDS)
        assertNotNull("the request reached the server", held)
        job.cancelAndJoin()
        assertNotNull("the call was cancelled, not left to its timeout", hold.cancelled.poll(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
    }

    // ── No redirect is followed (the real client, real sockets) ──────────

    /** A loopback server that answers every request with [reply] and counts
     *  the requests it saw. */
    private class OneReplyServer(private val reply: (Int) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort
        val requests = AtomicInteger(0)
        val seenHeaders = StringBuffer()

        init {
            thread(isDaemon = true, name = "one-reply-server") {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: IOException) { return@thread }
                    client.use {
                        try {
                            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                seenHeaders.append(line).append('\n')
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    length = line.substringAfter(':').trim().toInt()
                                }
                            }
                            repeat(length) { input.read() }
                            requests.incrementAndGet()
                            client.getOutputStream().apply {
                                write(reply(port).toByteArray(Charsets.ISO_8859_1))
                                flush()
                            }
                        } catch (e: IOException) {
                            // The client hung up.
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test fun `the app's own client does not follow a redirect, so a key header never leaves its host`() {
        OneReplyServer { "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }.use { elsewhere ->
            OneReplyServer {
                "HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:${elsewhere.port}/stolen\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            }.use { service ->
                val local = OnlineTtsConfig(
                    preset = OnlineTtsPreset.ELEVENLABS,
                    url = "http://127.0.0.1:${service.port}/v1/text-to-speech/{voice}",
                    model = OnlineTtsChoice("m"),
                )
                val request = OnlineTtsRequest.render(local, values)!!
                try {
                    runBlocking { OnlineTtsClient().synthesize(request) }
                    fail("a redirect is a failure")
                } catch (e: OnlineTtsException) {
                    assertEquals(307, e.failure.httpCode)
                }
                assertEquals(1, service.requests.get())
                val onTheWire = service.seenHeaders.toString()
                assertTrue(onTheWire.contains("xi-api-key: sk-SECRET"))
                // As it leaves the device, past everything OkHttp adds: the
                // content type is the request text's own, with nothing
                // appended to it.
                assertTrue(onTheWire, onTheWire.contains("Content-Type: application/json\n"))
                assertEquals("the redirect target was never asked", 0, elsewhere.requests.get())
            }
        }
    }
}
