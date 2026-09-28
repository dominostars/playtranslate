package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.security.SecretCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The Capture and overlay screen's "Filter furigana from OCR" row: under the
 * Minimum text size row, shown only on a Japanese source, and a user setting
 * (it used to be a debug-only row whose pref read as off on release builds).
 */
@RunWith(RobolectricTestRunner::class)
class FilterFuriganaRowTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        OcrManager.instance.filterFuriganaEnabled = true
    }

    private fun controller() =
        Robolectric.buildActivity(CaptureOverlaySettingsActivity::class.java).also { controller ->
            // setupDisplays() asks for the activity's display, which Robolectric's
            // activity context refuses; the Overlay group is built before it runs.
            val thrown = runCatching { controller.create() }.exceptionOrNull()
            assertTrue("$thrown", thrown == null || thrown is UnsupportedOperationException)
        }

    private fun activity(): CaptureOverlaySettingsActivity = controller().get()

    private val CaptureOverlaySettingsActivity.row
        get() = findViewById<View>(R.id.rowFilterFurigana)
    private val CaptureOverlaySettingsActivity.switch
        get() = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)

    @Test
    fun onAJapaneseSource_theRowSitsUnderMinimumTextSize() {
        Prefs(ctx).sourceLang = "ja"
        val a = activity()
        assertEquals(View.VISIBLE, a.row.visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.dividerFilterFurigana).visibility)
        assertEquals(
            a.getString(R.string.settings_filter_furigana_title),
            a.row.findViewById<TextView>(R.id.tvRowTitle).text.toString(),
        )
        val card = a.row.parent as android.view.ViewGroup
        val minText = card.indexOfChild(a.findViewById(R.id.rowOverlayMinText))
        assertEquals(minText + 1, card.indexOfChild(a.findViewById(R.id.dividerOverlayMinText)))
        assertEquals(minText + 2, card.indexOfChild(a.row))
        assertEquals(minText + 3, card.indexOfChild(a.findViewById(R.id.dividerFilterFurigana)))
        assertEquals(minText + 4, card.indexOfChild(a.findViewById(R.id.rowVerticalGrow)))
    }

    @Test
    fun onAnotherSource_theRowAndItsDividerAreHidden() {
        for (lang in listOf("zh", "en")) {
            Prefs(ctx).sourceLang = lang
            val a = activity()
            assertEquals(lang, View.GONE, a.row.visibility)
            assertEquals(lang, View.GONE, a.findViewById<View>(R.id.dividerFilterFurigana).visibility)
        }
    }

    @Test
    fun byDefault_itIsOn() {
        Prefs(ctx).sourceLang = "ja"
        assertTrue(Prefs(ctx).filterFurigana)
        assertTrue(activity().switch.isChecked)
    }

    @Test
    fun tappingTheRow_persists_andReachesTheOcrGate() {
        Prefs(ctx).sourceLang = "ja"
        val a = activity()
        assertTrue(a.switch.isChecked)
        a.row.performClick()
        assertFalse(a.switch.isChecked)
        assertFalse(Prefs(ctx).filterFurigana)
        assertFalse(OcrManager.instance.filterFuriganaEnabled)
        a.row.performClick()
        assertTrue(Prefs(ctx).filterFurigana)
        assertTrue(OcrManager.instance.filterFuriganaEnabled)
    }

    @Test
    fun aStoredValue_isShownOnOpen() {
        Prefs(ctx).apply { sourceLang = "ja"; filterFurigana = false }
        assertFalse(activity().switch.isChecked)
    }

    @Test
    fun aSourceChangeWhileAway_isPickedUpOnResume() {
        Prefs(ctx).sourceLang = "ja"
        val controller = controller()
        val a = controller.get()
        assertEquals(View.VISIBLE, a.row.visibility)
        Prefs(ctx).sourceLang = "en"
        controller.start().resume()
        assertEquals(View.GONE, a.row.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.dividerFilterFurigana).visibility)
    }

    @Test
    fun thePref_isHonouredOnReleaseBuilds() {
        assertTrue(Prefs(ctx, SecretCipher, debugBuild = false).filterFurigana)
        Prefs(ctx, SecretCipher, debugBuild = true).filterFurigana = false
        assertFalse(Prefs(ctx, SecretCipher, debugBuild = false).filterFurigana)
    }
}
