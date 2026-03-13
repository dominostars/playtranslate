package com.gamelens

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import com.gamelens.BuildConfig
import com.google.mlkit.nl.translate.TranslateLanguage
import org.json.JSONArray
import org.json.JSONObject

/** A named capture region expressed as fractions of the screen dimensions. */
data class RegionEntry(
    val label: String,
    val top: Float,
    val bottom: Float,
    val left: Float = 0f,
    val right: Float = 1f
)

/**
 * Simple wrapper around [SharedPreferences] for persisting user settings.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString(KEY_SOURCE_LANG, TranslateLanguage.JAPANESE) ?: TranslateLanguage.JAPANESE
        set(v) = sp.edit().putString(KEY_SOURCE_LANG, v).apply()

    var targetLang: String
        get() = sp.getString(KEY_TARGET_LANG, TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
        set(v) = sp.edit().putString(KEY_TARGET_LANG, v).apply()

    var captureDisplayId: Int
        get() = sp.getInt(KEY_DISPLAY_ID, 0)
        set(v) = sp.edit().putInt(KEY_DISPLAY_ID, v).apply()

    var captureRegionIndex: Int
        get() = sp.getInt(KEY_REGION, 0)
        set(v) = sp.edit().putInt(KEY_REGION, v).apply()

    /**
     * DeepL API key.  Defaults to the value baked into the build via
     * local.properties (your personal device build).  Empty on distributed
     * builds — user must enter their own key in Settings.
     */
    var deeplApiKey: String
        get() = sp.getString(KEY_DEEPL_KEY, BuildConfig.DEEPL_API_KEY) ?: ""
        set(v) = sp.edit().putString(KEY_DEEPL_KEY, v).apply()

    var ankiDeckId: Long
        get() = sp.getLong(KEY_ANKI_DECK_ID, -1L)
        set(v) = sp.edit().putLong(KEY_ANKI_DECK_ID, v).apply()

    var ankiDeckName: String
        get() = sp.getString(KEY_ANKI_DECK_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_ANKI_DECK_NAME, v).apply()

    var hideLiveMode: Boolean
        get() = sp.getBoolean(KEY_HIDE_LIVE_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_HIDE_LIVE_MODE, v).apply()

    var hideTranslation: Boolean
        get() = sp.getBoolean(KEY_HIDE_TRANSLATION, false)
        set(v) = sp.edit().putBoolean(KEY_HIDE_TRANSLATION, v).apply()

    var showTransliteration: Boolean
        get() = sp.getBoolean(KEY_SHOW_TRANSLITERATION, false)
        set(v) = sp.edit().putBoolean(KEY_SHOW_TRANSLITERATION, v).apply()

    /** Capture method chosen during onboarding: "" = not set, "accessibility", "media_projection" */
    var captureMethod: String
        get() = sp.getString(KEY_CAPTURE_METHOD, "") ?: ""
        set(v) = sp.edit().putString(KEY_CAPTURE_METHOD, v).apply()

    /** Capture interval for live mode in seconds. Minimum 1. */
    var captureIntervalSec: Int
        get() = sp.getInt(KEY_CAPTURE_INTERVAL_SEC, 1).coerceAtLeast(1)
        set(v) = sp.edit().putInt(KEY_CAPTURE_INTERVAL_SEC, v.coerceAtLeast(1)).apply()

    /** Saved scroll position for the settings sheet (restored after theme recreate). */
    var settingsScrollY: Int
        get() = sp.getInt(KEY_SETTINGS_SCROLL_Y, 0)
        set(v) = sp.edit().putInt(KEY_SETTINGS_SCROLL_Y, v).apply()

    /** Whether the floating overlay icon is shown on the game screen. */
    var showOverlayIcon: Boolean
        get() = sp.getBoolean(KEY_SHOW_OVERLAY_ICON, true)
        set(v) = sp.edit().putBoolean(KEY_SHOW_OVERLAY_ICON, v).apply()

    /** Edge the overlay icon is snapped to: 0=LEFT, 1=RIGHT, 2=TOP, 3=BOTTOM. */
    var overlayIconEdge: Int
        get() = sp.getInt(KEY_OVERLAY_ICON_EDGE, 1).let { if (it in 0..3) it else 1 }
        set(v) = sp.edit().putInt(KEY_OVERLAY_ICON_EDGE, v).apply()

    /** Fraction (0..1) along the snapped edge. 0.5 = middle. */
    var overlayIconFraction: Float
        get() = sp.getFloat(KEY_OVERLAY_ICON_FRACTION, 0.5f).coerceIn(0f, 1f)
        set(v) = sp.edit().putFloat(KEY_OVERLAY_ICON_FRACTION, v).apply()

    /** Debug-only: forces isSingleScreen() to return true regardless of actual display count. */
    var debugForceSingleScreen: Boolean
        get() = sp.getBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, false)
        set(v) = sp.edit().putBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, v).apply()

    /** Debug-only: show OCR bounding boxes overlaid on the game screen after each capture. */
    var debugShowOcrBoxes: Boolean
        get() = sp.getBoolean(KEY_DEBUG_SHOW_OCR_BOXES, false)
        set(v) = sp.edit().putBoolean(KEY_DEBUG_SHOW_OCR_BOXES, v).apply()

    /** Set before recreate() so MainActivity suppresses the window transition animation. */
    var suppressNextTransition: Boolean
        get() = sp.getBoolean(KEY_SUPPRESS_TRANSITION, false)
        set(v) = sp.edit().putBoolean(KEY_SUPPRESS_TRANSITION, v).apply()

    /** 0 = Black, 1 = White, 2 = Rainbow, 3 = Purple */
    var themeIndex: Int
        get() = sp.getInt(KEY_THEME_INDEX, 0)
        set(v) = sp.edit().putInt(KEY_THEME_INDEX, v).apply()

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
                    right  = o.optDouble("right", 1.0).toFloat()
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
            })
        }
        sp.edit().putString(KEY_REGION_LIST, arr.toString()).apply()
    }

    companion object {
        private const val KEY_SOURCE_LANG    = "source_lang"
        private const val KEY_TARGET_LANG    = "target_lang"
        private const val KEY_DISPLAY_ID     = "capture_display_id"
        private const val KEY_REGION         = "capture_region"
        private const val KEY_ANKI_DECK_ID   = "anki_deck_id"
        private const val KEY_ANKI_DECK_NAME = "anki_deck_name"
        private const val KEY_REGION_LIST    = "region_list"
        private const val KEY_DEEPL_KEY      = "deepl_api_key"
        private const val KEY_HIDE_LIVE_MODE        = "hide_live_mode"
        private const val KEY_HIDE_TRANSLATION      = "hide_translation"
        private const val KEY_THEME_INDEX           = "theme_index"
        private const val KEY_CAPTURE_INTERVAL_SEC  = "capture_interval_sec"
        private const val KEY_CAPTURE_METHOD           = "capture_method"
        private const val KEY_SETTINGS_SCROLL_Y        = "settings_scroll_y"
        private const val KEY_SHOW_OVERLAY_ICON       = "show_overlay_icon"
        private const val KEY_OVERLAY_ICON_EDGE      = "overlay_icon_edge"
        private const val KEY_OVERLAY_ICON_FRACTION  = "overlay_icon_fraction"
        private const val KEY_SUPPRESS_TRANSITION            = "suppress_next_transition"
        private const val KEY_SHOW_TRANSLITERATION             = "show_transliteration"
        private const val KEY_DEBUG_FORCE_SINGLE_SCREEN      = "debug_force_single_screen"
        private const val KEY_DEBUG_SHOW_OCR_BOXES           = "debug_show_ocr_boxes"

        /** Single source of truth for single-screen detection. */
        fun isSingleScreen(context: Context): Boolean {
            if (BuildConfig.DEBUG) {
                val sp = context.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
                if (sp.getBoolean(KEY_DEBUG_FORCE_SINGLE_SCREEN, false)) return true
            }
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return dm.displays.size <= 1
        }

        val DEFAULT_REGION_LIST: List<RegionEntry> = listOf(
            RegionEntry("Full screen",  0.00f, 1.00f),
            RegionEntry("Bottom 50%",   0.50f, 1.00f),
            RegionEntry("Bottom 33%",   0.67f, 1.00f),
            RegionEntry("Bottom 25%",   0.75f, 1.00f),
            RegionEntry("Top 50%",      0.00f, 0.50f),
            RegionEntry("Top 33%",      0.00f, 0.33f),
        )
    }
}
