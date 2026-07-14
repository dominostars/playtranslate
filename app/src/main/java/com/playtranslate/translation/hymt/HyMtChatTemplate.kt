package com.playtranslate.translation.hymt

import com.playtranslate.translation.llm.languageDisplayName
import java.util.Locale

/**
 * The Tencent Hunyuan-MT 1.5 chat-template envelope, fed to the MNN-backed
 * [com.playtranslate.translation.mnn.MnnTranslator].
 *
 * Hunyuan-MT 1.5 has **no system role** per the model card — a single user
 * turn carries both the instruction and the source text. We still split the
 * envelope across [systemBlock] / [userBlock] so the MNN translator's
 * per-pair caching ([com.playtranslate.translation.mnn.MnnTranslator]'s
 * `setSystemPrompt` + `resetForNextPrompt`) prefills the invariant
 * instruction prefix once per `(source, target)` and decodes only the
 * per-sentence suffix on each call. This is **Option A** in the plan review's
 * terminology and matches the spike's `benchmark_reuse()` exactly: sysLen=23
 * cached tokens, 0% catastrophic over 500 sentences (see
 * `mnn-spike/HYMT_SPIKE_REPORT.md`).
 *
 * The model sees one user turn at inference time — the cache-boundary split
 * between "system block" and "user block" is invisible to it because
 * `mnn_chat.cpp` runs with `use_template:false` and concatenates the two
 * strings verbatim with no role markers injected.
 *
 * **Special tokens** — the markers use full-width pipe `｜` (U+FF5C, UTF-8
 * `EF BD 9C`) and SentencePiece word-boundary `▁` (U+2581, UTF-8 `E2 96 81`),
 * not ASCII `|` and `_`. The exact byte sequences must match the tokenizer's
 * special-token entries or attention math breaks. These are verbatim UTF-8
 * literals — Kotlin source files are UTF-8 by default; the existing
 * Qwen/Gemma templates rely on the same convention.
 *
 * **EOS** — runtime stop is `<｜hy_place▁holder▁no▁2｜>` (the chat
 * template's assistant-turn close marker; verified by 0% max-token hits
 * across the spike's 500-sentence benchmark). MNN reads this from the
 * exported `llm_config.json`'s `jinja.eos` field; we don't need to emit it
 * here.
 *
 * **Instruction** — the prompt body matches the Hunyuan model card's
 * recommended non-Chinese-pair format: "Translate the following text into
 * <target_language>. Note that you should only output the translated result
 * without any additional explanation:" followed by the source.
 */
object HyMtChatTemplate {

    /**
     * The cacheable instruction prefix — the invariant portion that
     * MnnTranslator hands to `setSystemPrompt` once per `(source, target)`
     * pair. Includes the `<bos>` (which lands exactly once because the
     * system block is cached and only re-prefilled when the pair changes),
     * the `<｜hy_User｜>` open marker, and the natural-language instruction
     * up through the `\n\n` separator. The per-sentence body is supplied by
     * [userBlock].
     *
     * `source` is currently unused — Hunyuan-MT's prompt only needs the
     * target language name — but we keep it in the signature to match the
     * [com.playtranslate.translation.qwen.QwenChatTemplate.systemBlock] /
     * [com.playtranslate.translation.gemma.GemmaE2BChatTemplate.systemBlock]
     * shapes so MnnTranslator's `when` branch can call all three uniformly.
     */
    @Suppress("UNUSED_PARAMETER")
    fun systemBlock(source: String, target: String): String {
        val tgtName = languageDisplayName(target.lowercase(Locale.ROOT))
        return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" +
            "Translate the following text into $tgtName. " +
            "Note that you should only output the translated result without any " +
            "additional explanation:\n\n"
    }

    /**
     * The per-sentence body: the source text immediately followed by the
     * assistant-role open marker `<｜hy_Assistant｜>` so the model knows to
     * start generating the response. Matches the spike's `build_suffix`
     * exactly (`mnn-spike/MNN/.../demo/llm_demo.cpp`, MNN_HYMT branch).
     *
     * Carries no `{context}` — and must not grow one. That was originally a
     * side-effect of Hunyuan-MT's model-card prompt being off-limits to the
     * user-editable templates; it is now also the rule, because HyMt is an
     * on-device tier and `{context}` is online-only
     * ([com.playtranslate.translation.llm.LlmPromptTemplates.TOKEN_CONTEXT]).
     * Wiring this object into `LlmPromptTemplates` would therefore break two
     * invariants at once, not one.
     */
    @Suppress("UNUSED_PARAMETER")
    fun userBlock(text: String, source: String, target: String): String =
        "$text<｜hy_Assistant｜>"
}
