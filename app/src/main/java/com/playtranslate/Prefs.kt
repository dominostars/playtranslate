package com.playtranslate

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import com.playtranslate.BuildConfig
import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.SourceLangId
import com.playtranslate.ui.AccentColor
import com.playtranslate.ui.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

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
class Prefs(context: Context) {

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

    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, TranslateLanguage.JAPANESE) ?: TranslateLanguage.JAPANESE
        set(v) = sp.edit { putString(KEY_SOURCE_LANG, v) }

    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = sp.edit { putString(KEY_TARGET_LANG, v) }

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

    /**
     * DeepL API key.  Defaults to the value baked into the build via
     * local.properties (your personal device build).  Empty on distributed
     * builds — user must enter their own key in Settings.
     */
    var deeplApiKey: String
        get() = sp.getString(KEY_DEEPL_KEY, BuildConfig.DEEPL_API_KEY) ?: ""
        set(v) = sp.edit { putString(KEY_DEEPL_KEY, v) }

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
        get() = sp.getString(KEY_GEMINI_KEY, "") ?: ""
        set(v) = sp.edit { putString(KEY_GEMINI_KEY, v) }

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
        get() = sp.getString(KEY_OPENAI_KEY, "") ?: ""
        set(v) = sp.edit { putString(KEY_OPENAI_KEY, v) }

    /** User's explicit "use OpenAI?" toggle. See [geminiEnabled] for the
     *  no-auto-enable rationale. */
    var openaiEnabled: Boolean
        get() = sp.getBoolean(KEY_OPENAI_ENABLED, false)
        set(v) = sp.edit { putBoolean(KEY_OPENAI_ENABLED, v) }

    /** OpenAI model id; "Custom…" entry persists arbitrary strings. */
    var openaiModel: String
        get() = sp.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL
        set(v) = sp.edit { putString(KEY_OPENAI_MODEL, v) }

    /** DeepSeek API key from https://platform.deepseek.com/api_keys. */
    var deepseekApiKey: String
        get() = sp.getString(KEY_DEEPSEEK_KEY, "") ?: ""
        set(v) = sp.edit { putString(KEY_DEEPSEEK_KEY, v) }

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
     * sentinel meaning "use the legacy PlayTranslate v004 model" — that
     * path bypasses the per-field mapping system entirely. Any other
     * value means the structured path looks up
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

    /** Whether new sentence cards include synthesized sentence audio.
     *  See [ankiWordAudioEnabled] — same last-used-state-is-the-default
     *  behavior, for the sentence review surface. */
    var ankiSentenceAudioEnabled: Boolean
        get() = sp.getBoolean(KEY_ANKI_SENTENCE_AUDIO, true)
        set(v) = sp.edit { putBoolean(KEY_ANKI_SENTENCE_AUDIO, v) }

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

    /** Capture method chosen during onboarding: "" = not set, "accessibility", "media_projection" */
    var captureMethod: String
        get() = sp.getString(KEY_CAPTURE_METHOD, "") ?: ""
        set(v) = sp.edit { putString(KEY_CAPTURE_METHOD, v) }

    var overlayMode: OverlayMode
        get() = OverlayMode.fromStorageName(sp.getString(KEY_OVERLAY_MODE, null))
        set(v) = sp.edit { putString(KEY_OVERLAY_MODE, v.name) }

    var hideGameOverlays: Boolean
        get() = sp.getBoolean("hide_game_overlays", false)
        set(v) = sp.edit { putBoolean("hide_game_overlays", v) }

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

    /** Hotkey combo for hold-to-show translations. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyTranslation: String
        get() = sp.getString(KEY_HOTKEY_TRANSLATION, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_TRANSLATION, v) }

    /** Hotkey combo for hold-to-show furigana. Empty = not set. Format: keyCodes joined by "+". */
    var hotkeyFurigana: String
        get() = sp.getString(KEY_HOTKEY_FURIGANA, "") ?: ""
        set(v) = sp.edit { putString(KEY_HOTKEY_FURIGANA, v) }

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
        get() = sp.getBoolean("quick_tile_added", false)
        set(v) = sp.edit { putBoolean("quick_tile_added", v) }

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

    /** Tag (e.g. "v1.2.0") the user explicitly skipped; suppresses re-prompting
     *  until a newer tag is published. */
    var updateCheckSkippedTag: String
        get() = sp.getString(KEY_UPDATE_SKIP_TAG, "") ?: ""
        set(v) = sp.edit { putString(KEY_UPDATE_SKIP_TAG, v) }


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

        private const val KEY_SOURCE_LANG    = "source_lang"
        private const val KEY_TARGET_LANG    = "target_lang"
        private const val KEY_DISPLAY_ID     = "capture_display_id"
        private const val KEY_DISPLAY_IDS    = "capture_display_ids"
        private const val KEY_SELECTED_REGION_ID = "selected_region_id"
        private const val KEY_SELECTED_REGION_BY_DISPLAY = "selected_region_by_display"
        private const val KEY_ICON_POSITION_BY_DISPLAY   = "icon_position_by_display"
        private const val KEY_ANKI_DECK_ID         = "anki_deck_id"
        private const val KEY_ANKI_DECK_NAME       = "anki_deck_name"
        private const val KEY_ANKI_MODEL_ID        = "anki_model_id"
        private const val KEY_ANKI_MODEL_NAME      = "anki_model_name"
        private const val KEY_ANKI_FIELD_MAPPINGS  = "anki_field_mappings"   // JSON
        private const val KEY_ANKI_WORD_AUDIO      = "anki_word_audio_enabled"
        private const val KEY_ANKI_SENTENCE_AUDIO  = "anki_sentence_audio_enabled"
        private const val KEY_ANKI_AUDIO_MAPPING_MIGRATED = "anki_audio_mapping_migrated"
        private const val KEY_REGION_LIST    = "region_list"
        private const val KEY_DEEPL_KEY      = "deepl_api_key"
        const val KEY_DEEPL_ENABLED          = "deepl_enabled"
        const val KEY_LINGVA_ENABLED         = "lingva_enabled"
        const val KEY_BERGAMOT_ENABLED       = "bergamot_enabled"
        const val KEY_QWEN_MNN_ENABLED   = "qwen_mnn_enabled"
        const val KEY_GEMMA_E2B_ENABLED  = "gemma_e2b_enabled"
        const val KEY_HYMT_ENABLED          = "hymt_enabled"
        const val KEY_HYMT_LEGAL_ACCEPTED   = "hymt_legal_accepted"
        const val KEY_GEMINI_KEY                    = "gemini_api_key"
        const val KEY_GEMINI_ENABLED                = "gemini_enabled"
        const val KEY_GEMINI_MODEL                  = "gemini_model"
        const val KEY_OPENAI_KEY                    = "openai_api_key"
        const val KEY_OPENAI_ENABLED                = "openai_enabled"
        const val KEY_OPENAI_MODEL                  = "openai_model"
        const val KEY_DEEPSEEK_KEY                  = "deepseek_api_key"
        const val KEY_DEEPSEEK_ENABLED              = "deepseek_enabled"
        const val KEY_DEEPSEEK_MODEL                = "deepseek_model"

        /** Default selected model — chosen to match the first entry in
         *  the picker after filtering + sorting (newest alias by
         *  `created` timestamp for OpenAI; highest stable version's
         *  alphabetical-first variant for Gemini). These are best
         *  guesses; the picker's listModels log line shows the actual
         *  top so we can adjust if wrong. */
        const val DEFAULT_OPENAI_MODEL    = "chat-latest"
        const val DEFAULT_GEMINI_MODEL    = "gemini-flash-lite-latest"
        // DeepSeek doesn't ship a rolling -latest alias (per api-docs.
        // deepseek.com). The legacy `deepseek-chat` / `deepseek-reasoner`
        // aliases are scheduled for retirement on 2026-07-24, both
        // currently route to v4-flash anyway. Pin directly to flash —
        // mirrors the "small/fast/cheap" default we use on Gemini
        // (flash-lite-latest).
        const val DEFAULT_DEEPSEEK_MODEL  = "deepseek-v4-flash"
        private const val KEY_LEGACY_THEME_INDEX    = "theme_index"
        private const val KEY_THEME_MODE            = "theme_mode"
        private const val KEY_ACCENT_NAME           = "accent_name"
        private const val KEY_CAPTURE_INTERVAL_SEC  = "capture_interval_sec"
        private const val KEY_CAPTURE_METHOD           = "capture_method"
        private const val KEY_OVERLAY_MODE               = "overlay_mode"
        private const val KEY_SETTINGS_SCROLL_Y        = "settings_scroll_y"
        private const val KEY_SHOW_OVERLAY_ICON       = "show_overlay_icon"
        private const val KEY_OVERLAY_ICON_EDGE      = "overlay_icon_edge"
        private const val KEY_OVERLAY_ICON_FRACTION  = "overlay_icon_fraction"
        private const val KEY_SUPPRESS_TRANSITION            = "suppress_next_transition"
        private const val KEY_SHOW_TRANSLITERATION             = "show_transliteration"
        private const val KEY_HIDE_TRANSLATION_SECTION       = "hide_translation_section"
        private const val KEY_HIDE_ORIGINAL_SECTION          = "hide_original_section"
        private const val KEY_HIDE_WORDS_SECTION             = "hide_words_section"
        private const val KEY_SHOW_FURIGANA_INLINE          = "show_furigana_inline"
        private const val KEY_DEBUG_FORCE_SINGLE_SCREEN      = "debug_force_single_screen"
        private const val KEY_DEBUG_SHOW_OCR_BOXES           = "debug_show_ocr_boxes"
        private const val KEY_DEBUG_SHOW_DETECTION_LOG      = "debug_show_detection_log"
        private const val KEY_DEBUG_LIVE_MODE                = "debug_live_mode"
        private const val KEY_DEBUG_SAVE_OCR_SEED            = "debug_save_ocr_seed"
        private const val KEY_DEBUG_LOG_GROUPING             = "debug_log_grouping"
        private const val KEY_HOTKEY_TRANSLATION           = "hotkey_translation"
        private const val KEY_HOTKEY_FURIGANA              = "hotkey_furigana"
        private const val KEY_LAST_UPDATE_CHECK            = "last_update_check"
        private const val KEY_UPDATE_SKIP_TAG              = "update_skip_tag"
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
