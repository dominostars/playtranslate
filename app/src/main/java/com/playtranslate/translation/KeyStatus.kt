package com.playtranslate.translation

/**
 * Result of an on-save API-key validation ping.
 *
 * [Unreachable] is distinct from [Invalid] so the settings UI can stay
 * quiet when a custom OpenAI-compatible endpoint can't (or shouldn't) be
 * probed — only [Invalid] should produce a user-visible "wrong key" toast.
 */
sealed class KeyStatus {
    data object Ok : KeyStatus()
    data class Invalid(val reason: String) : KeyStatus()
    data object Unreachable : KeyStatus()
}
