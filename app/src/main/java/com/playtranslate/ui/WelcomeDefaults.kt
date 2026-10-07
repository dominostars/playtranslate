package com.playtranslate.ui

import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.TargetSelection
import java.util.Locale

/**
 * Pure helpers for the welcome onboarding page. Extracted from
 * [com.playtranslate.MainActivity] so the default-target logic is unit-testable
 * without Activity / Context scaffolding.
 */
object WelcomeDefaults {

    /**
     * Target to pre-populate in the welcome page when the user hasn't explicitly
     * picked one yet: code AND Chinese script, as one value, so the page cannot
     * commit the code alone. Prefers [deviceLocale]'s language if ML Kit supports
     * it AND it differs from [sourceCode] (ML Kit has no source→same-language
     * models — picking one would stall onboarding on a model-download error).
     * Falls back to English, which is guaranteed to differ from any supported
     * source. For a Chinese device the script follows the locale
     * ([ChineseScriptVariant.forLocale]): a Taiwan or Hong Kong device defaults
     * to its Traditional variant, not Simplified.
     *
     * [deviceLocale] defaults to the JVM's current locale so production callers
     * don't need to plumb it in; tests override it.
     */
    fun computeDefaultTarget(
        sourceCode: String,
        deviceLocale: Locale = Locale.getDefault(),
    ): TargetSelection {
        val deviceLang = deviceLocale.language
        val mlKitSupported = deviceLang in TranslateLanguage.getAllLanguages()
        if (!mlKitSupported || deviceLang == sourceCode) return TargetSelection(TranslateLanguage.ENGLISH)
        val variant =
            if (ChineseScriptVariant.isChineseTarget(deviceLang)) ChineseScriptVariant.forLocale(deviceLocale)
            else ChineseScriptVariant.SIMPLIFIED
        return TargetSelection(deviceLang, variant)
    }
}
