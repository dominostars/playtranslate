package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
 * [OpenAiBackend.complete], the free-form answer the Ask section runs on,
 * against the translation calls it shares one round trip with:
 *  - the request: one chat-completions call to the translation endpoint
 *    with exactly the caller's two messages, the model and its extras, and
 *    no structured-output field;
 *  - the reply: returned untouched, where a translation is cleaned; its
 *    tokens counted as a translation's are;
 *  - the service's standing: what the provider ANSWERED (429, 5xx, a
 *    billing 400, 401, a plain 400) is recorded or not exactly as for a
 *    translation, while a failure of the transport and a cancel are not
 *    counted at all (the contrast: two transport failures on translate do
 *    cool the service down);
 *  - cancellation reaches the HTTP call; the answer does not ride the
 *    translation client's read timeout;
 *  - [OpenAiBackend.canComplete];
 *  - the translation paths after the refactor onto the shared exchange.
 *
 * Pure JVM. Replies come from an interceptor ([recordingClient]) except for
 * the timeout test, which needs a real socket ([DelayedReplyServer]).
 */
class OpenAiBackendCompleteTest {

    private val clients = mutableListOf<OkHttpClient>()

    /** One frozen "now" for every cooldown state a test builds, so two
     *  fresh backends' ladder cooldowns compare equal. */
    private val frozenNow = System.currentTimeMillis()

    @After fun tearDown() {
        clients.forEach { it.dispatcher.executorService.shutdown() }
    }

    private fun openAi(
        client: OkHttpClient,
        cooldown: CooldownState? = CooldownState(context = null, backendId = "openai"),
        key: String? = "sk-test",
        enabled: Boolean = true,
        account: AccountRequirement = AccountRequirement.REQUIRED,
        extras: (String) -> Map<String, JsonElement> = { emptyMap() },
        baseUrl: String = BASE,
        usage: UsageTracker = UsageTracker(FakeSharedPreferences(), "openai"),
    ): OpenAiBackend {
        clients += client
        return OpenAiBackend(
            id = "openai",
            displayName = "OpenAI",
            priority = 8,
            keyProvider = { key },
            enabledProvider = { enabled },
            modelProvider = { MODEL },
            baseUrlProvider = { baseUrl },
            account = { account },
            requestExtras = extras,
            usageTracker = usage,
            applyOwnedByFilter = { false },
            cooldownState = cooldown,
            client = client,
        )
    }

