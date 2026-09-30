package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudTestSupport.BASE_URL
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The request exactly as the brief specifies it, captured at the client's
 * boundary: `POST {base}/v1/frame`, the two headers, and a two-part
 * multipart body whose `frame` is a file part with a filename and media type
 * and whose `meta` is a plain form field (a file part named `meta` makes the
 * server answer 422). Plus `GET /health`'s status mapping.
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
        baseUrl: String = BASE_URL,
        key: String = KEY,
    ): Request {
        val seen = mutableListOf<Request>()
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx, baseUrl, key),
            clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson, seen = seen) },
        )
        runBlocking { client.postFrame(frame, meta).translation.await() }
        return seen.single()
    }

    private fun MultipartBody.Part.disposition() = headers!!["Content-Disposition"]

    private fun MultipartBody.Part.text() = Buffer().also { body.writeTo(it) }.readUtf8()

    @Test fun `it posts to v1 frame under the base URL with the key and the NDJSON accept`() {
        val request = send()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/frame", request.url.toString())
        assertEquals("Bearer $KEY", request.header("Authorization"))
        assertEquals("application/x-ndjson", request.header("Accept"))
    }

    @Test fun `a stored base URL with spaces and a trailing slash still makes one clean path`() {
        assertEquals("$BASE_URL/v1/frame", send(baseUrl = "  $BASE_URL/ ").url.toString())
    }

    @Test fun `a base URL with a path keeps it`() {
        assertEquals("$BASE_URL/pt/v1/frame", send(baseUrl = "$BASE_URL/pt/").url.toString())
    }

    @Test fun `a frame with no usable endpoint is a programming error, and nothing is sent`() {
        val seen = mutableListOf<Request>()
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx, baseUrl = "http://pt.example.test"),
            clientFactory = { PtCloudTestSupport.cannedClient(200, ndjson, seen = seen) },
        )
        try {
            runBlocking { client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()) }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun `a pasted key's trailing newline is not sent`() {
        assertEquals("Bearer $KEY", send(key = "$KEY\n").header("Authorization"))
    }

    @Test fun `the body is multipart form data with exactly the frame and meta parts`() {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val body = send(frame = PtCloudTestSupport.frame(bytes)).body as MultipartBody
        assertEquals(MultipartBody.FORM, body.type)
        assertEquals(2, body.parts.size)

        val frame = body.parts[0]
        assertEquals("""form-data; name="frame"; filename="frame.webp"""", frame.disposition())
        assertEquals("image/webp", frame.body.contentType().toString())
        assertArrayEquals(bytes, Buffer().also { frame.body.writeTo(it) }.readByteArray())

        val meta = body.parts[1]
        // A plain form field: no filename, no part media type.
        assertEquals("""form-data; name="meta"""", meta.disposition())
        assertNull(meta.body.contentType())
    }

    @Test fun `meta carries source, target and frame id and omits an unset persona and context`() {
        val meta = (send().body as MultipartBody).parts[1]
        val obj = Json.parseToJsonElement(meta.text()) as JsonObject
        assertEquals(setOf("source", "target", "frame_id"), obj.keys)
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
            val meta = (send(FrameMeta(code, "en", "f", null, null)).body as MultipartBody).parts[1]
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

    @Test fun `health with an unusable URL is BadUrl, and sends nothing`() {
        for (bad in listOf("http://pt.example.test", "not a url")) {
            val seen = mutableListOf<Request>()
            val client = PtCloudClient(
                PtCloudTestSupport.config(ctx, baseUrl = bad),
                clientFactory = { PtCloudTestSupport.cannedClient(200, """{"ok":true}""", seen = seen) },
            )
            assertEquals(HealthStatus.BadUrl, runBlocking { client.health() })
            assertTrue(seen.isEmpty())
        }
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
}
