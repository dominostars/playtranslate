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

/**
 * [AnkiSettingsViewModel] projects the deck + card-type selection from [Prefs];
 * the edit-mapping row only shows for a non-default card type. Pins the
 * projection (incl. the `ankiModelId != -1L` gate) so a refactor can't quietly
 * surface the mapping row for the default card type.
 */
@RunWith(RobolectricTestRunner::class)
class AnkiSettingsViewModelTest {

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

    @Test fun `initial state reflects deck + card type`() {
        Prefs(ctx).apply {
            ankiDeckName = "Mining"
            ankiModelName = "Custom Note"
            ankiModelId = 42L
        }
        val vm = AnkiSettingsViewModel(app)
        assertEquals("Mining", vm.state.value.deckName)
        assertEquals("Custom Note", vm.state.value.cardTypeName)
        assertTrue(vm.state.value.showEditMapping)
    }

    @Test fun `default card type hides the edit-mapping row`() {
        Prefs(ctx).ankiModelId = -1L
        val vm = AnkiSettingsViewModel(app)
        assertFalse(vm.state.value.showEditMapping)
    }
}
