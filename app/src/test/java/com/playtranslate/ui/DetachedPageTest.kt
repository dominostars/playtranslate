package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.allViews
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
 * The translation's credit has its gear for a capture's result: the picker
 * is an alert over the game, a pick lands on the page, and its settings
 * button starts the app's page. While the page follows auto-translate there
 * is no gear (the session's next read would replace a pick); it is back,
 * for the result the session left, when the session ends.
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

    private val idle get() = ResultState.Status(
        ctx.getString(R.string.detached_status_idle),
        hint = ctx.getString(R.string.detached_status_idle_hint),
    )

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

    @Test fun `the page starts at its own idle prompt, with how the window is worked under it`() {
        assertEquals(idle, page.vm.result.value)
        assertEquals(ctx.getString(R.string.detached_status_idle), (view(R.id.tvStatus) as TextView).text.toString())
        val hint = view(R.id.tvStatusHint) as TextView
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(ctx.getString(R.string.detached_status_idle_hint), hint.text.toString())
        assertTrue("a second line, smaller than the prompt", hint.textSize < (view(R.id.tvStatus) as TextView).textSize)
    }

    /** The page's view in a window, as the detached window holds it: a
     *  view's own posts run only there. */
    private val holder by lazy {
        android.widget.FrameLayout(ctx).also {
            it.addView(page.view)
            wm.addView(
                it,
                WindowManager.LayoutParams(
                    420, 600, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT,
                ),
            )
        }
    }

    private fun layOut(width: Int, height: Int) {
        // The window's own size first: its layout pass runs with the looper
        // and would put a size set only by hand back.
        val p = holder.layoutParams as WindowManager.LayoutParams
        p.width = width
        p.height = height
        wm.updateViewLayout(holder, p)
        // Twice: the hint's coming or going is a layout of its own.
        repeat(2) {
            holder.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            holder.layout(0, 0, width, height)
            settle()
        }
    }

    @Test fun `the hint gives way where the window is too small for the prompt and it, and comes back with the room`() {
        val status = view(R.id.tvStatus)!!
        val hint = view(R.id.tvStatusHint)!!
        layOut(420, 600)
        assertEquals(View.VISIBLE, hint.visibility)
        assertTrue("both in view", status.top >= 0 && hint.bottom <= 600)

        // Room for the prompt alone.
        val small = status.height + hint.height / 2
        layOut(420, small)
        assertEquals(View.GONE, hint.visibility)
        assertEquals(View.VISIBLE, status.visibility)
        assertTrue("the prompt is in view whole", status.top >= 0 && status.bottom <= small)

        layOut(420, 600)
        assertEquals(View.VISIBLE, hint.visibility)
    }

    @Test fun `a status with no hint of its own shows none`() {
        layOut(420, 600)

        page.vm.showStatus("Capturing…")
        settle()

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

    @Test fun `the headers' actions are handed to the window as Read aloud, the rows about the text and the two languages`() {
        assertEquals(
            listOf(ctx.getString(R.string.lang_translate_from), ctx.getString(R.string.lang_translate_to)),
            page.languageRows().map { it.label.toString() },
        )
        assertTrue("each language row carries its language", page.languageRows().all { !it.detail.isNullOrEmpty() })

        page.vm.displayResult(result(), ctx)
        settle()

        assertEquals(ctx.getString(R.string.header_action_read_aloud), page.readAloud.label.toString())
        assertTrue(page.readAloud.available)
        assertEquals(
            listOf(
                ctx.getString(R.string.header_action_furigana),
                ctx.getString(R.string.header_action_hide_original),
                ctx.getString(R.string.header_action_hide_translation),
                ctx.getString(R.string.cd_text_size),
            ),
            page.textActions().map { it.label.toString() },
        )
        assertTrue("every one of them can run", page.textActions().all { it.available })
    }

    @Test fun `a game language with no reading hints lists no switch for them`() {
        prefs.sourceLang = "es"
        settle()

        assertEquals(
            listOf(
                ctx.getString(R.string.header_action_hide_original),
                ctx.getString(R.string.header_action_hide_translation),
                ctx.getString(R.string.cd_text_size),
            ),
            page.textActions().map { it.label.toString() },
        )
    }

    private fun textAction(label: Int): HeaderAction =
        page.textActions().first { it.label.toString() == ctx.getString(label) }

    @Test fun `hiding the original from the menu hides its text, and the row then offers to show it`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val rows = page.textActions()
        val eye = textAction(R.string.header_action_hide_original)
        val changed = mutableListOf<HeaderAction>()
        val listener = object : FoldingActionRow.Listener {
            override fun onActionChanged(action: HeaderAction) { changed += action }
        }
        page.addActionListener(listener)

        eye.onClick!!.invoke(page.view)
        settle()

        assertEquals(View.GONE, view(R.id.cardOriginal)!!.visibility)
        assertEquals(ctx.getString(R.string.header_action_show_original), eye.label.toString())
        assertFalse("what acts on the hidden text has nothing to act on", page.readAloud.available)
        assertFalse(rows.first { it.label.toString() == ctx.getString(R.string.header_action_furigana) }.available)
        assertEquals("the same rows in the same places: an open menu keeps its list", rows, page.textActions())
        assertTrue("and whoever follows the actions hears of it", page.readAloud in changed)
        page.removeActionListener(listener)
    }

    @Test fun `Text size is one row whichever text is on show, and has nothing to size with both hidden`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val textSize = textAction(R.string.cd_text_size)
        assertTrue(textSize.available)

        textAction(R.string.header_action_hide_translation).onClick!!.invoke(page.view)
        settle()
        assertTrue("the Original is still there to size", textSize.available)
        assertEquals("the same model", textSize, textAction(R.string.cd_text_size))

        textAction(R.string.header_action_hide_original).onClick!!.invoke(page.view)
        settle()
        assertFalse(textSize.available)

        textAction(R.string.header_action_show_translation).onClick!!.invoke(page.view)
        settle()
        assertTrue(textSize.available)
    }

    @Test fun `a result on show is what offers the page's actions and Anki, and a status takes them back`() {
        var changes = 0
        page.onResultOnShowChanged = { changes++ }
        assertFalse(page.resultOnShow)

        page.vm.displayResult(result(), ctx)
        settle()
        assertTrue(page.resultOnShow)
        assertEquals(1, changes)

        page.vm.showStatus("Capturing…")
        settle()
        assertFalse(page.resultOnShow)
        assertEquals(2, changes)
    }

    @Test fun `Text size opens through the window's popover when a window is attached`() {
        page.vm.displayResult(result(), ctx)
        settle()
        val shown = mutableListOf<Pair<PopoverContent, View>>()
        page.popoverPresenter = { content, anchor -> shown += content to anchor }
        val textSize = textAction(R.string.cd_text_size)
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

    // ── One result for the display: its latest capture ───────────────────

    @Test fun `a capture of the display made elsewhere takes down a result on show, and a no-text status that came with its frame`() {
        // The sheet's capture: the service's capture job.
        page.vm.displayResult(result(), ctx)
        page.onCaptureImageReplaced(byCaptureJob = true)
        assertEquals("the result pointed at the image that was just replaced", idle, page.vm.result.value)

        // A hold-to-preview, which runs beside that job.
        page.vm.displayResult(result(), ctx)
        page.onCaptureImageReplaced(byCaptureJob = false)
        assertEquals(idle, page.vm.result.value)

        page.vm.showStatus("No text found", screenshotPath = "/cache/capture-d0.jpg")
        page.onCaptureImageReplaced(byCaptureJob = true)
        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `it leaves the idle prompt and a status with no frame as they are`() {
        page.onCaptureImageReplaced(byCaptureJob = true)
        assertEquals(idle, page.vm.result.value)

        page.vm.showError("offline")
        val error = page.vm.result.value
        page.onCaptureImageReplaced(byCaptureJob = true)
        page.onCaptureImageReplaced(byCaptureJob = false)
        assertEquals(error, page.vm.result.value)
    }

    @Test fun `the page's own capture in flight stays, and if it is then cancelled the page is idle, not back on a result whose image is gone`() {
        page.vm.displayResult(result(), ctx)
        val capture = Capture()
        page.follow(capture.session)
        capture.state.value = CaptureState.Translating("新しい", listOf(TextSegment("新しい")), null)
        val reading = page.vm.result.value

        page.onCaptureImageReplaced(byCaptureJob = true)
        assertEquals("its own frame: nothing changes", reading, page.vm.result.value)
        assertFalse(capture.job.isCancelled)

        capture.state.value = CaptureState.Cancelled
        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `a hold-to-preview that reads the display while the page's capture is still translating takes that capture down`() {
        val capture = Capture()
        page.follow(capture.session)
        capture.state.value = CaptureState.Translating("新しい", listOf(TextSegment("新しい")), null)

        // Not the capture job's write: a later frame, which this capture
        // would finish with for its image.
        page.onCaptureImageReplaced(byCaptureJob = false)

        assertEquals(idle, page.vm.result.value)
        assertTrue("its capture is called off", capture.job.isCancelled)
        capture.state.value = CaptureState.Done(result("新しい"))
        assertEquals("and its result lands nowhere", idle, page.vm.result.value)
    }

    @Test fun `the same while the page's capture has shown nothing but its status`() {
        val capture = Capture()
        page.follow(capture.session)
        assertEquals(ResultState.Status("Capturing…"), page.vm.result.value)

        page.onCaptureImageReplaced(byCaptureJob = false)

        assertEquals(idle, page.vm.result.value)
        assertTrue(capture.job.isCancelled)
    }

    // ── Read aloud ───────────────────────────────────────────────────────

    @Test fun `with Play automatically on, a capture into the window is read aloud as its text arrives`() {
        val speech = com.playtranslate.audio.FakeSpeech()
        speech.install()
        try {
            val quiet = Capture()
            page.follow(quiet.session)
            quiet.state.value = CaptureState.Translating("こんにちは", listOf(TextSegment("こんにちは")), null)
            settle()
            assertTrue("off until the user turns it on", speech.played.isEmpty())

            prefs.autoPlayOriginal = true
            val capture = Capture()
            page.follow(capture.session)
            capture.state.value = CaptureState.Translating("さようなら", listOf(TextSegment("さようなら")), null)
            settle()
            assertEquals(1, speech.played.size)
            capture.state.value = CaptureState.Done(result("さようなら"))
            settle()
            assertEquals("once for a capture", 1, speech.played.size)
        } finally {
            com.playtranslate.audio.FakeSpeech.uninstall()
        }
    }

    // ── Auto-translate in the window ─────────────────────────────────────

    private var service: org.robolectric.android.controller.ServiceController<com.playtranslate.CaptureService>? = null

    /** A service whose stream of auto-translate results the page can follow. */
    private fun captureService(): com.playtranslate.CaptureService {
        val built = org.robolectric.Robolectric.buildService(com.playtranslate.CaptureService::class.java).create()
        service = built
        return built.get()
    }

    @After fun stopService() {
        service?.destroy()
        service = null
    }

    @Test fun `a page that follows auto-translate shows what the session reports, as the app's page does`() {
        val svc = captureService()
        assertFalse(page.followsLive)

        page.followLive(svc)
        settle()
        assertTrue(page.followsLive)
        assertEquals("the stream starts with nothing to say, and nothing is shown for that", idle, page.vm.result.value)

        val first = result("一つ目")
        svc.emitResult(first)
        settle()
        assertSame(first, (page.vm.result.value as ResultState.Ready).result)

        svc.emitError("the backend is down")
        settle()
        assertEquals(ResultState.Error("the backend is down"), page.vm.result.value)

        val second = result("二つ目")
        svc.emitResult(second)
        settle()
        assertSame("the session goes on after an error", second, (page.vm.result.value as ResultState.Ready).result)
    }

    @Test fun `following begins by dropping a capture on its way, whose end then lands nowhere`() {
        val svc = captureService()
        val before = result("前の")
        page.vm.displayResult(before, ctx)
        val capture = Capture()
        page.follow(capture.session)

        page.followLive(svc)
        svc.emitError("looking")
        settle()

        assertTrue("its capture is called off", capture.job.isCancelled)
        // What a session start does to it at the service: cancelled. The
        // page must not put back what it showed before that capture.
        capture.state.value = CaptureState.Cancelled
        settle()
        assertEquals(ResultState.Error("looking"), page.vm.result.value)
    }

    @Test fun `a capture of the display made elsewhere leaves a followed session's result on show`() {
        val svc = captureService()
        page.followLive(svc)
        val shown = result()
        svc.emitResult(shown)
        settle()

        // A hold-to-preview wrote the display's picture.
        page.onCaptureImageReplaced(byCaptureJob = false)
        page.onCaptureImageReplaced(byCaptureJob = true)

        assertSame("the loop keeps result and picture current itself", shown, (page.vm.result.value as ResultState.Ready).result)
    }

    @Test fun `no longer following, the page keeps what it shows and takes nothing more`() {
        val svc = captureService()
        page.followLive(svc)
        val last = result("最後")
        svc.emitResult(last)
        settle()

        page.followLive(null)
        svc.emitResult(result("その後"))
        settle()

        assertFalse(page.followsLive)
        assertSame(last, (page.vm.result.value as ResultState.Ready).result)
        // And a capture of the display made elsewhere takes it down again,
        // as it does any result with nothing keeping it current.
        page.onCaptureImageReplaced(byCaptureJob = false)
        assertEquals(idle, page.vm.result.value)
    }

    @Test fun `a change of language while following says the session is looking, and what was delivered is forgotten`() {
        val svc = captureService()
        page.followLive(svc)
        svc.emitResult(result())
        settle()

        prefs.setTarget("fr")
        settle()

        val status = page.vm.result.value as ResultState.Status
        assertTrue("not the idle prompt: ${status.message}", status.message.startsWith("Searching for"))
        assertSame(
            "the stream is cleared, so the same text in the new pair is delivered again",
            com.playtranslate.PanelState.Idle, svc.panelState.value,
        )
        assertTrue("and the page still follows", page.followsLive)
    }

    @Test fun `a released page follows no session`() {
        val svc = captureService()
        page.followLive(svc)
        svc.emitError("before")
        settle()

        page.release()
        svc.emitError("after")
        settle()

        assertFalse(page.followsLive)
        assertEquals(ResultState.Error("before"), page.vm.result.value)
    }

    // ── A service picked for the result on show ──────────────────────────

    /** A result a service is credited for, as a capture's is. */
    private fun credited(original: String = "こんにちは") = result(original).copy(backendDisplayName = "Lingva")

    private val gearShown: Boolean
        get() = view(R.id.btnTranslationService)?.visibility == View.VISIBLE &&
            view(R.id.translationNoteRow)?.isClickable == true

    /** The buttons of the alert last added over the game, by their labels. */
    private fun alertButton(label: String): android.widget.Button =
        wm.added.last().first.allViews.filterIsInstance<android.widget.Button>()
            .single { it.text.toString() == label }

    private fun <T> withServices(block: () -> T): T {
        com.playtranslate.translation.TranslationBackendRegistry.init(
            listOf(
                com.playtranslate.translation.FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva"),
                com.playtranslate.translation.FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false),
            ),
        )
        return try { block() } finally { com.playtranslate.translation.TranslationBackendRegistry.close() }
    }

    @Test fun `the credit's gear opens the service picker over the game, and a pick lands on the page`() = withServices {
        page.vm.chosenTranslation = { _, backend, _ ->
            ChosenServiceTranslation.Outcome.Translated(listOf("hello again"), backend.displayName)
        }
        page.vm.displayResult(credited(), ctx)
        settle()
        assertTrue(gearShown)
        val windowsBefore = wm.added.size

        view(R.id.translationNoteRow)!!.performClick()
        settle()
        assertEquals("one overlay window, the picker", windowsBefore + 1, wm.added.size)

        alertButton("Gemini").performClick()
        settle()

        val landed = (page.vm.result.value as ResultState.Ready).result
        assertEquals("hello again", landed.translatedText)
        assertEquals("Gemini", landed.backendDisplayName)
        assertEquals("hello again", (view(R.id.tvTranslation) as TextView).text.toString())
    }

    @Test fun `a failed pick is said in an alert over the game`() = withServices {
        page.vm.chosenTranslation = { _, _, _ -> ChosenServiceTranslation.Outcome.Failed("gemini", "Gemini", null) }
        val on = credited()
        page.vm.displayResult(on, ctx)
        settle()
        view(R.id.translationNoteRow)!!.performClick()
        settle()
        val afterPicker = wm.added.size

        alertButton("Gemini").performClick()
        settle()

        assertEquals("one more overlay window, the alert", afterPicker + 1, wm.added.size)
        assertSame(on, (page.vm.result.value as ResultState.Ready).result)
        assertTrue(
            wm.added.last().first.allViews.filterIsInstance<TextView>()
                .any { it.text.toString() == ctx.getString(R.string.translation_pick_failed_title) },
        )
    }

    @Test fun `the picker's settings button starts Translation services`() = withServices {
        page.vm.displayResult(credited(), ctx)
        settle()
        view(R.id.translationNoteRow)!!.performClick()
        settle()

        alertButton(ctx.getString(R.string.settings_cell_translation_services)).performClick()
        settle()

        val started = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
    }

    @Test fun `while the page follows auto-translate the credit has no gear, and it is back when the session ends`() {
        val svc = captureService()
        page.vm.displayResult(credited(), ctx)
        settle()
        assertTrue("a capture's result", gearShown)

        page.followLive(svc)
        val fed = credited("一つ目")
        svc.emitResult(fed)
        settle()
        assertEquals("the credit is still said", View.VISIBLE, view(R.id.translationNoteRow)?.visibility)
        assertFalse("the session's next read would replace a pick", gearShown)

        page.followLive(null)
        settle()
        assertSame(fed, (page.vm.result.value as ResultState.Ready).result)
        assertTrue("the result the session left can be picked for", gearShown)
    }
}
