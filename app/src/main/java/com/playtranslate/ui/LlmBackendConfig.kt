package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.translation.ModelLister
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Per-instance configuration descriptor handed to
 * [LlmBackendSettingsActivity] and [LlmModelPickerActivity]. Purely a
 * read-side + display bundle: persistence goes through
 * [com.playtranslate.translation.OnlineServiceMutations] (create-on-save
 * semantics — no store record exists until the first successful save),
 * and key validation runs against a page-state shell backend built in
 * the activity, so a preset switched in the UI but not yet saved
 * validates against the right endpoint.
 *
 * Rebuild via [LlmBackendConfigs.forInstance] whenever the page's preset
 * selection changes — construction is a handful of lambdas.
 */
data class LlmBackendConfig(
    val instanceId: String,
    val type: ServiceType,
    val preset: OpenAiPreset,
    val displayName: String,
    val keyHint: String,
    val getKeyUrl: String,
    val getKey: () -> String,
    val getModel: () -> String,
    /** Fetches the models the SAVED instance's key can call, via the
     *  registered backend — the model picker is only reachable for
     *  saved instances. Returns empty when the backend is missing or
     *  not [ModelLister]-capable. */
    val listModels: suspend () -> List<String>,
    val defaultModel: String,
    /** True for OPENAI-type instances: the ADVANCED section (provider
     *  preset + base URL) is shown. Hidden for Gemini. */
    val allowsBaseUrl: Boolean,
    /** The base URL the current preset resolves to — field text for
     *  pinned presets, hint for CUSTOM. */
    val presetBaseUrl: String,
    val getBaseUrl: () -> String,
)

object LlmBackendConfigs {

    fun forInstance(
        context: Context,
        instanceId: String,
        type: ServiceType,
        preset: OpenAiPreset,
    ): LlmBackendConfig {
        // A shell carrying the page's (possibly unsaved) preset — display
        // strings and URL resolution key off it, not the stored record.
        val shell = OnlineServiceInstance(
            id = instanceId, type = type, enabled = false, preset = preset,
        )
        return LlmBackendConfig(
            instanceId = instanceId,
            type = type,
            preset = preset,
            displayName = OnlineBackendFactory.displayName(context, shell),
            keyHint = keyHintFor(type, preset),
            getKeyUrl = keyUrlFor(type, preset),
            getKey = { OnlineServiceStore.readKey(instanceId) },
            getModel = {
                OnlineServiceStore.byId(instanceId)?.model
                    ?.ifBlank { OnlineBackendFactory.defaultModelFor(type, preset) }
                    ?: OnlineBackendFactory.defaultModelFor(type, preset)
            },
            listModels = {
                (TranslationBackendRegistry.byId(instanceId) as? ModelLister)?.listModels()
                    ?: emptyList()
            },
            defaultModel = OnlineBackendFactory.defaultModelFor(type, preset),
            allowsBaseUrl = type == ServiceType.OPENAI,
            presetBaseUrl = OnlineBackendFactory.resolveBaseUrl(shell)
                .ifBlank { Prefs.DEFAULT_OPENAI_BASE_URL },
            getBaseUrl = { OnlineServiceStore.byId(instanceId)?.baseUrl ?: "" },
        )
    }

    /**
     * Placeholder for the API-key field. It is a FORMAT EXAMPLE, so it may
     * only appear where the provider actually issues keys of that shape:
     * OpenAI (`sk-`, `sk-proj-`) and DeepSeek (`sk-`) do; Gemini's are
     * `AIza`-prefixed.
     *
     * Groq issues `gsk_`, OpenRouter `sk-or-v1-` and Anthropic `sk-ant-`.
     * Mistral's are not
     * prefixed at all, and CUSTOM can point at anyone — a raw JWT (MiniMax),
     * an opaque token, anything. Those two show no placeholder rather than an
     * `sk-...` that would have the user hunting for a key they don't have.
     * The field carries a visible "API Key" label, so the placeholder is a
     * nicety and its absence costs nothing.
     *
     * No prefix here is a contract — not one provider publishes a key format,
     * and Groq's and OpenRouter's are read off their own examples — so this
     * is a hint only. Never validate a pasted key against it.
     */
    private fun keyHintFor(type: ServiceType, preset: OpenAiPreset): String = when (type) {
        ServiceType.GEMINI -> "AIza..."
        ServiceType.OPENAI -> when (preset) {
            OpenAiPreset.OPENAI, OpenAiPreset.DEEPSEEK -> "sk-..."
            OpenAiPreset.GROQ -> "gsk_..."
            OpenAiPreset.OPENROUTER -> "sk-or-v1-..."
            OpenAiPreset.CLAUDE -> "sk-ant-..."
            OpenAiPreset.MISTRAL, OpenAiPreset.CUSTOM -> ""
        }
        // DeepL and Lingva don't route through this screen.
        ServiceType.DEEPL, ServiceType.LINGVA -> ""
    }

    /** Where to get a key for this provider. CUSTOM keeps OpenAI's page —
     *  the legacy custom-URL flow lived on the OpenAI screen with this
     *  same link, and most custom endpoints (OpenRouter, proxies) accept
     *  an OpenAI-style key anyway. */
    private fun keyUrlFor(type: ServiceType, preset: OpenAiPreset): String = when (type) {
        ServiceType.GEMINI -> "https://aistudio.google.com/app/apikey"
        ServiceType.OPENAI -> when (preset) {
            OpenAiPreset.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
            OpenAiPreset.MISTRAL -> "https://console.mistral.ai/api-keys"
            OpenAiPreset.GROQ -> "https://console.groq.com/keys"
            OpenAiPreset.OPENROUTER -> "https://openrouter.ai/keys"
            OpenAiPreset.CLAUDE -> "https://platform.claude.com/settings/keys"
            else -> "https://platform.openai.com/api-keys"
        }
        // DeepL and Lingva don't route through this screen.
        ServiceType.DEEPL, ServiceType.LINGVA -> ""
    }
}
