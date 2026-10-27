package com.playtranslate.translation

import com.playtranslate.PtJson
import com.playtranslate.translation.ReasoningLevel.HIGH
import com.playtranslate.translation.ReasoningLevel.LOW
import com.playtranslate.translation.ReasoningLevel.MEDIUM
import com.playtranslate.translation.ReasoningLevel.MINIMAL
import com.playtranslate.translation.ReasoningLevel.OFF
import com.playtranslate.translation.ReasoningLevel.ON
import com.playtranslate.translation.ReasoningLevel.UNSET
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows DeepSeek, OpenRouter and Claude get from what their catalogs
 * report, under the one policy: the levels the model names up to High, Off
 * where it can stop thinking with Minimal left out beside it, a switch for
 * off and on alone, no row below two choices, starting at the lowest. The
 * facts are the documented shapes (api-docs.deepseek.com/api/list-models,
 * openrouter.ai's reasoning-tokens guide, platform.claude.com's models list).
 * A report counts only for the model it is about; with none, the request
 * sends what it did before the row existed.
 */
class ReportedThinkingTest {

    private fun facts(json: String): JsonObject = PtJson.lenient.parseToJsonElement(json).jsonObject

    private fun control(preset: OpenAiPreset, model: String, json: String?) = ReasoningControls.of(
        ServiceType.OPENAI, preset, model, json?.let { ReportedThinking(model, facts(it), fetchedAtMs = 0) },
    )

    private fun json(control: ReasoningControl, pick: ReasoningLevel) = JsonObject(control.fields(pick)).toString()

    private fun assertNoRow(name: String, control: ReasoningControl, sends: String) {
        assertTrue(name, control.choices.isEmpty())
        ReasoningLevel.entries.forEach { assertEquals("$name $it", sends, json(control, it)) }
    }

    // ── DeepSeek ─────────────────────────────────────────────────────────

    private val deepSeekOff = """{"thinking":{"type":"disabled"}}"""
    private fun deepSeekLevel(effort: String) = """{"thinking":{"type":"enabled"},"reasoning_effort":"$effort"}"""

    @Test fun `DeepSeek offers Off and the levels it reports up to High, starting Off`() {
        val c = control(
            OpenAiPreset.DEEPSEEK, "deepseek-flash",
            """{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""",
        )
        assertEquals(listOf(OFF, LOW, HIGH), c.choices)
        assertEquals(OFF, c.resolve(UNSET))
        assertEquals(deepSeekOff, json(c, OFF))
        assertEquals(deepSeekLevel("low"), json(c, LOW))
        assertEquals(deepSeekLevel("high"), json(c, HIGH))
    }

    /** A level DeepSeek adds reaches the row without an app update. */
    @Test fun `a level DeepSeek starts reporting shows up`() {
        val c = control(OpenAiPreset.DEEPSEEK, "deepseek-flash", """{"effort":{"supported_levels":["low","medium","high"]}}""")
        assertEquals(listOf(OFF, LOW, MEDIUM, HIGH), c.choices)
        assertEquals(deepSeekLevel("medium"), json(c, MEDIUM))
    }

    @Test fun `DeepSeek with only levels above High is an off-on switch`() {
        val c = control(OpenAiPreset.DEEPSEEK, "deepseek-flash", """{"effort":{"supported_levels":["max"]}}""")
        assertTrue(c.isSwitch)
        assertEquals(OFF, c.resolve(UNSET))
        assertEquals(deepSeekOff, json(c, OFF))
        assertEquals("""{"thinking":{"type":"enabled"}}""", json(c, ON))
    }

    /** A model reported without `effort`, or no report yet: no row, and
     *  thinking still off on every request (issue #41). */
    @Test fun `DeepSeek without reported levels keeps thinking off with no row`() {
        assertNoRow("no effort", control(OpenAiPreset.DEEPSEEK, "deepseek-flash", "{}"), deepSeekOff)
        assertNoRow("no report", control(OpenAiPreset.DEEPSEEK, "deepseek-flash", null), deepSeekOff)
    }

    // ── OpenRouter ───────────────────────────────────────────────────────

    private fun effort(e: String) = """{"reasoning":{"effort":"$e"}}"""

    /** OpenRouter lists efforts highest first; the row reads them lowest
     *  first, and leaves Minimal out beside Off. */
    @Test fun `OpenRouter offers Off and the reported efforts up to High`() {
        val c = control(
            OpenAiPreset.OPENROUTER, "google/gemini-2.5-flash-lite",
            """{"reasoning":{"supported_efforts":["max","xhigh","high","medium","low","minimal"],"mandatory":false}}""",
        )
        assertEquals(listOf(OFF, LOW, MEDIUM, HIGH), c.choices)
        assertEquals(OFF, c.resolve(UNSET))
        assertEquals(effort("none"), json(c, OFF))
        assertEquals(effort("low"), json(c, LOW))
    }

