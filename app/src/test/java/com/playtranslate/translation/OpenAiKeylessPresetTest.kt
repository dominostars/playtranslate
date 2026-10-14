package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * [OpenAiBackend] for a provider that takes no key (Bilibili's
 * Index-Translate, [AccountRequirement.NONE]): every reader of the key goes
 * through one credential gate, so the keyless cells and the keyed ones can't
 * disagree.
 *
 * Keyless: usable with no key, reads "No account required" for good, sends
 * no Authorization on either chat path, and takes a 401 or 403 as the
 * endpoint refusing us ([isKeylessRefusal]: a cooldown and a rate-limit
 * failure, never "Invalid API key"). Keyed presets keep every old behaviour,
 * pinned here as control cells.
 */
class OpenAiKeylessPresetTest {

    private val clock = AtomicLong(1_000_000L)

    private fun newCooldown() =
        CooldownState(context = null, backendId = "index", nowMs = { clock.get() })

    /** A backend whose client records each request and answers [code] with
     *  [headers]; a success body fits whichever path asked. */
    private fun backend(
        account: AccountRequirement,
        key: String = "",
        enabled: Boolean = true,
        seen: MutableList<Request> = mutableListOf(),
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
        cooldown: CooldownState? = null,
    ) = OpenAiBackend(
        id = "index",
        displayName = "Bilibili Index-Translate",
        priority = 15,
        keyProvider = { key },
        enabledProvider = { enabled },
        modelProvider = { "Index-Translate-35B-A3B" },
        baseUrlProvider = { BASE },
        account = { account },
        usageTracker = UsageTracker(FakeSharedPreferences(), "index"),
        applyOwnedByFilter = { false },
        cooldownState = cooldown,
        client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            val batch = chain.request().body.let { body ->
                val buffer = okio.Buffer()
                body?.writeTo(buffer)
                buffer.readUtf8().contains("\"response_format\"")
            }
            val payload = when {
                code !in 200..299 -> """{"error":"refused"}"""
                batch -> BATCH_OK
                else -> SINGLE_OK
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(payload.toResponseBody("application/json".toMediaType()))
                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                .build()
        }.build(),
    )

    @Test fun `keyless is usable with no key, only while enabled`() {
        assertTrue(backend(AccountRequirement.NONE).isUsable("ja", "en"))
        assertFalse(backend(AccountRequirement.NONE, enabled = false).isUsable("ja", "en"))
    }

    @Test fun `keyless status reads No account required, key or no key`() {
        assertEquals(
            BackendStatus.Account(AccountRequirement.NONE),
            backend(AccountRequirement.NONE).status,
        )
        // A stale key left in the slot changes nothing: the provider takes none.
        assertEquals(
            BackendStatus.Account(AccountRequirement.NONE),
            backend(AccountRequirement.NONE, key = "stale").status,
        )
    }

    @Test fun `keyless sends no Authorization on either chat path`() = runBlocking {
        val seen = mutableListOf<Request>()
        // Even with a stale key in the slot: there is no key to send.
        val b = backend(AccountRequirement.NONE, key = "stale", seen = seen)
        assertEquals("こんにちは", b.translate("hello", "en", "ja"))
        assertEquals(listOf("あ", "い"), b.translateBatch(listOf("a", "b"), "en", "ja"))
        assertEquals(2, seen.size)
        seen.forEach { request ->
            assertNull(request.header("Authorization"))
            assertEquals("$BASE/chat/completions", request.url.toString())
        }
    }

    @Test fun `keyless 401 and 403 cool down on Retry-After as rate limits, on both paths`() {
        for (code in listOf(401, 403)) {
            for (batch in listOf(false, true)) {
                val cooldown = newCooldown()
                val b = backend(
                    AccountRequirement.NONE, code = code,
                    headers = mapOf("Retry-After" to "120"), cooldown = cooldown,
                )
                val thrown = runCatching {
                    runBlocking {
                        if (batch) b.translateBatch(listOf("a", "b"), "en", "ja")
                        else b.translate("hello", "en", "ja")
                    }
                }.exceptionOrNull()
                val cell = "code=$code batch=$batch"
                assertTrue("$cell: $thrown", thrown is OpenAiRateLimitException)
                assertEquals(
                    cell,
                    BackendFailure(BackendFailureKind.RATE_LIMITED, code),
                    (thrown as OpenAiRateLimitException).failure,
                )
                assertEquals(cell, clock.get() + 120_000L, cooldown.unavailableUntil())
                assertEquals(cell, "Rate limited", cooldown.unavailableDescription())
            }
        }
    }

    @Test fun `keyless refusal with no Retry-After takes the first ladder rung`() {
        val cooldown = newCooldown()
        val b = backend(AccountRequirement.NONE, code = 403, cooldown = cooldown)
        runCatching { runBlocking { b.translate("hello", "en", "ja") } }
        assertEquals(clock.get() + 60_000L, cooldown.unavailableUntil())
    }

    // ── Control cells: a keyed preset keeps every old behaviour ──────────

    @Test fun `keyed with a blank key stays unconfigured`() {
        val b = backend(AccountRequirement.REQUIRED, key = "")
        assertFalse(b.isUsable("ja", "en"))
        assertEquals(BackendStatus.Account(AccountRequirement.REQUIRED), b.status)
        val thrown = runCatching { runBlocking { b.translate("hello", "en", "ja") } }.exceptionOrNull()
        assertTrue("$thrown", thrown is StructuralFailureException)
        assertEquals(
            BackendFailureKind.AUTH,
            (thrown as StructuralFailureException).failure.kind,
        )
    }

    @Test fun `keyed sends its Bearer key`() = runBlocking {
        val seen = mutableListOf<Request>()
        backend(AccountRequirement.REQUIRED, key = "sk-test", seen = seen).translate("hello", "en", "ja")
        assertEquals("Bearer sk-test", seen.single().header("Authorization"))
    }

    @Test fun `keyed 401 is still a bad key and 403 still a rejection`() {
        val cooldown = newCooldown()
        val auth = runCatching {
            runBlocking { backend(AccountRequirement.REQUIRED, key = "sk", code = 401, cooldown = cooldown).translate("x", "en", "ja") }
        }.exceptionOrNull()
        assertTrue("$auth", auth is OpenAiAuthException)

        val rejected = runCatching {
            runBlocking { backend(AccountRequirement.REQUIRED, key = "sk", code = 403, cooldown = cooldown).translate("x", "en", "ja") }
        }.exceptionOrNull()
        assertTrue("$rejected", rejected is StructuralFailureException)
        assertEquals(
            BackendFailure(BackendFailureKind.REJECTED, 403),
            (rejected as StructuralFailureException).failure,
        )
        assertNull("neither keyed refusal cools down", cooldown.unavailableUntil())
    }

    private companion object {
        const val BASE = "https://index-translate.example/v1"
        const val SINGLE_OK = """{"choices":[{"message":{"content":"こんにちは"}}]}"""
        const val BATCH_OK = """{"choices":[{"message":{"content":"{\"translations\":[\"あ\",\"い\"]}"}}]}"""
    }
}
