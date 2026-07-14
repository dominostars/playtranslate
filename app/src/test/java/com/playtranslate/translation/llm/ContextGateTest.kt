package com.playtranslate.translation.llm

import com.playtranslate.translation.gemma.GemmaE2BChatTemplate
import com.playtranslate.translation.hymt.HyMtChatTemplate
import com.playtranslate.translation.qwen.QwenChatTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `{context}` online-only gate — the invariant that
 * [LlmPromptTemplates.TOKEN_CONTEXT] documents and that nothing else can
 * enforce at runtime.
 *
 * **Why this file exists.** The block is ~68 tokens at the median, it lands in
 * the USER turn, and the user turn is re-prefilled on every call (the MNN
 * translator caches the SYSTEM block only). A server prefills those tokens for
 * free; Thor prefills at ~110 tok/s, so the same block costs ~600 ms on EVERY
 * on-device translation — roughly double Qwen3.5-2B's 502 ms median. A refactor
 * that "unifies" the prose helpers would reintroduce that silently: the output
 * is still a *valid* prompt, the models still answer, and nothing fails except
 * the clock. These tests are the only thing that would notice.
 *
 * Both directions are pinned on purpose. A gate that leaks costs latency; a
 * gate that over-blocks silently kills the feature the user paid for with a
 * settings toggle.
 */
class ContextGateTest {

    private val ctx = "Recent dialogue lines, for context only:\n- 行くぞ → Let's go.\n\n"

    @After
    fun tearDown() {
        LlmPromptTemplates.resetOverrides()
    }

    private fun loadContext() {
        LlmPromptTemplates.contextProvider = { _, _ -> ctx }
    }

    private fun assertNoContext(where: String, prompt: String) {
        assertFalse("$where leaked the context block", prompt.contains("Recent dialogue lines"))
        assertFalse("$where leaked a context pair", prompt.contains("行くぞ"))
        // The load-bearing half: `substitute` passes tokens it has no value for
        // through LITERALLY, and DEFAULT_TRANSLATION opens with {context}. Gating
        // by *omitting* the mapping instead of mapping it to "" would ship these
        // eight characters to the head of every on-device prompt.
        assertFalse("$where shipped a literal {context}", prompt.contains("{context}"))
    }

    // ── On-device: never, even with a fully loaded provider ──────────────

    @Test
    fun `qwen MNN user block carries no context`() {
        loadContext()
        assertNoContext("QwenChatTemplate.userBlock", QwenChatTemplate.userBlock("こんにちは", "ja", "en"))
    }

    @Test
    fun `qwen 3-5 no-think user block carries no context`() {
        loadContext()
        assertNoContext(
            "QwenChatTemplate.userBlockNoThink",
            QwenChatTemplate.userBlockNoThink("こんにちは", "ja", "en"),
        )
    }

    @Test
    fun `gemma E2B user block carries no context`() {
        loadContext()
        assertNoContext(
            "GemmaE2BChatTemplate.userBlock",
            GemmaE2BChatTemplate.userBlock("こんにちは", "ja", "en"),
        )
    }

    @Test
    fun `hymt user block carries no context`() {
        loadContext()
        assertNoContext("HyMtChatTemplate.userBlock", HyMtChatTemplate.userBlock("こんにちは", "ja", "en"))
    }

    @Test
    fun `on-device user turn is byte-identical with and without a context provider`() {
        // The pre-{context} prompt, verbatim. This is also what keeps the
        // MnnTranslator system-block cache and the on-device quality numbers
        // (spike corpus) valid: the on-device prompt did not change at all.
        val cold = QwenChatTemplate.userBlock("こんにちは", "ja", "en")
        loadContext()
        assertEquals(cold, QwenChatTemplate.userBlock("こんにちは", "ja", "en"))
    }

    @Test
    fun `an edited translation template resolves {context} to empty on-device, never to braces`() {
        // The prompt editor advertises {context} as an available keyword on the
        // Translation prompt (PromptKind.TRANSLATION.keywords), and that prompt
        // is shared with the on-device tiers — so a user CAN put the token on a
        // path that must not fill it. It must render empty, not literal.
        LlmPromptTemplates.overrideProvider = { kind ->
            if (kind == PromptKind.TRANSLATION) "{context}Translate {text}." else null
        }
        loadContext()
        assertEquals("<|im_start|>user\nTranslate こんにちは.<|im_end|>\n<|im_start|>assistant\n",
            QwenChatTemplate.userBlock("こんにちは", "ja", "en"))
    }

    // ── Online: the gate is a gate, not an off-switch ────────────────────

    @Test
    fun `online single-text path still carries the context block`() {
        loadContext()
        val out = QwenChatTemplate.userMessage("こんにちは", "ja", "en", includeContext = true)
        assertTrue("online single-text lost its context", out.startsWith(ctx))
        assertTrue(out.endsWith("into English:\n\nこんにちは"))
    }

    @Test
    fun `online batch path still carries the context block`() {
        loadContext()
        val out = LlmBatchPrompt.userMessage(listOf("おはよう"), "ja", "en", includeContext = true)
        assertTrue("online batch lost its context", out.startsWith(ctx))
        assertTrue(out.contains("[\"おはよう\"]"))
    }
}
