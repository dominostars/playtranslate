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
 * The per-model request extras on the OpenAI-compatible path: the instance's
 * thinking pick as its preset's [ReasoningControls] entry sends it, carried
 * by the hook onto both chat bodies. Thinking is where the time goes (Sonnet
 * 5 with its default thinking: 4.9 s and 23.4 s for two small batches on the
 * Thor, 2026-09-06), so a pick has to reach the wire on the single-text and
 * the batch path alike. The tables themselves are [ReasoningControlsTest]
 * and [ReportedThinkingTest]; this pins the hook and the reply it reads back.
 */
class OpenAiRequestExtrasTest {

    private fun extras(preset: OpenAiPreset, model: String, pick: ReasoningLevel, reported: ReportedThinking? = null) =
        JsonObject(OnlineBackendFactory.requestExtrasFor(preset, model, pick, reported)).toString()

    /** What a provider's catalog reported about [model]. */
    private fun reported(model: String, json: String) = ReportedThinking(
        model, com.playtranslate.PtJson.lenient.parseToJsonElement(json) as JsonObject, fetchedAtMs = 0,
    )

    private fun deepSeekLevels(model: String) = reported(model, """{"effort":{"supported_levels":["low","high","max"]}}""")
    private fun claudeAdaptive(model: String) = reported(
        model,
        """{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"disabled":{"supported":true},""" +
            """"between_tools":{"supported":true},"enabled":{"supported":false}}}}""",
    )

