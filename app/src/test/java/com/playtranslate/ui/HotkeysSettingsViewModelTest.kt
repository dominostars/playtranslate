package com.playtranslate.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HotkeysSettingsViewModel] projects the hotkey + QS-tile state from [Prefs]
 * and writes back through it. These tests pin the projection seed + the
 * setters' write-through (the source-of-truth contract); the interactive a11y
 * gate, key-capture dialog, and StatusBarManager request live in the Activity
 * and are exercised on-device.
 */
@RunWith(RobolectricTestRunner::class)
class HotkeysSettingsViewModelTest {

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

    @Test fun `initial state reflects current prefs`() {
        Prefs(ctx).hotkeyTranslation = "3+4"
        val vm = HotkeysSettingsViewModel(app)
        assertEquals("3+4", vm.state.value.translationHotkey)
    }

    @Test fun `setTranslationHotkey writes through to prefs`() {
        val vm = HotkeysSettingsViewModel(app)
        vm.setTranslationHotkey("113+29")
        assertEquals("113+29", Prefs(ctx).hotkeyTranslation)
    }

    @Test fun `clearTranslationHotkey writes through to prefs`() {
        Prefs(ctx).hotkeyTranslation = "1+2"
        val vm = HotkeysSettingsViewModel(app)
        vm.clearTranslationHotkey()
        assertEquals("", Prefs(ctx).hotkeyTranslation)
    }

    @Test fun `setFuriganaHotkey writes through to prefs`() {
        val vm = HotkeysSettingsViewModel(app)
        vm.setFuriganaHotkey("57")
        assertEquals("57", Prefs(ctx).hotkeyFurigana)
    }

    @Test fun `markQuickTileAdded persists`() {
        val vm = HotkeysSettingsViewModel(app)
        vm.markQuickTileAdded()
        // Write-through to prefs (the source of truth); the cell's reactive
        // re-hide via addTileVisible is covered on-device.
        assertTrue(Prefs(ctx).quickTileAdded)
    }

    @Test @Config(sdk = [34])
    fun `add-tile cell stays visible but marked added once the tile is added`() {
        Prefs(ctx).quickTileAdded = true
        val vm = HotkeysSettingsViewModel(app)
        // On API 33+ the cell stays — it flips from the "add" CTA to a checked
        // "added" state rather than disappearing.
        assertTrue(vm.state.value.addTileVisible)
        assertTrue(vm.state.value.tileAdded)
    }

    @Test @Config(sdk = [31])
    fun `add-tile cell is absent below api 33`() {
        val vm = HotkeysSettingsViewModel(app)
        assertFalse(vm.state.value.addTileVisible)
    }

    @Test fun `furigana row is shown for the default Japanese source`() {
        // Default source language (cleared prefs) is Japanese, which has a
        // furigana hint layer — so the second hotkey row is offered.
        val vm = HotkeysSettingsViewModel(app)
        assertTrue(vm.state.value.showFuriganaRow)
    }
}
