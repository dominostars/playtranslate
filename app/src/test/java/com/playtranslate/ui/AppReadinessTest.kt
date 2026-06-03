package com.playtranslate.ui

import com.playtranslate.ui.AppReadiness.Home
import com.playtranslate.ui.AppReadiness.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth-table coverage of the pure readiness derivation. No Android, no
 * Context — this is the dependency-free safety net for the onboarding gate.
 *
 * Note this only pins the (inputs -> state) *mapping*. The bidirectional flip
 * (revoke a permission -> drop back to a page) is a property of the reactive
 * gate driving this function repeatedly, exercised on-device, not here.
 */
class AppReadinessTest {

    private fun ready(
        languageConfigured: Boolean = true,
        notifGranted: Boolean = true,
        captureReady: Boolean = true,
        singleScreen: Boolean = false,
    ) = computeReadiness(languageConfigured, notifGranted, captureReady, singleScreen)

    // ── Step precedence: earlier-unsatisfied wins regardless of later inputs ──

    @Test
    fun `language outstanding takes precedence over everything`() {
        // notif/capture/screen all vary; while language is unconfigured the
        // gate must stay on the LANGUAGE step.
        for (notif in listOf(false, true)) {
            for (capture in listOf(false, true)) {
                for (single in listOf(false, true)) {
                    assertEquals(
                        AppReadiness.Onboarding(Step.LANGUAGE),
                        ready(languageConfigured = false, notifGranted = notif, captureReady = capture, singleScreen = single),
                    )
                }
            }
        }
    }

    @Test
    fun `notification is next once language is configured`() {
        for (capture in listOf(false, true)) {
            for (single in listOf(false, true)) {
                assertEquals(
                    AppReadiness.Onboarding(Step.NOTIFICATION),
                    ready(notifGranted = false, captureReady = capture, singleScreen = single),
                )
            }
        }
    }

    @Test
    fun `capture is last once language and notification are satisfied`() {
        for (single in listOf(false, true)) {
            assertEquals(
                AppReadiness.Onboarding(Step.CAPTURE),
                ready(captureReady = false, singleScreen = single),
            )
        }
    }

    // ── Ready: screen mode selects the home, and is a distinct value ─────────

    @Test
    fun `all satisfied on single screen yields Ready SINGLE_SCREEN`() {
        assertEquals(AppReadiness.Ready(Home.SINGLE_SCREEN), ready(singleScreen = true))
    }

    @Test
    fun `all satisfied on dual screen yields Ready DUAL`() {
        assertEquals(AppReadiness.Ready(Home.DUAL), ready(singleScreen = false))
    }

    @Test
    fun `single and dual Ready are not equal (so a hot-plug re-emits)`() {
        // The load-bearing property behind the screen-mode re-fork: the two
        // homes must be distinct values or a StateFlow conflates them.
        assertEquals(false, ready(singleScreen = true) == ready(singleScreen = false))
    }

    // ── Exhaustive 16-row truth table ───────────────────────────────────────

    @Test
    fun `full truth table`() {
        val cases = listOf(
            // language, notif, capture, single -> expected
            Case(false, false, false, false, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, false, false, true, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, false, true, false, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, false, true, true, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, true, false, false, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, true, false, true, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, true, true, false, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(false, true, true, true, AppReadiness.Onboarding(Step.LANGUAGE)),
            Case(true, false, false, false, AppReadiness.Onboarding(Step.NOTIFICATION)),
            Case(true, false, false, true, AppReadiness.Onboarding(Step.NOTIFICATION)),
            Case(true, false, true, false, AppReadiness.Onboarding(Step.NOTIFICATION)),
            Case(true, false, true, true, AppReadiness.Onboarding(Step.NOTIFICATION)),
            Case(true, true, false, false, AppReadiness.Onboarding(Step.CAPTURE)),
            Case(true, true, false, true, AppReadiness.Onboarding(Step.CAPTURE)),
            Case(true, true, true, false, AppReadiness.Ready(Home.DUAL)),
            Case(true, true, true, true, AppReadiness.Ready(Home.SINGLE_SCREEN)),
        )
        for (c in cases) {
            assertEquals(
                "lang=${c.language} notif=${c.notif} capture=${c.capture} single=${c.single}",
                c.expected,
                computeReadiness(c.language, c.notif, c.capture, c.single),
            )
        }
    }

    private data class Case(
        val language: Boolean,
        val notif: Boolean,
        val capture: Boolean,
        val single: Boolean,
        val expected: AppReadiness,
    )
}