    /** Before any report, Claude sends the off each known model took
     *  before the row existed. */
    @Test
    fun claudeStartsWithThinkingOff() {
        for (model in listOf("claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001", "claude-opus-4-8")) {
            assertEquals(model, """{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.CLAUDE, model, ReasoningLevel.UNSET))
        }
        assertEquals(
            """{"thinking":{"type":"between_tools"}}""",
            extras(OpenAiPreset.CLAUDE, "claude-sonnet-5-5", ReasoningLevel.UNSET),
        )
    }

    /** Opus 5.5, Fable and Mythos reject an explicit off with a 400. */
    @Test
    fun alwaysOnModelsGetNoField() {
        for (model in listOf("claude-opus-5-5", "claude-fable-5-1", "claude-fable-5", "claude-mythos-5-1")) {
            for (pick in ReasoningLevel.entries) {
                assertEquals("$model $pick", "{}", extras(OpenAiPreset.CLAUDE, model, pick))
            }
        }
    }

    /** Every preset's request for a pick that was never made, with no
     *  report yet, as a table: the start states, each the lowest the model
     *  takes. Off where it can stop thinking (DeepSeek, Claude, Mistral's
     *  explicit "none"), Low where it can't (OpenAI's and Groq's reasoning
     *  models), Index-Translate's chat-template switch, and nothing for a
     *  model with no row, which is OpenRouter's until its catalog reports. */
    @Test
    fun everyPresetStartsLowOrOff() {
        val defaults = mapOf(
            OpenAiPreset.OPENAI to ("gpt-6-luna" to """{"reasoning_effort":"low"}"""),
            OpenAiPreset.DEEPSEEK to ("deepseek-flash" to """{"thinking":{"type":"disabled"}}"""),
            OpenAiPreset.MISTRAL to ("mistral-small-2603" to """{"reasoning_effort":"none"}"""),
            OpenAiPreset.GROQ to ("openai/gpt-oss-20b" to """{"reasoning_effort":"low"}"""),
            OpenAiPreset.OPENROUTER to ("google/gemini-2.5-flash-lite" to "{}"),
            OpenAiPreset.CLAUDE to ("claude-sonnet-5" to """{"thinking":{"type":"disabled"}}"""),
            OpenAiPreset.INDEX_TRANSLATE to ("Index-Translate-35B-A3B" to """{"chat_template_kwargs":{"enable_thinking":false}}"""),
            OpenAiPreset.CUSTOM to ("gpt-6-luna" to "{}"),
        )
        assertEquals(OpenAiPreset.entries.toSet(), defaults.keys)
        defaults.forEach { (preset, case) ->
            val (model, expected) = case
            assertEquals(preset.name, expected, extras(preset, model, ReasoningLevel.UNSET))
        }
    }

    /** DeepSeek never leaves the mode to the provider's default: Off is an
     *  explicit off, and a reported level turns thinking on at that effort.
     *  Before a report, every pick is off. */
    @Test
    fun deepSeekSendsEveryPickExplicitly() {
        for (model in listOf("deepseek-v4-flash", "deepseek-flash", "deepseek-v4-pro")) {
            val report = deepSeekLevels(model)
            assertEquals(model, """{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.DEEPSEEK, model, ReasoningLevel.OFF, report))
            assertEquals(
                model, """{"thinking":{"type":"enabled"},"reasoning_effort":"low"}""",
                extras(OpenAiPreset.DEEPSEEK, model, ReasoningLevel.LOW, report),
            )
            assertEquals(
                model, """{"thinking":{"type":"enabled"},"reasoning_effort":"high"}""",
                extras(OpenAiPreset.DEEPSEEK, model, ReasoningLevel.HIGH, report),
            )
            assertEquals(model, """{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.DEEPSEEK, model, ReasoningLevel.HIGH))
        }
    }

    /** The live hook reads the instance: its own pick and its own report
     *  decide, resolved for the model the call is made with. */
    @Test
    fun anInstanceSendsItsOwnPick() {
        fun instance(preset: OpenAiPreset, pick: ReasoningLevel, report: ReportedThinking? = null) = OnlineServiceInstance(
            id = "i", type = ServiceType.OPENAI, enabled = true, preset = preset, reasoning = pick, reportedThinking = report,
        )
        fun extras(preset: OpenAiPreset, pick: ReasoningLevel, model: String, report: ReportedThinking? = null) =
            JsonObject(OnlineBackendFactory.requestExtrasFor(instance(preset, pick, report), model)).toString()
        assertEquals("""{"reasoning_effort":"low"}""", extras(OpenAiPreset.OPENAI, ReasoningLevel.LOW, "gpt-6-luna"))
        // The same pick after the picker moved the instance to an Instant model.
        assertEquals("{}", extras(OpenAiPreset.OPENAI, ReasoningLevel.LOW, "chat-latest"))
        val sonnet = claudeAdaptive("claude-sonnet-5")
        assertEquals(
            """{"thinking":{"type":"adaptive"}}""", extras(OpenAiPreset.CLAUDE, ReasoningLevel.ON, "claude-sonnet-5", sonnet),
        )
        // A level picked on another provider reads as Claude's start state.
        assertEquals(
            """{"thinking":{"type":"disabled"}}""", extras(OpenAiPreset.CLAUDE, ReasoningLevel.LOW, "claude-sonnet-5", sonnet),
        )
        // The report is about Sonnet 5; on another model it counts for nothing.
        assertEquals(
            """{"thinking":{"type":"between_tools"}}""",
            extras(OpenAiPreset.CLAUDE, ReasoningLevel.ON, "claude-sonnet-5-5", sonnet),
        )
    }

    /** Every pick rides the same hook, so both bodies carry it. */
    @Test
    fun aPickReachesBothBodies() = runBlocking {
        val cases = listOf(
            Triple(OpenAiPreset.DEEPSEEK, "deepseek-flash", """"reasoning_effort":"low""""),
            Triple(OpenAiPreset.OPENAI, "gpt-6-luna", """"reasoning_effort":"low""""),
            Triple(OpenAiPreset.OPENROUTER, "openai/gpt-6-luna", """"reasoning":{"effort":"low"}"""),
        )
        val reports = mapOf(
            OpenAiPreset.DEEPSEEK to deepSeekLevels("deepseek-flash"),
            OpenAiPreset.OPENROUTER to reported(
                "openai/gpt-6-luna", """{"reasoning":{"supported_efforts":["high","medium","low"],"mandatory":false}}""",
            ),
        )
        for ((preset, model, field) in cases) {
            val seen = mutableListOf<String>()
            val backend = backendWith(seen, model) {
                OnlineBackendFactory.requestExtrasFor(preset, it, ReasoningLevel.LOW, reports[preset])
            }
            backend.translate("hello", "en", "ja")
            backend.translateBatch(listOf("a", "b"), "en", "ja")
            assertEquals(2, seen.size)
            seen.forEach { assertTrue("$preset $it", it.contains(field)) }
        }
    }

    /** Index-Translate's switch is model-independent: every model behind
     *  that endpoint is a Qwen 3.5 hybrid thinker. */
    @Test
    fun indexTranslateThinkingOffReachesBothBodies() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen, "Index-Translate-35B-A3B") {
            OnlineBackendFactory.requestExtrasFor(OpenAiPreset.INDEX_TRANSLATE, it, ReasoningLevel.UNSET)
        }
        backend.translate("hello", "en", "ja")
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(2, seen.size)
        seen.forEach { assertTrue(it, it.contains(INDEX_THINKING_OFF)) }
    }

    @Test
    fun extrasReachTheBatchBodyBesideItsSchema() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen, "claude-sonnet-5") {
            OnlineBackendFactory.requestExtrasFor(OpenAiPreset.CLAUDE, it, ReasoningLevel.UNSET)
        }
        backend.translateBatch(listOf("a", "b"), "en", "ja")
        assertEquals(1, seen.size)
        assertTrue(seen[0], seen[0].contains(""""thinking":{"type":"disabled"}"""))
        assertTrue("batch keeps its schema", seen[0].contains("\"response_format\""))
    }

    /** Haiku 4.5 thinks only on a token budget, which has to sit below
     *  `max_tokens`; the app sends no `max_tokens` otherwise. */
    @Test
    fun claudeBudgetThinkingCarriesItsMaxTokens() {
        val haiku = reported(
            "claude-haiku-4-5-20251001",
            """{"thinking":{"supported":true,"types":{"adaptive":{"supported":false},"disabled":{"supported":true},""" +
                """"between_tools":{"supported":false},"enabled":{"supported":true}}}}""",
        )
        assertEquals(
            """{"thinking":{"type":"enabled","budget_tokens":1024},"max_tokens":8192}""",
            extras(OpenAiPreset.CLAUDE, "claude-haiku-4-5-20251001", ReasoningLevel.ON, haiku),
        )
    }

    @Test
    fun defaultHookSendsNothing() = runBlocking {
        val seen = mutableListOf<String>()
        val backend = backendWith(seen, "gpt-6-luna") { emptyMap() }
        backend.translate("hello", "en", "ja")
        assertFalse(seen[0], seen[0].contains("\"thinking\""))
        assertFalse(seen[0], seen[0].contains("\"reasoning_effort\""))
    }

    /** Mistral with reasoning on answers with a list of chunks rather than
     *  a string: the `text` chunks are the answer, the `thinking` chunk the
     *  reasoning, which must not reach the overlay or the batch parse. */
    @Test
    fun mistralChunkedRepliesReadOnlyTheirTextChunks() = runBlocking {
        val thinking = """{"type":"thinking","thinking":[{"type":"text","text":"Let me think."}]}"""
        val single = """{"choices":[{"message":{"content":[$thinking,{"type":"text","text":"こんにちは"}]}}]}"""
        val batch = """{"choices":[{"message":{"content":[$thinking,""" +
            """{"type":"text","text":"{\"translations\":[\"あ\",\"い\"]}"}]}}]}"""
        val backend = backendWith(mutableListOf(), "mistral-small-2603", singleReply = single, batchReply = batch) {
            OnlineBackendFactory.requestExtrasFor(OpenAiPreset.MISTRAL, it, ReasoningLevel.ON)
        }
        assertEquals("こんにちは", backend.translate("hello", "en", "ja"))
        assertEquals(listOf("あ", "い"), backend.translateBatch(listOf("a", "b"), "en", "ja"))
    }

    /** A plain string still reads as before, and a null content as empty,
     *  which is what the String field read it as. */
    @Test
    fun stringAndNullContentReadAsBefore() = runBlocking {
        val backend = backendWith(mutableListOf(), "gpt-6-luna") { emptyMap() }
        assertEquals("こんにちは", backend.translate("hello", "en", "ja"))
        val nullContent = backendWith(
            mutableListOf(), "gpt-6-luna",
            singleReply = """{"choices":[{"message":{"content":null}}]}""",
        ) { emptyMap() }
        assertEquals("", nullContent.translate("hello", "en", "ja"))
    }

    /** A backend whose client records every request body and answers a
     *  success for either path. */
    private fun backendWith(
        seen: MutableList<String>,
        model: String,
        singleReply: String = SINGLE_OK,
        batchReply: String = BATCH_OK,
        extras: (String) -> Map<String, JsonElement>,
    ): OpenAiBackend = OpenAiBackend(
        id = "extras-test",
        displayName = "Test",
        priority = 15,
        keyProvider = { "sk-test" },
        enabledProvider = { true },
        modelProvider = { model },
        baseUrlProvider = { "https://api.example.com/v1" },
        usageTracker = UsageTracker(FakeSharedPreferences(), "extras-test"),
        applyOwnedByFilter = { false },
        requestExtras = extras,
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            seen += buffer.readUtf8()
            val body = if (seen.last().contains("\"response_format\"")) batchReply else singleReply
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
