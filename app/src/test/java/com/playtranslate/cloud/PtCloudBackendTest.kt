package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.cloud.PtCloudTestSupport.BASE_URL
import com.playtranslate.cloud.PtCloudTestSupport.KEY
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.BatchTranslator
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownMessageClass
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.HttpCallClock
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.StructuralFailureException
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.LlmPromptTemplates
import com.playtranslate.translation.llm.PromptKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PlayTranslate Cloud as a waterfall backend ([PtCloudBackend]) over
 * `POST /v1/translate` ([PtCloudClient.postTranslate]), against a fake
 * server: answers by position, the all-or-nothing contract (a partial
 * answer is the registry's per-text retry), the calls a batch is cut into
 * and their order, blanks and oversize texts, every row of the failure
 * table on the one cooldown with nothing reported by the client, the
 * route's own timeouts, cancellation, the wire, `isUsable`, and messages
 * free of content.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudBackendTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The cooldown's clock, frozen at the test's start on the wall clock's
     *  scale (the registry compares cooldowns with the wall clock). */
    private val clock = AtomicLong(System.currentTimeMillis())
    private val now: Long get() = clock.get()
    private lateinit var cooldown: CooldownState
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())

    /** Every request the fake server saw, and when each call started and
     *  ended, in order. */
    private val seen = Collections.synchronizedList(mutableListOf<Request>())
    private val events = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
        LlmPromptTemplates.resetOverrides()
    }

    @After fun tearDown() {
        // Before the uninstall: a registry walk reaches the cooldown.
        TranslationBackendRegistry.close()
        TranslationBackendRegistry.onlineAttemptListener = null
        LlmPromptTemplates.resetOverrides()
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    // ── Fixtures ───────────────────────────────────────────────────────

    /** A config switched on (or off) with [key] stored. */
    private fun config(key: String = KEY, enabled: Boolean = true): PtCloudConfig {
        PtCloudTestSupport.prefs(ctx).ptCloudEnabled = enabled
        return PtCloudTestSupport.config(ctx, key)
    }

    /** The backend on [http], with the service's cooldown (the default
     *  provider, read at each call). */
    private fun backend(http: OkHttpClient, config: PtCloudConfig = config()): PtCloudBackend {
        val client = PtCloudClient(config, clientFactory = { http })
        return PtCloudBackend(client = { client }, config = { config })
    }

    /** The fake server: answers the [n]th call (from 0) with [answer] for
     *  its texts, as a status code and a body. */
    private fun server(answer: (texts: List<String>, n: Int) -> Pair<Int, String>): OkHttpClient {
        val calls = AtomicInteger()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val n = calls.getAndIncrement()
            seen += request
            events += "start $n"
            val (code, body) = answer(textsOf(request), n)
            events += "end $n"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private fun bodyOf(request: Request) = Buffer().also { request.body!!.writeTo(it) }

    private fun json(request: Request): JsonObject = Json.parseToJsonElement(bodyOf(request).readUtf8()).jsonObject

    private fun textsOf(request: Request): List<String> = json(request)["texts"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** An `ok` answer naming [byPosition], in its order. */
    private fun ok(byPosition: Map<Int, String>): String = PtCloudTestSupport.part2(
        "ok", byPosition.entries.joinToString(",", "[", "]") { (id, text) -> """{"id":$id,"translation":"$text"}""" },
    )

    /** An `ok` answer naming every text of the call, as `EN:` + the text. */
    private fun echo(texts: List<String>): String = ok(texts.withIndex().associate { (i, t) -> i to "EN:$t" })

    private fun failedAnswer(error: String) = PtCloudTestSupport.part2("failed", "[]", extra = ""","translation_error":$error""")

    private fun thrownBy(block: suspend () -> Unit): Throwable =
        runCatching { runBlocking { block() } }.exceptionOrNull() ?: throw AssertionError("expected a throw")

    private fun assertNoCooldown() = assertNull(cooldown.unavailableUntil())

    private fun assertCooldown(untilMs: Long, cause: CooldownCause, description: String) {
        assertEquals(untilMs, cooldown.unavailableUntil())
        assertEquals(cause, cooldown.unavailableCause())
        assertEquals(description, cooldown.unavailableDescription())
    }

    /** A cooldown the server stated in seconds from its answer, computed on
     *  the wall clock at the answer: between the test's start and now. */
    private fun assertStatedCooldown(seconds: Long, startedAtMs: Long, cause: CooldownCause, description: String) {
        val until = cooldown.unavailableUntil()!!
        assertTrue("until $until", until in startedAtMs + seconds * 1000..System.currentTimeMillis() + seconds * 1000)
        assertEquals(cause, cooldown.unavailableCause())
        assertEquals(description, cooldown.unavailableDescription())
    }

    /** One failing batch of two texts: the thrown [PtCloudReadException],
     *  with no attempt reported (the registry reports, never the client). */
    private fun failure(http: OkHttpClient): PtCloudReadException {
        val e = thrownBy { backend(http).translateBatch(listOf("一", "二"), "ja", "en") }
        assertTrue("$e", e is PtCloudReadException)
        assertTrue("the client reports no attempt: $attempts", attempts.isEmpty())
        return e as PtCloudReadException
    }

    private fun answered(body: String) = failure(server { _, _ -> 200 to body })

    private fun http(code: Int, body: String = "", retryAfter: String? = null) =
        failure(PtCloudTestSupport.cannedClient(code, body, retryAfter, seen))

    /** A waterfall backend after the cloud that records what it was handed;
     *  offline, so the registry reports no attempt for it. */
    private class RecordingBackend : TranslationBackend, BatchTranslator {
        override val id = "next"
        override val displayName = "Next"
        override val priority = 20
        override val requiresInternet = false
        override val isDegradedFallback = false
        val handed: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String): String {
            handed += listOf(text)
            return "next:$text"
        }
        override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> {
            handed += texts
            return texts.map { "next:$it" }
        }
    }

    // ── Answers ────────────────────────────────────────────────────────

    @Test fun `the backend states what it is`() {
        val b = PtCloudBackend()
        assertEquals("ptcloud", b.id)
        assertEquals("PlayTranslate Cloud", b.displayName)
        assertEquals(10, b.priority)
        assertTrue(b.requiresInternet)
        assertFalse(b.isDegradedFallback)
        assertTrue(b.providesLlmContext)
        assertEquals(4.5f, b.qualityStars)
        assertNull(b.speedStars)
        assertFalse("never a definitions translator", b.usableAsOfflineFallback)
    }

    @Test fun `an ok answer fills each text at its position, in one call`() {
        val http = server { _, _ -> 200 to ok(mapOf(2 to "C", 0 to "A", 1 to "B")) }
        val out = runBlocking { backend(http).translateBatch(listOf("一", "二", "三"), "ja", "en") }
        assertEquals(listOf("A", "B", "C"), out)
        assertEquals(listOf("一", "二", "三"), textsOf(seen.single()))
        assertNoCooldown()
        assertTrue(attempts.isEmpty())
    }

    @Test fun `a single translate is a batch of one`() {
        val out = runBlocking { backend(server { t, _ -> 200 to echo(t) }).translate("一", "ja", "en") }
        assertEquals("EN:一", out)
        assertEquals(listOf("一"), textsOf(seen.single()))
    }

    @Test fun `an ok answer that leaves a text unnamed or blank is asked again once, for those texts alone`() {
        for (first in listOf(ok(mapOf(0 to "EN:一", 2 to "EN:三")), ok(mapOf(0 to "EN:一", 1 to "  ", 2 to "EN:三")))) {
            seen.clear()
            val http = server { texts, n -> 200 to if (n == 0) first else echo(texts) }
            val out = runBlocking { backend(http).translateBatch(listOf("一", "二", "三"), "ja", "en") }
            assertEquals(listOf("EN:一", "EN:二", "EN:三"), out)
            assertEquals(listOf(listOf("一", "二", "三"), listOf("二")), seen.map(::textsOf))
            assertNoCooldown()
        }
    }

    @Test fun `a second partial answer fails the batch as unusable, with no cooldown and no third call`() {
        val http = server { _, _ -> 200 to ok(mapOf(0 to "EN:一")) }
        val e = thrownBy { backend(http).translateBatch(listOf("一", "二", "三"), "ja", "en") }
        assertTrue("$e", e is StructuralFailureException)
        assertEquals(BackendFailure.BAD_RESPONSE, (e as StructuralFailureException).failure)
        assertEquals(listOf(listOf("一", "二", "三"), listOf("二", "三")), seen.map(::textsOf))
        assertNoCooldown()
    }

    @Test fun `through the registry a partial answer is asked again once on the cloud, one attempt reported`() {
        val http = server { texts, n -> 200 to if (n == 0) ok(mapOf(0 to "EN:一", 2 to "EN:三")) else echo(texts) }
        val next = FakeOnlineBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(backend(http), next))

        val results = runBlocking { TranslationBackendRegistry.translateBatch(listOf("一", "二", "三"), "ja", "en") }

        assertEquals(listOf("EN:一", "EN:二", "EN:三"), results.map { it.text })
        assertTrue(results.all { it.backend.id == PtCloudService.ID })
        assertEquals("one batch call, then one for the unnamed text", listOf(listOf("一", "二", "三"), listOf("二")), seen.map(::textsOf))
        assertEquals(0, next.translateCalls.get())
        val attempt = attempts.single()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertNull(attempt.failure)
        assertTrue(attempt.reachedServer)
    }

    @Test fun `through the registry a second partial answer reports one BAD_RESPONSE attempt and the next backend gets the whole list`() {
        val next = RecordingBackend()
        TranslationBackendRegistry.init(listOf(backend(server { _, _ -> 200 to ok(mapOf(0 to "EN:一")) }), next))
        val texts = listOf("一", "二", "三")

        val results = runBlocking { TranslationBackendRegistry.translateBatch(texts, "ja", "en") }

        assertEquals(listOf(texts), next.handed)
        assertTrue(results.all { it.backend.id == "next" })
        assertEquals("two calls, never one per text", 2, seen.size)
        val attempt = attempts.single()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertEquals(BackendFailure.BAD_RESPONSE, attempt.failure)
        assertTrue(attempt.reachedServer)
        assertNoCooldown()
    }

    @Test fun `skipped answers each text with itself for a source equal to the target`() {
        val out = runBlocking {
            backend(server { _, _ -> 200 to PtCloudTestSupport.part2("skipped", "[]") }).translateBatch(listOf("一", "二"), "ja", "ja")
        }
        assertEquals(listOf("一", "二"), out)
        assertNoCooldown()
    }

    @Test fun `skipped for a pair that is not the same language is an unusable answer, never the source text`() {
        val e = thrownBy {
            backend(server { _, _ -> 200 to PtCloudTestSupport.part2("skipped", "[]") }).translateBatch(listOf("一", "二"), "ja", "en")
        }
        assertTrue("$e", e is StructuralFailureException)
        assertEquals(BackendFailure.BAD_RESPONSE, (e as StructuralFailureException).failure)
        assertTrue("the server answered it", e.serverAnswered)
        assertEquals(1, seen.size)
        assertNoCooldown()
    }

    @Test fun `a batch's work runs off the caller's thread`() {
        // The context provider stands for the work before the call (the
        // key's decryption, the encoding, the size counting): on IO, never
        // on the caller's thread, which the registry's per-text retry would
        // otherwise hold once per text.
        val threads = Collections.synchronizedList(mutableListOf<String>())
        LlmPromptTemplates.contextProvider = { _, _ -> threads += Thread.currentThread().name; "" }
        val caller = Thread.currentThread().name
        runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("一", "二"), "ja", "en") }
        val thread = threads.single()
        assertTrue("$thread vs the caller's $caller", thread != caller && thread.startsWith("DefaultDispatcher-worker"))
    }

    // ── Calls a batch is cut into ──────────────────────────────────────

    @Test fun `65 texts make two calls, the second sent only after the first succeeded`() {
        val secondStarted = CountDownLatch(1)
        val http = server { texts, n ->
            // A second call made beside the first would start while this
            // one waits, and show up before its end.
            if (n == 1) secondStarted.countDown() else secondStarted.await(300, TimeUnit.MILLISECONDS)
            200 to echo(texts)
        }
        val texts = List(65) { "t$it" }
        val out = runBlocking { backend(http).translateBatch(texts, "ja", "en") }
        assertEquals(texts.map { "EN:$it" }, out)
        assertEquals(listOf("start 0", "end 0", "start 1", "end 1"), events)
        assertEquals(texts.take(64), textsOf(seen[0]))
        assertEquals(listOf("t64"), textsOf(seen[1]))
    }

    @Test fun `a failed first call sends no second, and a partial one is asked again before the next call`() {
        val texts = List(65) { "t$it" }
        val e = thrownBy { backend(server { _, _ -> 500 to """{"error":"internal"}""" }).translateBatch(texts, "ja", "en") }
        assertTrue("$e", e is PtCloudReadException)
        assertEquals(1, seen.size)

        seen.clear()
        cooldown.resetCooldown()
        // The first call leaves t1 unnamed: its re-ask goes out before the
        // 65th text's own call.
        val http = server { t, n ->
            200 to if (n == 0) ok(t.withIndex().filter { it.index != 1 }.associate { (i, x) -> i to "EN:$x" }) else echo(t)
        }
        val out = runBlocking { backend(http).translateBatch(texts, "ja", "en") }
        assertEquals(texts.map { "EN:$it" }, out)
        assertEquals(listOf(texts.take(64), listOf("t1"), listOf("t64")), seen.map(::textsOf))
    }

    @Test fun `a call's body is measured with the persona and the context it carries`() {
        // 60,000 bytes of persona and context: two texts of 2,000 fit under
        // the 65,536-byte body cap, three do not. Without them all three
        // would go in one call of some 66,000 bytes.
        val persona = "p".repeat(30_000)
        val context = "c".repeat(30_000)
        LlmPromptTemplates.overrideProvider = { if (it == PromptKind.SYSTEM) persona else null }
        LlmPromptTemplates.contextProvider = { _, _ -> context }
        val texts = List(3) { i -> "$i".repeat(2_000) }

        val out = runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(texts, "ja", "en") }

        assertEquals(texts.map { "EN:$it" }, out)
        assertEquals(listOf(texts.take(2), texts.drop(2)), seen.map(::textsOf))
        for (request in seen) {
            val size = bodyOf(request).size
            assertTrue("body $size", size < PtCloudBatches.MAX_BODY_BYTES)
            assertEquals(persona, json(request)["persona"]!!.jsonPrimitive.content)
            assertEquals(context, json(request)["context"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `blanks are answered as they are, at their positions, without the server`() {
        val out = runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("", "一", "  ", "二"), "ja", "en") }
        assertEquals(listOf("", "EN:一", "  ", "EN:二"), out)
        assertEquals(listOf("一", "二"), textsOf(seen.single()))
    }

    @Test fun `blanks across the 64 text boundary leave every answer at its position`() {
        val texts = List(67) { "t$it" }.toMutableList().apply { this[63] = ""; this[64] = " " }
        val out = runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(texts, "ja", "en") }
        assertEquals(texts.map { if (it.isBlank()) it else "EN:$it" }, out)
        assertEquals(2, seen.size)
        assertEquals(64, textsOf(seen[0]).size)
        assertEquals(listOf("t66"), textsOf(seen[1]))
    }

    @Test fun `an all-blank input makes no call`() {
        val out = runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("", " \n"), "ja", "en") }
        assertEquals(listOf("", " \n"), out)
        assertTrue(seen.isEmpty())
    }

    @Test fun `a text over 4,000 code points is refused before any call, alone or in a batch`() {
        val long = "a".repeat(4_001)
        val b = backend(server { t, _ -> 200 to echo(t) })
        for (call in listOf<suspend () -> Unit>(
            { b.translate(long, "ja", "en") },
            { b.translateBatch(listOf(long), "ja", "en") },
            { b.translateBatch(listOf("一", long, "二"), "ja", "en") },
        )) {
            val e = thrownBy(call)
            assertTrue("$e", e is StructuralFailureException)
            assertEquals(BackendFailure(BackendFailureKind.REJECTED), (e as StructuralFailureException).failure)
        }
        assertTrue(seen.isEmpty())
        assertNoCooldown()
        // 4,000 code points (8,000 UTF-16 units) go out.
        runBlocking { b.translate("𠀀".repeat(4_000), "ja", "en") }
        assertEquals(1, seen.size)
    }

    @Test fun `a text whose body alone, with the persona and the context, reaches the cap is refused before any call`() {
        LlmPromptTemplates.overrideProvider = { if (it == PromptKind.SYSTEM) "p".repeat(70_000) else null }
        val b = backend(server { t, _ -> 200 to echo(t) })
        val e = thrownBy { b.translate("一", "ja", "en") }
        assertTrue("$e", e is StructuralFailureException)
        assertEquals(BackendFailure(BackendFailureKind.REJECTED), (e as StructuralFailureException).failure)
        assertTrue(seen.isEmpty())
        assertNoCooldown()
    }

    @Test fun `through the registry an oversize text reports one REJECTED attempt and the next backend gets the whole list`() {
        val next = RecordingBackend()
        TranslationBackendRegistry.init(listOf(backend(server { t, _ -> 200 to echo(t) }), next))
        val texts = listOf("一", "a".repeat(4_001))

        val results = runBlocking { TranslationBackendRegistry.translateBatch(texts, "ja", "en") }

        assertEquals(listOf(texts), next.handed)
        assertTrue(results.all { it.backend.id == "next" })
        val attempt = attempts.single()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertEquals(BackendFailure(BackendFailureKind.REJECTED), attempt.failure)
        assertFalse("nothing was sent, so nothing reached a server", attempt.reachedServer)
        assertTrue(seen.isEmpty())
        assertNoCooldown()
    }

    @Test fun `through the registry a body over the cap reports one REJECTED attempt that reached no server`() {
        LlmPromptTemplates.overrideProvider = { if (it == PromptKind.SYSTEM) "p".repeat(70_000) else null }
        val next = RecordingBackend()
        TranslationBackendRegistry.init(listOf(backend(server { t, _ -> 200 to echo(t) }), next))

        val result = runBlocking { TranslationBackendRegistry.translate("一", "ja", "en") }

        assertEquals("next", result.backend.id)
        val attempt = attempts.single()
        assertEquals(BackendFailure(BackendFailureKind.REJECTED), attempt.failure)
        assertFalse(attempt.reachedServer)
        assertTrue(seen.isEmpty())
    }

    // ── The failure table, on the translate route ──────────────────────

    @Test fun `deferred is TIMEOUT with no cooldown`() {
        assertEquals(BackendFailure(BackendFailureKind.TIMEOUT), answered(PtCloudTestSupport.part2("deferred", "[]")).failure)
        assertNoCooldown()
    }

    @Test fun `failed rate_limited with retry_after_s is RATE_LIMITED until then`() {
        val start = System.currentTimeMillis()
        val e = answered(failedAnswer("""{"class":"rate_limited","http_status":429,"retry_after_s":12}"""))
        assertEquals(BackendFailure(BackendFailureKind.RATE_LIMITED, 429), e.failure)
        assertStatedCooldown(12, start, CooldownCause.RATE_LIMITED, "Rate limited")
    }

    @Test fun `failed quota is DAILY_QUOTA until the stated time`() {
        val start = System.currentTimeMillis()
        val e = answered(failedAnswer("""{"class":"quota","http_status":429,"retry_after_s":3600}"""))
        assertEquals(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429), e.failure)
        assertStatedCooldown(3_600, start, CooldownCause.DAILY_QUOTA, "Daily quota used")
    }

    @Test fun `failed server_error is SERVER_ERROR on the rate-limit ladder`() {
        val e = answered(failedAnswer("""{"class":"server_error","http_status":503}"""))
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 503), e.failure)
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    @Test fun `401 is AUTH on the rate-limit ladder`() {
        assertEquals(BackendFailure(BackendFailureKind.AUTH, 401), http(401, """{"error":"unauthorized"}""").failure)
        assertCooldown(now + 60_000, CooldownCause.AUTH, "Bad key")
    }

    @Test fun `403 no_entitlement is BILLING on the rate-limit ladder, the account class`() {
        assertEquals(BackendFailure(BackendFailureKind.BILLING, 403), http(403, """{"error":"no_entitlement"}""").failure)
        assertCooldown(now + 60_000, CooldownCause.BILLING, "No membership")
        assertEquals(CooldownMessageClass.ACCOUNT, cooldown.unavailableCause()!!.messageClass)
    }

    @Test fun `any other 403 is REJECTED with no cooldown`() {
        for (body in listOf("""{"error":"forbidden"}""", "", "<html>403</html>")) {
            attempts.clear()
            assertEquals(BackendFailure(BackendFailureKind.REJECTED, 403), http(403, body).failure)
            assertNoCooldown()
        }
    }

    @Test fun `an empty 404 is NOT_RUNNING on the ladder, a 404 with a body REJECTED with none`() {
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 404), http(404, """{"error":"not found"}""").failure)
        assertNoCooldown()
        assertEquals(BackendFailure(BackendFailureKind.NOT_RUNNING, 404), http(404, "").failure)
        assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
    }

    @Test fun `429 is RATE_LIMITED until its Retry-After`() {
        assertEquals(BackendFailure(BackendFailureKind.RATE_LIMITED, 429), http(429, "{}", retryAfter = "300").failure)
        assertCooldown(now + 300_000, CooldownCause.RATE_LIMITED, "Rate limited")
    }

    @Test fun `500 is SERVER_ERROR on the ladder, 503 SERVER_ERROR with no cooldown`() {
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 503), http(503, """{"error":"starting"}""").failure)
        assertNoCooldown()
        assertEquals(BackendFailure(BackendFailureKind.SERVER_ERROR, 500), http(500, """{"error":"internal"}""").failure)
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    @Test fun `a 200 that is not a translation part is BAD_RESPONSE with no cooldown`() {
        for (body in listOf("<html>502 Bad Gateway</html>", """{"part":"translation"}""", "")) {
            assertEquals(BackendFailure.BAD_RESPONSE, answered(body).failure)
            assertNoCooldown()
        }
    }

    @Test fun `one transport failure leaves no cooldown and a second within the minute starts one`() {
        // Recorded twice per call, the first would already start one.
        assertEquals(
            BackendFailure(BackendFailureKind.UNREACHABLE),
            failure(PtCloudTestSupport.failingClient(IOException("refused"))).failure,
        )
        assertNoCooldown()
        clock.addAndGet(10_000)
        assertEquals(
            BackendFailure(BackendFailureKind.TIMEOUT),
            failure(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))).failure,
        )
        assertCooldown(now + 30_000, CooldownCause.CONNECTION_FAILED, "Timed out")
    }

    @Test fun `the translate call reads for 22 s within 25 s, and a frame keeps its client's timeouts`() {
        val timeouts = Collections.synchronizedMap(mutableMapOf<String, Pair<Int, Long>>())
        val ndjson = PtCloudTestSupport.part1(PtCloudTestSupport.JA) + "\n" + PtCloudTestSupport.part2() + "\n"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            timeouts[path] = chain.readTimeoutMillis() to TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body((if (path == "/v1/frame") ndjson else ok(mapOf(0 to "A"))).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val client = PtCloudClient(config(), clientFactory = { http })

        runBlocking {
            client.postTranslate(TranslateRequest("ja", "en", listOf("一"), "tr-1", null, null))
            client.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()).translation.await()
        }

        assertEquals(22_000 to 25_000L, timeouts["/v1/translate"])
        assertEquals(http.readTimeoutMillis to http.callTimeoutMillis.toLong(), timeouts["/v1/frame"])
        // The production frame client, which the translate client is built on.
        assertEquals(8_000, PtCloudClient.defaultClient().readTimeoutMillis)
        assertEquals(12_000, PtCloudClient.defaultClient().callTimeoutMillis)
    }

    @Test fun `a cancelled call records nothing, reports nothing, and rethrows`() {
        val entered = Semaphore(0)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.release()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled()) {
                check(System.nanoTime() < deadline) { "the call was never cancelled" }
                Thread.sleep(5)
            }
            throw IOException("Canceled")
        }.build()
        val b = backend(http)
        // Twice: a transport failure recorded twice in the pair window
        // would start a cooldown.
        repeat(2) {
            val e = runCatching {
                runBlocking {
                    val call = async(Dispatchers.Default) { b.translateBatch(listOf("一", "二"), "ja", "en") }
                    assertTrue(entered.tryAcquire(5, TimeUnit.SECONDS))
                    call.cancel()
                    call.await()
                }
            }.exceptionOrNull()
            assertTrue("$e", e is CancellationException)
        }
        assertNoCooldown()
        assertTrue(attempts.isEmpty())
    }

    @Test fun `the backend reads the service's cooldown at each call, never a kept one`() {
        val b = backend(server { t, _ -> 200 to echo(t) })
        val other = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        other.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertNull(b.unavailableUntil())

        PtCloudService.installForTest(other)
        assertEquals(other.unavailableUntil(), b.unavailableUntil())
        assertEquals("Server error", b.unavailableDescription())
        assertEquals(CooldownCause.SERVER_ERROR, b.unavailableCause())
        b.resetCooldown()
        assertNull(other.unavailableUntil())
        repeat(2) { other.recordNetworkFailure("Connection failed") }
        assertTrue(b.onConnectivityRestored())
        assertNull(other.unavailableUntil())
        other.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        b.recordSuccess(clock.get() + 1)
        assertNull(other.unavailableUntil())
        assertNoCooldown()
    }

    // ── isUsable ───────────────────────────────────────────────────────

    @Test fun `usable only switched on with a usable key, for any pair`() {
        for (enabled in listOf(false, true)) for (key in listOf("", KEY)) {
            val config = config(key, enabled)
            val b = PtCloudBackend(client = { error("no call") }, config = { config })
            val expected = enabled && key.isNotEmpty()
            assertEquals("switch $enabled, key ${key.isNotEmpty()}", expected, b.isUsable("ja", "en"))
            assertEquals(expected, b.isUsable("xx", "yy"))
        }
    }

    /** [PtCloudTestSupport.FakeCodec], counting its decryptions. */
    private class CountingCodec : SecretCodec {
        private val fake = PtCloudTestSupport.FakeCodec()
        val decrypts = AtomicInteger()
        override fun encrypt(context: String, plaintext: String): String? = fake.encrypt(context, plaintext)
        override fun decrypt(context: String, stored: String): String? {
            decrypts.incrementAndGet()
            return fake.decrypt(context, stored)
        }
    }

    @Test fun `with the switch off the key is never read`() {
        val codec = CountingCodec()
        val prefs = Prefs(ctx, codec)
        prefs.ptCloudClientKey = KEY
        prefs.ptCloudEnabled = false
        val config = PtCloudConfig(prefs)
        val b = PtCloudBackend(client = { error("no call") }, config = { config })

        assertFalse(b.isUsable("ja", "en"))
        assertEquals(0, codec.decrypts.get())
        prefs.ptCloudEnabled = true
        assertTrue(b.isUsable("ja", "en"))
        assertTrue(codec.decrypts.get() > 0)
    }

    // ── The wire ───────────────────────────────────────────────────────

    @Test fun `the call is a JSON POST to v1 translate with the key, the codes as given, and frame_id on the wire`() {
        LlmPromptTemplates.overrideProvider = { if (it == PromptKind.SYSTEM) PERSONA else null }
        LlmPromptTemplates.contextProvider = { s, t -> "ctx $s>$t\n\n" }
        runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("一", "二"), "zh", "pt-BR") }

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/v1/translate", request.url.toString())
        assertEquals("Bearer $KEY", request.header("Authorization"))
        assertNull("no Accept: one JSON object, no item lines", request.header("Accept"))
        assertEquals("application/json", request.body!!.contentType().toString())
        assertEquals(bodyOf(request).size, request.body!!.contentLength())
        assertNotNull(request.tag(HttpCallClock::class.java))

        val body = json(request)
        assertEquals(setOf("source", "target", "texts", "frame_id", "persona", "context"), body.keys)
        assertEquals("zh", body["source"]!!.jsonPrimitive.content)
        assertEquals("pt-BR", body["target"]!!.jsonPrimitive.content)
        assertEquals(listOf("一", "二"), textsOf(request))
        assertTrue(body["frame_id"]!!.jsonPrimitive.content.startsWith("tr-"))
        assertEquals(PERSONA, body["persona"]!!.jsonPrimitive.content)
        assertEquals("ctx zh>pt-BR\n\n", body["context"]!!.jsonPrimitive.content)
    }

    @Test fun `no persona override and a blank context leave both keys out`() {
        LlmPromptTemplates.contextProvider = { _, _ -> " \n" }
        runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("一", "二"), "ja", "en") }
        assertEquals(setOf("source", "target", "texts", "frame_id"), json(seen.single()).keys)
    }

    @Test fun `each call logs one clock line, with counts and no text`() {
        ShadowLog.clear()
        runBlocking { backend(server { t, _ -> 200 to echo(t) }).translateBatch(listOf("秘密", "二"), "ja", "en") }
        http(500, """{"error":"internal"}""")
        val lines = ShadowLog.getLogsForTag("PtCloud").map { it.msg }.filter { it.startsWith("translate ") }
        assertEquals(2, lines.size)
        assertTrue(lines[0], Regex("""translate id=tr-\d+ n=2 chars=3 ms=\d+ status=ok reused=-""").matches(lines[0]))
        assertTrue(lines[1], Regex("""translate id=tr-\d+ n=2 chars=2 ms=\d+ status=http500 reused=-""").matches(lines[1]))
    }

    @Test fun `no exception message carries a text, the persona or the key`() {
        LlmPromptTemplates.overrideProvider = { if (it == PromptKind.SYSTEM) PERSONA else null }
        val text = "秘密の台詞"
        val messages = mutableListOf<String?>()
        fun collect(http: OkHttpClient, batch: List<String> = listOf(text, "二")) {
            cooldown.resetCooldown()
            messages += thrownBy { backend(http).translateBatch(batch, "ja", "en") }.message
        }
        collect(PtCloudTestSupport.cannedClient(401, """{"error":"unauthorized"}"""))
        collect(PtCloudTestSupport.cannedClient(403, """{"error":"no_entitlement"}"""))
        collect(PtCloudTestSupport.cannedClient(500, """{"error":"internal $text"}"""))
        collect(PtCloudTestSupport.failingClient(IOException("refused $text $KEY")))
        collect(server { _, _ -> 200 to "<html>$text</html>" })
        collect(server { _, _ -> 200 to PtCloudTestSupport.part2("deferred", "[]") })
        // A partial answer, then a re-ask that names nothing: the failure
        // after the second ask.
        collect(server { _, n -> 200 to if (n == 0) ok(mapOf(0 to text)) else ok(emptyMap()) })
        collect(server { _, _ -> 200 to ok(emptyMap()) }, batch = listOf(text.repeat(1_000)))
        assertEquals(8, messages.size)
        for (message in messages) {
            assertNotNull(message)
            assertFalse(message, message!!.contains(text))
            assertFalse(message, message.contains(PERSONA))
            assertFalse(message, message.contains(KEY))
        }
    }

    private companion object {
        const val PERSONA = "You are a careful translator of old games."
    }
}
