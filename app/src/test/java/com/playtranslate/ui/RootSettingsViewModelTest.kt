package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [RootSettingsViewModel] projects the root Settings drill-down cells. This
 * pins the Anki conditional-cell state machine (get-app → grant → navigate) —
 * the logic the renderer now consumes via render() instead of computing inline
 * — plus the language-name projection and the TTS Loading seed. The power card
 * and stale-pack card are deliberately not modelled here (live-system-state,
 * still renderer-owned). Construction seeds [RootSettingsViewModel.state]
 * synchronously, so each case sets the world up first, then asserts on
 * `state.value`.
 */
@RunWith(RobolectricTestRunner::class)
class RootSettingsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ctx: Context = app

    @Before fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun installAnkiDroid() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ichi2.anki" },
        )
    }

    @Test fun `target name projects from prefs`() {
        Prefs(ctx).targetLang = "en"
        val vm = RootSettingsViewModel(app)
        assertEquals("English", vm.state.value.targetName)
    }

    @Test fun `source name is populated`() {
        Prefs(ctx).sourceLang = "ja"
        val vm = RootSettingsViewModel(app)
        assertTrue(vm.state.value.sourceName.isNotBlank())
    }

    @Test fun `anki cell — not installed → GetApp`() {
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GetApp, vm.state.value.anki)
    }

    @Test fun `anki cell — installed without permission → GrantAccess`() {
        installAnkiDroid()
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GrantAccess, vm.state.value.anki)
    }

    @Test fun `anki cell — installed + granted → Navigate`() {
        installAnkiDroid()
        shadowOf(app).grantPermissions(AnkiManager.PERMISSION)
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.Navigate, vm.state.value.anki)
    }

    @Test fun `tts cell seeds as Loading`() {
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.TtsCell.Loading, vm.state.value.tts)
    }

    @Test fun `refresh re-resolves the anki cell after the grant lands`() {
        installAnkiDroid()
        val vm = RootSettingsViewModel(app)
        assertEquals(RootSettingsViewModel.AnkiCell.GrantAccess, vm.state.value.anki)
        // Grant arrives while Settings is backgrounded — refresh() (called on
        // resume) must re-poll and flip the cell to Navigate.
        shadowOf(app).grantPermissions(AnkiManager.PERMISSION)
        vm.refresh()
        assertEquals(RootSettingsViewModel.AnkiCell.Navigate, vm.state.value.anki)
    }
}
