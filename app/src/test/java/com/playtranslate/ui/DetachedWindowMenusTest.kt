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

    /** Every row of the open popover that shows [text], by its click target. */
    private fun rowWith(text: String): View {
        fun find(v: View): View? {
            if (v is TextView && v.text.toString() == text) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        var v: View = find(window.root) ?: error("no row \"$text\"")
        while (!v.hasOnClickListeners()) v = v.parent as View
        return v
    }

    // ── Quick settings ───────────────────────────────────────────────────

    @Test fun `Settings opens the quick settings' rows, and a second tap closes them`() {
        show()

        tap(bar(R.id.detachedBarSettings))
        assertTrue(window.popovers.isShowing)
        rowWith("Language")
        rowWith("Overlays")
        rowWith("Open PlayTranslate")

        tap(bar(R.id.detachedBarSettings))
        assertFalse(window.popovers.isShowing)
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
        assertEquals(View.VISIBLE, window.bar.moreButton.visibility)
        val folded = window.bar.foldedActions().map { it.label.toString() }
        assertTrue("Minimize folds first", ctx.getString(R.string.detached_bar_minimize) in folded)

        tap(window.bar.moreButton)
        assertTrue(window.popovers.isShowing)
        tap(rowWith(ctx.getString(R.string.detached_bar_minimize)))

        assertEquals(1, host.minimizes)
        assertFalse(window.popovers.isShowing)
    }

    @Test fun `the more button toggles its menu`() {
        show(Frame(400, 200, 300, 400))

        tap(window.bar.moreButton)
        assertTrue(window.popovers.isShowing)
        tap(window.bar.moreButton)
        assertFalse(window.popovers.isShowing)
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