    /** A chat-completions reply carrying [content], with a usage block
     *  when token counts are given. */
    private fun reply(content: String, promptTokens: Long? = null, completionTokens: Long? = null): String =
        buildJsonObject {
            putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
            if (promptTokens != null && completionTokens != null) {
                putJsonObject("usage") {
                    put("prompt_tokens", promptTokens)
                    put("completion_tokens", completionTokens)
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
    private fun standingAfter(code: Int, body: String, call: suspend OpenAiBackend.() -> Unit): Standing {
        val backend = openAi(
            recordingClient { CannedReply(code, body) },
            cooldown = CooldownState(context = null, backendId = "openai", nowMs = { frozenNow }),
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

    private fun thrownBy(backend: OpenAiBackend, call: suspend OpenAiBackend.() -> Unit): Throwable? =
        runCatching { runBlocking { backend.call() } }.exceptionOrNull()

    // ── a. the request ───────────────────────────────────────────────────

    @Test fun `complete sends one chat request with exactly the given messages, the model and its extras, and no response_format`() = runBlocking<Unit> {
        val seen = mutableListOf<SeenRequest>()
        val backend = openAi(
            recordingClient(seen) { CannedReply(200, reply("an answer")) },
            // Extras are chosen per model: they reach the body only for the
            // model the call is made with.
            extras = { model -> if (model == MODEL) mapOf("reasoning_effort" to JsonPrimitive("low")) else emptyMap() },
        )

        backend.translate("hello", "en", "ja")
        backend.complete(SYSTEM, USER)

        assertEquals("one request per call", 2, seen.size)
        val (translation, answer) = seen
        assertEquals("$BASE/chat/completions", answer.request.url.toString())
        assertEquals("the endpoint a translation uses", translation.request.url, answer.request.url)
        assertEquals("POST", answer.request.method)
        assertNotNull(answer.request.header("Authorization"))
        assertEquals(translation.request.header("Authorization"), answer.request.header("Authorization"))

        val body = answer.json
        assertEquals(MODEL, body.getValue("model").jsonPrimitive.content)
        assertEquals(
            "the caller's two messages, verbatim",
            listOf("system" to SYSTEM, "user" to USER),
            body.getValue("messages").jsonArray.map {
                it.jsonObject.getValue("role").jsonPrimitive.content to
                    it.jsonObject.getValue("content").jsonPrimitive.content
            },
        )
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse("no structured output on an answer: $body", body.containsKey("response_format"))
    }

    /** Index-Translate's question goes out as its translations do, in the
     *  shape its endpoint was seen to answer: no Authorization header, the
     *  preset's own thinking switch, the two messages. */
    @Test fun `a keyless Index-Translate question carries no Authorization and the preset's thinking switch`() = runBlocking<Unit> {
        val seen = mutableListOf<SeenRequest>()
        val backend = openAi(
            recordingClient(seen) { CannedReply(200, reply("an answer")) },
            key = null,
            account = OpenAiPreset.INDEX_TRANSLATE.account,
            extras = { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.INDEX_TRANSLATE, it, ReasoningLevel.UNSET) },
        )

        assertEquals("an answer", backend.complete(SYSTEM, USER))

        val answer = seen.single()
        assertNull("keyless: no Authorization", answer.request.header("Authorization"))
        assertEquals(
            """{"enable_thinking":false}""",
            answer.json["chat_template_kwargs"].toString(),
        )
        assertEquals(
            listOf("system", "user"),
            answer.json.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
    }

    // ── b. the reply ─────────────────────────────────────────────────────

    @Test fun `complete returns the reply untouched where translate cleans it`() = runBlocking<Unit> {
        val raw = "  Translation: \"hello\"\n"
        val backend = openAi(recordingClient { CannedReply(200, reply(raw)) })

        assertEquals("precondition: a translation is cleaned", "hello", backend.translate("こんにちは", "ja", "en"))
        assertEquals(raw, backend.complete(SYSTEM, USER))
    }

    // ── c. usage ─────────────────────────────────────────────────────────

    @Test fun `complete counts the reply's tokens as a translation does`() = runBlocking<Unit> {
        val usage = UsageTracker(FakeSharedPreferences(), "openai")
        val backend = openAi(recordingClient { CannedReply(200, reply("x", 12, 30)) }, usage = usage)

        backend.translate("hello", "en", "ja")
        assertEquals("precondition: a translation's tokens are counted", 42L, usage.todayTotal())

        backend.complete(SYSTEM, USER)
        assertEquals(84L, usage.todayTotal())
    }

    // ── d. what the provider answered ────────────────────────────────────

    @Test fun `a 429 on complete cools the service down as on translate and throws the rate-limit exception`() {
        val body = """{"error":{"message":"Rate limit reached","type":"requests"}}"""
        val answer = standingAfter(429, body) { complete(SYSTEM, USER) }
        val translation = standingAfter(429, body) { translate("hello", "en", "ja") }

        assertEquals(OpenAiRateLimitException::class.java, answer.thrown)
        assertEquals(BackendFailure(BackendFailureKind.RATE_LIMITED, 429), answer.failure)
        assertTrue("a cooldown in the future: ${answer.until}", (answer.until ?: 0L) > frozenNow)
        assertEquals("the same standing a translation's 429 leaves", translation, answer)
    }

    @Test fun `a 5xx on complete leaves the same standing as a 5xx on translate`() {
        val answer = standingAfter(503, """{"error":{"message":"overloaded"}}""") { complete(SYSTEM, USER) }
        val translation = standingAfter(503, """{"error":{"message":"overloaded"}}""") { translate("hello", "en", "ja") }

        assertNotNull("precondition: a translation's 5xx cools the service down", translation.until)
        assertEquals(CooldownCause.SERVER_ERROR, translation.cause)
        assertEquals(translation, answer)
    }

    @Test fun `an Anthropic credit-balance 400 on complete is a billing failure with a cooldown`() {
        val backend = openAi(recordingClient { CannedReply(400, CREDIT_BALANCE_400_BODY) })

        val thrown = thrownBy(backend) { complete(SYSTEM, USER) }

        assertTrue("$thrown", thrown is StructuralFailureException)
        assertEquals(BackendFailure(BackendFailureKind.BILLING, 400), (thrown as StructuralFailureException).failure)
        assertEquals(CooldownCause.BILLING, backend.unavailableCause())
        assertTrue((backend.unavailableUntil() ?: 0L) > System.currentTimeMillis())
    }

    @Test fun `a 401 on complete is the auth exception and no cooldown`() {
        val backend = openAi(recordingClient { CannedReply(401, """{"error":{"message":"Incorrect API key"}}""") })

        // Twice: a single failure counted as a network one would be forgiven.
        repeat(2) {
            val thrown = thrownBy(backend) { complete(SYSTEM, USER) }
            assertTrue("$thrown", thrown is OpenAiAuthException)
            assertEquals(BackendFailure(BackendFailureKind.AUTH, 401), (thrown as OpenAiAuthException).failure)
        }
        assertNull(backend.unavailableUntil())
    }

    @Test fun `an ordinary 400 on complete is a REJECTED structural failure, not the batch retry signal, and no cooldown`() {
        val backend = openAi(
            recordingClient { CannedReply(400, """{"error":{"message":"unknown model","type":"invalid_request_error"}}""") },
        )

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
        val translating = openAi(transportFailingClient())
        repeat(2) { thrownBy(translating) { translate("hello", "en", "ja") } }
        assertNotNull("precondition: two on translate cool the service down", translating.unavailableUntil())

        for (failure in listOf<() -> IOException>(
            { IOException("synthetic connection failure") },
            { SocketTimeoutException("synthetic timeout") },
        )) {
            val answering = openAi(transportFailingClient(failure))
            val expected = failure()
            repeat(2) {
                val thrown = thrownBy(answering) { complete(SYSTEM, USER) }
                assertEquals("the transport failure reaches the caller", expected.javaClass, thrown?.javaClass)
                assertEquals(expected.message, thrown?.message)
            }
            assertNull("two ${expected.javaClass.simpleName}s on complete record nothing", answering.unavailableUntil())
        }
    }

    @Test fun `without a cooldown state, the DeepSeek shape, transport failures on either path just throw`() {
        val backend = openAi(transportFailingClient(), cooldown = null)

        repeat(2) {
            val onTranslate = thrownBy(backend) { translate("hello", "en", "ja") }
            val onComplete = thrownBy(backend) { complete(SYSTEM, USER) }
            for (thrown in listOf(onTranslate, onComplete)) {
                assertEquals(IOException::class.java, thrown?.javaClass)
                assertEquals("synthetic connection failure", thrown?.message)
            }
        }
        assertNull(backend.unavailableUntil())
    }

    // ── f. cancellation ──────────────────────────────────────────────────

    @Test fun `cancelling complete cancels the HTTP call, ends in a CancellationException and records nothing`() = runBlocking<Unit> {
        val hold = HoldUntilCancelled()
        val backend = openAi(OkHttpClient.Builder().addInterceptor(hold).build())

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
            val backend = openAi(OkHttpClient.Builder().addInterceptor(stall).build())
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

    @Test fun `complete does not ride the translation client's read timeout`() = runBlocking<Unit> {
        DelayedReplyServer(delayMs = 1_200, body = reply("a long answer")).use { server ->
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .build()
            val backend = openAi(client, baseUrl = "http://127.0.0.1:${server.port}/v1")

            val onTranslate = runCatching { backend.translate("hello", "en", "ja") }.exceptionOrNull()
            assertTrue("precondition: translate times out, was $onTranslate", onTranslate is InterruptedIOException)

            assertEquals("a long answer", backend.complete(SYSTEM, USER))
        }
    }

    // ── h. canComplete ───────────────────────────────────────────────────

    @Test fun `canComplete needs the service on and a credential`() {
        fun backend(
            enabled: Boolean = true,
            key: String? = "sk-test",
            account: AccountRequirement = AccountRequirement.REQUIRED,
        ) = openAi(
            recordingClient { CannedReply(200, reply("x")) },
            key = key, enabled = enabled, account = account,
        )

        assertTrue("on, keyed", backend().canComplete())
        assertTrue(
            "a keyless provider (Index-Translate) needs no key",
            backend(key = null, account = AccountRequirement.NONE).canComplete(),
        )
        assertFalse("switched off", backend(enabled = false).canComplete())
        assertFalse(
            "a keyless provider switched off",
            backend(enabled = false, key = null, account = AccountRequirement.NONE).canComplete(),
        )
        assertFalse("no key where one is needed", backend(key = null).canComplete())
        assertFalse("a blank key where one is needed", backend(key = "  ").canComplete())
    }

    // ── i. the translation paths, unchanged ──────────────────────────────

    @Test fun `translate sends no response_format and translateBatch does`() = runBlocking<Unit> {
        val seen = mutableListOf<SeenRequest>()
        val backend = openAi(recordingClient(seen) { request ->
            CannedReply(200, if (request.body.contains("response_format")) reply(BATCH_JSON) else reply("こんにちは"))
        })

        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")

        assertEquals(2, seen.size)
        assertFalse(seen[0].body, seen[0].json.containsKey("response_format"))
        assertTrue(seen[1].body, seen[1].json.containsKey("response_format"))
    }

    @Test fun `a plain 400 is the per-text retry signal on translateBatch and a REJECTED failure on translate`() {
        val body = """{"error":{"message":"response_format is not supported","type":"invalid_request_error"}}"""
        val backend = openAi(recordingClient { CannedReply(400, body) })

        val onBatch = thrownBy(backend) { translateBatch(listOf("a", "b"), "en", "ja") }
        val onTranslate = thrownBy(backend) { translate("hello", "en", "ja") }

        assertTrue("$onBatch", onBatch is BatchParseException)
        assertTrue("$onTranslate", onTranslate is StructuralFailureException)
        assertEquals(BackendFailure(BackendFailureKind.REJECTED, 400), (onTranslate as StructuralFailureException).failure)
    }

    @Test fun `an empty reply is the per-text retry signal on translateBatch and a BAD_RESPONSE failure on translate`() {
        val backend = openAi(recordingClient { CannedReply(200, """{"choices":[]}""") })

        val onBatch = thrownBy(backend) { translateBatch(listOf("a", "b"), "en", "ja") }
        val onTranslate = thrownBy(backend) { translate("hello", "en", "ja") }

        assertTrue("$onBatch", onBatch is BatchParseException)
        assertTrue("$onTranslate", onTranslate is StructuralFailureException)
        assertEquals(BackendFailure.BAD_RESPONSE, (onTranslate as StructuralFailureException).failure)
    }

    @Test fun `a billing 400 on translateBatch is a BILLING failure with a cooldown, not the per-text retry signal`() {
        val backend = openAi(recordingClient { CannedReply(400, CREDIT_BALANCE_400_BODY) })

        val onBatch = thrownBy(backend) { translateBatch(listOf("a", "b"), "en", "ja") }

        assertFalse("$onBatch", onBatch is BatchParseException)
        assertTrue("$onBatch", onBatch is StructuralFailureException)
        assertEquals(BackendFailure(BackendFailureKind.BILLING, 400), (onBatch as StructuralFailureException).failure)
        assertEquals(CooldownCause.BILLING, backend.unavailableCause())
        assertTrue((backend.unavailableUntil() ?: 0L) > System.currentTimeMillis())
    }

    private companion object {
        const val BASE = "https://api.example.com/v1"
        const val MODEL = "gpt-6-luna"
        /** Characters a request body must carry through JSON intact, and
         *  tokens nothing on this path may expand. */
        const val SYSTEM = "You help a reader.\nAnswer in \"English\", {target}."
        const val USER = "Break this down: 猫が好き {text} \$1 \\ end"
        const val BATCH_JSON = """{"translations":["あ","い"]}"""
        /** Verbatim from the Thor's log, 2026-09-06 (as in OpenAiBackendCooldownTest). */
        const val CREDIT_BALANCE_400_BODY =
            """{"error":{"code":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.","type":"invalid_request_error","param":null}}"""
    }
}
