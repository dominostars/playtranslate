package com.playtranslate.translation.llm

/**
 * Thrown by on-device LLM translators when a recoverable condition prevents
 * the current call (typically low available memory). The waterfall in
 * [com.playtranslate.translation.TranslationBackendRegistry] catches this
 * specifically: instead of surfacing to the user as a translation failure,
 * it falls through to the next backend in the waterfall (commonly ML Kit).
 *
 * Previously named `TranslateGemmaTransientException` and defined in
 * `translation/translategemma/LlamaTranslator.kt`; moved here and renamed
 * as part of the :llama strip so the exception type is engine-agnostic.
 * [com.playtranslate.translation.mnn.MnnTranslator] is the sole thrower
 * now that the GGUF backends are gone.
 */
class OnDeviceLlmTransientException(message: String) : RuntimeException(message)
