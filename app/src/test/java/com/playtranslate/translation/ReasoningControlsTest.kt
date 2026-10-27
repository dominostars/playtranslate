package com.playtranslate.translation

import com.playtranslate.translation.ReasoningLevel.HIGH
import com.playtranslate.translation.ReasoningLevel.LOW
import com.playtranslate.translation.ReasoningLevel.MEDIUM
import com.playtranslate.translation.ReasoningLevel.MINIMAL
import com.playtranslate.translation.ReasoningLevel.OFF
import com.playtranslate.translation.ReasoningLevel.ON
import com.playtranslate.translation.ReasoningLevel.UNSET
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows that come from the docs, for the providers that publish no
 * thinking options (OpenAI, Groq, Gemini, Mistral), by exact model id: an id
 * not listed, even one that starts like a listed id, gets no row and sends
 * nothing, since a guessed level the model rejects is a 400 on every request
 * (Groq's Qwen 3 models other than 3.8 take only `none` and `default`). Every
 * row starts at its lowest choice. The rows that come from a provider's
 * report are [ReportedThinkingTest]'s.
 */
class ReasoningControlsTest {

    private val lowToHigh = listOf(LOW, MEDIUM, HIGH)
    private val switch = listOf(OFF, ON)

    private fun openAi(preset: OpenAiPreset, model: String) = ReasoningControls.of(ServiceType.OPENAI, preset, model)
    private fun gemini(model: String) = ReasoningControls.of(ServiceType.GEMINI, OpenAiPreset.OPENAI, model)
    private fun json(control: ReasoningControl, pick: ReasoningLevel) = JsonObject(control.fields(pick)).toString()

    private fun assertNoRow(name: String, control: ReasoningControl, sends: String = "{}") {
        assertTrue(name, control.choices.isEmpty())
        ReasoningLevel.entries.forEach { assertEquals("$name $it", sends, json(control, it)) }
    }

    /** [choices] in order, starting at the first, each sent as [wire] shows
     *  its API name. */
    private fun assertLevels(
        name: String,
        control: ReasoningControl,
        choices: List<ReasoningLevel>,
        wire: (String) -> String,
    ) {
        assertEquals(name, choices, control.choices)
        assertFalse(name, control.isSwitch)
        assertEquals("$name starts at its lowest", choices.first(), control.resolve(UNSET))
        val names = mapOf(OFF to "none", MINIMAL to "minimal", LOW to "low", MEDIUM to "medium", HIGH to "high")
        choices.forEach { assertEquals("$name $it", wire(names.getValue(it)), json(control, it)) }
        assertEquals(name, json(control, choices.first()), json(control, UNSET))
    }

    private fun assertSwitch(name: String, control: ReasoningControl, off: String, on: String) {
        assertEquals(name, switch, control.choices)
        assertTrue(name, control.isSwitch)
        assertEquals("$name starts off", OFF, control.resolve(UNSET))
        assertEquals(name, off, json(control, OFF))
        assertEquals(name, on, json(control, ON))
    }

    // ── OpenAI ───────────────────────────────────────────────────────────

    /** The documented reasoning models, each with low, medium and high
     *  (some reject `none`), and their dated snapshots. */
    @Test fun `OpenAI's documented reasoning models get Low to High, starting Low`() {
        for (model in listOf(
            "gpt-6-luna", "gpt-6-sol", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-5.5",
            "gpt-5", "gpt-5-mini", "gpt-5-nano", "o3", "o3-mini", "o4-mini", "o1",
            "gpt-5-2025-08-07", "gpt-5-mini-2025-08-07",
        )) {
            assertLevels(model, openAi(OpenAiPreset.OPENAI, model), lowToHigh) { """{"reasoning_effort":"$it"}""" }
        }
    }

    /** Instant and older models don't reason, pro models take only `high`,
     *  and an id that only starts like a listed one is not that model. */
    @Test fun `any other OpenAI id gets no row`() {
        for (model in listOf(
            "chat-latest", "gpt-5-chat-latest", "gpt-4.1", "gpt-4o", "gpt-5.5-pro", "gpt-5-pro",
            "gpt-6-luna-mini", "gpt-6.2-sol", "gpt-7", "o1-mini",
        )) {
            assertNoRow(model, openAi(OpenAiPreset.OPENAI, model))
        }
    }

    // ── Groq ─────────────────────────────────────────────────────────────

    @Test fun `Groq's documented reasoning models get their levels, Qwen 3_8 starting Off`() {
        for (model in listOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "meta-models/muse-spark-open")) {
            assertLevels(model, openAi(OpenAiPreset.GROQ, model), lowToHigh) { """{"reasoning_effort":"$it"}""" }
        }
        assertLevels(
            "qwen 3.8", openAi(OpenAiPreset.GROQ, "qwen/qwen3.8-27b"), listOf(OFF, LOW, MEDIUM, HIGH),
        ) { """{"reasoning_effort":"$it"}""" }
    }

    /** Groq's other Qwen 3 models take only `none` and `default`: a level
     *  there is a 400 on every request (the Codex finding). */
    @Test fun `Groq's other Qwen and undocumented models get no row`() {
        for (model in listOf(
            "qwen/qwen3.6-27b", "qwen/qwen3-32b", "openai/gpt-oss-safeguard-20b", "minimaxai/minimax-m2.7",
            "llama-3.3-70b-versatile",
        )) {
            assertNoRow(model, openAi(OpenAiPreset.GROQ, model))
        }
    }

    // ── Gemini ───────────────────────────────────────────────────────────

    /** Gemini's lowest level is named minimal on the models that have it,
     *  and that is where they start. */
    @Test fun `Gemini models that name a minimal level get Minimal to High, starting Minimal`() {
        for (model in listOf(
            "gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash",
            "gemini-3-flash-preview",
        )) {
            assertLevels(
                model, gemini(model), listOf(MINIMAL, LOW, MEDIUM, HIGH),
            ) { """{"thinkingConfig":{"thinkingLevel":"$it"}}""" }
        }
    }

    /** minimal is an error on 3.8 Flash, 3.1 Pro and 4 Argon. */
    @Test fun `other documented Gemini 3 and 4 models and their aliases get Low to High, starting Low`() {
        for (model in listOf(
            "gemini-flash-latest", "gemini-pro-latest", "gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-4-argon",
        )) {
            assertLevels(model, gemini(model), lowToHigh) { """{"thinkingConfig":{"thinkingLevel":"$it"}}""" }
        }
    }

    @Test fun `Gemini 2_5 Flash and Flash-Lite get a budget switch, starting off`() {
        for (model in listOf("gemini-2.5-flash-lite", "gemini-2.5-flash")) {
            assertSwitch(
                model, gemini(model),
                off = """{"thinkingConfig":{"thinkingBudget":0}}""", on = """{"thinkingConfig":{"thinkingBudget":-1}}""",
            )
        }
    }

    /** Image models take only minimal and high, 2.5 Pro takes only budgets,
     *  Gemma doesn't think, and a model not documented here, or a variant of
     *  a documented id, gets nothing. */
    @Test fun `any other Gemini id gets no row`() {
        for (model in listOf(
            "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image", "gemini-2.5-pro", "gemma-3-27b-it",
            "gemini-2.0-flash", "gemini-3.9-flash", "gemini-3.5-flash-lite-preview-11-2026", "gemini-2.5-flash-preview-09-2025",
        )) {
            assertNoRow(model, gemini(model))
        }
    }

    // ── Mistral, Index-Translate, Custom, DeepL, Lingva ──────────────────

    @Test fun `Mistral's reasoning models get a switch on reasoning_effort, the rest no row`() {
        for (model in listOf("mistral-small-2603", "mistral-small-latest", "mistral-medium-3-5", "mistral-large-4-0")) {
            assertSwitch(
                model, openAi(OpenAiPreset.MISTRAL, model),
                off = """{"reasoning_effort":"none"}""", on = """{"reasoning_effort":"high"}""",
            )
        }
        for (model in listOf("codestral-2508", "mistral-small-2506", "zai-glm-5-3", "ministral-8b-latest")) {
            assertNoRow(model, openAi(OpenAiPreset.MISTRAL, model))
        }
    }

    @Test fun `Index-Translate keeps thinking off with no row, Custom and the MT services get nothing`() {
        assertNoRow(
            "index", openAi(OpenAiPreset.INDEX_TRANSLATE, "Index-Translate-35B-A3B"),
            sends = """{"chat_template_kwargs":{"enable_thinking":false}}""",
        )
        assertNoRow("custom", openAi(OpenAiPreset.CUSTOM, "gpt-6-luna"))
        assertNoRow("deepl", ReasoningControls.of(ServiceType.DEEPL, OpenAiPreset.OPENAI, ""))
        assertNoRow("lingva", ReasoningControls.of(ServiceType.LINGVA, OpenAiPreset.OPENAI, ""))
    }

    // ── Over the whole table ─────────────────────────────────────────────

    /** Every case the tests above name, each one in its own service, plus
     *  the reported presets with no report yet. */
    private val everyCase: List<Pair<String, ReasoningControl>> by lazy {
        val openAiType = listOf(
            OpenAiPreset.OPENAI to listOf("gpt-6-luna", "o3", "chat-latest", "gpt-5.5-pro"),
            OpenAiPreset.GROQ to listOf("openai/gpt-oss-20b", "qwen/qwen3.8-27b", "qwen/qwen3.6-27b"),
            OpenAiPreset.OPENROUTER to listOf("google/gemini-2.5-flash-lite"),
            OpenAiPreset.DEEPSEEK to listOf("deepseek-flash"),
            OpenAiPreset.CLAUDE to listOf("claude-sonnet-5", "claude-sonnet-5-5", "claude-opus-5-5"),
            OpenAiPreset.MISTRAL to listOf("mistral-small-2603", "codestral-2508"),
            OpenAiPreset.INDEX_TRANSLATE to listOf("Index-Translate-35B-A3B"),
            OpenAiPreset.CUSTOM to listOf("gpt-6-luna"),
        )
        assertEquals("every preset is covered", OpenAiPreset.entries.toSet(), openAiType.map { it.first }.toSet())
        openAiType.flatMap { (preset, models) -> models.map { "$preset $it" to openAi(preset, it) } } +
            listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash", "gemini-2.5-pro")
                .map { "GEMINI $it" to gemini(it) }
    }

    /** No offered pick is a lie: each sends a request no other pick sends.
     *  A model with no row sends one request whatever is stored. */
    @Test fun `every offered pick changes the request, and no row means one request`() {
        everyCase.forEach { (name, control) ->
            val offered = control.choices.map { json(control, it) }
            assertEquals("$name: offered picks send distinct requests", offered.size, offered.toSet().size)
            if (control.choices.isEmpty()) {
                assertEquals(name, 1, ReasoningLevel.entries.map { json(control, it) }.toSet().size)
            }
        }
    }

    /** A pick the control doesn't offer, and no pick at all, read as the
     *  start state, so the page and the request show and send the same. */
    @Test fun `a pick the control doesn't offer reads as the start state`() {
        everyCase.forEach { (name, control) ->
            val start = control.resolve(UNSET)
            ReasoningLevel.entries.filter { it !in control.choices }.forEach { pick ->
                assertEquals("$name $pick", start, control.resolve(pick))
                assertEquals("$name $pick", json(control, start), json(control, pick))
            }
        }
        assertEquals(LOW, openAi(OpenAiPreset.OPENAI, "gpt-6-luna").resolve(ON))
        assertEquals(LOW, openAi(OpenAiPreset.OPENAI, "gpt-6-luna").resolve(MINIMAL))
    }

    /** Every row offers only levels the API names, starts at its lowest
     *  (its first choice), offers at most four, and never offers the unset
     *  marker or, outside a switch, ON: the page has no segment label for
     *  either. */
    @Test fun `rows start at their first choice and offer neither UNSET nor, outside a switch, ON`() {
        everyCase.forEach { (name, control) ->
            assertFalse(name, UNSET in control.choices)
            assertTrue(name, control.choices.size <= 4)
            if (!control.isSwitch) assertFalse(name, ON in control.choices)
            if (control.choices.isNotEmpty()) assertEquals(name, control.choices.first(), control.resolve(UNSET))
        }
    }
}
