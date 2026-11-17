package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.BuildConfig
import com.playtranslate.TranslationCache
import com.playtranslate.ask.AskPreset
import com.playtranslate.ask.AskPresets
import com.playtranslate.ask.AskPrompts
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskState
import com.playtranslate.cloud.PtCloudTestSupport.BASE_URL
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CompletionFailedException
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.SentenceAnswer
import com.playtranslate.translation.SentenceQuestion
import com.playtranslate.translation.StallBodyUntilCancelled
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationErrors
import com.playtranslate.translation.llm.LlmPromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PlayTranslate Cloud answering the Ask section's questions
 * ([PtCloudBackend] as a [com.playtranslate.translation.SentenceAnswerer])
 * over `POST /v1/ask` ([PtCloudClient.postAsk]), against a fake server:
 *  - the wire: the question's parts and never its two messages, the codes as
 *    given, the label, the app's build, and the caps checked before a call;
 *  - every status of a 200: `ok` with its text as it is, marked cut when
 *    the output cap ended it, `out_of_scope` as the service's answer, each class of
 *    `failed`, and a 200 that is no answer;
 *  - every HTTP answer: those that are the route's own (`429 ask_limit`,
 *    which pauses the answers and leaves the cooldown translation and OCR
 *    share alone, a 404 and a 500, which record nothing where the other
 *    routes' start a cooldown, and a 503), and those the edge gives on
 *    every route, which mean what they mean on the other two (a 401, a
 *    withholding 403 with its one report, a 410, the edge's own 429, a
 *    502);
 *  - the transport, the route's own timeout, and cancellation;
 *  - the registry's pass over it: the 404 and `ask_limit` fall through to
 *    the next service, the paused cloud is skipped with no request while a
 *    translation still goes to it, `out_of_scope` ends the pass, and one
 *    question end to end from the Ask session to the wire;
 *  - Try again end to end: it asks the cloud through the shared cooldown,
 *    whose end its answer is, and through the pause, whose refusal names
 *    the cloud again;
 *  - logs and messages free of content.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudAskTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The cooldown's clock, frozen at the test's start on the wall clock's
     *  scale (the registry compares cooldowns with the wall clock). */
    private val clock = AtomicLong(System.currentTimeMillis())
    private val now: Long get() = clock.get()
    private lateinit var cooldown: CooldownState
    private lateinit var config: PtCloudConfig
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())

    /** Every request the fake server saw, in order. */
    private val seen = Collections.synchronizedList(mutableListOf<Request>())

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        // Both switches on with the key stored, installed as the service's
        // settings too: the service acts on a 403 and on the daily cap only
        // for the key it holds.
        config = PtCloudTestSupport.switchedOn(ctx)
        PtCloudService.installForTest(cooldown, config)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
        LlmPromptTemplates.resetOverrides()
    }

    @After fun tearDown() {
        sessionScope.cancel()
        // Before the uninstall: a registry walk reaches the cooldown.
        TranslationBackendRegistry.close()
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationErrors.resetForTest()
        TranslationCache.shared.resetForTest()
        LlmPromptTemplates.resetOverrides()
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** The backend on [http], with the service's cooldown and settings. */
    private fun backend(http: OkHttpClient): PtCloudBackend {
        val client = PtCloudClient(config, clientFactory = { http })
        return PtCloudBackend(client = { client }, config = { config })
    }

    /** The fake server: answers the [n]th call (from 0) by its path, as a
     *  status code, a body and a `Retry-After`. */
    private fun server(answer: (path: String, n: Int) -> Triple<Int, String, String?>): OkHttpClient {
        val calls = AtomicInteger()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += request
            val (code, body, retryAfter) = answer(request.url.encodedPath, calls.getAndIncrement())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    /** A server answering every call [code] with [body]. */
    private fun answering(code: Int, body: String, retryAfter: String? = null) =
        server { _, _ -> Triple(code, body, retryAfter) }

    private fun bodyOf(request: Request) = Buffer().also { request.body!!.writeTo(it) }

    private fun json(request: Request): JsonObject = Json.parseToJsonElement(bodyOf(request).readUtf8()).jsonObject

    /** The asks among the requests the server saw. */
    private fun asks(): List<Request> = seen.filter { it.url.encodedPath == "/v1/ask" }

    /** A question whose two messages are markers no request may carry. */
    private fun question(
        sentence: String = SENTENCE,
        wording: String = WORDING,
        source: String = "ja",
        target: String = "zh-Hant",
    ) = SentenceQuestion(source, target, sentence, wording, system = SYSTEM_MARK, user = USER_MARK)

    /** A 200 in the server's shape, every field it sends. */
    private fun answer(
        status: String,
        text: String = "",
        finish: String? = "stop",
        error: String? = null,
        used: Int = 12,
    ): String = buildJsonObject {
        put("part", "answer")
        put("schema_version", 1)
        put("ask_id", "ask-1")
        put("status", status)
        put("answer", text)
        if (finish != null) put("finish_reason", finish)
        if (error != null) put("error", Json.parseToJsonElement(error))
        putJsonObject("engine") { put("model", MODEL); put("service", "0.1.0") }
        putJsonObject("usage") { put("input_tokens", 345); put("output_tokens", 138) }
        putJsonObject("limits") { put("used", used); put("cap", 100) }
        putJsonObject("timings") { put("first_token_ms", 680); put("answer_ms", 1290); put("total_ms", 1301) }
    }.toString()

    private fun ok(text: String = ANSWER, finish: String = "stop") = answer("ok", text, finish)

    private fun failed(error: String?) = answer("failed", finish = null, error = error)

    private fun askLimit(retryAfterS: String? = "3600") =
        """{"error":"ask_limit","detail":"100 answers a day","limit":100""" +
            (retryAfterS?.let { ""","retry_after_s":$it""" } ?: "") + "}"

    private fun thrownBy(block: suspend () -> Unit): Throwable =
        runCatching { runBlocking { block() } }.exceptionOrNull() ?: throw AssertionError("expected a throw")

    private fun assertNoCooldown() = assertNull(cooldown.unavailableUntil())

    private fun assertNoPause() = assertNull(PtCloudService.askLimitedUntil())

    private fun assertCooldown(untilMs: Long, cause: CooldownCause, description: String) {
        assertEquals(untilMs, cooldown.unavailableUntil())
        assertEquals(cause, cooldown.unavailableCause())
        assertEquals(description, cooldown.unavailableDescription())
    }

    /** The pause runs [seconds] from the answer: between the test's start
     *  and now, on the wall clock. */
    private fun assertPaused(seconds: Long, startedAtMs: Long) {
        val until = PtCloudService.askLimitedUntil()
        assertNotNull("the answers are paused", until)
        assertTrue("until $until", until!! in startedAtMs + seconds * 1000..System.currentTimeMillis() + seconds * 1000)
    }

    /** One failing ask: the thrown [PtCloudReadException]. */
    private fun failure(http: OkHttpClient): PtCloudReadException {
        val e = thrownBy { backend(http).answer(question()) }
        assertTrue("$e", e is PtCloudReadException)
        return e as PtCloudReadException
    }

    /** A failing ask that reports nothing, as every one does but a
     *  withholding 403. */
    private fun quietFailure(http: OkHttpClient): PtCloudReadException =
        failure(http).also { assertTrue("nothing is reported: $attempts", attempts.isEmpty()) }

    private fun http(code: Int, body: String = "", retryAfter: String? = null) =
        quietFailure(answering(code, body, retryAfter))

    // ── The wire ───────────────────────────────────────────────────────

    @Test fun `the call is a JSON POST to v1 ask with the key and the question's parts, never its two messages`() {
        runBlocking { backend(answering(200, ok())).answer(question()) }

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/ask", request.url.toString())
        assertEquals("Bearer $KEY", request.header("Authorization"))
        assertNull("no Accept: one JSON object, no delta lines", request.header("Accept"))
        assertEquals("application/json", request.body!!.contentType().toString())
        assertEquals(bodyOf(request).size, request.body!!.contentLength())
        assertNotNull(request.tag(HttpCallClock::class.java))

        val body = json(request)
        assertEquals(setOf("source", "target", "sentence", "question", "ask_id", "client"), body.keys)
        assertEquals("ja", body["source"]!!.jsonPrimitive.content)
        assertEquals("the code as the app holds it, never a name", "zh-Hant", body["target"]!!.jsonPrimitive.content)
        assertEquals(SENTENCE, body["sentence"]!!.jsonPrimitive.content)
        assertEquals(WORDING, body["question"]!!.jsonPrimitive.content)
        assertTrue(body["ask_id"]!!.jsonPrimitive.content.matches(Regex("""ask-\d+""")))
        val client = body["client"]!!.jsonObject
        assertEquals(setOf("app_version"), client.keys)
        assertEquals(BuildConfig.VERSION_NAME, client["app_version"]!!.jsonPrimitive.content)

        val sent = bodyOf(request).readUtf8()
        assertFalse("the server writes the prompt: $sent", sent.contains(SYSTEM_MARK) || sent.contains(USER_MARK))
    }

    @Test fun `the build's version name is one the server takes`() {
        // Anything else and the server answers the whole call 422.
        assertTrue(BuildConfig.VERSION_NAME, Regex("""[A-Za-z0-9._+-]{1,32}""").matches(BuildConfig.VERSION_NAME))
    }

    @Test fun `a sentence or a question over 1,000 code points is refused before any call, and 1,000 go out`() {
        val b = backend(answering(200, ok()))
        for (asked in listOf(question(sentence = "a".repeat(1_001)), question(wording = "a".repeat(1_001)))) {
            val e = thrownBy { b.answer(asked) }
            assertTrue("$e", e is StructuralFailureException)
            e as StructuralFailureException
            assertEquals(BackendFailure(BackendFailureKind.REJECTED), e.failure)
            assertFalse("nothing was sent", e.serverAnswered)
        }
        assertTrue(seen.isEmpty())
        assertNoCooldown()
        assertNoPause()

        // 1,000 code points of two UTF-16 units each, in both.
        val astral = "𠀀".repeat(1_000)
        runBlocking { b.answer(question(sentence = astral, wording = astral)) }
        assertEquals(astral, json(seen.single())["sentence"]!!.jsonPrimitive.content)
    }

    // ── A 200, by its status ───────────────────────────────────────────

    @Test fun `ok is the answer's text as it is, and nothing is recorded or reported`() {
        val text = "  第一行。\n\n第二行: \"引用\"  "
        val out = runBlocking { backend(answering(200, ok(text))).answer(question()) }

        assertEquals(SentenceAnswer.Text(text), out)
        assertNoCooldown()
        assertNoPause()
        assertTrue(attempts.isEmpty())
    }

    @Test fun `an answer cut at the output cap is still the answer, marked as cut, and no other finish marks one`() {
        val cut = runBlocking { backend(answering(200, ok("Cut off mid", finish = "length"))).answer(question()) }
        assertEquals(SentenceAnswer.Text("Cut off mid", cut = true), cut)
        assertNoCooldown()
        assertNoPause()

        for (finish in listOf("stop", null, "a reason from the future")) {
            val whole = runBlocking { backend(answering(200, answer("ok", ANSWER, finish))).answer(question()) }
            assertEquals("finish $finish", SentenceAnswer.Text(ANSWER, cut = false), whole)
        }
    }

    @Test fun `out_of_scope is the service's answer, not a failure`() {
        val out = runBlocking { backend(answering(200, answer("out_of_scope"))).answer(question()) }

        assertEquals(SentenceAnswer.OutOfScope, out)
        assertEquals("asked once", 1, seen.size)
        assertNoCooldown()
        assertNoPause()
        assertTrue(attempts.isEmpty())
    }

    @Test fun `a failed answer is its class's kind and records nothing, whatever the class`() {
        val kinds = listOf(
            """{"class":"rate_limited","http_status":429,"retry_after_s":12}""" to BackendFailure(BackendFailureKind.RATE_LIMITED, 429),
            """{"class":"quota","http_status":429,"retry_after_s":3600}""" to BackendFailure(BackendFailureKind.DAILY_QUOTA, 429),
            """{"class":"server_error","http_status":503}""" to BackendFailure(BackendFailureKind.SERVER_ERROR, 503),
            """{"class":"config","http_status":403}""" to BackendFailure(BackendFailureKind.SERVER_ERROR, 403),
            """{"class":"bad_output","http_status":200,"message":"empty answer"}""" to BackendFailure(BackendFailureKind.BAD_RESPONSE, 200),
            """{"class":"deadline"}""" to BackendFailure(BackendFailureKind.TIMEOUT),
            // The provider's 200 is not the app's: REJECTED's wording would show it.
            """{"class":"blocked","http_status":200,"message":"SAFETY"}""" to BackendFailure(BackendFailureKind.REJECTED),
            """{"class":"a class from the future"}""" to BackendFailure.BAD_RESPONSE,
            null to BackendFailure.BAD_RESPONSE,
        )
        for ((error, expected) in kinds) {
            assertEquals("$error", expected, quietFailure(answering(200, failed(error))).failure)
            // Where a translation's rate_limited, quota and server_error each
            // start the shared cooldown.
            assertNoCooldown()
            assertNoPause()
        }
        assertEquals("one call each, no retry", kinds.size, seen.size)
    }

    @Test fun `a 200 that is no answer is BAD_RESPONSE with nothing recorded`() {
        val bodies = listOf(
            "<html>502 Bad Gateway</html>",
            "",
            """{"part":"answer","schema_version":1}""",
            answer("ok", ANSWER).replace("\"part\":\"answer\"", "\"part\":\"translation\""),
            answer("ok", ANSWER).replace("\"schema_version\":1", "\"schema_version\":2"),
            answer("ok", " \n"),
            answer("pending", ANSWER),
        )
        for (body in bodies) {
            assertEquals(body, BackendFailure.BAD_RESPONSE, quietFailure(answering(200, body)).failure)
            assertNoCooldown()
            assertNoPause()
        }
    }

    // ── The route's own HTTP answers ───────────────────────────────────

    @Test fun `429 ask_limit pauses the answers for its seconds and leaves the shared cooldown alone`() {
        val start = System.currentTimeMillis()
        val b = backend(server { path, _ ->
            if (path == "/v1/ask") Triple(429, askLimit("3600"), "3600")
            else Triple(200, PtCloudTestSupport.part2("ok", """[{"id":0,"translation":"A"}]"""), null)
        })

        val e = thrownBy { b.answer(question()) } as PtCloudReadException

        assertEquals(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429), e.failure)
        assertTrue("nothing is reported: $attempts", attempts.isEmpty())
        assertPaused(3_600, start)
        assertEquals(PtCloudService.askLimitedUntil(), b.answersUnavailableUntil())
        // The cooldown translation and OCR share: untouched.
        assertNoCooldown()
        assertNull(b.unavailableUntil())
        assertFalse(PtCloudService.isCoolingDown())
        assertNull(
            "a capture still goes to the cloud",
            PtCloudService.refusal(config, SourceLangId.JA, deviceOnly = false, internet = InternetState.VALIDATED),
        )
        assertEquals("and so does a translation", "A", runBlocking { b.translate("一", "ja", "en") })
        assertTrue(config.ocrEnabled && config.translateEnabled)
    }

    @Test fun `the edge's own 429 is the shared cooldown until its Retry-After, and no pause`() {
        val e = http(429, """{"error":"rate limited"}""", retryAfter = "60")

        assertEquals(BackendFailure(BackendFailureKind.RATE_LIMITED, 429), e.failure)
        assertCooldown(now + 60_000, CooldownCause.RATE_LIMITED, "Rate limited")
        assertNoPause()
    }

    @Test fun `a 429 that names no error is the shared one`() {
        for (body in listOf("", "{}", "<html>429</html>", """{"error":"ask limit"}""")) {
            cooldown.resetCooldown()
            assertEquals(body, BackendFailureKind.RATE_LIMITED, http(429, body, retryAfter = "30").failure.kind)
            assertCooldown(now + 30_000, CooldownCause.RATE_LIMITED, "Rate limited")
            assertNoPause()
        }
    }

    @Test fun `ask_limit's seconds are read in any number form, held to a day, and none pauses nothing`() {
        val start = System.currentTimeMillis()
        http(429, askLimit("3600.0"))
        assertPaused(3_600, start)

        PtCloudService.installForTest(cooldown, config)
        http(429, askLimit("0.2"))
        assertPaused(1, start)

        // A number in another unit cannot pause the answers for good.
        PtCloudService.installForTest(cooldown, config)
        http(429, askLimit("43200000"))
        assertPaused(86_400, start)

        for (none in listOf(null, "0", "-5")) {
            PtCloudService.installForTest(cooldown, config)
            assertEquals(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429), http(429, askLimit(none), retryAfter = "3600").failure)
            assertNoPause()
            assertNoCooldown()
        }
    }

    @Test fun `a 404 is REJECTED with nothing recorded, with a body or without, where a translate call's empty 404 is the service switched off`() {
        for (body in listOf("""{"error":"not found"}""", "")) {
            assertEquals(body, BackendFailure(BackendFailureKind.REJECTED, 404), http(404, body).failure)
            assertNoCooldown()
            assertNoPause()
        }
        // The contrast: the same empty 404 to a translate call.
        thrownBy { backend(answering(404, "")).translateBatch(listOf("一", "二"), "ja", "en") }
        assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
    }

    @Test fun `a 500 is SERVER_ERROR with nothing recorded, where a translate call's climbs the ladder`() {
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 500), http(500, """{"error":"internal"}""").failure)
        assertNoCooldown()
        assertNoPause()
        // The contrast: the same 500 to a translate call.
        thrownBy { backend(answering(500, """{"error":"internal"}""")).translateBatch(listOf("一", "二"), "ja", "en") }
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    @Test fun `a 503 is SERVER_ERROR with nothing recorded, ask_unavailable like any other`() {
        for (body in listOf("""{"error":"ask_unavailable"}""", """{"error":"auth unavailable"}""", "")) {
            assertEquals(body, BackendFailure(BackendFailureKind.SERVER_ERROR, 503), http(503, body).failure)
            assertNoCooldown()
            assertNoPause()
        }
    }

    @Test fun `a 413, 415 or 422 is REJECTED with nothing recorded`() {
        for (code in listOf(413, 415, 422)) {
            assertEquals(BackendFailure(BackendFailureKind.REJECTED, code), http(code, """{"detail":"sentence over 1000 characters"}""").failure)
            assertNoCooldown()
            assertNoPause()
        }
    }

    // ── What every route shares ────────────────────────────────────────

    @Test fun `401 is AUTH on the rate-limit ladder, as on the other routes`() {
        assertEquals(BackendFailure(BackendFailureKind.AUTH, 401), http(401, """{"error":"Unauthorized"}""").failure)
        assertCooldown(now + 60_000, CooldownCause.AUTH, "Bad key")
        assertNoPause()
    }

    @Test fun `a 403 no_entitlement withholds the account, turns both switches off, records no cooldown and is reported once`() {
        val body = """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""

        val e = failure(answering(403, body))

        assertEquals(BackendFailure(BackendFailureKind.TRIAL_ENDED, 403), e.failure)
        assertNoCooldown()
        assertNoPause()
        val account = config.account!!
        assertFalse("the record is written as ended: $account", account.active)
        assertEquals("trial", account.lastSource)
        assertEquals(KEY, config.clientKey)
        assertFalse(config.ocrEnabled)
        assertFalse(config.translateEnabled)
        assertFalse(config.admitted)
        // The one telling: the registry's pass over the services that answer
        // questions reports nothing, and the switch-off is silent.
        val attempt = attempts.single()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertEquals(PtCloudService.DISPLAY_NAME, attempt.serviceName)
        assertEquals(BackendFailure(BackendFailureKind.TRIAL_ENDED, 403), attempt.failure)
        assertTrue(attempt.reachedServer)
    }

    @Test fun `a 403 terms_required withholds the account as owing the terms and is reported once`() {
        val e = failure(answering(403, """{"error":"terms_required","version":"2026-10-30"}"""))

        assertEquals(BackendFailure(BackendFailureKind.TERMS_REQUIRED, 403), e.failure)
        assertNoCooldown()
        assertEquals(PtCloudAccount.Standing.TERMS_REQUIRED, config.standing())
        assertFalse(config.ocrEnabled)
        assertFalse(config.translateEnabled)
        assertEquals(BackendFailure(BackendFailureKind.TERMS_REQUIRED, 403), attempts.single().failure)
    }

    @Test fun `any other 403 is REJECTED with nothing recorded, reported or switched`() {
        for (body in listOf("""{"error":"forbidden"}""", "")) {
            assertEquals(BackendFailure(BackendFailureKind.REJECTED, 403), http(403, body).failure)
            assertNoCooldown()
            assertTrue(config.ocrEnabled && config.translateEnabled)
        }
    }

    @Test fun `a 410 service_closed is CLOSED on the ladder, any other 410 REJECTED with none`() {
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 410), http(410, """{"error":"gone"}""").failure)
        assertNoCooldown()
        assertEquals(
            BackendFailure(BackendFailureKind.CLOSED, 410),
            http(410, """{"error":"service_closed","message":"Thank you."}""").failure,
        )
        assertCooldown(now + 60_000, CooldownCause.CLOSED, "Closed")
        assertNoPause()
    }

    @Test fun `a 502 is the edge's NOT_RUNNING on the ladder, as on the other routes`() {
        assertEquals(
            BackendFailure(BackendFailureKind.NOT_RUNNING, 502),
            http(502, """{"error":"origin unreachable","route":"vpc"}""").failure,
        )
        assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
        assertNoPause()
    }

    // ── The transport, the timeout, cancellation ───────────────────────

    @Test fun `a transport failure is never counted against the service, where a translate call's second starts a cooldown`() {
        repeat(2) {
            assertEquals(
                BackendFailure(BackendFailureKind.UNREACHABLE),
                quietFailure(PtCloudTestSupport.failingClient(IOException("refused"))).failure,
            )
            assertEquals(
                BackendFailure(BackendFailureKind.TIMEOUT),
                quietFailure(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))).failure,
            )
        }
        assertNoCooldown()
        assertNoPause()

        // The contrast: two translate calls failing the same way.
        repeat(2) {
            thrownBy {
                backend(PtCloudTestSupport.failingClient(IOException("refused"))).translateBatch(listOf("一", "二"), "ja", "en")
            }
        }
        assertEquals(CooldownCause.CONNECTION_FAILED, cooldown.unavailableCause())
    }

    @Test fun `an ask waits 35 s, read and whole call, and a translate call keeps its own timeouts`() {
        val timeouts = Collections.synchronizedMap(mutableMapOf<String, Pair<Int, Long>>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            timeouts[path] = chain.readTimeoutMillis() to TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
            val body = if (path == "/v1/ask") ok() else PtCloudTestSupport.part2("ok", """[{"id":0,"translation":"A"}]""")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val b = backend(http)

        runBlocking {
            b.answer(question())
            b.translate("一", "ja", "en")
        }

        assertEquals("above the server's 30 s deadline", 35_000 to 35_000L, timeouts["/v1/ask"])
        assertEquals(22_000 to 25_000L, timeouts["/v1/translate"])
    }

    @Test fun `a cancelled ask cancels its call, records nothing, reports nothing, and rethrows`() {
        val entered = Semaphore(0)
        val cancelledOnTheWire = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.release()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled()) {
                check(System.nanoTime() < deadline) { "the call was never cancelled" }
                Thread.sleep(5)
            }
            cancelledOnTheWire.incrementAndGet()
            throw IOException("Canceled")
        }.build()
        val b = backend(http)
        repeat(2) {
            val e = runCatching {
                runBlocking {
                    val call = async(Dispatchers.Default) { b.answer(question()) }
                    assertTrue(entered.tryAcquire(5, TimeUnit.SECONDS))
                    call.cancel()
                    call.await()
                }
            }.exceptionOrNull()
            assertTrue("$e", e is CancellationException)
        }
        assertEquals("closing the call is how the server learns to stop", 2, cancelledOnTheWire.get())
        assertNoCooldown()
        assertNoPause()
        assertTrue(attempts.isEmpty())
    }

    @Test fun `an ask cancelled while its answer's body is still arriving cancels its call, records nothing, reports nothing, and rethrows`() {
        // What the headers carried, with the body stalled behind them: an
        // answer, and the edge's own 429, which would start the cooldown.
        for (code in listOf(200, 429)) {
            val stall = StallBodyUntilCancelled(code)
            val b = backend(OkHttpClient.Builder().addInterceptor(stall).build())
            val e = runCatching {
                runBlocking {
                    val call = async(Dispatchers.Default) { b.answer(question()) }
                    assertNotNull("$code: the answer's headers are in and its body is being read", stall.reading.poll(5, TimeUnit.SECONDS))
                    call.cancel()
                    call.await()
                }
            }.exceptionOrNull()
            assertTrue("$code: $e", e is CancellationException)
            assertEquals("$code: the call was cancelled under the body's read", 1, stall.cancelled.size)
            assertNoCooldown()
            assertNoPause()
            assertTrue(attempts.isEmpty())
        }
    }

    // ── Through the registry's pass ────────────────────────────────────

    @Test fun `the 404 falls through to the next service, which answers from the two messages, and nothing is recorded`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20, displayName = "Gemini")
        TranslationBackendRegistry.init(listOf(backend(answering(404, """{"error":"not found"}""")), gemini))
        val named = Collections.synchronizedList(mutableListOf<String>())

        val result = runBlocking { TranslationBackendRegistry.complete(question()) { named += it.displayName } }

        assertSame(gemini, result.backend)
        assertEquals(SentenceAnswer.Text("answer-from-gemini"), result.answer)
        assertEquals(SYSTEM_MARK to USER_MARK, gemini.lastPrompt)
        assertEquals(listOf("PlayTranslate Cloud", "Gemini"), named)
        assertEquals("the cloud was asked once", 1, asks().size)
        assertNoCooldown()
        assertNoPause()
        assertTrue("an ask's failure never reaches the pill: $attempts", attempts.isEmpty())

        // And again: no cooldown and no pause keep the cloud from being asked.
        runBlocking { TranslationBackendRegistry.complete(question()) }
        assertEquals(2, asks().size)
    }

    @Test fun `ask_limit falls through, then the cloud is skipped for questions with no request while translations still go to it`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20, displayName = "Gemini")
        val http = server { path, _ ->
            if (path == "/v1/ask") Triple(429, askLimit("3600"), "3600")
            else Triple(200, PtCloudTestSupport.part2("ok", """[{"id":0,"translation":"A"}]"""), null)
        }
        TranslationBackendRegistry.init(listOf(backend(http), gemini))
        val named = Collections.synchronizedList(mutableListOf<String>())
        fun ask() = runBlocking { TranslationBackendRegistry.complete(question()) { named += it.displayName } }

        assertSame(gemini, ask().backend)
        assertEquals(listOf("PlayTranslate Cloud", "Gemini"), named)
        assertEquals(1, asks().size)

        // Paused: never named, never sent to.
        named.clear()
        assertSame(gemini, ask().backend)
        assertEquals(listOf("Gemini"), named)
        assertEquals("no second request", 1, asks().size)

        // Translation is another matter: the cloud is still the first
        // service, and translates.
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        val translated = runBlocking { TranslationBackendRegistry.translate("一", "ja", "en") }
        assertEquals(PtCloudService.ID, translated.backend.id)
        assertEquals("A", translated.text)
        assertEquals(0, gemini.translateCalls.get())
        assertNoCooldown()
    }

    @Test fun `with only the cloud, ask_limit names it with its reason, and the next question while paused names nothing`() {
        TranslationBackendRegistry.init(listOf(backend(answering(429, askLimit("3600")))))

        val first = thrownBy { TranslationBackendRegistry.complete(question()) } as CompletionFailedException
        assertEquals(PtCloudService.ID, first.backend!!.id)
        assertEquals(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429), (first.cause as PtCloudReadException).failure)

        val second = thrownBy { TranslationBackendRegistry.complete(question()) } as CompletionFailedException
        assertNull("skipped, so nothing was tried", second.backend)
        assertEquals(1, seen.size)
    }

    @Test fun `out_of_scope ends the pass, and the next service is not asked`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(backend(answering(200, answer("out_of_scope"))), gemini))

        val result = runBlocking { TranslationBackendRegistry.complete(question()) }

        assertEquals(SentenceAnswer.OutOfScope, result.answer)
        assertEquals(PtCloudService.DISPLAY_NAME, result.backend.displayName)
        assertEquals("no fall-through", 0, gemini.completeCalls.get())
        assertEquals("no retry", 1, seen.size)
    }

    @Test fun `a failed answer falls through, and with nobody else the failure names the cloud and its class`() {
        val body = failed("""{"class":"server_error","http_status":503}""")
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(backend(answering(200, body)), gemini))
        assertSame(gemini, runBlocking { TranslationBackendRegistry.complete(question()) }.backend)

        TranslationBackendRegistry.init(listOf(backend(answering(200, body))))
        val e = thrownBy { TranslationBackendRegistry.complete(question()) } as CompletionFailedException
        assertEquals(PtCloudService.ID, e.backend!!.id)
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 503), (e.cause as PtCloudReadException).failure)
        assertNoCooldown()
    }

    @Test fun `the shared cooldown keeps a question off the cloud too`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(backend(answering(200, ok())), gemini))
        cooldown.recordParsedFailure(System.currentTimeMillis() + 60_000, "Daily quota used", CooldownCause.DAILY_QUOTA)

        assertSame(gemini, runBlocking { TranslationBackendRegistry.complete(question()) }.backend)
        assertTrue(seen.isEmpty())
    }

    /** One question, from the Ask session to the wire and back: the app's
     *  own preset about a Traditional reader's sentence. */
    @Test fun `end to end, a preset is sent as its wording and the sentence, and the answer is credited to the cloud`() {
        TranslationBackendRegistry.init(listOf(backend(answering(200, ok(ANSWER)))))
        val session = AskSession(sessionScope)
        val grammar = AskQuestion.Preset(
            AskPreset(AskPresets.GRAMMAR_BREAKDOWN, "Grammar breakdown", AskPrompts.GRAMMAR_BREAKDOWN),
        )
        session.bind(AskSentence(SENTENCE, sourceLang = "ja", targetLang = "zh-Hant"))

        session.ask(grammar)
        val state = runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } }

        assertEquals(AskState.Answered(grammar, ANSWER, "PlayTranslate Cloud"), state)
        val body = json(seen.single())
        assertEquals("ja", body["source"]!!.jsonPrimitive.content)
        assertEquals("zh-Hant", body["target"]!!.jsonPrimitive.content)
        assertEquals(SENTENCE, body["sentence"]!!.jsonPrimitive.content)
        assertEquals(
            "Break this Japanese sentence down into its grammar points, in the order they appear. " +
                "For each one, quote the part of the sentence it covers, then explain in one or two " +
                "lines what it does there.",
            body["question"]!!.jsonPrimitive.content,
        )
    }

    @Test fun `end to end, a cut answer is shown as the cloud's answer, marked cut, and the next service is not asked`() {
        val gemini = FakeCompleterBackend(id = "gemini", priority = 20)
        TranslationBackendRegistry.init(listOf(backend(answering(200, ok("Cut off mid", finish = "length"))), gemini))
        val session = AskSession(sessionScope)
        val typed = AskQuestion.Custom(WORDING)
        session.bind(AskSentence(SENTENCE, sourceLang = "ja", targetLang = "en"))

        session.ask(typed)
        val state = runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } }

        assertEquals(AskState.Answered(typed, "Cut off mid", "PlayTranslate Cloud", cut = true), state)
        assertEquals("an answer, so no fall-through", 0, gemini.completeCalls.get())
        assertEquals(1, seen.size)
    }

    @Test fun `end to end, out_of_scope is the session's own state naming the cloud, and a typed question goes as typed`() {
        TranslationBackendRegistry.init(listOf(
            backend(answering(200, answer("out_of_scope"))),
            FakeCompleterBackend(id = "gemini", priority = 20),
        ))
        val session = AskSession(sessionScope)
        val typed = AskQuestion.Custom("What is the capital of {target}?")
        session.bind(AskSentence(SENTENCE, sourceLang = "ja", targetLang = "en"))

        session.ask(typed)
        val state = runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } }

        assertEquals(AskState.OutOfScope(typed, "PlayTranslate Cloud"), state)
        assertEquals("What is the capital of {target}?", json(seen.single())["question"]!!.jsonPrimitive.content)
    }

    /** Try again, from the Ask session to the wire: a question the shared
     *  cooldown kept off the cloud is put to it when the user asks again,
     *  and its answer ends the cooldown. */
    @Test fun `end to end, Try again asks the cloud through the shared cooldown, and its answer ends the cooldown`() {
        TranslationBackendRegistry.init(listOf(backend(answering(200, ok(ANSWER)))))
        cooldown.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited", CooldownCause.RATE_LIMITED)
        val session = AskSession(sessionScope)
        val typed = AskQuestion.Custom(WORDING)
        session.bind(AskSentence(SENTENCE, sourceLang = "ja", targetLang = "en"))

        session.ask(typed)
        assertEquals("passed by, so nobody is named", AskState.Failed(typed), session.state.value)
        assertTrue("and nothing was sent", seen.isEmpty())

        session.retry()
        val state = runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } }

        assertEquals(AskState.Answered(typed, ANSWER, "PlayTranslate Cloud"), state)
        assertEquals(1, asks().size)
        assertNoCooldown()
    }

    /** Try again through the pause of the cloud's answers: the question
     *  goes to the server, which refuses it again, and the failure names
     *  the cloud and its reason, where a skipped pass would name nobody. */
    @Test fun `end to end, Try again asks the cloud through its paused answers, and its refusal is named again`() {
        TranslationBackendRegistry.init(listOf(backend(answering(429, askLimit("3600")))))
        val session = AskSession(sessionScope)
        val typed = AskQuestion.Custom(WORDING)
        session.bind(AskSentence(SENTENCE, sourceLang = "ja", targetLang = "en"))
        val refused = AskState.Failed(typed, "PlayTranslate Cloud", BackendFailure(BackendFailureKind.DAILY_QUOTA, 429))

        session.ask(typed)
        assertEquals(refused, runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } })
        assertNotNull("precondition: the answers are paused", PtCloudService.askLimitedUntil())

        session.retry()
        val state = runBlocking { withTimeout(5_000) { session.state.first { it !is AskState.Asking } } }

        assertEquals(refused, state)
        assertEquals("the retry went to the server", 2, asks().size)
        assertNotNull("paused again by the server's answer", PtCloudService.askLimitedUntil())
        assertNoCooldown()
    }

    // ── Who is asked, and when the pause ends ──────────────────────────

    @Test fun `the cloud answers questions under the translation switch with a usable key, whatever the OCR switch says`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        for (translate in listOf(false, true)) for (ocr in listOf(false, true)) for (key in listOf("", KEY)) {
            prefs.ptCloudTranslateEnabled = translate
            prefs.ptCloudOcrEnabled = ocr
            prefs.ptCloudClientKey = key
            val b = PtCloudBackend(client = { error("no call") }, config = { config })
            assertEquals("translate $translate, ocr $ocr, key ${key.isNotEmpty()}", translate && key.isNotEmpty(), b.canAnswer())
            TranslationBackendRegistry.init(listOf(b))
            assertEquals(b.canAnswer(), TranslationBackendRegistry.hasCompleter())
        }
    }

    @Test fun `the pause is the stored key's, so another key's answer pauses nothing and a key change drops it`() {
        PtCloudService.onAskLimit("a-key-signed-out", 3_600)
        assertNoPause()

        PtCloudService.onAskLimit(KEY, 3_600)
        assertNotNull(PtCloudService.askLimitedUntil())
        assertNoCooldown()

        // Another account's key: its count is not the one the pause described.
        assertTrue(PtCloudService.saveClientKey("another-key"))
        assertNoPause()
    }

    @Test fun `a switch gesture drops the pause, as it drops the cooldown`() {
        PtCloudService.onAskLimit(KEY, 3_600)
        PtCloudService.setTranslateEnabled(true)
        assertNoPause()

        PtCloudService.onAskLimit(KEY, 3_600)
        PtCloudService.setOcrEnabled(false)
        assertNoPause()
    }

    // ── Logs and messages ──────────────────────────────────────────────

    @Test fun `each ask logs one clock line, with sizes and counts and no text`() {
        ShadowLog.clear()
        runBlocking { backend(answering(200, ok(ANSWER, finish = "length"))).answer(question()) }
        http(404, """{"error":"not found"}""")
        quietFailure(PtCloudTestSupport.failingClient(SocketTimeoutException("read")))
        quietFailure(answering(200, "<html>$SENTENCE</html>"))

        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }.filter { it.startsWith("ask ") }
        val sizes = "sentence=${SENTENCE.length} question=${WORDING.length}"
        assertEquals(4, lines.size)
        assertTrue(lines[0], Regex("""ask id=ask-\d+ $sizes ms=\d+ status=ok finish=length used=12/100 reused=-""").matches(lines[0]))
        assertTrue(lines[1], Regex("""ask id=ask-\d+ $sizes ms=\d+ status=http404 finish=- used=- reused=-""").matches(lines[1]))
        assertTrue(lines[2], Regex("""ask id=ask-\d+ $sizes ms=\d+ status=SocketTimeoutException finish=- used=- reused=-""").matches(lines[2]))
        assertTrue(lines[3], Regex("""ask id=ask-\d+ $sizes ms=\d+ status=unreadable finish=- used=- reused=-""").matches(lines[3]))
        for (line in ShadowLog.getLogsForTag("PtCloud").map { it.msg }) {
            for (secret in listOf(SENTENCE, WORDING, ANSWER, KEY, MODEL)) assertFalse(line, line.contains(secret))
        }
    }

    @Test fun `no exception message carries the sentence, the question, the answer or the key`() {
        val messages = mutableListOf<String?>()
        fun collect(http: OkHttpClient, asked: SentenceQuestion = question()) {
            PtCloudService.installForTest(cooldown, config)
            cooldown.resetCooldown()
            messages += thrownBy { backend(http).answer(asked) }.message
        }
        collect(answering(401, """{"error":"Unauthorized","detail":"$KEY"}"""))
        collect(answering(404, """{"error":"not found $SENTENCE"}"""))
        collect(answering(422, """{"detail":"$WORDING"}"""))
        collect(answering(429, askLimit("60")))
        collect(answering(500, """{"error":"internal $SENTENCE"}"""))
        collect(PtCloudTestSupport.failingClient(IOException("refused $SENTENCE $KEY")))
        collect(answering(200, "<html>$SENTENCE $ANSWER</html>"))
        collect(answering(200, answer("failed", text = ANSWER, finish = null, error = """{"class":"blocked","message":"$WORDING"}""")))
        collect(answering(200, ok()), asked = question(sentence = SENTENCE.repeat(200)))
        assertEquals(9, messages.size)
        for (message in messages) {
            assertNotNull(message)
            for (secret in listOf(SENTENCE, WORDING, ANSWER, KEY)) assertFalse(message, message!!.contains(secret))
        }
    }

    private companion object {
        const val SENTENCE = "宿屋に泊まりますか?"
        const val WORDING = "Why is は used here instead of が?"
        const val ANSWER = "因為「は」標示主題。"
        const val MODEL = "gemini-3.1-flash-lite"

        /** The question's two messages: for the models that are put a
         *  prompt, and never for the cloud. */
        const val SYSTEM_MARK = "SYSTEM-MESSAGE-FOR-A-PROMPT-COMPLETER"
        const val USER_MARK = "USER-MESSAGE-FOR-A-PROMPT-COMPLETER"
    }
}