    /** A model that must reason rejects `none`: no Off, and its lowest
     *  named level leads, Minimal included. */
    @Test fun `a mandatory OpenRouter model gets no Off`() {
        val c = control(
            OpenAiPreset.OPENROUTER, "openai/o3",
            """{"reasoning":{"supported_efforts":["high","medium","low","minimal"],"mandatory":true}}""",
        )
        assertEquals(listOf(MINIMAL, LOW, MEDIUM, HIGH), c.choices)
        assertEquals(MINIMAL, c.resolve(UNSET))
        assertEquals(effort("minimal"), json(c, MINIMAL))
        assertEquals(effort("minimal"), json(c, OFF))
    }

    /** `supported_efforts: null` means every effort is taken. */
    @Test fun `OpenRouter null efforts means every level`() {
        val c = control(OpenAiPreset.OPENROUTER, "x/y", """{"reasoning":{"supported_efforts":null}}""")
        assertEquals(listOf(OFF, LOW, MEDIUM, HIGH), c.choices)
    }

    /** Efforts not exposed: on and off only, through `enabled`; a model
     *  that must reason then has nothing to choose. */
    @Test fun `OpenRouter without efforts is a switch on enabled, or nothing when mandatory`() {
        val c = control(OpenAiPreset.OPENROUTER, "x/y", """{"reasoning":{"mandatory":false}}""")
        assertTrue(c.isSwitch)
        assertEquals("""{"reasoning":{"enabled":false}}""", json(c, OFF))
        assertEquals("""{"reasoning":{"enabled":true}}""", json(c, ON))
        assertNoRow("mandatory", control(OpenAiPreset.OPENROUTER, "x/y", """{"reasoning":{"mandatory":true}}"""), "{}")
    }

    /** Efforts only above High: off through `none`, on through `enabled`. */
    @Test fun `OpenRouter with only efforts above High is a switch`() {
        val c = control(OpenAiPreset.OPENROUTER, "x/y", """{"reasoning":{"supported_efforts":["max","xhigh"]}}""")
        assertTrue(c.isSwitch)
        assertEquals(effort("none"), json(c, OFF))
        assertEquals("""{"reasoning":{"enabled":true}}""", json(c, ON))
    }

    /** A model reported without `reasoning` doesn't reason; no report yet
     *  sends nothing either. */
    @Test fun `OpenRouter without reasoning, or unreported, sends nothing`() {
        assertNoRow("no reasoning", control(OpenAiPreset.OPENROUTER, "meta-llama/llama-3.3-70b-instruct", "{}"), "{}")
        assertNoRow("no report", control(OpenAiPreset.OPENROUTER, "google/gemini-2.5-flash-lite", null), "{}")
    }

    // ── Claude ───────────────────────────────────────────────────────────

    private fun claude(model: String, adaptive: Boolean, disabled: Boolean, betweenTools: Boolean, enabled: Boolean) =
        control(
            OpenAiPreset.CLAUDE, model,
            """{"thinking":{"supported":true,"types":{"adaptive":{"supported":$adaptive},""" +
                """"disabled":{"supported":$disabled},"between_tools":{"supported":$betweenTools},""" +
                """"enabled":{"supported":$enabled}}}}""",
        )

    private val claudeDisabled = """{"thinking":{"type":"disabled"}}"""
    private val claudeAdaptive = """{"thinking":{"type":"adaptive"}}"""

    @Test fun `a Claude model that turns off through disabled gets a switch to adaptive`() {
        val c = claude("claude-sonnet-5", adaptive = true, disabled = true, betweenTools = true, enabled = false)
        assertTrue(c.isSwitch)
        assertEquals(OFF, c.resolve(UNSET))
        assertEquals(claudeDisabled, json(c, OFF))
        assertEquals(claudeAdaptive, json(c, ON))
    }

    @Test fun `a Claude model that rejects disabled turns off through between_tools`() {
        val c = claude("claude-sonnet-5-5", adaptive = true, disabled = false, betweenTools = true, enabled = false)
        assertEquals("""{"thinking":{"type":"between_tools"}}""", json(c, OFF))
        assertEquals(claudeAdaptive, json(c, ON))
    }

    /** No off form: the model always thinks, so nothing is chosen and
     *  nothing is sent (an explicit off would be a 400). */
    @Test fun `a Claude model that can't stop thinking gets no row`() {
        assertNoRow("opus 5.5", claude("claude-opus-5-5", adaptive = true, disabled = false, betweenTools = false, enabled = false), "{}")
    }

