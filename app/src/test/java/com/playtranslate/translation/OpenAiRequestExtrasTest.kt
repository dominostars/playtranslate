package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-model request extras: Claude's thinking switch, and the hook that
 * carries it onto both chat bodies. Anthropic's compatible layer thinks by
 * default on Claude 5 models (Sonnet 5: 4.9 s and 23.4 s for two small
 * batches on the Thor, 2026-09-06), and `thinking` is the only field it
 * passes through, so the switch has to reach the wire on the single-text
 * and the batch path alike.
 */
class OpenAiRequestExtrasTest {

    private val disabled = """"thinking":{"type":"disabled"}"""

    @Test
    fun claudeModelsGetThinkingOff() {
        for (model in listOf("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001", "claude-opus-4-8")) {
            val extras = OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, model)
            assertEquals(model, """{"type":"disabled"}""", extras["thinking"].toString())
        }
    }

    /** Fable and Mythos reject an explicit off with a 400. */
    @Test
    fun alwaysOnModelsGetNoField() {
        for (model in listOf("claude-fable-5-1", "claude-fable-5", "claude-mythos-5-1")) {
            assertTrue(model, OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, model).isEmpty())
        }
    }

    @Test
    fun otherPresetsSendNothingExtra() {
        OpenAiPreset.entries.filter { it != OpenAiPreset.CLAUDE }.forEach { preset ->
            assertTrue(preset.name, OnlineBackendFactory.requestExtrasFor(preset, "claude-sonnet-5").isEmpty())
        }
    }

    @Test
    fun extrasReachTheSingleTextBody() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, it) }
        backend.translate("hello", "en", "ja")
        assertEquals(1, seen.size)
        assertTrue(seen[0], seen[0].contains(disabled))
    }

    @Test
    fun extrasReachTheBatchBody() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, it) }
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(1, seen.size)
        assertTrue(seen[0], seen[0].contains(disabled))
        assertTrue("batch keeps its schema", seen[0].contains("\"response_format\""))
    }

    @Test
    fun defaultHookSendsNothing() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { emptyMap() }
        backend.translate("hello", "en", "ja")
        assertFalse(seen[0], seen[0].contains("\"thinking\""))
    }

    /** A backend whose client records every request body and answers a
     *  minimal success for either path. */
    private fun backendWith(
        seen: MutableList<String>,
        extras: (String) -> Map<String, JsonElement>,
    ): OpenAiBackend = OpenAiBackend(
        id = "claude-test",
        displayName = "Claude",
        priority = 15,
        keyProvider = { "sk-ant-test" },
        enabledProvider = { true },
        modelProvider = { "claude-sonnet-5" },
        baseUrlProvider = { "https://api.anthropic.com/v1" },
        usageTracker = UsageTracker(FakeSharedPreferences(), "claude-test"),
        applyOwnedByFilter = { false },
        requestExtras = extras,
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            seen += buffer.readUtf8()
            val body = if (seen.last().contains("\"response_format\"")) BATCH_OK else SINGLE_OK
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )

    private companion object {
        const val SINGLE_OK = """{"choices":[{"message":{"content":"こんにちは"}}]}"""
        const val BATCH_OK = """{"choices":[{"message":{"content":"{\"translations\":[\"あ\",\"い\"]}"}}]}"""
    }
}
