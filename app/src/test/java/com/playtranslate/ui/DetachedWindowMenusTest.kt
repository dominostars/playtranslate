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
 * The detached window's two bar menus: the quick settings behind its
 * Settings button (the rows its host builds, the icon menu's own) and the
 * more menu that lists the actions a narrow bar folded, each row running
 * what the inline button runs. And what the bar and the capture button say
 * for the capture region and for what Exit would do.
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
        var fullScreen = true
        var turnsOff = true
        val ran = mutableListOf<String>()
        var leave: (() -> Unit)? = null
        override fun onCapture(displayId: Int) {}
        override fun onToggleAuto(displayId: Int) {}
        override fun onEditRegion(displayId: Int) { regions++ }
        override fun onMinimize(displayId: Int) { minimizes++ }
        override fun onExit(displayId: Int) {}
        override fun onDocked(displayId: Int, side: DetachedWindowGeometry.Side, fingerY: Int) {}
        override fun onTouched(displayId: Int) {}
        override fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting> {
            this.leave = leave
            return listOf(
                QuickSetting(QuickSetting.Id.LANGUAGE, "Language", "Japanese") { ran += "language"; leave() },
                QuickSetting(QuickSetting.Id.OVERLAYS, "Overlays", "Translation") { row ->
                    ran += "overlays"
                    row.value = "Furigana"
                },
                QuickSetting(QuickSetting.Id.OPEN_APP, "Open PlayTranslate", icon = R.drawable.ic_open_in_new) {
                    ran += "open"
                    leave()
                },
            )
        }
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

    /** What stands in for the results page. */
    private class FakePage(ctx: Context) : DetachedWindow.Page {
        override val view: View = View(ctx)
        val speak = HeaderAction(R.id.btnSpeakOriginal, R.drawable.ic_lens_speak, "Read aloud")
        val language = HeaderAction(R.id.detachedMenuGameLanguage, R.drawable.ic_translate, "Game Language")
            .apply { detail = "Japanese" }
        var groups: List<List<HeaderAction>> = listOf(listOf(speak), listOf(language))
        val listeners = mutableListOf<FoldingActionRow.Listener>()
        var adds = 0
        var oneTaps = 0

        override fun menuGroups() = groups
        override fun addMenuListener(listener: FoldingActionRow.Listener) { listeners += listener }
        override fun removeMenuListener(listener: FoldingActionRow.Listener) { listeners -= listener }
        override var ankiAvailable = false
            set(value) {
                field = value
                onAnkiAvailableChanged?.invoke()
            }
        override var onAnkiAvailableChanged: (() -> Unit)? = null
        override fun addToAnki() { adds++ }
        override fun ankiOneTap() { oneTaps++ }
        override var popoverPresenter: ((PopoverContent, View) -> Unit)? = null
    }

    // ── Quick settings ───────────────────────────────────────────────────

    @Test fun `Settings opens the quick settings' rows, and a tap off them closes them`() {
        show()

        tap(bar(R.id.detachedBarSettings))
        assertTrue(window.popovers.isShowing)
        rowWith("Language")
        rowWith("Overlays")
        rowWith("Open PlayTranslate")

        tapOutside()
        assertFalse(window.popovers.isShowing)
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

    @Test fun `a row runs its action, and one that leaves closes the popover`() {
        show()
        tap(bar(R.id.detachedBarSettings))

        tap(rowWith("Overlays"))
        assertEquals(listOf("overlays"), host.ran)
        assertTrue("a row that changes a value stays open", window.popovers.isShowing)
        rowWith("Furigana")

        tap(rowWith("Open PlayTranslate"))
        assertEquals(listOf("overlays", "open"), host.ran)
        assertFalse(window.popovers.isShowing)
    }

    // ── The more menu ────────────────────────────────────────────────────

    @Test fun `a narrow bar folds its lesser actions into the more menu, where they still run`() {
        // Narrow enough that Minimize and Settings fold (the bar's own fold
        // table is DetachedWindowBarTest).
        show(Frame(400, 200, 300, 400))
        val folded = window.bar.foldedActions().map { it.label.toString() }
        assertTrue("Minimize folds first", ctx.getString(R.string.detached_bar_minimize) in folded)

        tap(window.bar.moreButton)
        assertTrue(window.popovers.isShowing)
        tap(rowWith(ctx.getString(R.string.detached_bar_minimize)))

        assertEquals(1, host.minimizes)
        assertFalse(window.popovers.isShowing)
    }

    @Test fun `the more menu lists the bar's folded actions, then the page's own, each group ruled off`() {
        show(Frame(400, 200, 300, 400))
        val page = FakePage(ctx)
        window.attachPage(page)
        layOut()
        var spoke: View? = null
        page.speak.onClick = { spoke = it }

        tap(window.bar.moreButton)

        assertTrue(hasRow(ctx.getString(R.string.detached_bar_minimize)))
        assertTrue(hasRow("Read aloud"))
        assertTrue(hasRow("Game Language"))
        assertTrue("a row's value at its end", hasRow("Japanese"))
        assertEquals("the menu follows the page's actions", 1, page.listeners.size)

        tap(rowWith("Read aloud"))
        assertEquals("run with the more button as its anchor", window.bar.moreButton, spoke)
        assertFalse(window.popovers.isShowing)
        assertTrue("and no longer followed", page.listeners.isEmpty())
    }

    @Test fun `with every bar action in view the more button still opens the page's actions`() {
        show(Frame(100, 200, 1000, 400))
        window.attachPage(FakePage(ctx))
        layOut()
        assertTrue(window.bar.foldedActions().isEmpty())
        assertEquals(View.VISIBLE, window.bar.moreButton.visibility)

        tap(window.bar.moreButton)

        assertTrue(hasRow("Read aloud"))
        assertFalse(hasRow(ctx.getString(R.string.detached_bar_minimize)))
    }

    // ── The page's parts on the bar ──────────────────────────────────────

    @Test fun `Anki stands on the bar while the page has a result, a tap opening the review and a hold sending the card`() {
        show(Frame(100, 200, 1000, 400))
        val page = FakePage(ctx)
        window.attachPage(page)
        layOut()
        assertEquals("nothing to make a card of", View.GONE, bar(R.id.detachedBarAnki).visibility)

        page.ankiAvailable = true
        layOut()
        assertEquals(View.VISIBLE, bar(R.id.detachedBarAnki).visibility)

        bar(R.id.detachedBarAnki).performClick()
        assertEquals(1, page.adds)
        bar(R.id.detachedBarAnki).performLongClick()
        assertEquals(1, page.oneTaps)
        assertEquals("the hold is not a tap", 1, page.adds)

        page.ankiAvailable = false
        layOut()
        assertEquals(View.GONE, bar(R.id.detachedBarAnki).visibility)
    }

    @Test fun `the page's own popovers open in the window's, and a page taken out is unhooked`() {
        show()
        val page = FakePage(ctx)
        window.attachPage(page)
        layOut()
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
        assertTrue(page.onAnkiAvailableChanged == null)
        page.ankiAvailable = true
        layOut()
        assertEquals(View.GONE, bar(R.id.detachedBarAnki).visibility)
    }

    // ── What the actions say ─────────────────────────────────────────────

    @Test fun `Capture names the screen or the region, on the bar and on the button`() {
        show()
        val button = window.root.findViewById<View>(R.id.detachedCaptureButton)
        assertEquals("Capture Screen", bar(R.id.detachedBarCapture).contentDescription.toString())
        assertEquals("Capture\nScreen", button.contentDescription.toString())

        host.fullScreen = false
        window.refreshActions()

        assertEquals("Capture Region", bar(R.id.detachedBarCapture).contentDescription.toString())
        assertEquals("Capture\nRegion", button.contentDescription.toString())
    }

    @Test fun `Exit says what it would do`() {
        show()
        assertEquals(ctx.getString(R.string.floating_icon_close_label_turn_off), bar(R.id.detachedBarExit).contentDescription.toString())

        host.turnsOff = false
        window.refreshActions()

        assertEquals(ctx.getString(R.string.floating_icon_close_label_hide), bar(R.id.detachedBarExit).contentDescription.toString())
    }
}
