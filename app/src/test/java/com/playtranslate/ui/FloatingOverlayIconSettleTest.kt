package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OverlayPlacement
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.overlay.Sides
import com.playtranslate.testutil.RecordingWindowManager
import com.playtranslate.testutil.testEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * At rest the floating icon is where its slot places it against the
 * display as it is NOW, whatever happened while a gesture or a glide owned
 * the window. The slot is the one description of the rest position; the
 * host lays the window out on it ([FloatingOverlayIcon.layoutAtRest]) at its
 * add, whenever the display changes (here a navigation bar the app beneath
 * shows: [OverlayHost.refresh]), and at every gesture's and glide's end
 * (the icon invalidates the display's layout), and nothing restores pixels
 * saved before a change. Each case is a gesture ending crossed with the
 * moment the bar appears, and the oracle is the placement itself: a second
 * icon laid out on the same slot against the display as it is after the
 * change. Before the slot, the rest position also lived as pixels saved at
 * touch-down; each review found one more path restoring them after the bars
 * had changed (Codex adversarial reviews, 2026-10-10).
 */
@RunWith(RobolectricTestRunner::class)
class FloatingOverlayIconSettleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val display = Display.DEFAULT_DISPLAY

    /** The right-side navigation bar the display reports; a case shows it
     *  mid-gesture. */
    private var bar = 0
    private fun env() = testEnvironment(SCREEN_W, SCREEN_H, shownBars = Sides(0, 0, bar, 0))

    private val wm = RecordingWindowManager(OwnWindows.manager(ctx)!!)
    /** The MediaProjection backend's host: its windows lie below the bars. */
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also {
        it.environmentReader = { env() }
    }

    /** The slot the icon last reported (a snap decides a new one). */
    private var slot = FloatingOverlayIcon.Edge.RIGHT.ordinal to 0.5f

    private fun bareIcon(): FloatingOverlayIcon {
        val icon = FloatingOverlayIcon(ctx)
        icon.overlayHost = host
        icon.wm = wm
        icon.displayId = display
        icon.params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        icon.setSlot(slot.first, slot.second)
        return icon
    }

    /** The icon under test: added through the host, on [slot], and
     *  reporting its snaps. */
    private fun subject(): FloatingOverlayIcon {
        val icon = bareIcon()
        icon.onPositionChanged = { edge, fraction -> slot = edge to fraction }
        host.addOverlayWindow(icon, wm, icon.params!!, display, OverlayPlacement.Placed(icon::layoutAtRest))
        return icon
    }

    /** Where [slot] places an icon against the display as it is now. */
    private fun expected(): Pair<Int, Int> {
        val icon = bareIcon()
        val p = icon.params!!
        icon.layoutAtRest(env(), p)
        return p.x to p.y
    }

    private fun assertAtRest(icon: FloatingOverlayIcon) {
        val (x, y) = expected()
        assertEquals("x", x, icon.params!!.x)
        assertEquals("y", y, icon.params!!.y)
    }

    /** The bar appears, 84 px on the right, and the host hears it. */
    private fun barAppears() {
        bar = BAR
        host.refresh(display, "bars", force = false)
    }

    /** The bar goes, and the host hears it. */
    private fun barGoes() {
        bar = 0
        host.refresh(display, "bars", force = false)
    }

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private fun touch(
        icon: FloatingOverlayIcon, action: Int, x: Float, y: Float, atMs: Long, downAt: Long = T0,
    ) {
        val ev = MotionEvent.obtain(downAt, downAt + atMs, action, x, y, 0)
        try { icon.onTouchEvent(ev) } finally { ev.recycle() }
    }

    /** Lets a glide run out. */
    private fun glideOut() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    // ── No gesture ──────────────────────────────────────────────────────

    @Test fun `a bar that appears with nothing in flight moves the icon beside it at once`() {
        val icon = subject()
        val edgeX = icon.params!!.x

        barAppears()

        assertEquals(edgeX - BAR, icon.params!!.x)
        assertAtRest(icon)
    }

    @Test fun `a bar that goes moves the icon back to the edge at once`() {
        bar = BAR
        val icon = subject()
        val besideBarX = icon.params!!.x

        barGoes()

        assertEquals(besideBarX + BAR, icon.params!!.x)
        assertAtRest(icon)
    }

    @Test fun `a refresh with no change leaves the icon where it is and commits nothing`() {
        val icon = subject()
        val (x, y) = icon.params!!.let { it.x to it.y }

        host.refresh(display, "bars", force = false)
        host.invalidateLayout(display)

        assertEquals(x, icon.params!!.x)
        assertEquals(y, icon.params!!.y)
        assertEquals(0, wm.updatesOf(icon))
    }

    // ── A press ─────────────────────────────────────────────────────────

    @Test fun `a bar that appears during a tap is applied before the tap fires`() {
        val icon = subject()
        var atTap: Pair<Int, Int>? = null
        icon.onTap = { atTap = icon.params!!.let { it.x to it.y } }
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)

        barAppears()
        assertNotEquals("deferred while the finger is down", expected(), icon.params!!.let { it.x to it.y })

        touch(icon, MotionEvent.ACTION_UP, PRESS_X, PRESS_Y, 60)
        assertEquals("the tap's menu anchors on the new place", expected(), atTap)
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a hold is applied at the release`() {
        val icon = subject()
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)

        barAppears()

        touch(icon, MotionEvent.ACTION_UP, PRESS_X, PRESS_Y, HOLD_MS)
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a press the system cancels is applied at the cancel`() {
        val icon = subject()
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)

        barAppears()

        touch(icon, MotionEvent.ACTION_CANCEL, PRESS_X, PRESS_Y, 60)
        assertAtRest(icon)
    }

    // ── A drag ──────────────────────────────────────────────────────────

    @Test fun `a bar that appears during a drag that commits a sticky lens is applied at the release`() {
        val icon = subject()
        icon.onDragEnd = { true }   // a word under the finger: the lens goes sticky
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 40)

        barAppears()

        touch(icon, MotionEvent.ACTION_UP, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 80)
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a drag that snaps is read by the snap`() {
        val icon = subject()
        icon.onDragEnd = { false }  // nothing under the finger: the icon snaps
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 40)

        barAppears()

        touch(icon, MotionEvent.ACTION_UP, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 80)
        glideOut()
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a snap's glide is applied at the glide's end`() {
        val icon = subject()
        icon.onDragEnd = { false }
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 40)
        touch(icon, MotionEvent.ACTION_UP, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 80)

        barAppears()

        glideOut()
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a drag the system cancels is read by the glide back`() {
        val icon = subject()
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 40)

        barAppears()

        touch(icon, MotionEvent.ACTION_CANCEL, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 80)
        glideOut()
        assertAtRest(icon)
    }

    @Test fun `a bar that appears during a cancelled drag's glide back is applied at its end`() {
        val icon = subject()
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 40)
        touch(icon, MotionEvent.ACTION_CANCEL, PRESS_X + DRAG_DX, PRESS_Y + DRAG_DY, 80)

        barAppears()

        glideOut()
        assertAtRest(icon)
    }

    // ── A glide the next gesture interrupts ─────────────────────────────

    @Test fun `a touch mid-glide keeps the window where it caught it, and its tap settles`() {
        val icon = subject()
        icon.onDragEnd = { false }
        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X, PRESS_Y, 0)
        touch(icon, MotionEvent.ACTION_MOVE, PRESS_X + 300f, PRESS_Y + DRAG_DY, 40)
        // The drag's visuals are up: the window is at the finger.
        icon.revealDragVisuals(PRESS_X + 300f, PRESS_Y + DRAG_DY)
        val atFinger = icon.params!!.let { it.x to it.y }
        touch(icon, MotionEvent.ACTION_UP, PRESS_X + 300f, PRESS_Y + DRAG_DY, 80)
        // The glide back has started and no frame has run yet.
        barAppears()
        assertEquals("the glide owns the window", atFinger, icon.params!!.let { it.x to it.y })

        touch(icon, MotionEvent.ACTION_DOWN, PRESS_X + 300f, PRESS_Y + DRAG_DY, 0, downAt = T1)
        assertEquals("the new gesture owns the window", atFinger, icon.params!!.let { it.x to it.y })

        touch(icon, MotionEvent.ACTION_UP, PRESS_X + 300f, PRESS_Y + DRAG_DY, 60, downAt = T1)
        assertAtRest(icon)
        glideOut()
        assertAtRest(icon)
    }

    // ── What hangs off the dock ─────────────────────────────────────────

    @Test fun `a refresh that moves the icon commits it once`() {
        val icon = subject()

        barAppears()
        assertEquals(1, wm.updatesOf(icon))
        host.refresh(display, "bars", force = false)
        assertEquals("already there", 1, wm.updatesOf(icon))
    }

    @Test fun `the hold spinner follows the settle`() {
        val icon = subject()
        icon.showLoading = true
        val spinner = wm.added.last().first
        val lp = spinner.layoutParams as WindowManager.LayoutParams
        val gapToIcon = icon.params!!.x - lp.x

        barAppears()

        assertEquals("the same gap, beside the new place", gapToIcon, icon.params!!.x - lp.x)
        assertEquals(icon.params!!.y + (icon.viewSizePx - lp.height) / 2, lp.y)
        assertEquals("committed", 1, wm.updatesOf(spinner))
    }

    private companion object {
        const val SCREEN_W = 1080
        const val SCREEN_H = 2400
        const val T0 = 1_000L
        const val T1 = 5_000L
        const val BAR = 84
        const val PRESS_X = 12f
        const val PRESS_Y = 230f
        const val DRAG_DX = 40f
        const val DRAG_DY = 20f
        /** Past the icon's 400 ms hold delay. */
        const val HOLD_MS = 450L
    }
}
