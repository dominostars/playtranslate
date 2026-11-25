package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * [GeminiBackend.complete], the free-form answer the Ask section runs on,
 * against the translation calls it shares one round trip with:
 *  - the request: one generateContent call to the translation endpoint with
 *    exactly the caller's system instruction and user turn, the model's
 *    thinking config, and none of the translation's generation fields (no
 *    temperature, no JSON schema; the translation sends 0.2, pinned as the
 *    contrast);
 *  - the reply: returned untouched, where a translation is cleaned; its
 *    tokens counted as a translation's are;
 *  - the service's standing: a 429, a 5xx, a rejected key and a plain 400
 *    are recorded or not exactly as for a translation, while a failure of
 *    the transport and a cancel are not counted at all (the contrast: two
 *    transport failures on translate do cool the service down);
 *  - cancellation reaches the HTTP call; the answer does not ride the
 *    translation client's whole-call cap;
 *  - [GeminiBackend.canComplete];
 *  - the translation paths after the refactor onto the shared exchange.
 *
 * Pure JVM. Replies come from an interceptor ([recordingClient]), so the
 * hardcoded Google endpoint is never contacted; the timeout test redirects
 * the request to a loopback [DelayedReplyServer].
 */
class GeminiBackendCompleteTest {

    private val clients = mutableListOf<OkHttpClient>()

    /** One frozen "now" for every cooldown state a test builds, so two
     *  fresh backends' ladder cooldowns compare equal. */
    private val frozenNow = System.currentTimeMillis()

    @After fun tearDown() {
        clients.forEach { it.dispatcher.executorService.shutdown() }
    }

    private fun gemini(
        client: OkHttpClient,
        cooldown: CooldownState = CooldownState(context = null, backendId = "gemini"),
        key: String? = "AIza-test",
        enabled: Boolean = true,
        extras: (String) -> Map<String, JsonElement> = { emptyMap() },
        usage: UsageTracker = UsageTracker(FakeSharedPreferences(), "gemini"),
    ): GeminiBackend {
        clients += client
        return GeminiBackend(
            id = "gemini",
            keyProvider = { key },
            enabledProvider = { enabled },
            modelProvider = { MODEL },
            generationConfigExtras = extras,
            usageTracker = usage,
            cooldownState = cooldown,
            client = client,
        )
    }

