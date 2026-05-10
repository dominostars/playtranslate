package com.playtranslate.language

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [PackUpgradeOrchestrator]'s pure helpers. The full orchestration
 * flow (engine release, uninstall→install→ML-Kit priming sequencing,
 * cancel semantics) is exercised end-to-end via the manual verification
 * pass on Thor — mocking `LanguagePackStore.install`, `OverlayProgress`,
 * and `TranslationManager` would require either a full DI overhaul or
 * fragile reflection. The Robolectric pieces here cover the parts that
 * are testable cheaply.
 */
@RunWith(RobolectricTestRunner::class)
class PackUpgradeOrchestratorTest {

    private lateinit var ctx: Context
    private lateinit var activity: Activity

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        activity = Robolectric.buildActivity(Activity::class.java).get()
    }

    @Test fun `describeForAlert formats source pack as Game Language (display name)`() {
        val stale = listOf(
            StalePack(
                catalogKey = "ja",
                displayName = "Japanese",
                kind = PackKind.SOURCE,
                upgradeMode = UpgradeMode.ADDITIVE,
                sourceLangId = SourceLangId.JA,
            )
        )
        val message = PackUpgradeOrchestrator.describeForAlert(activity, stale)
        // "Game Language (...)" — the parenthesized name comes from
        // SourceLangId.displayName(default locale). On a default-en system
        // that's "Japanese". Mode is intentionally not surfaced in the
        // user-facing label.
        assertTrue(
            "Expected 'Game Language' label and Japanese name, got: $message",
            message.contains("Game Language") && message.contains("Japanese"),
        )
    }

    @Test fun `describeForAlert formats target pack as Your Language (display name)`() {
        val stale = listOf(
            StalePack(
                catalogKey = "target-fr",
                displayName = "French",
                kind = PackKind.TARGET,
                upgradeMode = UpgradeMode.FORCE,
                targetLangCode = "fr",
            )
        )
        val message = PackUpgradeOrchestrator.describeForAlert(activity, stale)
        assertTrue(
            "Expected 'Your Language' label and French name, got: $message",
            message.contains("Your Language") && message.contains("French"),
        )
    }

    @Test fun `describeForAlert renders multiple packs as separate lines`() {
        val stale = listOf(
            StalePack("ja", "Japanese", PackKind.SOURCE, UpgradeMode.ADDITIVE, sourceLangId = SourceLangId.JA),
            StalePack("target-fr", "French", PackKind.TARGET, UpgradeMode.FORCE, targetLangCode = "fr"),
        )
        val message = PackUpgradeOrchestrator.describeForAlert(activity, stale)
        assertEquals(
            "Two stale packs → exactly two lines (one per pack)",
            2, message.lines().size,
        )
    }

    @Test fun `describeForAlert mixes FORCE and ADDITIVE without distinguishing them`() {
        // Per the design (single combined overlay, mixed labeling), the user
        // doesn't see whether a pack is force-redownload or additive-upgrade.
        // Both render identically in the alert body.
        val additive = StalePack("ja", "Japanese", PackKind.SOURCE, UpgradeMode.ADDITIVE, sourceLangId = SourceLangId.JA)
        val force = StalePack("ja", "Japanese", PackKind.SOURCE, UpgradeMode.FORCE, sourceLangId = SourceLangId.JA)
        assertEquals(
            "FORCE and ADDITIVE render the same",
            PackUpgradeOrchestrator.describeForAlert(activity, listOf(additive)),
            PackUpgradeOrchestrator.describeForAlert(activity, listOf(force)),
        )
    }

    @Test fun `describeForAlert handles empty stale list as empty string`() {
        val message = PackUpgradeOrchestrator.describeForAlert(activity, emptyList())
        assertTrue(message.isEmpty())
    }
}
