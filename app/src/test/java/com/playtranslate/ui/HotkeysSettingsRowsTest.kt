package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.Prefs
import com.playtranslate.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The Hotkeys page's "Change game language" row: the last row of the
 * Translations card, titled as the floating icon's gesture it runs, showing
 * and clearing its own binding and no other row's.
 */
@RunWith(RobolectricTestRunner::class)
class HotkeysSettingsRowsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)

    @Before fun setUp() {
        // The page's view model comes from androidx's process-wide factory,
        // which keeps the first test's Application; see
        // IconGesturesSettingsRowsTest.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        clearPrefs()
    }

    @After fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** [keyCode] as the page names it. Robolectric has no key-name table, so
     *  there the name is the code's digits. */
    private fun label(keyCode: Int) = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

    private fun open(): Activity {
        val page = Robolectric.buildActivity(HotkeysSettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return page
    }

    @Test fun `Change game language is the Translations card's last row and shows and clears its own binding`() {
        prefs.hotkeyCaptureTap = KeyEvent.KEYCODE_BUTTON_X.toString()
        prefs.hotkeyChangeGameLanguageTap = KeyEvent.KEYCODE_BUTTON_Y.toString()
        val page = open()
        val row = page.findViewById<View>(R.id.rowHotkeyChangeGameLanguageTap)
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)

        val card = row.parent as ViewGroup
        assertSame("the Translations card", page.findViewById<View>(R.id.rowHotkeyCaptureTap).parent, card)
        assertSame("its last row", row, card.getChildAt(card.childCount - 1))
        assertEquals("Change game language", row.findViewById<TextView>(R.id.tvRowTitle).text.toString())
        assertEquals(label(KeyEvent.KEYCODE_BUTTON_Y), subtitle.text.toString())
        assertTrue(switch.isChecked)

        // A tap on the row flips its switch; off clears the binding.
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("", prefs.hotkeyChangeGameLanguageTap)
        assertEquals("the capture row's binding stands", KeyEvent.KEYCODE_BUTTON_X.toString(), prefs.hotkeyCaptureTap)
        assertEquals("Not set", subtitle.text.toString())
        assertFalse(switch.isChecked)
    }
}
