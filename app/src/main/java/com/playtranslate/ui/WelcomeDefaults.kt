package com.playtranslate.ui

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Pure helpers for the welcome onboarding page. Extracted from
 * [com.playtranslate.MainActivity] so the default-target logic is unit-testable
 * without Activity / Context scaffolding.
 */
object WelcomeDefaults {

    /**
     * Target language to pre-populate in the welcome page when the user hasn't
     * explicitly picked one yet. Prefers [deviceLang] if ML Kit supports it
     * AND it differs from [sourceCode] (ML Kit has no source→same-language
     * models — picking one would stall onboarding on a model-download error).
     * Falls back to English, which is guaranteed to differ from any supported
     * source.
     *
     * [deviceLang] defaults to the JVM's current locale so production callers
     * don't need to plumb it in; tests override it.
     */
    fun computeDefaultTarget(
        sourceCode: String,
        deviceLang: String = Locale.getDefault().language,
    ): String {
        val mlKitSupported = deviceLang in TranslateLanguage.getAllLanguages()
        return if (mlKitSupported && deviceLang != sourceCode) deviceLang
        else TranslateLanguage.ENGLISH
    }
}
