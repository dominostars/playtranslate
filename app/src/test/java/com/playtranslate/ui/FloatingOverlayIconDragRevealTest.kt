package com.playtranslate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import com.playtranslate.displaySizePx
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.testutil.testEnvironment

/**
 * Pins the floating icon's drag protocol against the drag lens's clean
 * capture: from the drag's start to [FloatingOverlayIcon.revealDragVisuals]
 * the icon submits nothing visible, no window move and no ring, so no
 * commit of ours lands around the capture's freshness anchor and gets
 * served as the "post-blank" frame (the ring at the finger in every drag
 * screenshot on the Moto G, 2026-10-09). The reveal centres the window on
 * the finger and paints the ring; moves follow from then on.
 *
 * Legacy graphics: the legacy Canvas shadow records draw calls, which the
 * paint assertions read (a NATIVE sandbox cannot construct a View reliably
 * in this JVM).
 */
@RunWith(RobolectricTestRunner::class)
class FloatingOverlayIconDragRevealTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Counts the layout commits the icon asks for. */
    private class RecordingWindowManager(real: WindowManager) : WindowManager by real {
        var layoutCommits = 0
        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
            layoutCommits++
        }
    }

    private class Harness(ctx: Context) {
        val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        val wm = RecordingWindowManager(ctx.getSystemService(WindowManager::class.java))
        val icon = FloatingOverlayIcon(ctx)
        val viewHalf = icon.viewSizePx / 2
        val params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        /** The dock the icon settles on: a left-edge slot, the circle's
         *  centre on the screen's left edge, half way down. */
        val dockX: Int
        val dockY: Int
        var dragStarts = 0
        val dragMoves = mutableListOf<Pair<Float, Float>>()
        var dragEndResult = true

        init {
            icon.wm = wm
            icon.params = params
            // The icon added through a host whose display has no bars or
            // cutout, as the controller adds it: the host lays it out on its
            // slot at the add and whenever it comes to rest.
            val screen = ctx.displaySizePx()
            val env = testEnvironment(screen.x, screen.y)
            icon.overlayHost = host.also { it.environmentReader = { env } }
            icon.setSlot(FloatingOverlayIcon.Edge.LEFT.ordinal, 0.5f)
            host.addOverlayWindow(
                icon, wm, params, android.view.Display.DEFAULT_DISPLAY,
                OverlayPlacement.Placed(icon::layoutAtRest),
            )
            dockX = params.x
            dockY = params.y
            assertEquals("the centre on the left edge", -viewHalf, dockX)
            icon.onDragStart = { dragStarts++ }
            icon.onDragMove = { x, y -> dragMoves += x to y }
            icon.onDragEnd = { dragEndResult }
        }

        fun touch(action: Int, x: Float, y: Float, atMs: Long) {
            val ev = MotionEvent.obtain(T0, T0 + atMs, action, x, y, 0)
            try { icon.onTouchEvent(ev) } finally { ev.recycle() }
        }

        fun down() = touch(MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        fun move(dx: Float, dy: Float, atMs: Long) =
            touch(MotionEvent.ACTION_MOVE, PRESS_X + dx, PRESS_Y + dy, atMs)
        fun up(dx: Float, dy: Float, atMs: Long) =
            touch(MotionEvent.ACTION_UP, PRESS_X + dx, PRESS_Y + dy, atMs)

        /** Past the 18 dp tap slop (density 1 here), into a drag. */
        fun dragPastSlop() {
            down()
            move(DRAG_DX, DRAG_DY, 40)
        }

        /** Draws the icon into a recording canvas and returns the count of
         *  circles, lines and paths it drew; zero of each is "nothing". */
        fun drawn(): Triple<Int, Int, Int> {
            val canvas = Canvas(
                Bitmap.createBitmap(icon.viewSizePx, icon.viewSizePx, Bitmap.Config.ARGB_8888),
            )
            icon.draw(canvas)
            val s = shadowOf(canvas)
            return Triple(s.circlePaintHistoryCount, s.linePaintHistoryCount, s.pathPaintHistoryCount)
        }

        fun assertAtDock() {
            assertEquals(dockX, params.x)
            assertEquals(dockY, params.y)
        }
    }

    private var last: Harness? = null
    private fun harness() = Harness(ctx).also { last = it }

    @org.junit.After fun tearDown() {
        last?.host?.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    @Test
    fun `a docked icon paints its chevron`() {
        val h = harness()
        val (circles, _, paths) = h.drawn()
        assertTrue("compact disc", circles > 0)
        assertTrue("edge triangle", paths > 0)
    }

    @Test
    fun `a drag start commits no layout and paints nothing`() {
        val h = harness()
        h.dragPastSlop()
        assertEquals(1, h.dragStarts)
        assertTrue(h.icon.inDragMode)
        assertFalse(h.icon.dragVisualsRevealed)
        assertEquals("no layout commit", 0, h.wm.layoutCommits)
        h.assertAtDock()
        assertEquals(Triple(0, 0, 0), h.drawn())
    }

    @Test
    fun `moves before the reveal reach onDragMove and commit no layout`() {
        val h = harness()
        h.dragPastSlop()
        h.move(DRAG_DX + 30f, DRAG_DY + 12f, 60)
        assertEquals(PRESS_X + DRAG_DX + 30f to PRESS_Y + DRAG_DY + 12f, h.dragMoves.last())
        assertEquals(0, h.wm.layoutCommits)
        h.assertAtDock()
        assertEquals(Triple(0, 0, 0), h.drawn())
    }

    @Test
    fun `the reveal centres the window on the finger and paints the ring, and moves follow`() {
        val h = harness()
        h.dragPastSlop()
        val fx = PRESS_X + DRAG_DX + 30f
        val fy = PRESS_Y + DRAG_DY + 12f
        h.move(DRAG_DX + 30f, DRAG_DY + 12f, 60)

        h.icon.revealDragVisuals(fx, fy)

        assertTrue(h.icon.dragVisualsRevealed)
        assertEquals(1, h.wm.layoutCommits)
        assertEquals((fx - h.viewHalf).toInt(), h.params.x)
        assertEquals((fy - h.viewHalf).toInt(), h.params.y)
        val (circles, lines, _) = h.drawn()
        assertTrue("ring", circles > 0)
        assertTrue("magnifying-glass handle", lines > 0)

        h.move(DRAG_DX + 40f, DRAG_DY + 20f, 80)
        assertEquals(2, h.wm.layoutCommits)
        assertEquals((fx + 10f - h.viewHalf).toInt(), h.params.x)
        assertEquals((fy + 8f - h.viewHalf).toInt(), h.params.y)
    }

    @Test
    fun `a second reveal is a no-op`() {
        val h = harness()
        h.dragPastSlop()
        h.icon.revealDragVisuals(100f, 300f)
        h.icon.revealDragVisuals(150f, 350f)
        assertEquals(1, h.wm.layoutCommits)
        assertEquals((100f - h.viewHalf).toInt(), h.params.x)
    }

    @Test
    fun `a reveal after the drag ended is a no-op`() {
        val h = harness()
        h.dragPastSlop()
        h.up(DRAG_DX, DRAG_DY, 80) // onDragEnd true: the icon settles on its dock
        assertFalse(h.icon.inDragMode)
        val commitsAfterEnd = h.wm.layoutCommits

        h.icon.revealDragVisuals(100f, 300f)

        assertFalse(h.icon.dragVisualsRevealed)
        assertEquals(commitsAfterEnd, h.wm.layoutCommits)
        h.assertAtDock()
    }

    @Test
    fun `the next drag starts hidden again`() {
        val h = harness()
        h.dragPastSlop()
        h.icon.revealDragVisuals(100f, 300f)
        h.up(DRAG_DX, DRAG_DY, 80)
        h.assertAtDock()
        val commitsAfterEnd = h.wm.layoutCommits

        h.dragPastSlop()

        assertEquals(2, h.dragStarts)
        assertFalse(h.icon.dragVisualsRevealed)
        assertEquals(commitsAfterEnd, h.wm.layoutCommits)
        assertEquals(Triple(0, 0, 0), h.drawn())
    }

    @Test
    fun `moves inside the tap slop commit no layout`() {
        val h = harness()
        h.down()
        h.move(5f, 4f, 20)
        h.move(-6f, 3f, 40)
        assertEquals(0, h.dragStarts)
        assertEquals(0, h.wm.layoutCommits)
        h.assertAtDock()
    }

    @Test
    fun `a release before the reveal leaves the icon at its dock`() {
        val h = harness()
        h.dragEndResult = false // nothing under the finger: the icon would snap
        h.dragPastSlop()
        h.up(DRAG_DX, DRAG_DY, 60)
        assertFalse(h.icon.inDragMode)
        h.assertAtDock()
        // Let the snap glide run out: from the dock it goes nowhere.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        h.assertAtDock()
    }

    private companion object {
        const val T0 = 1_000L
        const val PRESS_X = 12f
        const val PRESS_Y = 230f
        /** 40 + 20 px of travel: past the 18 px slop on the first move. */
        const val DRAG_DX = 40f
        const val DRAG_DY = 20f
    }
}
