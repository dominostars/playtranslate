package com.playtranslate.translation

import androidx.annotation.StringRes
import com.playtranslate.R
import kotlinx.serialization.Serializable

/**
 * What a service costs a user to get started with — and the single wording
 * of it. Two surfaces say it: the subtitle under a service's row in the
 * add-service picker (which has no backend to ask, the instance not existing
 * yet), and the status line under its cell on the services page, via
 * [BackendStatus.Account]. Both resolve [labelRes], so neither can drift into
 * wording it differently.
 *
 * A [StringRes] rather than a String because the online backends are
 * deliberately Context-free — they take providers, not a Context, which is
 * why their status lines were hardcoded English for so long. The resource id
 * travels with the status and the renderer, which does have a Context, turns
 * it into words. Same division of labour as [BackendStatus.Quota].
 */
enum class AccountRequirement(@StringRes val labelRes: Int) {
    /** An account with the provider, and an API key issued by it. */
    REQUIRED(R.string.service_account_required),

    /** As [REQUIRED], but the account has a free tier — worth saying, since
     *  it's the difference between "I'll set this up" and "I'll skip it". */
    REQUIRED_FREE_TIER(R.string.service_account_required_free),

    /** Usable as-is, with no account at all. */
    NONE(R.string.service_no_account_required),
}

/** Which online translation service an [OnlineServiceInstance] is an
 *  instance of. DeepSeek and Mistral are deliberately absent — they are
 *  OPENAI-type instances with [OpenAiPreset.DEEPSEEK] / [OpenAiPreset.MISTRAL]
 *  (same chat-completions API, different base URL).
 *
 *  The two properties here are what the app knows about a service *before*
 *  the user configures one, so this enum owns both rather than leaving each
 *  surface to restate them:
 *
 *  - [isLlm] — a large language model, not a classic MT engine. Read by the
 *    "LLM" chip on the services page and in the add-service picker, and by
 *    the inline Model sub-cell. (Config-page routing keeps its own exhaustive
 *    `when`: a new service type must consciously choose a settings screen,
 *    not inherit one.) The offline half of the question is answered by
 *    membership in OnDeviceLlmBackend.
 *  - [account] — what it costs to start. Read by the picker's subtitle, and
 *    by each backend's key-less [BackendStatus.Account]; every backend class
 *    maps 1:1 onto a type here, so it reads its own entry rather than
 *    restating the requirement.
 */
@Serializable
enum class ServiceType(
    val isLlm: Boolean,
    val account: AccountRequirement,
) {
    GEMINI(isLlm = true,  account = AccountRequirement.REQUIRED),
    OPENAI(isLlm = true,  account = AccountRequirement.REQUIRED),
    DEEPL (isLlm = false, account = AccountRequirement.REQUIRED_FREE_TIER),
    LINGVA(isLlm = false, account = AccountRequirement.NONE),
}

/** Provider preset for OPENAI-type instances. Every preset but CUSTOM pins
 *  the base URL to that provider's canonical endpoint; CUSTOM uses the
 *  instance's stored [OnlineServiceInstance.baseUrl]. The preset also
 *  names the instance's cell on the services page.
 *
 *  Declaration order is display order: it drives the provider dropdown and
 *  the Add row's "OpenAI (DeepSeek, Mistral, Custom)" subtitle, both of
 *  which enumerate this enum rather than restating it — so a new provider
 *  surfaces in the UI from this line alone. Keep OPENAI first (the default)
 *  and CUSTOM last (the escape hatch). Serialization is by name, so
 *  inserting a value doesn't disturb stored instances.
 *
 *  CLAUDE is Anthropic's OpenAI-compatible layer, which covers
 *  chat-completions only: its /models is the native endpoint with its own
 *  auth headers ([OnlineBackendFactory.modelsAuthHeadersFor]). */
@Serializable
enum class OpenAiPreset { OPENAI, DEEPSEEK, MISTRAL, GROQ, OPENROUTER, CLAUDE, CUSTOM }

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
