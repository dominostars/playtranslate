package com.playtranslate.ui

import android.os.Build

/**
 * When to offer the system's "add PlayTranslate to Quick Settings" prompt:
 * once, to a user who has just finished onboarding, never to one who had
 * finished it before the prompt existed.
 *
 * The app persists no "onboarding completed" bit; readiness is re-derived
 * on every resume ([AppReadiness]). The signal is the first readiness this
 * PROCESS derived ([first], kept across activity recreation) and whether a
 * target language was already set at that moment ([setUpAtFirst], the
 * oldest thing onboarding writes, so an install that has it was set up
 * before this build or before this process):
 *  - first derive Ready: an existing user opening the new build, marked
 *    done without a prompt;
 *  - first derive Onboarding with a language already set: an existing user
 *    re-granting a permission Android took away (auto-reset after months
 *    unused, or revoked by hand), marked done without a prompt;
 *  - first derive Onboarding with no language yet: a new user, prompted at
 *    their first Ready.
 * A new user whose process dies between the language step and the end of
 * onboarding is read as the second case and misses the prompt: a miss,
 * never a nag, and the Hotkeys row still offers the tile. The tile is an
 * Android 13+ API; below that the prompt is marked done unseen.
 */
object QuickTilePromptPolicy {

    enum class Decision { PROMPT, MARK_DONE, NONE }

    fun decide(
        first: AppReadiness?,
        setUpAtFirst: Boolean,
        current: AppReadiness,
        tileAdded: Boolean,
        promptDone: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Decision {
        if (promptDone || tileAdded) return Decision.NONE
        if (current !is AppReadiness.Ready) return Decision.NONE
        return when (first) {
            null -> Decision.NONE
            is AppReadiness.Ready -> Decision.MARK_DONE
            is AppReadiness.Onboarding -> when {
                setUpAtFirst -> Decision.MARK_DONE
                sdkInt >= Build.VERSION_CODES.TIRAMISU -> Decision.PROMPT
                else -> Decision.MARK_DONE
            }
        }
    }
}
