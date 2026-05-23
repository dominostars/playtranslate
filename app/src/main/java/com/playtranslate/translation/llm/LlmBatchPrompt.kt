package com.playtranslate.translation.llm

import com.google.gson.Gson
import java.util.Locale

/**
 * Shared system + user prompt builder for the batched cloud LLM
 * backends ([com.playtranslate.translation.GeminiBackend] and
 * [com.playtranslate.translation.OpenAiBackend]).
 *
 * Lives separately from `QwenChatTemplate` — which is packaged under
 * the on-device path (`translation/qwen/`) — because the on-device
 * LLMs aren't being batched and mixing cloud-only batch helpers into
 * that namespace muddles the abstraction. The single-text
 * `QwenChatTemplate.systemPrompt` / `userMessage` are still called
 * from the cloud backends' single-text `translate(...)` path; that
 * reuse stays.
 *
 * Encoding choice: the user message embeds the input list as a
 * JSON array via [Gson.toJson] rather than a numbered list
 * (`"1. text\n2. text"`). OCR groups frequently contain digits,
 * newlines, or quote characters that would collide with a numbered
 * format; JSON encoding handles all of that losslessly for free.
 */
object LlmBatchPrompt {

    fun systemPrompt(source: String, target: String): String {
        val srcName = languageDisplayName(source.lowercase(Locale.ROOT))
        val tgtName = languageDisplayName(target.lowercase(Locale.ROOT))
        return """You are a professional $srcName to $tgtName translator. You will be given a JSON array of $srcName strings. Translate each string into $tgtName accurately, preserving meaning and nuance.

Respond with a JSON object of the form {"translations": [...]} containing exactly one $tgtName string per input, in the same order. Do not add commentary, prefixes, or notes."""
    }

    fun userMessage(texts: List<String>): String {
        val json = Gson().toJson(texts)
        return "Translate each of these ${texts.size} strings:\n$json"
    }
}
