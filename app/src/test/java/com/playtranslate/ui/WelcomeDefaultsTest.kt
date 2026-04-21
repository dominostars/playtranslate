package com.playtranslate.ui

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [WelcomeDefaults.computeDefaultTarget].
 *
 * Guards the default-target logic used when a welcome-page user taps
 * Continue without explicitly picking a target. Regressions here can
 * silently switch upgrade users' translations or, in the source-equals-
 * device-locale case, send onboarding into an un-downloadable same-
 * language ML Kit model.
 */
class WelcomeDefaultsTest {

    @Test fun `device locale English with JA source stays English`() {
        assertEquals(
            TranslateLanguage.ENGLISH,
            WelcomeDefaults.computeDefaultTarget(sourceCode = "ja", deviceLang = "en"),
        )
    }

    @Test fun `device locale Spanish with JA source picks Spanish`() {
        assertEquals(
            "es",
            WelcomeDefaults.computeDefaultTarget(sourceCode = "ja", deviceLang = "es"),
        )
    }

    @Test fun `device locale French with ZH source picks French`() {
        assertEquals(
            "fr",
            WelcomeDefaults.computeDefaultTarget(sourceCode = "zh", deviceLang = "fr"),
        )
    }

    // ── Source-equals-locale exclusion ───────────────────────────────────

    @Test fun `JA device locale with JA source falls back to English`() {
        assertEquals(
            TranslateLanguage.ENGLISH,
            WelcomeDefaults.computeDefaultTarget(sourceCode = "ja", deviceLang = "ja"),
        )
    }

    @Test fun `ZH device locale with ZH source falls back to English`() {
        assertEquals(
            TranslateLanguage.ENGLISH,
            WelcomeDefaults.computeDefaultTarget(sourceCode = "zh", deviceLang = "zh"),
        )
    }

    @Test fun `KO device locale with KO source falls back to English`() {
        assertEquals(
            TranslateLanguage.ENGLISH,
            WelcomeDefaults.computeDefaultTarget(sourceCode = "ko", deviceLang = "ko"),
        )
    }

    // ── Unsupported locale fallback ──────────────────────────────────────

    @Test fun `ML Kit-unsupported device locale falls back to English`() {
        // "xx" is not a real ML Kit-supported target.
        assertEquals(
            TranslateLanguage.ENGLISH,
            WelcomeDefaults.computeDefaultTarget(sourceCode = "ja", deviceLang = "xx"),
        )
    }
}
