package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.MainActivity
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.audio.FakeSpeech
import com.playtranslate.audio.AudioRequest
import com.playtranslate.R
import com.playtranslate.displaySizePx
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlayThemedContext
import com.playtranslate.themeColor
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * A capture's Definitions presentation ([WordLookupSession]) on its own, with
 * the boxes view it drives and the card's real windows: one line ("hello
 * world"), two words. The boxes land once the tokens are in; every tap is
 * judged in one place (a word opens or moves the card, a word whose card is
 * up closes it, a tap off every word closes a card and is the host's with
 * none); a card that ends by its own means ends the presentation, one the
 * session closes does not; Back closes a card and is the host's with none;
 * the controller lights, moves and selects, and with a controller attached
 * the card takes it, its cursor on its first row when A opened it, until B
 * closes the card and hands it back to the boxes. A card cites the capture's
 * frame whether it came with the lines or after the card opened.
 */
@RunWith(RobolectricTestRunner::class)
class WordLookupSessionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val view = WordBoxesView(overlayThemedContext(ctx))
    private var ended = 0
    private val settled = mutableListOf<Boolean>()
    private var tokenizer: suspend (String) -> List<TokenSpan> = { text ->
        text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }
    }

    /** Built on first use, so a test can attach a controller first. */
    private val session by lazy {
        WordLookupSession(
            kit = LookupLensKit.overGame(
                context = ctx,
                displayCtx = ctx,
                wm = wm,
                overlayHost = host,
                displayId = Display.DEFAULT_DISPLAY,
            ),
            density = ctx.resources.displayMetrics.density,
            view = view,
            scope = scope,
            tokenize = { tokenizer(it) },
            onEnded = { ended++ },
            onBoxesSettled = { settled += it },
        )
    }

    /** One line, "hello world", 20 px a character from x = 100 at y 300..330:
     *  "hello" 100..200, "world" 220..320. */
    private val helloWorld = line("hello world", 300, (0 until 11).map { 100 + it * 20 })

    private fun line(text: String, top: Int, lefts: List<Int>) = OcrManager.OcrLine(
        text = text,
        bounds = Rect(lefts.first(), top, lefts.last() + 20, top + 30),
        symbols = text.mapIndexedNotNull { i, ch ->
            if (ch == ' ') null
            else OcrManager.SymbolBox(ch.toString(), Rect(lefts[i], top, lefts[i] + 20, top + 30), i)
        },
    )

    @After fun tearDown() {
        MainActivity.isInForeground = false
        session.release()
        scope.cancel()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        FakeSpeech.uninstall()
        Prefs(ctx).autoPlayWords = false
    }

    // ── harness ──────────────────────────────────────────────────────────

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    /** The box whose card is up. */
    private fun selected(): Int? = session.field("selected")

    /** The box the spotlight and glow are on. */
    private fun highlighted(): Int? = view.field("highlighted")

    private fun cursor(): Int? = session.field("cursor")

    private fun lens(): MagnifierLens = session.field("magnifier")

    private fun lookup(): DragLookupController = session.field("lookup")

    /** Wait out the tokenization on the background dispatcher. */
    private fun settle() {
        val deadline = System.currentTimeMillis() + 3000
        while (session.pending && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun load(vararg lines: OcrManager.OcrLine) {
        session.load(lines.toList(), null, 0L)
        settle()
    }

    private fun press(code: Int) {
        session.onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
        session.onKey(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** The card up and sticky on the word, as the lookup leaves it (the
     *  lookup itself does not complete here). */
    private fun cardUp(): MagnifierLens {
        val lens = lens()
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(150, 300, screen.x, screen.y, anchorHeight = 30))
        assertTrue(lens.makeInteractive())
        return lens
    }

    // ── boxes ────────────────────────────────────────────────────────────

    @Test fun `a session is pending until its first load lands`() {
        assertTrue("nothing boxed yet", session.pending)
        assertFalse(session.hasBoxes)

        load(helloWorld)

        assertFalse(session.pending)
        assertTrue(session.hasBoxes)
    }

    @Test fun `the boxes land once the tokens are in, one per word`() {
        session.load(listOf(helloWorld), null, 0L)
        assertTrue("tokenizing", session.pending)
        assertFalse(session.hasBoxes)

        settle()

        assertFalse(session.pending)
        assertEquals(listOf("hello", "world"), view.shownBoxes.map { it.surface })
        assertEquals(listOf(true), settled)
    }

    @Test fun `a tokenizer that fails lands no boxes and says so`() {
        tokenizer = { throw IllegalStateException("no dictionary") }
        load(helloWorld)
        assertFalse(session.hasBoxes)
        assertEquals(listOf(false), settled)
    }

    @Test fun `a session released while it tokenizes never paints`() {
        val gate = CompletableDeferred<Unit>()
        tokenizer = { text -> gate.await(); listOf(TokenSpan(text, text)) }
        session.load(listOf(helloWorld), null, 0L)
        shadowOf(Looper.getMainLooper()).idle()

        session.release()
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertTrue(view.shownBoxes.isEmpty())
        assertTrue(settled.isEmpty())
    }

    @Test fun `a reload of the same lines keeps the card on its word`() {
        load(helloWorld)
        session.onTapAt(260f, 315f)

        load(helloWorld)

        assertEquals(1, selected())
        assertEquals(0, ended)
    }

    // ── taps ─────────────────────────────────────────────────────────────

    @Test fun `a tap on a word lights its box and opens its card there`() {
        load(helloWorld)
        assertNull("nothing lit before a word is picked", highlighted())

        assertTrue(session.onTapAt(150f, 315f))

        assertEquals(0, selected())
        assertEquals(0, highlighted())
        assertTrue(session.hasCard)
    }

    @Test fun `a tap on no word with no card up is the host's`() {
        load(helloWorld)
        assertFalse(session.onTapAt(600f, 600f))
        assertNull(selected())
        assertEquals(0, ended)
    }

    @Test fun `a tap a little outside a word picks it, and one farther off is the host's`() {
        load(helloWorld)
        val density = ctx.resources.displayMetrics.density
        // "hello"'s box ends at y = 333; 20dp below it is inside the buffer.
        assertTrue(session.onTapAt(150f, 333f + 20 * density))
        assertEquals(0, selected())

        assertTrue("that closes the card", session.onTapAt(150f, 333f + 20 * density))
        assertNull(selected())

        assertFalse("past the buffer, with no card up", session.onTapAt(150f, 333f + 30 * density))
    }

    @Test fun `with a card up, a tap on no word closes only the card`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        assertTrue(session.onTapAt(600f, 600f))

        assertNull("the word is cleared", selected())
        assertNull("with no controller cursor, nothing stays lit", highlighted())
        assertTrue("the boxes stay", session.hasBoxes)
        assertEquals(0, ended)
    }

    @Test fun `with a card up, a tap on its own word closes the card`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        assertTrue(session.onTapAt(150f, 315f))

        assertNull(selected())
        assertEquals(0, ended)
    }

    @Test fun `with a card up, a tap on another word moves the card to it`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        session.onTapAt(260f, 315f)

        assertEquals(1, selected())
        assertEquals(1, highlighted())
        assertEquals(0, ended)
    }

    @Test fun `the card's off-card hook is a tap that moves the card and then closes it`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        lens().onOffCardDown!!.invoke(260f, 315f)
        assertEquals(1, selected())

        lens().onOffCardDown!!.invoke(600f, 600f)
        assertNull(selected())
        assertEquals(0, ended)
    }

    @Test fun `on two screens a word's card leaves the app's page to the capture`() {
        // The app in front on a second screen, where a drag's lookup sends
        // its sentence to the page.
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        Prefs(ctx).sourceLang = "en"
        load(helloWorld)

        session.onTapAt(150f, 315f)
        val deadline = System.currentTimeMillis() + 3000
        while (!lens().isInteractive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertTrue("the card came up", lens().isInteractive)
        val app = shadowOf(ctx as Application)
        val started = generateSequence { app.nextStartedActivity }.map { it.action }.toList()
        assertFalse(started.toString(), MainActivity.ACTION_DRAG_SENTENCE in started)
        assertEquals(0, ended)
    }

    // ── the card's explain button ────────────────────────────────────────
    //
    // This card's owner is the drag lookup, the one lens owner with no Ask
    // section behind it: it says what a row's question is about (the
    // sentence its lookup cut out of the line's group) and raises the
    // "needs an AI model" alert itself.

    @Test fun `with the Words setting on, a word's card speaks the word as it comes up, and with it off says nothing`() {
        val speech = FakeSpeech().also { it.install() }
        cardOnHello()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("off until the user turns it on", speech.played.isEmpty())

        // The card closed, the setting on, and the same word tapped again.
        session.onTapAt(150f, 315f)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("precondition: a tap on the card's own word closed it", lens().isInteractive)
        Prefs(ctx).autoPlayWords = true
        cardOnHello()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "the lookup's release path, which the drag lens shares",
            listOf(Triple("hello", AudioRequest.Kind.WORD, SourceLangId.EN)),
            speech.played.map { Triple(it.surface, it.kind, it.lang) },
        )
    }

    /** The card up on "hello" by the real lookup, its row laid out. */
    private fun cardOnHello(): DefRow.Word {
        Prefs(ctx).sourceLang = "en"
        load(helloWorld)
        session.onTapAt(150f, 315f)
        awaitCard()
        return lens().shownRowsForTest().filterIsInstance<DefRow.Word>().first()
    }

    /** Wait out the real lookup until its card is up and sticky. */
    private fun awaitCard() {
        val deadline = System.currentTimeMillis() + 3000
        while (!lens().isInteractive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("precondition: the card came up", lens().isInteractive)
    }

    private fun alertTexts(): List<String> {
        fun texts(v: View): List<String> = when (v) {
            is TextView -> listOf(v.text.toString())
            is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
            else -> emptyList()
        }
        return host.field<List<OverlayHost.OverlayHandle>>("overlayWindows").flatMap { texts(it.view) }
    }

    private fun alertButton(text: String): View {
        fun find(v: View): View? = when {
            v is android.widget.Button && v.text.toString() == text -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return host.field<List<OverlayHost.OverlayHandle>>("overlayWindows").firstNotNullOf { find(it.view) }
    }

    @Test fun `a word's card has the explain button, and its question is about the sentence the word was found in`() {
        val llm = FakeCompleterBackend(id = "lookup-test-llm", priority = 0, displayName = "Test LLM")
        TranslationBackendRegistry.addOnlineBackend(llm)
        try {
            val row = cardOnHello()
            assertEquals(DefRow.Explain(), row.explain)

            lens().tapRowForTest(row.id, DefAction.EXPLAIN)
            shadowOf(Looper.getMainLooper()).idle()

            val user = requireNotNull(llm.lastPrompt).second
            assertTrue("the word as the card heads it: $user", user.contains("\"${row.data.word}\""))
            assertTrue("in the line it was tapped in: $user", user.contains("hello world"))
            assertTrue("the card stays for its answer", lens().isInteractive)
        } finally {
            TranslationBackendRegistry.removeOnlineBackend(llm.id)
        }
    }

    @Test fun `with no AI model the card's explain button closes the card and raises the alert over the game`() {
        assertFalse("precondition: nothing here could answer", TranslationBackendRegistry.hasCompleter())
        val row = cardOnHello()
        assertFalse("precondition: no alert yet", ctx.getString(R.string.ask_no_llm_title) in alertTexts())

        lens().tapRowForTest(row.id, DefAction.EXPLAIN)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("the card's window is gone: it must never stand over the alert", lens().rootViewForTest())
        // The card went by its own means, so the presentation ends with it,
        // as when the card opens a page or is touched away.
        assertEquals(1, ended)
        val texts = alertTexts()
        assertTrue("the alert the Ask section raises: $texts", ctx.getString(R.string.ask_no_llm_title) in texts)
        // Its button in the accent and the accent's text colour, as the alert
        // resolves its own colours: the card hands it the service's context,
        // whose theme has none of the app's.
        val button = alertButton(ctx.getString(R.string.ask_no_llm_open_services)) as android.widget.Button
        val themed = overlayThemedContext(ctx)
        // A zero fill is drawn as no fill at all, a transparent colour.
        val fill = when (val bg = button.background) {
            is android.graphics.drawable.GradientDrawable -> bg.color!!.defaultColor
            is android.graphics.drawable.ColorDrawable -> bg.color
            else -> error("unexpected background $bg")
        }
        assertEquals(
            themed.themeColor(R.attr.ptAccent) to themed.themeColor(R.attr.ptAccentOn),
            fill to button.currentTextColor,
        )

        // Its button opens Translation services, as a new task: there is no
        // activity of ours under an over-game card to start it from.
        alertButton(ctx.getString(R.string.ask_no_llm_open_services)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val started = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
        assertTrue(
            "a new task",
            started!!.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    // ── the frame a card cites ───────────────────────────────────────────

    /** The frame the card's pages and Anki note read at tap time. */
    private fun cardFrame(): Pair<String?, Long?> =
        lookup().field<String?>("screenshotPath") to lookup().field<Long?>("dragCapturedAtMs")

    @Test fun `a card opened before the frame arrives cites it once it does`() {
        // The boxes go up with the read; the frame comes with the result.
        load(helloWorld)
        session.onTapAt(150f, 315f)
        assertTrue(session.hasCard)

        session.setFrame("/screenshots/capture.jpg", 1_234L)

        assertEquals("/screenshots/capture.jpg" to 1_234L, cardFrame())
    }

    @Test fun `a frame given with the lines reaches a card opened later`() {
        session.load(listOf(helloWorld), "/screenshots/capture.jpg", 1_234L)
        settle()

        session.onTapAt(150f, 315f)

        assertEquals("/screenshots/capture.jpg" to 1_234L, cardFrame())
    }

    // ── the card's end ───────────────────────────────────────────────────

    @Test fun `a card that ends by its own means ends the presentation`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        // What a card's own end reports: a page it opened, an Activity, a
        // touch outside its window.
        lookup().onSettled!!.invoke()

        assertEquals(1, ended)
    }

    @Test fun `a card the session closes ends nothing`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)

        session.closeCard()

        assertNull(selected())
        assertEquals(0, ended)
    }

    @Test fun `with no card up a card's end changes nothing`() {
        load(helloWorld)
        lookup().onSettled!!.invoke()
        assertEquals(0, ended)
    }

    @Test fun `Back with a card up closes only the card, and with none it is the host's`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)
        val lens = cardUp()

        assertTrue(session.onBack())

        assertNull("the card's word is cleared", selected())
        assertNull("and no longer lit", highlighted())
        assertFalse("the card is gone", lens.isInteractive)
        assertFalse("with none, Back is the host's", session.onBack())
        assertEquals(0, ended)
    }

    // ── the controller ───────────────────────────────────────────────────

    @Test fun `keys the cursor does not drive pass on, and a driven key's release moves nothing`() {
        load(helloWorld)
        assertFalse(session.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertFalse("Back is the host's", session.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))

        assertTrue(session.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)))

        assertNull(cursor())
    }

    @Test fun `the first controller press lights the first word, the D-pad moves the light, A opens the lit word`() {
        load(helloWorld)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the first press puts the cursor on the first word", 0, cursor())
        assertEquals("and lights it", 0, highlighted())
        assertNull("and opens nothing", selected())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, cursor())
        assertEquals(1, highlighted())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, selected())
    }

    @Test fun `with a card up the first controller press starts on its word, and the light leaves it with the cursor`() {
        load(helloWorld)
        session.onTapAt(260f, 315f)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("on the card's word, not the first word", 1, cursor())
        assertEquals(1, highlighted())

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0, highlighted())
        assertEquals("the card stays on its word", 1, selected())
    }

    @Test fun `a tap moves the controller cursor to the word it opens, and A closing that card leaves it lit`() {
        load(helloWorld)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(0, cursor())

        session.onTapAt(260f, 315f)
        assertEquals(1, selected())
        assertEquals("the cursor follows the tap", 1, cursor())
        assertEquals(1, highlighted())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertNull("A on the card's word closes it", selected())
        assertEquals("the cursor's word stays lit", 1, highlighted())
    }

    @Test fun `right on the controller stays on the row, past a nearer word on the next row, and stops at its end`() {
        // "aa" at 100..140 and "bb" far along the same line at 600..640; on
        // the next line, "cc" just below "aa" at 160..200, and "dd" past the
        // first row's end at 700..740.
        load(
            line("aa bb", 300, listOf(100, 120, 0, 600, 620)),
            line("cc dd", 340, listOf(160, 180, 0, 700, 720)),
        )
        assertEquals(4, view.shownBoxes.size)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the first press puts the cursor on the first word", 0, cursor())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("along the row to bb, not down to cc", 1, cursor())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the row ends at bb, though dd lies further right a row down", 1, cursor())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("down crosses to the next row", 3, cursor())
    }

    @Test fun `with no controller attached, A on the word whose card is up closes it, and A on another word moves the card there`() {
        load(helloWorld)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, selected())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertNull("A on its own word closes the card", selected())

        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the card moved to the cursor's word", 1, selected())
        assertEquals(0, ended)
    }

    /** A gamepad attached, as a handheld's built-in controls are. */
    private fun controllerAttached() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
    }

    /** With a controller attached, A on "hello": its card up by the real
     *  lookup. */
    private fun cardByController() {
        controllerAttached()
        Prefs(ctx).sourceLang = "en"
        load(helloWorld)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        awaitCard()
    }

    /** B on the card's own window, where the keys go while it holds them. */
    private fun pressBOnCard() {
        val window = requireNotNull(lens().rootViewForTest())
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `with a controller attached, the card A opens takes the controller, its cursor on its first row`() {
        cardByController()

        assertTrue("the card holds the controller", lens().isConsumingController)
        lens().layoutRowsForTest(ROW_CSS_PX)
        val first = lens().navRowsForTest().first()
        assertEquals("the next A opens the word", first to DefAction.OPEN, lens().cursorForTest())
    }

    @Test fun `a card a tap opens takes the controller too, its cursor waiting for the first press`() {
        controllerAttached()
        cardOnHello()

        assertTrue(lens().isConsumingController)
        lens().layoutRowsForTest(ROW_CSS_PX)
        assertNull(lens().cursorForTest())
    }

    @Test fun `B on the card closes the card alone, and the boxes have the controller again on the same word`() {
        cardByController()

        pressBOnCard()

        assertFalse("the card is gone", lens().isInteractive)
        assertNull("its word is no longer selected", selected())
        assertEquals("the presentation goes on", 0, ended)
        assertTrue("the boxes stay", session.hasBoxes)
        assertEquals("the cursor is on the word it was on", 0, cursor())
        assertEquals("and lit", 0, highlighted())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the D-pad moves it on from there", 1, cursor())
        assertFalse("with no card up, Back is the host's", session.onBack())
    }

    // ── a host whose picture moves, and a word known before the read ─────

    /** [helloWorld] moved by ([dx], [dy]): the same line, shown elsewhere. */
    private fun moved(dx: Int, dy: Int) = line("hello world", 300 + dy, (0 until 11).map { 100 + dx + it * 20 })

    @Test fun `the same lines moved on screen are boxed where they are now, at once, without tokenizing again`() {
        var tokenized = 0
        tokenizer = { text -> tokenized++; text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) } }
        load(helloWorld)
        assertEquals(1, tokenized)
        settled.clear()

        assertTrue(session.relocate(listOf(moved(dx = 40, dy = 100))))

        assertFalse("no tokenization to wait for", session.pending)
        assertEquals(1, tokenized)
        assertEquals(listOf("hello", "world"), view.shownBoxes.map { it.surface })
        assertEquals("the boxes are on the moved line", 400f - 3f * ctx.resources.displayMetrics.density, view.shownBoxes[0].rect.top)
        assertTrue("a tap where the word is now picks it", session.onTapAt(190f, 415f))
        assertEquals(0, selected())
        assertEquals("the first load's settle is the only one reported", emptyList<Boolean>(), settled)
    }

    @Test fun `moving the lines closes a card, whose word has moved from under it`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)
        assertEquals(0, selected())

        assertTrue(session.relocate(listOf(moved(dx = 0, dy = 100))))

        assertNull(selected())
        assertNull(highlighted())
        assertEquals("closed by the session, so the presentation goes on", 0, ended)
    }

    @Test fun `other lines are not moved but loaded`() {
        load(helloWorld)
        val other = line("goodbye world", 300, (0 until 13).map { 100 + it * 20 })
        assertFalse("another text is another read", session.relocate(listOf(other)))
        assertEquals(listOf("hello", "world"), view.shownBoxes.map { it.surface })

        session.load(listOf(other), null, 0L)
        settle()
        assertEquals(listOf("goodbye", "world"), view.shownBoxes.map { it.surface })
    }

    @Test fun `lines moved while their tokens are on the way keep the one tokenization, which lands where they are now`() {
        val gate = CompletableDeferred<Unit>()
        var tokenized = 0
        tokenizer = { text ->
            tokenized++
            gate.await()
            text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }
        }
        session.load(listOf(helloWorld), null, 0L)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.pending)

        // A gesture moves them frame after frame while they are tokenized.
        assertTrue(session.relocate(listOf(moved(dx = 10, dy = 30))))
        assertTrue(session.relocate(listOf(moved(dx = 20, dy = 60))))
        assertTrue(session.relocate(listOf(moved(dx = 40, dy = 100))))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("still the first tokenization", session.pending)
        assertTrue("nothing drawn before the tokens are in", view.shownBoxes.isEmpty())

        gate.complete(Unit)
        settle()

        assertEquals("tokenized once", 1, tokenized)
        assertEquals(listOf("hello", "world"), view.shownBoxes.map { it.surface })
        assertEquals("at the last place they were moved to", 400f - 3f * ctx.resources.displayMetrics.density, view.shownBoxes[0].rect.top)
        assertEquals(listOf(true), settled)
    }

    @Test fun `a word known by its text is found nearest to where it was picked, then under the point, then not at all`() {
        // "world" twice: 220..320 on the first line, 100..200 on the second.
        val second = line("world hello", 400, (0 until 11).map { 100 + it * 20 })
        load(helloWorld, second)
        val radius = 96f * ctx.resources.displayMetrics.density

        assertEquals("the one on the first line", 1, session.boxFor("world", 270f, 320f, radius))
        assertEquals("the one on the second line", 2, session.boxFor("world", 150f, 410f, radius))
        assertEquals(
            "a text this read does not have falls back to the word under the point",
            0, session.boxFor("absent", 150f, 315f, radius),
        )
        assertNull("no such text and no word there", session.boxFor("absent", 900f, 900f, radius))
        assertNull("the text exists, but too far from the point to be the same word", session.boxFor("hello", 900f, 900f, 10f))
    }

    @Test fun `a word sought by its text is measured against a slanted box as it is drawn, not the upright rectangle around it`() {
        // "w" on a line turned 20 degrees about (250, 120), 300 long and 40
        // tall in its own frame: its left end rises, its right end drops,
        // and the upright rectangle around it reaches down to y = 190.
        val slanted = OcrManager.OcrLine(
            text = "w", bounds = Rect(100, 50, 400, 190),
            angleDeg = 20f, orientedWidth = 300f, orientedHeight = 40f,
        )
        // The same word upright, under that rectangle's empty bottom-left corner.
        val upright = line("w", 200, listOf(100))
        load(slanted, upright)
        val radius = 96f * ctx.resources.displayMetrics.density

        // (140, 185) is inside the slanted word's rectangle but some 75 px
        // from the word itself, and 15 px above the upright one.
        assertEquals(1, session.boxFor("w", 140f, 185f, radius))
        assertEquals("on the slanted word's own raised end", 0, session.boxFor("w", 140f, 80f, radius))
    }

    @Test fun `opening a found word is a tap on it`() {
        load(helloWorld)
        session.open(1)
        assertEquals(1, selected())
        assertEquals(1, highlighted())
    }

    @Test fun `a host that keeps the boxes up when a card ends drops only the selection`() {
        load(helloWorld)
        session.onTapAt(150f, 315f)
        session.deselect()
        assertNull(selected())
        assertNull(highlighted())
        assertTrue("the boxes stay", session.hasBoxes)
        assertTrue("and take the next tap", session.onTapAt(270f, 315f))
        assertEquals(1, selected())
    }

    @Test fun `a session hosted in an activity builds its card from the activity's own windows`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val inApp = WordLookupSession(
            kit = LookupLensKit.inActivity(activity),
            density = ctx.resources.displayMetrics.density,
            view = WordBoxesView(overlayThemedContext(ctx)),
            scope = scope,
            tokenize = { tokenizer(it) },
            onEnded = {},
            onBoxesSettled = {},
        )
        try {
            inApp.load(listOf(helloWorld), null, 0L)
            val deadline = System.currentTimeMillis() + 3000
            while (inApp.pending && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(inApp.onTapAt(150f, 315f))
            assertTrue(inApp.hasCard)
            assertFalse(
                "no overlay window is involved",
                host.hasContentWindowOn(Display.DEFAULT_DISPLAY),
            )
        } finally {
            inApp.release()
        }
    }

    @Test fun `a host's shoulder hook reaches its card, and without one L1 and R1 on the card go unhandled`() {
        load(helloWorld)
        val window = requireNotNull(cardUp().rootViewForTest())
        assertFalse(
            "no hook, as the two-screen window builds it",
            window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)),
        )
        assertFalse(window.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)))

        val steps = mutableListOf<Boolean>()
        val hooked = WordLookupSession(
            kit = LookupLensKit.overGame(
                context = ctx,
                displayCtx = ctx,
                wm = wm,
                overlayHost = host,
                displayId = Display.DEFAULT_DISPLAY,
            ),
            density = ctx.resources.displayMetrics.density,
            view = WordBoxesView(overlayThemedContext(ctx)),
            scope = scope,
            tokenize = { tokenizer(it) },
            onEnded = {},
            onBoxesSettled = {},
            onShoulderKey = { steps += it },
        )
        try {
            val card: MagnifierLens = hooked.field("magnifier")
            val screen = ctx.displaySizePx()
            assertTrue(card.show(150, 300, screen.x, screen.y, anchorHeight = 30))
            assertTrue(card.makeInteractive())
            val hookedWindow = requireNotNull(card.rootViewForTest())
            assertTrue(hookedWindow.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1)))
            assertTrue(hookedWindow.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1)))
            assertEquals("L1: the left", listOf(false), steps)
        } finally {
            hooked.release()
        }
    }

    private companion object {
        /** A laid-out row's height in the page's CSS px
         *  ([MagnifierLens.layoutRowsForTest]). */
        const val ROW_CSS_PX = 300
    }
}
