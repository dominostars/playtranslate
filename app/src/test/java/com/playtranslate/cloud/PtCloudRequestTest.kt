package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.cloud.PtCloudTestSupport.BASE_URL
import com.playtranslate.cloud.PtCloudTestSupport.CapturingExecutor
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.cloud.PtCloudTestSupport.onExecutor
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * `GET /health`'s status mapping, its 502 and its 410 included; `GET /v1/key`:
 * the key it is given as its bearer, its reading of the edge's answers and of
 * the account they carry, nothing recorded or reported, and a key no request
 * can carry never sent; the two sign-in calls, `POST /v1/signin` and
 * `POST /v1/signin/verify`: what they send (JSON, no bearer), their reading
 * of every answer in the brief's two tables, nothing recorded or reported,
 * and logs with no email, code or key; the two terms calls, `GET /v1/terms`
 * and `POST /v1/terms/accept`: what they send (the locale as a query and no
 * bearer; the key given, as JSON), their reading of every answer, a text
 * longer than an error body read whole, nothing recorded or reported, and
 * logs with no key or body; and `GET /v1/warm`: what it sends,
 * when it sends nothing, and the head start it hands the next frame's clock
 * line. And a stored key no request can carry, refused at the build on both
 * routes.
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

    private fun health(code: Int, seen: MutableList<Request> = mutableListOf(), body: String? = null): HealthStatus {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = {
                PtCloudTestSupport.cannedClient(code, body ?: if (code == 200) """{"ok":true}""" else "", seen = seen)
            },
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

    /** The edge's 502 while it cannot reach the host, with its own body or
     *  the tunnel's: offline, whatever the body says. */
    @Test fun `health reads a 502 as offline`() {
        for (body in listOf("""{"error":"origin unreachable","route":"vpc"}""", "", "<html>502 Bad Gateway</html>")) {
            assertEquals(body, HealthStatus.Offline, health(502, body = body))
        }
    }

    @Test fun `health reads a 410 service_closed as closed with its message, and another 410 as an unexpected answer`() {
        assertEquals(
            HealthStatus.Closed("PlayTranslate Cloud has closed. Thank you."),
            health(410, body = """{"error":"service_closed","message":"PlayTranslate Cloud has closed. Thank you."}"""),
        )
        assertEquals(HealthStatus.Closed(null), health(410, body = """{"error":"service_closed"}"""))
        for (body in listOf("""{"error":"gone"}""", "", "<html>410</html>")) {
            assertEquals(body, HealthStatus.Other(410), health(410, body = body))
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

    // ── GET /v1/key ────────────────────────────────────────────────────

    /** The key the page is saving, where [KEY] is the one stored. */
    private val typedKey = "typed-client-key"

    private fun checkKey(
        code: Int,
        body: String,
        seen: MutableList<Request> = mutableListOf(),
        key: String = typedKey,
    ): KeyCheck {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.cannedClient(code, body, seen = seen) },
        )
        return runBlocking { client.checkKey(key) }
    }

    @Test fun `the key check is a GET on v1 key with the key it is given, not the stored one, and no body`() {
        val seen = mutableListOf<Request>()
        checkKey(200, """{"ok":true}""", seen)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/v1/key", request.url.toString())
        assertEquals("Bearer $typedKey", request.header("Authorization"))
        assertNull(request.body)
    }

    @Test fun `checkKey reads source and entitled_until from the 200`() {
        assertEquals(
            KeyCheck.Accepted("trial", TRIAL_END_MS),
            checkKey(200, """{"ok":true,"source":"trial","entitled_until":"2026-11-03T12:00:00.000Z"}"""),
        )
        assertEquals(
            "a source with a null end is open-ended",
            KeyCheck.Accepted("manual", null),
            checkKey(200, """{"ok":true,"source":"manual","entitled_until":null}"""),
        )
    }

    /** An older Worker sends `{"ok":true}` alone, and the new one does after
     *  its read of the account failed: the key is good, the account unknown.
     *  So is a 200 whose body does not decode: the status proves the key. */
    @Test fun `checkKey reads a 200 without source as an older Worker`() {
        for (body in listOf("""{"ok":true}""", "", "<html>ok</html>")) {
            assertEquals(body, KeyCheck.Accepted(null, null), checkKey(200, body))
        }
        // A date with no source names no account either: source is the test.
        val dateOnly = checkKey(200, """{"ok":true,"entitled_until":"2026-11-03T12:00:00.000Z"}""")
        assertNull((dateOnly as KeyCheck.Accepted).source)
    }

    @Test fun `checkKey reads last_source and ended_at from the 403`() {
        assertEquals(
            KeyCheck.Inactive("trial", TRIAL_END_MS),
            checkKey(403, """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""),
        )
        assertEquals(
            "nothing has ended, or an older Worker",
            KeyCheck.Inactive(null, null),
            checkKey(403, """{"error":"no_entitlement","last_source":null,"ended_at":null}"""),
        )
        assertEquals(KeyCheck.Inactive(null, null), checkKey(403, """{"error":"no_entitlement"}"""))
    }

    /** The terms gate's 403: a known key whose account is entitled and owes
     *  the current terms, the version the body names carried as it is. */
    @Test fun `checkKey reads a 403 terms_required as TermsRequired with its version`() {
        assertEquals(
            KeyCheck.TermsRequired(TERMS_VERSION),
            checkKey(403, """{"error":"terms_required","version":"2026-10-30"}"""),
        )
        assertEquals("a body that left the version out", KeyCheck.TermsRequired(null), checkKey(403, """{"error":"terms_required"}"""))
    }

    @Test fun `checkKey reads a 410 service_closed as closed, with its message`() {
        assertEquals(
            KeyCheck.Closed("PlayTranslate Cloud has closed. Thank you."),
            checkKey(410, """{"error":"service_closed","message":"PlayTranslate Cloud has closed. Thank you."}"""),
        )
        assertEquals(KeyCheck.Closed(null), checkKey(410, """{"error":"service_closed"}"""))
    }

    /** The edge's answers ("Key check" in docs/api.md): a 401 refuses the
     *  key whatever its body; every answer that is not one of the table's
     *  rows leaves it unchecked, a 403 or a 410 without its word included. */
    @Test fun `the key check refuses on a 401 and leaves every other answer unchecked`() {
        for (body in listOf("""{"error":"unauthorized"}""", "")) assertEquals(body, KeyCheck.Refused, checkKey(401, body))
        val unchecked = listOf(
            403 to """{"error":"forbidden"}""",
            403 to "",
            410 to """{"error":"gone"}""",
            410 to "",
            404 to """{"error":"not found"}""",
            429 to """{"error":"rate limited"}""",
            503 to """{"error":"auth unavailable"}""",
            500 to "",
        )
        for ((code, body) in unchecked) assertEquals("$code $body", KeyCheck.NotChecked, checkKey(code, body))
    }

    @Test fun `a key check with no connection is not checked`() {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(KeyCheck.NotChecked, runBlocking { client.checkKey(typedKey) })
    }

    /** Not a capture: whatever the check meets, the service's cooldown and
     *  the error tracker hear nothing of it. Each of these answers records a
     *  cooldown on a frame. */
    @Test fun `no key check records a cooldown or reports an attempt`() {
        val reported = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { reported += it }
        checkKey(401, """{"error":"unauthorized"}""")
        checkKey(403, """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}""")
        checkKey(410, """{"error":"service_closed","message":"closed"}""")
        checkKey(429, """{"error":"rate limited"}""")
        checkKey(500, "")
        assertNull(PtCloudService.unavailableUntil())
        assertTrue(reported.isEmpty())
    }

    @Test fun `the key check logs its status and never the key`() {
        ShadowLog.clear()
        checkKey(401, """{"error":"unauthorized"}""")
        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }
        assertTrue(lines.toString(), "key check status=401" in lines)
        assertFalse(lines.toString(), lines.any { typedKey in it })
    }

    // ── POST /v1/signin and /v1/signin/verify ──────────────────────────

    private fun signInClient(
        code: Int,
        body: String,
        retryAfter: String? = null,
        seen: MutableList<Request> = mutableListOf(),
    ) = PtCloudClient(
        PtCloudTestSupport.config(ctx),
        clientFactory = { PtCloudTestSupport.cannedClient(code, body, retryAfter, seen) },
    )

    private fun requestCode(code: Int, body: String, retryAfter: String? = null, seen: MutableList<Request> = mutableListOf()) =
        runBlocking { signInClient(code, body, retryAfter, seen).requestCode(EMAIL) }

    private fun verifyCode(code: Int, body: String, retryAfter: String? = null, seen: MutableList<Request> = mutableListOf()) =
        runBlocking { signInClient(code, body, retryAfter, seen).verifyCode(EMAIL, CODE, LABEL) }

    private fun Request.bodyJson(): JsonObject =
        Json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()) as JsonObject

    /** A key is stored, so a bearer riding along would show: the routes take
     *  none, and the stored key must never be sent to them. */
    @Test fun `requestCode posts the email as JSON with no bearer`() {
        val seen = mutableListOf<Request>()
        requestCode(200, SENT, seen = seen)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/signin", request.url.toString())
        assertNull(request.header("Authorization"))
        val type = request.body!!.contentType()!!
        assertEquals("application/json", "${type.type}/${type.subtype}")
        assertTrue("its length is known", request.body!!.contentLength() > 0)
        assertEquals(JsonObject(mapOf("email" to JsonPrimitive(EMAIL))), request.bodyJson())
    }

    @Test fun `verifyCode posts the email, the code and the label`() {
        val seen = mutableListOf<Request>()
        verifyCode(200, signedIn(), seen = seen)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/signin/verify", request.url.toString())
        assertNull(request.header("Authorization"))
        val type = request.body!!.contentType()!!
        assertEquals("application/json", "${type.type}/${type.subtype}")
        assertEquals(
            JsonObject(mapOf("email" to JsonPrimitive(EMAIL), "code" to JsonPrimitive(CODE), "label" to JsonPrimitive(LABEL))),
            request.bodyJson(),
        )
    }

    /** "What changed on the wire", item 1's table, row for row. */
    @Test fun `requestCode maps every answer`() {
        val rows = listOf(
            Triple(410, """{"error":"service_closed","message":"Closed for good."}""", null) to SignInAnswer.Closed("Closed for good."),
            Triple(404, """{"error":"not found"}""", null) to SignInAnswer.NotAvailable,
            Triple(429, """{"error":"rate limited"}""", "60") to SignInAnswer.RateLimited(60),
            Triple(415, """{"error":"unsupported media type"}""", null) to SignInAnswer.Unavailable(null),
            Triple(413, """{"error":"payload too large"}""", null) to SignInAnswer.Unavailable(null),
            Triple(400, """{"error":"invalid_email"}""", null) to SignInAnswer.BadAddress,
            Triple(503, """{"error":"auth unavailable"}""", "10") to SignInAnswer.Unavailable(10),
            Triple(429, """{"error":"cooldown"}""", "42") to SignInAnswer.RateLimited(42),
            Triple(429, """{"error":"too_many_codes"}""", "3600") to CodeRequestResult.TooManyCodes(3600),
            Triple(503, """{"error":"daily_cap"}""", "7200") to SignInAnswer.Unavailable(7200),
            Triple(503, """{"error":"service_offline"}""", "60") to SignInAnswer.Offline(60),
            Triple(503, """{"error":"mail_failed"}""", "60") to CodeRequestResult.MailFailed(60),
            Triple(200, SENT, null) to CodeRequestResult.Sent(600, 60),
            Triple(200, """{"ok":true,"expires_in":300,"resend_after":30}""", null) to CodeRequestResult.Sent(300, 30),
            Triple(200, """{"ok":true}""", null) to CodeRequestResult.Sent(600, 60),
            Triple(200, "<html>ok</html>", null) to SignInAnswer.Unavailable(null),
            Triple(410, """{"error":"gone"}""", null) to SignInAnswer.Unavailable(null),
            Triple(400, """{"error":"bad request"}""", null) to SignInAnswer.Unavailable(null),
            Triple(500, "", null) to SignInAnswer.Unavailable(null),
        )
        for ((answer, result) in rows) {
            val (code, body, retryAfter) = answer
            assertEquals("$code $body", result, requestCode(code, body, retryAfter))
        }
    }

    /** `cooldown` and `rate limited` both read as "a code may already be on
     *  its way"; `too_many_codes` has its own wording, so its own result. */
    @Test fun `requestCode keeps the 429's word, so too_many_codes is told from cooldown and rate limited`() {
        assertEquals(SignInAnswer.RateLimited(60), requestCode(429, """{"error":"rate limited"}""", "60"))
        assertEquals(SignInAnswer.RateLimited(17), requestCode(429, """{"error":"cooldown"}""", "17"))
        assertEquals(CodeRequestResult.TooManyCodes(30_000), requestCode(429, """{"error":"too_many_codes"}""", "30000"))
    }

    @Test fun `the Retry-After is read as whole seconds as given, and a missing or unreadable one is null`() {
        assertEquals(SignInAnswer.Unavailable(10), requestCode(503, """{"error":"auth unavailable"}""", "10"))
        assertEquals(SignInAnswer.Unavailable(null), requestCode(503, """{"error":"auth unavailable"}""", null))
        assertEquals(
            SignInAnswer.Unavailable(null),
            requestCode(503, """{"error":"auth unavailable"}""", "Wed, 21 Oct 2026 07:28:00 GMT"),
        )
        assertEquals(SignInAnswer.Offline(null), requestCode(503, """{"error":"service_offline"}""", "soon"))
    }

    /** Item 2's table, row for row. */
    @Test fun `verifyCode maps every answer`() {
        val rows = listOf(
            Triple(410, """{"error":"service_closed","message":"Closed for good."}""", null) to SignInAnswer.Closed("Closed for good."),
            Triple(404, """{"error":"not found"}""", null) to SignInAnswer.NotAvailable,
            Triple(429, """{"error":"rate limited"}""", "60") to SignInAnswer.RateLimited(60),
            Triple(415, """{"error":"unsupported media type"}""", null) to SignInAnswer.Unavailable(null),
            Triple(413, """{"error":"payload too large"}""", null) to SignInAnswer.Unavailable(null),
            Triple(400, """{"error":"invalid_email"}""", null) to SignInAnswer.BadAddress,
            Triple(400, """{"error":"bad request","detail":"code must be six digits"}""", null) to VerifyResult.BadAnswer,
            Triple(503, """{"error":"auth unavailable"}""", "10") to SignInAnswer.Unavailable(10),
            Triple(401, """{"error":"code_invalid","attempts_left":0}""", null) to VerifyResult.CodeInvalid(0),
            Triple(401, """{"error":"code_invalid","attempts_left":3}""", null) to VerifyResult.CodeInvalid(3),
            Triple(503, """{"error":"service_offline"}""", "60") to SignInAnswer.Offline(60),
            Triple(403, """{"error":"account_disabled"}""", null) to VerifyResult.Disabled,
            Triple(500, "", null) to SignInAnswer.Unavailable(null),
        )
        for ((answer, result) in rows) {
            val (code, body, retryAfter) = answer
            assertEquals("$code $body", result, verifyCode(code, body, retryAfter))
        }
    }

    /** The 200's fields: `entitled` decides which half of the account they
     *  are, and `trial` (whether this sign-in started one) is not read. */
    @Test fun `verifyCode reads the 200's key and account`() {
        assertEquals(
            VerifyResult.SignedIn(MINTED_KEY, KeyCheck.Accepted("trial", TRIAL_END_MS)),
            verifyCode(200, signedIn()),
        )
        assertEquals(
            "a second device signing in to an account on its trial: trial false, source trial",
            VerifyResult.SignedIn(MINTED_KEY, KeyCheck.Accepted("trial", TRIAL_END_MS)),
            verifyCode(200, signedIn(trial = false)),
        )
        assertEquals(
            VerifyResult.SignedIn(MINTED_KEY, KeyCheck.Accepted("kofi", null)),
            verifyCode(200, signedIn(source = "\"kofi\"", until = "null")),
        )
        assertEquals(
            "an address whose trial was used before: stored anyway, Join is next",
            VerifyResult.SignedIn(MINTED_KEY, KeyCheck.Inactive("trial", TRIAL_END_MS)),
            verifyCode(
                200,
                signedIn(entitled = false, source = "null", until = "null", lastSource = "\"trial\"", endedAt = "\"2026-11-03T12:00:00.000Z\""),
            ),
        )
        assertEquals(
            VerifyResult.SignedIn(MINTED_KEY, KeyCheck.Inactive(null, null)),
            verifyCode(200, signedIn(entitled = false, source = "null", until = "null")),
        )
    }

    /** A key that cannot be stored as a bearer, or a 200 the app cannot
     *  read, is BadAnswer, and nothing reaches the caller to store. */
    @Test fun `verifyCode refuses a 200 without a usable key`() {
        val bodies = listOf(
            signedIn().replace("\"key\":\"$MINTED_KEY\",", ""),
            signedIn().replace(MINTED_KEY, ""),
            signedIn().replace(MINTED_KEY, "pt_abc\\ndef"),
            signedIn().replace(MINTED_KEY, "pt_clé"),
            signedIn().replace("\"entitled\":true,", ""),
            """{"ok":true}""",
            "<html>ok</html>",
        )
        for (body in bodies) assertEquals(body, VerifyResult.BadAnswer, verifyCode(200, body))
    }

    @Test fun `the sign-in calls with no connection are unreachable`() {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(SignInAnswer.Unreachable, runBlocking { client.requestCode(EMAIL) })
        assertEquals(SignInAnswer.Unreachable, runBlocking { client.verifyCode(EMAIL, CODE, LABEL) })
    }

    /** Not a capture: each of these answers records a cooldown on a frame. */
    @Test fun `the sign-in calls record no cooldown and report no attempt`() {
        val reported = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { reported += it }
        requestCode(429, """{"error":"rate limited"}""", "60")
        requestCode(503, """{"error":"service_offline"}""", "60")
        requestCode(410, """{"error":"service_closed","message":"closed"}""")
        requestCode(500, "")
        verifyCode(401, """{"error":"code_invalid","attempts_left":0}""")
        verifyCode(403, """{"error":"account_disabled"}""")
        verifyCode(429, """{"error":"rate limited"}""", "60")
        verifyCode(200, signedIn())
        runBlocking {
            PtCloudClient(PtCloudTestSupport.config(ctx), clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) })
                .requestCode(EMAIL)
        }
        assertNull(PtCloudService.unavailableUntil())
        assertTrue(reported.isEmpty())
    }

    @Test fun `the sign-in logs carry neither the email, the code nor the key`() {
        ShadowLog.clear()
        requestCode(200, SENT)
        requestCode(429, """{"error":"too_many_codes"}""", "3600")
        verifyCode(401, """{"error":"code_invalid","attempts_left":2}""")
        verifyCode(200, signedIn())
        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }
        assertTrue(lines.toString(), "signin request status=200" in lines)
        assertTrue("a refusal names its word: $lines", "signin request status=429 error=too_many_codes" in lines)
        assertTrue(lines.toString(), "signin verify status=401 error=code_invalid" in lines)
        assertTrue(lines.toString(), "signin verify status=200" in lines)
        for (secret in listOf(EMAIL, CODE, MINTED_KEY, LABEL, "usr_")) {
            assertFalse("$secret in $lines", lines.any { secret in it })
        }
    }

    // ── GET /v1/terms and POST /v1/terms/accept ────────────────────────

    private fun termsClient(
        code: Int,
        body: String,
        retryAfter: String? = null,
        seen: MutableList<Request> = mutableListOf(),
    ) = PtCloudClient(
        PtCloudTestSupport.config(ctx),
        clientFactory = { PtCloudTestSupport.cannedClient(code, body, retryAfter, seen) },
    )

    private fun fetchTerms(code: Int, body: String, retryAfter: String? = null, seen: MutableList<Request> = mutableListOf()) =
        runBlocking { termsClient(code, body, retryAfter, seen).fetchTerms(LOCALE) }

    /** An acceptance carrying [MINTED_KEY], where [KEY] is the one stored. */
    private fun acceptTerms(code: Int, body: String, retryAfter: String? = null, seen: MutableList<Request> = mutableListOf()) =
        runBlocking { termsClient(code, body, retryAfter, seen).acceptTerms(MINTED_KEY, TERMS_VERSION, "en") }

    /** The text the Worker serves today, as the fixture holds it. */
    private fun shippedTerms(): String =
        PtCloudRequestTest::class.java.getResourceAsStream("/ptcloud/terms_en.md")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** A key is stored, so a bearer riding along would show: a person reads
     *  the terms before signing in, and the route takes none. */
    @Test fun `fetchTerms is a GET on v1 terms with the locale as a query and no bearer`() {
        val seen = mutableListOf<Request>()
        fetchTerms(200, terms(), seen = seen)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/v1/terms?locale=pt-BR", request.url.toString())
        assertEquals(LOCALE, request.url.queryParameter("locale"))
        assertNull(request.header("Authorization"))
        assertNull(request.body)
    }

    /** "Terms" in docs/api.md, row for row: the text with its version and
     *  the locale served, a Worker from before the route as nothing to
     *  show, the closed service with its message, and every other answer,
     *  a 200 the app cannot read included, as unavailable. */
    @Test fun `fetchTerms maps every answer`() {
        val rows = listOf(
            Triple(200, terms(), null) to TermsResult.Text(TERMS_VERSION, "en", TERMS_TEXT),
            Triple(200, terms(locale = "pt-br"), null) to TermsResult.Text(TERMS_VERSION, "pt-br", TERMS_TEXT),
            Triple(200, """{"version":"2026-10-30","locale":"en","format":"markdown"}""", null) to TermsResult.Unavailable,
            Triple(200, """{"locale":"en","format":"markdown","text":"# T"}""", null) to TermsResult.Unavailable,
            Triple(200, """{"version":"2026-10-30","format":"markdown","text":"# T"}""", null) to TermsResult.Unavailable,
            Triple(200, "<html>ok</html>", null) to TermsResult.Unavailable,
            Triple(200, "", null) to TermsResult.Unavailable,
            Triple(404, """{"error":"not found"}""", null) to TermsResult.NotAvailable,
            Triple(410, """{"error":"service_closed","message":"Closed for good."}""", null) to TermsResult.Closed("Closed for good."),
            Triple(410, """{"error":"service_closed"}""", null) to TermsResult.Closed(null),
            Triple(410, """{"error":"gone"}""", null) to TermsResult.Unavailable,
            Triple(410, "", null) to TermsResult.Unavailable,
            Triple(429, """{"error":"rate limited"}""", "60") to TermsResult.Unavailable,
            Triple(503, """{"error":"terms_misconfigured"}""", null) to TermsResult.Unavailable,
            Triple(500, "", null) to TermsResult.Unavailable,
        )
        for ((answer, result) in rows) {
            val (code, body, retryAfter) = answer
            assertEquals("$code $body", result, fetchTerms(code, body, retryAfter))
        }
    }

    @Test fun `fetchTerms with no connection is unreachable`() {
        val client = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(TermsResult.Unreachable, runBlocking { client.fetchTerms(LOCALE) })
    }

    /** The whole text rides in the answer, which an error body's 4,096-byte
     *  read would cut: the shipped text's answer is longer than that and
     *  reads whole, as does a much longer one, while an answer past the
     *  terms' own 64 KiB read does not decode and reads as unavailable. */
    @Test fun `a terms answer over 4 KB is read whole, and one over 64 KiB reads as unavailable`() {
        val shipped = shippedTerms()
        assertTrue("the premise: ${terms(text = shipped).length}", terms(text = shipped).length > 4_096)
        assertEquals(TermsResult.Text(TERMS_VERSION, "en", shipped), fetchTerms(200, terms(text = shipped)))
        val long = "Terms. ".repeat(3_000)
        assertEquals(TermsResult.Text(TERMS_VERSION, "en", long), fetchTerms(200, terms(text = long)))
        assertEquals(TermsResult.Unavailable, fetchTerms(200, terms(text = "x".repeat(70_000))))
    }

    /** The key it is given, not the stored one, and the agreement as the
     *  route reads it. */
    @Test fun `acceptTerms is a JSON POST on v1 terms accept with the bearer, the version and the locale`() {
        val seen = mutableListOf<Request>()
        acceptTerms(200, ACCEPTED, seen = seen)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/terms/accept", request.url.toString())
        assertEquals("Bearer $MINTED_KEY", request.header("Authorization"))
        val type = request.body!!.contentType()!!
        assertEquals("application/json", "${type.type}/${type.subtype}")
        assertTrue("its length is known", request.body!!.contentLength() > 0)
        assertEquals(
            JsonObject(mapOf("version" to JsonPrimitive(TERMS_VERSION), "locale" to JsonPrimitive("en"))),
            request.bodyJson(),
        )
    }

    /** "Accepting the terms" in docs/api.md, row for row: recorded, a newer
     *  text with its version, a refused key, the closed service, and every
     *  other answer as not recorded. */
    @Test fun `acceptTerms maps every answer`() {
        val rows = listOf(
            Triple(200, ACCEPTED, null) to TermsAcceptResult.Accepted,
            Triple(200, "", null) to TermsAcceptResult.Accepted,
            Triple(409, """{"error":"terms_outdated","version":"2026-11-15"}""", null) to TermsAcceptResult.Outdated("2026-11-15"),
            Triple(409, """{"error":"terms_outdated"}""", null) to TermsAcceptResult.Outdated(null),
            Triple(409, """{"error":"conflict"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(409, "", null) to TermsAcceptResult.NotRecorded,
            Triple(401, """{"error":"unauthorized"}""", null) to TermsAcceptResult.Refused,
            Triple(401, "", null) to TermsAcceptResult.Refused,
            Triple(410, """{"error":"service_closed","message":"Closed for good."}""", null) to TermsAcceptResult.Closed("Closed for good."),
            Triple(410, """{"error":"gone"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(503, """{"error":"terms_unavailable"}""", "10") to TermsAcceptResult.NotRecorded,
            Triple(503, """{"error":"auth unavailable"}""", "10") to TermsAcceptResult.NotRecorded,
            Triple(503, """{"error":"terms_misconfigured"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(429, """{"error":"rate limited"}""", "60") to TermsAcceptResult.NotRecorded,
            Triple(400, """{"error":"bad request","detail":"version must be YYYY-MM-DD"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(413, """{"error":"payload too large"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(415, """{"error":"unsupported media type"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(404, """{"error":"not found"}""", null) to TermsAcceptResult.NotRecorded,
            Triple(500, "", null) to TermsAcceptResult.NotRecorded,
        )
        for ((answer, result) in rows) {
            val (code, body, retryAfter) = answer
            assertEquals("$code $body", result, acceptTerms(code, body, retryAfter))
        }
    }

    /** No answer, and a key no request can carry (the page checks the key
     *  first and never sends one), both read as not recorded; the second
     *  sends nothing. */
    @Test fun `acceptTerms with no connection, or with a key no request can carry, is not recorded`() {
        val failing = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        assertEquals(TermsAcceptResult.NotRecorded, runBlocking { failing.acceptTerms(MINTED_KEY, TERMS_VERSION, "en") })
        val seen = mutableListOf<Request>()
        assertEquals(
            TermsAcceptResult.NotRecorded,
            runBlocking { termsClient(200, ACCEPTED, seen = seen).acceptTerms("abc\ndef", TERMS_VERSION, "en") },
        )
        assertTrue(seen.isEmpty())
    }

    /** Not a capture: each of these answers records a cooldown on a frame,
     *  and two transport failures in a row would engage the network ladder. */
    @Test fun `the terms calls record no cooldown and report no attempt`() {
        val reported = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { reported += it }
        fetchTerms(200, terms())
        fetchTerms(429, """{"error":"rate limited"}""", "60")
        fetchTerms(410, """{"error":"service_closed","message":"closed"}""")
        fetchTerms(500, "")
        acceptTerms(200, ACCEPTED)
        acceptTerms(401, """{"error":"unauthorized"}""")
        acceptTerms(429, """{"error":"rate limited"}""", "60")
        acceptTerms(503, """{"error":"terms_unavailable"}""", "10")
        acceptTerms(410, """{"error":"service_closed","message":"closed"}""")
        val failing = PtCloudClient(
            PtCloudTestSupport.config(ctx),
            clientFactory = { PtCloudTestSupport.failingClient(IOException("refused")) },
        )
        repeat(2) {
            runBlocking { failing.fetchTerms(LOCALE) }
            runBlocking { failing.acceptTerms(MINTED_KEY, TERMS_VERSION, "en") }
        }
        assertNull(PtCloudService.unavailableUntil())
        assertTrue(reported.isEmpty())
    }

    @Test fun `the terms logs carry the status and the server's word, never the key, the locale or a body`() {
        ShadowLog.clear()
        fetchTerms(200, terms(text = shippedTerms()))
        fetchTerms(429, """{"error":"rate limited"}""", "60")
        acceptTerms(200, ACCEPTED)
        acceptTerms(409, """{"error":"terms_outdated","version":"2026-11-15"}""")
        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }
        assertTrue(lines.toString(), "terms status=200" in lines)
        assertTrue("a refusal names its word: $lines", "terms status=429 error=rate limited" in lines)
        assertTrue(lines.toString(), "terms accept status=200" in lines)
        assertTrue(lines.toString(), "terms accept status=409 error=terms_outdated" in lines)
        for (secret in listOf(MINTED_KEY, KEY, LOCALE, TERMS_VERSION, "2026-11-15", "Version:", "Terms and Privacy", "**")) {
            assertFalse("$secret in $lines", lines.any { secret in it })
        }
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

    private companion object {
        const val EMAIL = "player@example.com"
        const val CODE = "482913"
        const val LABEL = "AYN Thor"
        /** `pt_` and 43 characters, as the server mints them. */
        const val MINTED_KEY = "pt_0123456789abcdefghijABCDEFGHIJ0123456789_-x"
        /** `{"ok":true,"expires_in":600,"resend_after":60}`, the request's 200. */
        const val SENT = """{"ok":true,"expires_in":600,"resend_after":60}"""
        /** 2026-11-03T12:00:00.000Z, the brief's trial end. */
        const val TRIAL_END_MS = 1_793_707_200_000L
        /** The device's locale as the cloud page sends it
         *  (`Locale.toLanguageTag`). */
        const val LOCALE = "pt-BR"
        /** The terms' version, the date on the text's `Version:` line. */
        const val TERMS_VERSION = "2026-10-30"
        /** The acceptance's 200. */
        const val ACCEPTED = """{"ok":true,"version":"2026-10-30"}"""
        /** A short text in the server's subset. */
        const val TERMS_TEXT = "# PlayTranslate Cloud: Terms and Privacy\n\nVersion: 2026-10-30\n\n**Your account.** One per person."

        /** The terms' 200 in the route's shape, [text] escaped as JSON. */
        fun terms(version: String = TERMS_VERSION, locale: String = "en", text: String = TERMS_TEXT) =
            """{"version":"$version","locale":"$locale","format":"markdown","text":${JsonPrimitive(text)}}"""

        /** A verify 200 in the brief's shape; each JSON value as written. */
        fun signedIn(
            trial: Boolean = true,
            entitled: Boolean = true,
            source: String = "\"trial\"",
            until: String = "\"2026-11-03T12:00:00.000Z\"",
            lastSource: String = "null",
            endedAt: String = "null",
        ) = """{"ok":true,"key":"$MINTED_KEY","user_id":"usr_abc123","trial":$trial,"entitled":$entitled,""" +
            """"source":$source,"entitled_until":$until,"last_source":$lastSource,"ended_at":$endedAt}"""
    }
}
