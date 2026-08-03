package com.playtranslate

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import com.playtranslate.BuildConfig
import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.security.SecretCipher
import com.playtranslate.security.SecretCodec
import com.playtranslate.ui.AccentColor
import com.playtranslate.ui.CaptureResultGeometry
import com.playtranslate.ui.CardMode
import com.playtranslate.ui.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Which overlay the live-mode loop and hold-to-preview gesture should render
 * when the user doesn't force a specific mode via hotkey. Persisted as the
 * enum *name* (not ordinal) so the stored value survives future enum edits;
 * the old ordinal-based `auto_translation_mode` pref is handled in
 * [Prefs.migrateLegacyPrefs].
 */
enum class OverlayMode(@androidx.annotation.StringRes val displayNameRes: Int) {
    TRANSLATION(R.string.overlay_mode_option_translation),
    FURIGANA(R.string.overlay_mode_option_furigana);

    companion object {
        fun fromStorageName(name: String?): OverlayMode =
            entries.find { it.name == name } ?: TRANSLATION
    }
}

/**
 * Which per-surface OCR engine selection a picker action persists to:
 * the GLOBAL selection (over-game/live capture and the settings screen's
 * own rows) or a tool's inherit-until-set override ([Prefs.cameraOcrBackendToken],
 * [Prefs.importOcrBackendToken]). Travels through the OCR download deep link
 * so the on-success write lands in the scope that initiated it — a tool-only
 * action must never switch the live engine as a side effect.
 */
enum class OcrTokenScope { GLOBAL, CAMERA, IMPORT }

/** A named capture region expressed as fractions of the screen dimensions. */
data class RegionEntry(
    val label: String,
    val top: Float,
    val bottom: Float,
    val left: Float = 0f,
    val right: Float = 1f,
    val id: String = java.util.UUID.randomUUID().toString()
) {
    val isFullScreen: Boolean get() = top <= 0f && bottom >= 1f && left <= 0f && right >= 1f

    /** User-facing name for this region. An unnamed region carries an empty
     *  [label] sentinel — the full-screen default, a region drawn on the fly,
     *  or a custom region saved without a name — and resolves to the localized
     *  "Full screen" / "Capture region" here, so callers that describe a
     *  region to the user don't each have to remember the fallback. */
    fun displayName(context: Context): String = label.ifEmpty {
        context.getString(
            if (isFullScreen) R.string.region_default_full_screen
            else R.string.region_default_capture
        )
    }
}

/** Floating-icon snap position for a single display. [edge] encoding:
 *  0=LEFT, 1=RIGHT, 2=TOP, 3=BOTTOM. */
data class IconPosition(val edge: Int, val fraction: Float) {
    companion object {
        /** Default icon placement: right edge, vertically centered. Used by
         *  [Prefs.iconPositionForDisplay] for displays the user hasn't
         *  positioned an icon on yet. */
        val DEFAULT = IconPosition(edge = 1, fraction = 0.5f)
    }
}

/**
 * Simple wrapper around [SharedPreferences] for persisting user settings.
 */
