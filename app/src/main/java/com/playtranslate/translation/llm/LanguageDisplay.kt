package com.playtranslate.translation.llm

import java.util.Locale

/**
 * English-locale language name from an ISO code: "ja" → "Japanese",
 * "zh" → "Chinese", etc. Used by the on-device LLM translators' chat-template
 * assembly so the *prompt* text the model sees stays in English regardless of
 * the device locale — Qwen / Gemma / etc. are trained on English instructions
 * and respond more reliably to "Japanese (ja) to English (en)" than to a
 * device-locale rendering like "日本語 (ja) to English (en)".
 *
 * Returns the input code unchanged for codes the JDK doesn't recognize, so a
 * stray ISO 639-3 code or `und` gracefully falls through instead of becoming
 * an empty string.
 */
fun languageDisplayName(code: String): String =
    Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
