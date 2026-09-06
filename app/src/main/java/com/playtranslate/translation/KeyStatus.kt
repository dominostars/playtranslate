package com.playtranslate.translation

import androidx.annotation.StringRes

/**
 * Result of an on-save API-key validation ping.
 *
 * [Unreachable] is distinct from [Invalid] so the settings UI can stay
 * quiet when a custom OpenAI-compatible endpoint can't (or shouldn't) be
 * probed — only [Invalid] should produce a user-visible "wrong key" toast.
 *
 * [Ok] is a claim, not a shrug: it means the provider was asked and accepted
 * this key. An endpoint that answers 2xx whether or not a key is attached
 * (several OpenAI-compatible hosts serve /models publicly) proves nothing
 * about the key, and must report [Unreachable] — "we could not tell" — not
 * [Ok]. See [OpenAiBackend.validateKey].
 */
sealed class KeyStatus {
    data object Ok : KeyStatus()
    /** [explanationRes], when set, replaces the generic "rejected the key,
     *  double-check it at the console" alert body with a condition-specific
     *  one. Some rejections are of a VALID key the app cannot use (an
     *  Anthropic key not scoped to a workspace), where re-checking the key
     *  is the wrong advice. A resource id rather than text because the
     *  backends are Context-free, as with [BackendStatus]. */
    data class Invalid(
        val reason: String,
        @StringRes val explanationRes: Int? = null,
    ) : KeyStatus()
    data object Unreachable : KeyStatus()
}
