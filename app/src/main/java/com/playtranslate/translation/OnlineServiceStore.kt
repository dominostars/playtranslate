package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Single source of truth for the user's ordered list of
 * [OnlineServiceInstance]s. The list order IS the translation-waterfall
 * priority among online services ([TranslationBackendRegistry.setOrder]
 * is fed from it).
 *
 * Persistence: one JSON string under [KEY_INSTANCES] in the shared
 * `playtranslate_prefs` file. API keys are NOT in the JSON — each
 * instance owns an encrypted slot named by [keySlot], read/written
 * through [Prefs]' SecretCodec path so keys stay encrypted at rest.
 *
 * Thread safety: writes happen on the main thread (settings UI) and are
 * serialized on [writeLock]; reads happen from any coroutine (backend
 * `enabledProvider`/`keyProvider` closures run on Dispatchers.IO inside
 * the translate waterfall) via the lock-free `@Volatile` [snapshot] —
 * the same copy-on-write discipline [TranslationBackendRegistry] uses
 * for its backend list.
 *
 * Lifecycle: [init] runs once from
 * [com.playtranslate.PlayTranslateApplication.onCreate], BEFORE the
 * registry is built (the registry's online set is constructed from this
 * store). It performs the one-shot migration from the legacy
 * one-per-service prefs, then loads the snapshot.
 */
object OnlineServiceStore {

    private const val TAG = "OnlineServiceStore"

    const val KEY_INSTANCES = "online_service_instances"
    private const val KEY_MIGRATED = "online_instances_migrated"

    /** DeepSeek speaks the OpenAI-compatible chat-completions API at /v1,
     *  but /v1/models returns 200 + empty body — the real model-listing
     *  endpoint sits at the root. */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val DEEPSEEK_MODELS_URL = "https://api.deepseek.com"

    /** Mistral serves both chat-completions and /models under /v1, so it
     *  needs no models-URL override the way DeepSeek does. */
    const val MISTRAL_BASE_URL = "https://api.mistral.ai/v1"

    /** Groq's OpenAI-compatible surface is nested under /openai — the host
     *  root serves Groq's own API. */
    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"

    /** Note the /api: users reliably guess "openrouter.ai/v1" and get a 404,
     *  which is half the reason this is a preset rather than a Custom URL.
     *
     *  OpenRouter's /models is PUBLIC — it answers 200 with no key at all
     *  (verified) — so it cannot double as a key check the way every other
     *  provider's does. [KEY_PROBE_PATH] names the endpoint that can:
     *  GET /key reports the key's own credit and rate-limit state and 401s
     *  without a valid one. */
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val OPENROUTER_KEY_PROBE_PATH = "/key"

    /** Anthropic's OpenAI-compatible layer shares the /v1 prefix with the
     *  native API: `/v1/chat/completions` takes the Claude key as a Bearer
     *  token, but there is no compatible `/v1/models`. That path is the
     *  NATIVE model list, which ignores `Authorization: Bearer` (401
     *  "invalid x-api-key", verified with a bogus key on 2026-09-05) and
     *  reads `x-api-key` plus the `anthropic-version` header instead, so
     *  the catalog calls send those ([OnlineBackendFactory.modelsAuthHeadersFor]).
     *  Anthropic documents the layer as ignoring `response_format`, so the
     *  batch path's JSON shape rests on the prompt there (see
     *  OpenAiBackend.translateBatch). */
    const val CLAUDE_BASE_URL = "https://api.anthropic.com/v1"
    const val CLAUDE_API_VERSION = "2023-06-01"

    /** Migrated instances keep the legacy encrypted key slots so no
     *  ciphertext ever moves: SecretCodec binds each blob to its slot
     *  name as GCM AAD, so a copy to a new slot would not decrypt. */
    private val LEGACY_SLOTS = mapOf(
        "gemini" to Prefs.KEY_GEMINI_KEY,
        "openai" to Prefs.KEY_OPENAI_KEY,
        "deepseek" to Prefs.KEY_DEEPSEEK_KEY,
        "deepl" to Prefs.KEY_DEEPL_KEY,
    )

    @Volatile private var snapshot: List<OnlineServiceInstance> = emptyList()
    private lateinit var sp: SharedPreferences
    private lateinit var prefs: Prefs
    private val writeLock = Any()

    fun init(context: Context) = init(context, Prefs(context))

    /** Test seam: lets JVM tests hand in a [Prefs] built on a fake
     *  [com.playtranslate.security.SecretCodec] (AndroidKeyStore is
     *  instrumented-only). Re-runs load, so tests can re-init after
     *  seeding SharedPreferences. */
    internal fun init(context: Context, prefs: Prefs) {
        this.prefs = prefs
        this.sp = context.applicationContext
            .getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        migrateLegacyServices()
        snapshot = load()
    }

    fun all(): List<OnlineServiceInstance> = snapshot

    fun byId(id: String): OnlineServiceInstance? = snapshot.firstOrNull { it.id == id }

    /** New instances go to the TOP of the list — highest online priority.
     *  A freshly-added paid service that landed below the always-usable
     *  Lingva would never win a translation until manually reordered,
     *  which reads as "I added OpenAI and nothing changed". */
    fun add(instance: OnlineServiceInstance) = mutate { listOf(instance) + it }

