package com.playtranslate.translation

import kotlinx.serialization.Serializable

/** Which online translation service an [OnlineServiceInstance] is an
 *  instance of. DeepSeek is deliberately absent — it is an OPENAI-type
 *  instance with [OpenAiPreset.DEEPSEEK] (same chat-completions API,
 *  different base URL). */
@Serializable
enum class ServiceType { GEMINI, OPENAI, DEEPL, LINGVA }

/** Provider preset for OPENAI-type instances. OPENAI and DEEPSEEK pin the
 *  base URL to the provider's canonical endpoint; CUSTOM uses the
 *  instance's stored [OnlineServiceInstance.baseUrl]. The preset also
 *  names the instance's cell on the services page. */
@Serializable
enum class OpenAiPreset { OPENAI, DEEPSEEK, CUSTOM }

/**
 * One user-configured online translation service. Users can hold any
 * number of instances of the same [type] (e.g. two OpenAI configs with
 * different API keys); the list order in [OnlineServiceStore] is the
 * translation-waterfall priority among online services.
 *
 * [id] is the stable identity across four subsystems — the registry
 * ([TranslationBackendRegistry.byId]), the translation cache's
 * preferred-backend check, the [UsageTracker] namespace, and the
 * [CooldownState] namespace. Instances migrated from the legacy
 * one-per-service prefs keep their legacy ids ("gemini", "openai",
 * "deepseek", "deepl", "lingva") so that history survives; new instances
 * get a random UUID.
 *
 * The API key is deliberately NOT a field: keys stay in per-instance
 * encrypted SharedPreferences slots (see [OnlineServiceStore.keySlot])
 * so they remain AES-GCM-encrypted at rest with the slot name bound as
 * AAD, exactly like the legacy per-service keys.
 */
@Serializable
data class OnlineServiceInstance(
    val id: String,
    val type: ServiceType,
    val enabled: Boolean,
    /** Model id — meaningful for GEMINI and OPENAI types only. */
    val model: String = "",
    /** Meaningful for OPENAI type only. */
    val preset: OpenAiPreset = OpenAiPreset.OPENAI,
    /** User-entered base URL — consulted only when [preset] == CUSTOM. */
    val baseUrl: String = "",
)
