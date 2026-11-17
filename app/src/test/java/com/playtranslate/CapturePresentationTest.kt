package com.playtranslate

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.TokenSpan
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.CaptureOverlayWindow
import com.playtranslate.ui.TextBox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The capture's presentation on the game display when the app's results
 * page is the panel ([OverlayUiController.presentCapture] and its window,
 * [CaptureOverlayWindow]): the capture's mode, a remembered None reading as
 * auto-translate's (Show on screen keeps two screens' overlay off, not
 * None), nothing for a capture that asked for None; a fill or a reading
 * build that leaves nothing takes it down; a running auto-translate stops
 * first; the dismissed-notification fires once per window. The window
 * carries the one-screen sheet's mode pill: it offers the capture's modes
 * with the window's own marked, a real tap on a segment is the pill's (a
 * word under it included) and re-flavors the window in place, writing the
 * pref, None takes it down and turns Show on screen off, the remembered
 * mode untouched, the X collapses it and the next window opens collapsed;
 * any other tap on
 * the game screen hands it back, and a window already replaced switches
 * and dismisses nothing. In Definitions (the pill's pick, or tap-to-lookup's
 * override of one capture, which writes no setting) the window holds key
 * focus, a tap on a word opens its card and Back closes a card before the
 * window; tap-to-lookup over a window in another mode switches it in place,
 * the pill marking Definitions, and over one in Definitions takes it down.
 * A change of the game language takes the window down, and a capture read
 * in a language other than the game's is never put up. A change of the game
 * display's size or rotation takes the window down; an event that keeps
 * both leaves it; and a capture whose frame was taken in another layout
 * than the display's now never goes up, as it lands or from the page's Show
 * on screen, while one that recorded no layout goes up as before. A switch
 * of the window's mode in place, the pill's or tap-to-lookup's, tells the
 * page; a tap-to-lookup hold that slides into a drag takes the window down.
 */
