package com.playtranslate.translation.qwen

import com.playtranslate.translation.llm.LlmPromptTemplates

/**
 * The Qwen 2.5 chat-template envelope, fed to the MNN-backed
 * [com.playtranslate.translation.mnn.MnnTranslator].
 *
 * The prompt *content* (system prose + user-turn prose) lives in
 * [LlmPromptTemplates] — shared with the Gemma template and the cloud
 * backends, and user-editable via the Advanced LLM Configuration
 * settings. This object owns only the Qwen envelope.
 *
 * The MNN side enables `use_template:false` and feeds the raw text from
 * these helpers because the taobao-mnn Qwen ships without a jinja
 * `chat_template` and MNN's `apply_chat_template` fallback strips role
 * markers (spike-confirmed; see mnn-spike/SPIKE_REPORT.md). The envelope:
 * `<|im_start|>system\n…<|im_end|>\n<|im_start|>user\n…<|im_end|>\n<|im_start|>assistant\n`.
 */
object QwenChatTemplate {
    /**
     * The system-role content. Caller is responsible for wrapping it in the
     * `<|im_start|>system\n…<|im_end|>` markers (llama.cpp's apply_chat_template
     * does this; MNN's `processSystemPrompt` JNI feeds the raw text and the
     * native side wraps if needed). Also called directly by the cloud
     * backends' single-text path, where the API's system role is the envelope.
     */
    fun systemPrompt(source: String, target: String): String =
        LlmPromptTemplates.systemPrompt(source, target)

    /**
     * The user-turn body. Wrapped by the engine's `<|im_start|>user\n…<|im_end|>`
     * markers on the MNN path, or sent as the API's user role by the cloud
     * backends.
     *
     * This object is the one prose helper with consumers on BOTH sides of the
     * `{context}` line — the cloud backends call this directly (online: pass
     * `includeContext = true`), while [userBlock] reaches it for MNN (on-device:
     * always false). Hence the un-defaulted parameter; see
     * [LlmPromptTemplates.TOKEN_CONTEXT] for why on-device must not pay it.
     */
    fun userMessage(text: String, source: String, target: String, includeContext: Boolean): String =
        LlmPromptTemplates.translationUserMessage(text, source, target, includeContext)

    /**
     * The system prompt with the full `<|im_start|>system\n…<|im_end|>\n`
     * envelope. Use this on the MNN path where the engine runs with
     * `use_template: false` (the taobao-mnn Qwen ships no jinja
     * `chat_template` and MNN's fallback strips role markers); we hand the
     * engine the raw token stream the model expects.
     *
     * On the llama.cpp path, [systemPrompt] (no envelope) is the right call —
     * llama.cpp's `apply_chat_template` wraps Qwen's role markers automatically
     * from the GGUF's embedded jinja template.
     */
    fun systemBlock(source: String, target: String): String =
        "<|im_start|>system\n${systemPrompt(source, target)}<|im_end|>\n"

    /**
     * The user turn with the full envelope plus the assistant role-open marker
     * so the model knows to start generating the response. Matches the spike's
     * `build_suffix` exactly (`mnn-spike/MNN/.../demo/llm_demo.cpp:125`).
     *
     * MNN-only (the envelope markers are what `use_template:false` expects), so
     * `includeContext = false` is hardcoded, not passed: every on-device caller
     * of this is by definition on the wrong side of the prefill budget. The
     * on-device user turn is therefore byte-identical to its pre-{context} form.
     */
    fun userBlock(text: String, source: String, target: String): String =
        "<|im_start|>user\n${userMessage(text, source, target, includeContext = false)}" +
            "<|im_end|>\n<|im_start|>assistant\n"

    /**
     * Qwen 3.5 user turn — identical to [userBlock] but opens the assistant turn
     * with the non-thinking marker `<think>\n\n</think>\n\n`. Qwen 3.5 is a
     * hybrid reasoning model; its own chat template emits this empty think-block
     * after `<|im_start|>assistant\n` whenever `enable_thinking` is not true, so
     * the model skips the `<think>` monologue and produces the translation
     * directly. With `use_template:false` on the MNN side we must bake it in
     * ourselves. Used by [com.playtranslate.translation.llm.PromptStyle.Qwen35Chat].
     */
    fun userBlockNoThink(text: String, source: String, target: String): String =
        userBlock(text, source, target) + "<think>\n\n</think>\n\n"
}
