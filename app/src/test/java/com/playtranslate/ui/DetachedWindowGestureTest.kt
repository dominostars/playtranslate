package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
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
import com.playtranslate.ui.DetachedWindowGeometry.Corner
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
import org.robolectric.shadows.ShadowChoreographer
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
        /** What the controller does with a dock, for a test that needs it. */
        var onDockedDo: (() -> Unit)? = null
        override fun onDocked(displayId: Int, side: Side, fingerY: Int) {
            docked += side to fingerY
            onDockedDo?.invoke()
        }
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
        // A test that paced its frames ([paceFrames]) hands the runtime's
        // own pacing back before what is left of its windows is drained.
        ShadowChoreographer.setPaused(false)
        if (::window.isInitialized) window.remove()
        // Let the churn gate finish the removal inside this test.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    /** The window up, resting on [frame]; with [from], flying there out of
     *  that rect. */
    private fun show(frame: Frame = Frame(400, 200, 420, 400), from: Rect? = null) {
        prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(frame, room, 1f))
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        overlayHost.environmentReader = { testEnvironment(1280, 800) }
        window = DetachedWindow(displayCtx, wm, d0, overlayHost, prefs, host)
        assertTrue(window.show(from))
        page = View(ctx)
        window.setPage(page)
        layOut()
        if (from == null) assertEquals("the window starts on its saved frame", frame, frame())
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

    /** Where the gesture under way went down: the window's root, or the
     *  corner pad above it that the point fell in, with that window's
     *  params. A gesture stays with the window it went down on. */
    private var target: Pair<View, WindowManager.LayoutParams>? = null

    private fun windowAt(rawX: Float, rawY: Float): Pair<View, WindowManager.LayoutParams> =
        window.handlePadsForTest().firstOrNull { (_, p) ->
            rawX >= p.x && rawX < p.x + p.width && rawY >= p.y && rawY < p.y + p.height
        } ?: (window.root to params())

    /** A touch at display point ([rawX], [rawY]), in the coordinates of the
     *  window it went down on (the platform offsets by that window's origin
     *  at dispatch): a corner's touch pad lies above the window, so a point
     *  in one goes to it. */
    private fun touch(action: Int, rawX: Float, rawY: Float) {
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = SystemClock.uptimeMillis()
            target = windowAt(rawX, rawY)
        }
        val (view, p) = target!!
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rawX, rawY, 0)
        ev.setLocation(rawX - p.x, rawY - p.y)
        assertEquals("the touch keeps its display coordinates", rawX, ev.rawX, 0.01f)
        view.dispatchTouchEvent(ev)
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
        // Minimize always stands, so it is on the bar at any width.
        val button = window.root.findViewById<View>(R.id.detachedBarMinimize)
        assertEquals(View.VISIBLE, button.visibility)
        val at = IntArray(2).also { button.getLocationInWindow(it) }
        val x = params().x + at[0] + button.width / 2f
        val y = params().y + at[1] + button.height / 2f

        drag(x, y, x + 80, y + 40)
        assertEquals(0, host.minimizes)
        assertEquals(Frame(480, 240, 420, 400), frame())

        // A plain tap on it still presses.
        val p = params()
        down(p.x + at[0] + button.width / 2f, p.y + at[1] + button.height / 2f)
        up(p.x + at[0] + button.width / 2f, p.y + at[1] + button.height / 2f)
        assertEquals(1, host.minimizes)
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

    /** A corner pad's rect on the display. */
    private fun padRect(corner: Corner): android.graphics.Rect {
        val (_, p) = window.handlePadsForTest()[if (corner == Corner.BOTTOM_LEFT) 0 else 1]
        return android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
    }

    @Test fun `each corner's touch target reaches 24 dp past the card and 34 dp into it`() {
        show()
        // The card: 400..820 across, 200..600 down.
        assertEquals(android.graphics.Rect(376, 566, 434, 624), padRect(Corner.BOTTOM_LEFT))
        assertEquals(android.graphics.Rect(786, 566, 844, 624), padRect(Corner.BOTTOM_RIGHT))
    }

    @Test fun `a touch past the card's corner resizes, and one further inside than the target does not`() {
        show()

        // 20 px outside the bottom right corner, on the game.
        drag(840f, 620f, 900f, 670f)
        assertEquals(Frame(400, 200, 480, 450), frame())

        // 40 px inside the new corner: the page's, not the handle's.
        drag(840f, 610f, 840f, 560f)
        assertEquals("a vertical drag in the page moves and sizes nothing", Frame(400, 200, 480, 450), frame())
    }

    @Test fun `the touch targets follow the window when a move or a resize ends`() {
        show()

        drag(barX, barY, barX + 100, barY + 50)
        assertEquals(Frame(500, 250, 420, 400), frame())
        assertEquals(android.graphics.Rect(886, 616, 944, 674), padRect(Corner.BOTTOM_RIGHT))
        assertEquals(android.graphics.Rect(476, 616, 534, 674), padRect(Corner.BOTTOM_LEFT))

        drag(915f, 645f, 975f, 695f)
        assertEquals(Frame(500, 250, 480, 450), frame())
        assertEquals(android.graphics.Rect(946, 666, 1004, 724), padRect(Corner.BOTTOM_RIGHT))
    }

    @Test fun `while a corner is being dragged a second finger on the window does nothing`() {
        show()

        down(815f, 595f)
        move(875f, 645f)
        val resized = frame()
        // A second touch goes down on the bar and drags.
        val ev = { action: Int, x: Float, y: Float ->
            val p = params()
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            e.setLocation(x - p.x, y - p.y)
            window.root.dispatchTouchEvent(e)
            e.recycle()
        }
        ev(MotionEvent.ACTION_DOWN, barX, barY)
        ev(MotionEvent.ACTION_MOVE, barX + 200, barY + 100)
        ev(MotionEvent.ACTION_UP, barX + 200, barY + 100)
        idle()
        assertEquals(resized, frame())

        up(875f, 645f)
        assertEquals(Frame(400, 200, 480, 450), frame())
    }

    @Test fun `the bottom left corner sizes it from its left and bottom edges`() {
        show()

        drag(405f, 595f, 345f, 645f)

        assertEquals(Frame(340, 200, 480, 450), frame())
        assertEquals(Frame(340, 200, 480, 450), savedFrame())
    }

    /** The room under the bar and its hairline for a card [cardHeight] tall. */
    private fun pageRoom(cardHeight: Int): Int = cardHeight - window.bar.height - 1

    @Test fun `while a corner is dragged the bar and the page follow the window, before the finger lifts`() {
        show()
        assertEquals(420, window.bar.width)
        assertEquals(420, page.width)
        assertEquals(pageRoom(400), page.height)

        down(815f, 595f)
        move(875f, 645f)
        assertEquals("the window follows the finger", Frame(400, 200, 480, 450), frame())
        assertEquals("and the bar with it", 480, window.bar.width)
        assertEquals("and the page", 480, page.width)
        assertEquals(pageRoom(450), page.height)

        move(755f, 545f)
        assertEquals(360, page.width)
        assertEquals(pageRoom(350), page.height)
        up(755f, 545f)
        assertEquals(360, page.width)
    }

    @Test fun `from the left corner the page follows too`() {
        show()

        down(405f, 595f)
        move(345f, 645f)
        assertEquals(Frame(340, 200, 480, 450), frame())
        assertEquals(480, window.bar.width)
        assertEquals(480, page.width)
        assertEquals(0, page.left)
        up(345f, 645f)
    }

    @Test fun `the bar fits itself again as a corner is dragged, before the finger lifts`() {
        show(Frame(100, 200, 1000, 400))
        assertFalse("room for Capture and Auto by name", window.bar.pillsIconOnly)

        down(1095f, 595f)
        move(295f, 595f)
        assertEquals(Frame(100, 200, 200, 400), frame())
        assertTrue("narrow: icons while the finger is down", window.bar.pillsIconOnly)
        assertTrue("both still on the bar", window.bar.foldedActions().isEmpty())
        assertEquals(View.VISIBLE, window.bar.moreButton.visibility)

        move(245f, 595f)
        assertEquals(Frame(100, 200, 150, 400), frame())
        assertEquals("narrowest: Auto folds into the menu", 1, window.bar.foldedActions().size)

        move(1095f, 595f)
        assertFalse("wide again: the names are back", window.bar.pillsIconOnly)
        assertTrue(window.bar.foldedActions().isEmpty())
        up(1095f, 595f)
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

    @Test fun `widened from the capture button the page shows at once, at the window's size`() {
        show(Frame(400, 200, 120, 120))
        assertFalse(window.showsPage)
        assertEquals("under the button the page has no layout", 0, page.width)
        assertEquals(0, window.bar.width)

        down(515f, 315f)
        move(645f, 315f)
        assertTrue(window.showsPage)
        assertEquals(Frame(400, 200, 250, 120), frame())
        assertEquals(250, page.width)
        assertEquals("in the room under the bar", pageRoom(120), page.height)

        move(745f, 315f)
        assertEquals(350, window.bar.width)
        assertEquals(350, page.width)
        up(745f, 315f)
    }

    @Test fun `narrowed to the button and widened again in one drag, the page comes back at the width it has now`() {
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

    // ── The dock: a move carried on past a side ──────────────────────────
    //
    // The bar is held 160 px from the card's left edge, so a drag to the
    // left stops the card with the finger at x = 160; the window docks 48
    // px further on, at x = 112, and comes back with the finger past x =
    // 136 (less than 24 px past the stop).

    /** The picture of the icon the window shows while it is the icon. */
    private fun ghost(): View? {
        val root = window.root
        for (i in 0 until root.childCount) {
            val holder = root.getChildAt(i) as? FrameLayout ?: continue
            if (holder.childCount == 1 && holder.getChildAt(0) is FloatingOverlayIcon) return holder
        }
        return null
    }

    private fun assertIsTheWindow(at: Frame) {
        assertEquals(at, frame())
        assertEquals("solid", 1f, card.alpha, 0f)
        assertEquals("at its own size", 1f, card.scaleX, 0f)
        assertNull("no picture of the icon", ghost())
    }

    /** The window shows as the icon whose window is centred on ([x], [y]). */
    private fun assertIsTheIcon(x: Int, y: Int) {
        assertEquals("centred on the icon's place", x to y, centre())
        assertEquals("the card is gone", 0f, card.alpha, 0f)
        assertEquals("the picture of the icon is whole", 1f, ghost()!!.alpha, 0f)
        assertEquals("the window keeps its size", 420, frame().w)
    }

    @Test fun `a move carried on past a side docks the window there, as the icon at the finger's height`() {
        paceFrames()
        show()

        down(barX, barY)
        move(300f, barY)
        move(150f, 300f)
        assertIsTheWindow(Frame(0, 280, 420, 400))

        move(100f, 300f)
        land()
        assertIsTheIcon(0, 300)

        // Along the side it is the icon under the finger.
        move(100f, 500f)
        assertIsTheIcon(0, 500)
        assertTrue("nothing is docked before the finger lifts", host.docked.isEmpty())

        up(100f, 500f)
        assertEquals(listOf(Side.LEFT to 500), host.docked)
    }

    @Test fun `dragged back out before letting go the window is the window again, and the release leaves it there`() {
        paceFrames()
        show()
        down(barX, barY)
        move(300f, barY)
        move(100f, 300f)
        land()
        assertIsTheIcon(0, 300)

        move(140f, 320f)
        land()
        assertIsTheWindow(Frame(0, 300, 420, 400))

        // And it moves as it did.
        move(260f, 320f)
        assertIsTheWindow(Frame(100, 300, 420, 400))
        up(260f, 320f)
        assertTrue(host.docked.isEmpty())
        assertEquals(Frame(100, 300, 420, 400), savedFrame())
    }

    @Test fun `between the two lines the window stays what it was`() {
        paceFrames()
        show()
        down(barX, barY)
        move(300f, barY)

        move(113f, 300f)
        land()
        assertIsTheWindow(Frame(0, 280, 420, 400))
        move(112f, 300f)
        land()
        assertIsTheIcon(0, 300)

        // Back by less than it takes to come out.
        move(136f, 300f)
        land()
        assertIsTheIcon(0, 300)
        move(137f, 300f)
        land()
        assertIsTheWindow(Frame(0, 280, 420, 400))

        // And in again by less than it takes to dock.
        move(120f, 300f)
        land()
        assertIsTheWindow(Frame(0, 280, 420, 400))
        up(120f, 300f)
        assertTrue(host.docked.isEmpty())
    }

    @Test fun `a card held near its own edge docks when the finger reaches the side`() {
        paceFrames()
        show()
        // By Minimize, 20 px from the card's left edge: the card never gets
        // 48 px past its stop before the finger is at the side.
        down(420f, barY)
        move(200f, barY)
        move(30f, 300f)
        assertIsTheWindow(Frame(10, 280, 420, 400))

        move(16f, 300f)
        land()
        assertIsTheIcon(0, 300)

        // Out again once it is clearly off the side.
        move(28f, 300f)
        land()
        assertIsTheIcon(0, 300)
        move(29f, 300f)
        land()
        assertIsTheWindow(Frame(9, 280, 420, 400))
        up(29f, 300f)
    }

    @Test fun `the right side docks to the right`() {
        paceFrames()
        show()

        down(barX, barY)
        move(900f, barY)
        move(1275f, 500f)
        land()
        assertIsTheIcon(1280, 500)
        up(1275f, 500f)

        assertEquals(listOf(Side.RIGHT to 500), host.docked)
    }

    @Test fun `let go while still on its way to the icon, it docks all the same`() {
        paceFrames()
        show()

        down(barX, barY)
        move(300f, barY)
        move(100f, 300f)
        up(100f, 300f)

        assertEquals(listOf(Side.LEFT to 300), host.docked)
    }

    @Test fun `a dock the host makes no icon of comes back as the window`() {
        paceFrames()
        show()
        down(barX, barY)
        move(300f, barY)
        move(100f, 300f)
        land()

        up(100f, 300f)
        land()
        layOut()

        assertEquals(1, host.docked.size)
        assertIsTheWindow(Frame(0, 280, 420, 400))
    }

    @Test fun `let go docked and taken down for the icon, the window goes at once, with no flight left to make`() {
        paceFrames()
        show()
        host.onDockedDo = { window.remove(Rect(-40, 260, 40, 340)) }
        down(barX, barY)
        move(300f, barY)
        move(100f, 300f)
        land()

        up(100f, 300f)

        assertTrue(takenDown())
    }

    @Test fun `a move the system takes away while docked is the window again`() {
        paceFrames()
        show()
        down(barX, barY)
        move(300f, barY)
        move(100f, 300f)
        land()
        assertIsTheIcon(0, 300)

        touch(MotionEvent.ACTION_CANCEL, 100f, 300f)
        land()
        layOut()

        assertTrue(host.docked.isEmpty())
        assertIsTheWindow(Frame(0, 280, 420, 400))
    }

    @Test fun `a resize at a side never docks`() {
        show(Frame(0, 200, 420, 400))

        down(5f, 595f)
        move(8f, 600f)
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

    // ── The flight to and from the icon ──────────────────────────────────

    private val card get() = window.root.findViewById<View>(R.id.detachedCard)

    /** The icon's window, at the left side. */
    private val icon = Rect(0, 300, 60, 360)
    private val home = Frame(400, 200, 420, 400)

    /** The card's centre on the display, from the window's params. */
    private fun centre(): Pair<Int, Int> = frame().let { (it.x + it.w / 2) to (it.y + it.h / 2) }

    /** Whether a window has been taken down: the churn gate hides a
     *  removed window and stops its touches at once, and destroys it in a
     *  quiet moment later (its params are then as they were). */
    private fun takenDown(view: View = window.root, p: WindowManager.LayoutParams = params()) =
        p.alpha == 0f || view in wm.removed

    /** Frames come as the clock is moved, one every 16 ms, where the test
     *  runtime would otherwise run a whole animation in one turn of the
     *  looper. Before the window is shown; [tearDown] hands it back. */
    private fun paceFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** Let the flight run out: its length several times over, a frame at
     *  a time (a paced animation takes its start from the first frame it
     *  sees, so one long turn of the looper shows it only that one). */
    private fun land() = repeat((DetachedWindow.FLIGHT_MS * 4 / 16).toInt()) { idle(16) }

    /** Let the flight run to somewhere in its middle. */
    private fun flyToTheMiddle() {
        val small = 60 / 420f
        repeat(40) {
            if (card.scaleX > small + 0.2f && card.scaleX < 0.8f) return
            idle(8)
        }
        error("the flight never showed a frame in its middle (scale ${card.scaleX})")
    }

    private fun assertOnItsWay() {
        val (x, y) = centre()
        assertTrue("between the icon and home: $x, $y", x in 31..609 && y in 331..399)
        assertEquals("at the window's own size: nothing is laid out again in flight", 420, frame().w)
        // The host's layout pass leaves a window in flight to its flight.
        overlayHost.invalidateLayout(d0)
        assertEquals(x to y, centre())
    }

    @Test fun `shown out of the icon's place the window starts on it, small and unseen, and lands on its frame`() {
        paceFrames()
        show(from = icon)

        assertEquals("the card's centre on the icon's", 30 to 330, centre())
        assertEquals("scaled to the icon's size", 60 / 420f, card.scaleX, 0.001f)
        assertEquals(60 / 400f, card.scaleY, 0.001f)
        assertEquals(0f, card.alpha, 0.001f)

        flyToTheMiddle()
        assertOnItsWay()
        assertTrue("coming into sight", card.alpha > 0f)

        land()
        layOut()
        assertEquals(home, frame())
        assertEquals(1f, card.scaleX, 0f)
        assertEquals(1f, card.scaleY, 0f)
        assertEquals(1f, card.alpha, 0f)
        // And from there it is the window it always was.
        drag(barX, barY, barX + 100, barY + 60)
        assertEquals(Frame(500, 260, 420, 400), frame())
    }

    @Test fun `a window in flight takes no gesture, on its bar or at a corner`() {
        paceFrames()
        show(from = icon)
        flyToTheMiddle()

        // Where its bar and its bottom right corner will be.
        down(barX, barY)
        move(barX + 100, barY + 60)
        assertOnItsWay()
        up(barX + 100, barY + 60)
        down(815f, 595f)
        move(700f, 500f)
        up(700f, 500f)

        land()
        layOut()
        assertEquals("it lands where it was going", home, frame())
        assertNull("and nothing was saved for it", prefs.detachedStateForDisplay(d0).bounds(landscape = true)?.takeIf {
            g.fromBounds(it, room, 1f) != home
        })
    }

    @Test fun `removed into the icon's place the window is done with its page at once, and is taken down when it gets there`() {
        paceFrames()
        show()

        window.remove(icon)

        assertNull("the page is its owner's again", page.parent)
        assertTrue("the corners' touch targets are gone", window.handlePadsForTest().all { takenDown(it.first, it.second) })
        assertFalse("the window is still up, for the flight", takenDown())
        assertEquals("it starts where it stood", home, frame())
        assertEquals(1f, card.alpha, 0f)
        val inCard = card as FrameLayout
        assertEquals("only a picture of the card flies", 1, inCard.childCount)
        assertTrue(inCard.getChildAt(0) is android.widget.ImageView)

        flyToTheMiddle()
        assertOnItsWay()
        assertTrue("shrinking", card.scaleX < 1f)
        // It takes no touch on the way.
        val touches = host.touches
        down(centre().first.toFloat(), centre().second.toFloat())
        up(centre().first.toFloat(), centre().second.toFloat())
        assertEquals(touches, host.touches)
        assertFalse(takenDown())

        land()
        assertTrue("taken down at the icon", takenDown())
        assertEquals(30 to 330, centre())
        assertEquals(60 / 420f, card.scaleX, 0.001f)
        assertEquals(0f, card.alpha, 0.001f)

        // Once only, whoever asks again.
        window.remove(icon)
        window.remove()
        idle(5000)
        assertEquals(1, wm.removed.count { it === window.root })
    }

    @Test fun `removed with nowhere to fly the window goes at once`() {
        show()

        window.remove()

        assertTrue(takenDown())
        assertNull(page.parent)
    }

    @Test fun `removed while still flying in, the window turns round where it is`() {
        paceFrames()
        show(from = icon)
        flyToTheMiddle()
        val midway = card.scaleX

        window.remove(icon)

        assertEquals("no jump to its frame first", midway, card.scaleX, 0.001f)
        assertFalse(takenDown())
        land()
        assertTrue(takenDown())
        assertEquals(30 to 330, centre())
    }

    // ── Pulled out of the icon ───────────────────────────────────────────
    //
    // The icon's window is 80 px square, centred on the right side at the
    // finger's height. The finger holds the bar 28 px in from its right end
    // and 26 px down, so with the finger at (x, y) the window's frame is
    // (x - 392, y - 26), where it fits.

    /** The window up as the icon it is pulled out of, the finger at
     *  ([x], [y]) on the icon. */
    private fun showPulled(x: Float, y: Float) {
        prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(home, room, 1f))
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        overlayHost.environmentReader = { testEnvironment(1280, 800) }
        window = DetachedWindow(displayCtx, wm, d0, overlayHost, prefs, host)
        assertTrue(window.showPulled(Rect(1240, 360, 1320, 440), Side.RIGHT, x, y))
        page = View(ctx)
        window.setPage(page)
        layOut()
    }

    private fun pullTo(x: Float, y: Float) {
        window.pullTo(x, y)
        layOut()
    }

    @Test fun `pulled out of the icon the window starts as the icon, comes out as the finger leaves the side and goes back in as it returns`() {
        paceFrames()
        showPulled(1262f, 400f)
        assertIsTheIcon(1280, 400)

        // Still by the side: the icon, at the finger's height.
        pullTo(1253f, 410f)
        assertIsTheIcon(1280, 410)

        pullTo(1200f, 420f)
        land()
        layOut()
        assertIsTheWindow(Frame(808, 394, 420, 400))
        pullTo(1000f, 300f)
        assertIsTheWindow(Frame(608, 274, 420, 400))

        pullTo(1270f, 400f)
        land()
        assertIsTheIcon(1280, 400)

        assertEquals("let go as the icon: its side and the finger's height", Side.RIGHT to 400, window.pullEnd())
        assertTrue("the window's own dock is not asked", host.docked.isEmpty())
    }

    @Test fun `let go out, a pulled window rests where it is, its place saved, and is a window like any other`() {
        paceFrames()
        showPulled(1262f, 400f)
        pullTo(1200f, 420f)
        pullTo(1000f, 300f)

        assertNull("the window", window.pullEnd())
        land()
        layOut()

        assertIsTheWindow(Frame(608, 274, 420, 400))
        assertEquals(Frame(608, 274, 420, 400), savedFrame())
        // Its bar is 168 px in from its left edge at this point.
        drag(776f, 294f, 876f, 354f)
        assertEquals(Frame(708, 334, 420, 400), frame())
    }

    @Test fun `while it is being pulled a touch on the window itself does nothing`() {
        paceFrames()
        showPulled(1262f, 400f)
        pullTo(1000f, 300f)
        land()
        layOut()

        down(776f, 294f)
        move(600f, 294f)
        up(600f, 294f)

        assertIsTheWindow(Frame(608, 274, 420, 400))
        assertNull(window.pullEnd())
    }
}