@RunWith(RobolectricTestRunner::class)
class CapturePresentationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )
    private var service: ServiceController<CaptureService>? = null
    private var dismissals = 0
    private var modeChanges = 0

    private class StandInLiveMode(override val flavor: OverlayFlavor) : LiveMode {
        var stops = 0
        override fun start() {}
        override fun stop() { stops++ }
        override fun refresh() {}
        override fun getCachedState(): CachedOverlayState? = null
    }

    private val rect = Rect(100, 100, 300, 140)
    private val group = OcrManager.OcrGroup(
        "hello", rect, lines = listOf(OcrManager.LineBox("hello", rect, groupIndex = 0)),
    )
    private val readingBox = TextBox("へろ", Rect(100, 90, 300, 100), isFurigana = true)

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        controller.hideAll()
        com.playtranslate.capture.CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        service?.destroy()
        service = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun data(vararg texts: String) = OneShotOverlayData(
        boxes = texts.map { TextBox(it, rect) },
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(rect),
        groups = listOf(group),
    )

    /** "hello world" with a box per character, for Definitions: 20 px a
     *  character from x = 100 at y 100..130 of the frame. */
    private val wordRect = Rect(100, 100, 320, 130)
    private val wordGroup = OcrManager.OcrGroup(
        "hello world", wordRect,
        lines = listOf(
            OcrManager.LineBox(
                "hello world", wordRect, groupIndex = 0,
                symbols = "hello world".mapIndexedNotNull { i, ch ->
                    if (ch == ' ') null
                    else OcrManager.SymbolBox(ch.toString(), Rect(100 + i * 20, 100, 120 + i * 20, 130), i)
                },
            ),
        ),
    )

    private fun wordData() = OneShotOverlayData(
        boxes = listOf(TextBox("hi", wordRect)),
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(wordRect),
        groups = listOf(wordGroup),
    )

    /** A stand-in for the language's tokenizer: one token per word. */
    private val words: suspend (String, SourceLangId) -> List<TokenSpan> = { text, _ ->
        text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }
    }

    /** The capture window holds key focus. */
    private fun windowFocusable(): Boolean {
        val params = OverlayUiController::class.java.getDeclaredField("captureWindowParams")
            .apply { isAccessible = true }.get(controller) as WindowManager.LayoutParams
        return (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
    }

    /** A window up in Definitions over [data], its words boxed. */
    private fun definitionsWindow(data: OneShotOverlayData = wordData()): CaptureOverlayWindow {
        assertTrue(present(OverlayMode.TRANSLATION, data))
        val w = window()!!
        w.layer.tokenizeLine = words
        pick(OverlayMode.DEFINITIONS)
        settle()
        assertTrue(w.layer.hasBoxes)
        return w
    }

    /** The showing window's pill picks [mode], as a tap on its segment does. */
    private fun pick(mode: OverlayMode) {
        window()!!.pill.onSelect!!.invoke(mode)
        idle()
    }

    private fun descendants(v: android.view.View): List<android.view.View> =
        if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) }
        else listOf(v)

    /** The pill's segments, in order. */
    private fun segments(w: CaptureOverlayWindow): List<android.widget.TextView> =
        descendants(w.pill).filterIsInstance<android.widget.TextView>().filter { it.isClickable }

    private fun segment(w: CaptureOverlayWindow, label: String) = segments(w).single { it.text.toString() == label }

    private fun closeButton(w: CaptureOverlayWindow): android.widget.ImageView =
        descendants(w.pill).filterIsInstance<android.widget.ImageView>()
            .single { it.contentDescription == ctx.getString(R.string.capture_overlay_pill_collapse_cd) }

    /** What the pill's disc says is selected, as its content description
     *  names the mode the window paints in. */
    private fun pillSays(w: CaptureOverlayWindow): String =
        descendants(w.pill).filterIsInstance<android.widget.ImageView>()
            .single { it !== closeButton(w) }.contentDescription.toString()

    private fun pillSaying(label: String) = ctx.getString(R.string.capture_overlay_pill_collapsed_cd, label)

    /** [w] as its window lays it out over the display, the pill placed. */
    private fun laidOut(w: CaptureOverlayWindow): CaptureOverlayWindow {
        idle()
        assertTrue("the window is laid out", w.width > 0 && w.height > 0 && w.pill.width > 0)
        return w
    }

    /** [r], in [w]'s coordinates, as a rectangle of the 1280x720 frame the
     *  test data is read from: the window maps the frame onto its size. */
    private fun frameRectOf(w: CaptureOverlayWindow, r: Rect) = Rect(
        r.left * 1280 / w.width, r.top * 720 / w.height,
        r.right * 1280 / w.width, r.bottom * 720 / w.height,
    )

    /** [v]'s bounds in [w]'s coordinates. */
    private fun rectIn(w: CaptureOverlayWindow, v: android.view.View): Rect {
        var x = 0
        var y = 0
        var cur = v
        while (cur !== w) {
            x += cur.left
            y += cur.top
            cur = cur.parent as android.view.View
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    /** A real tap at the middle of [v], dispatched to the window. */
    private fun tapOn(w: CaptureOverlayWindow, v: android.view.View) {
        val r = rectIn(w, v)
        tapWindow(w, r.exactCenterX(), r.exactCenterY())
    }

    /** One word, "hello", a box per character across [r] of the frame. */
    private fun wordDataAt(r: Rect): OneShotOverlayData {
        val step = r.width() / 5
        val group = OcrManager.OcrGroup(
            "hello", r,
            lines = listOf(
                OcrManager.LineBox(
                    "hello", r, groupIndex = 0,
                    symbols = "hello".mapIndexed { i, ch ->
                        OcrManager.SymbolBox(ch.toString(), Rect(r.left + i * step, r.top, r.left + (i + 1) * step, r.bottom), i)
                    },
                ),
            ),
        )
        return OneShotOverlayData(
            boxes = listOf(TextBox("hi", r)),
            cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
            cropWidth = 1280, cropHeight = 720,
            text = listOf(r),
            groups = listOf(group),
        )
    }

    private fun tapWindow(w: CaptureOverlayWindow, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            w.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idle()
    }

    private fun present(mode: OverlayMode, data: OneShotOverlayData = data("hi")): Boolean {
        prefs.captureOverlayMode = mode
        val shown = controller.presentCapture(
            Display.DEFAULT_DISPLAY, data, SourceLangId.JA, onModeChanged = { modeChanges++ },
        ) { dismissals++ }
        idle()
        return shown
    }

    private fun window(): CaptureOverlayWindow? =
        OverlayUiController::class.java.getDeclaredField("captureWindow")
            .apply { isAccessible = true }.get(controller) as CaptureOverlayWindow?

    private fun settle() {
        val deadline = System.currentTimeMillis() + 3000
        while (window()?.layer?.pending == true && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    /** A created CaptureService with auto-translate running in [flavor]. */
    private fun running(flavor: OverlayFlavor): StandInLiveMode {
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        val svc = built.get()
        svc.gameDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val mode = StandInLiveMode(flavor)
        val modes = CaptureService::class.java.getDeclaredField("liveModes")
            .apply { isAccessible = true }.get(svc)
        MutableMap::class.java.getMethod("put", Any::class.java, Any::class.java)
            .invoke(modes, Display.DEFAULT_DISPLAY, mode)
        assertTrue(svc.isLive)
        return mode
    }

    @Test fun `a remembered None goes up in auto-translate's mode`() {
        prefs.sourceLang = "ja"
        assertTrue(present(OverlayMode.NONE))
        assertEquals(OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertEquals("the remembered None is one screen's, left as it is", OverlayMode.NONE, prefs.captureOverlayMode)

        // Auto-translate in the reading hint: the window binds in it at
        // once (its readings build off the main thread after).
        prefs.overlayMode = OverlayMode.FURIGANA
        assertTrue(controller.presentCapture(Display.DEFAULT_DISPLAY, data("hi"), SourceLangId.JA) { dismissals++ })
        assertEquals(OverlayMode.FURIGANA, controller.capturePresentationMode)
    }

    @Test fun `a capture that asked for None puts nothing up and leaves a running auto-translate alone`() {
        val live = running(OverlayFlavor.TRANSLATION)
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        assertFalse(
            controller.presentCapture(
                Display.DEFAULT_DISPLAY, data("hi"), SourceLangId.JA, modeOverride = OverlayMode.NONE,
            ) { dismissals++ },
        )
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertNull(controller.capturePresentationMode)
        assertEquals("nothing took the game screen, so nothing stopped", 0, live.stops)
        assertTrue(service!!.get().isLive)
    }

    @Test fun `translations present the chips in the capture's mode`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(controller.capturePresentationShowing)
        assertEquals(OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertTrue(window()!!.layer.hasBoxes)
    }

    @Test fun `data with no chips presents nothing in translations`() {
        assertFalse(present(OverlayMode.TRANSLATION, fillOneShotOverlayData(data(""), listOf(" "))!!))
        assertFalse(controller.capturePresentationShowing)
    }

    @Test fun `a fill that leaves nothing takes the window down once`() {
        assertTrue(present(OverlayMode.TRANSLATION, data("")))
        controller.updateCapture(fillOneShotOverlayData(data(""), listOf(" "))!!)
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `none on the pill takes the window down and turns Show on screen off, the remembered mode untouched`() {
        prefs.dualScreenShowOnScreen = true
        assertTrue(present(OverlayMode.FURIGANA))
        pick(OverlayMode.NONE)
        assertFalse(controller.capturePresentationShowing)
        assertFalse("later captures stay down", prefs.dualScreenShowOnScreen)
        assertEquals("the mode Show on screen puts back up", OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertEquals(1, dismissals)
    }

    @Test fun `a reading pick re-flavors in place and keeps the window when readings land`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        window()!!.layer.readingBoxes = { _, _ -> listOf(readingBox) }
        pick(OverlayMode.FURIGANA)
        settle()
        assertTrue(controller.capturePresentationShowing)
        assertEquals(OverlayMode.FURIGANA, controller.capturePresentationMode)
        assertEquals(OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertTrue(window()!!.layer.hasBoxes)
        assertEquals(0, dismissals)
    }

    @Test fun `readings that land empty take the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        window()!!.layer.readingBoxes = { _, _ -> emptyList() }
        pick(OverlayMode.FURIGANA)
        settle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a running auto-translate stops before the capture takes the game screen`() {
        val live = running(OverlayFlavor.TRANSLATION)
        assertTrue(present(OverlayMode.TRANSLATION))
        assertEquals(1, live.stops)
        assertFalse(service!!.get().isLive)
        assertTrue(controller.capturePresentationShowing)
    }

    @Test fun `the window carries the mode pill, offering the capture's modes with the window's own marked`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val w = window()!!
        assertEquals(android.view.View.VISIBLE, w.pill.visibility)
        assertEquals(listOf("Translation", "Furigana", "Definitions", "None"), segments(w).map { it.text.toString() })
        assertEquals(pillSaying("Translation"), pillSays(w))
        assertFalse("expanded until its X is tapped", w.pill.collapsed)
    }

    @Test fun `a real tap on a segment re-flavors the window in place and writes the pref, and one off the pill hands the screen back`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val w = laidOut(window()!!)
        w.layer.readingBoxes = { _, _ -> listOf(readingBox) }

        tapOn(w, segment(w, "Furigana"))
        settle()

        assertSame("the same window, re-flavored", w, window())
        assertEquals(OverlayMode.FURIGANA, controller.capturePresentationMode)
        assertEquals(OverlayMode.FURIGANA, prefs.captureOverlayMode)
        assertEquals(pillSaying("Furigana"), pillSays(w))
        assertEquals(1, modeChanges)
        assertEquals(0, dismissals)

        // Off the pill, under it: the game screen.
        val pill = rectIn(w, w.pill)
        tapWindow(w, pill.exactCenterX(), pill.bottom + 40f)
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `the pill sits under the status bar`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val w = window()!!
        val statusBar = android.view.WindowInsets.Type.statusBars()
        w.dispatchApplyWindowInsets(
            android.view.WindowInsets.Builder()
                .setInsets(statusBar, android.graphics.Insets.of(0, 40, 0, 0))
                .setVisible(statusBar, true)
                .build(),
        )
        val density = ctx.resources.displayMetrics.density
        val lp = w.pill.layoutParams as android.widget.FrameLayout.LayoutParams
        assertEquals(40 + (com.playtranslate.ui.OverlayModePill.TOP_DP * density).toInt(), lp.topMargin)
        assertEquals((com.playtranslate.ui.OverlayModePill.END_DP * density).toInt(), lp.marginEnd)
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.END, lp.gravity)
    }

    @Test fun `the X collapses the pill, and the next window opens collapsed`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val w = laidOut(window()!!)

        tapOn(w, closeButton(w))

        assertTrue(w.pill.collapsed)
        assertTrue("the one-screen sheet's memory", prefs.captureOverlayPillCollapsed)
        assertTrue("collapsing it is not a tap on the game", controller.capturePresentationShowing)
        controller.dismissCapturePresentation()
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(window()!!.pill.collapsed)
    }

    @Test fun `in Definitions a tap on the pill over a word is the pill's`() {
        // Where the Translation segment sits, measured on a window like the
        // one the word will be boxed in.
        assertTrue(present(OverlayMode.TRANSLATION))
        val probe = laidOut(window()!!)
        val under = rectIn(probe, segment(probe, "Translation"))
        val frameRect = frameRectOf(probe, under)
        controller.dismissCapturePresentation()
        assertTrue(present(OverlayMode.TRANSLATION, wordDataAt(frameRect)))
        val w = laidOut(window()!!)
        w.layer.tokenizeLine = words
        pick(OverlayMode.DEFINITIONS)
        settle()
        assertTrue("a word lies under the segment", w.layer.wordAt(under.exactCenterX(), under.exactCenterY()))

        tapOn(w, segment(w, "Translation"))
        idle()

        assertFalse("no card for the word under the pill", w.layer.hasCard)
        assertEquals(OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertTrue(controller.capturePresentationShowing)
    }

    @Test fun `a window already replaced switches, dismisses and remembers nothing`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val old = window()!!
        assertTrue(present(OverlayMode.TRANSLATION))
        val current = window()!!
        assertEquals("the replacement notified the old one", 1, dismissals)

        // Clicks the old pill posted before it went (a segment, its X), and
        // a tap still reaching it while its removal lands.
        old.pill.onSelect!!.invoke(OverlayMode.NONE)
        closeButton(old).performClick()
        idle()
        tapWindow(old, 20f, 400f)

        assertSame(current, window())
        assertEquals(OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertEquals("nothing written", OverlayMode.TRANSLATION, prefs.captureOverlayMode)
        assertFalse("nor its collapse remembered", prefs.captureOverlayPillCollapsed)
        assertEquals(1, dismissals)
        assertEquals(0, modeChanges)
    }

    @Test fun `presenting again replaces the window and notifies the old one once`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(present(OverlayMode.TRANSLATION))
        assertTrue(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
        controller.dismissCapturePresentation()
        controller.dismissCapturePresentation()
        assertEquals("idempotent", 2, dismissals)
    }

    @Test fun `a change of the game language takes the window down once`() {
        assertTrue(present(OverlayMode.TRANSLATION))

        controller.endCaptures(CaptureTakeover.LANGUAGE_CHANGE)
        idle()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a capture read in a language other than the game's is not put up`() {
        // The page keeps a result through a change of the game language,
        // and its Show on screen presents it through here.
        prefs.sourceLang = "en"
        assertFalse("read in Japanese, the game's English now", present(OverlayMode.TRANSLATION))
        assertFalse(controller.capturePresentationShowing)
        assertEquals(0, dismissals)

        prefs.sourceLang = "ja"
        assertTrue("read in the game's language", present(OverlayMode.TRANSLATION))
    }

    // ── The display's geometry ───────────────────────────────────────────

    private fun geometry() = ctx.displayGeometryOf(Display.DEFAULT_DISPLAY)!!

    /** The display listener's check for the capture's window, as an event
     *  for the game display runs it. */
    private fun displayChanged() {
        OverlayUiController::class.java
            .getDeclaredMethod("dropReconfiguredCaptureWindow", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(controller, Display.DEFAULT_DISPLAY)
        idle()
    }

    @Test fun `a display event that keeps the geometry leaves the window up`() {
        // A refresh-rate or state event changes neither the size nor the
        // rotation.
        assertTrue(present(OverlayMode.TRANSLATION))

        displayChanged()

        assertTrue(controller.capturePresentationShowing)
        assertEquals(0, dismissals)
    }

    /** The game display takes [qualifiers]: the display itself, and the
     *  configuration the app reads its size through. */
    private fun reconfigure(qualifiers: String) {
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, qualifiers)
        RuntimeEnvironment.setQualifiers(qualifiers)
    }

    @Test fun `a turn of the game display takes the window down once`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val before = geometry()
        reconfigure("+land")
        val after = geometry()
        assertNotEquals("another size", before.width to before.height, after.width to after.height)
        assertNotEquals("another rotation", before.rotation, after.rotation)

        displayChanged()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a resize that keeps the rotation takes the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        val before = geometry()
        reconfigure("w400dp-h600dp")
        val after = geometry()
        assertEquals("the same rotation", before.rotation, after.rotation)
        assertNotEquals("another size", before.width to before.height, after.width to after.height)

        displayChanged()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a 180 degree flip that keeps the size takes the Definitions window down`() {
        definitionsWindow()
        val before = geometry()
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(Surface.ROTATION_180)
        val after = geometry()
        assertEquals("the same size", before.width to before.height, after.width to after.height)
        assertNotEquals("another rotation", before.rotation, after.rotation)

        displayChanged()

        assertFalse("its words would sit over other text", controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    // ── The layout a capture's frame was taken in ────────────────────────

    /** [data] put up as a capture of the page's: landing as the capture of
     *  [captureId], or with none, as the page's Show on screen asks. */
    private fun presentAs(data: OneShotOverlayData, captureId: Long? = null): Boolean {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        val shown = controller.presentCapture(
            Display.DEFAULT_DISPLAY, data, SourceLangId.JA, captureId = captureId,
            onModeChanged = { modeChanges++ },
        ) { dismissals++ }
        idle()
        return shown
    }

    @Test fun `a capture whose frame was taken before a turn stays off the game, as it lands or from Show on screen`() {
        val taken = data("hi").copy(frameGeometry = geometry())
        val id = controller.beginAppCapture(Display.DEFAULT_DISPLAY, null)
        reconfigure("+land")
        assertNotEquals(taken.frameGeometry, geometry())

        assertFalse("landing after the turn", presentAs(taken, id))
        assertFalse("Show on screen asking after it", presentAs(taken))
        assertFalse(controller.capturePresentationShowing)
        assertEquals(0, dismissals)
    }

    @Test fun `a capture taken in the layout the display has goes up, as it lands or from Show on screen`() {
        val taken = data("hi").copy(frameGeometry = geometry())
        assertTrue("landing", presentAs(taken, controller.beginAppCapture(Display.DEFAULT_DISPLAY, null)))
        controller.dismissCapturePresentation()
        assertTrue("Show on screen asking", presentAs(taken))
    }

    @Test fun `a frame whose layout nothing recorded goes up after a turn as before`() {
        reconfigure("+land")
        assertTrue(presentAs(data("hi")))
    }

    @Test fun `after a flip took the window down, Show on screen leaves it down until the display flips back`() {
        val taken = data("hi").copy(frameGeometry = geometry())
        assertTrue(presentAs(taken))
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display.rotation
        shadowOf(display).setRotation(Surface.ROTATION_180)
        displayChanged()
        assertFalse(controller.capturePresentationShowing)

        assertFalse("its boxes would sit over other text", presentAs(taken))
        assertFalse(controller.capturePresentationShowing)

        shadowOf(display).setRotation(rotation)
        assertTrue("the layout it was taken in again", presentAs(taken))
    }

    // ── Definitions ──────────────────────────────────────────────────────

    @Test fun `translations leave the keys to the game`() {
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        assertFalse(windowFocusable())
    }

    @Test fun `tap-to-lookup over the window switches it to Definitions in place with key focus, the setting untouched`() {
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        val w = window()!!
        w.layer.tokenizeLine = words

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        settle()

        assertSame(w, window())
        assertEquals(OverlayMode.DEFINITIONS, controller.capturePresentationMode)
        assertEquals("the pill marks it", pillSaying("Definitions"), pillSays(w))
        assertTrue(w.layer.hasBoxes)
        assertTrue(windowFocusable())
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
        assertEquals(0, dismissals)
    }

    @Test fun `a pick away from Definitions gives the keys back`() {
        definitionsWindow()
        assertTrue(windowFocusable())

        pick(OverlayMode.TRANSLATION)

        assertTrue(controller.capturePresentationShowing)
        assertFalse(windowFocusable())
    }

    /** The default display's floating icon, installed. */
    private fun icon(): com.playtranslate.ui.FloatingOverlayIcon {
        com.playtranslate.capture.CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        controller.reconcileFloatingIcons(freshAppearance = false)
        idle()
        val handles = OverlayUiController::class.java.getDeclaredField("iconHandles")
            .apply { isAccessible = true }.get(controller) as Map<*, *>
        val handle = handles[Display.DEFAULT_DISPLAY]!!
        return handle.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as com.playtranslate.ui.FloatingOverlayIcon
    }

    @Test fun `a tap-to-lookup hold that slides into a drag takes the window it switched down`() {
        prefs.iconHoldAction = HoldAction.TAP_TO_LOOKUP
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        window()!!.layer.tokenizeLine = words
        val icon = icon()

        icon.onHoldStart!!.invoke()
        settle()
        assertEquals("the hold switched it in place", OverlayMode.DEFINITIONS, controller.capturePresentationMode)
        assertTrue("its words are up", window()!!.layer.hasBoxes)

        icon.onHoldCancel!!.invoke()
        idle()

        assertFalse("not left over the game under the lens", controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    // The slide takes down the window the hold switched in place, and sends
    // the app nothing: the app's result stands on its page.
    @Test fun `a tap-to-lookup hold that switched the window in place sends the app nothing`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        prefs.iconHoldAction = HoldAction.TAP_TO_LOOKUP
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        window()!!.layer.tokenizeLine = words
        val icon = icon()
        val app = shadowOf(ctx as android.app.Application)
        generateSequence { app.nextStartedActivity }.toList()

        icon.onHoldStart!!.invoke()
        settle()
        assertEquals(OverlayMode.DEFINITIONS, controller.capturePresentationMode)
        icon.onHoldCancel!!.invoke()
        idle()

        assertFalse(controller.capturePresentationShowing)
        val sent = generateSequence { app.nextStartedActivity }.map { it.action }.toList()
        assertEquals("no capture sent", emptyList<String?>(), sent)
    }

    // Driven through the hold triggers in CapturePauseFunnelsTest; here the
    // row itself, which this controller (not the active backend's) receives.
    @Test fun `a hold-to-preview on the window's display takes a Definitions window down and leaves another up`() {
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        window()!!.layer.tokenizeLine = words

        controller.endCaptures(CaptureTakeover.HOLD_PREVIEW, setOf(Display.DEFAULT_DISPLAY))
        assertTrue("a Translations window stands under the preview", controller.capturePresentationShowing)

        pick(OverlayMode.DEFINITIONS)
        settle()
        controller.endCaptures(CaptureTakeover.HOLD_PREVIEW, setOf(Display.DEFAULT_DISPLAY + 7))
        assertTrue("a preview on another display leaves it up", controller.capturePresentationShowing)
        controller.endCaptures(CaptureTakeover.HOLD_PREVIEW, setOf(Display.DEFAULT_DISPLAY))
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `the quick menu opening takes the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))
        prefs.iconTapAction = TapAction.OPEN_QUICK_MENU
        val icon = icon()

        icon.onTap!!.invoke()
        idle()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
        controller.dismissFloatingMenu()
    }

    @Test fun `the error pill's gear takes the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))

        OverlayUiController::class.java.getDeclaredMethod("openServiceSettingsFromPill", String::class.java, Int::class.java)
            .apply { isAccessible = true }.invoke(controller, "deepl", Display.DEFAULT_DISPLAY)
        idle()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `opening the game-language picker takes the window down`() {
        assertTrue(present(OverlayMode.TRANSLATION))

        // No second language installed: "Change game language" opens the picker.
        controller.changeGameLanguageForDisplay(Display.DEFAULT_DISPLAY)
        idle()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `tap-to-lookup's switch in place tells the page the mode changed, as the pill's pick does`() {
        // The page reads the window's mode (Translations need the deferred
        // translation); tap-to-lookup switches it without the page.
        assertTrue(present(OverlayMode.TRANSLATION, wordData()))
        window()!!.layer.tokenizeLine = words

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)

        assertEquals(OverlayMode.DEFINITIONS, controller.capturePresentationMode)
        assertEquals(1, modeChanges)
        assertEquals(0, dismissals)

        pick(OverlayMode.TRANSLATION)
        assertEquals("one path for both", 2, modeChanges)

        pick(OverlayMode.NONE)
        assertEquals(1, dismissals)
        assertEquals("a switch that takes it down is the dismissal", 2, modeChanges)
    }

    @Test fun `an icon's tap-to-lookup on another display leaves this window alone, and the hotkey toggles it wherever it is`() {
        val other = ShadowDisplayManager.addDisplay("w640dp-h480dp")
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        assertTrue(
            controller.presentCapture(other, wordData(), SourceLangId.JA, onModeChanged = { modeChanges++ }) {
                dismissals++
            },
        )
        idle()
        window()!!.layer.tokenizeLine = words

        // The default display's icon is not pressed over this window.
        OverlayUiController::class.java.getDeclaredMethod("captureInDefinitions", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, Display.DEFAULT_DISPLAY)
        idle()
        assertEquals("not switched", OverlayMode.TRANSLATION, controller.capturePresentationMode)
        assertEquals(0, modeChanges)

        // The hotkey has no display of its own: it toggles the one up.
        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        assertEquals(OverlayMode.DEFINITIONS, controller.capturePresentationMode)
    }

    @Test fun `tap-to-lookup over a Definitions window takes it down`() {
        definitionsWindow()

        controller.toggleTapToLookupForDisplay(Display.DEFAULT_DISPLAY)
        idle()

        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `a capture presented with the Definitions override opens in Definitions without writing the setting`() {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        assertTrue(
            controller.presentCapture(
                Display.DEFAULT_DISPLAY, wordData(), SourceLangId.JA,
                modeOverride = OverlayMode.DEFINITIONS,
            ) { dismissals++ },
        )
        // The word build starts on the next turn of the main loop: the
        // stand-in tokenizer goes in first.
        window()!!.layer.tokenizeLine = words
        settle()

        assertEquals(OverlayMode.DEFINITIONS, controller.capturePresentationMode)
        assertTrue(window()!!.layer.hasBoxes)
        assertTrue(windowFocusable())
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
    }

    @Test fun `in Definitions a tap on a word opens its card and keeps the window, and one on nothing hands the screen back`() {
        val w = definitionsWindow()
        val word = w.layer.drawnBoxRects().first()

        tapWindow(w, word.exactCenterX(), word.exactCenterY())
        assertTrue(controller.capturePresentationShowing)
        assertTrue(w.layer.hasCard)

        // With a card up its own window takes a tap off it; with none, a tap
        // off every word is on the game.
        w.layer.closeCard()
        tapWindow(w, word.exactCenterX(), word.bottom + 200f)
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }

    @Test fun `in Definitions Back closes a card first, then hands the screen back`() {
        val w = definitionsWindow()
        val word = w.layer.drawnBoxRects().first()
        tapWindow(w, word.exactCenterX(), word.exactCenterY())
        assertTrue(w.layer.hasCard)

        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idle()
        assertFalse(w.layer.hasCard)
        assertTrue(controller.capturePresentationShowing)

        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idle()
        assertFalse(controller.capturePresentationShowing)
        assertEquals(1, dismissals)
    }
}
