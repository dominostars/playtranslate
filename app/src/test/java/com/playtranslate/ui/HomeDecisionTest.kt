package com.playtranslate.ui

import com.playtranslate.ui.AppReadiness.Home
import com.playtranslate.ui.AppReadiness.Ready
import com.playtranslate.ui.AppReadiness.Onboarding
import com.playtranslate.ui.AppReadiness.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth-table coverage of the pure home-routing decision. No Android, no
 * Context — the dependency-free safety net for the single↔dual chrome logic that
 * is otherwise only checkable by sleeping a physical dual-screen device.
 *
 * Note single-screen excursions are intentionally not tab-tracked: returning to
 * dual lands on Settings (single forced it), and `KeepTab` keeps it.
 */
class HomeDecisionTest {

    private val dual = Ready(Home.DUAL)
    private val single = Ready(Home.SINGLE_SCREEN)

    // ── Entering SINGLE_SCREEN always forces Settings ────────────────────────

    @Test
    fun `single always forces Settings and passes restoredTab through untouched`() {
        // No capture of the outgoing tab — restoredTab is the recreation hint only.
        assertEquals(
            HomeDecision(HomeAction.ForceSettings, restoredTab = null),
            decideHome(dual, Home.SINGLE_SCREEN, restoredTab = null, requiresA11y = false),
        )
        assertEquals(
            HomeDecision(HomeAction.ForceSettings, restoredTab = Tab.REGIONS),
            decideHome(dual, Home.SINGLE_SCREEN, restoredTab = Tab.REGIONS, requiresA11y = false),
        )
        assertEquals(
            HomeDecision(HomeAction.ForceSettings, restoredTab = null),
            decideHome(null, Home.SINGLE_SCREEN, restoredTab = null, requiresA11y = false),
        )
    }

    // ── DUAL entry (prev !is Ready): recreation-saved tab, else default ──────

    @Test
    fun `dual from recreation re-selects the saved tab and consumes it`() {
        assertEquals(
            HomeDecision(HomeAction.ShowTab(Tab.REGIONS), restoredTab = null),
            decideHome(null, Home.DUAL, restoredTab = Tab.REGIONS, requiresA11y = false),
        )
    }

    @Test
    fun `dual entry default is Settings on MediaProjection, Translate otherwise`() {
        assertEquals(
            HomeDecision(HomeAction.ShowTab(Tab.SETTINGS), restoredTab = null),
            decideHome(null, Home.DUAL, restoredTab = null, requiresA11y = false),
        )
        assertEquals(
            HomeDecision(HomeAction.ShowTab(Tab.TRANSLATE), restoredTab = null),
            decideHome(null, Home.DUAL, restoredTab = null, requiresA11y = true),
        )
    }

    @Test
    fun `dual leaving onboarding picks the entry default`() {
        assertEquals(
            HomeDecision(HomeAction.ShowTab(Tab.SETTINGS), restoredTab = null),
            decideHome(Onboarding(Step.CAPTURE), Home.DUAL, restoredTab = null, requiresA11y = false),
        )
    }

    // ── DUAL while already Ready: keep the current tab ──────────────────────

    @Test
    fun `dual back from single keeps the current tab (Settings, since single forced it)`() {
        assertEquals(
            HomeDecision(HomeAction.KeepTab, restoredTab = null),
            decideHome(single, Home.DUAL, restoredTab = null, requiresA11y = false),
        )
    }

    @Test
    fun `plain dual resume keeps the tab and leaves the restore hint untouched`() {
        assertEquals(
            HomeDecision(HomeAction.KeepTab, restoredTab = null),
            decideHome(dual, Home.DUAL, restoredTab = null, requiresA11y = false),
        )
        assertEquals(
            HomeDecision(HomeAction.KeepTab, restoredTab = Tab.TRANSLATE),
            decideHome(dual, Home.DUAL, restoredTab = Tab.TRANSLATE, requiresA11y = false),
        )
    }
}