    @Test fun `a Claude model that thinks only on a budget turns on with one below max_tokens`() {
        val c = claude("claude-haiku-4-5-20251001", adaptive = false, disabled = true, betweenTools = false, enabled = true)
        assertEquals(claudeDisabled, json(c, OFF))
        assertEquals(
            """{"thinking":{"type":"enabled","budget_tokens":1024},"max_tokens":8192}""", json(c, ON),
        )
    }

    @Test fun `a Claude model that doesn't think gets no row`() {
        assertNoRow(
            "no thinking",
            control(OpenAiPreset.CLAUDE, "claude-x", """{"thinking":{"supported":false,"types":{"disabled":{"supported":true}}}}"""),
            "{}",
        )
    }

    /** Before a report, or one that doesn't describe thinking: no row, and
     *  the off each known model took before the row existed. */
    @Test fun `Claude unreported sends the known off with no row`() {
        assertNoRow("sonnet 5", control(OpenAiPreset.CLAUDE, "claude-sonnet-5", null), claudeDisabled)
        assertNoRow("haiku 4.5", control(OpenAiPreset.CLAUDE, "claude-haiku-4-5-20251001", null), claudeDisabled)
        assertNoRow(
            "sonnet 5.5", control(OpenAiPreset.CLAUDE, "claude-sonnet-5-5", null), """{"thinking":{"type":"between_tools"}}""",
        )
        assertNoRow("opus 5.5", control(OpenAiPreset.CLAUDE, "claude-opus-5-5", null), "{}")
        assertNoRow("fable", control(OpenAiPreset.CLAUDE, "claude-fable-5-1", null), "{}")
        assertNoRow("unknown", control(OpenAiPreset.CLAUDE, "claude-sonnet-6", null), "{}")
        assertNoRow("no thinking key", control(OpenAiPreset.CLAUDE, "claude-sonnet-5", "{}"), claudeDisabled)
    }

    // ── The binding and the policy over every case ───────────────────────

    /** A report about another model counts for nothing: the instance moved
     *  on in the picker, and the old report waits in case it moves back. */
    @Test fun `a report about another model is ignored`() {
        val aboutAnother = ReportedThinking(
            "deepseek-v4-pro", facts("""{"effort":{"supported_levels":["low","high"]}}"""), fetchedAtMs = 0,
        )
        val c = ReasoningControls.of(ServiceType.OPENAI, OpenAiPreset.DEEPSEEK, "deepseek-flash", aboutAnother)
        assertTrue(c.choices.isEmpty())
        assertEquals(deepSeekOff, json(c, LOW))
        val same = ReasoningControls.of(ServiceType.OPENAI, OpenAiPreset.DEEPSEEK, "deepseek-v4-pro", aboutAnother)
        assertEquals(listOf(OFF, LOW, HIGH), same.choices)
    }

    private val everyCase: List<Pair<String, ReasoningControl>> by lazy {
        listOf(
            "ds" to control(OpenAiPreset.DEEPSEEK, "m", """{"effort":{"supported_levels":["low","high","max"]}}"""),
            "ds max" to control(OpenAiPreset.DEEPSEEK, "m", """{"effort":{"supported_levels":["max"]}}"""),
            "ds none" to control(OpenAiPreset.DEEPSEEK, "m", "{}"),
            "or" to control(OpenAiPreset.OPENROUTER, "m", """{"reasoning":{"supported_efforts":null}}"""),
            "or mandatory" to control(OpenAiPreset.OPENROUTER, "m", """{"reasoning":{"supported_efforts":["high","low","minimal"],"mandatory":true}}"""),
            "or enabled" to control(OpenAiPreset.OPENROUTER, "m", """{"reasoning":{}}"""),
            "or none" to control(OpenAiPreset.OPENROUTER, "m", "{}"),
            "claude" to claude("m", adaptive = true, disabled = true, betweenTools = false, enabled = false),
            "claude budget" to claude("m", adaptive = false, disabled = true, betweenTools = false, enabled = true),
            "claude always" to claude("m", adaptive = true, disabled = false, betweenTools = false, enabled = false),
        )
    }

    /** No offered pick is a lie, a row starts at its first choice, offers
     *  at most four, never UNSET, and ON only as a switch; no row means one
     *  request whatever is stored. */
    @Test fun `the policy holds over every reported row`() {
        everyCase.forEach { (name, c) ->
            val offered = c.choices.map { json(c, it) }
            assertEquals("$name: distinct requests", offered.size, offered.toSet().size)
            assertTrue("$name: at most four", c.choices.size <= 4)
            assertFalse(name, UNSET in c.choices)
            if (!c.isSwitch) assertFalse(name, ON in c.choices)
            if (c.choices.isNotEmpty()) assertEquals(name, c.choices.first(), c.resolve(UNSET))
            else assertEquals(name, 1, ReasoningLevel.entries.map { json(c, it) }.toSet().size)
        }
    }
}
