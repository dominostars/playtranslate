package com.playtranslate.translation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A provider whose model catalog states each model's thinking options: where
 * to ask about one model, and which part of the answer is the
 * [ReportedThinking.facts] that [ReasoningControls] reads. Only the providers
 * that publish them are here ([forPreset]); everyone else's options come from
 * their docs.
 */
enum class ThinkingCatalog {
    /** `GET {models}/models`, the model's `effort`: the levels it takes with
     *  thinking on (`supported_levels`). Off is `thinking: disabled`. */
    DEEPSEEK,

    /** `GET {base}/models`, the model's `reasoning`: absent on a model that
     *  doesn't reason, else `supported_efforts` and `mandatory`. */
    OPENROUTER,

    /** `GET {base}/models/{id}`, the model's `capabilities.thinking`. */
    CLAUDE;

    /** Where to ask about [model], under the catalog base [modelsUrl]. */
    fun url(modelsUrl: String, model: String): String = when (this) {
        DEEPSEEK, OPENROUTER -> "${modelsUrl.trimEnd('/')}/models"
        CLAUDE -> "${modelsUrl.trimEnd('/')}/models/$model"
    }

    /** The facts for [model] in the catalog's answer [body]: its subtree, or
     *  `{}` where the catalog describes the model without one; null where the
     *  answer says nothing about the model (not listed, or Claude's
     *  `capabilities` null), which stores nothing. */
    fun facts(body: JsonElement, model: String): JsonObject? = when (this) {
        DEEPSEEK -> listed(body, model)?.let { subtree(it, "effort") }
        OPENROUTER -> listed(body, model)?.let { subtree(it, "reasoning") }
        CLAUDE -> ((body as? JsonObject)?.get("capabilities") as? JsonObject)?.let { subtree(it, "thinking") }
    }

    companion object {
        /** The catalog an OpenAI-type preset's thinking options come from,
         *  or null where the provider publishes none. Exhaustive, so a new
         *  preset has to decide. */
        fun forPreset(preset: OpenAiPreset): ThinkingCatalog? = when (preset) {
            OpenAiPreset.DEEPSEEK -> DEEPSEEK
            OpenAiPreset.OPENROUTER -> OPENROUTER
            OpenAiPreset.CLAUDE -> CLAUDE
            OpenAiPreset.OPENAI, OpenAiPreset.MISTRAL, OpenAiPreset.GROQ,
            OpenAiPreset.INDEX_TRANSLATE, OpenAiPreset.CUSTOM -> null
        }

        /** The entry for [model] in a list answer's `data`. */
        private fun listed(body: JsonElement, model: String): JsonObject? =
            ((body as? JsonObject)?.get("data") as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == model }

        /** `{key: …}` when [obj] has an object at [key], else `{}`. */
        private fun subtree(obj: JsonObject, key: String): JsonObject =
            (obj[key] as? JsonObject)?.let { JsonObject(mapOf(key to it)) } ?: JsonObject(emptyMap())
    }
}
