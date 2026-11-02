package com.playtranslate

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.input.InputManager
import android.view.InputDevice
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.DragLookupController
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.TapLookupOverlay
import com.playtranslate.ui.TapLookupSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * "Enable tap-to-lookup" through a real controller, a real window and a
 * stand-in scene reader ([TapLookupSession.Reader]): one frame, one line
 * ("hello world"), two words. The hotkey's toggle opens and closes it, a
 * press while the capture is out included, and a capture that throws ends
 * it; every tap is judged in one
 * place (a word fills and keeps the boxes, the X and anything else close
 * them); a card that ends by its own means ends the session, Back with no
 * card ends it, and the controller rings, moves and selects as on the
 * capture sheet.
 *
 * What the boxes paused is the session's [LookupPause]: closed by the user
 * it restarts auto-translate, closed by a capture or the error pill's gear
 * it does not, a drag (a hold sliding into one) or a new session takes it
 * over, a live control (a toggle hotkey, the icon's toggle and swap) acts
 * on the session it paused, and the game-language change's picker closes
 * them with the restart. With the app in front on a second screen the stop and the start
 * are intents to MainActivity, which is what this JVM can observe of them.
 */
@RunWith(RobolectricTestRunner::class)
class TapLookupSessionTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    /** One line, "hello world", 20 px a character from x = 100 at y 300..330:
     *  "hello" 100..200, "world" 220..320. */
    private val line = OcrManager.OcrLine(
        text = "hello world",
        bounds = Rect(100, 300, 320, 330),
        symbols = "hello world".mapIndexedNotNull { i, ch ->
            if (ch == ' ') null
            else OcrManager.SymbolBox(ch.toString(), Rect(100 + i * 20, 300, 120 + i * 20, 330), i)
        },
    )
    private val reader = FakeReader()
    private var service: ServiceController<CaptureService>? = null


    private inner class FakeReader : TapLookupSession.Reader {
        /** When set, the capture waits for it, and comes back with it even
         *  after the session closed, as a native capture does. */
        var frame: CompletableDeferred<Bitmap?>? = null
        /** When set, the read waits for it, the window up with its spinner. */
        var read: CompletableDeferred<Unit>? = null
        var lines: List<OcrManager.OcrLine> = listOf(line)
        val frames = mutableListOf<Bitmap>()

        override suspend fun capture(displayId: Int): Bitmap? {
            val pending = frame ?: return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { frames += it }
            return withContext(NonCancellable) { pending.await() }
        }

        override suspend fun readLines(bitmap: Bitmap, displayId: Int): List<OcrManager.OcrLine> {
            read?.await()
            return lines
        }

        override suspend fun tokenize(text: String): List<TokenSpan> =
            text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }

        override suspend fun saveScreenshot(bitmap: Bitmap): String? = null
    }

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        override fun start() {}
        override fun stop() {}
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        controller.tapLookupReader = reader
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    // ── harness ──────────────────────────────────────────────────────────

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun session(): TapLookupSession? = controller.field("tapLookup")

    private fun overlay(): TapLookupOverlay? = session()?.field("overlay")

    private fun boxCount(): Int = overlay()!!.field<List<*>>("boxes").size

    private fun selected(): Int? = overlay()!!.field("selected")

    private fun cursor(): Int? = session()!!.field("cursor")

    private fun open() {
        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        settle()
    }

    private fun down(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        overlay()!!.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun press(code: Int) {
        val view = overlay()!!
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** A created CaptureService on the default display, auto-translate off. */
    private fun captureService(): CaptureService {
        service?.let { return it.get() }
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        return built.get().also { it.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY) }
    }

    /** Auto-translate running on the default display. */
    private fun running() {
        val svc = captureService()
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, StandInLiveMode(OverlayFlavor.FURIGANA))
        assertTrue(svc.isLive)
    }

    /** The app in front on a second screen: starts and stops go through it. */
    private fun appInFront() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
    }

    private fun startedActions(): List<String?> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.map { it.action }.toList().asReversed()
    }

    /** Install the default display's icon and return its handle. */
    private fun iconHandle(): Any {
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        return controller.field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]!!
    }

    private fun Any.icon(): FloatingOverlayIcon = field("icon")

    private fun Any.dragController(): DragLookupController = field("dragController")

    // ── toggle ───────────────────────────────────────────────────────────

    @Test fun `the hotkey's toggle opens the boxes over every word, and a second press closes them`() {
        open()
        assertNotNull(session())
        assertEquals(2, boxCount())

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)

        assertNull(session())
    }

    @Test fun `a second press while the capture is out closes the session, and the late frame is dropped`() {
        val pending = CompletableDeferred<Bitmap?>()
        reader.frame = pending
        open()
        val first = session()
        assertNotNull(first)
        assertNull("no window before the frame", overlay())

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        assertNull(session())

        val late = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        pending.complete(late)
        settle()

        assertNull("no window for a closed session", first!!.field<TapLookupOverlay?>("overlay"))
        assertTrue("the late frame is recycled", late.isRecycled)
        assertNull(session())
    }

    @Test fun `a capture that throws ends the session and restarts the auto-translate it paused`() {
        appInFront()
        running()
        val pending = CompletableDeferred<Bitmap?>()
        reader.frame = pending
        open()
        assertNotNull(session())
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())

        pending.completeExceptionally(IllegalStateException("the capture backend threw"))
        settle()

        assertNull(session())
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    @Test fun `a read with no word ends the session with the whole screen's no-text pill`() {
        // An active region of its own on the display: the pill must name
        // what the session read, the whole screen, not that region.
        captureService().configureOverride(Display.DEFAULT_DISPLAY, RegionEntry("Dialogue box", 0.5f, 1f))
        reader.lines = emptyList()

        open()

        assertNull(session())
        val pill = controller.field<View?>("pillView")
        assertNotNull("the no-text pill", pill)
        val texts = pill!!.javaClass.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(pill) as String? }
        assertTrue(texts.toString(), texts.any { "Full screen" in it })
        assertFalse(texts.toString(), texts.any { "Dialogue box" in it })
    }

    @Test fun `the frame is recycled once the boxes are up`() {
        open()
        assertTrue(reader.frames.single().isRecycled)
    }

    @Test fun `once the boxes are up the X takes the spinner's place`() {
        open()
        assertEquals(View.GONE, overlay()!!.field<View>("spinner").visibility)
        val button = overlay()!!.field<View>("closeButton")
        assertFalse("the X is drawn", button.field<Boolean>("loading"))
    }

    // ── taps ─────────────────────────────────────────────────────────────

    @Test fun `a tap on a word fills its box and keeps the boxes up`() {
        open()

        down(150f, 315f)

        assertNotNull(session())
        assertEquals(0, selected())
    }

    @Test fun `a tap on no word closes the boxes and restarts the auto-translate they paused`() {
        appInFront()
        running()
        open()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())

        down(600f, 600f)
        settle()

        assertNull(session())
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    private fun dock(): TapLookupOverlay.Dock = overlay()!!.field("dock")

    /** Where the X is drawn: its disc is the icon's, 56dp, centred on the
     *  edge, and the X sits 2dp nearer the edge than the middle of the
     *  disc's visible half. */
    private fun xPoint(): Pair<Float, Float> {
        val d = dock()
        val density = ctx.resources.displayMetrics.density
        val inset = 28 * density / 2 - 2 * density
        return (if (d.edgeLeft) d.centreX + inset else d.centreX - inset) to d.centreY
    }

    private fun lens(): com.playtranslate.ui.MagnifierLens = session()!!.field("magnifier")

    @Test fun `the X sits over the floating icon, its centre on the edge where the icon rests`() {
        val icon = iconHandle().icon()
        open()

        val d = dock()
        val p = icon.params!!
        assertEquals(p.x + icon.viewSizePx / 2f, d.centreX)
        assertEquals(p.y + icon.viewSizePx / 2f, d.centreY)
        assertFalse("the icon docks right by default", d.edgeLeft)
        assertEquals("the right edge", ctx.resources.displayMetrics.widthPixels.toFloat(), d.centreX)
    }

    @Test fun `the X follows a left-docked icon`() {
        prefs.setIconPositionForDisplay(Display.DEFAULT_DISPLAY, IconPosition(edge = 0, fraction = 0.2f))
        val icon = iconHandle().icon()
        open()

        val d = dock()
        assertTrue(d.edgeLeft)
        assertEquals(0f, d.centreX)
        assertEquals(icon.params!!.y + icon.viewSizePx / 2f, d.centreY)
        // The X (and the spinner in its place) mirrors onto the disc's right half.
        val spinner = overlay()!!.field<View>("spinner")
        val (x, _) = xPoint()
        assertTrue("on the screen side of the edge", x > 0f)
        assertEquals(x, spinner.translationX + spinner.layoutParams.width / 2f, 0.5f)
    }

    @Test fun `the X closes the boxes`() {
        open()
        val (x, y) = xPoint()
        assertTrue(overlay()!!.closeHit(x, y))

        down(x, y)

        assertNull(session())
    }

    @Test fun `with a card up, the X closes the boxes`() {
        open()
        down(150f, 315f)
        val (x, y) = xPoint()

        // The card's full-screen window takes the tap and hands it here.
        lens().onOffCardDown!!.invoke(x, y)

        assertNull(session())
    }

    @Test fun `with a card up, a tap off it on no word closes only the card`() {
        open()
        down(150f, 315f)

        lens().onOffCardDown!!.invoke(600f, 600f)

        assertNotNull("the boxes stay", session())
        assertNull("the word is cleared", selected())
    }

    @Test fun `with a card up, a tap on its own word closes the card and the boxes stay`() {
        open()
        down(150f, 315f)

        down(150f, 315f)

        assertNotNull(session())
        assertNull(selected())
    }

    @Test fun `with a card up, a tap on another word moves the card to it`() {
        open()
        down(150f, 315f)

        down(260f, 315f)

        assertNotNull(session())
        assertEquals(1, selected())
    }

    @Test fun `with a card up, the card's off-card hook moves it to the word tapped`() {
        open()
        down(150f, 315f)

        lens().onOffCardDown!!.invoke(260f, 315f)

        assertEquals(1, selected())
    }

    @Test fun `the X is there from the window's first frame, while the read runs, and closes it`() {
        val pending = CompletableDeferred<Unit>()
        reader.read = pending
        open()
        assertNotNull("the window is up with its spinner", overlay())
        assertEquals("no boxes yet", 0, boxCount())
        assertEquals("the X's disc shows", View.VISIBLE, overlay()!!.field<View>("closeButton").visibility)
        // The spinner stands in the X's place, twice its 9dp span, while the
        // read runs.
        val spinner = overlay()!!.field<View>("spinner")
        assertEquals(View.VISIBLE, spinner.visibility)
        val density = ctx.resources.displayMetrics.density
        assertEquals(18 * density, spinner.layoutParams.width.toFloat(), 0.5f)
        val (x, y) = xPoint()
        assertEquals(x, spinner.translationX + spinner.layoutParams.width / 2f, 0.5f)
        assertEquals(y, spinner.translationY + spinner.layoutParams.height / 2f, 0.5f)
        assertTrue(overlay()!!.closeHit(x, y))

        down(x, y)

        assertNull(session())
        pending.complete(Unit)
    }

    @Test fun `a tap a little outside a word picks it, and one farther off ends the session`() {
        open()
        val density = ctx.resources.displayMetrics.density
        // "hello"'s box ends at y = 333; 20dp below it is inside the buffer.
        down(150f, 333f + 20 * density)
        assertEquals(0, selected())

        down(150f, 333f + 20 * density)
        assertNull("that closed the card", selected())
        assertNotNull(session())

        down(150f, 333f + 30 * density)
        assertNull("past the buffer, with no card up", session())
    }

    @Test fun `a card that ends by its own means ends the session`() {
        open()
        down(150f, 315f)
        val lookup = session()!!.field<DragLookupController>("lookup")

        // What a card's own end reports: a page it opened, an Activity, a
        // touch outside its window.
        lookup.onSettled!!.invoke()

        assertNull(session())
    }

    @Test fun `Back with a card up closes only the card, and the next Back closes the boxes`() {
        open()
        down(150f, 315f)
        // The card, up and sticky on the word, as the lookup leaves it (the
        // lookup itself is not run here).
        val lens = session()!!.field<com.playtranslate.ui.MagnifierLens>("magnifier")
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(150, 300, screen.x, screen.y, anchorHeight = 30))
        assertTrue(lens.makeInteractive())

        press(KeyEvent.KEYCODE_BACK)

        assertNotNull("the boxes stay", session())
        assertNull("the box is no longer filled", selected())
        assertFalse("the card is gone", lens.isInteractive)

        press(KeyEvent.KEYCODE_BACK)
        assertNull(session())
    }

    @Test fun `a page the card opens cancels the restart, as after a drag lookup`() {
        running()
        open()
        val svc = captureService()
        assertFalse("stopped for the boxes", svc.isLive)
        down(150f, 315f)

        // What the card's actions do before they open the detail page or
        // Anki: the restart is cancelled, then the card closes.
        controller.cancelLivePauseObligation()
        session()!!.field<DragLookupController>("lookup").onSettled!!.invoke()
        settle()

        assertNull(session())
        assertFalse(svc.isLive || svc.isLiveStartPending)
    }

    @Test fun `with no card up a card's end changes nothing`() {
        open()
        val lookup = session()!!.field<DragLookupController>("lookup")

        lookup.onSettled!!.invoke()

        assertNotNull(session())
    }

    // ── keys ─────────────────────────────────────────────────────────────

    @Test fun `Back with no card up closes the boxes`() {
        open()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertNull(session())
    }

    @Test fun `a Back release whose press this window never saw does nothing`() {
        open()
        overlay()!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertNotNull(session())
    }

    @Test fun `the first controller press rings the first word, the D-pad moves the ring, A opens the ringed word`() {
        open()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the first press rings the first word", 0, cursor())
        assertNull("and opens nothing", selected())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(1, cursor())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, selected())
        assertNotNull(session())
    }

    @Test fun `right on the controller stays on the row, past a nearer word on the next row, and stops at its end`() {
        // "aa" at 100..140 and "bb" far along the same line at 600..640; on
        // the next line, "cc" just below "aa" at 160..200, and "dd" past the
        // first row's end at 700..740.
        fun line(text: String, top: Int, lefts: List<Int>) = OcrManager.OcrLine(
            text = text,
            bounds = Rect(lefts.first(), top, lefts.last() + 20, top + 30),
            symbols = text.mapIndexedNotNull { i, ch ->
                if (ch == ' ') null
                else OcrManager.SymbolBox(ch.toString(), Rect(lefts[i], top, lefts[i] + 20, top + 30), i)
            },
        )
        reader.lines = listOf(
            line("aa bb", 300, listOf(100, 120, 0, 600, 620)),
            line("cc dd", 340, listOf(160, 180, 0, 700, 720)),
        )
        open()
        assertEquals(4, boxCount())

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the first press rings the first word", 0, cursor())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("along the row to bb, not down to cc", 1, cursor())
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("the row ends at bb, though dd lies further right a row down", 1, cursor())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("down crosses to the next row", 3, cursor())
    }

    @Test fun `A on the word whose card is up closes it, and A on another word moves the card there`() {
        open()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, selected())

        press(KeyEvent.KEYCODE_BUTTON_A)
        assertNull("A on its own word closes the card", selected())
        assertNotNull(session())

        press(KeyEvent.KEYCODE_BUTTON_A)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("the card moved to the ringed word", 1, selected())
    }

    @Test fun `with a controller attached the card leaves the controller to the boxes`() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
        open()
        down(150f, 315f)
        // The card, up and sticky on the word, as the lookup leaves it.
        val lens = lens()
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(150, 300, screen.x, screen.y, anchorHeight = 30))
        assertTrue(lens.makeInteractive())

        assertFalse("the card does not take the controller", lens.isConsumingController)
        // So A reaches the boxes: the first press rings the word, the next
        // closes its card.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertNull(selected())
        assertNotNull(session())
    }

    // ── what the boxes paused ────────────────────────────────────────────

    @Test fun `a capture closes the boxes and leaves auto-translate stopped`() {
        appInFront()
        running()
        open()
        startedActions()

        controller.toggleCaptureScreenForDisplay(Display.DEFAULT_DISPLAY)

        assertNull(session())
        // The capture's own stop of a session it still finds running (the
        // routed stop never lands here) may come first; no start does.
        val actions = startedActions()
        assertEquals(MainActivity.ACTION_REGION_CAPTURE, actions.last())
        assertFalse(actions.toString(), MainActivity.ACTION_START_LIVE in actions)
    }

    @Test fun `another hotkey closes the boxes first and restarts what they paused, the capture's does not`() {
        appInFront()
        running()
        open()
        startedActions()

        controller.closeTapToLookupForHotkey(HotkeyAssignment.CAPTURE_TAP)
        assertNotNull("the capture's own path closes them", session())
        controller.closeTapToLookupForHotkey(HotkeyAssignment.TAP_TO_LOOKUP_TAP)
        assertNotNull("its own hotkey toggles them", session())
        controller.closeTapToLookupForHotkey(HotkeyAssignment.TRANSLATION_TAP)
        controller.closeTapToLookupForHotkey(HotkeyAssignment.FURIGANA_TAP)
        assertNotNull("the live toggles close them themselves", session())
        controller.closeTapToLookupForHotkey(HotkeyAssignment.CHANGE_GAME_LANGUAGE_TAP)
        assertNotNull("the game-language change closes them itself", session())

        controller.closeTapToLookupForHotkey(HotkeyAssignment.TRANSLATION_HOLD)

        assertNull(session())
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    @Test fun `the live toggle of the mode the boxes paused closes them and leaves auto-translate off`() {
        appInFront()
        running()
        open()
        startedActions()
        val mode = prefs.overlayMode
        val tap = HotkeyAssignment.entries.first { it.mode == mode && it.trigger == HotkeyTrigger.TAP }

        // The hotkey's path: the close ahead of it, then the toggle.
        controller.closeTapToLookupForHotkey(tap)
        controller.toggleAutoMode(mode)

        assertNull(session())
        assertEquals(mode, prefs.overlayMode)
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `the other mode's live toggle closes the boxes and restarts auto-translate in its mode`() {
        appInFront()
        running()
        open()
        startedActions()
        val other = OverlayMode.entries.first { it != prefs.overlayMode }

        controller.toggleAutoMode(other)

        assertNull(session())
        assertEquals(other, prefs.overlayMode)
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    /** The icon's live gestures, which the quick menu's Auto button shares:
     *  reachable with the boxes up from another display's icon. */
    private fun liveGesture(name: String): Any? =
        OverlayUiController::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(controller)

    @Test fun `the icon's toggle turns off the session the boxes paused, and they close`() {
        appInFront()
        running()
        open()
        startedActions()

        val stopped = liveGesture("toggleLiveFromOverlay") as Boolean

        assertTrue("it reports auto-translate stopped", stopped)
        assertNull(session())
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `the icon's swap restarts the session the boxes paused in the other mode`() {
        appInFront()
        running()
        open()
        startedActions()
        val other = OverlayMode.entries.first { it != prefs.overlayMode }

        liveGesture("swapOverlayModeOrStartLive")

        assertNull(session())
        assertEquals(other, prefs.overlayMode)
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    @Test fun `the game-language change's picker closes the boxes and restarts what they paused`() {
        // No language downloaded: the change opens the picker.
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        appInFront()
        running()
        open()
        startedActions()

        controller.changeGameLanguageForDisplay(Display.DEFAULT_DISPLAY)

        assertNull(session())
        val actions = startedActions()
        assertEquals(actions.toString(), MainActivity.ACTION_START_LIVE, actions.first())
    }

    @Test fun `the error pill's gear closes the boxes without a restart`() {
        appInFront()
        running()
        open()
        startedActions()

        OverlayUiController::class.java
            .getDeclaredMethod("openServiceSettingsFromPill", String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(controller, "deepl", Display.DEFAULT_DISPLAY)

        assertNull(session())
        assertFalse(MainActivity.ACTION_START_LIVE in startedActions())
    }

    @Test fun `a teardown that ends capture forgets what the boxes paused`() {
        appInFront()
        running()
        open()
        startedActions()

        controller.hideFloatingIcon("hideAll", endsCapture = true)
        settle()

        assertNull(session())
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test fun `a hold that slides into a drag hands what the boxes paused to the drag`() {
        appInFront()
        running()
        prefs.iconHoldAction = HoldAction.TAP_TO_LOOKUP
        val handle = iconHandle()
        val icon = handle.icon()

        icon.onHoldStart!!.invoke()
        settle()
        assertNotNull(session())
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())

        icon.onHoldCancel!!.invoke()
        assertNull(session())
        assertEquals("nothing restarts in between", emptyList<String?>(), startedActions())

        // MainActivity never runs here, so the stop it was sent for has not
        // landed when the drag starts; the drag's own pause adds nothing.
        icon.onDragStart!!.invoke()
        startedActions()
        handle.dragController().onSettled!!.invoke()

        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    @Test fun `the boxes take over what an open drag lens paused, so its close restarts nothing`() {
        appInFront()
        running()
        val handle = iconHandle()
        handle.icon().onDragStart!!.invoke()
        assertEquals(listOf<String?>(MainActivity.ACTION_STOP_LIVE), startedActions())

        open()
        // The session's own pause may stop again a session it still finds
        // running (the routed stop never lands here); no start comes.
        val actions = startedActions()
        assertFalse("the lens's close restarts nothing: $actions", MainActivity.ACTION_START_LIVE in actions)

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        assertEquals(listOf<String?>(MainActivity.ACTION_START_LIVE), startedActions())
    }

    @Test fun `the icon's Tap opens the boxes`() {
        prefs.iconTapAction = TapAction.TAP_TO_LOOKUP
        iconHandle().icon().onTap!!.invoke()
        settle()
        assertNotNull(session())
        assertEquals(2, boxCount())
    }
}
