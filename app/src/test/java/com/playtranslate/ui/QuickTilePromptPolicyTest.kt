package com.playtranslate.ui

import com.playtranslate.ui.QuickTilePromptPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Who sees the end-of-onboarding "add the tile" prompt, and who is
 *  marked done without seeing it. */
class QuickTilePromptPolicyTest {

    private val onboarding = AppReadiness.Onboarding(AppReadiness.Step.CAPTURE)
    private val ready = AppReadiness.Ready(AppReadiness.Home.SINGLE_SCREEN)

    private fun decide(
        first: AppReadiness?,
        setUpAtFirst: Boolean = false,
        current: AppReadiness = ready,
        tileAdded: Boolean = false,
        promptDone: Boolean = false,
        sdk: Int = 34,
    ) = QuickTilePromptPolicy.decide(first, setUpAtFirst, current, tileAdded, promptDone, sdk)

    @Test fun `a user who just finished onboarding is prompted`() {
        assertEquals(Decision.PROMPT, decide(first = onboarding))
    }

    @Test fun `a user who was already set up is marked done unseen`() {
        assertEquals(Decision.MARK_DONE, decide(first = ready))
    }

    @Test fun `an existing user re-granting a permission is not a new user`() {
        // First derive Onboarding (the notification permission was taken
        // away), but a target language was already set: marked done unseen.
        assertEquals(Decision.MARK_DONE, decide(first = onboarding, setUpAtFirst = true))
    }

    @Test fun `nothing happens while still onboarding`() {
        assertEquals(Decision.NONE, decide(first = onboarding, current = onboarding))
    }

    @Test fun `done or added means never again`() {
        assertEquals(Decision.NONE, decide(first = onboarding, promptDone = true))
        assertEquals(Decision.NONE, decide(first = onboarding, tileAdded = true))
        assertEquals(Decision.NONE, decide(first = ready, promptDone = true))
    }

    @Test fun `below Android 13 there is no prompt to show, so it is marked done`() {
        assertEquals(Decision.MARK_DONE, decide(first = onboarding, sdk = 32))
    }

    @Test fun `no readiness yet means nothing`() {
        assertEquals(Decision.NONE, decide(first = null))
    }
}
