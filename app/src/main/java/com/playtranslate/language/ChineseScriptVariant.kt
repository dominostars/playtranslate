package com.playtranslate.language

import java.util.Locale

/**
 * The Chinese script the user wants TARGET output (sentence translations +
 * dictionary glosses) rendered in. Deliberately orthogonal to [com.playtranslate.Prefs.targetLang],
 * which stays the backend code `"zh"` for ALL four variants: every translation
 * and gloss producer emits Simplified, and Traditional is a render-time OpenCC
 * transform (see [com.playtranslate.translation.ChineseScriptConverter]).
 *
 * Persisted in its own pref ([com.playtranslate.Prefs.targetChineseVariant]) so
 * the ~dozen sites that read `targetLang` as a backend / ML Kit / pack / cache
 * code keep seeing `"zh"` and stay correct by default — only conversion and
 * display labels read this enum. The default read is the safe read.
 */
enum class ChineseScriptVariant(val code: String) {
    SIMPLIFIED("zh-Hans"),
    TRADITIONAL("zh-Hant"),
    TRADITIONAL_TW("zh-Hant-TW"),
    TRADITIONAL_HK("zh-Hant-HK"),
    ;

    /**
     * Localized picker / settings label, e.g. "Chinese (Simplified)",
     * "Chinese (Traditional, Taiwan)". Uses `getDisplayName` (which includes the
     * script + region subtags) rather than `getDisplayLanguage` (which would
     * flatten all four to bare "Chinese"), exactly as [SourceLangId.displayName]
     * does for the `zh-Hant` source.
     */
    fun displayLabel(locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayName(locale)
            .replaceFirstChar { it.uppercase(locale) }

    companion object {
        /** The single backend / pack / cache code every variant collapses to.
         *  ML Kit, Bergamot, the `target-zh` gloss pack, the translation cache,
         *  and the same-language bypass all key on this — never on [code]. */
        const val BACKEND_CODE = "zh"

        val all: List<ChineseScriptVariant> = entries

        /** Parse a persisted [code]; unknown or null → [SIMPLIFIED]. Fail-safe:
         *  a stale or bad value shows Simplified, never crashes or mis-converts. */
        fun fromCode(code: String?): ChineseScriptVariant =
            entries.firstOrNull { it.code == code } ?: SIMPLIFIED

        /** True when [targetLang] selects Chinese — i.e. when the stored
         *  [com.playtranslate.Prefs.targetChineseVariant] is meaningful. The only
         *  Chinese backend code is [BACKEND_CODE]. */
        fun isChineseTarget(targetLang: String): Boolean = targetLang == BACKEND_CODE

        private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

        /** The variant a device [locale] reads: the Hant script or a Traditional
         *  region (TW, HK, MO) picks the matching Traditional variant, anything else
         *  Simplified. The source side (`SourceLangId.fromCode`) folds the same
         *  regions onto ZH_HANT. Drives the welcome page's default target and the
         *  target picker's suggested Chinese row, so a Taiwan or Hong Kong device
         *  is not offered Simplified first. */
        fun forLocale(locale: Locale): ChineseScriptVariant {
            val region = locale.country.uppercase(Locale.ROOT)
            if (locale.script != "Hant" && region !in TRADITIONAL_REGIONS) return SIMPLIFIED
            return when (region) {
                "TW" -> TRADITIONAL_TW
                "HK", "MO" -> TRADITIONAL_HK
                else -> TRADITIONAL
            }
        }

        /** Human label for a target language: the script-qualified Chinese
         *  [variant] label (e.g. "Chinese (Traditional, Taiwan)") when [targetLang]
         *  is Chinese, else the plain language name. [variant] is ignored for
         *  non-Chinese targets. Used by Settings / onboarding / result-panel rows
         *  where bare `getDisplayLanguage` would flatten all four to "Chinese". */
        fun targetDisplayName(
            targetLang: String,
            variant: ChineseScriptVariant,
            locale: Locale = Locale.getDefault(),
        ): String =
            if (isChineseTarget(targetLang)) variant.displayLabel(locale)
            else Locale.forLanguageTag(targetLang).getDisplayLanguage(locale)
                .replaceFirstChar { it.uppercase(locale) }
    }
}

/** A target language pick: the backend code plus, for Chinese, the script. The
 *  two are persisted together ([com.playtranslate.Prefs.setTarget]) and computed
 *  together (`WelcomeDefaults.computeDefaultTarget`), so no writer can set one
 *  without the other. */
data class TargetSelection(
    val code: String,
    val chineseVariant: ChineseScriptVariant = ChineseScriptVariant.SIMPLIFIED,
)
