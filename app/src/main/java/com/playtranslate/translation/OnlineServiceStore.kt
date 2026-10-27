package com.playtranslate.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

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

    /** Bilibili's Index-Translate endpoint: chat-completions and /models
     *  under /v1, free and keyless ([OpenAiPreset.INDEX_TRANSLATE] needs no
     *  account). Bilibili's own client sends no Authorization header and
     *  switches thinking off through `chat_template_kwargs`
     *  ([OnlineBackendFactory.requestExtrasFor]). A maintainer gave its
     *  public limits as 32K input and 4K output tokens; no rate limit is
     *  published. */
    const val INDEX_TRANSLATE_BASE_URL = "https://index-translate.bilibili.com/v1"

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

    /** Told after every write to the instance list, outside [writeLock], on
     *  the writing thread: the main thread, since every write is an
     *  [OnlineServiceMutations] recipe. The config page listens while it is
     *  resumed, so a provider's thinking report that lands while it is open
     *  redraws its row. Copy-on-write, like [snapshot], so a listener
     *  can remove itself while being told. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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

    /** Stores what [reported]'s provider said about its model, if [id] still
     *  has that model: a report that lands after the user picked another
     *  model writes nothing. Reached only through
     *  [OnlineServiceMutations.applyReportedThinking]. */
    fun setReportedThinking(id: String, reported: ReportedThinking) = mutate { list ->
        list.map {
            if (it.id == id && OnlineBackendFactory.modelOf(it) == reported.model) it.copy(reportedThinking = reported)
            else it
        }
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

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private inline fun mutate(transform: (List<OnlineServiceInstance>) -> List<OnlineServiceInstance>) {
        synchronized(writeLock) {
            val next = transform(snapshot)
            snapshot = next
            sp.edit { putString(KEY_INSTANCES, PtJson.lenient.encodeToString(next)) }
        }
        listeners.forEach { it() }
    }

    private fun load(): List<OnlineServiceInstance> {
        val json = sp.getString(KEY_INSTANCES, null) ?: return defaultList()
        return try {
            PtJson.lenient.decodeFromJsonElement<List<OnlineServiceInstance>>(
                carryLegacyThinking(PtJson.lenient.parseToJsonElement(json)),
            )
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

    /** DeepSeek's first Thinking switch (2026-10-20) saved its on as
     *  `thinking: true`, which sent thinking enabled at DeepSeek's default
     *  effort, high. A record with that key and no pick of its own takes
     *  [ReasoningLevel.HIGH], the pick that sends the same request once
     *  DeepSeek has reported its levels ([ReportedThinkingRefresher] asks at
     *  launch; until then every DeepSeek request is thinking off). On any
     *  other preset it reads as that preset's start state until the user
     *  returns to DeepSeek. The next write drops the old key;
     *  `thinking: false` was the start state and needs nothing. */
    private fun carryLegacyThinking(list: JsonElement): JsonElement {
        if (list !is JsonArray) return list
        return JsonArray(list.map { record ->
            val legacyOn = ((record as? JsonObject)?.get("thinking") as? JsonPrimitive)?.booleanOrNull == true
            if (!legacyOn || "reasoning" in record.jsonObject) record
            else JsonObject(record.jsonObject + ("reasoning" to JsonPrimitive(ReasoningLevel.HIGH.name)))
        })
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
