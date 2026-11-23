package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlay.Sides
import com.playtranslate.testutil.RecordingWindowManager
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.DetachedWindowGeometry.Frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The detached window's one menu: everything the window can do that its bar
 * does not hold, by name, in four groups under headings; which rows are
 * dimmed with nothing to act on, which leave the menu up, and what each
 * runs. And the bar's two shortcuts to rows of it, and what Capture and the
 * menu's last row say.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedWindowMenusTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private val g = DetachedWindowGeometry
    private val room = g.room(1280, 800, Sides.NONE)
    private lateinit var window: DetachedWindow
    private val host = FakeHost()

    private class FakeHost : DetachedWindow.Host {
        var minimizes = 0
        var regions = 0
        var autos = 0
        var exits = 0
        var fullScreen = true
        var turnsOff = true
        val ran = mutableListOf<String>()
        override fun onCapture(displayId: Int) {}
        override fun onToggleAuto(displayId: Int) { autos++ }
        override fun onEditRegion(displayId: Int) { regions++ }
        override fun onMinimize(displayId: Int) { minimizes++ }
        override fun onExit(displayId: Int) { exits++ }
        override fun onDocked(displayId: Int, side: DetachedWindowGeometry.Side, fingerY: Int) {}
        override fun onTouched(displayId: Int) {}
        override fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting> = listOf(
            QuickSetting(QuickSetting.Id.LANGUAGE, "Language", "Japanese") { ran += "language"; leave() },
            QuickSetting(QuickSetting.Id.OCR, "OCR", "ML Kit") { ran += "ocr" },
            QuickSetting(QuickSetting.Id.OVERLAYS, "Overlays", "Translation") { row ->
                ran += "overlays"
                row.value = "Reading hints"
            },
            QuickSetting(QuickSetting.Id.OPEN_APP, "Open PlayTranslate", icon = R.drawable.ic_open_in_new) {
                ran += "open"
                leave()
            },
        )
        override fun regionIsFullScreen(displayId: Int) = fullScreen
        override fun exitTurnsOff() = turnsOff
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    @Before fun setUp() = clearPrefs()

    @After fun tearDown() {
        if (::window.isInitialized) window.remove()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun show(frame: Frame = Frame(400, 200, 420, 400)) {
        prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(frame, room, 1f))
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        val wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        val overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        overlayHost.environmentReader = { testEnvironment(1280, 800) }
        window = DetachedWindow(displayCtx, wm, d0, overlayHost, prefs, host)
        assertTrue(window.show())
        layOut()
    }

    private fun layOut() {
        val p = window.root.layoutParams as WindowManager.LayoutParams
        window.root.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY),
        )
        window.root.layout(0, 0, p.width, p.height)
        idle()
    }

    private fun bar(id: Int): View = window.root.findViewById(id)

    private fun tap(view: View) {
        view.performClick()
        layOut()
    }

    /** The popover's own window, laid out as the window manager would and
     *  given a turn of the looper for its card to come up. */
    private fun popoverRoot(): ViewGroup {
        val root = window.popovers.rootForTest as? ViewGroup ?: error("no popover window")
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1280, 800)
            idle()
        }
        return root
    }

    private fun find(v: View, text: String): View? {
        if (v is TextView && v.text.toString() == text) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), text)?.let { return it }
        return null
    }

    /** The row of the open popover that shows [text], by its click target. */
    private fun rowWith(text: String): View {
        var v: View = find(popoverRoot(), text) ?: error("no row \"$text\"")
        while (!v.hasOnClickListeners()) v = v.parent as View
        return v
    }

    private fun hasRow(text: String): Boolean = find(popoverRoot(), text) != null

    /** A tap off the popover's card: its scrim, the window's first child. */
    private fun tapOutside() {
        popoverRoot().getChildAt(0).performClick()
        idle()
    }

    /** Every text of the open popover, top to bottom. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE && v.text.isNotEmpty()) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(popoverRoot())
        return out
    }

    /** What stands in for the results page. */
    private class FakePage(ctx: Context) : DetachedWindow.Page {
        override val view: View = View(ctx)
        val speak = HeaderAction(R.id.btnSpeakOriginal, R.drawable.ic_lens_speak, "Read aloud")
        val furigana = HeaderAction(R.id.btnToggleFurigana, R.drawable.ic_furigana, "Furigana")
        val eye = HeaderAction(R.id.btnToggleOriginal, R.drawable.ic_visibility, "Hide original")
        val textSize = HeaderAction(R.id.btnFontSize, R.drawable.ic_format_size, "Text size")
        val language = HeaderAction(R.id.detachedMenuGameLanguage, R.drawable.ic_translate, "Game Language")
            .apply { detail = "Japanese" }
        override val readAloud get() = speak
        private val text = listOf(furigana, eye, textSize)
        override fun textActions() = text
        override fun languageRows() = listOf(language)
        val listeners = mutableListOf<FoldingActionRow.Listener>()
        var adds = 0
        var oneTaps = 0

        init {
            // The eye as the page's own: it flips, and its row then offers
            // the way back.
            eye.onClick = {
                eye.label = if (eye.label.toString() == "Hide original") "Show original" else "Hide original"
                changed(eye)
            }
        }

        override fun addActionListener(listener: FoldingActionRow.Listener) { listeners += listener }
        override fun removeActionListener(listener: FoldingActionRow.Listener) { listeners -= listener }

        /** A change of one of the actions, told as its header row tells it. */
        fun changed(a: HeaderAction) = listeners.toList().forEach { it.onActionChanged(a) }

        override var resultOnShow = false
            set(value) {
                field = value
                onResultOnShowChanged?.invoke()
            }
        override var onResultOnShowChanged: (() -> Unit)? = null
        override fun addToAnki() { adds++ }
        override fun ankiOneTap() { oneTaps++ }
        override var popoverPresenter: ((PopoverContent, View) -> Unit)? = null
    }

    private fun attach(result: Boolean): FakePage {
        val page = FakePage(ctx)
        window.attachPage(page)
        page.resultOnShow = result
        layOut()
        return page
    }

    private val addToAnki get() = ctx.getString(R.string.cd_add_to_anki)
    private val editRegion get() = ctx.getString(R.string.floating_menu_edit_region)
    private val turnOff get() = ctx.getString(R.string.floating_icon_close_label_turn_off)

    // ── The menu ─────────────────────────────────────────────────────────

    @Test fun `the menu lists everything by name in four headed groups, in a fixed order`() {
        show()
        attach(result = true)

        tap(window.bar.moreButton)

        assertEquals(
            listOf(
                ctx.getString(R.string.detached_menu_group_result),
                addToAnki, "Read aloud",
                ctx.getString(R.string.detached_menu_group_text),
                "Furigana", "Hide original", "Text size",
                ctx.getString(R.string.detached_menu_group_capture),
                editRegion, "Game Language", "Japanese", "OCR", "ML Kit", "Overlays", "Translation",
                ctx.getString(R.string.app_name),
                "Open PlayTranslate", turnOff,
            ),
            texts(),
        )
        assertFalse("the quick settings' own Language row is the page's Game Language", hasRow("Language"))
    }

    @Test fun `a popover is a window of its own, as large as the display, and goes with its card`() {
        show(Frame(400, 200, 200, 120))

        tap(window.bar.moreButton)
        val root = popoverRoot()
        var up: View? = root
        while (up != null && up !== window.root) up = up.parent as? View
        assertTrue("not inside the window, which is smaller than the menu", up == null)
        val p = root.layoutParams as WindowManager.LayoutParams
        assertEquals("the display's width (the overlay host pins it)", 1280, p.width)
        assertEquals(800, p.height)
        assertTrue("takes no keys", p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)

        tapOutside()
        assertTrue("the window went with the card", window.popovers.rootForTest == null)
    }

    @Test fun `with no result on show the result's rows are dimmed in their places and take no tap, and a result undims them`() {
        show()
        val page = attach(result = false)
        tap(window.bar.moreButton)
        val listed = texts()

        for (name in listOf(addToAnki, "Read aloud", "Furigana", "Hide original", "Text size")) {
            assertFalse("$name has nothing to act on", rowWith(name).isEnabled)
            assertTrue("$name is dimmed", rowWith(name).alpha < 1f)
        }
        for (name in listOf(editRegion, "Game Language", "OCR", "Open PlayTranslate", turnOff)) {
            assertTrue("$name needs no result", rowWith(name).isEnabled)
            assertEquals(1f, rowWith(name).alpha)
        }

        tap(rowWith(addToAnki))
        assertEquals("a dimmed row runs nothing", 0, page.adds)
        assertTrue("and the menu stays", window.popovers.isShowing)

        page.resultOnShow = true
        layOut()
        assertTrue("the menu is still the one that was open", window.popovers.isShowing)
        assertEquals("the same rows in the same places", listed, texts())
        assertTrue(rowWith(addToAnki).isEnabled)
        assertEquals(1f, rowWith("Read aloud").alpha)
    }

    @Test fun `an action's row closes the menu and runs it, with the menu button as its anchor`() {
        show()
        val page = attach(result = true)
        var spoke: View? = null
        page.speak.onClick = { spoke = it }

        tap(window.bar.moreButton)
        tap(rowWith("Read aloud"))
        assertEquals(window.bar.moreButton, spoke)
        assertFalse(window.popovers.isShowing)

        tap(window.bar.moreButton)
        tap(rowWith(addToAnki))
        assertEquals(1, page.adds)
        assertEquals("the row is a tap only: no card sent", 0, page.oneTaps)
        assertFalse(window.popovers.isShowing)

        tap(window.bar.moreButton)
        tap(rowWith(editRegion))
        assertEquals(1, host.regions)
        assertFalse(window.popovers.isShowing)

        tap(window.bar.moreButton)
        tap(rowWith(turnOff))
        assertEquals(1, host.exits)
        assertFalse(window.popovers.isShowing)
    }

    @Test fun `a switch leaves the menu up, and its row and the rows it touches follow it`() {
        show()
        val page = attach(result = true)
        tap(window.bar.moreButton)

        tap(rowWith("Hide original"))
        assertTrue("still up, to flip back or to watch", window.popovers.isShowing)
        assertTrue("the row now offers the way back", hasRow("Show original"))

        // What hiding the Original does to the actions on it.
        page.furigana.available = false
        page.changed(page.furigana)
        layOut()
        assertTrue(window.popovers.isShowing)
        assertFalse("dimmed in its place, not taken out", rowWith("Furigana").isEnabled)

        tap(rowWith("Show original"))
        page.furigana.available = true
        page.changed(page.furigana)
        layOut()
        assertTrue(rowWith("Furigana").isEnabled)
        assertTrue(window.popovers.isShowing)
    }

    @Test fun `a quick setting runs in place with its value read again, and one that hands over closes the menu`() {
        show()
        attach(result = true)
        tap(window.bar.moreButton)

        tap(rowWith("Overlays"))
        assertEquals(listOf("overlays"), host.ran)
        assertTrue("a row that changes a value stays open", window.popovers.isShowing)
        assertTrue(hasRow("Reading hints"))
        assertFalse(hasRow("Translation"))

        tap(rowWith("OCR"))
        assertEquals("its picker opens over the menu, which waits", listOf("overlays", "ocr"), host.ran)
        assertTrue(window.popovers.isShowing)

        tap(rowWith("Open PlayTranslate"))
        assertEquals(listOf("overlays", "ocr", "open"), host.ran)
        assertFalse(window.popovers.isShowing)
    }

    @Test fun `Text size opens the page's picker in the menu's place`() {
        show()
        val page = attach(result = true)
        val picker = object : PopoverContent {
            override fun createView(ctx: Context, host: PopoverHost): View = TextView(ctx).apply { text = "the picker" }
            override fun cardWidth(ctx: Context, natural: Int): Int = 200
        }
        page.textSize.onClick = { anchor -> page.popoverPresenter!!.invoke(picker, anchor) }
        tap(window.bar.moreButton)

        tap(rowWith("Text size"))

        assertTrue(window.popovers.isShowing)
        assertTrue(window.popovers.content === picker)
        assertEquals("pointing at the menu button", window.bar.moreButton, window.popovers.anchor)
        assertTrue(hasRow("the picker"))
    }

    @Test fun `a bar too narrow for Auto lists it in the menu's Capture group, where it still runs`() {
        show(Frame(400, 200, 150, 400))
        attach(result = true)
        val auto = ctx.getString(R.string.live_mode_auto_label)
        assertEquals(listOf(auto), window.bar.foldedActions().map { it.label.toString() })

        tap(window.bar.moreButton)
        val listed = texts()
        assertEquals(
            "first under Capture",
            listed.indexOf(ctx.getString(R.string.detached_menu_group_capture)) + 1,
            listed.indexOf(auto),
        )

        tap(rowWith(auto))
        assertEquals(1, host.autos)
        assertFalse(window.popovers.isShowing)
    }

    @Test fun `with no page the menu is the capture's rows and the app's, with no empty heading`() {
        show()

        tap(window.bar.moreButton)

        assertEquals(
            listOf(
                ctx.getString(R.string.detached_menu_group_capture),
                editRegion, "OCR", "ML Kit", "Overlays", "Translation",
                ctx.getString(R.string.app_name),
                "Open PlayTranslate", turnOff,
            ),
            texts(),
        )
    }

    @Test fun `the menu button closes the menu it opened`() {
        show()
        tap(window.bar.moreButton)
        assertTrue(window.popovers.isShowing)

        tap(window.bar.moreButton)

        assertFalse(window.popovers.isShowing)
    }

    // ── The bar's shortcuts ──────────────────────────────────────────────

    @Test fun `wide and with a result, Anki and Read aloud stand on the bar by name, a tap running each and a hold sending the card`() {
        show(Frame(100, 200, 1000, 400))
        val page = attach(result = false)
        var spoke: View? = null
        page.speak.onClick = { spoke = it }
        assertEquals("nothing to make a card of", View.GONE, bar(R.id.detachedBarAnki).visibility)
        assertEquals(View.GONE, bar(R.id.btnSpeakOriginal).visibility)

        page.resultOnShow = true
        layOut()
        assertEquals(View.VISIBLE, bar(R.id.detachedBarAnki).visibility)
        assertEquals(View.VISIBLE, bar(R.id.btnSpeakOriginal).visibility)
        assertTrue(find(bar(R.id.detachedBarAnki), ctx.getString(R.string.detached_bar_anki)) != null)
        assertTrue(find(bar(R.id.btnSpeakOriginal), "Read aloud") != null)

        bar(R.id.detachedBarAnki).performClick()
        assertEquals(1, page.adds)
        bar(R.id.detachedBarAnki).performLongClick()
        assertEquals(1, page.oneTaps)
        assertEquals("the hold is not a tap", 1, page.adds)
        bar(R.id.btnSpeakOriginal).performClick()
        assertEquals("run with its own button as the anchor", bar(R.id.btnSpeakOriginal), spoke)

        tap(window.bar.moreButton)
        assertTrue("a shortcut is a second way: the menu lists both all the same", hasRow(addToAnki) && hasRow("Read aloud"))
        tapOutside()

        page.resultOnShow = false
        layOut()
        assertEquals(View.GONE, bar(R.id.detachedBarAnki).visibility)
        assertEquals(View.GONE, bar(R.id.btnSpeakOriginal).visibility)
    }

    @Test fun `at the default width there is no room for both shortcuts, and the menu still has them`() {
        show(Frame(400, 200, 300, 400))
        attach(result = true)

        assertEquals(View.GONE, bar(R.id.btnSpeakOriginal).visibility)
        assertEquals(View.VISIBLE, bar(R.id.detachedBarCapture).visibility)
        assertEquals(View.VISIBLE, bar(R.id.detachedBarAuto).visibility)

        tap(window.bar.moreButton)
        assertTrue(rowWith("Read aloud").isEnabled)
    }

    @Test fun `the page's own popovers open in the window's, and a page taken out is unhooked`() {
        show()
        val page = attach(result = false)
        val content = object : PopoverContent {
            override fun createView(ctx: Context, host: PopoverHost): View = TextView(ctx).apply { text = "Text size" }
            override fun cardWidth(ctx: Context, natural: Int): Int = 200
        }

        page.popoverPresenter!!.invoke(content, window.bar.moreButton)
        assertTrue(window.popovers.isShowing)
        assertTrue(hasRow("Text size"))

        window.attachPage(null)
        assertFalse("its popover goes with it", window.popovers.isShowing)
        assertTrue(page.popoverPresenter == null)
        assertTrue(page.onResultOnShowChanged == null)
        assertTrue("no longer followed", page.listeners.isEmpty())
        assertTrue("its Read aloud leaves the bar", window.root.findViewById<View>(R.id.btnSpeakOriginal) == null)
        page.resultOnShow = true
        layOut()
        assertEquals(View.GONE, bar(R.id.detachedBarAnki).visibility)
    }

    // ── What the actions say ─────────────────────────────────────────────

    @Test fun `Capture names the screen or the region, on the bar and on the button`() {
        show()
        val button = window.root.findViewById<View>(R.id.detachedCaptureButton)
        assertEquals("Capture screen", bar(R.id.detachedBarCapture).contentDescription.toString())
        assertEquals("Capture\nScreen", button.contentDescription.toString())

        host.fullScreen = false
        window.refreshActions()

        assertEquals("Capture region", bar(R.id.detachedBarCapture).contentDescription.toString())
        assertEquals("Capture\nRegion", button.contentDescription.toString())
    }

    @Test fun `the menu's last row says what it would do, and the bar's one window control is Minimize`() {
        show()
        tap(window.bar.moreButton)
        assertEquals(turnOff, texts().last())
        tapOutside()

        host.turnsOff = false
        window.refreshActions()
        tap(window.bar.moreButton)
        assertEquals(ctx.getString(R.string.floating_icon_close_label_hide), texts().last())
        tapOutside()

        assertEquals("the first thing on the bar", 0, window.bar.indexOfChild(bar(R.id.detachedBarMinimize)))
        tap(bar(R.id.detachedBarMinimize))
        assertEquals(1, host.minimizes)
        assertEquals("and it asks to turn nothing off", 0, host.exits)
    }
}
