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
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Asking a provider's catalog about one model ([OpenAiBackend.reportedThinking]):
 * the URL and auth headers of each catalog, and the part of the answer kept as
 * the report. DeepSeek and OpenRouter are asked for their model list and the
 * model found in it; Claude is asked for the one model. An answer that says
 * nothing about the model is null, which stores nothing; a model listed
 * without thinking options is `{}`. Only DeepSeek, OpenRouter and Claude
 * publish them.
 */
class ThinkingCatalogTest {

    @Test fun `only DeepSeek, OpenRouter and Claude have a catalog`() {
        val expected = mapOf(
            OpenAiPreset.DEEPSEEK to ThinkingCatalog.DEEPSEEK,
            OpenAiPreset.OPENROUTER to ThinkingCatalog.OPENROUTER,
            OpenAiPreset.CLAUDE to ThinkingCatalog.CLAUDE,
        )
        OpenAiPreset.entries.forEach { assertEquals(it.name, expected[it], ThinkingCatalog.forPreset(it)) }
    }

    @Test fun `DeepSeek is asked for its model list and the model's effort is kept`() = runBlocking {
        val seen = mutableListOf<Request>()
        val body = """{"object":"list","data":[""" +
            """{"id":"deepseek-flash","object":"model","owned_by":"deepseek","effort":{"supported_levels":["low","high","max"],"default_level":"high"}},""" +
            """{"id":"deepseek-chat-legacy","object":"model","owned_by":"deepseek"}]}"""
        val backend = backendWith(seen, ThinkingCatalog.DEEPSEEK, modelsUrl = "https://api.deepseek.com", answer = body)

        assertEquals(
            """{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""",
            backend.reportedThinking("deepseek-flash").toString(),
        )
        assertEquals("https://api.deepseek.com/models", seen.single().url.toString())
        assertEquals("Bearer sk-test", seen.single().header("Authorization"))
        assertEquals("{}", backend.reportedThinking("deepseek-chat-legacy").toString())
        assertNull("not listed: nothing to store", backend.reportedThinking("deepseek-v9"))
    }

    @Test fun `OpenRouter is asked for its model list and the model's reasoning is kept`() = runBlocking {
        val seen = mutableListOf<Request>()
        val body = """{"data":[""" +
            """{"id":"google/gemini-2.5-flash-lite","reasoning":{"supported_efforts":["high","medium","low"],"mandatory":false}},""" +
            """{"id":"meta-llama/llama-3.3-70b-instruct"}]}"""
        val backend = backendWith(seen, ThinkingCatalog.OPENROUTER, modelsUrl = "https://openrouter.ai/api/v1", answer = body)

        assertEquals(
            """{"reasoning":{"supported_efforts":["high","medium","low"],"mandatory":false}}""",
            backend.reportedThinking("google/gemini-2.5-flash-lite").toString(),
        )
        assertEquals("https://openrouter.ai/api/v1/models", seen.single().url.toString())
        assertEquals("{}", backend.reportedThinking("meta-llama/llama-3.3-70b-instruct").toString())
    }

    /** Claude's catalog is the native endpoint, with its own headers; it
     *  is asked about the one model. */
    @Test fun `Claude is asked about the model and its thinking capability is kept`() = runBlocking {
        val seen = mutableListOf<Request>()
        val body = """{"id":"claude-sonnet-5-5","type":"model","capabilities":{"effort":{"supported":true},""" +
            """"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"between_tools":{"supported":true},""" +
            """"disabled":{"supported":false},"enabled":{"supported":false}}}}}"""
        val backend = backendWith(
            seen, ThinkingCatalog.CLAUDE, modelsUrl = "https://api.anthropic.com/v1", answer = body,
            modelsAuthHeaders = { OnlineBackendFactory.modelsAuthHeadersFor(OpenAiPreset.CLAUDE, it) },
        )

        val facts = backend.reportedThinking("claude-sonnet-5-5").toString()
        assertTrue(facts, facts.startsWith("""{"thinking":{"supported":true,"types":{"adaptive":{"supported":true}"""))
        assertFalse("only the thinking subtree is kept", facts.contains("effort"))
        val request = seen.single()
        assertEquals("https://api.anthropic.com/v1/models/claude-sonnet-5-5", request.url.toString())
        assertEquals("sk-test", request.header("x-api-key"))
        assertEquals(OnlineServiceStore.CLAUDE_API_VERSION, request.header("anthropic-version"))
        assertNull(request.header("Authorization"))
    }

    /** `capabilities` null says nothing about thinking: nothing to store, and
     *  the model keeps the request it had. */
    @Test fun `a Claude answer without capabilities is null`() = runBlocking {
        val backend = backendWith(
            mutableListOf(), ThinkingCatalog.CLAUDE, modelsUrl = "https://api.anthropic.com/v1",
            answer = """{"id":"claude-sonnet-5","capabilities":null}""",
        )
        assertNull(backend.reportedThinking("claude-sonnet-5"))
    }

    @Test fun `a provider without a catalog asks nothing`() = runBlocking {
        val seen = mutableListOf<Request>()
        val backend = backendWith(seen, catalog = null, modelsUrl = "https://api.openai.com/v1", answer = "{}")
        assertNull(backend.reportedThinking("gpt-6-luna"))
        assertTrue(seen.isEmpty())
    }

    /** A failure is thrown, so the refresher keeps the report it had. */
    @Test fun `a rejected key or a failed call throws`() = runBlocking {
        for ((code, expected) in listOf(401 to OpenAiAuthException::class.java, 500 to IOException::class.java)) {
            val backend = backendWith(mutableListOf(), ThinkingCatalog.DEEPSEEK, "https://api.deepseek.com", "{}", code = code)
            try {
                backend.reportedThinking("deepseek-flash")
                fail("HTTP $code should throw")
            } catch (e: IOException) {
                assertTrue("$code: ${e.javaClass}", expected.isInstance(e))
            }
        }
    }

    private fun backendWith(
        seen: MutableList<Request>,
        catalog: ThinkingCatalog?,
        modelsUrl: String,
        answer: String,
        code: Int = 200,
        modelsAuthHeaders: (String) -> Map<String, String> = { mapOf("Authorization" to "Bearer $it") },
    ): OpenAiBackend = OpenAiBackend(
        id = "catalog-test",
        displayName = "Test",
        priority = 15,
        keyProvider = { "sk-test" },
        enabledProvider = { true },
        modelProvider = { "m" },
        baseUrlProvider = { modelsUrl },
        modelsUrlProvider = { modelsUrl },
        modelsAuthHeaders = modelsAuthHeaders,
        usageTracker = UsageTracker(FakeSharedPreferences(), "catalog-test"),
        applyOwnedByFilter = { false },
        thinkingCatalog = { catalog },
        client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("x")
                .body(answer.toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )
}
