package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlaySurface
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.FakeSpeech
import com.playtranslate.audio.AudioRequest
import com.playtranslate.ask.Explanation
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import com.playtranslate.themeColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The results page's Show on screen on two screens, through the real page
 * ([TranslationResultFragment]) in an activity host whose game display is a
 * stand-in for the controller's window: it puts a capture up in the mode
 * two screens read ([twoScreenMode]) and refuses None, as
 * [com.playtranslate.OverlayUiController.presentCapture] does, and pokes the
 * page synchronously when the window goes, as its dismissal does. The
 * toggle is a saved setting ([Prefs.dualScreenShowOnScreen]), off until
 * turned on: offered only with a capture's result on the page, on two
 * screens, with auto-translate off; its look is the setting. A capture goes
 * up by itself only while it is on, or when it asked for a mode of its own
 * (tap-to-lookup's Definitions, which leaves the setting as it is). A flip
 * saves it and applies it to the capture on the page at once: on puts the
 * overlay up as the user's ask (no capture id), off takes it down and keeps
 * the next captures down. The pill's None turns it off from elsewhere; the
 * window going by its own means (a tap on the game) leaves it on. The
 * window's switch of mode runs a deferred translation Translations now
 * need. During auto-translate the toggle is the hide-overlays setting.
 */
@RunWith(RobolectricTestRunner::class)
// Wide enough that the Translation header holds Show on screen inline.
@Config(qualifiers = "w900dp-h1200dp")
class ResultsPageShowOnScreenTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(app)
    private lateinit var controller: ActivityController<Host>
    private lateinit var host: Host
    private lateinit var vm: TranslationResultViewModel

    /** The page's activity, its game display a stand-in. */
    class Host : FragmentActivity(), TranslationResultFragment.TranslationResultHost {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }

        val page: TranslationResultFragment
            get() = supportFragmentManager.findFragmentByTag(PAGE) as TranslationResultFragment

        var twoScreens = true
        var live: Boolean? = null
        var liveRunning = false
        var completions = 0
        val liveSets = mutableListOf<Boolean>()

        /** Every put-up asked, with the mode it resolved to. */
        val presents = mutableListOf<Pair<OnScreenBoxes, OverlayMode>>()
        var hides = 0
        var shown: OnScreenBoxes? = null
        var shownMode: OverlayMode? = null

        override fun showResultBoxesOnScreen(boxes: OnScreenBoxes) {
            val prefs = Prefs(this)
            val mode = twoScreenMode(
                boxes.modeOverride,
                prefs.overlayModeFor(OverlaySurface.CAPTURE),
                prefs.overlayModeFor(OverlaySurface.LIVE),
            )
            presents += boxes to mode
            if (mode == OverlayMode.NONE) return
            shown = boxes
            shownMode = mode
        }

        override fun updateResultBoxesOnScreen(boxes: OnScreenBoxes) {
            if (shown != null) shown = boxes
        }

        override fun hideResultBoxesOnScreen() {
            if (shown == null) return
            hides++
            windowGone()
        }

        /** The window went by its own means (a tap on the game, None on its
         *  pill): disowned, and the page poked, as the controller does. */
        fun windowGone() {
            shown = null
            shownMode = null
            page.onScreenBoxesChanged()
        }

        /** The window switched its mode in place (a pick on its pill). */
        fun windowSwitched(mode: OverlayMode) {
            shownMode = mode
            page.onScreenBoxesChanged()
        }

        override fun supportsShowOnScreen() = twoScreens
        override fun isResultBoxesShownOnScreen() = shown != null
        override fun captureModeOnScreen() = shownMode
        override fun liveShowOnScreenState() = live
        override fun setLiveShowOnScreen(on: Boolean) {
            liveSets += on
            live = on
        }
        override fun isLiveModeRunning() = liveRunning
        override fun completeDeferredTranslation() { completions++ }

        override fun getCaptureService(): CaptureService? = null
        override fun onWordTapped(
            word: String,
            reading: String?,
            surface: String?,
            screenshotPath: String?,
            sentenceOriginal: String?,
            sentenceTranslation: String?,
            wordResults: Map<String, Triple<String, String, Int>>,
            lookupForm: String,
            lookupReading: String?,
            explanation: Explanation?,
        ) {}
        override fun onInteraction() {}
        override fun getAnkiPermissionLauncher(): androidx.activity.result.ActivityResultLauncher<String>? = null
        override fun onEditOriginalRequested() {}
        override fun onReOcrRequested(deviceOnly: Boolean) {}
        override fun canReOcr() = false
        override fun onChangeLanguageRequested(isSource: Boolean) {}
        override fun onUserScrolled() {}
        override fun showsClearAction() = true

        companion object {
            const val PAGE = "page"
        }
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        prefs.hideTranslationSection = false
        prefs.hideOriginalSection = false
        controller = Robolectric.buildActivity(Host::class.java).setup()
        host = controller.get()
        host.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, TranslationResultFragment(), Host.PAGE)
            .commitNow()
        vm = ViewModelProvider(host)[TranslationResultViewModel::class.java]
        idle()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        idle()
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val rect = Rect(100, 100, 300, 140)
    private val data = OneShotOverlayData(
        boxes = listOf(TextBox("hi", rect)),
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(rect),
    )

    /** A capture's boxes as the app's capture collector hands them on. */
    private fun boxes(captureId: Long? = 1L, override: OverlayMode? = null) =
        OnScreenBoxes(data, displayId = 0, sourceLangId = SourceLangId.JA, modeOverride = override, captureId = captureId)

    private fun result(pending: PendingTranslation? = null) = TranslationResult(
        originalText = "猫", segments = TextSegments.ofText("猫"), translatedText = if (pending == null) "Cat" else "",
        timestamp = "", pendingTranslation = pending, langContext = prefs.langContext(),
    )

    /** A capture's result lands on the page with [b]. */
    private fun capture(b: OnScreenBoxes = boxes(), pending: PendingTranslation? = null) {
        vm.displayResult(result(pending), app, onScreenBoxes = b)
        idle()
    }

    /** The Show on screen pill that shows, the Translation header's or, with
     *  that section hidden, the Original's; null while neither is offered. */
    private fun button(): TextView? {
        idle()
        return listOf(R.id.btnShowOnScreen, R.id.btnShowOnScreenOriginal)
            .map { host.findViewById<TextView>(it) }
            .firstOrNull { it.visibility == View.VISIBLE }
    }

    private fun offered(): Boolean = button() != null

    /** The pill in its ON look. */
    private fun on(): Boolean = button()!!.currentTextColor == host.themeColor(R.attr.ptAccentOn)

    private fun tap() {
        button()!!.performClick()
        idle()
    }

    @Test fun `before a capture it is not offered`() {
        assertFalse(offered())
    }

    @Test fun `off by default, a capture on two screens stays down, and the toggle is offered and off`() {
        capture()
        assertTrue(host.presents.isEmpty())
        assertTrue(offered())
        assertFalse(on())
        assertFalse(prefs.dualScreenShowOnScreen)
    }

    @Test fun `on one screen it is not offered and nothing goes up, whatever the setting`() {
        host.twoScreens = false
        prefs.dualScreenShowOnScreen = true
        capture()
        assertTrue(host.presents.isEmpty())
        assertFalse(offered())
    }

    @Test fun `turning it on saves it and puts the capture on the page up as the user's ask, in the remembered mode`() {
        prefs.captureOverlayMode = OverlayMode.FURIGANA
        capture()

        tap()

        assertTrue(prefs.dualScreenShowOnScreen)
        val (asked, mode) = host.presents.single()
        assertNull("the user asked: nothing checks the game screen", asked.captureId)
        assertEquals(OverlayMode.FURIGANA, mode)
        assertTrue(on())
        assertEquals(OverlayMode.FURIGANA, prefs.captureOverlayMode)
    }

    @Test fun `with it on, each capture comes up by itself as it lands`() {
        prefs.dualScreenShowOnScreen = true
        capture(boxes(captureId = 1L))
        capture(boxes(captureId = 2L))

        assertEquals(listOf(1L, 2L), host.presents.map { it.first.captureId })
        assertTrue(on())
    }

    @Test fun `turning it off takes the overlay down, and the next capture stays down`() {
        prefs.dualScreenShowOnScreen = true
        capture(boxes(captureId = 1L))
        assertTrue(on())

        tap()

        assertEquals(1, host.hides)
        assertFalse(prefs.dualScreenShowOnScreen)
        assertFalse(on())
        capture(boxes(captureId = 2L))
        assertEquals("nothing more went up", 1, host.presents.size)
        assertNull(host.shown)
        assertFalse(on())
    }

    @Test fun `a tap-to-lookup capture comes up with the setting off, and leaves it off`() {
        capture(boxes(override = OverlayMode.DEFINITIONS))
        assertEquals(OverlayMode.DEFINITIONS, host.presents.single().second)
        assertTrue(offered())
        assertFalse("the toggle shows the setting", on())
        assertFalse(prefs.dualScreenShowOnScreen)

        // Turned on over it: saved, the overlay already up stays as it is.
        tap()
        assertTrue(prefs.dualScreenShowOnScreen)
        assertEquals(1, host.presents.size)
        assertEquals(OverlayMode.DEFINITIONS, host.shownMode)
    }

    @Test fun `with it on, a remembered None comes up in auto-translate's mode and stays remembered`() {
        prefs.captureOverlayMode = OverlayMode.NONE
        prefs.dualScreenShowOnScreen = true
        capture()

        assertEquals(OverlayMode.TRANSLATION, host.presents.single().second)
        assertEquals(OverlayMode.NONE, prefs.captureOverlayMode)
        assertTrue(on())
    }

    @Test fun `the pill's None, which turns the setting off and takes the window down, turns the toggle off`() {
        prefs.dualScreenShowOnScreen = true
        capture(boxes(captureId = 1L))

        prefs.dualScreenShowOnScreen = false
        host.windowGone()

        assertTrue(offered())
        assertFalse(on())
        capture(boxes(captureId = 2L))
        assertEquals("the next capture stays down", 1, host.presents.size)
    }

    @Test fun `the setting written elsewhere reaches the toggle`() {
        capture()
        assertFalse(on())

        prefs.dualScreenShowOnScreen = true
        assertTrue(on())
        prefs.dualScreenShowOnScreen = false
        assertFalse(on())
    }

    @Test fun `the window going by its own means leaves the setting, and the toggle, on`() {
        prefs.dualScreenShowOnScreen = true
        capture(boxes(captureId = 1L))

        // A tap on the game screen, or Back with no card.
        host.windowGone()

        assertTrue(prefs.dualScreenShowOnScreen)
        assertTrue(on())
        capture(boxes(captureId = 2L))
        assertEquals("the next capture comes up", 2L, host.presents.last().first.captureId)
    }

    @Test fun `a switch of the window to Translations runs the translation it deferred`() {
        prefs.dualScreenShowOnScreen = true
        prefs.hideTranslationSection = true
        prefs.captureOverlayMode = OverlayMode.FURIGANA
        capture(pending = PendingTranslation(listOf("猫"), SourceLangId.JA, "en"))
        assertEquals(OverlayMode.FURIGANA, host.shownMode)
        assertEquals("readings need no translation, the section is hidden", 0, host.completions)

        host.windowSwitched(OverlayMode.TRANSLATION)

        assertEquals(1, host.completions)
    }

    @Test fun `during auto-translate the toggle is the hide-overlays setting and puts nothing up`() {
        host.live = true
        host.liveRunning = true
        // A live result: no boxes of a capture.
        vm.displayResult(result(), app)
        idle()
        host.page.refreshShowOnScreen()
        assertTrue(offered())
        assertTrue(on())

        tap()

        assertEquals(listOf(false), host.liveSets)
        assertTrue(host.presents.isEmpty())
        assertFalse(prefs.dualScreenShowOnScreen)
        assertFalse(on())
    }

    @Test fun `once auto-translate runs it is not offered for the capture left on the page`() {
        // More than one capture display: auto-translate's own meaning of the
        // toggle is not offered there.
        prefs.dualScreenShowOnScreen = true
        capture()
        host.liveRunning = true
        host.hideResultBoxesOnScreen()
        host.page.refreshShowOnScreen()

        assertFalse(offered())
    }

    @Test fun `a status after the result takes the overlay down and the toggle away`() {
        prefs.dualScreenShowOnScreen = true
        capture()
        assertTrue(host.shown != null)

        vm.showStatus("Capturing…")
        idle()

        assertEquals(1, host.hides)
        assertFalse(offered())
        assertTrue("the setting is the next capture's", prefs.dualScreenShowOnScreen)
    }

    @Test fun `a capture's original handed to the page is read aloud once the user has asked for that`() {
        val speech = FakeSpeech().also { it.install() }
        try {
            host.page.autoPlayOriginal("猫が鳴く")
            assertTrue("off until the user turns it on", speech.played.isEmpty())

            prefs.autoPlayOriginal = true
            host.page.autoPlayOriginal("猫が鳴く")
            assertEquals(listOf(AudioRequest.sentence("猫が鳴く", prefs.sourceLangId)), speech.played)
        } finally {
            FakeSpeech.uninstall()
        }
    }
}
