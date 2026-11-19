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

    @Test fun `the sections start straight under the bar, with no gap above the first header`() {
        assertTrue(page.view.findViewById<ResultSectionsLayout>(R.id.resultsScrollContent).flushTop)
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