    /** A generateContent reply carrying [text], with usage metadata when
     *  token counts are given. */
    private fun reply(text: String, promptTokens: Long? = null, candidatesTokens: Long? = null): String =
        buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } }
                }
            }
            if (promptTokens != null && candidatesTokens != null) {
                putJsonObject("usageMetadata") {
                    put("promptTokenCount", promptTokens)
                    put("candidatesTokenCount", candidatesTokens)
                }
            }
        }.toString()

    /** What a call left visible: what it threw, and the service's cooldown. */
    private data class Standing(
        val thrown: Class<*>?,
        val failure: BackendFailure?,
        val until: Long?,
        val cause: CooldownCause?,
        val description: String?,
    )

    /** One call on a FRESH backend whose server answers [code] [body]. */
    private fun standingAfter(code: Int, body: String, call: suspend GeminiBackend.() -> Unit): Standing {
        val backend = gemini(
            recordingClient { CannedReply(code, body) },
            cooldown = CooldownState(context = null, backendId = "gemini", nowMs = { frozenNow }),
        )
        val thrown = runCatching { runBlocking { backend.call() } }.exceptionOrNull()
        return Standing(
            thrown?.javaClass,
            (thrown as? ClassifiedFailure)?.failure,
            backend.unavailableUntil(),
            backend.unavailableCause(),
            backend.unavailableDescription(),
        )
    }

    private fun thrownBy(backend: GeminiBackend, call: suspend GeminiBackend.() -> Unit): Throwable? =
        runCatching { runBlocking { backend.call() } }.exceptionOrNull()

    // ── a. the request ───────────────────────────────────────────────────

    @Test fun `complete sends one request with exactly the given texts and the thinking config, and no translation fields`() = runBlocking<Unit> {
        val seen = mutableListOf<SeenRequest>()
        val thinking = buildJsonObject { put("thinkingLevel", "low") }
        val backend = gemini(
            recordingClient(seen) { CannedReply(200, reply("an answer")) },
            // Chosen per model: reaches the body only for the call's model.
            extras = { model -> if (model == MODEL) mapOf("thinkingConfig" to thinking) else emptyMap() },
        )

        backend.translate("hello", "en", "ja")
        backend.complete(SYSTEM, USER)

        assertEquals("one request per call", 2, seen.size)
        val (translation, answer) = seen
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent",
            answer.request.url.toString(),
        )
        assertEquals("the endpoint a translation uses", translation.request.url, answer.request.url)
        assertNotNull(answer.request.header("x-goog-api-key"))
        assertEquals(translation.request.header("x-goog-api-key"), answer.request.header("x-goog-api-key"))

        val body = answer.json
        assertEquals(
            SYSTEM,
            body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content,
        )
        val turn = body.getValue("contents").jsonArray.single().jsonObject
        assertEquals("user", turn.getValue("role").jsonPrimitive.content)
        assertEquals(USER, turn.getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)

        val config = body.getValue("generationConfig").jsonObject
        assertEquals(thinking, config["thinkingConfig"])
        assertFalse("no JSON mime type on an answer: $config", config.containsKey("responseMimeType"))
        assertFalse("no JSON schema on an answer: $config", config.containsKey("responseSchema"))
        // The contrast: a translation pins its temperature, an answer
        // leaves the model's own.
        val translationConfig = translation.json.getValue("generationConfig").jsonObject
        assertEquals("0.2", translationConfig.getValue("temperature").jsonPrimitive.content)
        assertFalse("no temperature on an answer: $config", config.containsKey("temperature"))
    }

    // ── b. the reply ─────────────────────────────────────────────────────

    @Test fun `complete returns the reply untouched where translate cleans it`() = runBlocking<Unit> {
        val raw = "  Translation: \"hello\"\n"
        val backend = gemini(recordingClient { CannedReply(200, reply(raw)) })

        assertEquals("precondition: a translation is cleaned", "hello", backend.translate("こんにちは", "ja", "en"))
        assertEquals(raw, backend.complete(SYSTEM, USER))
    }

    // ── c. usage ─────────────────────────────────────────────────────────

    @Test fun `complete counts the reply's tokens as a translation does`() = runBlocking<Unit> {
        val usage = UsageTracker(FakeSharedPreferences(), "gemini")
        val backend = gemini(recordingClient { CannedReply(200, reply("x", 12, 30)) }, usage = usage)

        backend.translate("hello", "en", "ja")
        assertEquals("precondition: a translation's tokens are counted", 42L, usage.todayTotal())

        backend.complete(SYSTEM, USER)
        assertEquals(84L, usage.todayTotal())
    }

    // ── d. what the provider answered ────────────────────────────────────

    @Test fun `a 429 on complete cools the service down as on translate and throws the rate-limit exception`() {
        val body = """{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}"""
        val answer = standingAfter(429, body) { complete(SYSTEM, USER) }
        val translation = standingAfter(429, body) { translate("hello", "en", "ja") }

        assertEquals(GeminiRateLimitException::class.java, answer.thrown)
        assertEquals(BackendFailure(BackendFailureKind.RATE_LIMITED, 429), answer.failure)
        assertTrue("a cooldown in the future: ${answer.until}", (answer.until ?: 0L) > frozenNow)
        assertEquals("the same standing a translation's 429 leaves", translation, answer)
    }

    @Test fun `a 429 naming the day's quota on complete records the quota cooldown as on translate`() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[""" +
            """{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[""" +
            """{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        val answer = standingAfter(429, body) { complete(SYSTEM, USER) }
        val translation = standingAfter(429, body) { translate("hello", "en", "ja") }

        assertEquals(BackendFailure(BackendFailureKind.DAILY_QUOTA, 429), answer.failure)
        assertEquals(CooldownCause.DAILY_QUOTA, answer.cause)
        assertEquals(translation.cause, answer.cause)
        assertEquals(translation.failure, answer.failure)
        assertEquals(translation.description, answer.description)
    }

    @Test fun `a 5xx on complete leaves the same standing as a 5xx on translate`() {
        val answer = standingAfter(503, """{"error":{"code":503,"status":"UNAVAILABLE"}}""") { complete(SYSTEM, USER) }
        val translation = standingAfter(503, """{"error":{"code":503,"status":"UNAVAILABLE"}}""") {
            translate("hello", "en", "ja")
        }

        assertNotNull("precondition: a translation's 5xx cools the service down", translation.until)
        assertEquals(CooldownCause.SERVER_ERROR, translation.cause)
        assertEquals(translation, answer)
    }

    @Test fun `a 400 naming an invalid key on complete is the auth exception and no cooldown`() {
        val backend = gemini(recordingClient { CannedReply(400, INVALID_KEY_400_BODY) })

        // Twice: a single failure counted as a network one would be forgiven.
        repeat(2) {
            val thrown = thrownBy(backend) { complete(SYSTEM, USER) }
            assertTrue("$thrown", thrown is GeminiAuthException)
            assertEquals(BackendFailure(BackendFailureKind.AUTH, 400), (thrown as GeminiAuthException).failure)
        }
        assertNull(backend.unavailableUntil())
    }

    @Test fun `an ordinary 400 on complete is a REJECTED structural failure, not the batch retry signal, and no cooldown`() {
        val backend = gemini(recordingClient { CannedReply(400, PLAIN_400_BODY) })

        repeat(2) {
            val thrown = thrownBy(backend) { complete(SYSTEM, USER) }
            assertFalse("$thrown", thrown is BatchParseException)
            assertTrue("$thrown", thrown is StructuralFailureException)
            assertEquals(BackendFailure(BackendFailureKind.REJECTED, 400), (thrown as StructuralFailureException).failure)
        }
        assertNull(backend.unavailableUntil())
    }

    // ── e. the transport ─────────────────────────────────────────────────

    @Test fun `transport failures on complete are not counted, where two on translate cool the service down`() {
        // The contrast first: the network ladder forgives the first transport
        // failure of a translation and engages on the second.
        val translating = gemini(transportFailingClient())
        repeat(2) { thrownBy(translating) { translate("hello", "en", "ja") } }
        assertNotNull("precondition: two on translate cool the service down", translating.unavailableUntil())

        for (failure in listOf<() -> IOException>(
            { IOException("synthetic connection failure") },
            { SocketTimeoutException("synthetic timeout") },
        )) {
            val answering = gemini(transportFailingClient(failure))
            val expected = failure()
            repeat(2) {
                val thrown = thrownBy(answering) { complete(SYSTEM, USER) }
                assertEquals("the transport failure reaches the caller", expected.javaClass, thrown?.javaClass)
                assertEquals(expected.message, thrown?.message)
            }
            assertNull("two ${expected.javaClass.simpleName}s on complete record nothing", answering.unavailableUntil())
        }
    }

    // ── f. cancellation ──────────────────────────────────────────────────

    @Test fun `cancelling complete cancels the HTTP call, ends in a CancellationException and records nothing`() = runBlocking<Unit> {
        val hold = HoldUntilCancelled()
        val backend = gemini(OkHttpClient.Builder().addInterceptor(hold).build())

        // Twice: a cancel counted as a network failure would cool down on
        // the second.
        repeat(2) { round ->
            var ended: Throwable? = null
            val asking = launch(Dispatchers.Default) {
                try {
                    backend.complete(SYSTEM, USER)
                } catch (t: Throwable) {
                    ended = t
                    throw t
                }
            }
            val held = hold.held.poll(5, TimeUnit.SECONDS)
            assertNotNull("round $round: the request reached the server unanswered", held)

            asking.cancelAndJoin()

            assertSame("round $round: the held call was cancelled", held, hold.cancelled.poll(5, TimeUnit.SECONDS))
            assertTrue(held!!.isCanceled())
            assertTrue("round $round: complete ended in $ended", ended is CancellationException)
        }
        assertNull(backend.unavailableUntil())
    }

    @Test fun `cancelling complete while the reply's body is still arriving cancels the HTTP call and records nothing`() = runBlocking<Unit> {
        // What the headers carried, with the body stalled behind them: an
        // answer, and a refusal whose recording waits on its body.
        for (code in listOf(200, 429)) {
            val stall = StallBodyUntilCancelled(code)
            val backend = gemini(OkHttpClient.Builder().addInterceptor(stall).build())
            var ended: Throwable? = null
            val asking = launch(Dispatchers.Default) {
                try {
                    backend.complete(SYSTEM, USER)
                } catch (t: Throwable) {
                    ended = t
                    throw t
                }
            }
            val reading = stall.reading.poll(5, TimeUnit.SECONDS)
            assertNotNull("$code: the reply's headers are in and its body is being read", reading)

            asking.cancelAndJoin()

            assertSame("$code: the call was cancelled under the body's read", reading, stall.cancelled.poll(5, TimeUnit.SECONDS))
            assertTrue(reading!!.isCanceled())
            assertTrue("$code: complete ended in $ended", ended is CancellationException)
            assertNull("$code: nothing recorded", backend.unavailableUntil())
        }
    }

    // ── g. the answer's own wait ─────────────────────────────────────────

    @Test fun `complete does not ride the translation client's whole-call timeout`() = runBlocking<Unit> {
        DelayedReplyServer(delayMs = 1_200, body = reply("a long answer")).use { server ->
            // The fixed Google endpoint, sent to the loopback server instead.
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .callTimeout(250, TimeUnit.MILLISECONDS)
                .addInterceptor { chain ->
                    val url = chain.request().url.newBuilder()
                        .scheme("http").host("127.0.0.1").port(server.port).build()
                    chain.proceed(chain.request().newBuilder().url(url).build())
                }
                .build()
            val backend = gemini(client)

            val onTranslate = runCatching { backend.translate("hello", "en", "ja") }.exceptionOrNull()
            assertTrue("precondition: translate times out, was $onTranslate", onTranslate is InterruptedIOException)

            assertEquals("a long answer", backend.complete(SYSTEM, USER))
        }
    }

    // ── h. canComplete ───────────────────────────────────────────────────

    @Test fun `canComplete needs the service on and a non-blank key`() {
        fun backend(enabled: Boolean = true, key: String? = "AIza-test") =
            gemini(recordingClient { CannedReply(200, reply("x")) }, key = key, enabled = enabled)

        assertTrue("on, with a key", backend().canComplete())
        assertFalse("switched off", backend(enabled = false).canComplete())
        assertFalse("no key", backend(key = null).canComplete())
        assertFalse("an empty key", backend(key = "").canComplete())
        assertFalse("a blank key", backend(key = "   ").canComplete())
    }

    @Test fun `set up to complete is the key alone, whatever the switch says`() {
        fun backend(enabled: Boolean = true, key: String? = "AIza-test") =
            gemini(recordingClient { CannedReply(200, reply("x")) }, key = key, enabled = enabled)

        assertTrue("on, with a key", backend().isSetUpToComplete())
        assertTrue("switched off, with a key: a pick can ask it", backend(enabled = false).isSetUpToComplete())
        assertFalse("and still no ordinary question reaches it", backend(enabled = false).canComplete())
        for (key in listOf(null, "", "   ")) {
            assertFalse("on, key '$key'", backend(key = key).isSetUpToComplete())
            assertFalse("off, key '$key'", backend(enabled = false, key = key).isSetUpToComplete())
        }
    }

    // ── i. the translation paths, unchanged ──────────────────────────────

    @Test fun `translate sends no JSON schema and translateBatch does`() = runBlocking<Unit> {
        val seen = mutableListOf<SeenRequest>()
        val backend = gemini(recordingClient(seen) { request ->
            CannedReply(200, if (request.body.contains("responseSchema")) reply(BATCH_JSON) else reply("こんにちは"))
        })

        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")

        assertEquals(2, seen.size)
        val single = seen[0].json.getValue("generationConfig").jsonObject
        val batch = seen[1].json.getValue("generationConfig").jsonObject
        assertFalse("$single", single.containsKey("responseMimeType"))
        assertFalse("$single", single.containsKey("responseSchema"))
        assertEquals("application/json", batch.getValue("responseMimeType").jsonPrimitive.content)
        assertTrue("$batch", batch.containsKey("responseSchema"))
    }

    @Test fun `a plain 400 is the per-text retry signal on translateBatch and a REJECTED failure on translate`() {
        val backend = gemini(recordingClient { CannedReply(400, PLAIN_400_BODY) })

        val onBatch = thrownBy(backend) { translateBatch(listOf("a", "b"), "en", "ja") }
        val onTranslate = thrownBy(backend) { translate("hello", "en", "ja") }

        assertTrue("$onBatch", onBatch is BatchParseException)
        assertTrue("$onTranslate", onTranslate is StructuralFailureException)
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 400), (onTranslate as StructuralFailureException).failure)
    }

    @Test fun `an empty reply is the per-text retry signal on translateBatch and a BAD_RESPONSE failure on translate`() {
        val backend = gemini(recordingClient { CannedReply(200, """{"candidates":[]}""") })

        val onBatch = thrownBy(backend) { translateBatch(listOf("a", "b"), "en", "ja") }
        val onTranslate = thrownBy(backend) { translate("hello", "en", "ja") }

        assertTrue("$onBatch", onBatch is BatchParseException)
        assertTrue("$onTranslate", onTranslate is StructuralFailureException)
        assertEquals(BackendFailure.BAD_RESPONSE, (onTranslate as StructuralFailureException).failure)
    }

    private companion object {
        const val MODEL = "gemini-flash-lite-latest"
        /** Characters a request body must carry through JSON intact, and
         *  tokens nothing on this path may expand. */
        const val SYSTEM = "You help a reader.\nAnswer in \"English\", {target}."
        const val USER = "Break this down: 猫が好き {text} \$1 \\ end"
        const val BATCH_JSON = """{"translations":["あ","い"]}"""
        const val INVALID_KEY_400_BODY =
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        const val PLAIN_400_BODY =
            """{"error":{"code":400,"message":"Invalid JSON payload received.","status":"INVALID_ARGUMENT"}}"""
    }
}
