package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Regression coverage for the Codex adversarial-review finding:
 *
 *  > OpenAI client-side 400s are misclassified as network failures and
 *  > can trip cooldown.
 *
 * The fix introduces [StructuralFailureException] for deterministic
 * upstream rejections (4xx that isn't auth/rate-limit, empty payload,
 * missing translation). These tests prove that:
 *
 *  1. Two consecutive 400 responses do NOT engage the network ladder.
 *  2. True transport-level IOExceptions still DO engage the ladder on
 *     the second occurrence (so the structural distinction is precise,
 *     not "everything is ignored").
 */
class OpenAiBackendCooldownTest {

    @Test fun `HTTP 400 does not advance the network cooldown ladder`() = runBlocking {
        val cooldown = CooldownState(context = null, backendId = "openai")
        val backend = openAiWith(
            cooldown = cooldown,
            client = cannedClient(400, """{"error":{"message":"unknown model","type":"invalid_request_error"}}"""),
        )

        // First 400 — would have triggered the IO-pair-window primer.
        runCatching { backend.translate("hello", "ja", "en") }
        assertNull(
            "first 400 should be classified Structural, not Network",
            cooldown.unavailableUntil(),
        )

        // Second 400 — pre-fix, this would have engaged the network
        // ladder (Connection failed, 30 s) because the IO pair window
        // is still open. Post-fix, both throws are StructuralFailure
        // and recordNetworkFailure is never called.
        runCatching { backend.translate("hello", "ja", "en") }
        assertNull(
            "two consecutive 400s should still not cool the backend down",
            cooldown.unavailableUntil(),
        )
    }

    @Test fun `changing the API key clears an existing cooldown`() = runBlocking {
        // Regression for the second Codex finding: a cooldown set under
        // one key/model/baseUrl shouldn't pin the backend out of rotation
        // after the user replaces those credentials. The next time
        // unavailableUntil() (or translate) runs after the fingerprint
        // changes, the cooldown is cleared.
        val cooldown = CooldownState(context = null, backendId = "openai")
        var apiKey = "first-key"
        val backend = OpenAiBackend(
            id = "openai",
            displayName = "OpenAI",
            priority = 8,
            keyProvider = { apiKey },
            enabledProvider = { true },
            modelProvider = { "gpt-4" },
            baseUrlProvider = { "https://api.openai.com/v1" },
            usageTracker = UsageTracker(FakeSharedPreferences(), "openai"),
            filterFineTunes = true,
            cooldownState = cooldown,
            client = cannedClient(200, "{}"),
        )

        // First query records the fingerprint baseline.
        backend.unavailableUntil()

        // Cooldown gets set (e.g. user hits a quota on the first key).
        cooldown.recordParsedFailure(
            retryAt = System.currentTimeMillis() + 5L * 60 * 1000,
            description = "Billing exhausted",
        )
        assertNotNull("cooldown should be set", cooldown.unavailableUntil())

        // User swaps in a different key.
        apiKey = "second-key"

        // The next query at the Cooldownable surface detects the change
        // and clears the cooldown — the new key may be perfectly healthy.
        assertNull("expected cooldown cleared after key change",
            backend.unavailableUntil())
    }

    @Test fun `true network IOException still engages ladder on second hit`() = runBlocking {
        val cooldown = CooldownState(context = null, backendId = "openai")
        val backend = openAiWith(
            cooldown = cooldown,
            client = ioFailingClient(),
        )

        // First connection failure — forgiven by the first-IOException-
        // ignored rule (wifi blips happen).
        runCatching { backend.translate("hello", "ja", "en") }
        assertNull(
            "first connection failure should be forgiven",
            cooldown.unavailableUntil(),
        )

        // Second connection failure within the pair window — the ladder
        // engages, surfacing as "Connection failed" with the 30 s
        // bottom-rung cooldown.
        runCatching { backend.translate("hello", "ja", "en") }
        assertNotNull(
            "second connection failure should engage the network ladder",
            cooldown.unavailableUntil(),
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun openAiWith(cooldown: CooldownState, client: OkHttpClient): OpenAiBackend =
        OpenAiBackend(
            id = "openai",
            displayName = "OpenAI",
            priority = 8,
            keyProvider = { "test-key" },
            enabledProvider = { true },
            modelProvider = { "gpt-4" },
            baseUrlProvider = { "https://api.openai.com/v1" },
            usageTracker = UsageTracker(FakeSharedPreferences(), "openai"),
            filterFineTunes = true,
            cooldownState = cooldown,
            client = client,
        )

    private fun cannedClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun ioFailingClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { _ ->
            throw IOException("synthetic connection failure")
        }.build()
}
