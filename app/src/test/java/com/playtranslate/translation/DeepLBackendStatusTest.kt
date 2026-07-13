package com.playtranslate.translation

import com.playtranslate.R
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [DeepLBackend.status] / [DeepLBackend.refreshStatus] mapping
 * onto [BackendStatus]. Pure JVM — no real network. We inject an
 * [OkHttpClient] whose [okhttp3.Interceptor] short-circuits requests with
 * canned responses (or simulates IOException for the offline path).
 */
class DeepLBackendStatusTest {

    // DeepL's key-less status names its free tier — the one thing that decides
    // whether a user bothers setting it up. It reports the *requirement*, not a
    // sentence: DeepLBackend has no Context to localize one with, so the
    // renderer resolves AccountRequirement.labelRes.
    //
    // The old "quiet, not alarming" tone assertion is gone with the tone — the
    // key-less state was Tone.Warning historically, which made a freshly-added
    // service look broken. BackendStatus.Account carries no tone at all and the
    // renderer pins it Neutral, so that regression is now unrepresentable
    // rather than merely tested against.

    @Test fun `status reports the free-tier account requirement when keyProvider returns null`() {
        val backend = DeepLBackend(
            keyProvider     = { null },
            enabledProvider = { true },
            client          = cannedClient(200, "{}"),
        )
        val s = backend.status
        assertTrue("expected Account, got $s", s is BackendStatus.Account)
        assertEquals(
            AccountRequirement.REQUIRED_FREE_TIER,
            (s as BackendStatus.Account).requirement,
        )
    }

    @Test fun `status reports the free-tier account requirement when key is blank`() {
        val backend = DeepLBackend(
            keyProvider     = { "   " },
            enabledProvider = { true },
            client          = cannedClient(200, "{}"),
        )
        val s = backend.status
        assertTrue("expected Account, got $s", s is BackendStatus.Account)
        assertEquals(
            AccountRequirement.REQUIRED_FREE_TIER,
            (s as BackendStatus.Account).requirement,
        )
    }

