package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.allViews
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OneShotOverlayData
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import com.playtranslate.translation.FakeSwitchedBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.ChosenServiceTranslation.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The translation's gear on the in-app results page, through the real page
 * ([TranslationResultFragment]) in the activity host
 * [ResultsPageShowOnScreenTest] uses, whose game display is a stand-in for
 * the controller's window.
 *
 * A capture's result has the gear; the picker shows over the activity, and
 * its settings button starts Translation services there. A pick lands on
 * the page, and the capture's boxes that are up on the game display are
 * filled in place with the new translations: neither the gear's tap nor the
 * pick takes them down, as a word tap or an Anki button would.
 *
 * While auto-translate runs there is no gear (its next read would replace a
 * pick), for a page shown during the session and for one created during it;
 * when it stops, the result it left can be picked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h1200dp")
class ResultsPageTranslationGearTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(app)
    private lateinit var controller: ActivityController<ResultsPageShowOnScreenTest.Host>
    private lateinit var host: ResultsPageShowOnScreenTest.Host
    private lateinit var vm: TranslationResultViewModel

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        prefs.hideTranslationSection = false
        prefs.hideOriginalSection = false
        TranslationBackendRegistry.init(
            listOf(
                FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
                FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false),
            ),
        )
        controller = Robolectric.buildActivity(ResultsPageShowOnScreenTest.Host::class.java).setup()
        host = controller.get()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        idle()
        TranslationBackendRegistry.close()
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The page is created now, with the host as it stands. */
    private fun addPage() {
        host.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, TranslationResultFragment(), ResultsPageShowOnScreenTest.Host.PAGE)
            .commitNow()
        vm = ViewModelProvider(host)[TranslationResultViewModel::class.java]
        vm.chosenTranslation = { _, backend, _ -> Outcome.Translated(listOf("Kitty"), backend.displayName) }
        idle()
    }

    private val rect = Rect(100, 100, 300, 140)

    private fun boxes() = OnScreenBoxes(
        OneShotOverlayData(
            boxes = listOf(TextBox("Cat", rect)),
            cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
            cropWidth = 1280, cropHeight = 720, text = listOf(rect),
        ),
        displayId = 0, sourceLangId = SourceLangId.JA, captureId = 1L,
    )

    private fun result() = TranslationResult(
        originalText = "猫", segments = TextSegments.ofText("猫"), translatedText = "Cat",
        timestamp = "", backendDisplayName = "Lingva", langContext = prefs.langContext(),
    )

    private val row: View get() = host.findViewById(R.id.translationNoteRow)
    private val gearShown: Boolean
        get() = host.findViewById<View>(R.id.btnTranslationService).visibility == View.VISIBLE && row.isClickable

    private fun alertButton(label: String): Button =
        host.window.decorView.allViews.filterIsInstance<Button>().single { it.text.toString() == label }

    @Test fun `a pick lands on the page and fills the capture's boxes on the game display in place`() {
        addPage()
        prefs.dualScreenShowOnScreen = true
        vm.displayResult(result(), app, onScreenBoxes = boxes())
        idle()
        assertTrue("the capture went up as it landed", host.shown != null)
        assertTrue(gearShown)

        row.performClick()
        idle()
        assertTrue(
            "the picker is over the activity",
            host.window.decorView.allViews.filterIsInstance<TextView>()
                .any { it.text.toString() == host.getString(R.string.translation_picker_title) },
        )
        assertEquals("the gear's tap leaves the boxes up", 0, host.hides)

        alertButton("Gemini").performClick()
        idle()

        assertEquals("Kitty", host.findViewById<TextView>(R.id.tvTranslation).text.toString())
        assertEquals(
            host.getString(R.string.translation_source_label, "Gemini"),
            host.findViewById<TextView>(R.id.tvTranslationNote).text.toString(),
        )
        assertEquals("and so does the pick", 0, host.hides)
        assertEquals("filled in place with the new translation", "Kitty", host.shown!!.data.boxes.single().translatedText)
        assertEquals("the window was not put up again", 1, host.presents.size)
    }

    @Test fun `the picker's settings button starts Translation services from the activity`() {
        addPage()
        vm.displayResult(result(), app)
        idle()
        row.performClick()
        idle()

        alertButton(host.getString(R.string.settings_cell_translation_services)).performClick()
        idle()

        assertEquals(
            TranslationServicesActivity::class.java.name,
            shadowOf(host).nextStartedActivity?.component?.className,
        )
    }

    @Test fun `while auto-translate runs there is no gear, and the result it leaves has one when it stops`() {
        addPage()
        vm.displayResult(result(), app)
        idle()
        assertTrue("a capture's result", gearShown)

        host.liveRunning = true
        host.page.onLiveModeChanged(true)
        assertEquals("the credit is still said", View.VISIBLE, row.visibility)
        assertFalse("auto-translate's next read would replace a pick", gearShown)

        host.liveRunning = false
        host.page.onLiveModeChanged(false)
        assertTrue(gearShown)
    }

    @Test fun `a page created during auto-translate starts with no gear`() {
        host.liveRunning = true
        addPage()
        vm.displayResult(result(), app)
        idle()

        assertEquals(View.VISIBLE, row.visibility)
        assertFalse(gearShown)
    }
}
