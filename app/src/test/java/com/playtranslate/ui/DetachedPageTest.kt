package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.testutil.RecordingWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * [DetachedPage], the detached window's results page, on its own: the idle
 * prompt it starts at and Clear returns to, what a capture cancelled from
 * outside leaves on it, the language change that resets it, that it offers
 * no source edit, and its Anki pill's over-game route. The controller's side (which captures reach it, when it is dropped)
 * is DetachedCaptureRoutingTest.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedPageTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private lateinit var wm: RecordingWindowManager
    private lateinit var page: DetachedPage

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

    @Before fun setUp() {
        clearPrefs()
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        page = DetachedPage(displayCtx, wm, d0, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
        settle()
    }

    @After fun tearDown() {
        page.release()
        // Let the churn gate finish any alert's removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private val idle get() = ResultState.Status(ctx.getString(R.string.detached_status_idle))

    private fun result(original: String = "こんにちは") = TranslationResult(
        originalText = original,
        segments = original.map { TextSegment(it.toString()) },
        translatedText = "hello",
        timestamp = "00:00:00",
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
    )

    private class Capture {
        val state = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing…"))
        val job = Job()
        val session = CaptureSession(state.asStateFlow(), job)
    }

    private fun view(id: Int): View? = page.view.findViewById(id)

    @Test fun `the page starts at its own idle prompt, with no start hint`() {
        assertEquals(idle, page.vm.result.value)
        assertEquals(ctx.getString(R.string.detached_status_idle), (view(R.id.tvStatus) as TextView).text.toString())
        assertNotEquals(View.VISIBLE, view(R.id.tvStatusHint)!!.visibility)
    }

    @Test fun `a capture walks the page to its result`() {
        val capture = Capture()
        page.follow(capture.session)
        assertEquals(ResultState.Status("Capturing…"), page.vm.result.value)

        val done = result()
        capture.state.value = CaptureState.Done(done)

        assertSame(done, (page.vm.result.value as ResultState.Ready).result)
    }

    @Test fun `a capture cancelled from outside puts back the result that was on show`() {
        val before = result()
        page.vm.displayResult(before, ctx)
        val capture = Capture()
        page.follow(capture.session)
        assertEquals(ResultState.Status("Capturing…"), page.vm.result.value)

        capture.state.value = CaptureState.Cancelled

        assertSame("not left on its Capturing status", before, (page.vm.result.value as ResultState.Ready).result)
    }

    @Test fun `a capture cancelled from the idle prompt puts the prompt back`() {
        val capture = Capture()
        page.follow(capture.session)

        capture.state.value = CaptureState.Cancelled

        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `Clear returns to the idle prompt and drops a capture on its way`() {
        page.vm.displayResult(result(), ctx)
        val capture = Capture()
        page.follow(capture.session)

        view(R.id.btnResultClear)!!.performClick()

        assertEquals(idle, page.vm.result.value)
        assertTrue("the capture was abandoned", capture.job.isCancelled)
        capture.state.value = CaptureState.Done(result("別"))
        assertEquals("and lands nowhere", idle, page.vm.result.value)
    }

    @Test fun `a change of the game language resets the page and drops a capture on its way`() {
        page.vm.displayResult(result(), ctx)
        val capture = Capture()
        page.follow(capture.session)

        prefs.sourceLang = "zh"
        settle()

        assertEquals(idle, page.vm.result.value)
        assertTrue(capture.job.isCancelled)
    }

    @Test fun `a change of the translation language resets the page`() {
        page.vm.displayResult(result(), ctx)

        prefs.setTarget("fr")
        settle()

        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `an unrelated setting leaves the result where it is`() {
        val shown = result()
        page.vm.displayResult(shown, ctx)

        prefs.showOverlayIcon = false
        settle()

        assertSame(shown, (page.vm.result.value as ResultState.Ready).result)
    }

    // ── The compact page ─────────────────────────────────────────────────

    private val sections get() = page.view.findViewById<ResultSectionsLayout>(R.id.resultsScrollContent)

    @Test fun `the page is the compact one, with no language headers, no card frames and no pill`() {
        page.vm.displayResult(result(), ctx)
        settle()

        assertEquals(View.GONE, view(R.id.sourceHeaderRow)!!.visibility)
        assertEquals(View.GONE, view(R.id.targetHeaderRow)!!.visibility)
        assertEquals("the texts stay", View.VISIBLE, view(R.id.cardOriginal)!!.visibility)
        assertEquals(View.VISIBLE, view(R.id.cardTranslation)!!.visibility)
        val card = view(R.id.cardTranslation) as com.google.android.material.card.MaterialCardView
        assertEquals("no frame", 0, card.strokeWidth)
        assertEquals("no fill", android.graphics.Color.TRANSPARENT, card.cardBackgroundColor.defaultColor)
        assertNotEquals("the floating pill is the bar's Anki here", View.VISIBLE, view(R.id.btnAnkiPill)!!.visibility)
        assertTrue("the sections start straight under the bar", sections.flushTop)
        assertTrue("a hairline between them, in place of the cards", sections.sectionRules)
        assertTrue("no pill to clear at the end of the scroll", sections.paddingBottom < view(R.id.btnAnkiPill)!!.height + 64)
        assertEquals("the headers that stay are slim", 0, view(R.id.wordsHeaderRow)!!.paddingTop)
        assertEquals(0, view(R.id.askHeaderRow)!!.paddingTop)
    }

    @Test fun `the headers' actions are the more menu's while a result is on show, the languages always`() {
        val idleGroups = page.menuGroups()
        assertTrue("a status has no text to act on", idleGroups[0].isEmpty())
        assertEquals(
            listOf(ctx.getString(R.string.lang_translate_from), ctx.getString(R.string.lang_translate_to)),
            idleGroups[1].map { it.label.toString() },
        )
        assertTrue("each language row carries its language", idleGroups[1].all { !it.detail.isNullOrEmpty() })

        page.vm.displayResult(result(), ctx)
        settle()
        val labels = page.menuGroups()[0].map { it.label.toString() }

        assertTrue(ctx.getString(R.string.header_action_read_aloud) in labels)
        assertTrue(ctx.getString(R.string.header_action_furigana) in labels)
        assertTrue(ctx.getString(R.string.cd_text_size) in labels)
        assertTrue(ctx.getString(R.string.header_action_hide_original) in labels)
        assertTrue(ctx.getString(R.string.header_action_hide_translation) in labels)
        assertFalse("no source edit here", ctx.getString(R.string.header_action_edit) in labels)
    }

    @Test fun `hiding the original from the menu hides its text, and the row then offers to show it`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val hide = page.menuGroups()[0].first { it.label.toString() == ctx.getString(R.string.header_action_hide_original) }

        hide.onClick!!.invoke(page.view)
        settle()

        assertEquals(View.GONE, view(R.id.cardOriginal)!!.visibility)
        val labels = page.menuGroups()[0].map { it.label.toString() }
        assertTrue(ctx.getString(R.string.header_action_show_original) in labels)
        assertFalse("what acts on the hidden text goes with it", ctx.getString(R.string.header_action_read_aloud) in labels)
    }

    @Test fun `Anki is offered while a result is on show, and not on a status`() {
        var changes = 0
        page.onAnkiAvailableChanged = { changes++ }
        assertFalse(page.ankiAvailable)

        page.vm.displayResult(result(), ctx)
        settle()
        assertTrue(page.ankiAvailable)
        assertEquals(1, changes)

        page.vm.showStatus("Capturing…")
        settle()
        assertFalse(page.ankiAvailable)
        assertEquals(2, changes)
    }

    @Test fun `Text size opens through the window's popover when a window is attached`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val shown = mutableListOf<Pair<PopoverContent, View>>()
        page.popoverPresenter = { content, anchor -> shown += content to anchor }
        val textSize = page.menuGroups()[0].first { it.label.toString() == ctx.getString(R.string.cd_text_size) }
        val anchor = View(ctx)

        textSize.onClick!!.invoke(anchor)

        assertEquals(1, shown.size)
        assertSame(anchor, shown[0].second)
    }

    @Test fun `the page offers no source edit`() {
        page.vm.displayResult(result(), ctx)
        settle()

        assertNotEquals(View.VISIBLE, view(R.id.btnEditOriginal)?.visibility)
    }

    @Test fun `the Anki pill without AnkiDroid raises an alert over the game`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val windowsBefore = wm.added.size

        view(R.id.btnAnkiPill)!!.performClick()
        settle()

        assertEquals("one overlay window, the alert", windowsBefore + 1, wm.added.size)
    }

    @Test fun `a released page follows nothing`() {
        val capture = Capture()
        page.follow(capture.session)

        page.release()
        capture.state.value = CaptureState.Done(result())

        assertTrue(capture.job.isCancelled)
        assertEquals(ResultState.Status("Capturing…"), page.vm.result.value)
    }
}
