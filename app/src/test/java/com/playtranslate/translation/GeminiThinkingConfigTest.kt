package com.playtranslate.translation

import com.playtranslate.PtJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Gemini's thinking pick rides inside `generationConfig`, not at the body's
 * top level, on the single-text and the batch path alike, beside the fields
 * each path already sends (the temperature; the batch's JSON schema). A
 * service that never picked sends its model's lowest level.
 */
class GeminiThinkingConfigTest {

    private fun instance(pick: ReasoningLevel) = OnlineServiceInstance(
        id = "g", type = ServiceType.GEMINI, enabled = true, model = "gemini-flash-lite-latest", reasoning = pick,
    )

    @Test
    fun aLevelReachesBothGenerationConfigs() = runBlocking {
        val seen = mutableListOf<JsonObject>()
        val backend = backendWith(seen, "gemini-flash-lite-latest", instance(ReasoningLevel.LOW))
        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(2, seen.size)
        seen.forEach { body ->
            val config = body.getValue("generationConfig").jsonObject
            assertEquals(body.toString(), """{"thinkingLevel":"low"}""", config.getValue("thinkingConfig").toString())
            assertEquals("0.2", config.getValue("temperature").jsonPrimitive.content)
            assertFalse("not at the top level", body.containsKey("thinkingConfig"))
        }
        val batchConfig = seen[1].getValue("generationConfig").jsonObject
        assertTrue("batch keeps its schema", batchConfig.containsKey("responseSchema"))
        assertEquals("application/json", batchConfig.getValue("responseMimeType").jsonPrimitive.content)
    }

    /** No pick sends the lowest level the model names, explicitly: minimal
     *  on the default model. */
    @Test
    fun noPickSendsTheLowestLevel() = runBlocking {
        val seen = mutableListOf<JsonObject>()
        val backend = backendWith(seen, "gemini-flash-lite-latest", instance(ReasoningLevel.UNSET))
        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(2, seen.size)
        seen.forEach {
            assertEquals(
                it.toString(), """{"thinkingLevel":"minimal"}""",
                it.getValue("generationConfig").jsonObject.getValue("thinkingConfig").toString(),
            )
        }
    }

    /** The hook resolves the pick for the model the call is made with:
     *  3.8 Flash has no minimal and starts at low; a 2.5 Flash-Lite model
     *  takes a budget and starts off. */
    @Test
    fun theModelOfTheCallDecidesTheShape() {
        fun thinkingConfig(model: String): String {
            val seen = mutableListOf<JsonObject>()
            runBlocking { backendWith(seen, model, instance(ReasoningLevel.UNSET)).translate("hello", "en", "ja") }
            return seen.single().getValue("generationConfig").jsonObject.getValue("thinkingConfig").toString()
        }
        assertEquals("""{"thinkingLevel":"low"}""", thinkingConfig("gemini-flash-latest"))
        assertEquals("""{"thinkingBudget":0}""", thinkingConfig("gemini-2.5-flash-lite"))
    }

    /** A Gemini backend whose client records every request body and answers
     *  a success for either path, its hook reading [instance] as the
     *  factory's does. */
    private fun backendWith(seen: MutableList<JsonObject>, model: String, instance: OnlineServiceInstance) = GeminiBackend(
        id = "gemini-test",
        keyProvider = { "AIza-test" },
        enabledProvider = { true },
        modelProvider = { model },
        generationConfigExtras = { m -> OnlineBackendFactory.generationConfigExtrasFor(instance, m) },
        usageTracker = UsageTracker(FakeSharedPreferences(), "gemini-test"),
        cooldownState = CooldownState(null, "gemini-test"),
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val body = PtJson.lenient.parseToJsonElement(buffer.readUtf8()).jsonObject
            seen += body
            val batch = body.getValue("generationConfig").jsonObject.containsKey("responseSchema")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body((if (batch) BATCH_OK else SINGLE_OK).toResponseBody("application/json".toMediaType()))
                .build()
        }.build(),
    )

    private companion object {
        const val SINGLE_OK = """{"candidates":[{"content":{"parts":[{"text":"こんにちは"}]}}]}"""
        const val BATCH_OK = """{"candidates":[{"content":{"parts":[{"text":"{\"translations\":[\"あ\",\"い\"]}"}]}}]}"""
    }
}
