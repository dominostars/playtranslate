package com.playtranslate.ui

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.translation.KeyStatus
import com.playtranslate.translation.ModelLister
import com.playtranslate.translation.OpenAiBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.UsageTracker

/**
 * Per-backend configuration handed to [LlmBackendSettingsActivity]. The
 * activity stays generic by reading and writing through these getters
 * and setters; adding a third LLM backend (Anthropic, etc.) is one new
 * branch in [LlmBackendConfigs.forId] plus the backend class itself.
 *
 * `validateKey` is nullable rather than always-present so providers
 * without a cheap key-validation endpoint (Gemini) can omit it; the
 * activity skips the on-save ping in that case.
 */
data class LlmBackendConfig(
    val displayName: String,
    val titleStringRes: Int,
    val keyHint: String,
    val getKeyUrl: String,
    val getKey: () -> String,
    val setKey: (String) -> Unit,
    val getModel: () -> String,
    val setModel: (String) -> Unit,
    /** Fetches the list of models the configured key can call. Suspend
     *  because it's a network call to the provider's /models endpoint;
     *  see [GeminiBackend.listModels] and [OpenAiBackend.listModels].
     *  Throws on any failure — the picker is responsible for catching
     *  and rendering a fallback. */
    val listModels: suspend () -> List<String>,
    val defaultModel: String,
    val getEnabled: () -> Boolean,
    val setEnabled: (Boolean) -> Unit,
    val todayUsageString: () -> String,
    val validateKey: (suspend () -> KeyStatus)?,
)

object LlmBackendConfigs {

    /** Loud failure on unknown id beats silently opening the wrong screen
     *  from a typo'd row click. */
    fun forId(context: Context, id: String): LlmBackendConfig = when (id) {
        "openai" -> openAiConfig(context)
        "gemini" -> geminiConfig(context)
        "deepseek" -> deepseekConfig(context)
        else -> throw IllegalArgumentException("Unknown LLM backend id: $id")
    }

    /** Shared `listModels` lambda for any provider whose registered
     *  backend implements [ModelLister]. Adding a new LLM backend
     *  (Anthropic, etc.) only needs to implement the capability — no
     *  per-class smart-cast here. Returns an empty list if the backend
     *  isn't ModelLister-capable so the picker falls back to its
     *  "Custom…"-only state. */
    private fun lookupModels(backendId: String): suspend () -> List<String> = {
        (TranslationBackendRegistry.byId(backendId) as? ModelLister)?.listModels()
            ?: emptyList()
    }

    /** Shared `validateKey` lambda for any provider whose registered
     *  backend is an [OpenAiBackend] instance. The validation hits the
     *  provider's /v1/models endpoint with the configured key —
     *  identical implementation for OpenAI, DeepSeek, and any future
     *  OpenAI-compatible provider. */
    private fun lookupValidateKey(backendId: String): suspend () -> KeyStatus = {
        (TranslationBackendRegistry.byId(backendId) as? OpenAiBackend)?.validateKey()
            ?: KeyStatus.Unreachable
    }

    private fun openAiConfig(context: Context): LlmBackendConfig {
        val prefs = Prefs(context)
        val sp = context.applicationContext.getSharedPreferences(
            "playtranslate_prefs",
            Context.MODE_PRIVATE,
        )
        val tracker = UsageTracker(sp, "openai")
        return LlmBackendConfig(
            displayName = context.getString(R.string.openai_display_name),
            titleStringRes = R.string.openai_settings_title,
            keyHint = "sk-...",
            getKeyUrl = "https://platform.openai.com/api-keys",
            getKey = { prefs.openaiApiKey },
            setKey = { prefs.openaiApiKey = it },
            getModel = { prefs.openaiModel },
            setModel = { prefs.openaiModel = it },
            listModels = lookupModels("openai"),
            defaultModel = Prefs.DEFAULT_OPENAI_MODEL,
            getEnabled = { prefs.openaiEnabled },
            setEnabled = { prefs.openaiEnabled = it },
            todayUsageString = { tracker.todayString() },
            validateKey = lookupValidateKey("openai"),
        )
    }

    private fun geminiConfig(context: Context): LlmBackendConfig {
        val prefs = Prefs(context)
        val sp = context.applicationContext.getSharedPreferences(
            "playtranslate_prefs",
            Context.MODE_PRIVATE,
        )
        val tracker = UsageTracker(sp, "gemini")
        return LlmBackendConfig(
            displayName = context.getString(R.string.gemini_display_name),
            titleStringRes = R.string.gemini_settings_title,
            keyHint = "AIza...",
            getKeyUrl = "https://aistudio.google.com/app/apikey",
            getKey = { prefs.geminiApiKey },
            setKey = { prefs.geminiApiKey = it },
            getModel = { prefs.geminiModel },
            setModel = { prefs.geminiModel = it },
            listModels = lookupModels("gemini"),
            defaultModel = Prefs.DEFAULT_GEMINI_MODEL,
            getEnabled = { prefs.geminiEnabled },
            setEnabled = { prefs.geminiEnabled = it },
            todayUsageString = { tracker.todayString() },
            validateKey = null,
        )
    }

    private fun deepseekConfig(context: Context): LlmBackendConfig {
        val prefs = Prefs(context)
        val sp = context.applicationContext.getSharedPreferences(
            "playtranslate_prefs",
            Context.MODE_PRIVATE,
        )
        val tracker = UsageTracker(sp, "deepseek")
        return LlmBackendConfig(
            displayName = context.getString(R.string.deepseek_display_name),
            titleStringRes = R.string.deepseek_settings_title,
            keyHint = "sk-...",
            getKeyUrl = "https://platform.deepseek.com/api_keys",
            getKey = { prefs.deepseekApiKey },
            setKey = { prefs.deepseekApiKey = it },
            getModel = { prefs.deepseekModel },
            setModel = { prefs.deepseekModel = it },
            listModels = lookupModels("deepseek"),
            defaultModel = Prefs.DEFAULT_DEEPSEEK_MODEL,
            getEnabled = { prefs.deepseekEnabled },
            setEnabled = { prefs.deepseekEnabled = it },
            todayUsageString = { tracker.todayString() },
            validateKey = lookupValidateKey("deepseek"),
        )
    }
}
