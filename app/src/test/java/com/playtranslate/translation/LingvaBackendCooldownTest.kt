package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooldown participation for [LingvaBackend] — the field failure this
 * guards against is gtx's per-IP 429 under live-mode cadence: without a
 * cooldown the waterfall re-hit the limited endpoint every capture
 * cycle, keeping the limiter hot indefinitely (users could only recover
 * by stopping the app long enough for the window to cool — "restarting
 * the emulator fixes it, temporarily").
 *
 * Mirrors [OpenAiBackendCooldownTest]'s structure: canned OkHttp
 * clients, [CooldownState] with `context=null` and an injected clock
 * for exact-timestamp assertions.
 */
class LingvaBackendCooldownTest {

    private fun newCooldown(clock: AtomicLong): CooldownState =
        CooldownState(context = null, backendId = "lingva", nowMs = { clock.get() })

    private fun lingvaWith(cooldown: CooldownState?, client: OkHttpClient): LingvaBackend =
        LingvaBackend(
            enabledProvider = { true },
            cooldownState = cooldown,
            client = client,
        )

    @Test fun `HTTP 429 engages the rate-limit ladder`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(429, "rate limited"))

        val thrown = runCatching { backend.translate("こんにちは", "ja", "en") }.exceptionOrNull()
        assertTrue("expected LingvaRateLimitException, got $thrown",
            thrown is LingvaRateLimitException)
        // Rate-limit ladder rung 0 = 1 minute.
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
        assertEquals("Rate limited", cooldown.unavailableDescription())
    }

    @Test fun `HTTP 429 with Retry-After uses the parsed delay`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(
            cooldown,
            cannedClient(429, "rate limited", headers = mapOf("Retry-After" to "120")),
        )

        runCatching { backend.translate("こんにちは", "ja", "en") }
        assertEquals(clock.get() + 120_000L, cooldown.unavailableUntil())
    }

    @Test fun `HTTP 429 with an HTTP-date Retry-After honors the deadline`() = runBlocking {
        // Clock at epoch+1,000,000ms; the header names 01:00:00Z the
        // same day = epoch+3,600,000ms. A valid date form must hold the
        // backend to the server's deadline, not the 60s ladder rung.
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(
            cooldown,
            cannedClient(429, "rate limited",
                headers = mapOf("Retry-After" to "Thu, 01 Jan 1970 01:00:00 GMT")),
        )

        runCatching { backend.translate("こんにちは", "ja", "en") }
        assertEquals(3_600_000L, cooldown.unavailableUntil())
    }

    @Test fun `HTTP 403 is a block, not an auth problem — cools down`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(403, "blocked"))

        val thrown = runCatching { backend.translate("こんにちは", "ja", "en") }.exceptionOrNull()
        assertTrue("expected LingvaRateLimitException, got $thrown",
            thrown is LingvaRateLimitException)
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
    }

    @Test fun `HTTP 500 engages the ladder as Server error`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(500, "boom"))

        runCatching { backend.translate("こんにちは", "ja", "en") }
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
        assertEquals("Server error", cooldown.unavailableDescription())
    }

    @Test fun `HTTP 400 is structural and does not cool down`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(400, "bad request"))

        // Two consecutive 400s — neither the rate-limit nor the network
        // ladder may engage (the IO pair window must not see these).
        runCatching { backend.translate("hello", "ja", "en") }
        runCatching { backend.translate("hello", "ja", "en") }
        assertNull(cooldown.unavailableUntil())
    }

    @Test fun `response-body parse failures never count as connection failures`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        // A 200 whose body the parse path rejects. (Under the stubbed
        // android org.json this throws from the parse layer rather than
        // the real blank-translation StructuralFailureException, but the
        // guarantee under test is the same either way: only transport
        // IOExceptions may reach the recordNetworkFailure catch — a
        // response that ARRIVED must not engage the network ladder, no
        // matter how its body handling fails.)
        val backend = lingvaWith(
            cooldown,
            cannedClient(200, """[[["","こんにちは"]],null,"ja"]"""),
        )

        runCatching { backend.translate("こんにちは", "ja", "en") }
        runCatching { backend.translate("こんにちは", "ja", "en") }
        assertNull(cooldown.unavailableUntil())
    }

    @Test fun `second transport IOException engages the network ladder`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, ioFailingClient())

        // First connection failure — forgiven (wifi blips happen).
        runCatching { backend.translate("hello", "ja", "en") }
        assertNull(cooldown.unavailableUntil())

        // Second within the pair window — network ladder rung 0 = 30 s.
        runCatching { backend.translate("hello", "ja", "en") }
        assertEquals(clock.get() + 30_000L, cooldown.unavailableUntil())
        assertEquals("Connection failed", cooldown.unavailableDescription())
    }

    @Test fun `batched 429 records the same cooldown`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(429, "rate limited"))

        val thrown = runCatching {
            backend.translateBatch(listOf("こんにちは", "さようなら"), "ja", "en")
        }.exceptionOrNull()
        assertTrue("expected LingvaRateLimitException, got $thrown",
            thrown is LingvaRateLimitException)
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
    }

    @Test fun `batch shape drift is BatchParseException and does not cool down`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        // 200 with an empty top-level array for a 2-text batch: the
        // size check throws BatchParseException (an IOException subclass)
        // which must NOT be mistaken for a connection failure.
        val backend = lingvaWith(cooldown, cannedClient(200, "[]"))

        val thrown = runCatching {
            backend.translateBatch(listOf("hello", "world"), "ja", "en")
        }.exceptionOrNull()
        assertTrue("expected BatchParseException, got $thrown",
            thrown is BatchParseException)
        runCatching { backend.translateBatch(listOf("hello", "world"), "ja", "en") }
        assertNull(cooldown.unavailableUntil())
    }

    @Test fun `null cooldownState records nothing and never reports unavailable`() = runBlocking {
        // Legacy/test constructor path: failures must neither NPE nor
        // surface a cooldown. (The 200 success path can't be asserted
        // here — unit tests get the stubbed android org.json, so the
        // gtx body parse isn't exercisable off-device.)
        val limited = lingvaWith(cooldown = null, client = cannedClient(429, "rate limited"))
        val thrown = runCatching { limited.translate("こんにちは", "ja", "en") }.exceptionOrNull()
        assertTrue(thrown is LingvaRateLimitException)
        assertNull(limited.unavailableUntil())

        val broken = lingvaWith(cooldown = null, client = ioFailingClient())
        runCatching { broken.translate("こんにちは", "ja", "en") }
        runCatching { broken.translate("こんにちは", "ja", "en") }
        assertNull(broken.unavailableUntil())
    }

    @Test fun `registry recordSuccess path clears a Lingva cooldown`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val cooldown = newCooldown(clock)
        val backend = lingvaWith(cooldown, cannedClient(429, "rate limited"))

        runCatching { backend.translate("こんにちは", "ja", "en") }
        assertNotNull(cooldown.unavailableUntil())

        // What TranslationBackendRegistry does on a waterfall win, via
        // the Cooldownable surface the backend now exposes.
        clock.addAndGet(120_000)
        (backend as Cooldownable).recordSuccess(clock.get())
        assertNull(backend.unavailableUntil())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun cannedClient(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            builder.build()
        }.build()

    private fun ioFailingClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { _ ->
            throw IOException("synthetic connection failure")
        }.build()
}
