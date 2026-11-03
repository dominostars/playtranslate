package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.MainActivity
import com.playtranslate.OcrManager
import com.playtranslate.Prefs
import com.playtranslate.displaySizePx
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlayThemedContext
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
 * the controller lights, moves and selects, the card never taking it. A card
 * cites the capture's frame whether it came with the lines or after the card
 * opened.
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
            context = ctx,
            displayCtx = ctx,
            wm = wm,
            overlayHost = host,
            displayId = Display.DEFAULT_DISPLAY,
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

    @Test fun `A on the word whose card is up closes it, and A on another word moves the card there`() {
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

    @Test fun `with a controller attached the card leaves the controller to the boxes`() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
        load(helloWorld)
        session.onTapAt(150f, 315f)
        val lens = cardUp()

        assertFalse("the card does not take the controller", lens.isConsumingController)
        // So A reaches the boxes: the first press puts the cursor on the
        // card's word, the next closes its card.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertNull(selected())
    }
}
