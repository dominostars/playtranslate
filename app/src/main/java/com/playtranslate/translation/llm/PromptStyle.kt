package com.playtranslate.translation.llm

/**
 * The on-device LLM prompting flows we support. The caller declares which one
 * applies to the model it's asking [com.playtranslate.translation.mnn.MnnTranslator]
 * to load — there is no automatic detection from the file path.
 *
 * Lives in the shared `llm/` package so the new-backend pattern can reuse it
 * without an engine-specific import; after the :llama strip, the MNN translator
 * is the sole consumer.
 */
sealed interface PromptStyle {
    /**
     * Models whose chat template has a true `system` role (e.g. Qwen 2.5),
     * using `<|im_start|>{role}` / `<|im_end|>` envelope markers. Drives
     * `setSystemPrompt` + `sendUserPrompt`, with `resetForNextPrompt`
     * reusing system-prompt KV across calls in the same `(source, target)`.
     */
    object StandardChat : PromptStyle

    /**
     * Gemma 4 chat template — `<|turn>{role}…<turn|>` markers with the role
     * name (system / user / model) baked into the opening marker, plus a
     * `<bos>` prepended to the first system block. Same JNI dispatch as
     * [StandardChat] (Gemma 4 has a true system role); the difference is
     * purely the marker strings, supplied by
     * [com.playtranslate.translation.gemma.GemmaE2BChatTemplate].
     */
    object Gemma4Chat : PromptStyle

    /**
     * Tencent Hunyuan-MT chat template (1.5 and Hy-MT2). A translation is
     * a single user turn, per the model card's prompt. Full-width pipe
     * markers `<｜hy_User｜>` / `<｜hy_Assistant｜>` (UTF-8 U+FF5C "｜" pipes,
     * not ASCII `|`) wrap the single user message; runtime stop is
     * `<｜hy_place▁holder▁no▁2｜>`. A free-form prompt uses the template's
     * system turn as well
     * ([com.playtranslate.translation.hymt.HyMtChatTemplate.systemEnvelope]).
     *
     * We still go through the same `setSystemPrompt` + `sendUserPrompt`
     * dispatch as the other styles — the "system block" here is the
     * cacheable instruction prefix (`<bos><｜hy_User｜>Translate the
     * following text into …`), and `sendUserPrompt` carries the
     * per-sentence body + `<｜hy_Assistant｜>` generation marker. The
     * model sees one user turn at inference; the cache-boundary split is
     * invisible to it. This split matches the spike's `benchmark_reuse()`
     * exactly (see `mnn-spike/HYMT_SPIKE_REPORT.md`, sysLen=23 tokens
     * cached, 0% catastrophic over 500 sentences). Marker strings live in
     * [com.playtranslate.translation.hymt.HyMtChatTemplate].
     */
    object HyMtChat : PromptStyle

    /**
     * Qwen 3.5 — same ChatML `<|im_start|>{role}` / `<|im_end|>` envelope and
     * true system role as [StandardChat], but Qwen 3.5 is a hybrid
     * thinking/non-thinking model: its chat template opens the assistant turn
     * with `<think>\n\n</think>\n\n` whenever thinking is disabled. With
     * `use_template:false` (mnn_chat.cpp) we bake that empty think-block into
     * the user turn ourselves so the model skips the reasoning monologue and
     * emits the translation directly. Without it the model burns latency and
     * tokens on a `<think>` pass — fatal for a translation tier. Envelope
     * strings come from [com.playtranslate.translation.qwen.QwenChatTemplate]
     * ([QwenChatTemplate.userBlockNoThink]); spike-confirmed 0 think-leaks over
     * 500 sentences (mnn-spike/QWEN35_SPIKE_REPORT.md).
     */
    object Qwen35Chat : PromptStyle
}
