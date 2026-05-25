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
     * Tencent Hunyuan-MT 1.5 chat template — single user turn (no `system`
     * role per the model card). Full-width pipe markers `<｜hy_User｜>` /
     * `<｜hy_Assistant｜>` (UTF-8 U+FF5C "｜" pipes, not ASCII `|`) wrap the
     * single user message; runtime stop is `<｜hy_place▁holder▁no▁2｜>`.
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
}
