package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.displayGeometryOf
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * A drag session settles ([DragLookupController.onSettled], which restores
 * live mode and the region indicator) exactly once, whether or not a lens
 * window exists when it ends. The lens window is created at the reveal,
 * after the clean capture returns, and removed by every dismissal, so a
 * release or a cancel before the capture returns (a quick flick of the
 * icon) ends the session with no lens. Those ends used to call a dismiss
 * that did nothing, and live mode stayed paused until a later drag's lens
 * closed (Codex adversarial review, 2026-10-10; the ordering dates to the
 * capture-before-reveal change of 2026-06-03).
 *
 * A display whose geometry (size or rotation) changed under the session
 * ends it the same way, from the drag's start: a rotation before the lens
 * reveals, a flip with a sticky lens up. The screenshot, the OCR lines and
 * the finger coordinates belong to the layout the drag started on, so a
 * capture that returns after the change ends the session itself, before
 * any display event ([DragLookupController.onDisplayChanged]). A display
 * event with the geometry unchanged (a refresh-rate switch) does nothing.
 */
@RunWith(RobolectricTestRunner::class)
class DragLookupControllerSettleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

    /** Records every window the lens adds and removes; [failNextAdd]
     *  refuses one add the way the platform does (a thrown
     *  BadTokenException), which the host reports as not attached. */
    private class RecordingWindowManager(real: WindowManager) : WindowManager by real {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        var failNextAdd = false
        private val real = real
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failNextAdd) {
                failNextAdd = false
                throw WindowManager.BadTokenException("test: window refused")
            }
            added += view
            real.addView(view, params)
        }
        override fun removeView(view: View) {
            removed += view
            real.removeView(view)
        }
        override fun removeViewImmediate(view: View) {
            removed += view
            real.removeViewImmediate(view)
        }
    }

    private val wm = RecordingWindowManager(OwnWindows.manager(ctx)!!)
    private val lens = MagnifierLens(ctx, wm, Display.DEFAULT_DISPLAY, host)
    private val controller = DragLookupController(
        context = ctx,
        displayId = Display.DEFAULT_DISPLAY,
        popup = WordLookupPopup(ctx, wm, Display.DEFAULT_DISPLAY, host),
        magnifier = lens,
        overlayHost = host,
    )
    private var settles = 0

    init {
        controller.onSettled = { settles++ }
    }

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    /** A drag whose capture has not returned: the OCR job is queued on the
     *  main looper and has not run, so no lens window exists. */
    private fun startDragBeforeCapture() {
        controller.onDragStart(existingScreenshotPath = "/nonexistent/drag.png")
    }

    private fun geometry() = ctx.displayGeometryOf(Display.DEFAULT_DISPLAY)!!

    /** The display turns to landscape: another size. */
    private fun rotate() {
        val before = geometry()
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+land")
        assertNotEquals("the display changed", before, geometry())
    }

    /** The display flips 180 degrees: the same size, another rotation. */
    private fun flip() {
        val before = geometry()
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(display).setRotation(Surface.ROTATION_180)
        val after = geometry()
        assertEquals(before.width to before.height, after.width to after.height)
        assertNotEquals(before.rotation, after.rotation)
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun `a release before the lens exists settles once`() {
        startDragBeforeCapture()

        assertFalse("no word under the finger", controller.onDragEnd())

        assertEquals(1, settles)
        assertFalse(lens.isInteractive)
        // The cancelled capture job runs out: no second settle.
        idle(2)
        assertEquals(1, settles)
    }

    @Test fun `a cancel before the lens exists settles once`() {
        startDragBeforeCapture()

        controller.cancelDrag()

        assertEquals(1, settles)
        idle(2)
        assertEquals(1, settles)
    }

    @Test fun `a release with a lens up settles through its dismissal, once`() {
        startDragBeforeCapture()
        // The reveal, as the capture's return would make it.
        lens.show(100, 300, 720, 1604)

        assertFalse(controller.onDragEnd())

        assertEquals(1, settles)
        assertFalse(lens.isInteractive)
    }

    // ── Teardown ────────────────────────────────────────────────────────

    @Test fun `teardown mid-drag reports nothing, with no lens`() {
        startDragBeforeCapture()

        controller.destroy()

        assertEquals("the host decides what the session paused", 0, settles)
        idle(2)
        assertEquals(0, settles)
    }

    @Test fun `teardown with a sticky lens up removes it and reports nothing`() {
        startDragBeforeCapture()
        lens.show(100, 300, 720, 1604)
        assertTrue(lens.makeInteractive())

        controller.destroy()

        assertFalse(lens.isInteractive)
        assertEquals(0, settles)
    }

    // ── The display changes under the session ───────────────────────────

    @Test fun `a rotation before the lens reveals ends the drag once, and the release finds nothing`() {
        startDragBeforeCapture()

        rotate()
        controller.onDisplayChanged()

        assertEquals("settled at the change", 1, settles)
        // The release's own end settles again (idempotent at the host, as
        // after any external dismiss mid-drag) and shows nothing; the
        // cancelled capture reveals nothing either.
        assertFalse(controller.onDragEnd())
        idle(2)
        assertEquals("no lens window was ever added", 0, wm.added.size)
        assertFalse(lens.isInteractive)
    }

    @Test fun `a capture that returns after a rotation ends the drag by itself`() {
        // No file: the capture path runs on the first idle, finds no
        // capture source here and would reveal the lens without a bitmap.
        controller.onDragStart()
        rotate()
        // No display event reaches the controller.

        idle(2)

        assertEquals("the capture ended the session", 1, settles)
        assertEquals("nothing revealed", 0, wm.added.size)
        assertFalse(controller.onDragEnd())
    }

    @Test fun `a flip with a sticky lens up ends the session once`() {
        startDragBeforeCapture()
        val g = geometry()
        assertTrue(lens.show(100, 300, g.width, g.height))
        assertTrue(lens.makeInteractive())
        val root = wm.added.single()

        flip()
        controller.onDisplayChanged()

        assertEquals(1, settles)
        assertFalse(lens.isInteractive)
        idle(5)
        assertTrue("the window is gone", root in wm.removed)
        assertEquals(1, settles)
    }

    @Test fun `a lens lost to a refused rebuild mid-drag leaves the display event to end the session`() {
        // The capture returns (no source here, so it reveals the lens with
        // nothing to zoom) and the drag is in flight with its lens up. The
        // display change below idles the looper (Robolectric), so the
        // capture must be back before it, as it is on the device while a
        // lens exists.
        controller.onDragStart()
        idle(2)
        assertEquals("the lens is up", 1, wm.added.size)
        assertEquals(0, settles)

        rotate()
        // A move lands before the display event: its show at the new size
        // rebuilds the window, and the platform refuses the add. The lens
        // reports the loss; the drag is still in flight.
        wm.failNextAdd = true
        assertFalse(lens.show(100, 300, 1604, 720))
        assertEquals("the drag carries on without its lens", 0, settles)

        controller.onDisplayChanged()

        assertEquals("the display event ended the session", 1, settles)
        assertFalse("the release finds nothing", controller.onDragEnd())
        idle(2)
        assertFalse(lens.isInteractive)
    }

    @Test fun `a display event with the geometry unchanged leaves the session alone`() {
        startDragBeforeCapture()
        val g = geometry()
        assertTrue(lens.show(100, 300, g.width, g.height))
        assertTrue(lens.makeInteractive())

        controller.onDisplayChanged()

        assertEquals(0, settles)
        assertTrue(lens.isInteractive)
        assertEquals(1, wm.added.size)
    }

    @Test fun `a display change with no session does nothing`() {
        rotate()
        controller.onDisplayChanged()
        assertEquals(0, settles)
    }
}
