package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.graphics.Insets
import android.hardware.input.InputManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.ask.AskQuestion
import com.playtranslate.ask.AskSession
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.AskState
import com.playtranslate.ask.Explanation
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.vocab.HiddenWordsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The over-game capture sheet's own wiring of the Ask and Words sections, on
 * a real [CaptureResultOverlay] shown with a result and let settle, as
 * [CaptureSheetSectionsTest] builds one. Written from the spec as a contract.
 *
 * Pins, Ask on the sheet: shown by default between the two texts and the
 * Words header; the panel's need counts a shown Ask card with its header and
 * a hidden one not at all, and the eye re-fits by exactly that; an answer
 * grows the card and the sheet follows; a collapsed Ask's pill shows the card
 * without writing the pref; the question is about the sentence on show
 * (another sentence ends it, the same one keeps it); a page opened from the
 * sheet takes the result and the finished answer (none while in flight); a
 * sheet shown again with an answer shows it about its result.
 *
 * Typing in the Ask field: the field's focus is the sheet's typing state,
 * in which the body's long downward drag does not dismiss (it does on a sheet
 * with no focus, and again once the typing is over) and B leaves the field
 * before it closes the sheet; the keyboard closing under the question ends
 * that state, where the source edit's editor stays up through the same inset
 * change; the window takes key focus and the keyboard for the field and
 * gives them back; with no LLM a chip raises the sheet's alert, whose button
 * opens Translation services and closes an over-game sheet (an in-app host's
 * stays).
 *
 * Words on the sheet: a shown card's row opens the word's page by the
 * sheet's route; the sentence cache holds the sentence's translation whether
 * Words is shown or hidden (a blank translation writes nothing), and a hidden
 * card never has its rows looked up; the need counts a shown Words card and
 * re-fits when its rows lay out; with a card shown below, the two texts'
 * cards are not stretched. The controller: the sheet's actions in visual
 * order in each state, and A on the question field.
 *
 * The LLM is a fake completer registered in the backend registry beside the
 * app's own (removed after each test), answering when the test completes
 * [reply]. The source language is English, whose tokenizer needs no pack:
 * the lookup's analysis really runs, and its rows settle empty for want of a
 * dictionary. Rows to tap are pushed through the sheet's renderer (the seam
 * a real lookup goes through), and the Words page, which no engine lays out
 * here, is played by [WordRowList.layoutForTest].
 *
 * The display is set to each sheet's size before it is shown; mdpi, so a dp
 * is a px. Native graphics, so the texts measure in a real font.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureSheetAskWordsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val prefs = Prefs(ctx)
    private var sheet: CaptureResultOverlay? = null
    private val dismissals = mutableListOf<CaptureResultOverlay.DismissReason>()

    /** What the LLM answers the question in flight, completed by the test;
     *  a question stays in flight until then. */
    private var reply = CompletableDeferred<String>()

    private val llm = FakeCompleterBackend(
        id = "ask-sheet-test-llm", priority = 0, displayName = "Test LLM",
        answer = { _, _ -> reply.await() },
    )

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
        LastSentenceCache.clear()
        // A tokenizer with no pack: the lookup runs here (class doc).
        prefs.sourceLang = "en"
        prefs.setTarget("en")
    }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        TranslationBackendRegistry.removeOnlineBackend(llm.id)
        SourceLanguageEngines.release(SourceLangId.EN)
        runBlocking { HiddenWordsStore.resetForTest(ctx) }
        LastSentenceCache.clear()
        sp().edit().clear().commit()
    }

    // ── harness ──────────────────────────────────────────────────────────

    private data class Screen(val w: Int, val h: Int)

    /** Paired, and tall enough that two texts and the Ask card fit under the
     *  auto-size cap with the floor below them. */
    private val screen = Screen(1280, 1100)

    /** Paired, with a floor above two short texts and the Ask card. */
    private val tall = Screen(1280, 2000)

    private val density get() = ctx.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun constInt(name: String): Int =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun constFloat(name: String): Float =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(null)

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private val CaptureResultOverlay.sections: ResultSectionsLayout get() = field("sections")
    private val CaptureResultOverlay.scroll: NestedScrollView get() = field("scroll")
    private val CaptureResultOverlay.panel: View get() = field("panel")
    private val CaptureResultOverlay.body: View get() = field("body")
    private val CaptureResultOverlay.root: ViewGroup get() = field("root")
    private val CaptureResultOverlay.binder: TranslationSectionBinder get() = field("binder")
    private val CaptureResultOverlay.dismissed: Boolean get() = field("dismissed")
    private val CaptureResultOverlay.askSession: AskSession get() = field("askSession")
    private val CaptureResultOverlay.popovers: PopoverHost get() = field("popovers")
    private val CaptureResultOverlay.wordRows: WordRowsBinder get() = field("wordRows")
    private val CaptureResultOverlay.wordLookups: WordLookupPipeline get() = field("wordLookups")
    private val CaptureResultOverlay.lookupsRenderer: WordLookupsRenderer get() = field("lookupsRenderer")
    private val CaptureResultOverlay.navHost: CaptureSheetNavHost get() = field("navHost")
    private val CaptureResultOverlay.nav: CaptureSheetControllerNav? get() = field("nav")
    private val CaptureResultOverlay.editContainer: View get() = field("editContainer")

    /** The settled panel height. */
    private val CaptureResultOverlay.panelHeight: Int get() = field("panelHeightPx")

    /** What the content needs at max text size: the drag ceiling, and what
     *  the auto height grows to (up to its cap). */
    private val CaptureResultOverlay.need: Int get() = field("maxNeededHeightPx")

    /** Where the user is typing, as the sheet derives it: the name of its
     *  answer (NOTHING, SOURCE_EDIT, ASK_QUESTION). */
    private val CaptureResultOverlay.typingIn: String
        get() = CaptureResultOverlay::class.java.getDeclaredMethod("getTypingIn")
            .apply { isAccessible = true }.invoke(this).toString()

    /** Whether a downward gesture may throw the sheet away at all, as the
     *  sheet answers it (the content's scroll is the other gate). */
    private val CaptureResultOverlay.canSwipeAway: Boolean
        get() = CaptureResultOverlay::class.java.getDeclaredMethod("canSwipeAway")
            .apply { isAccessible = true }.invoke(this) as Boolean

    private fun CaptureResultOverlay.v(id: Int): View = sections.findViewById(id)

    private val CaptureResultOverlay.askField: EditText get() = v(R.id.etAskQuestion) as EditText

    /** The Ask card's chips, in order. */
    private fun chips(o: CaptureResultOverlay): List<View> {
        val row = o.v(R.id.askPresetChips) as ViewGroup
        return (0 until row.childCount).map(row::getChildAt)
    }

    /** The Ask header's question pills (its worded actions), shown or not. */
    private fun pills(o: CaptureResultOverlay): List<TextView> {
        val header = o.v(R.id.askHeaderRow) as ViewGroup
        val label = o.v(R.id.labelAsk)
        return (0 until header.childCount).map(header::getChildAt)
            .filterIsInstance<TextView>()
            .filter { it !== label }
    }

    /** The binder's word list, whose page the test plays. */
    private fun list(o: CaptureResultOverlay): WordRowList<*> = o.wordRows.field("list")

    private fun floor(s: Screen) = CaptureResultGeometry.minPanelHeight(s.h)

    /** A pangram sentence repeated [sentences] times: about 62 characters a
     *  sentence. */
    private fun text(sentences: Int) =
        "The quick brown fox jumps over the lazy dog, again and again. ".repeat(sentences).trim()

    private fun result(original: String, translation: String) = TranslationResult(
        originalText = original,
        segments = listOf(TextSegment(original)),
        translatedText = translation,
        timestamp = "",
        backendDisplayName = "Test MT",
        langContext = prefs.langContext(),
    )

    private fun row(word: String, meaning: String) = RowState(
        displayWord = word, reading = "", meaning = meaning, senses = emptyList(),
        freqScore = 3, isCommon = true, surface = word,
    )

    /** Two looked-up words for the Words card. */
    private val words = listOf(row("fox", "a small wild canine"), row("dog", "a domesticated canine"))

    /** A long answer: several lines in the Ask card at any width here. */
    private val longAnswer =
        "The sentence is in the past tense because the jump is over before it is told. ".repeat(8).trim()

    /** Run the sheet's posted fits, its height animation and the layout
     *  they cause until nothing is left pending. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    /** Drive the main looper until the sheet's word lookup is at rest: its
     *  rows settled where the Words card asked for them, else its analysis
     *  done. The tiers hop to IO and back on real threads, so a test that
     *  read the sheet before then would race the lookup's next state. */
    private fun awaitLookupAtRest(o: CaptureResultOverlay) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val s = o.wordLookups.state.value
            if (s is WordLookupsState.Settled || (s is WordLookupsState.Analyzed && o.wordRows.isHidden)) break
            check(System.currentTimeMillis() < deadline) { "the word lookup never came to rest: $s" }
            Thread.sleep(10)
        }
        settle()
    }

    /** [open] with a result of [original] and [translation]. */
    private fun open(
        screen: Screen,
        original: String,
        translation: String,
        askHidden: Boolean? = false,
        wordsHidden: Boolean = true,
        askAnswer: AskState.Answered? = null,
        configure: (CaptureResultOverlay) -> Unit = {},
    ): CaptureResultOverlay =
        open(screen, result(original, translation), askHidden, wordsHidden, askAnswer, configure)

    /**
     * A sheet on a display of [screen]'s size, shown with [shown] and
     * settled, its word lookup at rest. [askHidden] null leaves the Ask pref
     * as a new install has it. [askAnswer] is the answer the sheet is shown
     * again with. [configure] runs before show.
     */
    private fun open(
        screen: Screen,
        shown: TranslationResult,
        askHidden: Boolean? = false,
        wordsHidden: Boolean = true,
        askAnswer: AskState.Answered? = null,
        configure: (CaptureResultOverlay) -> Unit = {},
    ): CaptureResultOverlay {
        RuntimeEnvironment.setQualifiers("w${screen.w}dp-h${screen.h}dp-mdpi")
        // Each sheet starts from the state it names: the eyes write the
        // prefs, and a sheet closed earlier in a test must not hand them on.
        prefs.hideOriginalSection = false
        prefs.hideTranslationSection = false
        if (askHidden != null) prefs.hideAskSection = askHidden
        prefs.hideWordsSection = wordsHidden
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        dismissals.clear()
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        configure(o)
        o.showWithResult(screen.w, screen.h, shown, askAnswer = askAnswer)
        settle()
        awaitLookupAtRest(o)
        assertEquals("the sheet's window is the screen", screen.w to screen.h, o.root.width to o.root.height)
        return o
    }

    private fun close(o: CaptureResultOverlay) {
        o.dismiss()
        host.removeAll()
        settle()
    }

    /** The user has an LLM: the fake, answering through [reply]. */
    private fun giveLlm() = TranslationBackendRegistry.addOnlineBackend(llm)

    /** The question in flight is answered with [text]; the next one waits
     *  for its own reply. */
    private fun answer(text: String) {
        val r = reply
        reply = CompletableDeferred()
        r.complete(text)
        settle()
    }

    /** [rows] on the Words card, pushed through the sheet's renderer as a
     *  settled lookup lands, once the sheet's own lookup is at rest. */
    private fun showRows(o: CaptureResultOverlay, rows: List<RowState>, sentence: AskSentence? = null) {
        o.lookupsRenderer.render(
            WordLookupsState.Settled(rows, emptyList(), emptyMap(), definitionLang = "en", sentence = sentence),
        )
        settle()
        assertEquals("precondition: the card draws the rows",
            rows.map { it.displayWord }, o.wordRows.shownRows.map { it.data.word })
    }

    /** A tap on [v], which must be on screen. */
    private fun tap(v: View) {
        assertTrue("tapped while on screen: $v", v.isShown)
        v.performClick()
        settle()
    }

    private fun panelTop(o: CaptureResultOverlay): Float = o.panel.top + o.panel.translationY

    /** A finger's quick tap on [view], as the sheet's window delivers one:
     *  down and up at the view's centre, through the root's touch rules. */
    private fun touch(o: CaptureResultOverlay, view: View) {
        assertTrue("touched while on screen: $view", view.isShown)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val x = loc[0] + view.width / 2f
        val y = loc[1] + view.height / 2f
        val grabBottom = panelTop(o) + dp(constInt("HANDLE_HEIGHT_DP") + constInt("EXTRA_GRAB_INTO_SHEET_DP"))
        assertTrue("the touch ($y) is below the band that grabs the sheet ($grabBottom)", y > grabBottom)
        assertTrue("the touch ($y) is on the sheet", y < o.panel.bottom + o.panel.translationY)
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, x, y, 0)
            o.root.dispatchTouchEvent(e)
            e.recycle()
        }
        settle()
    }

    /** A key's press and release through the sheet root's key dispatch. */
    private fun key(o: CaptureResultOverlay, code: Int) {
        o.root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        o.root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        settle()
    }

    /** A gamepad attached before show, so the sheet's controller nav arms. */
    private fun attachPad() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
    }

    /** A finger on the sheet's root: DOWN at ([x], [fromY]), [steps] MOVEs
     *  out to [dy] past it, UP there. [onMove] runs after each MOVE. */
    private fun drag(o: CaptureResultOverlay, x: Float, fromY: Float, dy: Float, steps: Int = 6, onMove: () -> Unit = {}) {
        val downTime = SystemClock.uptimeMillis()
        var t = downTime
        fun send(action: Int, y: Float) {
            val ev = MotionEvent.obtain(downTime, t, action, x, y, 0)
            o.root.dispatchTouchEvent(ev)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromY)
        for (i in 1..steps) {
            t += 16
            send(MotionEvent.ACTION_MOVE, fromY + dy * i / steps)
            onMove()
        }
        t += 16
        send(MotionEvent.ACTION_UP, fromY + dy)
    }

    /** The middle of the sheet's body: below the band that grabs the sheet
     *  for a resize, so a drag there is the body's. */
    private fun bodyPoint(o: CaptureResultOverlay, s: Screen): Pair<Float, Float> {
        val top = panelTop(o)
        val y = top + o.body.top + o.body.height / 2f
        val grabBottom = top + dp(constInt("HANDLE_HEIGHT_DP") + constInt("EXTRA_GRAB_INTO_SHEET_DP"))
        assertTrue("the body point ($y) is below the band that grabs the sheet ($grabBottom)", y > grabBottom)
        assertTrue("the body point ($y) is on the sheet", y < o.panel.bottom + o.panel.translationY)
        return s.w / 4f to y
    }

    /** Past the distance that throws the sheet away, with room to spare. */
    private val dismissTravel: Float get() = constFloat("DISMISS_DISTANCE_DP") * density + dp(50)

    /** A long downward drag of the body, then the exit slide's time. Returns
     *  the furthest the sheet followed the finger before the release. */
    private fun swipeDown(o: CaptureResultOverlay, s: Screen): Float {
        val (x, y) = bodyPoint(o, s)
        var followed = 0f
        drag(o, x, y, dismissTravel) { followed = maxOf(followed, o.panel.translationY) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return followed
    }

    /** The sheet window's layout params, where its focus policy is written. */
    private fun windowParams(o: CaptureResultOverlay): WindowManager.LayoutParams =
        o.field<Any>("sheetHost").field("params")

    private fun takesKeyFocus(p: WindowManager.LayoutParams) =
        p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0

    /** The window takes the keyboard: focusable, a possible IME target, and
     *  asking for the keyboard whenever it has focus. */
    private fun takesKeyboard(p: WindowManager.LayoutParams) =
        takesKeyFocus(p) &&
            p.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM == 0 &&
            p.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE ==
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE

    /** What the focus policy writes to the window: no key focus, key focus
     *  that is never the keyboard's, and the keyboard mode. */
    private fun focusPolicy(p: WindowManager.LayoutParams) = Triple(
        p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        p.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0,
        p.softInputMode,
    )

    /** The sheet's window insets with the keyboard up [imeHeight] tall, or
     *  down (null); everything else as the window has it now. */
    private fun keyboardInsets(o: CaptureResultOverlay, imeHeight: Int?): WindowInsets =
        WindowInsets.Builder(o.root.rootWindowInsets)
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imeHeight ?: 0))
            .setVisible(WindowInsets.Type.ime(), imeHeight != null)
            .build()

    private val CaptureResultOverlay.panelBottomMargin: Int
        get() = (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin

    /** The windows the sheet's host holds besides the sheet: where the
     *  sheet's own alerts show. */
    private fun otherWindows(o: CaptureResultOverlay): List<View> =
        host.field<List<OverlayHost.OverlayHandle>>("overlayWindows").map { it.view }.filter { it !== o.root }

    private fun textViewsIn(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textViewsIn(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** The cursor's item, when the controller nav has one. */
    private fun cursorOf(o: CaptureResultOverlay): Any? = o.nav!!.field("cursor")

    /** Whether the controller's ring is drawn. */
    private fun ringShown(o: CaptureResultOverlay): Boolean =
        o.field<FocusRingView>("focusRing").field("hasItem")

    // ── Ask on the sheet ────────────────────────────────────────────────

    @Test fun `a sheet opened with the default pref shows the Ask questions between the two texts and the Words header`() {
        val o = open(screen, text(2), text(2), askHidden = null)
        assertFalse("precondition: a new install's pref shows the section", prefs.hideAskSection)
        val card = o.v(R.id.cardAsk)
        assertEquals(View.VISIBLE, card.visibility)
        assertTrue("the card is on screen", card.isShown)
        assertEquals("with the questions", View.VISIBLE, o.v(R.id.askIdle).visibility)
        assertEquals("and no answer", View.GONE, o.v(R.id.askAnswer).visibility)
        val header = o.v(R.id.askHeaderRow)
        val textsBottom = maxOf(o.v(R.id.cardOriginal).bottom, o.v(R.id.cardTranslation).bottom)
        assertTrue("the Ask header is under the two texts (${header.top} vs $textsBottom)", header.top >= textsBottom)
        assertTrue("the card is under its header", card.top >= header.bottom)
        assertTrue("the Words header is under the Ask card", o.v(R.id.wordsHeaderRow).top >= card.bottom)
    }

    @Test fun `the panel's need counts a shown Ask card with its header, and a hidden Ask adds nothing`() {
        // Per belowNeed(): every header and card down to the last card shown.
        // Words is hidden in both, so its header is past the fold in both:
        // the difference is the Ask header and the Ask card, nothing else.
        val shown = open(screen, text(4), text(3), askHidden = false)
        val needShown = shown.need
        val askHeader = shown.v(R.id.askHeaderRow).height
        val askCard = shown.v(R.id.cardAsk).height
        assertTrue("precondition: the card has a height", askCard > 0)
        close(shown)

        val hidden = open(screen, text(4), text(3), askHidden = true)
        assertEquals("precondition: hidden", View.GONE, hidden.v(R.id.cardAsk).visibility)
        val needHidden = hidden.need
        assertTrue("precondition: both needs are above the floor ($needHidden vs ${floor(screen)})",
            needHidden > floor(screen))
        assertEquals(askHeader + askCard, needShown - needHidden)
    }

    @Test fun `the Ask eye re-fits the sheet, dropping the need by the header and card and returning it exactly`() {
        val o = open(screen, text(4), text(3), askHidden = false)
        val need = o.need
        val height = o.panelHeight
        val dropped = o.v(R.id.askHeaderRow).height + o.v(R.id.cardAsk).height
        assertTrue("precondition: the hidden need is above the floor", need - dropped > floor(screen))
        assertTrue("precondition: the panel rests at the need, under the cap",
            height == need && need < CaptureResultGeometry.autoMaxHeight(screen.h))

        tap(o.v(R.id.btnToggleAsk))
        assertEquals(View.GONE, o.v(R.id.cardAsk).visibility)
        assertTrue(prefs.hideAskSection)
        assertEquals("the need dropped by the header and the card", need - dropped, o.need)
        assertEquals("and the panel followed", need - dropped, o.panelHeight)

        tap(o.v(R.id.btnToggleAsk))
        assertEquals(View.VISIBLE, o.v(R.id.cardAsk).visibility)
        assertEquals("the need came back exactly", need, o.need)
        assertEquals("and the panel height", height, o.panelHeight)
    }

    @Test fun `an answer grows the Ask card past its Asking line, and the sheet grows with it`() {
        val o = open(screen, text(2), text(2), askHidden = false)
        giveLlm()
        tap(chips(o)[0])
        assertTrue("asking", o.askSession.state.value is AskState.Asking)
        assertEquals(View.VISIBLE, o.v(R.id.askAnswer).visibility)
        assertEquals(
            "the registry's pass named the fake it is asking",
            ctx.getString(R.string.ask_status_asking, llm.displayName),
            (o.v(R.id.tvAskBody) as TextView).text.toString(),
        )
        val needAsking = o.need
        val heightAsking = o.panelHeight
        val cardAsking = o.v(R.id.cardAsk).height
        assertTrue("precondition: the need is above the floor", needAsking > floor(screen))
        assertTrue("precondition: the panel rests at the need, under the cap ($heightAsking, $needAsking)",
            heightAsking == needAsking && needAsking < CaptureResultGeometry.autoMaxHeight(screen.h))

        answer(longAnswer)
        assertTrue("answered", o.askSession.state.value is AskState.Answered)
        assertEquals(longAnswer, (o.v(R.id.tvAskBody) as TextView).text.toString())
        val grown = o.v(R.id.cardAsk).height - cardAsking
        assertTrue("the card grew ($grown)", grown > 0)
        assertEquals("the need grew by the card's growth", needAsking + grown, o.need)
        assertEquals("the panel follows, up to the cap",
            minOf(o.need, CaptureResultGeometry.autoMaxHeight(screen.h)), o.panelHeight)
        assertTrue("and it grew", o.panelHeight > heightAsking)
    }

    @Test fun `the sheet's Text size opens the picker on its popover host, and a larger answer grows the card and the sheet with it`() {
        val o = open(screen, text(2), text(2), askHidden = false)
        giveLlm()
        tap(chips(o)[0])
        answer("It is about a fox.")
        val button = o.v(R.id.btnFontSizeAsk)
        tap(button)
        assertTrue("${o.popovers.content}", o.popovers.content is FontSizePopover)
        assertSame(button, o.popovers.anchor)

        val need = o.need
        val card = o.v(R.id.cardAsk).height
        val track = o.root.findViewById<View>(R.id.sizePickerTrack)
        assertTrue("precondition: the picker is laid out", track.width > 0)
        // A touch at the track's far end: the largest size.
        val t = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, track.width - 1f, track.height / 2f, 0)
            track.dispatchTouchEvent(e)
            e.recycle()
        }
        settle()
        assertEquals(Prefs.FONT_SP_CEIL, prefs.askFontSp)
        val grown = o.v(R.id.cardAsk).height - card
        assertTrue("the card grew ($grown)", grown > 0)
        assertEquals("the need grew by the card's growth", need + grown, o.need)
        assertEquals("the panel follows, up to the cap",
            minOf(o.need, CaptureResultGeometry.autoMaxHeight(screen.h)), o.panelHeight)
        assertTrue("the picker stays up on its anchor", o.popovers.isShowing)
    }

    @Test fun `a collapsed Ask's pill shows the card for its question without writing the pref, the need growing by its header and card`() {
        val o = open(screen, text(4), text(3), askHidden = true)
        giveLlm()
        val collapsed = o.need
        assertEquals("precondition: collapsed", View.GONE, o.v(R.id.cardAsk).visibility)
        assertTrue("precondition: the need is above the floor", collapsed > floor(screen))
        val pill = pills(o).first { it.isShown }

        tap(pill)
        assertTrue("asking", o.askSession.state.value is AskState.Asking)
        assertEquals("the card shows", View.VISIBLE, o.v(R.id.cardAsk).visibility)
        assertTrue("and the pref still hides the section", prefs.hideAskSection)
        assertEquals("the need grew by the Ask header and card",
            collapsed + o.v(R.id.askHeaderRow).height + o.v(R.id.cardAsk).height, o.need)

        answer("It is about a fox.")
        assertTrue(o.askSession.state.value is AskState.Answered)
        assertTrue("an answer writes no pref either", prefs.hideAskSection)
        assertEquals(View.VISIBLE, o.v(R.id.cardAsk).visibility)
    }

    @Test fun `binding another sentence returns Ask to its questions, and binding the same sentence keeps the answer`() {
        val first = result(text(2), text(2))
        val o = open(screen, first.originalText, first.translatedText, askHidden = false)
        giveLlm()
        tap(chips(o)[0])
        answer("It is about a fox.")
        val answered = o.askSession.state.value
        assertTrue("precondition: an answer on show", answered is AskState.Answered)

        // A new capture result reaches the sheet through its session.
        fun bind(r: TranslationResult) {
            o.observe(CaptureSession(MutableStateFlow<CaptureState>(CaptureState.Done(r)), Job()))
            settle()
        }

        bind(first.copy(translatedText = "Another translation of the same text."))
        assertEquals("the same text and languages keep the answer", answered, o.askSession.state.value)
        assertEquals(View.VISIBLE, o.v(R.id.askAnswer).visibility)

        bind(result("The cat sleeps in the sun.", "Le chat dort au soleil."))
        assertEquals("another sentence ends it", AskState.Idle, o.askSession.state.value)
        assertEquals("the questions are back", View.VISIBLE, o.v(R.id.askIdle).visibility)
        assertEquals(View.GONE, o.v(R.id.askAnswer).visibility)
    }

    @Test fun `a page opened from the sheet receives the result and the finished answer, and no answer while the question is in flight`() {
        val navigations = mutableListOf<Pair<TranslationResult, AskState.Answered?>>()
        val shown = result(text(2), text(2))
        val o = open(screen, shown, askHidden = false, wordsHidden = false) {
            it.onNavigateToDetail = { r, a -> navigations += r to a }
        }
        giveLlm()
        showRows(o, words)
        tap(chips(o)[0])
        answer("It is about a fox.")
        val answered = o.askSession.state.value as AskState.Answered

        // A Words row's tap is a page the sheet routes through the host.
        o.wordRows.tapForTest(0, DefAction.OPEN)
        settle()
        assertEquals(1, navigations.size)
        assertEquals("the result on show", shown, navigations[0].first)
        assertEquals("and the answer on show", answered, navigations[0].second)

        tap(o.v(R.id.btnAskClose))
        tap(chips(o)[0])
        assertTrue("precondition: a question in flight", o.askSession.state.value is AskState.Asking)
        o.wordRows.tapForTest(0, DefAction.OPEN)
        settle()
        assertEquals(2, navigations.size)
        assertEquals(shown, navigations[1].first)
        assertNull("no answer while the question is in flight", navigations[1].second)
    }

    @Test fun `a sheet shown again with an answer shows it, about the result it was shown with`() {
        val answered = AskState.Answered(AskQuestion.Custom("Why the fox?"), "Because it is quick.", "Test LLM")
        val shown = result(text(2), text(2))
        val o = open(screen, shown, askHidden = false, askAnswer = answered)
        assertEquals(View.VISIBLE, o.v(R.id.askAnswer).visibility)
        assertEquals(View.GONE, o.v(R.id.askIdle).visibility)
        assertEquals("Because it is quick.", (o.v(R.id.tvAskBody) as TextView).text.toString())
        assertEquals("Why the fox?", (o.v(R.id.tvAskTitle) as TextView).text.toString())
        assertEquals(answered, o.askSession.state.value)
        assertEquals("the question is about the result shown",
            askSentence(shown.originalText, shown.langContext), o.askSession.sentence)
    }

    // ── Typing in the Ask field ─────────────────────────────────────────

    @Test fun `a touch on the question field is the typing state, in which a long downward drag does not dismiss, where on a sheet with no focus it does`() {
        // The control: the same drag on the same content, nothing focused.
        val control = open(screen, text(2), text(2), askHidden = false)
        assertTrue("precondition: not typing", control.typingIn == "NOTHING" && control.canSwipeAway)
        assertEquals("precondition: the content at its top", 0, control.scroll.scrollY)
        swipeDown(control, screen)
        assertEquals("with no focus the drag dismisses", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
        host.removeAll()
        settle()

        val o = open(screen, text(2), text(2), askHidden = false)
        giveLlm()
        touch(o, o.askField)
        assertTrue("the touch focused the field", o.askField.isFocused)
        assertEquals("ASK_QUESTION", o.typingIn)
        assertFalse("the sheet says no swipe-away while typing", o.canSwipeAway)
        // Taking focus scrolled the field clear of the bottom fading edge.
        // Back at the top, the drag is the body's, so what keeps the sheet
        // is the typing state alone.
        o.scroll.scrollTo(0, 0)
        settle()
        assertEquals("precondition: the content at its top", 0, o.scroll.scrollY)
        val followed = swipeDown(o, screen)
        assertTrue("the sheet followed the finger past the distance ($followed)",
            followed >= constFloat("DISMISS_DISTANCE_DP") * density)
        assertTrue("typing, the drag does not dismiss ($dismissals)", dismissals.isEmpty())
        assertFalse(o.dismissed)
        assertTrue("and the field keeps its focus", o.askField.isFocused)
    }

    @Test fun `once B ends the typing, the long downward drag is the at-the-top rule's again and dismisses`() {
        attachPad()
        val o = open(screen, text(2), text(2), askHidden = false) { it.controllerNavEnabled = true }
        assertNotNull("precondition: the controller nav is live", o.nav)
        giveLlm()
        assertEquals("precondition: the content at its top before typing", 0, o.scroll.scrollY)
        touch(o, o.askField)
        assertTrue("precondition: typing in the field", o.askField.isFocused)

        key(o, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("precondition: the typing is over", "NOTHING", o.typingIn)
        assertTrue("the sheet allows a swipe-away again", o.canSwipeAway)
        // Taking focus scrolled the field clear of the bottom fading edge,
        // and a drag on content that is not at its top scrolls it (the
        // swipe-away's own rule, pinned in CaptureSheetSectionsTest). Back
        // at the top, nothing of the typing is left to keep the sheet.
        o.scroll.scrollTo(0, 0)
        settle()
        swipeDown(o, screen)
        assertEquals("the drag dismisses again", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `B takes the focus off the question field without closing the sheet, and a second B closes it`() {
        attachPad()
        val o = open(screen, text(2), text(2), askHidden = false) { it.controllerNavEnabled = true }
        assertNotNull("precondition: the controller nav is live", o.nav)
        giveLlm()
        touch(o, o.askField)
        assertTrue("precondition: typing in the field", o.askField.isFocused)

        key(o, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse("the first B leaves the field", o.askField.isFocused)
        assertEquals("NOTHING", o.typingIn)
        assertTrue("and does not close the sheet ($dismissals)", dismissals.isEmpty())
        assertFalse(o.dismissed)

        key(o, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("the second B closes it", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `the keyboard closing under the question ends the typing state, and the sheet swipes away again`() {
        val o = open(screen, text(2), text(2), askHidden = false)
        giveLlm()
        touch(o, o.askField)
        assertTrue("precondition: typing in the field", o.askField.isFocused)

        o.root.dispatchApplyWindowInsets(keyboardInsets(o, 400))
        assertEquals("the keyboard lifts the panel", 400, o.panelBottomMargin)
        assertTrue("and the field keeps its focus", o.askField.isFocused)
        assertEquals("ASK_QUESTION", o.typingIn)

        o.root.dispatchApplyWindowInsets(keyboardInsets(o, null))
        assertEquals("the panel is down again", 0, o.panelBottomMargin)
        assertFalse("the field lost its focus", o.askField.isFocused)
        assertEquals("NOTHING", o.typingIn)

        settle()
        assertFalse("and stays without it once everything posted has run", o.askField.isFocused)
        assertFalse("the window no longer takes the keyboard", takesKeyboard(windowParams(o)))
        // Back at the top (the focus scrolled the content, see the B test),
        // the long downward drag dismisses again.
        o.scroll.scrollTo(0, 0)
        settle()
        swipeDown(o, screen)
        assertEquals("the long downward drag dismisses again",
            listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `leaving the source editor never starts typing in the Ask field, with a controller or without`() {
        for (controller in listOf(false, true)) {
            if (controller) attachPad()
            val o = open(screen, text(2), text(2), askHidden = false) { it.controllerNavEnabled = controller }
            assertEquals("the controller nav is live: $controller", controller, o.nav != null)
            assertTrue("precondition: the Ask field is on the sheet ($controller)", o.askField.isShown)
            tap(o.v(R.id.btnEditOriginal))
            assertEquals("precondition: the editor is up ($controller)", "SOURCE_EDIT", o.typingIn)

            // The editor's own way out: its done button.
            val done = (0 until (o.editContainer as ViewGroup).childCount)
                .map { (o.editContainer as ViewGroup).getChildAt(it) }
                .filterIsInstance<android.widget.Button>().single()
            tap(done)

            assertEquals("the editor is gone ($controller)", View.GONE, o.editContainer.visibility)
            assertFalse("the Ask field did not take the focus the editor gave up ($controller)", o.askField.isFocused)
            assertEquals("nothing is being typed in ($controller)", "NOTHING", o.typingIn)
            assertFalse("the window does not take the keyboard ($controller)", takesKeyboard(windowParams(o)))
            close(o)
        }
    }

    @Test fun `the Edit action tapped mid-question hands the typing to the source editor, with a controller or without`() {
        for (controller in listOf(false, true)) {
            if (controller) attachPad()
            val o = open(screen, text(2), text(2), askHidden = false) { it.controllerNavEnabled = controller }
            assertEquals("the controller nav is live: $controller", controller, o.nav != null)
            giveLlm()
            touch(o, o.askField)
            assertTrue("precondition: typing in the question ($controller)", o.askField.isFocused)

            tap(o.v(R.id.btnEditOriginal))

            val editor: EditText = o.field("editText")
            assertEquals("the editor is what is typed in ($controller)", "SOURCE_EDIT", o.typingIn)
            assertTrue("and it holds the focus ($controller)", editor.isFocused)
            assertFalse(o.askField.isFocused)
            assertTrue("the window still takes the keyboard ($controller)", takesKeyboard(windowParams(o)))
            TranslationBackendRegistry.removeOnlineBackend(llm.id)
            close(o)
        }
    }

    @Test fun `the keyboard closing under the source edit leaves its editor up`() {
        val o = open(screen, text(2), text(2), askHidden = false)
        tap(o.v(R.id.btnEditOriginal))
        assertEquals("precondition: the editor is up", View.VISIBLE, o.editContainer.visibility)
        assertEquals("SOURCE_EDIT", o.typingIn)

        o.root.dispatchApplyWindowInsets(keyboardInsets(o, 400))
        assertEquals(400, o.panelBottomMargin)
        o.root.dispatchApplyWindowInsets(keyboardInsets(o, null))
        assertEquals(0, o.panelBottomMargin)
        assertEquals("the editor stays up", View.VISIBLE, o.editContainer.visibility)
        assertEquals("SOURCE_EDIT", o.typingIn)
    }

    @Test fun `the window takes key focus and the keyboard while the question field has focus, and returns to what it was once the focus goes`() {
        for (controller in listOf(false, true)) {
            if (controller) attachPad()
            val o = open(screen, text(2), text(2), askHidden = false) { it.controllerNavEnabled = controller }
            assertEquals("the controller nav is live: $controller", controller, o.nav != null)
            giveLlm()
            val before = focusPolicy(windowParams(o))
            assertEquals("before: key focus only with a controller ($controller)",
                controller, takesKeyFocus(windowParams(o)))
            assertFalse("before: never the keyboard ($controller)", takesKeyboard(windowParams(o)))

            touch(o, o.askField)
            assertTrue("precondition: typing ($controller)", o.askField.isFocused)
            assertTrue("typing: the window takes the keyboard ($controller)", takesKeyboard(windowParams(o)))

            // The eye hides the card, and the field's focus goes with it.
            tap(o.v(R.id.btnToggleAsk))
            assertFalse("precondition: the field lost its focus ($controller)", o.askField.isFocused)
            assertEquals("the policy is back to what it was ($controller)", before, focusPolicy(windowParams(o)))
            close(o)
        }
    }

    @Test fun `with no LLM a chip raises the sheet's alert and asks nothing, and the alert's button opens Translation services and closes an over-game sheet`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        val o = open(screen, text(2), text(2), askHidden = false)
        assertTrue("precondition: an over-game sheet", o.dismissOnActivityLaunch)
        assertTrue("precondition: no alert yet", otherWindows(o).isEmpty())

        tap(chips(o)[0])
        assertEquals("nothing asked", AskState.Idle, o.askSession.state.value)
        assertTrue("no preset marked asked", prefs.askPresetRecency.isEmpty())
        val alert = otherWindows(o).single()
        val texts = textViewsIn(alert)
        assertTrue("the alert's title", texts.any { it.text.toString() == ctx.getString(R.string.ask_no_llm_title) })
        val open = texts.filterIsInstance<Button>()
            .single { it.text.toString() == ctx.getString(R.string.ask_no_llm_open_services) }

        open.performClick()
        settle()
        val started = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
        assertEquals("the over-game sheet goes for the page", listOf(CaptureResultOverlay.DismissReason.PAGE), dismissals)
        assertTrue(o.dismissed)
    }

    /** The sentence the sheet's own lookup settled with: what its rows'
     *  explain questions are about. */
    private fun lookupSentence(o: CaptureResultOverlay): AskSentence =
        requireNotNull((o.wordLookups.state.value as WordLookupsState.Settled).sentence)

    @Test fun `the sheet's own lookup hands its rows the text they are the words of, so each has the explain button`() {
        val o = open(screen, text(2), text(2), askHidden = true, wordsHidden = false)
        val settled = o.wordLookups.state.value as WordLookupsState.Settled
        assertEquals(
            "the text the lookup analysed, under the pair it analysed it in: what Ask is asked about here too",
            o.askSession.sentence,
            settled.sentence,
        )
        assertEquals(text(2), settled.sentence?.text)
        // The tokenizer here has no pack and finds no words (class doc), so
        // rows are put in the lookup's place, with the sentence it settled.
        showRows(o, words, settled.sentence)
        assertTrue(o.wordRows.shownRows.all { it.explain == DefRow.Explain() })
        // And rows that come with none have no button.
        showRows(o, words)
        assertTrue(o.wordRows.shownRows.all { it.explain == null })
    }

    @Test fun `with no LLM a Words row's explain button raises the sheet's alert, the one its Ask chips raise`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        val o = open(screen, text(2), text(2), askHidden = true, wordsHidden = false)
        showRows(o, words, lookupSentence(o))
        assertTrue("precondition: no alert yet", otherWindows(o).isEmpty())

        o.wordRows.tapForTest(0, DefAction.EXPLAIN)
        settle()

        val texts = textViewsIn(otherWindows(o).single())
        assertTrue("the alert's title", texts.any { it.text.toString() == ctx.getString(R.string.ask_no_llm_title) })
        assertEquals("nothing was asked", DefRow.Explain(), o.wordRows.shownRows[0].explain)
        texts.filterIsInstance<Button>()
            .single { it.text.toString() == ctx.getString(R.string.ask_no_llm_open_services) }
            .performClick()
        settle()
        assertEquals(
            TranslationServicesActivity::class.java.name,
            shadowOf(ctx as Application).nextStartedActivity?.component?.className,
        )
    }

    @Test fun `with no LLM the explain button in the sheet's own lens closes the lens and raises the sheet's alert`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        val o = open(screen, text(2), text(2), askHidden = true, wordsHidden = false)
        // The tap spans of the first two words, as an analysis gives them.
        val the = com.playtranslate.language.TokenSpan("The", "the")
        val quick = com.playtranslate.language.TokenSpan("quick", "quick")
        o.lookupsRenderer.render(
            WordLookupsState.Analyzed(
                tokenSpans = listOf(the, quick),
                annotation = com.playtranslate.language.SentenceAnnotation(
                    text(2), com.playtranslate.language.SourceLangId.EN, 0,
                    listOf(
                        com.playtranslate.language.AnnotatedSpan(0, 3, "The", lookupForm = "the", lookupHint = ""),
                        com.playtranslate.language.AnnotatedSpan(4, 9, "quick", lookupForm = "quick", lookupHint = ""),
                    ),
                ),
                phrases = emptyList(),
            ),
        )
        settle()
        val sourceLens: SourceTextLens = o.field("sourceLens")
        assertEquals("precondition: the source text has its tap spans", 2, sourceLens.wordSpans.size)

        sourceLens.onTapAtOffset(5)
        val deadline = System.currentTimeMillis() + 3000
        while (!sourceLens.isShowing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("precondition: the lens came up over the word", sourceLens.isShowing)
        val lens: MagnifierLens = sourceLens.field("lens")
        val row = lens.shownRowsForTest().filterIsInstance<DefRow.Word>().first()
        assertEquals(DefRow.Explain(), row.explain)
        val windowsBefore = otherWindows(o).toSet()

        lens.tapRowForTest(row.id, DefAction.EXPLAIN)
        settle()

        assertFalse("the lens is gone", sourceLens.isShowing)
        val raised = otherWindows(o).filter { it !in windowsBefore }.flatMap { textViewsIn(it) }.map { it.text.toString() }
        assertTrue("the sheet's alert, the one its Ask chips raise: $raised", ctx.getString(R.string.ask_no_llm_title) in raised)
    }

    @Test fun `a Words row's explain question is answered in its row, and the Ask section is left as it was`() {
        giveLlm()
        val o = open(screen, text(2), text(2), askHidden = false, wordsHidden = false)
        showRows(o, words, lookupSentence(o))
        val word = o.wordRows.shownRows[0].data.word

        o.wordRows.tapForTest(0, DefAction.EXPLAIN)
        settle()
        assertEquals(DefRow.Explain(busy = true), o.wordRows.shownRows[0].explain)
        assertTrue("about that row's word: ${llm.lastPrompt?.second}", llm.lastPrompt!!.second.contains("\"$word\""))
        assertTrue("in the sheet's text", llm.lastPrompt!!.second.contains(text(2)))

        answer("It names the animal.")

        val shown = o.wordRows.shownRows[0].explain!!.answer!!
        assertEquals(ctx.getString(R.string.word_explain_title), shown.title)
        assertEquals("It names the animal.", shown.text)
        assertEquals(ctx.getString(R.string.ask_answered_by, "Test LLM"), shown.footer)
        assertEquals("a word's answer is the row's, never the section's", AskState.Idle, o.askSession.state.value)
    }

    @Test fun `a host that keeps the sheet through a launch keeps it when the alert opens Translation services`() {
        val o = open(screen, text(2), text(2), askHidden = false) { it.dismissOnActivityLaunch = false }
        tap(chips(o)[0])
        val open = textViewsIn(otherWindows(o).single()).filterIsInstance<Button>()
            .single { it.text.toString() == ctx.getString(R.string.ask_no_llm_open_services) }

        open.performClick()
        settle()
        val started = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
        assertTrue("the sheet stays ($dismissals)", dismissals.isEmpty())
        assertFalse(o.dismissed)
    }

    // ── Words on the sheet ──────────────────────────────────────────────

    @Test fun `a Words row's tap opens the word's page by the sheet's route, the host's hand-off when it has one, else the sheet closes`() {
        val navigations = mutableListOf<TranslationResult>()
        val shown = result(text(2), text(2))
        val o = open(screen, shown, askHidden = true, wordsHidden = false) {
            it.onNavigateToDetail = { r, _ -> navigations += r }
        }
        assertEquals(View.VISIBLE, o.v(R.id.cardWords).visibility)
        showRows(o, words)

        o.wordRows.tapForTest(1, DefAction.OPEN)
        settle()
        val page = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationResultActivity::class.java.name, page?.component?.className)
        assertEquals("the tapped row's word", "dog", page?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_WORD))
        assertEquals("in its sentence", shown.originalText, page?.getStringExtra(TranslationResultActivity.EXTRA_SENTENCE_TEXT))
        assertEquals("the host is handed the result", listOf(shown), navigations)
        assertFalse("and decides about the sheet", o.dismissed)
        close(o)

        // No hand-off: an over-game sheet goes for the page.
        val bare = open(screen, shown.originalText, shown.translatedText, askHidden = true, wordsHidden = false)
        showRows(bare, words)
        bare.wordRows.tapForTest(0, DefAction.OPEN)
        settle()
        assertEquals(TranslationResultActivity::class.java.name,
            shadowOf(ctx as Application).nextStartedActivity?.component?.className)
        assertEquals(listOf(CaptureResultOverlay.DismissReason.PAGE), dismissals)
    }

    // ── A row's answer, for an Anki card's note ─────────────────────────

    // Mutations: drop the answer on the way to the page; hand a row its
    // neighbour's.
    @Test fun `a Words row opened with an answer standing in it carries the answer to the word's page, and a row with none carries none`() {
        giveLlm()
        val o = open(screen, result(text(2), text(2)), askHidden = true, wordsHidden = false) {
            it.onNavigateToDetail = { _, _ -> }
        }
        showRows(o, words, lookupSentence(o))
        o.wordRows.tapForTest(0, DefAction.EXPLAIN)
        settle()
        answer("It names the animal.")

        o.wordRows.tapForTest(0, DefAction.OPEN)
        settle()
        val explained = shadowOf(ctx as Application).nextStartedActivity
        assertEquals("fox", explained?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_WORD))
        assertEquals("It names the animal.", explained?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_EXPLANATION))
        // The whole answer, who gave it included, for the page to draw.
        assertEquals(
            Explanation("It names the animal.", "Test LLM"),
            explained?.extras?.getExplanation(TranslationResultActivity.EXTRA_DRAG_EXPLANATION),
        )

        o.wordRows.tapForTest(1, DefAction.OPEN)
        settle()
        val plain = shadowOf(ctx as Application).nextStartedActivity
        assertEquals("dog", plain?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_WORD))
        assertFalse("no answer in that row", plain!!.hasExtra(TranslationResultActivity.EXTRA_DRAG_EXPLANATION))
    }

    // Mutations: send the sentence card no note; send it one answer when
    // two stand; order them by when they were asked.
    @Test fun `the sheet's sentence card starts its note with its explained words' answers, each under its word`() {
        giveLlm()
        shadowOf(ctx.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = "com.ichi2.anki" },
        )
        val o = open(screen, result(text(2), text(2)), askHidden = true, wordsHidden = false)
        showRows(o, words, lookupSentence(o))
        o.wordRows.tapForTest(1, DefAction.EXPLAIN)
        settle()
        answer("It is the one jumped over.")
        o.wordRows.tapForTest(0, DefAction.EXPLAIN)
        settle()
        answer("It names the animal.")

        tap(o.v(R.id.btnAnkiOriginal))

        // The AnkiDroid permission is not held here, so the card goes
        // through the trampoline, whose extras rebuild the editor's args.
        val launched = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(AnkiPermissionActivity::class.java.name, launched?.component?.className)
        assertEquals(text(2), launched?.getStringExtra(SentenceAnkiReviewActivity.EXTRA_SENTENCE))
        assertEquals(
            "fox\nIt names the animal.\n\ndog\nIt is the one jumped over.",
            launched!!.extras!!.getAnkiNotes(),
        )
    }

    // Mutations: the sheet's sentence card leaves the Ask section's answer
    // out; heads a lone answer with its question; puts it after the words.
    @Test fun `the sheet's sentence card starts its note with the Ask answer, as it is alone and ahead of its explained words under its question`() {
        giveLlm()
        shadowOf(ctx.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = "com.ichi2.anki" },
        )
        val alone = open(screen, result(text(2), text(2)), askHidden = false, wordsHidden = false)
        tap(chips(alone)[0])
        answer("It is about a fox.")
        val question = (alone.askSession.state.value as AskState.Answered).question.title

        tap(alone.v(R.id.btnAnkiOriginal))

        val first = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(AnkiPermissionActivity::class.java.name, first?.component?.className)
        assertEquals("It is about a fox.", first!!.extras!!.getAnkiNotes())
        close(alone)

        val both = open(screen, result(text(2), text(2)), askHidden = false, wordsHidden = false)
        showRows(both, words, lookupSentence(both))
        both.wordRows.tapForTest(1, DefAction.EXPLAIN)
        settle()
        answer("It is the one jumped over.")
        tap(chips(both)[0])
        answer("It is about a fox.")

        tap(both.v(R.id.btnAnkiOriginal))

        val second = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(
            "$question\nIt is about a fox.\n\ndog\nIt is the one jumped over.",
            second!!.extras!!.getAnkiNotes(),
        )
    }

    // Mutations: an answer of any length goes into the launch; the cut
    // falls somewhere other than the limit.
    @Test fun `answers too long for a launch are cut at the limit on their way into it, to the card and to a word's page`() {
        giveLlm()
        shadowOf(ctx.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = "com.ichi2.anki" },
        )
        val long = "a".repeat(ANKI_NOTE_MAX_CHARS + 2_000)
        val o = open(screen, result(text(2), text(2)), askHidden = true, wordsHidden = false) {
            it.onNavigateToDetail = { _, _ -> }
        }
        showRows(o, words, lookupSentence(o))
        o.wordRows.tapForTest(0, DefAction.EXPLAIN)
        settle()
        answer(long)
        o.wordRows.tapForTest(1, DefAction.EXPLAIN)
        settle()
        answer(long)

        // To the word's page: the one answer, cut.
        o.wordRows.tapForTest(0, DefAction.OPEN)
        settle()
        val page = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(
            "a".repeat(ANKI_NOTE_MAX_CHARS) + "…",
            page?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_EXPLANATION),
        )

        // To the sentence card: both answers under their words, the whole cut.
        tap(o.v(R.id.btnAnkiOriginal))
        val card = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(AnkiPermissionActivity::class.java.name, card?.component?.className)
        val notes = card!!.extras!!.getAnkiNotes()
        assertEquals(ANKI_NOTE_MAX_CHARS + 1, notes.length)
        assertTrue(notes.startsWith("fox\naaa"))
        assertTrue(notes.endsWith("a…"))
    }

    // Mutation: the lens's actions do not read the row's answer.
    @Test fun `a word opened from the sheet's lens carries the answer standing in its lens row`() {
        giveLlm()
        val o = open(screen, result(text(2), text(2)), askHidden = true, wordsHidden = false) {
            it.onNavigateToDetail = { _, _ -> }
        }
        val the = com.playtranslate.language.TokenSpan("The", "the")
        val quick = com.playtranslate.language.TokenSpan("quick", "quick")
        o.lookupsRenderer.render(
            WordLookupsState.Analyzed(
                tokenSpans = listOf(the, quick),
                annotation = com.playtranslate.language.SentenceAnnotation(
                    text(2), com.playtranslate.language.SourceLangId.EN, 0,
                    listOf(
                        com.playtranslate.language.AnnotatedSpan(0, 3, "The", lookupForm = "the", lookupHint = ""),
                        com.playtranslate.language.AnnotatedSpan(4, 9, "quick", lookupForm = "quick", lookupHint = ""),
                    ),
                ),
                phrases = emptyList(),
            ),
        )
        settle()
        val sourceLens: SourceTextLens = o.field("sourceLens")
        sourceLens.onTapAtOffset(5)
        val deadline = System.currentTimeMillis() + 3000
        while (!sourceLens.isShowing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("precondition: the lens came up over the word", sourceLens.isShowing)
        val lens: MagnifierLens = sourceLens.field("lens")
        val row = lens.shownRowsForTest().filterIsInstance<DefRow.Word>().first()
        lens.tapRowForTest(row.id, DefAction.EXPLAIN)
        settle()
        answer("It describes the fox.")
        assertEquals(
            "precondition: the answer stands in the lens's row",
            Explanation("It describes the fox.", "Test LLM"), lens.explanationOf(null),
        )

        lens.tapRowForTest(row.id, DefAction.OPEN)
        settle()

        val page = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationResultActivity::class.java.name, page?.component?.className)
        assertEquals("It describes the fox.", page?.getStringExtra(TranslationResultActivity.EXTRA_DRAG_EXPLANATION))
        assertEquals(
            Explanation("It describes the fox.", "Test LLM"),
            page?.extras?.getExplanation(TranslationResultActivity.EXTRA_DRAG_EXPLANATION),
        )
    }

    @Test fun `the sheet caches its sentence's translation whether Words is shown or hidden, and a blank translation caches nothing`() {
        for (wordsHidden in listOf(false, true)) {
            LastSentenceCache.clear()
            val o = open(screen, text(2), "Le renard saute.", askHidden = true, wordsHidden = wordsHidden)
            assertEquals("the sentence (Words hidden: $wordsHidden)", text(2), LastSentenceCache.original)
            assertEquals("its translation (Words hidden: $wordsHidden)", "Le renard saute.", LastSentenceCache.translation)
            close(o)
        }
        for (wordsHidden in listOf(false, true)) {
            LastSentenceCache.clear()
            val o = open(screen, text(3), "", askHidden = true, wordsHidden = wordsHidden)
            assertNull("no sentence (Words hidden: $wordsHidden)", LastSentenceCache.original)
            assertNull("no translation (Words hidden: $wordsHidden)", LastSentenceCache.translation)
            close(o)
        }
    }

    @Test fun `with Words hidden the lookup stops at its analysis, where a shown card has its rows looked up`() {
        val shownCard = open(screen, text(2), text(2), askHidden = true, wordsHidden = false)
        assertTrue("a shown card's rows settle", shownCard.wordLookups.state.value is WordLookupsState.Settled)
        close(shownCard)

        val o = open(screen, text(2), text(2), askHidden = true, wordsHidden = true)
        assertTrue("the analysis ran (${o.wordLookups.state.value})", o.wordLookups.state.value is WordLookupsState.Analyzed)
        // Time for rows that were asked for to settle, on real threads.
        val until = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        settle()
        assertTrue("and no rows were looked up (${o.wordLookups.state.value})",
            o.wordLookups.state.value is WordLookupsState.Analyzed)
        assertTrue(o.wordRows.isEmpty)
    }

    @Test fun `the need counts a shown Words card and its header row, and the hidden Ask header above it, and re-fits when the rows lay out`() {
        val o = open(screen, text(4), text(3), askHidden = true, wordsHidden = false)
        showRows(o, words)
        val card = o.v(R.id.cardWords)
        // Rows the page has not drawn: the card holds the loading line over
        // the page's one pixel.
        val line = o.v(R.id.tvMainWordsLoading)
        assertTrue("precondition: the loading line stands in for the rows", line.isShown)
        val needBefore = o.need
        val cardBefore = card.height
        val lineBefore = line.height

        list(o).layoutForTest(ROW_CSS_PX)
        settle()
        assertEquals("precondition: the line went with the rows drawn", View.GONE, line.visibility)
        val grown = card.height - cardBefore
        assertEquals("precondition: the rows took the line's place at their reported height",
            words.size * dp(ROW_CSS_PX) - 1 - lineBefore, grown)
        assertEquals("the need re-fit by the card's growth", needBefore + grown, o.need)

        // Per belowNeed(): every header and card down to the last card shown.
        // The Words card is that card, so its header counts, and so does the
        // hidden Ask's header row above it. With Words hidden there is no
        // card below the two texts, and neither header counts.
        val expected = o.v(R.id.askHeaderRow).height + o.v(R.id.wordsHeaderRow).height + card.height
        val need = o.need
        tap(o.v(R.id.btnToggleWords))
        assertEquals("precondition: Words hidden", View.GONE, card.visibility)
        assertTrue("precondition: the hidden need is above the floor", o.need > floor(screen))
        assertEquals(expected, need - o.need)
    }

    @Test fun `with a card shown below them the two texts' cards keep their own height, and with both lower sections hidden they stretch to the body`() {
        for ((what, askHidden, wordsHidden) in listOf(
            Triple("Ask shown", false, true),
            Triple("Words shown", true, false),
        )) {
            val o = open(tall, "Short.", "Short too.", askHidden = askHidden, wordsHidden = wordsHidden)
            assertEquals("precondition: the panel rests at its floor ($what)", floor(tall), o.panelHeight)
            for (id in listOf(R.id.cardOriginal, R.id.cardTranslation)) {
                val card = o.v(id)
                assertEquals("no minimum on the card ($what)", 0, card.minimumHeight)
                assertFalse("not stretched past the pair's own height ($what)", stretched(o, id))
            }
            close(o)
        }
        // The contrast, pinned in full by CaptureSheetSectionsTest.
        val o = open(tall, "Short.", "Short too.", askHidden = true, wordsHidden = true)
        for (id in listOf(R.id.cardOriginal, R.id.cardTranslation)) {
            assertTrue("both lower sections hidden: a minimum", o.v(id).minimumHeight > 0)
            assertTrue("both lower sections hidden: stretched", stretched(o, id))
        }
    }

    /** Whether the paired text card [cardId] is taller than the pair's own
     *  height: the taller of the two cards' content and frame, which the
     *  pair gives both cards (the Translation's note row makes it the
     *  taller here). Taller than that is the fill's stretch. */
    private fun stretched(o: CaptureResultOverlay, cardId: Int): Boolean {
        fun natural(card: Int, content: Int): Int =
            o.v(content).height + o.v(card).paddingTop + o.v(card).paddingBottom
        val pair = maxOf(
            natural(R.id.cardOriginal, R.id.originalContent),
            natural(R.id.cardTranslation, R.id.translationContent),
        )
        return o.v(cardId).height > pair
    }

    // ── The controller on the sheet ─────────────────────────────────────

    @Test fun `the controller reaches the texts' header actions, then Ask's, then the Words eye and its rows, in that order, in each state`() {
        val o = open(screen, text(2), text(2), askHidden = false, wordsHidden = false)
        giveLlm()
        showRows(o, words)
        list(o).layoutForTest(ROW_CSS_PX)
        settle()
        val askEye = o.v(R.id.btnToggleAsk)
        val wordsEye = o.v(R.id.btnToggleWords)
        val doc = assertNotNullAnd(o.wordRows.rowsView, "the rows are drawn")
        val targets = list(o).targets.filter { it.act.isNotEmpty() }
        assertTrue("precondition: the rows report targets", targets.isNotEmpty())

        fun texts() = o.binder.navigableActions().map { it.view }
        fun views() = o.navHost.navActions().map { it.view }

        // Nothing asked, the field empty: the eye, the chips and the field.
        assertEquals(
            texts() + askEye + chips(o) + o.askField + wordsEye + targets.map { doc },
            views(),
        )
        val actions = o.navHost.navActions()
        assertTrue("every action but the rows' is a view of its own",
            actions.dropLast(targets.size).all { it.target == null })
        assertEquals("the rows' targets, in the page's order",
            targets.map { it.rowId to it.act },
            actions.takeLast(targets.size).map { (it.target as DocNavTarget).let { t -> t.rowId to t.act } })

        // Something to send: send follows the field.
        o.askField.setText("Why the fox?")
        assertEquals(
            texts() + askEye + chips(o) + o.askField + o.v(R.id.btnAskSend) + wordsEye + targets.map { doc },
            views(),
        )

        // An answer on show: the Text size before the eye, and X instead
        // of the chips and the field.
        tap(chips(o)[0])
        answer("It is about a fox.")
        assertTrue("precondition: answered", o.askSession.state.value is AskState.Answered)
        assertEquals(
            texts() + o.v(R.id.btnFontSizeAsk) + askEye + o.v(R.id.btnAskClose) + wordsEye + targets.map { doc },
            views(),
        )

        // Ask and Words hidden: their headers' actions alone.
        tap(o.v(R.id.btnAskClose))
        tap(askEye)
        tap(wordsEye)
        assertEquals("precondition: Ask collapsed", View.GONE, o.v(R.id.cardAsk).visibility)
        assertEquals("precondition: Words hidden", View.GONE, o.v(R.id.cardWords).visibility)
        val shownPills = pills(o).filter { it.isShown }
        assertTrue("precondition: a pill shows in the collapsed header", shownPills.isNotEmpty())
        assertEquals(texts() + shownPills + askEye + wordsEye, views())
    }

    @Test fun `A on the question field focuses it and drops the cursor, and while typing the dpad is the field's`() {
        attachPad()
        val o = open(screen, "Short.", "Short too.", askHidden = false) { it.controllerNavEnabled = true }
        giveLlm()
        val nav = assertNotNullAnd(o.nav, "the controller nav is live")
        val field = o.askField

        // Down through the sheet until the cursor is on the field.
        var presses = 0
        while ((cursorOf(o) as? CaptureSheetControllerNav.Item.Button)?.view !== field) {
            if (++presses > 30) fail("the cursor never reached the field (it is on ${cursorOf(o)})")
            key(o, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue("precondition: the ring is on the field", ringShown(o))
        assertFalse("precondition: no focus yet", field.isFocused)

        key(o, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue("A focused the field", field.isFocused)
        assertEquals("ASK_QUESTION", o.typingIn)
        assertNull("the nav dropped its cursor", cursorOf(o))
        assertFalse("and its ring", ringShown(o))
        assertFalse("while typing the dpad is the field's",
            nav.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
    }

    private fun <T : Any> assertNotNullAnd(value: T?, message: String): T {
        assertNotNull("precondition: $message", value)
        return value!!
    }

    private companion object {
        /** A Words row's height in the page's stand-in layout, in CSS px. */
        const val ROW_CSS_PX = 60
    }
}