    @Test fun `status surfaces cached usage even when toggle is off`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "k:fx" },
            enabledProvider = { false },
            client          = cannedClient(200, """{"character_count":7,"character_limit":42}"""),
        )
        // Initially cached state is Loading.
        assertEquals(BackendStatus.Loading, backend.status)
        backend.refreshStatus()
        // After refresh, the cached Quota is exposed even with toggle off,
        // so the user can see their DeepL quota when deciding to re-enable.
        val s = backend.status
        assertTrue("expected Quota, got $s", s is BackendStatus.Quota)
        assertEquals(7L, (s as BackendStatus.Quota).used)
    }

    @Test fun `refreshStatus on free-tier response yields Quota with null reset`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "free-key:fx" },
            enabledProvider = { true },
            client          = cannedClient(200, """{"character_count":12345,"character_limit":500000}"""),
        )
        val s = backend.refreshStatus()
        assertTrue("expected Quota, got $s", s is BackendStatus.Quota)
        s as BackendStatus.Quota
        assertEquals(12345L, s.used)
        assertEquals(500000L, s.limit)
        assertNull(s.resetEpochMs)
    }

    @Test fun `refreshStatus on Pro response yields Quota with parsed reset`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "pro-key" },
            enabledProvider = { true },
            client          = cannedClient(
                200,
                """{"character_count":1,"character_limit":2,"end_time":"2026-06-15T00:00:00Z"}""",
            ),
        )
        val s = backend.refreshStatus()
        assertTrue(s is BackendStatus.Quota)
        s as BackendStatus.Quota
        assertEquals(1L, s.used)
        assertEquals(2L, s.limit)
        assertNotNull(s.resetEpochMs)
        // 2026-06-15T00:00:00Z corresponds to 1781481600000 ms since epoch.
        assertEquals(1781481600000L, s.resetEpochMs)
    }

    @Test fun `refreshStatus 403 yields Invalid API Key danger`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "bad-key" },
            enabledProvider = { true },
            client          = cannedClient(403, """{"message":"Authorization failure"}"""),
        )
        val s = backend.refreshStatus()
        assertTrue("expected Info, got $s", s is BackendStatus.Info)
        s as BackendStatus.Info
        // The resource, not the sentence: DeepLBackend has no Context to word
        // it with, so the renderer resolves textRes.
        assertEquals(R.string.tr_service_status_invalid_key, s.textRes)
        assertEquals(Tone.Danger, s.tone)
    }

    @Test fun `refreshStatus on IOException yields offline italic info`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "k:fx" },
            enabledProvider = { true },
            client          = ioFailingClient(),
        )
        val s = backend.refreshStatus()
        assertTrue("expected Info, got $s", s is BackendStatus.Info)
        s as BackendStatus.Info
        assertEquals(R.string.tr_service_status_no_internet, s.textRes)
        assertTrue("expected italic", s.italic)
    }

    @Test fun `refreshStatus 5xx falls into offline italic info`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "k:fx" },
            enabledProvider = { true },
            client          = cannedClient(503, "Service Unavailable"),
        )
        val s = backend.refreshStatus()
        assertTrue("expected Info, got $s", s is BackendStatus.Info)
        s as BackendStatus.Info
        assertEquals(R.string.tr_service_status_no_internet, s.textRes)
        assertTrue(s.italic)
    }

    @Test fun `refreshStatus on malformed 200 JSON yields safe couldn't-check state`() = runBlocking {
        // 200 OK with garbage that Gson can't bind to DeepLUsageResponse —
        // a JsonSyntaxException would propagate uncaught into the
        // renderer's coroutine and crash Settings without the defensive
        // catch.
        val backend = DeepLBackend(
            keyProvider     = { "k:fx" },
            enabledProvider = { true },
            client          = cannedClient(200, "<not json at all>"),
        )
        val s = backend.refreshStatus()
        assertTrue("expected Info, got $s", s is BackendStatus.Info)
        s as BackendStatus.Info
        assertEquals(R.string.tr_service_status_check_failed, s.textRes)
        assertTrue("expected italic", s.italic)
    }

    @Test fun `free-tier key routes to api-free host`() = runBlocking {
        val captured = AtomicReference<String>()
        val backend = DeepLBackend(
            keyProvider     = { "abc:fx" },
            enabledProvider = { true },
            client          = urlCapturingClient(captured),
        )
        backend.refreshStatus()
        assertTrue(
            "expected api-free.deepl.com URL, got ${captured.get()}",
            captured.get().startsWith("https://api-free.deepl.com/v2/usage"),
        )
    }

    @Test fun `paid key routes to api host`() = runBlocking {
        val captured = AtomicReference<String>()
        val backend = DeepLBackend(
            keyProvider     = { "paid-abc" },
            enabledProvider = { true },
            client          = urlCapturingClient(captured),
        )
        backend.refreshStatus()
        assertTrue(
            "expected api.deepl.com URL, got ${captured.get()}",
            captured.get().startsWith("https://api.deepl.com/v2/usage"),
        )
    }

    @Test fun `status after refresh returns the cached dynamic state when configured`() = runBlocking {
        val backend = DeepLBackend(
            keyProvider     = { "k:fx" },
            enabledProvider = { true },
            client          = cannedClient(200, """{"character_count":7,"character_limit":42}"""),
        )
        backend.refreshStatus()
        val s = backend.status
        assertTrue(s is BackendStatus.Quota)
        assertEquals(7L, (s as BackendStatus.Quota).used)
        assertEquals(42L, s.limit)
    }

    @Test fun `re-keying after no-key state triggers a fresh fetch`() = runBlocking {
        var key: String? = ""
        val backend = DeepLBackend(
            keyProvider     = { key },
            enabledProvider = { true },
            client          = cannedClient(200, """{"character_count":7,"character_limit":42}"""),
        )
        // First refresh with no key surfaces the account requirement (and
        // internally resets the dynamic cache to Loading).
        val keyless = backend.refreshStatus()
        assertTrue("expected Account, got $keyless", keyless is BackendStatus.Account)
        assertEquals(
            AccountRequirement.REQUIRED_FREE_TIER,
            (keyless as BackendStatus.Account).requirement,
        )

        // After a key is added, the next refresh fetches and produces a Quota.
        key = "new-key:fx"
        val s = backend.refreshStatus()
        assertTrue("expected Quota after re-keying, got $s", s is BackendStatus.Quota)
        assertEquals(7L, (s as BackendStatus.Quota).used)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun cannedClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun ioFailingClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { _ ->
            throw IOException("simulated offline")
        }.build()

    private fun urlCapturingClient(captured: AtomicReference<String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request().url.toString())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("canned")
                .body("""{"character_count":0,"character_limit":1}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
}
