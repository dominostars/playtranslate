package com.playtranslate.translation.llm

import com.playtranslate.translation.gemma.GemmaE2BChatTemplate
import com.playtranslate.translation.hymt.HyMtChatTemplate
import com.playtranslate.translation.mnn.MnnTranslator
import com.playtranslate.translation.qwen.QwenChatTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The on-device chat envelopes, apart from the translation prose they
 * usually carry. MnnTranslator runs a free-form prompt (an Ask answer) in
 * the model's envelope with the caller's own prose, and a translation in
 * the same envelope around the translation prose; this pins both halves:
 *  - each envelope, as literal markers around any content (Qwen's ChatML,
 *    Qwen 3.5's empty think block, Gemma 4's turn markers and `<bos>`,
 *    Hunyuan-MT's system turn and user turn);
 *  - each translation block as exactly that envelope around
 *    [LlmPromptTemplates]' translation prose, with `{context}` off (an
 *    on-device prompt never carries it), so the two cannot drift apart;
 *    Hunyuan-MT's translation as `<bos>` and one user turn around the
 *    model card's prompt, with no system turn and no edit taken;
 *  - which envelope a free-form prompt goes in for each model
 *    ([MnnTranslator.answerPrompt]).
 *
 * The block equalities run with a prompt override and a context provider
 * installed: a block assembled on its own, or with the context on, would
 * then differ from envelope(prose). Pure JVM.
 */
class ChatTemplateEnvelopeTest {

    @After fun tearDown() {
        LlmPromptTemplates.resetOverrides()
    }

    private fun installEdits() {
        LlmPromptTemplates.overrideProvider = { kind ->
            when (kind) {
                PromptKind.SYSTEM -> "Be terse. {source} to {target}."
                PromptKind.TRANSLATION -> "{context}Translate from {source}: {text}"
                PromptKind.BATCH -> null
            }
        }
        LlmPromptTemplates.contextProvider = { _, _ -> "Recent lines:\n- 行くぞ → Let's go.\n\n" }
    }

    // ── Envelopes ────────────────────────────────────────────────────────

    @Test fun `qwen system envelope`() {
        assertEquals("<|im_start|>system\nS<|im_end|>\n", QwenChatTemplate.systemEnvelope("S"))
    }

    @Test fun `qwen user envelope opens the assistant turn`() {
        assertEquals(
            "<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n",
            QwenChatTemplate.userEnvelope("U"),
        )
    }

    @Test fun `qwen 3_5 no-think user envelope is the user envelope plus the empty think block`() {
        assertEquals(
            QwenChatTemplate.userEnvelope("U") + "<think>\n\n</think>\n\n",
            QwenChatTemplate.userEnvelopeNoThink("U"),
        )
    }

    @Test fun `gemma system envelope carries the bos marker`() {
        assertEquals("<bos><|turn>system\nS<turn|>\n", GemmaE2BChatTemplate.systemEnvelope("S"))
    }

    @Test fun `gemma user envelope opens the model turn`() {
        assertEquals("<|turn>user\nU<turn|>\n<|turn>model\n", GemmaE2BChatTemplate.userEnvelope("U"))
    }

    @Test fun `hunyuan system envelope carries the bos marker and the system turn's close`() {
        assertEquals(
            "<｜hy_begin▁of▁sentence｜>S<｜hy_place▁holder▁no▁3｜>",
            HyMtChatTemplate.systemEnvelope("S"),
        )
    }

    @Test fun `hunyuan user envelope opens the assistant turn`() {
        assertEquals("<｜hy_User｜>U<｜hy_Assistant｜>", HyMtChatTemplate.userEnvelope("U"))
    }

    @Test fun `an envelope carries its content verbatim`() {
        // A free-form prompt is the user's words: tokens and markup in it
        // are content, not template.
        val content = "What is {text} in {source}? \$1 \\n <b>"
        assertEquals("<|im_start|>system\n$content<|im_end|>\n", QwenChatTemplate.systemEnvelope(content))
        assertEquals("<|turn>user\n$content<turn|>\n<|turn>model\n", GemmaE2BChatTemplate.userEnvelope(content))
        assertEquals("<｜hy_User｜>$content<｜hy_Assistant｜>", HyMtChatTemplate.userEnvelope(content))
    }

