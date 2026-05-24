package com.playtranslate.translation.gemma

import com.playtranslate.translation.llm.languageDisplayName
import java.util.Locale

/**
 * Gemma 4 chat-template envelope for the MNN-backed E2B translator.
 *
 * Gemma 4 uses `<|turn>` / `<turn|>` role markers with the role name baked
 * into the opening marker (system / user / model) — distinct from both
 * Gemma 3's `<start_of_turn>{role}` form and Qwen's `<|im_start|>{role}`
 * form. Pulled verbatim from `regist_gemma4()` in the MNN converter's
 * `llmexport.py:108-113` (the converter's hardcoded jinja template):
 *
 *   <bos><|turn>system\n{system}<turn|>\n<|turn>user\n{user}<turn|>\n
 *   <|turn>model\n{assistant}<turn|>\n…<|turn>model\n   (gen prompt)
 *
 * Tokenizer treats `<bos>`, `<|turn>`, and `<turn|>` as special tokens.
 * EOS is `<turn|>`; MNN's stop-token logic picks this up from the shipped
 * `llm_config.json` (`jinja.eos`). Mirrors
 * [com.playtranslate.translation.qwen.QwenChatTemplate] but for Gemma 4's
 * distinct markers; spike confirmed both load + generate cleanly on Thor.
 */
object GemmaE2BChatTemplate {
    /** Plain system content. Caller wraps with `<|turn>system\n…<turn|>` markers. */
    fun systemPrompt(source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return """You are a professional $srcName ($src) to $tgtName ($tgt) translator. Your goal is to accurately convey the meaning and nuances of the original $srcName text while adhering to $tgtName grammar, vocabulary, and cultural sensitivities.

Produce only the $tgtName translation, without any additional explanations or commentary."""
    }

    /** Plain user-turn body. */
    fun userMessage(text: String, source: String, target: String): String {
        val src = source.lowercase(Locale.ROOT)
        val tgt = target.lowercase(Locale.ROOT)
        val srcName = languageDisplayName(src)
        val tgtName = languageDisplayName(tgt)
        return "Please translate the following $srcName text into $tgtName:\n\n$text"
    }

    /**
     * Full system block: `<bos><|turn>system\n{system}<turn|>\n`. `<bos>` is
     * prepended to the first system block only; [com.playtranslate.translation.mnn.MnnTranslator]
     * caches the system block per `(source, target)` pair and reuses it
     * across translations until the pair changes, so including `<bos>` once
     * here is correct. With `use_template:false` in the MNN config, the
     * tokenizer does not auto-prepend a `<bos>` token, so this is the only
     * place the marker appears.
     */
    fun systemBlock(source: String, target: String): String =
        "<bos><|turn>system\n${systemPrompt(source, target)}<turn|>\n"

    /**
     * Full user turn + model role-open: `<|turn>user\n{user}<turn|>\n<|turn>model\n`.
     * The trailing `<|turn>model\n` (no closing `<turn|>`) is the generation
     * prompt — the model continues from there and emits `<turn|>` itself as
     * EOS, where MNN's stop-token logic terminates the response.
     */
    fun userBlock(text: String, source: String, target: String): String =
        "<|turn>user\n${userMessage(text, source, target)}<turn|>\n<|turn>model\n"
}
