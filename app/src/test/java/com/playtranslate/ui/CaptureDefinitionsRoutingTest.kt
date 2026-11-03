package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.displaySizePx
import com.playtranslate.language.TokenSpan
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * The over-game capture sheet in Definitions: where its touches and keys go.
 * A tap on a word opens its card, parked or expanded, and a word inside the
 * band that grabs the sheet past its edge is the word's in either posture;
 * a tap on nothing is the sheet's
 * (the band's tap-to-expand inside it, the user's close above it); the
 * D-pad and A drive the words while the sheet is parked and are the panel's
 * when it is up; Back closes a card before the sheet; a card that ends by
 * its own means closes the sheet as the user would; the window holds key
 * focus in Definitions alone; the dimming stays beneath the panel while the
 * boxes rise over a parked strip. A capture that tap-to-lookup opened (the mode
 * override) starts parked whatever the remembered posture, and leaves the
 * remembered mode and posture as they were.
 *
 * The sheet is laid out by hand at the test display's size before each
 * touch, so the panel's edge (which the touch rules read) is where a device
 * would put it; the words sit at the crop's own coordinates (no crop, a
 * frame the display's size).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureDefinitionsRoutingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val prefs = Prefs(ctx)
    private val screen = ctx.displaySizePx()
    private val density = ctx.resources.displayMetrics.density
    private var sheet: CaptureResultOverlay? = null
    private val dismissals = mutableListOf<CaptureResultOverlay.DismissReason>()
    private var flow: MutableStateFlow<CaptureState>? = null

    /** "hello world" at y 100..120, 20 px a character from x = 20: "hello"
     *  20..120, "world" 140..240. */
    private val top = group("hello world", top = 100, left = 20)

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        // The pill as its disc in the top corner, clear of every word here.
        prefs.captureOverlayPillCollapsed = true
    }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        sp().edit().clear().commit()
    }

    // ── harness ──────────────────────────────────────────────────────────

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Let the sheet's animations run out. */
    private fun idleLong() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private fun layerOf(o: CaptureResultOverlay): CaptureOverlayLayer = o.field("layer")
    private fun words(o: CaptureResultOverlay): WordLookupSession = layerOf(o).field("words")
    private fun selected(o: CaptureResultOverlay): Int? = words(o).field("selected")
    private fun cursor(o: CaptureResultOverlay): Int? = words(o).field("cursor")
    private fun parked(o: CaptureResultOverlay): Boolean = o.field("sliverMode")
    private fun pillOf(o: CaptureResultOverlay): OverlayModePill = o.field("modePill")

    /** The sheet's window holds key focus. */
    private fun focusable(o: CaptureResultOverlay): Boolean {
        val params: WindowManager.LayoutParams = o.field<Any>("sheetHost").field("params")
        return (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
    }

    private fun group(text: String, top: Int, left: Int): OcrManager.OcrGroup {
        val bounds = Rect(left, top, left + text.length * 20, top + 20)
        val symbols = text.mapIndexedNotNull { i, ch ->
            if (ch == ' ') null
            else OcrManager.SymbolBox(ch.toString(), Rect(left + i * 20, top, left + (i + 1) * 20, top + 20), i)
        }
        return OcrManager.OcrGroup(
            text, bounds,
            lines = listOf(OcrManager.LineBox(text, bounds, groupIndex = 0, symbols = symbols)),
        )
    }

    private fun data(groups: List<OcrManager.OcrGroup>) = OneShotOverlayData(
        boxes = groups.map { TextBox("", it.bounds) },
        cropLeft = 0, cropTop = 0, screenshotW = screen.x, screenshotH = screen.y,
        cropWidth = screen.x, cropHeight = screen.y,
        text = groups.map { it.bounds },
        groups = groups,
    )

    /** A sheet up, before its capture reads anything; [override] is the mode
     *  tap-to-lookup asks for. */
    private fun create(override: OverlayMode? = null): CaptureResultOverlay {
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        o.overlayModeOverride = override
        layerOf(o).tokenizeLine = { text, _ ->
            text.split(' ').filter { it.isNotEmpty() }.map { TokenSpan(it, it) }
        }
        o.show(screen.x, screen.y)
        idleLong()
        return o
    }

    /** The capture's read lands with [groups]; the words are boxed. */
    private fun bind(o: CaptureResultOverlay, vararg groups: OcrManager.OcrGroup) {
        val f = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing"))
        flow = f
        o.observe(CaptureSession(f, Job()))
        idle()
        f.value = CaptureState.Translating(
            "hello world", listOf(TextSegment("hello world")), null, data(groups.toList()),
        )
        idle()
        settleWords(o)
    }

    /** Wait out a word build on the background dispatcher. */
    private fun settleWords(o: CaptureResultOverlay) {
        val layer = layerOf(o)
        val deadline = System.currentTimeMillis() + 3000
        while (layer.pending && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idleLong()
    }

    /** How far below the top line a tap still picks one of its words: the
     *  box's pad and the tap buffer past its bottom. */
    private val wordsReach get() = 120 + (WordLookupSession.BOX_PAD_DP + WordLookupSession.BUFFER_DP) * density

    /** A y between the top line's reach and [limit], clear of both. */
    private fun gapAbove(limit: Float): Float {
        val y = (wordsReach + limit) / 2
        assertTrue("a gap between the words and $limit", y > wordsReach + 1 && y < limit - 1)
        return y
    }

    private fun done(o: CaptureResultOverlay, vararg groups: OcrManager.OcrGroup) {
        flow!!.value = CaptureState.Done(
            TranslationResult(
                originalText = "hello world",
                segments = listOf(TextSegment("hello world")),
                translatedText = "hi",
                timestamp = "",
                langContext = prefs.langContext(),
            ),
            data(groups.toList()),
        )
        idleLong()
    }

    private fun layout(o: CaptureResultOverlay) {
        val root: ViewGroup = o.field("root")
        root.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, screen.x, screen.y)
    }

    /** Where the panel's edge sits once laid out. */
    private fun panelTop(o: CaptureResultOverlay): Float {
        layout(o)
        val panel: View = o.field("panel")
        return panel.top + panel.translationY
    }

    /** The top of the band above the panel's edge that grabs the sheet. */
    private fun bandTop(o: CaptureResultOverlay): Float {
        val grab = CaptureResultOverlay::class.java.getDeclaredField("EXTRA_GRAB_PAST_EDGE_DP")
            .apply { isAccessible = true }.getInt(null)
        return panelTop(o) - grab * density
    }

    private fun tap(o: CaptureResultOverlay, x: Float, y: Float) {
        layout(o)
        val root: ViewGroup = o.field("root")
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val ev = MotionEvent.obtain(now, now, action, x, y, 0)
            root.dispatchTouchEvent(ev)
            ev.recycle()
        }
        idle()
    }

    private fun key(o: CaptureResultOverlay, code: Int) {
        val root: ViewGroup = o.field("root")
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        idle()
    }

    // ── the override ─────────────────────────────────────────────────────

    @Test fun `a tap-to-lookup capture opens parked over its words whatever the remembered posture`() {
        prefs.capturePanelPosture = 0.4f
        val o = create(override = OverlayMode.DEFINITIONS)
        assertTrue("so its translation waits", o.willOpenParked())
        assertTrue(parked(o))

        bind(o, top)

        assertTrue(parked(o))
        assertEquals(OverlayMode.DEFINITIONS, o.overlayMode)
        assertEquals(listOf("hello", "world"), words(o).field<WordBoxesView>("view").shownBoxes.map { it.surface })
    }

    @Test fun `closing a tap-to-lookup capture leaves the remembered mode and posture as they were`() {
        prefs.capturePanelPosture = 0.4f
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        done(o, top)

        o.animateOutAndDismiss()
        idleLong()

        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
        assertFalse("no mode remembered", sp().contains(Prefs.KEY_CAPTURE_OVERLAY_MODE))
        assertEquals("no posture remembered", 0.4f, prefs.capturePanelPosture, 0f)
    }

    @Test fun `the override over a Translations sheet paints its words in place, takes key focus, and remembers nothing`() {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        val o = create()
        bind(o, top)
        assertFalse(focusable(o))

        o.applyOverlayModeOverride(OverlayMode.DEFINITIONS)
        settleWords(o)

        assertEquals(OverlayMode.DEFINITIONS, o.overlayMode)
        assertTrue(layerOf(o).hasBoxes)
        assertTrue(focusable(o))
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
    }

    @Test fun `a tap-to-lookup capture lets its translation wait while it is parked`() {
        prefs.capturePanelPosture = 0.4f
        val o = create(override = OverlayMode.DEFINITIONS)
        assertEquals(CaptureResultOverlay.CaptureDeferral(allow = true, whileParked = true), o.captureDeferral())
    }

    @Test fun `a Translations capture defers nothing, and an expanded Definitions one only with the section hidden`() {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        assertEquals(CaptureResultOverlay.CaptureDeferral(allow = false, whileParked = false), create().captureDeferral())
        sheet?.dismiss()

        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        assertEquals(CaptureResultOverlay.CaptureDeferral(allow = true, whileParked = false), create().captureDeferral())
    }

    @Test fun `a pick on the pill ends the override, is remembered, and gives key focus back`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        assertTrue(focusable(o))

        pillOf(o).onSelect!!.invoke(OverlayMode.TRANSLATION)
        idle()

        assertEquals(OverlayMode.TRANSLATION, o.overlayMode)
        assertNull(o.overlayModeOverride)
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
        assertFalse(focusable(o))
    }

    @Test fun `a tap-to-lookup sheet holds the keys while its read runs, and Back then closes it`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        assertTrue("from the first frame", focusable(o))

        key(o, KeyEvent.KEYCODE_BACK)
        idleLong()

        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `a pick before the read lands gives the keys back`() {
        val o = create(override = OverlayMode.DEFINITIONS)

        pillOf(o).onSelect!!.invoke(OverlayMode.TRANSLATION)
        idle()

        assertFalse(focusable(o))
    }

    // ── touches, parked ──────────────────────────────────────────────────

    @Test fun `parked, a tap on a word opens its card and the sheet stays parked`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)

        tap(o, 70f, 110f)

        assertEquals(0, selected(o))
        assertTrue(parked(o))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `parked, a tap a little off a word picks it`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)

        // 20dp below "hello"'s grown box: inside the tap buffer.
        tap(o, 70f, 120 + (WordLookupSession.BOX_PAD_DP + 20) * density)

        assertEquals(0, selected(o))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `parked, a word inside the grab band is the word's, and the band keeps the rest`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        val band = bandTop(o)
        bind(o, top, group("low", top = (band + 2).toInt(), left = 20))
        assertTrue(parked(o))

        tap(o, 50f, band + 12)
        assertEquals("the band's word, not the band", 2, selected(o))
        assertTrue(parked(o))

        words(o).closeCard()
        tap(o, screen.x - 20f, band + 12)
        idleLong()
        assertFalse("a band tap off every word expands the sheet", parked(o))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `parked, a tap above the band on nothing is the user's close`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        val band = bandTop(o)
        bind(o, top)

        tap(o, screen.x / 2f, gapAbove(band))
        idleLong()

        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `with a card up, a tap on nothing closes only the card`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        val band = bandTop(o)
        bind(o, top)
        tap(o, 70f, 110f)

        // The card's full-screen window takes that tap and hands it here.
        words(o).field<MagnifierLens>("magnifier").onOffCardDown!!.invoke(screen.x / 2f, gapAbove(band))
        idle()

        assertNull(selected(o))
        assertTrue(parked(o))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `parked, the dimming stays beneath the sheet while the words sit above it`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        tap(o, 70f, 110f)
        assertEquals(0, selected(o))

        val root: ViewGroup = o.field("root")
        val panel: View = o.field("panel")
        val holder = words(o).field<WordBoxesView>("view").spotlight.parent as View
        assertTrue("the dimming beneath the panel", root.indexOfChild(holder) in 0 until root.indexOfChild(panel))
        assertTrue("the words above it", root.indexOfChild(layerOf(o)) > root.indexOfChild(panel))
    }

    // ── touches, expanded ────────────────────────────────────────────────

    @Test fun `expanded, the words sit above the dimming and the panel above both`() {
        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        val o = create()
        bind(o, top)
        tap(o, 70f, 110f)
        assertEquals(0, selected(o))

        val root: ViewGroup = o.field("root")
        val panel: View = o.field("panel")
        val holder = words(o).field<WordBoxesView>("view").spotlight.parent as View
        val words = root.indexOfChild(layerOf(o))
        assertTrue("the dimming beneath the words", root.indexOfChild(holder) in 0 until words)
        assertTrue("the panel over the words", words < root.indexOfChild(panel))
    }

    @Test fun `expanded, a word just above the sheet's edge is the word's, inside the grab band`() {
        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        val o = create()
        bind(o, top)
        assertFalse(parked(o))
        val edge = panelTop(o)

        // The same text read again with a word just above the edge: the sheet
        // fits the same text, so its edge stays.
        bind(o, top, group("edge", top = (edge - 22).toInt(), left = 20))
        assertEquals(edge, panelTop(o), 0.5f)
        tap(o, 50f, edge - 12)

        assertEquals("the word, not the grab band", 2, selected(o))
        assertTrue(dismissals.isEmpty())
    }

    @Test fun `expanded, a tap above the panel on a word opens its card, and one on nothing is the user's close`() {
        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        val o = create()
        bind(o, top)
        assertFalse(parked(o))
        val y = gapAbove(bandTop(o))

        tap(o, 70f, 110f)
        assertEquals(0, selected(o))
        assertTrue(dismissals.isEmpty())

        words(o).closeCard()
        tap(o, screen.x / 2f, y)
        idleLong()
        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `expanded, a word the panel covers is the panel's`() {
        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        val o = create()
        bind(o, top)
        val edge = panelTop(o)
        val under = (edge + 60).toInt()
        assertTrue("room for a line under the panel's edge", under + 40 < screen.y)

        bind(o, top, group("under", top = under, left = 20))
        assertEquals(edge, panelTop(o), 0.5f)
        tap(o, 50f, under + 10f)

        assertNull("no card for a word the panel hides", selected(o))
        assertTrue(dismissals.isEmpty())
    }

    // ── keys ─────────────────────────────────────────────────────────────

    @Test fun `parked, the D-pad and A drive the words`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)

        key(o, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(0, cursor(o))
        key(o, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, selected(o))
        assertTrue(parked(o))
    }

    @Test fun `expanded, the D-pad is not the words'`() {
        prefs.captureOverlayMode = OverlayMode.DEFINITIONS
        val o = create()
        bind(o, top)
        assertFalse(parked(o))

        key(o, KeyEvent.KEYCODE_DPAD_RIGHT)

        assertNull(cursor(o))
    }

    @Test fun `Back closes a card first, then the sheet`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        tap(o, 70f, 110f)
        assertEquals(0, selected(o))

        key(o, KeyEvent.KEYCODE_BACK)
        assertNull(selected(o))
        assertTrue(dismissals.isEmpty())

        key(o, KeyEvent.KEYCODE_BACK)
        idleLong()
        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    /** Back as the platform's callback delivers it (target 36 on Android
     *  16), where the key never arrives. */
    private fun systemBack(o: CaptureResultOverlay) {
        val root: ViewGroup = o.field("root")
        val callback: android.window.OnBackInvokedCallback = root.field("backCallback")
        callback.onBackInvoked()
        idle()
    }

    @Test fun `the platform's Back closes a card first, then the sheet, as the key does`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        tap(o, 70f, 110f)
        assertEquals(0, selected(o))

        systemBack(o)
        assertNull(selected(o))
        assertTrue(dismissals.isEmpty())

        systemBack(o)
        idleLong()
        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `the platform's Back leaves a Translations sheet without a controller to the game`() {
        val o = create()
        bind(o, top)

        systemBack(o)
        idleLong()

        assertTrue("nothing of the sheet's to close", dismissals.isEmpty())
    }

    @Test fun `with a controller attached, the platform's Back runs the sheet's own ladder in any mode`() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        o.controllerNavEnabled = true
        o.show(screen.x, screen.y)
        idleLong()
        assertNotNull("the controller nav is live", o.field<Any?>("nav"))

        systemBack(o)
        idleLong()

        assertEquals("B's rung: the sheet closes", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `a Back release whose press the sheet never saw does nothing`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        val root: ViewGroup = o.field("root")

        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idleLong()

        assertTrue(dismissals.isEmpty())
    }

    // ── the card's end ───────────────────────────────────────────────────

    @Test fun `a card that ends by its own means closes the sheet as the user would`() {
        val o = create(override = OverlayMode.DEFINITIONS)
        bind(o, top)
        tap(o, 70f, 110f)

        words(o).field<DragLookupController>("lookup").onSettled!!.invoke()
        idleLong()

        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }
}
