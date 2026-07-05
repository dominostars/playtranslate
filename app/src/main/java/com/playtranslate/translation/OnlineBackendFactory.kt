package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import com.playtranslate.Prefs
import com.playtranslate.R

/**
 * Maps an [OnlineServiceInstance] to a live [TranslationBackend].
 *
 * Everything that can change without a config-page save — enabled state,
 * key, model, base URL, owned_by filter — is a closure that re-reads the
 * [OnlineServiceStore] snapshot per call, so toggles and model picks
 * propagate to the next translate() with no registry churn (the same
 * closure discipline the legacy `Prefs`-reading wiring used).
 *
 * Two things ARE baked at construction: [TranslationBackend.displayName]
 * (a val on the interface) and the cooldown participation (DeepSeek
 * preset opts out — its 10-minute TCP-hold makes SocketTimeoutException
 * categorisation too ambiguous for the v1 cooldown ladder). Both can
 * only change through a config-page save, and the save path rebuilds the
 * backend (registry remove + add) — so they can never go stale.
 */
object OnlineBackendFactory {

    /** Nominal priority for store-driven online instances. Never consulted
     *  while every online id is in the registry's setOrder override; it
     *  only positions an instance in the brief window between
     *  addOnlineBackend and the following setOrder — above the offline
     *  tiers (25+), below nothing that matters. */
    private const val ONLINE_PRIORITY = 15

    fun build(
        context: Context,
        sharedPrefs: SharedPreferences,
        instance: OnlineServiceInstance,
    ): TranslationBackend {
        val appContext = context.applicationContext
        val id = instance.id
        // Per-call closures read the live store record, falling back to
        // the construction snapshot. The fallback matters for SHELL
        // backends — the config page builds a throwaway backend from its
        // unsaved page state to run key validation before any store
        // record exists; for registered backends the store record always
        // wins.
        fun current() = OnlineServiceStore.byId(id) ?: instance
        return when (instance.type) {
            ServiceType.GEMINI -> GeminiBackend(
                id = id,
                priority = ONLINE_PRIORITY,
                keyProvider = { OnlineServiceStore.readKey(id) },
                enabledProvider = { current().enabled },
                modelProvider = { modelOf(current()) },
                usageTracker = UsageTracker(sharedPrefs, id),
                cooldownState = CooldownState(appContext, id),
            )
            ServiceType.OPENAI -> buildOpenAi(appContext, sharedPrefs, instance)
            ServiceType.DEEPL -> DeepLBackend(
                id = id,
                priority = ONLINE_PRIORITY,
                keyProvider = { OnlineServiceStore.readKey(id) },
                enabledProvider = { current().enabled },
                cooldownState = CooldownState(appContext, id),
            )
            ServiceType.LINGVA -> LingvaBackend(
                id = id,
                priority = ONLINE_PRIORITY,
                enabledProvider = { current().enabled },
            )
        }
    }

    private fun buildOpenAi(
        appContext: Context,
        sharedPrefs: SharedPreferences,
        instance: OnlineServiceInstance,
    ): OpenAiBackend {
        val id = instance.id
        fun current() = OnlineServiceStore.byId(id) ?: instance
        return OpenAiBackend(
            id = id,
            displayName = displayName(appContext, instance),
            priority = ONLINE_PRIORITY,
            keyProvider = { OnlineServiceStore.readKey(id) },
            enabledProvider = { current().enabled },
            modelProvider = { modelOf(current()) },
            baseUrlProvider = { resolveBaseUrl(current()) },
            modelsUrlProvider = {
                val c = current()
                if (c.preset == OpenAiPreset.DEEPSEEK) OnlineServiceStore.DEEPSEEK_MODELS_URL
                else resolveBaseUrl(c)
            },
            usageTracker = UsageTracker(sharedPrefs, id),
            // owned_by filtering only makes sense against the canonical
            // first-party OpenAI catalog; DeepSeek and custom endpoints
            // tag models with their own org and would filter to empty.
            applyOwnedByFilter = { current().preset == OpenAiPreset.OPENAI },
            cooldownState = if (instance.preset == OpenAiPreset.DEEPSEEK) null
            else CooldownState(appContext, id),
        )
    }

    /** The chat-completions base URL an OPENAI-type instance resolves to:
     *  presets pin the canonical provider endpoint; CUSTOM uses the
     *  user-entered URL. */
    fun resolveBaseUrl(instance: OnlineServiceInstance): String = when (instance.preset) {
        OpenAiPreset.OPENAI -> Prefs.DEFAULT_OPENAI_BASE_URL
        OpenAiPreset.DEEPSEEK -> OnlineServiceStore.DEEPSEEK_BASE_URL
        OpenAiPreset.CUSTOM -> instance.baseUrl.ifBlank { Prefs.DEFAULT_OPENAI_BASE_URL }
    }

    fun defaultModelFor(type: ServiceType, preset: OpenAiPreset): String = when (type) {
        ServiceType.GEMINI -> Prefs.DEFAULT_GEMINI_MODEL
        ServiceType.OPENAI ->
            if (preset == OpenAiPreset.DEEPSEEK) Prefs.DEFAULT_DEEPSEEK_MODEL
            else Prefs.DEFAULT_OPENAI_MODEL
        ServiceType.DEEPL, ServiceType.LINGVA -> ""
    }

    /** User-facing name for the instance — the service brand, except
     *  OPENAI-type instances take their preset's name ("OpenAI" /
     *  "DeepSeek" / "Custom"), which is also the cell title on the
     *  services page. */
    fun displayName(context: Context, instance: OnlineServiceInstance): String =
        when (instance.type) {
            ServiceType.GEMINI -> context.getString(R.string.gemini_display_name)
            ServiceType.OPENAI -> when (instance.preset) {
                OpenAiPreset.OPENAI -> context.getString(R.string.openai_display_name)
                OpenAiPreset.DEEPSEEK -> context.getString(R.string.deepseek_display_name)
                OpenAiPreset.CUSTOM -> context.getString(R.string.llm_backend_preset_custom)
            }
            ServiceType.DEEPL -> context.getString(R.string.deepl_display_name)
            ServiceType.LINGVA -> context.getString(R.string.lingva_display_name)
        }

    private fun modelOf(instance: OnlineServiceInstance): String =
        instance.model.ifBlank { defaultModelFor(instance.type, instance.preset) }
}
