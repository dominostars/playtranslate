package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.HoldAction
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TapAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The gesture picker page's Hold and Tap rows on each kind of source
 * language: the auto-translate toggle and the game-language change are on
 * every one; the swap row is there, named after the language's reading
 * hint, only on a language that has one; elsewhere a stored swap shows as
 * its gesture's default, checked.
 * A language changed while the page is open (dual-screen: from the floating
 * menu on the other display) re-renders it.
 */
@RunWith(RobolectricTestRunner::class)
class IconGesturesSettingsRowsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = Prefs(ctx)

    private val showTranslations = "Show translations on screen"
    private val quickMenu = "Open the quick menu"
    private val capture = "Capture screen"
    private val toggle = "Start/stop auto translate"
    private val swapFurigana = "Swap between translation and furigana"
    private val swapPinyin = "Swap between translation and pinyin"
    private val changeLanguage = "Change game language"

    @Before fun setUp() {
        // androidx keeps one AndroidViewModelFactory per process, holding the
        // Application it was first asked for; Robolectric makes a new
        // Application per test, so without this the page's view model reads
        // an earlier test's preferences.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
        clearPrefs()
        prefs.iconHoldAction = HoldAction.SWAP_OVERLAY_MODE
        prefs.iconTapAction = TapAction.SWAP_OVERLAY_MODE
    }

    @After fun tearDown() = clearPrefs()

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `a Japanese source offers the furigana swap, checked where bound`() {
        prefs.sourceLang = "ja"
        val page = open()
        assertEquals(
            listOf(
                showTranslations to false, quickMenu to false, toggle to false,
                swapFurigana to true, changeLanguage to false,
            ),
            rows(page, R.id.optionsHold),
        )
        assertEquals(
            listOf(
                quickMenu to false, capture to false, toggle to false,
                swapFurigana to true, changeLanguage to false,
            ),
            rows(page, R.id.optionsTap),
        )
    }

    @Test fun `a Chinese source names the swap after pinyin`() {
        prefs.sourceLang = "zh"
        val page = open()
        assertEquals(
            listOf(
                showTranslations to false, quickMenu to false, toggle to false,
                swapPinyin to true, changeLanguage to false,
            ),
            rows(page, R.id.optionsHold),
        )
        assertEquals(
            listOf(
                quickMenu to false, capture to false, toggle to false,
                swapPinyin to true, changeLanguage to false,
            ),
            rows(page, R.id.optionsTap),
        )
    }

    @Test fun `a source without a reading hint has no swap row and checks the default`() {
        prefs.sourceLang = "en"
        val page = open()
        assertEquals(
            listOf(showTranslations to true, quickMenu to false, toggle to false, changeLanguage to false),
            rows(page, R.id.optionsHold),
        )
        assertEquals(
            listOf(quickMenu to true, capture to false, toggle to false, changeLanguage to false),
            rows(page, R.id.optionsTap),
        )
    }

    @Test fun `the game-language change is checked where bound, on a language without a reading hint too`() {
        prefs.sourceLang = "en"
        prefs.iconHoldAction = HoldAction.CHANGE_GAME_LANGUAGE
        prefs.iconTapAction = TapAction.CHANGE_GAME_LANGUAGE
        val page = open()
        assertEquals(
            listOf(showTranslations to false, quickMenu to false, toggle to false, changeLanguage to true),
            rows(page, R.id.optionsHold),
        )
        assertEquals(
            listOf(quickMenu to false, capture to false, toggle to false, changeLanguage to true),
            rows(page, R.id.optionsTap),
        )
    }

    @Test fun `a language changed while the page is open re-renders it`() {
        prefs.sourceLang = "ja"
        val page = open()
        prefs.sourceLang = "en"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(showTranslations to true, quickMenu to false, toggle to false, changeLanguage to false),
            rows(page, R.id.optionsHold),
        )
        assertEquals(
            listOf(quickMenu to true, capture to false, toggle to false, changeLanguage to false),
            rows(page, R.id.optionsTap),
        )
    }

    private fun open(): Activity {
        val page = Robolectric.buildActivity(IconGesturesSettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return page
    }

    /** [containerId]'s choice rows in order: title, and whether it's checked. */
    private fun rows(page: Activity, containerId: Int): List<Pair<String, Boolean>> {
        val container = page.findViewById<ViewGroup>(containerId)
        return (0 until container.childCount).map { container.getChildAt(it) }.mapNotNull { row ->
            val title = row.findViewById<TextView>(R.id.tvRowTitle) ?: return@mapNotNull null
            title.text.toString() to (row.findViewById<View>(R.id.ivCheck).visibility == View.VISIBLE)
        }
    }
}