class Prefs internal constructor(
    context: Context,
    private val codec: SecretCodec,
) {

    /** Production constructor — always the AndroidKeyStore-backed [SecretCipher].
     *  The [codec] seam on the primary constructor exists only so JVM tests can
     *  inject a fake (AndroidKeyStore is instrumented-only). */
    constructor(context: Context) : this(context, SecretCipher)

    private val sp: SharedPreferences =
        context.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    init {
        // Upgrade-time migration runs on every Prefs construction so any
        // read (including from PlayTranslateAccessibilityService.onServiceConnected,
        // which can fire before MainActivity ever runs) sees post-migration
        // values. Idempotent and cheap (sp.contains() lookups) once migration
        // has actually executed on a device.
        migrateLegacyPrefs()
    }

    /**
     * Cold [Flow] that emits once immediately (seeding the current state) and
     * again on every change to any of [keys] — plus on a null-key broadcast,
     * which `clear()` and some OEMs send. Emits [Unit]: collectors re-read the
     * relevant typed getter on each tick, so a single signal covers any number
     * of observed keys. Pair with `.map { … }.distinctUntilChanged()` to drive
     * a settings ViewModel's UiState reactively (prefs are the source of truth;
     * the VM is a projection).
     *
     * The listener is held in a local `val` for the flow's lifetime —
     * [SharedPreferences] keeps only a weak reference to listeners — and is
     * removed in [awaitClose]. It registers on the process-global
     * `playtranslate_prefs` store, so it observes writes from any [Prefs]
     * instance or process component.
     */
    fun observe(vararg keys: String): Flow<Unit> = callbackFlow {
        val watched = keys.toHashSet()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed in watched) trySend(Unit)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit) // seed with the current state
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, TranslateLanguage.JAPANESE) ?: TranslateLanguage.JAPANESE
        set(v) = sp.edit { putString(KEY_SOURCE_LANG, v) }

    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = sp.edit { putString(KEY_TARGET_LANG, v) }

    /**
     * The Chinese script variant for target output, meaningful only when
     * [targetLang] == "zh" (Chinese). All four variants share that one backend
     * code; this picks Simplified vs Traditional/TW/HK, applied as a render-time
     * OpenCC transform. Stored as [ChineseScriptVariant.code]; unknown/unset →
     * [ChineseScriptVariant.SIMPLIFIED] so existing `targetLang="zh"` users and
     * every non-Chinese target read as Simplified (a no-op). Write it together
     * with [targetLang] on selection; reset to Simplified when leaving Chinese.
     */
    var targetChineseVariant: ChineseScriptVariant
        get() = ChineseScriptVariant.fromCode(sp.getString(KEY_TARGET_CHINESE_VARIANT, null))
        set(v) = sp.edit { putString(KEY_TARGET_CHINESE_VARIANT, v.code) }

    /** The current (source, target, variant) translation context — what a freshly
     *  produced result is translated under. [sourceOverride] pins the source for a
     *  re-OCR / pinned path; otherwise the current [sourceLangId] is used. */
    fun langContext(sourceOverride: SourceLangId? = null): TranslationLangContext =
        TranslationLangContext(sourceOverride ?: sourceLangId, targetLang, targetChineseVariant)

    /** True iff the user has explicitly picked a target language at least once.
     *  The [targetLang] getter returns an English fallback for unsaved values,
     *  but this key-presence check is the cleanest signal for the onboarding
     *  gate: the key is only written by [LanguageSetupActivity.onTargetSelected]. */
    val hasTargetLangBeenSet: Boolean
        get() = sp.contains(KEY_TARGET_LANG)

    /**
     * Profile-aware view of [sourceLang]. Derives a [SourceLangId] from the raw
     * ML Kit code; falls back to [SourceLangId.JA] on unknown/blank values and
     * logs a warning on non-blank fallback so any future language-code
     * mismatch is visible in the log-export pipeline (e.g. a user downgrading
     * from a Phase 3 build with `sourceLang = "en"` stored to a Phase 1 build
     * that only knows JA).
     */
    val sourceLangId: SourceLangId
        get() {
            val raw = sourceLang
            val resolved = SourceLangId.fromCode(raw)
            if (resolved == null && raw.isNotBlank()) {
                android.util.Log.w("Prefs", "sourceLangId fallback to JA (raw=\"$raw\")")
            }
            return resolved ?: SourceLangId.JA
        }

    // ── Per-language OCR engine choice (production) ─────────────────────────
    /** Coarse selection token ("mlkit"/"meiki"/"paddle") for [id]'s OCR engine, or
     *  null when the user hasn't chosen (→ ML Kit floor). Written during language
     *  consolidation (default = top priority) and the Settings OCR section;
     *  resolved by `OcrModelManager.selectedBackend`. */
    fun ocrBackendToken(id: SourceLangId): String? = sp.getString("ocr_backend_${id.code}", null)
    fun setOcrBackendToken(id: SourceLangId, token: String) =
        sp.edit { putString("ocr_backend_${id.code}", token) }
    fun clearOcrBackendToken(id: SourceLangId) = sp.edit { remove("ocr_backend_${id.code}") }

    /** The slow-OCR rescue prompt was answered for [id] — either way, it
     *  never shows again for that language (the OCR picker is the standing
     *  change-your-mind path). This is LIVE/over-game capture's latch; the
     *  camera tool keeps its own ([cameraSlowOcrPromptAnswered]) because the
     *  decision must scope with the engine selection it changes. */
    fun slowOcrPromptAnswered(id: SourceLangId): Boolean =
        sp.getBoolean("slow_ocr_prompt_answered_${id.code}", false)
    fun setSlowOcrPromptAnswered(id: SourceLangId) =
        sp.edit { putBoolean("slow_ocr_prompt_answered_${id.code}", true) }

    /** Camera-scoped twin of [slowOcrPromptAnswered]: the camera tool's
     *  rescue prompt was answered for [id]. Separate state so accepting the
     *  camera's rescue (which switches only [cameraOcrBackendToken]) can't
     *  silence live mode's own offer for its still-slow global engine, and
     *  vice versa. */
    fun cameraSlowOcrPromptAnswered(id: SourceLangId): Boolean =
        sp.getBoolean("camera_slow_ocr_prompt_answered_${id.code}", false)
    fun setCameraSlowOcrPromptAnswered(id: SourceLangId) =
        sp.edit { putBoolean("camera_slow_ocr_prompt_answered_${id.code}", true) }

    /** Import-tool twin of [cameraSlowOcrPromptAnswered], same scoping
     *  rationale: the import rescue switches only [importOcrBackendToken],
     *  so its answered-latch must scope with that selection. */
    fun importSlowOcrPromptAnswered(id: SourceLangId): Boolean =
        sp.getBoolean("import_slow_ocr_prompt_answered_${id.code}", false)
    fun setImportSlowOcrPromptAnswered(id: SourceLangId) =
        sp.edit { putBoolean("import_slow_ocr_prompt_answered_${id.code}", true) }

    /** The user's preferred TTS voice for [lang], by [android.speech.tts.Voice]
     *  name, or null to use the engine default. Voices are stored per language
     *  because a voice is locale-specific. */
    fun ttsVoiceName(lang: SourceLangId): String? =
        sp.getString("tts_voice_${lang.code}", null)

    fun setTtsVoiceName(lang: SourceLangId, voiceName: String?) {
        sp.edit {
            if (voiceName == null) remove("tts_voice_${lang.code}")
            else putString("tts_voice_${lang.code}", voiceName)
        }
    }

    /**
     * Set of displays the user has selected to translate. Insertion order
     * is preserved (LinkedHashSet) so "primary" disambiguators (hotkey
     * routing fallback, single-display call sites' `firstOrNull()`) are
     * deterministic.
     *
     * Pre-multi-display installs stored a single Int under [KEY_DISPLAY_ID].
     * The migration in [migrateLegacyPrefs] converts that to the new
     * [KEY_DISPLAY_IDS] CSV; the getter falls back to reading the legacy key
     * directly so a fresh-install / pre-migration read still returns
     * something sensible.
     */
    var captureDisplayIds: Set<Int>
        get() {
            val csv = sp.getString(KEY_DISPLAY_IDS, null)
            if (csv.isNullOrEmpty()) {
                return linkedSetOf(sp.getInt(KEY_DISPLAY_ID, 0))
            }
            return csv.split(",").mapNotNull { it.toIntOrNull() }
                .toCollection(LinkedHashSet())
        }
        set(v) {
            sp.edit { putString(KEY_DISPLAY_IDS, v.joinToString(",")) }
        }

    /** True iff the user (or the legacy-key migration in [migrateLegacyPrefs])
     *  has explicitly written the multi-display selection key. The
     *  [captureDisplayIds] getter always returns a non-empty set thanks to
     *  legacy-key fallback + DEFAULT_DISPLAY default, so the public Set value
     *  can't distinguish "user picked display 0" from "no selection ever made."
     *  This key-presence check is the clean signal for the auto-detect gate
     *  in `MainActivity.ensureConfigured`: only seed an auto-detected display
     *  when there's no persisted selection to clobber.
     *
     *  Pre-multi-display upgrade users get this set to true by
     *  [migrateLegacyPrefs], which writes [KEY_DISPLAY_IDS] from the legacy
     *  [KEY_DISPLAY_ID] before any code path can hit the auto-detect branch
     *  (migration runs from the [Prefs] init block on every construction). */
    val hasDisplaySelection: Boolean
        get() = sp.contains(KEY_DISPLAY_IDS)

    /**
     * Per-display selected region id, or empty string if [displayId] has no
     * entry yet — callers treat empty as "use the full-screen default" (see
     * [CaptureService.activeRegionForDisplay] and [primaryDisplayRegion]).
     * The region LIST itself ([getRegionList]) stays shared across displays —
     * region fractions are display-portable.
     */
    fun selectedRegionIdForDisplay(displayId: Int): String {
        val map = readSelectedRegionMap()
        return map[displayId] ?: ""
    }

    fun setSelectedRegionIdForDisplay(displayId: Int, id: String) {
        val map = readSelectedRegionMap().toMutableMap()
        map[displayId] = id
        writeSelectedRegionMap(map)
    }

    private fun readSelectedRegionMap(): Map<Int, String> {
        val json = sp.getString(KEY_SELECTED_REGION_BY_DISPLAY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val id = key.toIntOrNull() ?: return@forEach
                    put(id, obj.getString(key))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeSelectedRegionMap(map: Map<Int, String>) {
        val obj = JSONObject()
        for ((id, regionId) in map) obj.put(id.toString(), regionId)
        sp.edit { putString(KEY_SELECTED_REGION_BY_DISPLAY, obj.toString()) }
    }

    /**
     * Per-display floating-icon snap position, or [IconPosition.DEFAULT]
     * (right edge, vertically centered) for displays that don't have their
     * own entry yet.
     */
    fun iconPositionForDisplay(displayId: Int): IconPosition {
        val map = readIconPositionMap()
        return map[displayId] ?: IconPosition.DEFAULT
    }

    fun setIconPositionForDisplay(displayId: Int, position: IconPosition) {
        val map = readIconPositionMap().toMutableMap()
        map[displayId] = position
        writeIconPositionMap(map)
    }

    private fun readIconPositionMap(): Map<Int, IconPosition> {
        val json = sp.getString(KEY_ICON_POSITION_BY_DISPLAY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val id = key.toIntOrNull() ?: return@forEach
                    val entry = obj.getJSONObject(key)
                    put(
                        id, IconPosition(
                            edge = entry.getInt("edge"),
                            fraction = entry.getDouble("fraction").toFloat(),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeIconPositionMap(map: Map<Int, IconPosition>) {
        val obj = JSONObject()
        for ((id, pos) in map) {
            obj.put(id.toString(), JSONObject().apply {
                put("edge", pos.edge)
                put("fraction", pos.fraction.toDouble())
            })
        }
        sp.edit { putString(KEY_ICON_POSITION_BY_DISPLAY, obj.toString()) }
    }

    /**
     * Persistent counterpart to [CaptureService.activeRegion]: resolves the
     * region for the first id in [captureDisplayIds] from the per-display
     * selection map, falling back to the first list entry (full screen).
     * Use as the in-app UI fallback when [CaptureService] isn't bound yet
     * (e.g., the initial render before onServiceConnected) — once the
     * service is up, prefer its `activeRegion` so `lastInteractedDisplayId`
     * can steer the answer.
     */
    fun primaryDisplayRegion(): RegionEntry {
        val list = getRegionList()
        val primaryId = captureDisplayIds.firstOrNull() ?: android.view.Display.DEFAULT_DISPLAY
        val id = selectedRegionIdForDisplay(primaryId)
        return if (id.isNotEmpty()) list.find { it.id == id } ?: list.first() else list.first()
    }

    // ── Encrypted secret storage (online-backend API keys) ───────────────
    // The four API-key fields below are encrypted at rest through [codec]
    // (AndroidKeyStore AES-GCM in production), each value bound to its
    // preference key as AAD so a ciphertext can't be moved between slots. The
    // [default] (a baked BuildConfig key for DeepL, "" otherwise) is the
    // bootstrap value used ONLY when the key is genuinely absent. Two other
    // paths deliberately do NOT fall through to it: an explicitly-cleared key
    // is stored as a literal "" (never removed), and a present-but-
    // undecryptable key reads as "" (fail-closed) — so a lost/rotated keystore
    // key, or a blob shuffled between slots, can never silently substitute the
    // baked DeepL key for the one the user stored.

    @VisibleForTesting
    internal fun readSecret(key: String, default: String): String {
        val stored = sp.getString(key, null) ?: return default   // absent → bootstrap default
        if (stored.isEmpty()) return ""                           // explicitly cleared → no key
        return codec.decrypt(key, stored) ?: ""                   // unreadable/wrong-slot → no key, NOT default
    }

    private fun writeSecret(key: String, value: String) = sp.edit {
        if (value.isEmpty()) putString(key, "")
        // Fail-closed: if encryption is unavailable, persist nothing rather
        // than fall back to plaintext (and leave any existing value intact).
        else putString(key, codec.encrypt(key, value) ?: return@edit)
    }

    // ── Per-instance key slots (multi-instance online services) ──────────
    // [com.playtranslate.translation.OnlineServiceStore] owns which slot
    // belongs to which service instance; these expose the same
    // codec-encrypted read/write path for arbitrary slot names. The AAD
    // binding still holds per slot — a blob can't be moved between
    // instances any more than between the legacy fixed slots.

    internal fun readInstanceSecret(slot: String, default: String = ""): String =
        readSecret(slot, default)

    internal fun writeInstanceSecret(slot: String, value: String) = writeSecret(slot, value)

    /** Removes the slot outright (instance deleted) — distinct from
     *  writing "", which means "key explicitly cleared but slot alive". */
    internal fun clearInstanceSecret(slot: String) = sp.edit { remove(slot) }

    /**
     * Re-encrypt any pre-existing plaintext API keys in place, exactly once.
     * Runs first in [migrateLegacyPrefs], before any property read.
     *
     * Atomic by construction: every ciphertext is staged in memory and
     * committed together with the done-marker in a single [edit]. Either all
     * present keys encrypt and commit (with the marker), or nothing is written
     * and it retries cleanly next launch — so no partial or double-encryption
     * state is reachable.
     *
     * Serialized on [SECRET_MIGRATION_LOCK]: [Prefs] is constructed from many
     * components, some off the main thread (the translation key-providers run
     * on Dispatchers.IO), so two first-launch constructors could otherwise both
     * pass the marker check and the second could read a key the first already
     * encrypted and double-encrypt it. The lock is process-wide (companion)
     * because [Prefs] is not a singleton; the in-lock recheck is standard
     * double-checked locking. With it, "every stored value is plaintext" holds
     * whenever the marker is absent, so no ciphertext detection is needed.
     */
    private fun migrateSecretsToEncrypted() {
        if (sp.contains(KEY_SECRETS_ENCRYPTED_MIGRATED)) return
        synchronized(SECRET_MIGRATION_LOCK) {
            if (sp.contains(KEY_SECRETS_ENCRYPTED_MIGRATED)) return
            val updates = HashMap<String, String>()
            for (key in listOf(KEY_DEEPL_KEY, KEY_GEMINI_KEY, KEY_OPENAI_KEY, KEY_DEEPSEEK_KEY)) {
                val raw = sp.getString(key, null)?.takeIf { it.isNotEmpty() } ?: continue
                val enc = codec.encrypt(key, raw) ?: return
                updates[key] = enc
            }
            sp.edit {
                updates.forEach { (key, enc) -> putString(key, enc) }
                putBoolean(KEY_SECRETS_ENCRYPTED_MIGRATED, true)
            }
        }
    }

    /**
     * DeepL API key.  Defaults to the value baked into the build via
     * local.properties (your personal device build).  Empty on distributed
     * builds — user must enter their own key in Settings.
     */
    var deeplApiKey: String
        get() = readSecret(KEY_DEEPL_KEY, BuildConfig.DEEPL_API_KEY)
        set(v) = writeSecret(KEY_DEEPL_KEY, v)

    /** User's explicit "use DeepL?" toggle. Independent of [deeplApiKey]
     *  presence — disabling DeepL preserves the saved key so a later
     *  re-enable can prepopulate the entry field. Default false; the
     *  one-time migration in [migrateLegacyPrefs] flips this to true on
     *  first launch for users who already had a stored key. */
    var deeplEnabled: Boolean
        get() = sp.getBoolean(KEY_DEEPL_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_DEEPL_ENABLED, v) }

    /** User's explicit "use Lingva?" toggle. Default true so out-of-the-box
     *  the free online backend is on. */
    var lingvaEnabled: Boolean
        get() = sp.getBoolean(KEY_LINGVA_ENABLED, true)
        set(v) = sp.edit { putBoolean(KEY_LINGVA_ENABLED, v) }

    /** "Use Firefox Translations (Bergamot) offline?" toggle. Default true so the
     *  fast offline NMT tier is the default replacement for ML Kit. Existing
     *  users still see the Settings toggle OFF until a model is downloaded, since
     *  the row's checked state is (enabled && model-for-current-pair installed). */
    var bergamotEnabled: Boolean
        get() = sp.getBoolean(KEY_BERGAMOT_ENABLED, true)
        set(v) = sp.edit { putBoolean(KEY_BERGAMOT_ENABLED, v) }

    /** Gemini API key from AI Studio (https://aistudio.google.com/app/apikey).
     *  Empty by default — users must enter their own key in Settings. */
    var geminiApiKey: String
        get() = readSecret(KEY_GEMINI_KEY, "")
        set(v) = writeSecret(KEY_GEMINI_KEY, v)

    /** User's explicit "use Gemini?" toggle. Independent of [geminiApiKey]
     *  presence — disabling Gemini preserves the saved key so a later
     *  re-enable can prepopulate the entry field. Default false; no
     *  auto-enable migration (unlike DeepL) since Gemini is a paid API
     *  the user must opt into deliberately. */
    var geminiEnabled: Boolean
        get() = sp.getBoolean(KEY_GEMINI_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_GEMINI_ENABLED, v) }

    /** Gemini model id. The picker in Settings stores a curated id; the
     *  "Custom…" entry persists any user-typed string. */
    var geminiModel: String
        get() = sp.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        set(v) = sp.edit { putString(KEY_GEMINI_MODEL, v) }

    /** OpenAI API key from https://platform.openai.com/api-keys. */
    var openaiApiKey: String
        get() = readSecret(KEY_OPENAI_KEY, "")
        set(v) = writeSecret(KEY_OPENAI_KEY, v)

    /** User's explicit "use OpenAI?" toggle. See [geminiEnabled] for the
     *  no-auto-enable rationale. */
    var openaiEnabled: Boolean
        get() = sp.getBoolean(KEY_OPENAI_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_OPENAI_ENABLED, v) }

    /** OpenAI model id; "Custom…" entry persists arbitrary strings. */
    var openaiModel: String
        get() = sp.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(v) = sp.edit { putString(KEY_OPENAI_MODEL, v) }

    /** OpenAI base URL. Plaintext (not a secret) — mirrors [openaiModel],
     *  NOT the encrypted key path. Defaults to the canonical endpoint; the
     *  ADVANCED section of the OpenAI settings sub-screen lets users point
     *  the backend at any OpenAI-compatible service (proxy/gateway,
     *  OpenRouter, LM Studio, Ollama, self-hosted llama.cpp). */
    var openaiBaseUrl: String
        get() = sp.getString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL
        set(v) = sp.edit { putString(KEY_OPENAI_BASE_URL, v) }

    // ── User-edited LLM prompt templates (Advanced LLM Configuration) ──────
    // null = no override → the built-in default in
    // [com.playtranslate.translation.llm.LlmPromptTemplates] applies, so
    // default improvements keep flowing to users who never edited. The
    // editor stores null when a save equals the default. Raw templates with
    // `{tokens}`, global across all eligible LLM backends. Plaintext — not
    // secrets. Setter remove()s on null (setTtsVoiceName idiom).

    var llmSystemPrompt: String?
        get() = sp.getString(KEY_LLM_SYSTEM_PROMPT, null)
        set(v) = sp.edit {
            if (v == null) remove(KEY_LLM_SYSTEM_PROMPT) else putString(KEY_LLM_SYSTEM_PROMPT, v)
        }

    var llmTranslationPrompt: String?
        get() = sp.getString(KEY_LLM_TRANSLATION_PROMPT, null)
        set(v) = sp.edit {
            if (v == null) remove(KEY_LLM_TRANSLATION_PROMPT) else putString(KEY_LLM_TRANSLATION_PROMPT, v)
        }

    var llmBatchPrompt: String?
        get() = sp.getString(KEY_LLM_BATCH_PROMPT, null)
        set(v) = sp.edit {
            if (v == null) remove(KEY_LLM_BATCH_PROMPT) else putString(KEY_LLM_BATCH_PROMPT, v)
        }

    /** Feed the last few translated lines to LLM-tier backends as `{context}`
     *  in the Translation prompt (Translation Services → Advanced LLM
     *  Configuration). Ephemeral in-memory pairs only — independent of
     *  [translationHistoryEnabled]; see
     *  [com.playtranslate.translationlog.TranslationLogRecorder]. */
    var llmContextEnabled: Boolean
        get() = sp.getBoolean(KEY_LLM_CONTEXT_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_LLM_CONTEXT_ENABLED, v) }

    /** Persist translated lines to the on-device History store (Settings →
     *  Tools → History; the master switch lives on that screen). */
    var translationHistoryEnabled: Boolean
        get() = sp.getBoolean(KEY_TRANSLATION_HISTORY_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_TRANSLATION_HISTORY_ENABLED, v) }

    /** Save one image per capture session alongside History rows (camera
     *  and screen captures only — never auto/live). Sub-toggle under the
     *  History master switch; meaningless while
     *  [translationHistoryEnabled] is off. */
    var captureImageHistoryEnabled: Boolean
        get() = sp.getBoolean(KEY_CAPTURE_IMAGE_HISTORY_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_CAPTURE_IMAGE_HISTORY_ENABLED, v) }

    /** Drop the translation line from the History LIST rows so only the
     *  captured text shows, and copy the source alone when a row is
     *  long-pressed — tapping through to the entry stays the way to see the
     *  translation, and nothing about recording changes. Display-only, so
     *  independent of [translationHistoryEnabled]: rows captured earlier
     *  still render while recording is off. */
    var historyHideTranslations: Boolean
        get() = sp.getBoolean(KEY_HISTORY_HIDE_TRANSLATIONS, false)
        set(v) = sp.edit { putBoolean(KEY_HISTORY_HIDE_TRANSLATIONS, v) }

    /** True when [openaiBaseUrl] points somewhere other than the canonical
     *  OpenAI endpoint (trailing-slash / whitespace insensitive). The single
     *  definition of "is this still real OpenAI?" — drives the model-list
     *  owned_by filter (custom endpoints tag models with their own org, so
     *  OpenAI's first-party filter would empty the picker on them). */
    val isCustomOpenaiBaseUrl: Boolean
        get() = openaiBaseUrl.trim().trimEnd('/') != DEFAULT_OPENAI_BASE_URL.trimEnd('/')

    /** DeepSeek API key from https://platform.deepseek.com/api_keys. */
    var deepseekApiKey: String
        get() = readSecret(KEY_DEEPSEEK_KEY, "")
        set(v) = writeSecret(KEY_DEEPSEEK_KEY, v)

    /** User's explicit "use DeepSeek?" toggle. Default false; explicit
     *  opt-in like every other paid LLM backend. */
    var deepseekEnabled: Boolean
        get() = sp.getBoolean(KEY_DEEPSEEK_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_DEEPSEEK_ENABLED, v) }

    /** DeepSeek model id. The picker fetches the live list from
     *  api.deepseek.com/v1/models; "Custom…" persists arbitrary strings. */
    var deepseekModel: String
        get() = sp.getString(KEY_DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL) ?: DEFAULT_DEEPSEEK_MODEL
        set(v) = sp.edit { putString(KEY_DEEPSEEK_MODEL, v) }

    /** User-controlled toggle for the MNN-backed Qwen 2.5 1.5B (live-mode tier).
     *  Default false — Settings flips this on after a successful download or
     *  when the user enables an already-extracted install. File existence is
     *  checked separately via
     *  [com.playtranslate.translation.qwen.QwenMnnModel.isInstalled]. */
    var qwenMnnEnabled: Boolean
        get() = sp.getBoolean(KEY_QWEN_MNN_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_QWEN_MNN_ENABLED, v) }

    /** User toggle for the MNN-backed Qwen 3.5 2B — the fast on-device tier
     *  that replaces the deprecated Qwen 2.5 1.5B ([qwenMnnEnabled]). Default
     *  false; same download/enable/disable semantics. */
    var qwen35Mnn2bEnabled: Boolean
        get() = sp.getBoolean(KEY_QWEN35_MNN_2B_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_QWEN35_MNN_2B_ENABLED, v) }

    /** User-controlled toggle for the MNN-backed Gemma 4 E2B (premium-quality
     *  manual-lookup tier — replaces the legacy TranslateGemma 4B). Default
     *  false; same enable/disable semantics as [qwenMnnEnabled]. File
     *  existence checked via
     *  [com.playtranslate.translation.gemma.GemmaE2BMnnModel.isInstalled]. */
    var gemmaE2bEnabled: Boolean
        get() = sp.getBoolean(KEY_GEMMA_E2B_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_GEMMA_E2B_ENABLED, v) }

    /** User-controlled toggle for the MNN-backed Hunyuan-MT 1.5 1.8B —
     *  translation-specialist tier (Tencent HY Community License, restricted
     *  to outside EU/UK/SK; gated by [com.playtranslate.region.RegionPolicy]
     *  before the Settings row is even shown). Default false; same
     *  enable/disable semantics as [qwenMnnEnabled]. File existence checked
     *  via [com.playtranslate.translation.hymt.HyMtModel.isInstalled]. */
    var hyMtEnabled: Boolean
        get() = sp.getBoolean(KEY_HYMT_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_HYMT_ENABLED, v) }

    /** Persisted acknowledgement of the Hunyuan-MT 1.5 click-through legal
     *  attestation dialog. Set to true after the user taps "Agree" the first
     *  time they enable [hyMtEnabled]; subsequent enables skip the dialog.
     *  Mirrors how Meta handles Llama ToS acceptance: one-time, persisted. */
    var hyMtLegalAccepted: Boolean
        get() = sp.getBoolean(KEY_HYMT_LEGAL_ACCEPTED, false)
        set(v) = sp.edit { putBoolean(KEY_HYMT_LEGAL_ACCEPTED, v) }

    var ankiDeckId: Long
        get() = sp.getLong(KEY_ANKI_DECK_ID, -1L)
        set(v) = sp.edit { putLong(KEY_ANKI_DECK_ID, v) }

    var ankiDeckName: String
        get() = sp.getString(KEY_ANKI_DECK_NAME, "") ?: ""
        set(v) = sp.edit { putString(KEY_ANKI_DECK_NAME, v) }

    /**
     * The user-selected AnkiDroid note type id. `-1L` (the default) is a
     * sentinel meaning "use the field-based PlayTranslate models"
     * ([com.playtranslate.ui.PtModels] — Word or Sentence, chosen by
     * send mode) — that path bypasses the per-field mapping system
     * entirely. Any other value means the structured path looks up
     * [getAnkiFieldMapping] and writes per-field content sources.
     */
    var ankiModelId: Long
        get() = sp.getLong(KEY_ANKI_MODEL_ID, -1L)
        set(v) = sp.edit { putLong(KEY_ANKI_MODEL_ID, v) }

    /** Display label for the chosen card type. Empty when using the
     *  Default (PlayTranslate) sentinel. Refreshed by the section's
     *  healing pass when the model is renamed in AnkiDroid. */
    var ankiModelName: String
        get() = sp.getString(KEY_ANKI_MODEL_NAME, "") ?: ""
        set(v) = sp.edit { putString(KEY_ANKI_MODEL_NAME, v) }

    /**
     * Returns the saved field mapping for [modelId], or empty when no
     * mapping has been configured. Empty also signals "user hasn't
     * wired this card type up yet" — the send-time guard checks this
     * before shipping a note.
     */
    fun getAnkiFieldMapping(modelId: Long): Map<String, com.playtranslate.ui.ContentSource> {
        val raw = sp.getString(KEY_ANKI_FIELD_MAPPINGS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val obj = root.optJSONObject(modelId.toString()) ?: return emptyMap()
            val result = mutableMapOf<String, com.playtranslate.ui.ContentSource>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                val source = resolveContentSourceName(v)
                result[k] = source
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Resolves a persisted [com.playtranslate.ui.ContentSource] enum-name
     * string, including legacy names that were collapsed into the
     * surviving sources. The two short-lived format-flavoured variants
     * (`SENTENCE_ANKI_FURIGANA`, `SENTENCE_MIGAKU_FURIGANA`,
     * `EXPRESSION_ANKI_FURIGANA`, `EXPRESSION_MIGAKU_FURIGANA`) all
     * map to the bracketed `*_FURIGANA` source — they always carried
     * furigana payload, so the plain `EXPRESSION`/`SENTENCE` source
     * would lose information.
     */
    private fun resolveContentSourceName(name: String): com.playtranslate.ui.ContentSource {
        val direct = com.playtranslate.ui.ContentSource.values().firstOrNull { it.name == name }
        if (direct != null) return direct
        return when (name) {
            "SENTENCE_ANKI_FURIGANA", "SENTENCE_MIGAKU_FURIGANA"
                -> com.playtranslate.ui.ContentSource.SENTENCE_FURIGANA
            "EXPRESSION_ANKI_FURIGANA", "EXPRESSION_MIGAKU_FURIGANA"
                -> com.playtranslate.ui.ContentSource.EXPRESSION_FURIGANA
            else -> com.playtranslate.ui.ContentSource.NONE
        }
    }

    /**
     * Replaces the saved mapping for [modelId]. Pass an empty map to
     * clear the entry (useful when healing detects a deleted model).
     */
    fun setAnkiFieldMapping(modelId: Long, mapping: Map<String, com.playtranslate.ui.ContentSource>) {
        val raw = sp.getString(KEY_ANKI_FIELD_MAPPINGS, null)
        val root = if (raw != null) {
            try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
        if (mapping.isEmpty()) {
            root.remove(modelId.toString())
        } else {
            val obj = JSONObject()
            mapping.forEach { (k, v) -> obj.put(k, v.name) }
            root.put(modelId.toString(), obj)
        }
        sp.edit { putString(KEY_ANKI_FIELD_MAPPINGS, root.toString()) }
    }

    /** Whether new word cards include synthesized word audio. Mirrors the
     *  Audio-card switch in the word review sheet — toggling the switch
     *  writes this, and the next card seeds the switch from it. There is
     *  deliberately no Settings UI; the last-used state is the default. */
    var ankiWordAudioEnabled: Boolean
        get() = sp.getBoolean(KEY_ANKI_WORD_AUDIO, true)
        set(v) = sp.edit { putBoolean(KEY_ANKI_WORD_AUDIO, v) }

    /** Whether Wikimedia Commons is used for DEFAULT word playback (the live tap
     *  path and Anki "Auto" cells). **Default off** — Commons is opt-in per card
     *  via the Anki audio picker, which shows and plays it regardless of this flag
     *  (explicit picks resolve through the source directly). Owned solely by
     *  `WikimediaCommonsAudioSource`. */
    var commonsAudioEnabled: Boolean
        get() = sp.getBoolean("commons_audio_enabled", false)
        set(v) = sp.edit { putBoolean("commons_audio_enabled", v) }

    /** Whether new sentence cards include synthesized sentence audio.
     *  See [ankiWordAudioEnabled] — same last-used-state-is-the-default
     *  behavior, for the sentence review surface. */
    var ankiSentenceAudioEnabled: Boolean
        get() = sp.getBoolean(KEY_ANKI_SENTENCE_AUDIO, true)
        set(v) = sp.edit { putBoolean(KEY_ANKI_SENTENCE_AUDIO, v) }

    /** Which card shape (sentence vs word) new cards default to when both
     *  are possible — the word review sheet's Sentence/Word toggle seeds
     *  from this, and one-tap sends route by it when a real sentence
     *  surrounds the word. Flipping the sheet's toggle writes it back.
     *  See [ankiWordAudioEnabled] — same last-used-state-is-the-default
     *  behavior; deliberately no Settings UI. Word-only contexts (no
     *  meaningful sentence) ignore it and stay word. */
    var ankiPreferredCardMode: CardMode
        get() = sp.getString(KEY_ANKI_CARD_MODE, null)
            ?.let { stored -> CardMode.entries.firstOrNull { it.name == stored } }
            ?: CardMode.SENTENCE
        set(v) = sp.edit { putString(KEY_ANKI_CARD_MODE, v.name) }

    /** Opt-in: keep a rolling recording of the game's audio (AudioPlaybackCapture
     *  on the MediaProjection session) so sentence cards can attach the real
     *  voice line. Settings → Anki Flashcards → Audio. Recording itself also
     *  needs an active capture session + screen-capture consent + RECORD_AUDIO —
     *  see GameAudioRecorder.reconcile.
     *
     *  WRITE via [com.playtranslate.CaptureService.setRecordGameAudio] —
     *  audio-only session lifecycle rides that transition; a bare pref
     *  write on disable leaks an audio-only projection (capture chip lit,
     *  no client) until Turn Off. */
    var recordGameAudio: Boolean
        get() = sp.getBoolean(KEY_ANKI_GAME_AUDIO, false)
        set(v) = sp.edit { putBoolean(KEY_ANKI_GAME_AUDIO, v) }

    var showTransliteration: Boolean
        get() = sp.getBoolean(KEY_SHOW_TRANSLITERATION, false)
        set(v) = sp.edit { putBoolean(KEY_SHOW_TRANSLITERATION, v) }

    var hideTranslationSection: Boolean
        get() = sp.getBoolean(KEY_HIDE_TRANSLATION_SECTION, false)
        set(v) = sp.edit { putBoolean(KEY_HIDE_TRANSLATION_SECTION, v) }

    var hideOriginalSection: Boolean
        get() = sp.getBoolean(KEY_HIDE_ORIGINAL_SECTION, false)
        set(v) = sp.edit { putBoolean(KEY_HIDE_ORIGINAL_SECTION, v) }

    var hideWordsSection: Boolean
        get() = sp.getBoolean(KEY_HIDE_WORDS_SECTION, false)
        set(v) = sp.edit { putBoolean(KEY_HIDE_WORDS_SECTION, v) }

    var showFuriganaInline: Boolean
        get() = sp.getBoolean(KEY_SHOW_FURIGANA_INLINE, false)
        set(v) = sp.edit { putBoolean(KEY_SHOW_FURIGANA_INLINE, v) }

    // ── Results text size (both sections, every surface) ───────────────────
    // The sections don't render at ONE size: each text is auto-fitted to the
    // space it has, and these are the bounds that fit is allowed to pick from
    // (TranslationSectionBinder.fitSize). Defaults reproduce the sizes that
    // were hard-coded before the picker existed, so an untouched install
    // renders identically. Read defensively: the binder binary-searches
    // [min, max], which an inverted or out-of-range pair would break.

    /** Smallest size the results fit may shrink to, in sp. A FLOOR, not the
     *  rendered size — raising it past what fits grows the card and scrolls
     *  rather than clipping. Equal to [resultsFontMaxSp] = a fixed size. */
    var resultsFontMinSp: Int
        get() = sp.getInt(KEY_RESULTS_FONT_MIN_SP, DEFAULT_RESULTS_FONT_MIN_SP)
            .coerceIn(FONT_SP_FLOOR, FONT_SP_CEIL)
            .coerceAtMost(rawResultsFontMaxSp)
        set(v) = sp.edit { putInt(KEY_RESULTS_FONT_MIN_SP, v.coerceIn(FONT_SP_FLOOR, FONT_SP_CEIL)) }

    /** Largest size the results fit may grow to, in sp. */
    var resultsFontMaxSp: Int
        get() = rawResultsFontMaxSp.coerceAtLeast(
            sp.getInt(KEY_RESULTS_FONT_MIN_SP, DEFAULT_RESULTS_FONT_MIN_SP)
                .coerceIn(FONT_SP_FLOOR, FONT_SP_CEIL),
        )
        set(v) = sp.edit { putInt(KEY_RESULTS_FONT_MAX_SP, v.coerceIn(FONT_SP_FLOOR, FONT_SP_CEIL)) }

    /** The stored max clamped to the selectable range but NOT reconciled against
     *  the min — the raw half of the pair, so the two accessors above can settle
     *  a crossed pair without recursing into each other. */
    private val rawResultsFontMaxSp: Int
        get() = sp.getInt(KEY_RESULTS_FONT_MAX_SP, DEFAULT_RESULTS_FONT_MAX_SP)
            .coerceIn(FONT_SP_FLOOR, FONT_SP_CEIL)

    /** Capture method chosen during onboarding: "" = not set, "accessibility", "media_projection" */
    var captureMethod: String
        get() = sp.getString(KEY_CAPTURE_METHOD, "") ?: ""
        set(v) = sp.edit { putString(KEY_CAPTURE_METHOD, v) }

    var overlayMode: OverlayMode
        get() = OverlayMode.fromStorageName(sp.getString(KEY_OVERLAY_MODE, null))
        set(v) = sp.edit { putString(KEY_OVERLAY_MODE, v.name) }

    var hideGameOverlays: Boolean
        get() = sp.getBoolean(KEY_HIDE_GAME_OVERLAYS, false)
        set(v) = sp.edit { putBoolean(KEY_HIDE_GAME_OVERLAYS, v) }

    // ── Capture-result presentation (two independent axes per flow) ────────
    // "Boxes" (on-frame overlays) and the panel's start posture are separate
    // switches, both per-flow: the over-game capture and the camera snapshot
    // are different reading postures. Boxes are written by the header toggle
    // the moment it's tapped; the posture is written on dismissal — "it opens
    // how you left it" for the panel position only. A posture is read through
    // CaptureResultGeometry: NO_POSTURE (the default) = never set,
    // COLLAPSED_POSTURE = parked in the sliver, else the height the user
    // dragged the sheet to as a fraction of the display.

    /** Over-game capture: paint the result's boxes over the game (the header
     *  toggle's state). Default ON. */
    var captureBoxesEnabled: Boolean
        get() = sp.getBoolean("capture_boxes_enabled", true)
        set(v) = sp.edit { putBoolean("capture_boxes_enabled", v) }

    /** Over-game capture: the panel posture the last dismissal left behind, so
     *  the next capture opens there. */
    var capturePanelPosture: Float
        get() = sp.getFloat("capture_panel_posture", CaptureResultGeometry.NO_POSTURE)
        set(v) = sp.edit { putFloat("capture_panel_posture", v) }

    /** Camera snapshot: paint the result's boxes over the frozen frame.
     *  Default ON. Also decides whether live overlays are kept through the
     *  shutter's freeze (they track the very frame being frozen). */
    var cameraBoxesEnabled: Boolean
        get() = sp.getBoolean("camera_boxes_enabled", true)
        set(v) = sp.edit { putBoolean("camera_boxes_enabled", v) }

    /** Camera snapshot: the panel posture the last dismissal left behind, so
     *  the next snapshot opens there. */
    var cameraPanelPosture: Float
        get() = sp.getFloat("camera_panel_posture", CaptureResultGeometry.NO_POSTURE)
        set(v) = sp.edit { putFloat("camera_panel_posture", v) }

    /** The camera tool's own overlay flavor (Translation vs Furigana/Pinyin),
     *  cycled from the camera pill's gear menu. Inherit-until-set: unset reads
     *  the global [overlayMode], so the camera starts wherever the app is; the
     *  first camera-side cycle pins it, after which the two move independently
     *  (a camera flavor change must not silently rebuild live mode's overlays,
     *  and vice versa). */
    var cameraOverlayMode: OverlayMode
        get() = sp.getString("camera_overlay_mode", null)
            ?.let { OverlayMode.fromStorageName(it) } ?: overlayMode
        set(v) = sp.edit { putString("camera_overlay_mode", v.name) }

    /** The camera tool's own OCR engine selection for [id], or null to
     *  inherit the global [ocrBackendToken] — same inherit-until-set contract
     *  as [cameraOverlayMode]. Resolved by `OcrModelManager.selectedBackend`
     *  via its token override, so a stale camera token degrades exactly like
     *  a stale global one (floor fallback, never an empty engine). */
    fun cameraOcrBackendToken(id: SourceLangId): String? =
        sp.getString("camera_ocr_backend_${id.code}", null)
    fun setCameraOcrBackendToken(id: SourceLangId, token: String) =
        sp.edit { putString("camera_ocr_backend_${id.code}", token) }
    fun clearCameraOcrBackendToken(id: SourceLangId) =
        sp.edit { remove("camera_ocr_backend_${id.code}") }

    /** Import tool: paint the result's boxes over the imported image.
     *  Default ON. Twin of [cameraBoxesEnabled] for the import review. */
    var importBoxesEnabled: Boolean
        get() = sp.getBoolean("import_boxes_enabled", true)
        set(v) = sp.edit { putBoolean("import_boxes_enabled", v) }

    /** Import tool: the panel posture the last dismissal left behind, so the
     *  next review opens there. */
    var importPanelPosture: Float
        get() = sp.getFloat("import_panel_posture", CaptureResultGeometry.NO_POSTURE)
        set(v) = sp.edit { putFloat("import_panel_posture", v) }

    /** The import tool's own overlay flavor — same inherit-until-set
     *  contract and rationale as [cameraOverlayMode], inheriting from the
     *  GLOBAL flavor (not the camera's: each tool pins independently off
     *  the app-wide default). */
    var importOverlayMode: OverlayMode
        get() = sp.getString("import_overlay_mode", null)
            ?.let { OverlayMode.fromStorageName(it) } ?: overlayMode
        set(v) = sp.edit { putString("import_overlay_mode", v.name) }

    /** The import tool's own OCR engine selection for [id], or null to
     *  inherit the global [ocrBackendToken] — same inherit-until-set and
     *  stale-token contracts as [cameraOcrBackendToken]. Swept alongside the
     *  camera token by `OcrModelManager.deleteOcrPack` when its pack is
     *  deleted. */
    fun importOcrBackendToken(id: SourceLangId): String? =
        sp.getString("import_ocr_backend_${id.code}", null)
    fun setImportOcrBackendToken(id: SourceLangId, token: String) =
        sp.edit { putString("import_ocr_backend_${id.code}", token) }
    fun clearImportOcrBackendToken(id: SourceLangId) =
        sp.edit { remove("import_ocr_backend_${id.code}") }

    /** When on (the default), touching the game screen during auto translation
     *  dismisses the current overlay and re-captures one capture interval
     *  later — or, on the single-app (clean-stream) tier that can read the
     *  text under its own boxes, briefly speeds the detection loop up instead
     *  of hiding anything. Off makes screen touches a no-op for refresh; the
     *  detection loop and gamepad input still refresh. Read at touch-time (see
     *  the touch-sentinel callbacks), so toggling takes effect immediately
     *  without restarting live mode. */
    var touchesRefreshTranslation: Boolean
        get() = sp.getBoolean("touches_refresh_translation", true)
        set(v) = sp.edit { putBoolean("touches_refresh_translation", v) }

    /** When on (the default), a vertical-source box whose translation is too narrow to
     *  stack and too long to fit in place is grown in width over its source and rendered
     *  horizontally (it may widen over nearby non-text pixels). Off keeps such boxes in
     *  their original footprint (90° rotation). Read when the overlay view is created, so
     *  the settings toggle restarts live mode to apply it. */
    var verticalTextGrow: Boolean
        get() = sp.getBoolean("vertical_text_grow", true)
        set(v) = sp.edit { putBoolean("vertical_text_grow", v) }

    /** Opt-in manga-ocr refinement for Japanese OCR — high quality, slow; OFF by
     *  default. Runtime-gated further to Japanese + arm64 + installed pack; the value
     *  is pushed to [OcrManager.mangaOcrEnabled] via
     *  [com.playtranslate.ocr.mangaocr.MangaOcrProvisioning.refresh]. */
    var useMangaOcr: Boolean
        get() = sp.getBoolean("use_manga_ocr", false)
        set(v) = sp.edit { putBoolean("use_manga_ocr", v) }

    /**
     * One-shot migration of the legacy `auto_translation_mode` ordinal pref
     * (used on the shipped `main` branch, where 0 = OVERLAYS and
     * 1 = IN_APP_ONLY). If an upgrading user had IN_APP_ONLY selected, flip
     * the new [hideGameOverlays] toggle on. The legacy key is then removed
     * so this only runs once. Invoked from the [Prefs] init block on every
     * construction; idempotent and cheap once the legacy keys are gone.
     *
     * The new overlay-mode pref is a separate key ([KEY_OVERLAY_MODE],
     * string-backed by [OverlayMode.name]) that defaults to TRANSLATION for
     * everyone on upgrade; Furigana is new in v1.2.0, so no existing
     * user on a released build could have selected it. Pre-release v1.2.0
     * testers lose their Furigana preference across this migration — an
     * acceptable cost given the internal audience.
     */
    fun migrateLegacyPrefs() {
        // Encrypt any legacy plaintext API keys before anything reads them.
        migrateSecretsToEncrypted()

        val legacyKey = "auto_translation_mode"
        if (sp.contains(legacyKey)) {
            val legacyOrdinal = try {
                sp.getInt(legacyKey, 0)
            } catch (_: ClassCastException) {
                0
            }
            if (legacyOrdinal == 1) {
                hideGameOverlays = true
            }
            sp.edit { remove(legacyKey) }
        }

        // Migrate the pre-redesign 4-theme picker (Black/White/Rainbow/Purple)
        // to the new (themeMode, accentName) split. Only run if the new keys
        // haven't been written yet so we don't clobber an explicit choice.
        if (sp.contains(KEY_LEGACY_THEME_INDEX) && !sp.contains(KEY_THEME_MODE)) {
            val legacyIndex = try {
                sp.getInt(KEY_LEGACY_THEME_INDEX, 0)
            } catch (_: ClassCastException) {
                0
            }
            val (mode, accent) = when (legacyIndex) {
                1 -> ThemeMode.LIGHT to AccentColor.Teal     // White
                2 -> ThemeMode.LIGHT to AccentColor.Coral    // Rainbow
                3 -> ThemeMode.DARK  to AccentColor.Violet   // Purple
                else -> ThemeMode.DARK to AccentColor.Teal   // Black
            }
            sp.edit {
                putString(KEY_THEME_MODE, mode.storageKey)
                putString(KEY_ACCENT_NAME, accent.name)
                remove(KEY_LEGACY_THEME_INDEX)
            }
        }

        // Multi-display migration: seed the new per-display schemas from the
        // legacy single-display state. The legacy display id is the key under
        // which we file the legacy icon position and selected region — using
        // 0 would lose the user's setup on devices where the active display
        // isn't DEFAULT_DISPLAY (e.g. a foldable user who picked the outer
        // panel). Each block is guarded by absence-of-new so re-running is a
        // no-op and a manual user choice on the new schema is never clobbered.
        val legacyDisplayId = sp.getInt(KEY_DISPLAY_ID, 0)

        if (sp.contains(KEY_DISPLAY_ID) && !sp.contains(KEY_DISPLAY_IDS)) {
            sp.edit { putString(KEY_DISPLAY_IDS, legacyDisplayId.toString()) }
            // KEY_DISPLAY_ID stays in SharedPreferences as harmless bytes —
            // [captureDisplayIds] only consults it as a fresh-install
            // fallback before the new key has been written.
        }

        if (sp.contains(KEY_OVERLAY_ICON_EDGE) || sp.contains(KEY_OVERLAY_ICON_FRACTION)) {
            if (!sp.contains(KEY_ICON_POSITION_BY_DISPLAY)) {
                val legacyEdge = sp.getInt(KEY_OVERLAY_ICON_EDGE, 1)
                val legacyFraction = sp.getFloat(KEY_OVERLAY_ICON_FRACTION, 0.5f)
                val obj = JSONObject().apply {
                    put(legacyDisplayId.toString(), JSONObject().apply {
                        put("edge", legacyEdge)
                        put("fraction", legacyFraction.toDouble())
                    })
                }
                sp.edit { putString(KEY_ICON_POSITION_BY_DISPLAY, obj.toString()) }
            }
            // Nothing reads the legacy icon-position keys after this point — drop them.
            sp.edit {
                remove(KEY_OVERLAY_ICON_EDGE)
                remove(KEY_OVERLAY_ICON_FRACTION)
            }
        }

        if (sp.contains(KEY_SELECTED_REGION_ID)) {
            if (!sp.contains(KEY_SELECTED_REGION_BY_DISPLAY)) {
                val legacyRegionId = sp.getString(KEY_SELECTED_REGION_ID, "") ?: ""
                if (legacyRegionId.isNotEmpty()) {
                    val obj = JSONObject().apply {
                        put(legacyDisplayId.toString(), legacyRegionId)
                    }
                    sp.edit { putString(KEY_SELECTED_REGION_BY_DISPLAY, obj.toString()) }
                }
            }
            // Nothing reads KEY_SELECTED_REGION_ID after this point — drop it.
            sp.edit { remove(KEY_SELECTED_REGION_ID) }
        }

        // First launch under the per-backend toggle UI: existing users with
        // a stored DeepL key get DeepL on by default (the old waterfall
        // would have used DeepL automatically; we want continuity). Users
        // without a key keep the default-false. Guarded by absence of the
        // new key so a deliberate user choice is never clobbered.
        if (!sp.contains(KEY_DEEPL_ENABLED) &&
            (sp.getString(KEY_DEEPL_KEY, "") ?: "").isNotBlank()) {
            sp.edit { putBoolean(KEY_DEEPL_ENABLED, true) }
        }

        // Back-fill TTS-audio field mappings for non-default card types
        // configured before v2.2.0 — see [migrateAnkiAudioFieldMappings].
        migrateAnkiAudioFieldMappings()

        // Back-fill pitch/frequency field mappings for card types configured
        // before those Yomitan-derived sources existed — see
        // [migrateAnkiPitchFreqFieldMappings].
        migrateAnkiPitchFreqFieldMappings()
    }

    /**
     * One-shot back-fill of TTS-audio field mappings for non-default
     * card types configured before v2.2.0.
     *
     * Until v2.2.0, [com.playtranslate.ui.ContentSource] had no audio
     * sources, so [com.playtranslate.ui.AnkiCardTypeMapper]'s Lapis /
     * JPMN / Migaku defaults left those templates' audio fields unmapped
     * and the field-mapping dialog persisted them as `NONE`. A user who
     * wired up one of those card types on v2.1.0 therefore carries a
     * saved mapping that pins every audio field to `NONE` — and because
     * [getAnkiFieldMapping] is authoritative once non-empty, the v2.2.0
     * audio defaults never get a chance to fill them in. The send path
     * then silently drops the synthesized audio: no field carries the
     * `[sound:]` tag, the media upload still succeeds, and nothing
     * reports the loss.
     *
     * This walks every saved mapping and rewrites any audio field still
     * sitting at `NONE` to its
     * [com.playtranslate.ui.AnkiCardTypeMapper.AUDIO_FIELD_DEFAULTS]
     * source. It touches only fields already present in the saved JSON,
     * and only those still at `NONE` — a field the user (or a
     * fresh-v2.2.0 default) already mapped is left as-is. Gated on
     * [KEY_ANKI_AUDIO_MAPPING_MIGRATED] so it runs exactly once: after
     * it, a `NONE` on an audio field is a deliberate choice to keep.
     */
    private fun migrateAnkiAudioFieldMappings() {
        if (sp.contains(KEY_ANKI_AUDIO_MAPPING_MIGRATED)) return

        val raw = sp.getString(KEY_ANKI_FIELD_MAPPINGS, null)
        if (raw != null) {
            try {
                val root = JSONObject(raw)
                val audioDefaults = com.playtranslate.ui.AnkiCardTypeMapper.AUDIO_FIELD_DEFAULTS
                val noneName = com.playtranslate.ui.ContentSource.NONE.name
                var changed = false
                val modelIds = root.keys()
                while (modelIds.hasNext()) {
                    val obj = root.optJSONObject(modelIds.next()) ?: continue
                    for ((fieldName, source) in audioDefaults) {
                        // Back-fill only a field the pre-v2.2.0 dialog
                        // left at NONE. An absent field — the model
                        // gained the audio slot in AnkiDroid after the
                        // mapping was saved — is left for the mapping
                        // dialog; that is a schema change, not a
                        // v2.1.0→v2.2.0 upgrade gap.
                        if (obj.has(fieldName) && obj.optString(fieldName) == noneName) {
                            obj.put(fieldName, source.name)
                            changed = true
                        }
                    }
                }
                if (changed) {
                    sp.edit { putString(KEY_ANKI_FIELD_MAPPINGS, root.toString()) }
                }
            } catch (_: Exception) {
                // Corrupt JSON — getAnkiFieldMapping already degrades it
                // to an empty mapping, so there is nothing to migrate.
                // Fall through and set the marker rather than re-parsing
                // a broken blob on every launch.
            }
        }

        sp.edit { putBoolean(KEY_ANKI_AUDIO_MAPPING_MIGRATED, true) }
    }

    /**
     * One-shot back-fill of pitch/frequency field mappings for card types
     * configured before those Yomitan-derived sources existed.
     *
     * Like [migrateAnkiAudioFieldMappings], the field-mapping dialog persisted
     * every note field, and these pitch/frequency slots had no source yet, so
     * they sit at `NONE` — except Lapis's `Frequency`, which sat at the old
     * ★-stars default ([com.playtranslate.ui.ContentSource.FREQUENCY]). Because
     * [getAnkiFieldMapping] is authoritative once non-empty, an existing saved
     * mapping would never pick up the new template defaults, so the fields
     * would stay blank on every send.
     *
     * Walks every saved mapping and applies
     * [com.playtranslate.ui.AnkiCardTypeMapper.PITCH_FREQ_FIELD_MIGRATION]: a
     * field present and still at its OLD auto-default is rewritten to the new
     * source; a deliberately different choice (incl. a user-cleared `NONE` on
     * `Frequency`) is left as-is. Touches only fields already present in the
     * saved JSON. Gated on [KEY_ANKI_PITCH_FREQ_MAPPING_MIGRATED] so it runs
     * exactly once.
     *
     * Known limitation (shared with [migrateAnkiAudioFieldMappings]): a mapping
     * saved before the note type gained these fields has no key to rewrite —
     * the user re-opens the mapping dialog to pick them up (its template
     * defaults now include them).
     */
    private fun migrateAnkiPitchFreqFieldMappings() {
        if (sp.contains(KEY_ANKI_PITCH_FREQ_MAPPING_MIGRATED)) return

        val raw = sp.getString(KEY_ANKI_FIELD_MAPPINGS, null)
        if (raw != null) {
            try {
                val root = JSONObject(raw)
                val rules = com.playtranslate.ui.AnkiCardTypeMapper.PITCH_FREQ_FIELD_MIGRATION
                var changed = false
                val modelIds = root.keys()
                while (modelIds.hasNext()) {
                    val obj = root.optJSONObject(modelIds.next()) ?: continue
                    for ((fieldName, from, to) in rules) {
                        // Rewrite only a field still sitting at its old
                        // auto-default. An absent field (note type gained the
                        // slot after the mapping was saved) or a deliberately
                        // different value is left alone.
                        if (obj.has(fieldName) && obj.optString(fieldName) == from.name) {
                            obj.put(fieldName, to.name)
                            changed = true
                        }
                    }
                }
                if (changed) {
                    sp.edit { putString(KEY_ANKI_FIELD_MAPPINGS, root.toString()) }
                }
            } catch (_: Exception) {
                // Corrupt JSON — getAnkiFieldMapping already degrades it to an
                // empty mapping; set the marker rather than re-parsing a broken
                // blob on every launch.
            }
        }

        sp.edit { putBoolean(KEY_ANKI_PITCH_FREQ_MAPPING_MIGRATED, true) }
    }

    /** Hotkey combo for hold-to-show translations. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyTranslation: String
        get() = sp.getString(KEY_HOTKEY_TRANSLATION, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_TRANSLATION, v) }

    /** Hotkey combo for hold-to-show furigana. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyFurigana: String
        get() = sp.getString(KEY_HOTKEY_FURIGANA, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_FURIGANA, v) }

    /** Hotkey combo for tap-to-toggle auto translation. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyTranslationTap: String
        get() = sp.getString(KEY_HOTKEY_TRANSLATION_TAP, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_TRANSLATION_TAP, v) }

    /** Hotkey combo for tap-to-toggle auto furigana/pinyin. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyFuriganaTap: String
        get() = sp.getString(KEY_HOTKEY_FURIGANA_TAP, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_FURIGANA_TAP, v) }

    /** Hotkey combo for the one-shot "Capture screen" (toggles the capture
     *  result off if it's still showing). Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyCaptureTap: String
        get() = sp.getString(KEY_HOTKEY_CAPTURE_TAP, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_CAPTURE_TAP, v) }

    /** Capture interval for live mode in seconds. */
    var captureIntervalSec: Float
        get() = sp.getFloat(KEY_CAPTURE_INTERVAL_SEC, DEFAULT_CAPTURE_INTERVAL_SEC).coerceAtLeast(MIN_CAPTURE_INTERVAL_SEC)
        set(v) = sp.edit { putFloat(KEY_CAPTURE_INTERVAL_SEC, v.coerceAtLeast(MIN_CAPTURE_INTERVAL_SEC)) }

    /** Capture interval in milliseconds. */
    val captureIntervalMs: Long get() = (captureIntervalSec * 1000).toLong()

    /** Saved scroll position for the settings sheet (restored after theme recreate). */
    var settingsScrollY: Int
        get() = sp.getInt(KEY_SETTINGS_SCROLL_Y, 0)
        set(v) = sp.edit { putInt(KEY_SETTINGS_SCROLL_Y, v) }

    /** Whether the floating overlay icon is shown on the game screen. */
    var showOverlayIcon: Boolean
        get() = sp.getBoolean(KEY_SHOW_OVERLAY_ICON, true)
        set(v) = sp.edit { putBoolean(KEY_SHOW_OVERLAY_ICON, v) }

    /** Set to true once StatusBarManager.requestAddTileService reports the
     *  PlayTranslate tile is added (or already added). Drives whether the
     *  Settings "Add Quick Settings tile" row is offered. */
    var quickTileAdded: Boolean
        get() = sp.getBoolean(KEY_QUICK_TILE_ADDED, false)
        set(v) = sp.edit { putBoolean(KEY_QUICK_TILE_ADDED, v) }

    /** Debug-only: forces isSingleScreen() to return true regardless of actual display count. */
    var debugForceSingleScreen: Boolean
        get() = sp.getBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, v) }

    /** Debug-only: show OCR bounding boxes overlaid on the game screen after each capture. */
    var debugShowOcrBoxes: Boolean
        get() = sp.getBoolean(KEY_DEBUG_SHOW_OCR_BOXES, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_SHOW_OCR_BOXES, v) }

    var debugShowDetectionLog: Boolean
        get() = sp.getBoolean(KEY_DEBUG_SHOW_DETECTION_LOG, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_SHOW_DETECTION_LOG, v) }

    /** Debug-only: log per-cycle pinhole detection metrics + box transitions
     *  + render-offscreen layout-settle stats. Used to diagnose live-mode
     *  flicker; off in steady-state to keep logcat quiet. */
    var debugLiveMode: Boolean
        get() = sp.getBoolean(KEY_DEBUG_LIVE_MODE, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_LIVE_MODE, v) }

    /** Debug-only: when on, [com.playtranslate.OcrSeedWriter] writes the
     *  bitmap that was fed to OCR plus a transcription of the result to
     *  external files dir. Intended for one-off seeding of the golden-set
     *  test harness — not always-on (PNG compression on every capture is
     *  not free). See [com.playtranslate.OcrSeedWriter]. */
    var debugSaveOcrSeed: Boolean
        get() = sp.getBoolean(KEY_DEBUG_SAVE_OCR_SEED, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_SAVE_OCR_SEED, v) }

    /** Debug-only: log every candidate line's grouping decision during OCR
     *  with the previous group's bounds, the candidate's bounds + text, and
     *  the numeric reason it merged (or didn't). Use to diagnose why rows
     *  fail to combine — see [OcrManager.wouldGroup]. */
    var debugLogGrouping: Boolean
        get() = sp.getBoolean(KEY_DEBUG_LOG_GROUPING, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_LOG_GROUPING, v) }

    /** Debug-only: append every live-mode committed region set
     *  (post-TypewriterGate `toTranslate`) to a JSONL trace under
     *  external-files/log-traces/ — the offline feed for validating the
     *  translation-log write gate on real sessions. See
     *  [com.playtranslate.translationlog.LogTraceRecorder]. */
    var debugLogTrace: Boolean
        get() = sp.getBoolean(KEY_DEBUG_LOG_TRACE, false)
        set(v) = sp.edit { putBoolean(KEY_DEBUG_LOG_TRACE, v) }

    /** Set to true after the user dismisses the target-pack migration dialog. */
    var targetPackMigrationDismissed: Boolean
        get() = sp.getBoolean(KEY_TARGET_PACK_MIGRATION_DISMISSED, false)
        set(v) = sp.edit { putBoolean(KEY_TARGET_PACK_MIGRATION_DISMISSED, v) }

    /** Set before recreate() so MainActivity suppresses the window transition animation. */
    var suppressNextTransition: Boolean
        get() = sp.getBoolean(KEY_SUPPRESS_TRANSITION, false)
        set(v) = sp.edit { putBoolean(KEY_SUPPRESS_TRANSITION, v) }

    /** Timestamp (ms) of the most recent GitHub release check. Debounced to 24h. */
    var lastUpdateCheckTime: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit { putLong(KEY_LAST_UPDATE_CHECK, v) }

    /** Timestamp (ms) of the most recent Yomitan dictionary auto-update scan.
     *  Debounced to ~24h (mirrors [lastUpdateCheckTime]). */
    var lastYomitanUpdateCheckMs: Long
        get() = sp.getLong(KEY_LAST_YOMITAN_UPDATE_CHECK, 0L)
        set(v) = sp.edit { putLong(KEY_LAST_YOMITAN_UPDATE_CHECK, v) }

    /** Whether the one-time backfill of Yomitan update metadata
     *  (isUpdatable/indexUrl/downloadUrl onto pre-existing registry entries)
     *  has run. */
    var yomitanUpdateBackfillDone: Boolean
        get() = sp.getBoolean(KEY_YOMITAN_UPDATE_BACKFILL_DONE, false)
        set(v) = sp.edit { putBoolean(KEY_YOMITAN_UPDATE_BACKFILL_DONE, v) }

    /** Whether the one-time sweep of retained Yomitan dictionary zips
     *  (extract index.json, delete dict.zip, remove orphan dirs) has fully
     *  completed. Left false while any zip remains so the sweep retries. */
    var yomitanZipSweepDone: Boolean
        get() = sp.getBoolean(KEY_YOMITAN_ZIP_SWEEP_DONE, false)
        set(v) = sp.edit { putBoolean(KEY_YOMITAN_ZIP_SWEEP_DONE, v) }

    /** Timestamp (ms) of the most recent Yomitan outdated-dictionary heal
     *  check. Short-debounced (~15 min, unlike the 24h update scan) so a
     *  schema bump heals on the first online resume, not a day later. */
    var lastYomitanHealAttemptMs: Long
        get() = sp.getLong(KEY_LAST_YOMITAN_HEAL_ATTEMPT, 0L)
        set(v) = sp.edit { putLong(KEY_LAST_YOMITAN_HEAL_ATTEMPT, v) }

    /** Tag (e.g. "v1.2.0") the user explicitly skipped; suppresses re-prompting
     *  until a newer tag is published. */
    var updateCheckSkippedTag: String
        get() = sp.getString(KEY_UPDATE_SKIP_TAG, "") ?: ""
        set(v) = sp.edit { putString(KEY_UPDATE_SKIP_TAG, v) }

    /** Tag whose APK is fully downloaded + validated in cache/updates/,
     *  awaiting the system-installer hand-off. Set only after the whole
     *  [com.playtranslate.update.ApkUpdateManager] validation ladder passes;
     *  cleared once the update is installed, skipped, or the cached file
     *  stops validating. Bridges process death between download and install
     *  (the unknown-sources grant can kill the app). */
    var updateDownloadedTag: String
        get() = sp.getString(KEY_UPDATE_DOWNLOADED_TAG, "") ?: ""
        set(v) = sp.edit { putString(KEY_UPDATE_DOWNLOADED_TAG, v) }

    /** Newest release tag either check has seen that beats this build, or ""
     *  when the last completed check found nothing newer. Written by
     *  [com.playtranslate.UpdateChecker] the moment a tag proves newer —
     *  BEFORE the skip filter, so a skipped version still shows in Settings
     *  (skipping silences the launch nudge, it doesn't erase the fact).
     *
     *  Persisted rather than held in memory because the launch check runs at
     *  most once a day: a process started 20h into that window would otherwise
     *  have no idea an update exists. Readers must still gate on
     *  [com.playtranslate.UpdateChecker.isNewer] against the running version —
     *  after a successful self-update, this tag stays stale until the next
     *  check clears it. */
    var updateAvailableTag: String
        get() = sp.getString(KEY_UPDATE_AVAILABLE_TAG, "") ?: ""
        set(v) = sp.edit { putString(KEY_UPDATE_AVAILABLE_TAG, v) }


    /** SYSTEM follows the OS uiMode; DARK/LIGHT are explicit overrides. */
    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(sp.getString(KEY_THEME_MODE, null))
        set(v) = sp.edit { putString(KEY_THEME_MODE, v.storageKey) }

    /** Name of the active accent (matches [AccentColor] enum constant name). */
    var accentName: String
        get() = sp.getString(KEY_ACCENT_NAME, AccentColor.Default.name) ?: AccentColor.Default.name
        set(v) = sp.edit { putString(KEY_ACCENT_NAME, v) }

    /** Resolved accent — falls back to [AccentColor.Default] for unknown names. */
    val accent: AccentColor get() = AccentColor.byName(accentName)

    fun getRegionList(): MutableList<RegionEntry> {
        val json = sp.getString(KEY_REGION_LIST, null)
            ?: return DEFAULT_REGION_LIST.toMutableList()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RegionEntry(
                    label  = o.getString("label"),
                    top    = o.getDouble("top").toFloat(),
                    bottom = o.getDouble("bottom").toFloat(),
                    left   = o.optDouble("left",  0.0).toFloat(),
                    right  = o.optDouble("right", 1.0).toFloat(),
                    id     = o.optString("id", "").ifEmpty { java.util.UUID.randomUUID().toString() }
                )
            }
        } catch (_: Exception) {
            DEFAULT_REGION_LIST.toMutableList()
        }
    }

    fun setRegionList(list: List<RegionEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("label",  e.label)
                put("top",    e.top.toDouble())
                put("bottom", e.bottom.toDouble())
                put("left",   e.left.toDouble())
                put("right",  e.right.toDouble())
                put("id",     e.id)
            })
        }
        sp.edit { putString(KEY_REGION_LIST, arr.toString()) }
    }

    companion object {
        const val MIN_CAPTURE_INTERVAL_SEC = 0.5f
        const val DEFAULT_CAPTURE_INTERVAL_SEC = 1.0f

        /** Selectable bounds for the results text-size range picker, in sp.
         *  The picker's track spans exactly this; the binder clamps its fit to
         *  whatever sub-range the user parks the two handles on. */
        const val FONT_SP_FLOOR = 12
        const val FONT_SP_CEIL = 28
        const val DEFAULT_RESULTS_FONT_MIN_SP = 16
        const val DEFAULT_RESULTS_FONT_MAX_SP = 24

        const val KEY_SOURCE_LANG    = "source_lang"
        const val KEY_TARGET_LANG    = "target_lang"
        const val KEY_TARGET_CHINESE_VARIANT = "target_chinese_variant"

        /** The pref keys whose writes affect onboarding readiness — the source/
         *  target language pick and the debug force-single toggle. The
         *  readiness gate observes exactly these so a write from any component
         *  re-derives on its own. The gate's non-pref inputs (pack install,
         *  notification/accessibility permissions, display topology) are NOT
         *  observable and are driven by MainActivity's refresh() triggers. */
        val ONBOARDING_GATE_KEYS = arrayOf(
            KEY_SOURCE_LANG, KEY_TARGET_LANG, KEY_DEBUG_FORCE_SINGLE_SCREEN,
        )

        private const val KEY_DISPLAY_ID     = "capture_display_id"
        private const val KEY_DISPLAY_IDS    = "capture_display_ids"
        private const val KEY_SELECTED_REGION_ID = "selected_region_id"
        private const val KEY_SELECTED_REGION_BY_DISPLAY = "selected_region_by_display"
        private const val KEY_ICON_POSITION_BY_DISPLAY   = "icon_position_by_display"
        private const val KEY_ANKI_DECK_ID         = "anki_deck_id"
        const val KEY_ANKI_DECK_NAME       = "anki_deck_name"
        const val KEY_ANKI_MODEL_ID        = "anki_model_id"
        const val KEY_ANKI_MODEL_NAME      = "anki_model_name"
        private const val KEY_ANKI_FIELD_MAPPINGS  = "anki_field_mappings"   // JSON
        private const val KEY_ANKI_WORD_AUDIO      = "anki_word_audio_enabled"
        private const val KEY_ANKI_CARD_MODE       = "anki_default_card_mode"
        private const val KEY_ANKI_SENTENCE_AUDIO  = "anki_sentence_audio_enabled"
        private const val KEY_ANKI_GAME_AUDIO      = "anki_game_audio_enabled"
        private const val KEY_ANKI_AUDIO_MAPPING_MIGRATED = "anki_audio_mapping_migrated"
        private const val KEY_ANKI_PITCH_FREQ_MAPPING_MIGRATED = "anki_pitch_freq_mapping_migrated"
        private const val KEY_REGION_LIST    = "region_list"
        const val KEY_DEEPL_KEY              = "deepl_api_key"
        private const val KEY_SECRETS_ENCRYPTED_MIGRATED = "secrets_encrypted_migrated"

        /** Process-wide guard so the one-shot secret migration runs exactly
         *  once even if two threads construct [Prefs] concurrently on the first
         *  launch after upgrade (see [migrateSecretsToEncrypted]). */
        private val SECRET_MIGRATION_LOCK = Any()
        const val KEY_DEEPL_ENABLED          = "deepl_enabled"
        const val KEY_LINGVA_ENABLED         = "lingva_enabled"
        const val KEY_BERGAMOT_ENABLED       = "bergamot_enabled"
        const val KEY_QWEN_MNN_ENABLED   = "qwen_mnn_enabled"
        const val KEY_QWEN35_MNN_2B_ENABLED = "qwen35_mnn_2b_enabled"
        const val KEY_GEMMA_E2B_ENABLED  = "gemma_e2b_enabled"
        const val KEY_HYMT_ENABLED          = "hymt_enabled"
        const val KEY_HYMT_LEGAL_ACCEPTED   = "hymt_legal_accepted"
        const val KEY_GEMINI_KEY                    = "gemini_api_key"
        const val KEY_GEMINI_ENABLED                = "gemini_enabled"
        const val KEY_GEMINI_MODEL                  = "gemini_model"
        const val KEY_OPENAI_KEY                    = "openai_api_key"
        const val KEY_OPENAI_ENABLED                = "openai_enabled"
        const val KEY_OPENAI_MODEL                  = "openai_model"
        const val KEY_OPENAI_BASE_URL               = "openai_base_url"
        const val KEY_DEEPSEEK_KEY                  = "deepseek_api_key"
        const val KEY_DEEPSEEK_ENABLED              = "deepseek_enabled"
        const val KEY_DEEPSEEK_MODEL                = "deepseek_model"
        const val KEY_LLM_SYSTEM_PROMPT             = "llm_system_prompt"
        const val KEY_LLM_TRANSLATION_PROMPT        = "llm_translation_prompt"
        const val KEY_LLM_BATCH_PROMPT              = "llm_batch_prompt"
        private const val KEY_LLM_CONTEXT_ENABLED           = "llm_context_enabled"
        private const val KEY_TRANSLATION_HISTORY_ENABLED   = "translation_history_enabled"
        private const val KEY_CAPTURE_IMAGE_HISTORY_ENABLED = "capture_image_history_enabled"
        private const val KEY_HISTORY_HIDE_TRANSLATIONS     = "history_hide_translations"

        /** Default selected model — chosen to match the first entry in
         *  the picker after filtering + sorting (newest alias by
         *  `created` timestamp for OpenAI; highest stable version's
         *  alphabetical-first variant for Gemini). These are best
         *  guesses; the picker's listModels log line shows the actual
         *  top so we can adjust if wrong. */
        const val DEFAULT_OPENAI_MODEL    = "chat-latest"
        /** Canonical OpenAI endpoint. Default for [openaiBaseUrl]; the
         *  reference point for [isCustomOpenaiBaseUrl]. */
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_GEMINI_MODEL    = "gemini-flash-lite-latest"
        // DeepSeek doesn't ship a rolling -latest alias (per api-docs.
        // deepseek.com). The legacy `deepseek-chat` / `deepseek-reasoner`
        // aliases are scheduled for retirement on 2026-07-24, both
        // currently route to v4-flash anyway. Pin directly to flash —
        // mirrors the "small/fast/cheap" default we use on Gemini
        // (flash-lite-latest).
        const val DEFAULT_DEEPSEEK_MODEL  = "deepseek-v4-flash"
        // Mistral Small 4 — their small/fast/cheap tier, pinned by dated id
        // like DeepSeek's above and NOT to a "mistral-small-latest" alias:
        // docs.mistral.ai documents no -latest alias for it (neither the
        // models overview nor the model card lists one; only stale examples
        // inside their OpenAPI spec still use it). Gemini's rolling alias is
        // documented, so we take it there — here we'd be guessing, and an
        // alias that doesn't resolve 400s every new Mistral instance on its
        // first translation, because key validation probes /models and never
        // touches the model id. A dated id ages instead of breaking, and the
        // model picker reads the live catalog when the user wants to move.
        // Revisit when Mistral Small 5 lands.
        const val DEFAULT_MISTRAL_MODEL   = "mistral-small-2603"
        // Groq's cheapest model is llama-3.1-8b-instant, but an 8B model
        // translates game/manga text badly — the 20B is $0.075/$0.30 and
        // still runs at ~1000 tok/s, which is the whole reason to be on
        // Groq. Open-weights, so no vendor deprecation clock on the id.
        const val DEFAULT_GROQ_MODEL      = "openai/gpt-oss-20b"
        // OpenRouter ids are provider-namespaced and carry no rolling
        // -latest alias, so this pins a specific model: flash-lite is the
        // same small/fast/cheap tier we default to on Gemini, and holds up
        // on CJK short text far better than the cheaper open models.
        const val DEFAULT_OPENROUTER_MODEL = "google/gemini-2.5-flash-lite"
        private const val KEY_LEGACY_THEME_INDEX    = "theme_index"
        const val KEY_THEME_MODE                    = "theme_mode"
        const val KEY_ACCENT_NAME                   = "accent_name"
        private const val KEY_CAPTURE_INTERVAL_SEC  = "capture_interval_sec"
        private const val KEY_CAPTURE_METHOD           = "capture_method"
        private const val KEY_OVERLAY_MODE               = "overlay_mode"
        private const val KEY_SETTINGS_SCROLL_Y        = "settings_scroll_y"
        const val KEY_SHOW_OVERLAY_ICON       = "show_overlay_icon"
        private const val KEY_OVERLAY_ICON_EDGE      = "overlay_icon_edge"
        private const val KEY_OVERLAY_ICON_FRACTION  = "overlay_icon_fraction"
        private const val KEY_SUPPRESS_TRANSITION            = "suppress_next_transition"
        private const val KEY_SHOW_TRANSLITERATION             = "show_transliteration"
        private const val KEY_HIDE_TRANSLATION_SECTION       = "hide_translation_section"
        private const val KEY_HIDE_ORIGINAL_SECTION          = "hide_original_section"
        private const val KEY_HIDE_WORDS_SECTION             = "hide_words_section"
        private const val KEY_SHOW_FURIGANA_INLINE          = "show_furigana_inline"
        private const val KEY_RESULTS_FONT_MIN_SP           = "results_font_min_sp"
        private const val KEY_RESULTS_FONT_MAX_SP           = "results_font_max_sp"
        private const val KEY_DEBUG_FORCE_SINGLE_SCREEN      = "debug_force_single_screen"
        private const val KEY_DEBUG_SHOW_OCR_BOXES           = "debug_show_ocr_boxes"
        private const val KEY_DEBUG_SHOW_DETECTION_LOG      = "debug_show_detection_log"
        private const val KEY_DEBUG_LIVE_MODE                = "debug_live_mode"
        private const val KEY_DEBUG_SAVE_OCR_SEED            = "debug_save_ocr_seed"
        private const val KEY_DEBUG_LOG_GROUPING             = "debug_log_grouping"
        private const val KEY_DEBUG_LOG_TRACE                = "debug_log_trace"
        const val KEY_HOTKEY_TRANSLATION                   = "hotkey_translation"
        const val KEY_HOTKEY_FURIGANA                      = "hotkey_furigana"
        const val KEY_HOTKEY_TRANSLATION_TAP               = "hotkey_translation_tap"
        const val KEY_HOTKEY_FURIGANA_TAP                  = "hotkey_furigana_tap"
        const val KEY_HOTKEY_CAPTURE_TAP                   = "hotkey_capture_tap"
        const val KEY_QUICK_TILE_ADDED                     = "quick_tile_added"
        /** Public so the in-app result header's "Show on screen" toggle can
         *  [observe] it and stay in sync with the Settings row. */
        const val KEY_HIDE_GAME_OVERLAYS                   = "hide_game_overlays"
        private const val KEY_LAST_UPDATE_CHECK            = "last_update_check"
        private const val KEY_LAST_YOMITAN_UPDATE_CHECK    = "last_yomitan_update_check"
        private const val KEY_YOMITAN_UPDATE_BACKFILL_DONE = "yomitan_update_backfill_done"
        private const val KEY_YOMITAN_ZIP_SWEEP_DONE       = "yomitan_zip_sweep_done"
        private const val KEY_LAST_YOMITAN_HEAL_ATTEMPT    = "last_yomitan_heal_attempt"
        private const val KEY_UPDATE_SKIP_TAG              = "update_skip_tag"
        private const val KEY_UPDATE_DOWNLOADED_TAG        = "update_downloaded_tag"
        /** Public so the Settings sheet can [observe] it — the row must repaint
         *  when a check completes, not only when the sheet is next resumed. */
        const val KEY_UPDATE_AVAILABLE_TAG                 = "update_available_tag"
        private const val KEY_TARGET_PACK_MIGRATION_DISMISSED = "target_pack_migration_dismissed"

        /**
         * True when more than one currently-capturable display is connected
         * (matches [capturableDisplays] — FLAG_PRIVATE excluded, STATE_ON
         * required). On a foldable this flips between true (unfolded /
         * both panels live) and false (folded — one panel STATE_OFF), which
         * is the right shape for all current call sites: the dim overlay
         * over the app's own window only matters when a second viewport is
         * actually live, and the simplified disable prompt assumes the user
         * has nowhere else to look. For "can the user see both the app and
         * the game at once?", use [isSingleScreen] instead — it additionally
         * accounts for Android multi-window mode.
         */
        fun hasMultipleDisplays(context: Context): Boolean {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return dm.capturableDisplays().size > 1
        }

        /**
         * True when InAppOnly mode is the right route for the current
         * device + selection state: user has explicitly opted into hiding
         * overlays, has a separate viewport for the app, AND has only one
         * display selected for capture. With multi-select the user has
         * implicitly chosen per-display overlays so [hideGameOverlays] no
         * longer makes sense — see [SettingsRenderer]'s inline disclosure.
         */
        fun shouldUseInAppOnlyMode(context: Context): Boolean {
            val prefs = Prefs(context)
            return prefs.hideGameOverlays
                && !isSingleScreen(context)
                && prefs.captureDisplayIds.size <= 1
        }

        /**
         * True when the user has only one visible viewport into PlayTranslate
         * and the game combined — i.e., NOT (two physical displays OR
         * MainActivity in Android multi-window mode alongside the game).
         *
         * Despite the name this is a viewport-count predicate, not a
         * physical-display predicate. Use [hasMultipleDisplays] if you
         * specifically need the physical topology.
         */
        fun isSingleScreen(context: Context): Boolean {
            if (BuildConfig.DEBUG) {
                val sp = context.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                if (sp.getBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, false)) return true
            }
            if (hasMultipleDisplays(context)) return false
            // Multi-window mode counts as two viewports (app + game visible
            // together), but only while MainActivity is actually foregrounded.
            // Otherwise a stale companion var from a killed activity could
            // latch a misleading "split-screen" signal after the user has
            // clearly left the app.
            if (MainActivity.isInForeground && MainActivity.isInMultiWindowMode) return false
            return true
        }

        val DEFAULT_REGION_LIST: List<RegionEntry> = listOf(
            RegionEntry("Full screen",  0.00f, 1.00f, id = "default_full"),
            RegionEntry("Bottom 50%",   0.50f, 1.00f, id = "default_bottom_50"),
            RegionEntry("Bottom 33%",   0.67f, 1.00f, id = "default_bottom_33"),
            RegionEntry("Bottom 25%",   0.75f, 1.00f, id = "default_bottom_25"),
            RegionEntry("Top 50%",      0.00f, 0.50f, id = "default_top_50"),
            RegionEntry("Top 33%",      0.00f, 0.33f, id = "default_top_33"),
        )
    }
}
