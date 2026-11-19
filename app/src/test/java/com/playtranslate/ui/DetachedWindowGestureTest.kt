package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.WindowBounds
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.testutil.RecordingWindowManager
import com.playtranslate.testutil.testEnvironment
import com.playtranslate.ui.DetachedWindowGeometry.Frame
import com.playtranslate.ui.DetachedWindowGeometry.Side
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The detached window's gestures on a real window: what moves it (the bar,
 * a very clear sideways drag in the page, any drag of the capture button),
 * what does not (a vertical drag, a drag from something that pans sideways
 * itself), a resize from each bottom corner and its switch between page and
 * button, the dock (armed after a second at a side, disarmed on leaving it),
 * and that a layout pass or a display change during a gesture does what the
 * class says.
 *
 * The display is 1280 x 800 at density 1 with no bars; the window starts at
 * (400, 200), 420 x 400, so its page area is real. Touches are given in
 * display coordinates and placed in the window's own, as the platform does.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedWindowGestureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private val g = DetachedWindowGeometry
    private val room = g.room(1280, 800, com.playtranslate.overlay.Sides.NONE)

    private lateinit var wm: RecordingWindowManager
    private lateinit var overlayHost: OverlayHost
    private lateinit var window: DetachedWindow
    private val host = FakeHost()

    private class FakeHost : DetachedWindow.Host {
        var captures = 0
        var minimizes = 0
        var exits = 0
        var touches = 0
        val docked = mutableListOf<Pair<Side, Int>>()
        override fun onCapture(displayId: Int) { captures++ }
        override fun onToggleAuto(displayId: Int) {}
        override fun onEditRegion(displayId: Int) {}
        override fun onMinimize(displayId: Int) { minimizes++ }
        override fun onExit(displayId: Int) { exits++ }
        override fun onDocked(displayId: Int, side: Side, fingerY: Int) { docked += side to fingerY }
        override fun onTouched(displayId: Int) { touches++ }
        override fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting> = emptyList()
        override fun regionIsFullScreen(displayId: Int) = true
        override fun exitTurnsOff() = true
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setUp() {
        clearPrefs()
    }

    @After fun tearDown() {
        if (::window.isInitialized) window.remove()
        // Let the churn gate finish the removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** The window up, resting on [frame]. */
    private fun show(frame: Frame = Frame(400, 200, 420, 400)) {
        prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(frame, room, 1f))
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        overlayHost.environmentReader = { testEnvironment(1280, 800) }
        window = DetachedWindow(displayCtx, wm, d0, overlayHost, prefs, host)
        assertTrue(window.show())
        page = View(ctx)
        window.setPage(page)
        layOut()
        assertEquals("the window starts on its saved frame", frame, frame())
    }

    /** The view standing in for the results page, under the bar. */
    private lateinit var page: View

    private fun params() = window.root.layoutParams as WindowManager.LayoutParams

    /** The rim around the card: half the handles' stroke. */
    private val rim = 2

    /** The card's rect on the display, from the window's params. */
    private fun frame(): Frame {
        val p = params()
        return Frame(p.x + rim, p.y + rim, p.width - 2 * rim, p.height - 2 * rim)
    }

    /** Give the root the size its params say, as the window manager would. */
    private fun layOut() {
        val p = params()
        window.root.measure(
            View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY),
        )
        window.root.layout(0, 0, p.width, p.height)
    }

    private var downTime = 0L

    /** A touch at display point ([rawX], [rawY]), in the window's own
     *  coordinates as it stood when the gesture went down (the platform
     *  offsets by the window's origin at dispatch; [origin] stands in). */
    private fun touch(action: Int, rawX: Float, rawY: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val p = params()
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        ev.setLocation(rawX - p.x, rawY - p.y)
        assertEquals("the touch keeps its display coordinates", rawX, ev.rawX, 0.01f)
        window.root.dispatchTouchEvent(ev)
        ev.recycle()
        // A view posts its click from the release.
        if (action == MotionEvent.ACTION_UP) idle()
        layOut()
    }

    private fun down(x: Float, y: Float) = touch(MotionEvent.ACTION_DOWN, x, y)
    private fun move(x: Float, y: Float) = touch(MotionEvent.ACTION_MOVE, x, y)
    private fun up(x: Float, y: Float) = touch(MotionEvent.ACTION_UP, x, y)

    /** A drag from one display point to another, in two steps, and its release. */
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        down(fromX, fromY)
        move((fromX + toX) / 2, (fromY + toY) / 2)
        move(toX, toY)
        up(toX, toY)
    }

    private fun savedFrame(): Frame =
        g.fromBounds(prefs.detachedStateForDisplay(d0).bounds(landscape = true)!!, room, 1f)

    // A point on the bar (not on a button: the gap between Minimize and
    // Settings), and one in the page below it.
    private val barX = 560f
    private val barY = 220f
    private val pageX = 600f
    private val pageY = 400f

    // ── Moving ───────────────────────────────────────────────────────────

    @Test fun `a drag of the bar moves the window and saves where it rests`() {
        show()

        drag(barX, barY, barX + 100, barY + 60)

        assertEquals(Frame(500, 260, 420, 400), frame())
        assertEquals(Frame(500, 260, 420, 400), savedFrame())
        assertEquals("the touch was reported once", 1, host.touches)
    }

    @Test fun `a press on a bar button that becomes a drag moves the window and presses nothing`() {
        show()
        // Exit never folds, so it is on the bar at any width.
        val exit = window.root.findViewById<View>(R.id.detachedBarExit)
        assertEquals(View.VISIBLE, exit.visibility)
        val at = IntArray(2).also { exit.getLocationInWindow(it) }
        val x = params().x + at[0] + exit.width / 2f
        val y = params().y + at[1] + exit.height / 2f

        drag(x, y, x + 80, y + 40)
        assertEquals(0, host.exits)
        assertEquals(Frame(480, 240, 420, 400), frame())

        // A plain tap on it still presses.
        val p = params()
        down(p.x + at[0] + exit.width / 2f, p.y + at[1] + exit.height / 2f)
        up(p.x + at[0] + exit.width / 2f, p.y + at[1] + exit.height / 2f)
        assertEquals(1, host.exits)
    }

    @Test fun `a very clear sideways drag in the page moves the window`() {
        show()

        drag(pageX, pageY, pageX + 120, pageY + 10)

        assertEquals(Frame(520, 210, 420, 400), frame())
    }

    @Test fun `a vertical or a diagonal drag in the page leaves the window where it is`() {
        show()
        val start = frame()

        drag(pageX, pageY, pageX + 6, pageY + 150)
        assertEquals("vertical", start, frame())

        drag(pageX, pageY, pageX + 100, pageY + 100)
        assertEquals("diagonal", start, frame())
        assertEquals(0, wm.updatesOf(window.root))
    }

    @Test fun `a sideways drag from a text field leaves the window where it is`() {
        show()
        val field = EditText(ctx)
        window.setPage(FrameLayout(ctx).apply { addView(field, FrameLayout.LayoutParams(-1, -1)) })
        layOut()
        val start = frame()

        drag(pageX, pageY, pageX + 150, pageY)

        assertEquals(start, frame())
    }

    @Test fun `a text field and the Words list's page keep their own sideways drags, plain views do not`() {
        // The arbiter's side is the text field's test above; this is which
        // views it leaves alone. (A WebView takes no size in a test layout.)
        assertTrue(DetachedWindow.keepsSidewaysDrags(EditText(ctx)))
        assertTrue(DetachedWindow.keepsSidewaysDrags(android.webkit.WebView(ctx)))
        assertFalse(DetachedWindow.keepsSidewaysDrags(View(ctx)))
        assertFalse(DetachedWindow.keepsSidewaysDrags(android.widget.TextView(ctx)))
        assertFalse(DetachedWindow.keepsSidewaysDrags(FrameLayout(ctx)))
    }

    @Test fun `a child that keeps its own sideways drag keeps it`() {
        show()
        // A slider's rule: it asks its parents not to intercept as it goes down.
        val slider = object : View(ctx) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        window.setPage(slider)
        layOut()
        val start = frame()

        drag(pageX, pageY, pageX + 150, pageY)

        assertEquals(start, frame())
    }

    // ── The capture button ───────────────────────────────────────────────

    @Test fun `narrower than the page the card is the capture button, a tap captures and a drag moves`() {
        show(Frame(400, 200, 120, 120))
        assertFalse(window.showsPage)

        down(460f, 260f)
        up(460f, 260f)
        assertEquals(1, host.captures)

        drag(460f, 260f, 560f, 360f)
        assertEquals("the drag captured nothing", 1, host.captures)
        assertEquals(Frame(500, 300, 120, 120), frame())
    }

    // ── Resizing ─────────────────────────────────────────────────────────

    @Test fun `the bottom right corner sizes the window from its right and bottom edges`() {
        show()

        drag(815f, 595f, 875f, 645f)

        assertEquals(Frame(400, 200, 480, 450), frame())
        assertEquals(Frame(400, 200, 480, 450), savedFrame())
    }

    @Test fun `the bottom left corner sizes it from its left and bottom edges`() {
        show()

        drag(405f, 595f, 345f, 645f)

        assertEquals(Frame(340, 200, 480, 450), frame())
        assertEquals(Frame(340, 200, 480, 450), savedFrame())
    }

    /** The room under the bar and its hairline for a card [cardHeight] tall. */
    private fun pageRoom(cardHeight: Int): Int = cardHeight - window.bar.height - 1

    @Test fun `while a corner is dragged the bar follows the window, and the page keeps its layout until release`() {
        show()
        assertEquals(420, window.bar.width)
        assertEquals(420, page.width)
        assertEquals(pageRoom(400), page.height)

        down(815f, 595f)
        move(875f, 645f)
        assertEquals("the window follows the finger", Frame(400, 200, 480, 450), frame())
        assertEquals("and the bar with it", 480, window.bar.width)
        assertEquals("the page does not", 420, page.width)
        assertEquals(pageRoom(400), page.height)
        assertEquals("pinned to the corner that stays, the left", 0, page.left)

        up(875f, 645f)
        assertEquals("laid out once the finger lifts", 480, page.width)
        assertEquals(pageRoom(450), page.height)
    }

    @Test fun `from the left corner the page stays with the right edge`() {
        show()

        down(405f, 595f)
        move(345f, 645f)
        assertEquals(Frame(340, 200, 480, 450), frame())
        assertEquals(480, window.bar.width)
        assertEquals(420, page.width)
        assertEquals("60 px of new card to its left", 60, page.left)

        up(345f, 645f)
        assertEquals(480, page.width)
        assertEquals(0, page.left)
    }

    @Test fun `the bar folds and unfolds its actions as a corner is dragged, before the finger lifts`() {
        show(Frame(100, 200, 1000, 400))
        assertTrue("room for every action", window.bar.foldedActions().isEmpty())

        down(1095f, 595f)
        move(295f, 595f)
        assertEquals(Frame(100, 200, 200, 400), frame())
        assertTrue("narrow: folded while the finger is down", window.bar.foldedActions().isNotEmpty())
        assertEquals(View.VISIBLE, window.bar.moreButton.visibility)

        move(1095f, 595f)
        assertTrue("wide again: unfolded", window.bar.foldedActions().isEmpty())
        up(1095f, 595f)
    }

    @Test fun `the dock's hold is one second`() {
        assertEquals(1000L, DetachedWindow.DOCK_HOLD_MS)
    }

    @Test fun `crossing the page width switches to the button at once, and back`() {
        show()
        assertTrue(window.showsPage)

        down(815f, 595f)
        move(600f, 595f)
        assertTrue("205 wide is still the page", window.showsPage)
        move(525f, 595f)
        assertFalse("narrowed past 150", window.showsPage)
        move(815f, 595f)
        assertTrue("widened again", window.showsPage)
        move(525f, 595f)
        up(525f, 595f)

        assertFalse(window.showsPage)
        assertEquals(Frame(400, 200, 130, 400), frame())
    }

    @Test fun `widened from the capture button the page shows at once, laid out for the room it appeared in`() {
        show(Frame(400, 200, 120, 120))
        assertFalse(window.showsPage)
        assertEquals("under the button the page has no layout", 0, page.width)
        assertEquals(0, window.bar.width)

        down(515f, 315f)
        move(645f, 315f)
        assertTrue(window.showsPage)
        assertEquals(Frame(400, 200, 250, 120), frame())
        assertEquals("laid out as it comes into view", 250, page.width)
        assertEquals("in the room under the bar", pageRoom(120), page.height)

        move(745f, 315f)
        assertEquals(Frame(400, 200, 350, 120), frame())
        assertEquals("the bar goes on with the window", 350, window.bar.width)
        assertEquals("the page is kept while the corner is dragged on", 250, page.width)

        up(745f, 315f)
        assertEquals(350, page.width)
    }

    @Test fun `narrowed to the button and widened again in one drag, the page is laid out for the width it comes back at`() {
        show()
        assertEquals(420, page.width)

        down(815f, 595f)
        move(525f, 595f)
        assertFalse(window.showsPage)
        move(615f, 595f)
        assertTrue(window.showsPage)
        assertEquals("not the 420 it had before the button", 220, page.width)
        up(615f, 595f)
    }

    // ── The dock ─────────────────────────────────────────────────────────

    @Test fun `a finger kept at a side for a second arms the dock, and the release docks there`() {
        show()

        down(barX, barY)
        move(300f, barY)
        move(10f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        up(10f, 300f)

        assertEquals(listOf(Side.LEFT to 300), host.docked)
    }

    @Test fun `the right side docks to the right`() {
        show()

        down(barX, barY)
        move(900f, barY)
        move(1275f, 500f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        up(1275f, 500f)

        assertEquals(listOf(Side.RIGHT to 500), host.docked)
    }

    @Test fun `a release before the second has passed only moves the window`() {
        show()

        down(barX, barY)
        move(300f, barY)
        move(10f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS - 1)
        up(10f, 300f)

        assertTrue(host.docked.isEmpty())
        assertEquals("it rests at the side", 0, frame().x)
    }

    @Test fun `leaving the side disarms the dock`() {
        show()

        down(barX, barY)
        move(10f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        move(200f, 300f)
        up(200f, 300f)

        assertTrue(host.docked.isEmpty())
        assertEquals(frame(), savedFrame())
    }

    @Test fun `the armed dock dims the card, and leaving the side restores it`() {
        show()
        val card = window.root.findViewById<View>(R.id.detachedCard)

        down(barX, barY)
        move(300f, barY)
        move(10f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        assertTrue("armed: dimmed", card.alpha < 1f)

        move(200f, 300f)
        assertEquals("off the side: back", 1f, card.alpha, 0f)
        up(200f, 300f)
    }

    @Test fun `armed at one side, a release at the other before its own second does not dock`() {
        show()

        down(barX, barY)
        move(300f, barY)
        move(10f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        move(640f, 300f)
        move(1275f, 300f)
        idle(DetachedWindow.DOCK_HOLD_MS - 1)
        up(1275f, 300f)

        assertTrue(host.docked.isEmpty())
    }

    @Test fun `the second starts over each time the finger comes back to the side`() {
        show()

        down(barX, barY)
        move(300f, barY)
        move(10f, 300f)
        idle(600)
        move(200f, 300f)
        idle(100)
        move(10f, 300f)
        // 1.2 s since it first reached the side, half a second since it came back.
        idle(500)
        up(10f, 300f)

        assertTrue(host.docked.isEmpty())
    }

    @Test fun `the second counts from reaching the side, not from the start of the drag`() {
        show()

        down(barX, barY)
        move(300f, barY)
        idle(DetachedWindow.DOCK_HOLD_MS)
        move(10f, 300f)
        up(10f, 300f)

        assertTrue(host.docked.isEmpty())
    }

    @Test fun `a resize at a side never docks`() {
        show(Frame(0, 200, 420, 400))

        down(5f, 595f)
        move(8f, 600f)
        idle(DetachedWindow.DOCK_HOLD_MS)
        up(8f, 600f)

        assertTrue(host.docked.isEmpty())
    }

    // ── Layout passes and display changes ────────────────────────────────

    @Test fun `a layout pass during a drag leaves the window under the finger`() {
        show()

        down(barX, barY)
        move(barX + 60, barY)
        move(barX + 120, barY + 40)
        overlayHost.invalidateLayout(d0)
        layOut()

        assertEquals(Frame(520, 240, 420, 400), frame())
        up(barX + 120, barY + 40)
        assertEquals(Frame(520, 240, 420, 400), frame())
    }

    @Test fun `a gesture ended from outside puts the window back at rest and the rest of the touch does nothing`() {
        show()

        down(barX, barY)
        move(barX + 60, barY)
        move(barX + 120, barY + 40)
        window.endGesture()
        overlayHost.onDisplayChanged(d0)
        layOut()
        assertEquals("back on its saved frame", Frame(400, 200, 420, 400), frame())

        move(barX + 200, barY + 90)
        up(barX + 200, barY + 90)
        assertEquals(Frame(400, 200, 420, 400), frame())
        assertEquals(Frame(400, 200, 420, 400), savedFrame())
    }

    @Test fun `the rest place is the saved bounds of the display's orientation`() {
        prefs.setWindowBounds(d0, landscape = false, bounds = WindowBounds(0f, 0f, 320f, 300f))
        show()

        overlayHost.environmentReader = { testEnvironment(800, 1280) }
        overlayHost.onDisplayChanged(d0)
        layOut()

        assertEquals(Frame(0, 0, 320, 300), frame())
        assertNull("nothing was docked", host.docked.firstOrNull())
    }
}