    // ── A free-form prompt's envelope, model by model ────────────────────

    @Test fun `a free-form prompt goes in each model's own system turn and user turn`() {
        fun blocks(style: PromptStyle) =
            MnnTranslator.answerPrompt(style, "S", "U").let { it.systemBlock to it.userBlock }

        assertEquals(
            "<|im_start|>system\nS<|im_end|>\n" to "<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n",
            blocks(PromptStyle.StandardChat),
        )
        assertEquals(
            "<|im_start|>system\nS<|im_end|>\n" to
                "<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
            blocks(PromptStyle.Qwen35Chat),
        )
        assertEquals(
            "<bos><|turn>system\nS<turn|>\n" to "<|turn>user\nU<turn|>\n<|turn>model\n",
            blocks(PromptStyle.Gemma4Chat),
        )
        assertEquals(
            "<｜hy_begin▁of▁sentence｜>S<｜hy_place▁holder▁no▁3｜>" to "<｜hy_User｜>U<｜hy_Assistant｜>",
            blocks(PromptStyle.HyMtChat),
        )
    }

    // ── Translation blocks = envelope(translation prose) ─────────────────

    @Test fun `qwen translation blocks are the envelopes around the translation prose`() {
        for (edited in listOf(false, true)) {
            if (edited) installEdits()
            assertEquals(
                "edited=$edited",
                QwenChatTemplate.systemEnvelope(QwenChatTemplate.systemPrompt("ja", "en")),
                QwenChatTemplate.systemBlock("ja", "en"),
            )
            assertEquals(
                "edited=$edited",
                QwenChatTemplate.userEnvelope(QwenChatTemplate.userMessage("猫", "ja", "en", includeContext = false)),
                QwenChatTemplate.userBlock("猫", "ja", "en"),
            )
            assertEquals(
                "edited=$edited",
                QwenChatTemplate.userEnvelopeNoThink(QwenChatTemplate.userMessage("猫", "ja", "en", includeContext = false)),
                QwenChatTemplate.userBlockNoThink("猫", "ja", "en"),
            )
        }
    }

    @Test fun `gemma translation blocks are the envelopes around the translation prose`() {
        for (edited in listOf(false, true)) {
            if (edited) installEdits()
            assertEquals(
                "edited=$edited",
                GemmaE2BChatTemplate.systemEnvelope(GemmaE2BChatTemplate.systemPrompt("ja", "en")),
                GemmaE2BChatTemplate.systemBlock("ja", "en"),
            )
            assertEquals(
                "edited=$edited",
                GemmaE2BChatTemplate.userEnvelope(GemmaE2BChatTemplate.userMessage("猫", "ja", "en", includeContext = false)),
                GemmaE2BChatTemplate.userBlock("猫", "ja", "en"),
            )
        }
    }

    @Test fun `a hunyuan translation is bos and one user turn around the card's prompt, edits or not`() {
        val prompt = "Translate the following text into English. Note that you should only output " +
            "the translated result without any additional explanation:\n\n猫"
        for (edited in listOf(false, true)) {
            if (edited) installEdits()
            assertEquals(
                "edited=$edited",
                "<｜hy_begin▁of▁sentence｜>" + HyMtChatTemplate.userEnvelope(prompt),
                HyMtChatTemplate.systemBlock("ja", "en") + HyMtChatTemplate.userBlock("猫", "ja", "en"),
            )
        }
        // The literal form, and where the cached prefix ends.
        assertEquals(
            "<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate the following text into English. Note that " +
                "you should only output the translated result without any additional explanation:\n\n",
            HyMtChatTemplate.systemBlock("ja", "en"),
        )
        assertEquals("猫<｜hy_Assistant｜>", HyMtChatTemplate.userBlock("猫", "ja", "en"))
    }

    @Test fun `an edited translation block is the edit in the envelope, with no context`() {
        // The literal form of the equalities above, for one edited pair.
        installEdits()
        assertEquals(
            "<|im_start|>system\nBe terse. Japanese to English.<|im_end|>\n",
            QwenChatTemplate.systemBlock("ja", "en"),
        )
        assertEquals(
            "<|turn>user\nTranslate from Japanese: 猫<turn|>\n<|turn>model\n",
            GemmaE2BChatTemplate.userBlock("猫", "ja", "en"),
        )
    }
}
