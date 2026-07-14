package com.playtranslate.translation.llm

/**
 * The batched cloud prompt pair for
 * [com.playtranslate.translation.GeminiBackend] and
 * [com.playtranslate.translation.OpenAiBackend]'s `translateBatch`.
 *
 * Thin facade over [LlmPromptTemplates], which owns the content: the
 * system message is the user-editable System prompt plus the fixed
 * `{"translations": [...]}` response contract (code-owned — the backends'
 * parsers depend on it), and the user message is the user-editable Batch
 * prompt with the input list embedded as a JSON array. JSON rather than a
 * numbered list because OCR groups frequently contain digits, newlines,
 * or quote characters that a `"1. text\n2. text"` format would collide
 * with; JSON encoding handles all of that losslessly for free.
 */
object LlmBatchPrompt {

    fun systemPrompt(source: String, target: String): String =
        LlmPromptTemplates.batchSystemPrompt(source, target)

    fun userMessage(
        texts: List<String>,
        source: String,
        target: String,
        includeContext: Boolean,
    ): String = LlmPromptTemplates.batchUserMessage(texts, source, target, includeContext)
}
