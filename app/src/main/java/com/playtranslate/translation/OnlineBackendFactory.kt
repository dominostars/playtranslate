package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import com.playtranslate.Prefs
import com.playtranslate.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    /**
     * [live] = true: every per-call closure re-reads this instance from
     * [OnlineServiceStore], so a registered backend picks up a config edit
     * without being rebuilt.
     *
     * [live] = false pins the backend to the [instance] passed in. The config
     * page builds a throwaway SHELL from unsaved page state to validate a key
     * before saving, and there the store record is precisely the stale thing
     * the user is changing: a live shell would take the Groq key just typed,
     * read the *saved* record's OpenAI preset, probe api.openai.com with it,
     * collect a 401 and tell the user their good key was rejected. A shell
     * must answer from the page, not from the store it is about to overwrite.
     * (This only bites in EDIT mode — in CREATE mode there is no record to
     * read through to, which is why it went unnoticed.)
     */
    fun build(
        context: Context,
        sharedPrefs: SharedPreferences,
        instance: OnlineServiceInstance,
        live: Boolean = true,
    ): TranslationBackend {
        val appContext = context.applicationContext
        val id = instance.id
        fun current() = if (live) OnlineServiceStore.byId(id) ?: instance else instance
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
            ServiceType.OPENAI -> buildOpenAi(appContext, sharedPrefs, instance, live)
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
                cooldownState = CooldownState(appContext, id),
            )
        }
    }

    private fun buildOpenAi(
        appContext: Context,
        sharedPrefs: SharedPreferences,
        instance: OnlineServiceInstance,
        live: Boolean,
    ): OpenAiBackend {
        val id = instance.id
        fun current() = if (live) OnlineServiceStore.byId(id) ?: instance else instance
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
            keyProbeUrlProvider = { base -> base.trimEnd('/') + keyProbePathFor(current().preset) },
            modelsAuthHeaders = { key -> modelsAuthHeadersFor(current().preset, key) },
            // Every preset but CUSTOM names a provider whose catalog endpoint
            // exists and authenticates, so a 400 from the key probe is about
            // the key; a user-typed endpoint keeps the lenient probe.
            pinnedEndpoint = { current().preset != OpenAiPreset.CUSTOM },
            requestExtras = { model -> requestExtrasFor(current().preset, model) },
            usageTracker = UsageTracker(sharedPrefs, id),
            // owned_by filtering only makes sense against the canonical
            // first-party OpenAI catalog; every other provider tags models
            // with its own org (Mistral "mistralai", Groq the upstream lab,
            // OpenRouter the routed provider) and would filter to empty.
            applyOwnedByFilter = { current().preset == OpenAiPreset.OPENAI },
            // DeepSeek is the one provider with no cooldown to record: it
            // doesn't 429 on rate limit, it holds the request open (docs say
            // up to ~10 minutes), so there is no retry signal to read and a
            // timer would be invented. Everyone else 429s — see
            // OpenAiBackend.recordOpenAi429 for the header dialects.
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
        OpenAiPreset.MISTRAL -> OnlineServiceStore.MISTRAL_BASE_URL
        OpenAiPreset.GROQ -> OnlineServiceStore.GROQ_BASE_URL
        OpenAiPreset.OPENROUTER -> OnlineServiceStore.OPENROUTER_BASE_URL
        OpenAiPreset.CLAUDE -> OnlineServiceStore.CLAUDE_BASE_URL
        OpenAiPreset.CUSTOM -> instance.baseUrl.ifBlank { Prefs.DEFAULT_OPENAI_BASE_URL }
    }

    fun defaultModelFor(type: ServiceType, preset: OpenAiPreset): String = when (type) {
        ServiceType.GEMINI -> Prefs.DEFAULT_GEMINI_MODEL
        ServiceType.OPENAI -> when (preset) {
            OpenAiPreset.DEEPSEEK -> Prefs.DEFAULT_DEEPSEEK_MODEL
            OpenAiPreset.MISTRAL -> Prefs.DEFAULT_MISTRAL_MODEL
            OpenAiPreset.GROQ -> Prefs.DEFAULT_GROQ_MODEL
            OpenAiPreset.OPENROUTER -> Prefs.DEFAULT_OPENROUTER_MODEL
            OpenAiPreset.CLAUDE -> Prefs.DEFAULT_CLAUDE_MODEL
            OpenAiPreset.OPENAI, OpenAiPreset.CUSTOM -> Prefs.DEFAULT_OPENAI_MODEL
        }
        ServiceType.DEEPL, ServiceType.LINGVA -> ""
    }

    /** Path, relative to the base URL, whose 401 tells us a key is bad.
     *  Everyone authenticates /models — except OpenRouter, which serves it
     *  publicly, so a 200 there says nothing about the key (see
     *  [OpenAiBackend.validateKey], which independently guards against this
     *  for user-entered CUSTOM endpoints, where the dialect is unknowable). */
    private fun keyProbePathFor(preset: OpenAiPreset): String = when (preset) {
        OpenAiPreset.OPENROUTER -> OnlineServiceStore.OPENROUTER_KEY_PROBE_PATH
        else -> "/models"
    }

    /**
     * Extra top-level fields on a chat-completions request. Claude only:
     * Anthropic's compatible layer turns thinking ON by default for Claude 5
     * models, ignores `reasoning_effort`, and passes only a `thinking` field
     * through, so switching thinking off is the one latency lever a live
     * overlay has there. On the Thor (2026-09-06) Sonnet 5 took 4.9 s and
     * 23.4 s for batches of 5 and 7 short strings, about 2,000 tokens of
     * hidden reasoning across the pair, where Haiku 4.5 (no thinking by
     * default) took 1.9 to 3.1 s on the same screens.
     *
     * Fable and Mythos ids get no field: thinking is always on there and an
     * explicit off is a 400, which the batch path would then hand to the
     * per-text retry. Their latency is whatever the model decides.
     */
    fun requestExtrasFor(preset: OpenAiPreset, model: String): Map<String, JsonElement> = when {
        preset != OpenAiPreset.CLAUDE -> emptyMap()
        CLAUDE_THINKING_ALWAYS_ON.any { model.startsWith(it) } -> emptyMap()
        else -> mapOf("thinking" to buildJsonObject { put("type", "disabled") })
    }

    /** Model-id prefixes that reject `thinking: disabled`. */
    private val CLAUDE_THINKING_ALWAYS_ON = listOf("claude-fable", "claude-mythos")

    /** Headers that carry [apiKey] on the catalog calls: the key probe and
     *  the model picker's /models. Every provider takes the same Bearer
     *  token its chat endpoint takes — except Claude, whose compatible
     *  layer stops at chat-completions: its /models is Anthropic's native
     *  endpoint, which answers a Bearer token with 401 "invalid x-api-key"
     *  and reads `x-api-key` plus `anthropic-version` instead (see
     *  [OnlineServiceStore.CLAUDE_BASE_URL]). Without these, the settings
     *  page would report every valid Claude key as rejected and the picker
     *  would never load. */
    fun modelsAuthHeadersFor(preset: OpenAiPreset, apiKey: String): Map<String, String> =
        when (preset) {
            OpenAiPreset.CLAUDE -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to OnlineServiceStore.CLAUDE_API_VERSION,
            )
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }

    /** User-facing name for the instance — the service brand, except
     *  OPENAI-type instances take their preset's name ("OpenAI" /
     *  "DeepSeek" / "Custom"), which is also the cell title on the
     *  services page. */
    fun displayName(context: Context, instance: OnlineServiceInstance): String =
        when (instance.type) {
            ServiceType.GEMINI -> context.getString(R.string.gemini_display_name)
            ServiceType.OPENAI -> presetDisplayName(context, instance.preset)
            ServiceType.DEEPL -> context.getString(R.string.deepl_display_name)
            ServiceType.LINGVA -> context.getString(R.string.lingva_display_name)
        }

    /** The provider a preset stands for — the sole naming of [OpenAiPreset]
     *  in the app. Read by the instance cell title, the provider dropdown
     *  and the Add row's provider list, so a new preset is named once. */
    fun presetDisplayName(context: Context, preset: OpenAiPreset): String = when (preset) {
        OpenAiPreset.OPENAI -> context.getString(R.string.openai_display_name)
        OpenAiPreset.DEEPSEEK -> context.getString(R.string.deepseek_display_name)
        OpenAiPreset.MISTRAL -> context.getString(R.string.mistral_display_name)
        OpenAiPreset.GROQ -> context.getString(R.string.groq_display_name)
        OpenAiPreset.OPENROUTER -> context.getString(R.string.openrouter_display_name)
        OpenAiPreset.CLAUDE -> context.getString(R.string.claude_display_name)
        OpenAiPreset.CUSTOM -> context.getString(R.string.llm_backend_preset_custom)
    }

    /** The service brand itself, with no instance to take a preset from —
     *  for naming the catalog of services rather than one configured
     *  instance. OPENAI is the brand here, not its DeepSeek/Custom
     *  presets, which are reached through it. */
    fun typeDisplayName(context: Context, type: ServiceType): String = when (type) {
        ServiceType.GEMINI -> context.getString(R.string.gemini_display_name)
        ServiceType.OPENAI -> context.getString(R.string.openai_display_name)
        ServiceType.DEEPL -> context.getString(R.string.deepl_display_name)
        ServiceType.LINGVA -> context.getString(R.string.lingva_display_name)
    }

    private fun modelOf(instance: OnlineServiceInstance): String =
        instance.model.ifBlank { defaultModelFor(instance.type, instance.preset) }
}
