package com.playtranslate.translation.llm

import com.playtranslate.translation.translategemma.LlamaTranslator
// MnnTranslator (com.playtranslate.translation.mnn.MnnTranslator) is referenced
// in the doc below but not imported — it doesn't exist until task #17 lands and
// the :app module hasn't gained the :mnn dependency yet. KDoc tolerates the
// dangling reference as plain text.

/**
 * The two on-device LLM prompting flows we support. The caller declares which
 * one applies to the model it's asking [LlamaTranslator] / `MnnTranslator` to
 * load — there is no automatic detection from the file path.
 *
 * Lives in the shared `llm/` package (rather than under one engine's
 * sub-package) so the parallel translators reference the same enum without
 * cross-engine package dependencies. Originally defined inside
 * `LlamaTranslator.kt`; moved here when the :mnn module was added so the
 * Mnn-side could reach the same type without importing across engines.
 */
sealed interface PromptStyle {
    /**
     * Models whose chat template has a true `system` role (e.g. Qwen 2.5).
     * Drives `setSystemPrompt` + `sendUserPrompt`, with `resetForNextPrompt`
     * reusing system-prompt KV across calls.
     */
    object StandardChat : PromptStyle

    /**
     * Models whose chat template emits empty for `role: system` and replays the
     * system content into every user-turn diff (e.g. Gemma 3). The fixed prefix
     * (system block + "Please translate..." scaffolding) is decoded once via
     * `processRawPrefix`; per-call only the variable suffix runs through
     * `sendRawSuffix`.
     */
    object Gemma3Prefix : PromptStyle
}
