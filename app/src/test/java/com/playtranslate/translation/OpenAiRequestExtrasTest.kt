package com.playtranslate.translation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * and the batch path alike. Index-Translate's Qwen 3.5 models think unless
 * the chat template is told not to, and Bilibili's own client always does.
 * DeepSeek's API thinks by default when a request says nothing (issue #41:
 * every translation ran in thinking mode at reasoning-token prices), so its
 * request always carries the instance's Thinking switch, off by default.
 */
class OpenAiRequestExtrasTest {

    private val disabled = """"thinking":{"type":"disabled"}"""
    private val enabled = """"thinking":{"type":"enabled"}"""

    @Test
    fun claudeModelsGetThinkingOff() {
        for (model in listOf("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001", "claude-opus-4-8")) {
            val extras = OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, model, thinking = false)
            assertEquals(model, """{"type":"disabled"}""", extras["thinking"].toString())
        }
    }

    /** Fable and Mythos reject an explicit off with a 400. */
    @Test
    fun alwaysOnModelsGetNoField() {
        for (model in listOf("claude-fable-5-1", "claude-fable-5", "claude-mythos-5-1")) {
            assertTrue(model, OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, model, thinking = false).isEmpty())
        }
    }

    /** Every preset's thinking switch, as a table, with the instance's
     *  Thinking switch off (the default): Claude's `thinking`, DeepSeek's
     *  explicit off, Index-Translate's chat-template switch, nothing for
     *  the rest. */
    @Test
    fun everyPresetSendsItsOwnExtras() {
        OpenAiPreset.entries.forEach { preset ->
            val expected = when (preset) {
                OpenAiPreset.CLAUDE -> """{"thinking":{"type":"disabled"}}"""
                OpenAiPreset.DEEPSEEK -> """{"thinking":{"type":"disabled"}}"""
                OpenAiPreset.INDEX_TRANSLATE -> """{"chat_template_kwargs":{"enable_thinking":false}}"""
                else -> "{}"
            }
            val extras = OnlineBackendFactory.requestExtrasFor(preset, "claude-sonnet-5", thinking = false)
            assertEquals(preset.name, expected, JsonObject(extras).toString())
        }
    }

    /** DeepSeek never leaves the mode to the provider's default: the switch
     *  off is an explicit off, on an explicit on. */
    @Test
    fun deepSeekSendsTheSwitchEitherWay() {
        for (model in listOf("deepseek-v4-flash", "deepseek-flash", "deepseek-v4-pro")) {
            assertEquals(
                model, """{"thinking":{"type":"disabled"}}""",
                JsonObject(OnlineBackendFactory.requestExtrasFor(OpenAiPreset.DEEPSEEK, model, thinking = false)).toString(),
            )
            assertEquals(
                model, """{"thinking":{"type":"enabled"}}""",
                JsonObject(OnlineBackendFactory.requestExtrasFor(OpenAiPreset.DEEPSEEK, model, thinking = true)).toString(),
            )
        }
    }

    /** The instance's Thinking switch changes the request exactly for the
     *  presets whose config page shows it ([OpenAiPreset.thinkingSwitch]):
     *  a switch that changed nothing, or a request that read a switch no
     *  page shows, would both be a lie to the user. */
    @Test
    fun theSwitchMovesTheRequestExactlyWhereAPageShowsIt() {
        for (model in listOf("deepseek-v4-flash", "claude-sonnet-5", "claude-fable-5-1", "chat-latest")) {
            OpenAiPreset.entries.forEach { preset ->
                val off = OnlineBackendFactory.requestExtrasFor(preset, model, thinking = false)
                val on = OnlineBackendFactory.requestExtrasFor(preset, model, thinking = true)
                assertEquals("$preset $model", preset.thinkingSwitch, off != on)
            }
        }
        assertEquals(listOf(OpenAiPreset.DEEPSEEK), OpenAiPreset.entries.filter { it.thinkingSwitch })
    }

    /** The live hook reads the instance: a DeepSeek instance's own switch
     *  decides, and an instance of another preset ignores the flag. */
    @Test
    fun anInstanceSendsItsOwnSwitch() {
        fun instance(preset: OpenAiPreset, thinking: Boolean) = OnlineServiceInstance(
            id = "i", type = ServiceType.OPENAI, enabled = true, preset = preset, thinking = thinking,
        )
        fun extras(preset: OpenAiPreset, thinking: Boolean) =
            JsonObject(OnlineBackendFactory.requestExtrasFor(instance(preset, thinking), "deepseek-v4-flash")).toString()
        assertEquals("""{"thinking":{"type":"enabled"}}""", extras(OpenAiPreset.DEEPSEEK, thinking = true))
        assertEquals("""{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.DEEPSEEK, thinking = false))
        assertEquals("""{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.CLAUDE, thinking = true))
        assertEquals("{}", extras(OpenAiPreset.MISTRAL, thinking = true))
    }

    /** DeepSeek's switch rides the same hook as the others, so both bodies
     *  carry it, off and on. */
    @Test
    fun deepSeekSwitchReachesBothBodies() = runBlocking {
        for ((thinking, field) in listOf(false to disabled, true to enabled)) {
            val seen = mutableListOf<String>()
            val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.DEEPSEEK, it, thinking) }
            backend.translate("hello", "en", "ja")
            backend.translateBatch(listOf("a", "b"), "en", "ja")
            assertEquals(2, seen.size)
            seen.forEach { assertTrue("thinking=$thinking $it", it.contains(field)) }
        }
    }

    /** Index-Translate's switch is model-independent: every model behind
     *  that endpoint is a Qwen 3.5 hybrid thinker. */
    @Test
    fun indexTranslateThinkingOffReachesBothBodies() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.INDEX_TRANSLATE, it, thinking = false) }
        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(2, seen.size)
        seen.forEach { assertTrue(it, it.contains(INDEX_THINKING_OFF)) }
    }

    @Test
    fun extrasReachTheSingleTextBody() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, it, thinking = false) }
        backend.translate("hello", "en", "ja")
        assertEquals(1, seen.size)
        assertTrue(seen[0], seen[0].contains(disabled))
    }

    @Test
    fun extrasReachTheBatchBody() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen) { OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, it, thinking = false) }
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
        const val INDEX_THINKING_OFF = """"chat_template_kwargs":{"enable_thinking":false}"""
        const val SINGLE_OK = """{"choices":[{"message":{"content":"こんにちは"}}]}"""
        const val BATCH_OK = """{"choices":[{"message":{"content":"{\"translations\":[\"あ\",\"い\"]}"}}]}"""
    }
}