    fun update(instance: OnlineServiceInstance) =
        mutate { list -> list.map { if (it.id == instance.id) instance else it } }

    /** Removes the instance and its encrypted key slot. Callers own the
     *  registry/cooldown/usage cleanup for the id. */
    fun remove(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        prefs.clearInstanceSecret(keySlot(id))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        mutate { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    /** Reorders to [orderedIds]; instances missing from it (stale caller
     *  list) are appended rather than silently dropped. */
    fun reorder(orderedIds: List<String>) = mutate { list ->
        val byId = list.associateBy { it.id }
        orderedIds.mapNotNull { byId[it] } + list.filter { it.id !in orderedIds }
    }

    /** The encrypted SharedPreferences slot holding this instance's API
     *  key. Legacy ids map to their pre-refactor slots (see
     *  [LEGACY_SLOTS]); everything else gets a namespaced slot. */
    fun keySlot(id: String): String = LEGACY_SLOTS[id] ?: "svc_api_key_$id"

    /** Only the migrated DeepL instance keeps the personal-build baked
     *  key as its absent-slot bootstrap — same semantics as the legacy
     *  [Prefs.deeplApiKey] getter. */
    fun readKey(id: String): String =
        prefs.readInstanceSecret(keySlot(id), if (id == "deepl") BuildConfig.DEEPL_API_KEY else "")

    fun writeKey(id: String, value: String) = prefs.writeInstanceSecret(keySlot(id), value)

    private inline fun mutate(transform: (List<OnlineServiceInstance>) -> List<OnlineServiceInstance>) {
        synchronized(writeLock) {
            val next = transform(snapshot)
            snapshot = next
            sp.edit { putString(KEY_INSTANCES, PtJson.lenient.encodeToString(next)) }
        }
    }

    private fun load(): List<OnlineServiceInstance> {
        val json = sp.getString(KEY_INSTANCES, null) ?: return defaultList()
        return try {
            PtJson.lenient.decodeFromString<List<OnlineServiceInstance>>(json)
        } catch (e: Exception) {
            // Same recovery posture as Prefs.getRegionList: a corrupt blob
            // falls back to the fresh-install default rather than wedging
            // every launch. Key slots are separate prefs, so a re-add
            // restores a service without re-entering its key... the slot
            // is only cleared by an explicit delete.
            Log.e(TAG, "Corrupt $KEY_INSTANCES — falling back to default", e)
            defaultList()
        }
    }

    /** Fresh-install baseline: the free, keyless Lingva service, on. */
    private fun defaultList() = listOf(
        OnlineServiceInstance(id = "lingva", type = ServiceType.LINGVA, enabled = true),
    )

    /**
     * One-shot migration from the legacy one-per-service scalar prefs.
     * Emission order preserves the legacy waterfall (Gemini, OpenAI,
     * DeepSeek, DeepL, Lingva — priorities 7/8/9/10/20). Any service
     * with a saved token migrates, enabled or not (a disabled cell keeps
     * its key, exactly like the legacy toggles did); Lingva has no token
     * and always migrates carrying its enabled flag. Legacy DeepSeek
     * becomes an OPENAI-type instance with the DEEPSEEK preset.
     *
     * The legacy scalar prefs are left in place: the key slots are
     * actively reused (see [LEGACY_SLOTS]), and a downgrade to a
     * pre-instance build finds its scalars intact.
     */
    private fun migrateLegacyServices() {
        if (sp.contains(KEY_INSTANCES) || sp.getBoolean(KEY_MIGRATED, false)) return
        val migrated = buildList {
            if (prefs.geminiApiKey.isNotBlank()) add(
                OnlineServiceInstance(
                    id = "gemini",
                    type = ServiceType.GEMINI,
                    enabled = prefs.geminiEnabled,
                    model = prefs.geminiModel,
                )
            )
            if (prefs.openaiApiKey.isNotBlank()) add(
                OnlineServiceInstance(
                    id = "openai",
                    type = ServiceType.OPENAI,
                    enabled = prefs.openaiEnabled,
                    model = prefs.openaiModel,
                    preset = if (prefs.isCustomOpenaiBaseUrl) OpenAiPreset.CUSTOM else OpenAiPreset.OPENAI,
                    baseUrl = prefs.openaiBaseUrl,
                )
            )
            if (prefs.deepseekApiKey.isNotBlank()) add(
                OnlineServiceInstance(
                    id = "deepseek",
                    type = ServiceType.OPENAI,
                    enabled = prefs.deepseekEnabled,
                    model = prefs.deepseekModel,
                    preset = OpenAiPreset.DEEPSEEK,
                    baseUrl = DEEPSEEK_BASE_URL,
                )
            )
            if (prefs.deeplApiKey.isNotBlank()) add(
                OnlineServiceInstance(
                    id = "deepl",
                    type = ServiceType.DEEPL,
                    enabled = prefs.deeplEnabled,
                )
            )
            add(
                OnlineServiceInstance(
                    id = "lingva",
                    type = ServiceType.LINGVA,
                    enabled = prefs.lingvaEnabled,
                )
            )
        }
        sp.edit {
            putString(KEY_INSTANCES, PtJson.lenient.encodeToString(migrated))
            putBoolean(KEY_MIGRATED, true)
        }
    }
}
