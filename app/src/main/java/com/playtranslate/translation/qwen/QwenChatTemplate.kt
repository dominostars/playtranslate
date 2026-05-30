package com.playtranslate.translation.qwen

import com.playtranslate.translation.llm.languageDisplayName
import java.util.Locale

/**
 * The Qwen 2.5 chat-template envelope, fed to the MNN-backed
 * [com.playtranslate.translation.mnn.MnnTranslator].
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
     * native side wraps if needed).
     */
    fun systemPrompt(source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return """You are a professional $srcName ($src) to $tgtName ($tgt) translator. Your goal is to accurately convey the meaning and nuances of the original $srcName text while adhering to $tgtName grammar, vocabulary, and cultural sensitivities.

Produce only the $tgtName translation, without any additional explanations or commentary."""
    }

    /** The user-turn body. Wrapped by the engine's `<|im_start|>user\n…<|im_end|>` markers. */
    fun userMessage(text: String, source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return "Please translate the following $srcName text into $tgtName:\n\n$text"
    }

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
     */
    fun userBlock(text: String, source: String, target: String): String =
        "<|im_start|>user\n${userMessage(text, source, target)}<|im_end|>\n<|im_start|>assistant\n"

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
