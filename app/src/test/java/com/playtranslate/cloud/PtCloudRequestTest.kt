package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.cloud.PtCloudTestSupport.BASE_URL
import com.playtranslate.cloud.PtCloudTestSupport.CapturingExecutor
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.cloud.PtCloudTestSupport.onExecutor
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.KeyStatus
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.Collections

/**
 * The request exactly as the brief specifies it, captured at the client's
 * boundary: `POST https://api.playtranslate.com/v1/frame` (the fixed root,
 * [BASE_URL]), the two headers, and a two-part multipart body of known
 * length whose `frame` is a JPEG file part and whose `meta` is a plain form
 * field (a file part named `meta` makes the server answer 422). Plus
 * `GET /health`'s status
 * mapping; `GET /v1/key`: the key being saved as its bearer, its reading of
 * the edge's answers, nothing recorded or reported, and a key no request can
 * carry never sent; and `GET /v1/warm`: what it sends, when it sends nothing,
 * and the head start it hands the next frame's clock line. And a stored key
 * no request can carry, refused at the build on both routes.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudRequestTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID))
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    private val ndjson = PtCloudTestSupport.part1(PtCloudTestSupport.JA) + "\n" + PtCloudTestSupport.part2() + "\n"

    private fun send(
        meta: FrameMeta = PtCloudTestSupport.meta(),
        frame: EncodedFrame = PtCloudTestSupport.frame(),
        key: String = KEY,
    ): Request {
        val seen = mutableListOf<Request>()
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx, key),
            clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson, seen = seen) },
        )
        runBlocking { client.postFrame(frame, meta).translation.await() }
        return seen.single()
    }

    private fun MultipartBody.Part.disposition() = headers!!["Content-Disposition"]

    private fun MultipartBody.Part.text() = Buffer().also { body.writeTo(it) }.readUtf8()

    @Test fun `it posts to v1 frame on api playtranslate com with the key and the NDJSON accept`() {
        val request = send()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/frame", request.url.toString())
        assertEquals("Bearer $KEY", request.header("Authorization"))
        assertEquals("application/x-ndjson", request.header("Accept"))
    }

    @Test fun `a pasted key's trailing newline is not sent`() {
        assertEquals("Bearer $KEY", send(key = "$KEY\n").header("Authorization"))
    }

    @Test fun `the body is multipart form data with exactly the frame and meta parts, the frame a JPEG named frame jpg`() {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val body = send(frame = PtCloudTestSupport.frame(bytes)).body as MultipartBody
        assertEquals(MultipartBody.FORM, body.type)
        assertEquals(2, body.parts.size)

        val frame = body.parts[0]
        assertEquals("""form-data; name="frame"; filename="frame.jpg"""", frame.disposition())
        assertEquals("image/jpeg", frame.body.contentType().toString())
        assertArrayEquals(bytes, Buffer().also { frame.body.writeTo(it) }.readByteArray())

        val meta = body.parts[1]
        // A plain form field: no filename, no part media type.
        assertEquals("""form-data; name="meta"""", meta.disposition())
        assertNull(meta.body.contentType())
    }

    @Test fun `the frame body's length is known, so OkHttp sends the Content-Length the edge requires`() {
        // The edge answers 411 to an upload without one. A streaming body
        // (length -1) would make OkHttp send it chunked instead.
        val body = send().body!!
        val length = body.contentLength()
        assertTrue("length $length", length >= 0)
        assertEquals(Buffer().also { body.writeTo(it) }.size, length)
    }

    @Test fun `the frame request carries a clock for the HTTP stack to fill`() {
        assertNotNull(send().tag(HttpCallClock::class.java))
    }

    @Test fun `stream_translations goes out as true when asked for and is absent otherwise`() {
        val on = metaJson(send(PtCloudTestSupport.meta(streamTranslations = true)))
        assertTrue(on["stream_translations"]!!.jsonPrimitive.boolean)
        val off = metaJson(send(PtCloudTestSupport.meta(streamTranslations = null)))
        assertFalse("absent, not null or false: $off", "stream_translations" in off)
    }

    @Test fun `filter_furigana goes out as the boolean it is, true and false, and is never absent`() {
        // The field has no default: the meta's encoder leaves out a value
        // equal to its default, which would drop the key on one of the two.
        for (filter in listOf(true, false)) {
            val obj = metaJson(send(PtCloudTestSupport.meta(filterFurigana = filter)))
            assertTrue("present when $filter: $obj", "filter_furigana" in obj)
            assertEquals(filter, obj["filter_furigana"]!!.jsonPrimitive.boolean)
        }
    }

    private fun metaJson(request: Request): JsonObject =
        Json.parseToJsonElement((request.body as MultipartBody).parts[1].text()) as JsonObject

    @Test fun `meta carries source, target, frame id and the furigana filter and omits an unset persona and context`() {
        val meta = (send().body as MultipartBody).parts[1]
        val obj = Json.parseToJsonElement(meta.text()) as JsonObject
        assertEquals(setOf("source", "target", "frame_id", "filter_furigana"), obj.keys)
        assertEquals("ja", obj["source"]!!.jsonPrimitive.content)
        assertEquals("en", obj["target"]!!.jsonPrimitive.content)
        assertEquals("capture-0042", obj["frame_id"]!!.jsonPrimitive.content)
    }

    @Test fun `meta carries a persona and a context when they are set, verbatim`() {
        val context = "Recent dialogue:\nA: hi\n\n"
        val meta = (send(PtCloudTestSupport.meta(persona = "You translate {source}.", context = context)).body
            as MultipartBody).parts[1]
        val obj = Json.parseToJsonElement(meta.text()) as JsonObject
        assertEquals("You translate {source}.", obj["persona"]!!.jsonPrimitive.content)
        assertEquals(context, obj["context"]!!.jsonPrimitive.content)
    }

    @Test fun `every supported source code goes out as it is spelled`() {
        for (code in PtCloudRouting.SUPPORTED_SOURCE_CODES) {
            val meta = (send(FrameMeta(code, "en", "f", null, null, filterFurigana = true)).body as MultipartBody).parts[1]
            val obj = Json.parseToJsonElement(meta.text()) as JsonObject
            assertEquals(code, obj["source"]!!.jsonPrimitive.content)
        }
    }

    // ── GET /health ────────────────────────────────────────────────────

    private fun health(code: Int, seen: MutableList<Request> = mutableListOf()): HealthStatus {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.cannedClient(code, if (code == 200) """{"ok":true}""" else "", seen = seen) },
        )
        return runBlocking { client.health() }
    }

    @Test fun `health is a GET on health without the key`() {
        val seen = mutableListOf<Request>()
        health(200, seen)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/health", request.url.toString())
        assertNull(request.header("Authorization"))
    }

    @Test fun `health maps 200, 503, 404 and anything else`() {
        assertEquals(HealthStatus.Running, health(200))
        assertEquals(HealthStatus.Starting, health(503))
        assertEquals(HealthStatus.NotRunning, health(404))
        assertEquals(HealthStatus.Other(500), health(500))
    }

    @Test fun `health with no connection is unreachable, and records nothing`() {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(HealthStatus.Unreachable, runBlocking { client.health() })
        assertNull(PtCloudService.unavailableUntil())
        assertFalse(PtCloudService.isCoolingDown())
    }

    // ── GET /v1/key ────────────────────────────────────────────────────

    /** The key the page is saving, where [KEY] is the one stored. */
    private val typedKey = "typed-client-key"

    private fun checkKey(
        code: Int,
        body: String,
        seen: MutableList<Request> = mutableListOf(),
        key: String = typedKey,
    ): KeyStatus {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.cannedClient(code, body, seen = seen) },
        )
        return runBlocking { client.checkKey(key) }
    }

    @Test fun `the key check is a GET on v1 key with the key being saved, not the stored one, and no body`() {
        val seen = mutableListOf<Request>()
        checkKey(200, """{"ok":true}""", seen)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/v1/key", request.url.toString())
        assertEquals("Bearer $typedKey", request.header("Authorization"))
        assertNull(request.body)
    }

    /** The edge's answers ("Key check" in docs/api.md): its 200 alone
     *  accepts the key; a 401 and a 403 no_entitlement refuse it, the second
     *  with its own explanation; every other answer leaves it unchecked. */
    @Test fun `the key check reads the edge's answers`() {
        assertEquals(KeyStatus.Ok, checkKey(200, """{"ok":true}"""))
        val unknown = checkKey(401, """{"error":"unauthorized"}""")
        assertTrue(unknown is KeyStatus.Invalid)
        assertNull((unknown as KeyStatus.Invalid).explanationRes)
        val noMembership = checkKey(403, """{"error":"no_entitlement"}""")
        assertTrue(noMembership is KeyStatus.Invalid)
        assertEquals(R.string.pt_cloud_key_no_membership, (noMembership as KeyStatus.Invalid).explanationRes)
        val unchecked = listOf(
            403 to """{"error":"forbidden"}""",
            404 to """{"error":"not found"}""",
            429 to """{"error":"rate limited"}""",
            503 to """{"error":"auth unavailable"}""",
            500 to "",
        )
        for ((code, body) in unchecked) assertEquals("$code", KeyStatus.Unreachable, checkKey(code, body))
    }

    @Test fun `a key check with no connection is unreachable`() {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(KeyStatus.Unreachable, runBlocking { client.checkKey(typedKey) })
    }

    /** Not a capture: whatever the check meets, the service's cooldown and
     *  the error tracker hear nothing of it. Each of these answers records a
     *  cooldown on a frame. */
    @Test fun `no key check records a cooldown or reports an attempt`() {
        val reported = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { reported += it }
        checkKey(401, """{"error":"unauthorized"}""")
        checkKey(403, """{"error":"no_entitlement"}""")
        checkKey(429, """{"error":"rate limited"}""")
        checkKey(500, "")
        assertNull(PtCloudService.unavailableUntil())
        assertTrue(reported.isEmpty())
    }

    @Test fun `a key no request can carry is refused, and nothing is sent`() {
        for (key in listOf("", "k\u0001ey")) {
            val seen = mutableListOf<Request>()
            assertTrue(key, checkKey(200, """{"ok":true}""", seen, key) is KeyStatus.Invalid)
            assertTrue(key, seen.isEmpty())
        }
    }

    @Test fun `the key check logs its status and never the key`() {
        ShadowLog.clear()
        checkKey(401, """{"error":"unauthorized"}""")
        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }
        assertTrue(lines.toString(), "key check status=401" in lines)
        assertFalse(lines.toString(), lines.any { typedKey in it })
    }

    // ── GET /v1/warm ───────────────────────────────────────────────────

    @Test fun `warm is a GET on v1 warm with the key and no body, on the frame's own client`() {
        val seen = Collections.synchronizedList(mutableListOf<Request>())
        val dispatcher = CapturingExecutor()
        var built = 0
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { built++; PtCloudTestSupport.cannedClient(200, ndjson, seen = seen).onExecutor(dispatcher.executor) },
        )
        client.warm()
        dispatcher.await()
        val warm = seen.single()
        assertEquals("GET", warm.method)
        assertEquals("$BASE_URL/v1/warm", warm.url.toString())
        assertEquals("Bearer $KEY", warm.header("Authorization"))
        assertNull(warm.body)
        assertTrue(dispatcher.thrown.isEmpty())

        // The frame goes through the same client, so the same connection pool.
        runBlocking { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()).translation.await() }
        assertEquals("$BASE_URL/v1/frame", seen.last().url.toString())
        assertEquals(1, built)
    }

    @Test fun `warm sends nothing with no usable key`() {
        val cases = listOf<Pair<String, (Prefs) -> Unit>>(
            "no key" to { it.ptCloudClientKey = "" },
            "a key that is not a legal header value" to { it.ptCloudClientKey = "abc\ndef" },
        )
        for ((name, edit) in cases) {
            val prefs = PtCloudTestSupport.prefs(ctx)
            prefs.ptCloudClientKey = KEY
            edit(prefs)
            val seen = Collections.synchronizedList(mutableListOf<Request>())
            val dispatcher = CapturingExecutor()
            val client = PtCloudClient(
                PtCloudConfig(prefs),
                clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson, seen = seen).onExecutor(dispatcher.executor) },
            )
            client.warm()
            dispatcher.await()
            assertTrue("$name: $seen", seen.isEmpty())
            assertTrue(name, dispatcher.thrown.isEmpty())
        }
    }

    private fun part1Lines() = ShadowLog.getLogsForTag("PtCloud").map { it.msg }.filter { it.startsWith("part1 ") }

    @Test fun `a warm's head start shows on the next frame's part 1 line, and only that frame's`() {
        ShadowLog.clear()
        val dispatcher = CapturingExecutor()
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson).onExecutor(dispatcher.executor) },
        )
        client.warm()
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        runBlocking { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()).translation.await() }
        runBlocking { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()).translation.await() }
        dispatcher.await()

        val lines = part1Lines()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], " warm=1000 " in lines[0])
        assertTrue("taken by the first frame: ${lines[1]}", " warm=none " in lines[1])
    }

    @Test fun `a warm that sent nothing leaves the next frame with no head start`() {
        ShadowLog.clear()
        val prefs = PtCloudTestSupport.prefs(ctx)
        val client = PtCloudClient(PtCloudConfig(prefs), clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson) })
        client.warm()
        // A key saved after the warm, so the frame goes out.
        prefs.ptCloudClientKey = KEY
        runBlocking { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()).translation.await() }
        assertTrue(part1Lines().single(), " warm=none " in part1Lines().single())
    }

    // ── A key the request cannot carry ─────────────────────────────────
    // The router asked hasKey, then a key that fails it was saved before the
    // request was built: each route reads the key once and refuses it there,
    // as a transport failure, instead of OkHttp's IllegalArgumentException,
    // which no caller catches. (The warm's case is "warm sends nothing with
    // no usable key" above.)

    /** A client whose stored key is not a legal header value, answering
     *  [body] to every call it would make, recorded in [seen]. */
    private fun unsendableKeyClient(seen: MutableList<Request>, body: String) = PtCloudClient(
        PtCloudTestSupport.config(ctx, key = "abc\ndef"),
        clientFactory = { PtCloudTestSupport.cannedClient(200, body, seen = seen) },
    )

    /** Runs [call] and returns what it threw. */
    private fun thrownBy(call: suspend () -> Unit): Throwable? = runCatching { runBlocking { call() } }.exceptionOrNull()

    /** [thrown] is the transport failure: the kind a lost connection has. */
    private fun assertTransportFailure(thrown: Throwable?) {
        assertTrue("$thrown", thrown is PtCloudReadException)
        assertEquals(BackendFailureKind.UNREACHABLE, (thrown as PtCloudReadException).failure.kind)
    }

    @Test fun `a frame whose key cannot be sent fails as a transport failure and sends nothing`() {
        val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
        val seen = mutableListOf<Request>()
        val client = unsendableKeyClient(seen, ndjson)
        val frame = suspend { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()); Unit }

        assertTransportFailure(thrownBy(frame))
        assertFalse("a first one is forgiven, as a lost connection is", PtCloudService.isCoolingDown())
        assertTransportFailure(thrownBy(frame))
        assertTrue("recorded: a second cools down", PtCloudService.isCoolingDown())
        assertTrue(seen.isEmpty())
        assertEquals(2, attempts.size)
        assertTrue("no server answered: $attempts", attempts.none { it.reachedServer })
    }

    @Test fun `a translate call whose key cannot be sent fails as a transport failure and sends nothing`() {
        val seen = mutableListOf<Request>()
        val client = unsendableKeyClient(seen, PtCloudTestSupport.part2())
        val translate = suspend { client.postTranslate(TranslateRequest("ja", "en", listOf("一"), "tr-1", null, null)); Unit }

        assertTransportFailure(thrownBy(translate))
        assertFalse(PtCloudService.isCoolingDown())
        assertTransportFailure(thrownBy(translate))
        assertTrue("recorded: a second cools down", PtCloudService.isCoolingDown())
        assertTrue(seen.isEmpty())
    }
}
