package com.playtranslate.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AppearanceViewModel] is a projection over [Prefs] (the source of truth):
 * [AppearanceViewModel.state] seeds from prefs, and the setters write through
 * to prefs rather than mutating VM-owned state. These tests pin that contract
 * — the H2 invariant of the settings MVVM migration — so a future refactor
 * can't quietly turn the VM back into an authoritative store that drifts from
 * prefs. Also checks the [Prefs.observe] primitive seeds an emission, which
 * the projection depends on.
 */
@RunWith(RobolectricTestRunner::class)
class AppearanceViewModelTest {

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

    @Test fun `observe seeds an immediate emission`() = runBlocking {
        // The seed is what makes the VM render the current value on first
        // collection — without it the page would be blank until the first
        // change. first() returns only if the seed arrives.
        withTimeout(2_000) { Prefs(ctx).observe(Prefs.KEY_THEME_MODE).first() }
    }

    @Test fun `initial state reflects current prefs`() {
        Prefs(ctx).apply {
            themeMode = ThemeMode.DARK
            accentName = AccentColor.Rose.name
        }
        val vm = AppearanceViewModel(app)
        assertEquals(ThemeMode.DARK, vm.state.value.themeMode)
        assertEquals(AccentColor.Rose, vm.state.value.accent)
    }

    @Test fun `setThemeMode writes through to prefs`() {
        val vm = AppearanceViewModel(app)
        vm.setThemeMode(ThemeMode.LIGHT)
        // Read back through a fresh Prefs to confirm it persisted, not just
        // cached in the VM.
        assertEquals(ThemeMode.LIGHT, Prefs(ctx).themeMode)
    }

    @Test fun `setAccent writes through to prefs`() {
        val vm = AppearanceViewModel(app)
        vm.setAccent(AccentColor.Violet)
        assertEquals(AccentColor.Violet, Prefs(ctx).accent)
    }
}
