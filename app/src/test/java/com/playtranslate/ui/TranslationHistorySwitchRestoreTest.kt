package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Translation History's three switch rows (recording, capture images, hide
 * translations) are settings_row_switch copies sharing one view id. A
 * recreate, which a rotation causes here since the activity handles no
 * config changes, used to restore every switch from the last row's saved
 * state, and the listeners wrote it: history recording and image capture
 * turned off. [SwitchRowSaveStateTest] guards the layouts; this pins the
 * screen the bug was proven on.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationHistorySwitchRestoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun recreate_keepsEachSwitchOnItsOwnSetting() {
        val prefs = Prefs(ctx)
        prefs.translationHistoryEnabled = true
        prefs.captureImageHistoryEnabled = true
        prefs.historyHideTranslations = false
        val controller = Robolectric.buildActivity(TranslationHistoryActivity::class.java).setup()
        try {
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(prefs.translationHistoryEnabled)
            assertTrue(prefs.captureImageHistoryEnabled)
            assertFalse(prefs.historyHideTranslations)
            val activity = controller.get()
            assertEquals(
                listOf(true, true, false),
                listOf(R.id.rowHistoryToggle, R.id.rowCaptureImageToggle, R.id.rowHideTranslationsToggle)
                    .map { row ->
                        activity.findViewById<View>(row)
                            .findViewById<MaterialSwitch>(R.id.switchRowToggle).isChecked
                    },
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
