package com.playtranslate.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.testutil.RecordingWindowManager
import com.playtranslate.testutil.testEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * [OverlayHost] owns where its windows go: the layout contract each
 * placement gets ([OverlayHost.applyOverlayLayoutDefaults]), and the
 * refresh that brings every window on a display to the display as it is
 * ([OverlayHost.refresh]), from a display change, a bar the app beneath
 * shows or hides (heard through the Placed windows' own insets, below the
 * bars), or a window others derive from moving
 * ([OverlayHost.invalidateLayout]). The environment comes through the
 * host's reader seam, so each case states the display it sees.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayHostPlacementTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val wm = RecordingWindowManager(OwnWindows.manager(ctx)!!)
    private val display = Display.DEFAULT_DISPLAY
    private val other = Display.DEFAULT_DISPLAY + 7

    private var env = mutableMapOf(
        display to testEnvironment(1000, 2000),
        other to testEnvironment(1920, 1080),
    )
    private var reads = 0
    private val host = mpHost()

    private fun mpHost() = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also {
        it.environmentReader = { id -> reads++; env[id] }
    }

    private fun params(w: Int, h: Int, flags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) =
        WindowManager.LayoutParams(w, h, 0, flags, PixelFormat.TRANSLUCENT)

    /** A Placed window at the bottom-right of what covers nothing, inset by
     *  [offset]: a stand-in for the icon's dock. Counts its layouts. */
    private inner class Docked(val offset: Int = 0) {
        var layouts = 0
        val view = View(ctx)
        val p = params(100, 100).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        val placement = OverlayPlacement.Placed { e, lp ->
            layouts++
            lp.x = e.geometry.width - e.covered.right - lp.width - offset
            lp.y = e.geometry.height - e.covered.bottom - lp.height - offset
        }
        fun add(onDisplay: Int = display, h: OverlayHost = host) = assertTrue(h.addOverlayWindow(view, wm, p, onDisplay, placement))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    // ── The layout contract per placement ─────────────────────────────────

    @Test fun `a placed or content window is laid out in display pixels, a fixed one keeps the platform's layout`() {
        val placed = params(100, 100)
        val content = params(100, 100)
        val fixed = params(100, 100)
        host.addOverlayWindow(View(ctx), wm, placed, display, OverlayPlacement.Placed { _, _ -> })
        host.addOverlayWindow(View(ctx), wm, content, display, OverlayPlacement.Content)
        host.addOverlayWindow(View(ctx), wm, fixed, display, OverlayPlacement.Fixed)

        for (p in listOf(placed, content)) {
            assertEquals(0, p.fitInsetsTypes)
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, p.layoutInDisplayCutoutMode)
            assertEquals("NO_LIMITS is the full-screen windows' own", 0, p.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            assertEquals(100, p.width)
        }
        assertEquals("the platform's default fit", WindowInsets.Type.systemBars(), fixed.fitInsetsTypes)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, fixed.layoutInDisplayCutoutMode)
    }

    @Test fun `a caller's explicit cutout mode is honoured`() {
        val p = params(100, 100).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        host.addOverlayWindow(View(ctx), wm, p, display, OverlayPlacement.Content)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, p.layoutInDisplayCutoutMode)
    }

    @Test fun `a full-screen window is pinned to the display and gets NO_LIMITS`() {
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        host.addOverlayWindow(View(ctx), wm, p, display, OverlayPlacement.CaptureSpace)
        assertEquals(1000, p.width)
        assertEquals(2000, p.height)
        assertEquals(0, p.fitInsetsTypes)
        assertNotEquals(0, p.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    @Test fun `below R only the full-screen windows get the display frame, as in v3,3,1`() {
        val q = Build.VERSION_CODES.Q
        val r = Build.VERSION_CODES.R
        val placed = OverlayPlacement.Placed { _, _ -> }
        for (placement in listOf(placed, OverlayPlacement.Content, OverlayPlacement.Fixed, OverlayPlacement.CaptureSpace)) {
            assertFalse("$placement fixed-size below R", OverlayHost.layoutDefaults(false, placement, q).displayFrame)
            assertTrue("$placement full-screen below R", OverlayHost.layoutDefaults(true, placement, q).displayFrame)
        }
        assertTrue(OverlayHost.layoutDefaults(false, placed, r).displayFrame)
        assertTrue(OverlayHost.layoutDefaults(false, OverlayPlacement.Content, r).displayFrame)
        assertFalse(OverlayHost.layoutDefaults(false, OverlayPlacement.Fixed, r).displayFrame)
        assertTrue(OverlayHost.layoutDefaults(true, OverlayPlacement.Fixed, r).noLimits)
        assertFalse(OverlayHost.layoutDefaults(false, placed, r).noLimits)
    }

    // ── Placement and refresh ─────────────────────────────────────────────

    @Test fun `a placed window is laid out before it is added, so it appears in place`() {
        val w = Docked()
        w.add()
        val atAdd = wm.added.single { it.first === w.view }.second
        assertEquals(1000 - 100, atAdd.x)
        assertEquals(2000 - 100, atAdd.y)
        assertEquals(1, w.layouts)
    }

    @Test fun `a display change lays out every placed window on that display and none on another`() {
        val a = Docked(); val b = Docked(offset = 10); val elsewhere = Docked()
        a.add(); b.add(); elsewhere.add(onDisplay = other)
        env[display] = testEnvironment(2000, 1000)

        host.onDisplayChanged(display)

        assertEquals(2000 - 100, a.p.x)
        assertEquals(1000 - 100 - 10, b.p.y)
        assertEquals("not this display's", 1, elsewhere.layouts)
        assertEquals(1, wm.updatesOf(a.view))
        assertEquals(1, wm.updatesOf(b.view))
        assertEquals(0, wm.updatesOf(elsewhere.view))
    }

    @Test fun `a refresh that moves nothing commits nothing`() {
        val a = Docked()
        a.add()
        host.onDisplayChanged(display)
        host.refresh(display, "bars", force = false)
        host.invalidateLayout(display)
        assertEquals(0, wm.updatesOf(a.view))
    }

    @Test fun `a bars refresh runs only the layouts laid out with another environment`() {
        val a = Docked()
        a.add()
        host.refresh(display, "bars", force = false)
        assertEquals("same environment: not run", 1, a.layouts)

        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 0, 84))
        host.refresh(display, "bars", force = false)
        assertEquals(2000 - 84 - 100, a.p.y)
        assertEquals(1, wm.updatesOf(a.view))
    }

    @Test fun `a window added mid-change is laid out again when the change reverts (the Thor blip)`() {
        val landscape = testEnvironment(1920, 1080)
        val blip = testEnvironment(1080, 1920)
        env[display] = landscape
        val a = Docked(); a.add()
        // The natural-portrait blip: a window added meanwhile (the icon,
        // re-raised every live cycle) sees portrait, and brings the others
        // to it.
        env[display] = blip
        val b = Docked(); b.add()
        assertEquals(1920 - 100, a.p.y)
        // The blip reverts; its display event finds both laid out for it.
        env[display] = landscape
        host.onDisplayChanged(display)
        assertEquals(1920 - 100, a.p.x)
        assertEquals(1920 - 100, b.p.x)
        assertEquals(1080 - 100, b.p.y)
    }

    @Test fun `a window placed from another one ends right whatever the order they were added in`() {
        val anchor = Docked()
        val follower = View(ctx)
        val fp = params(10, 10)
        // Added FIRST, so a single pass would lay it out before its anchor
        // moves.
        assertTrue(host.addOverlayWindow(follower, wm, fp, display, OverlayPlacement.Placed { _, lp ->
            lp.x = anchor.p.x - 20
            lp.y = anchor.p.y
        }))
        anchor.add()

        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 84, 0))
        host.onDisplayChanged(display)

        assertEquals(1000 - 84 - 100, anchor.p.x)
        assertEquals("follows in the same refresh", anchor.p.x - 20, fp.x)
    }

    @Test fun `a layout that never settles stops after the pass limit`() {
        var runs = 0
        val p = params(10, 10)
        host.addOverlayWindow(View(ctx), wm, p, display, OverlayPlacement.Placed { _, lp -> runs++; lp.x = lp.x + 1 })
        runs = 0
        host.invalidateLayout(display)
        assertEquals(4, runs)
    }

    @Test fun `a refresh asked for during a pass runs once more after it`() {
        var asked = false
        var runs = 0
        val p = params(10, 10)
        host.addOverlayWindow(View(ctx), wm, p, display, OverlayPlacement.Placed { _, _ ->
            runs++
            if (!asked) { asked = true; host.invalidateLayout(display) }
        })
        runs = 0
        asked = false
        host.invalidateLayout(display)
        assertEquals("the pass, then the nested one", 2, runs)
    }

    @Test fun `a window removed by an earlier layout in the pass is skipped`() {
        val victim = Docked()
        val killer = View(ctx)
        var kill = false
        host.addOverlayWindow(killer, wm, params(10, 10), display, OverlayPlacement.Placed { _, _ ->
            if (kill) host.removeOverlayWindow(victim.view)
        })
        victim.add()
        val before = victim.layouts
        kill = true
        host.invalidateLayout(display)
        assertEquals(before, victim.layouts)
    }

    @Test fun `a pinned window is re-pinned only when the size changed`() {
        val view = View(ctx)
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        host.addOverlayWindow(view, wm, p, display, OverlayPlacement.CaptureSpace)
        env[display] = testEnvironment(1000, 2000, rotation = 2)
        host.onDisplayChanged(display)
        assertEquals("a flip keeps the size", 0, wm.updatesOf(view))
        env[display] = testEnvironment(2000, 1000, rotation = 1)
        host.onDisplayChanged(display)
        assertEquals(2000, p.width)
        assertEquals(1000, p.height)
        assertEquals(1, wm.updatesOf(view))
    }

    @Test fun `a commit during a clean capture keeps the window blanked`() {
        val a = Docked(); a.add()
        val state = host.prepareForCleanCapture(display)
        env[display] = testEnvironment(2000, 1000)
        host.onDisplayChanged(display)
        assertEquals(2000 - 100, a.p.x)
        assertEquals(0f, a.p.alpha)
        host.restoreAfterCapture(state)
        assertEquals(1f, a.p.alpha)
    }

    // ── A failed read ─────────────────────────────────────────────────────

    /** What a read returns from R on when the window-metrics query fails:
     *  no insets, and the size the display read before at the same rotation
     *  and panel size (DisplayWindowContexts.knownSize), here the real
     *  read's; for a geometry never read, the coarse read's, which need not
     *  be the display's (the Thor's read 1240x1080 for 1920x1080). */
    private fun failedRead(w: Int = 1000, h: Int = 2000, rotation: Int = 0) =
        testEnvironment(w, h, rotation = rotation, insetsKnown = false, provisional = true)

    @Test fun `a failed read moves and resizes nothing while the geometry is the same`() {
        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 84, 0))
        val a = Docked(); a.add()
        val full = View(ctx)
        val fp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        host.addOverlayWindow(full, wm, fp, display, OverlayPlacement.CaptureSpace)
        env[display] = failedRead()

        host.refresh(display, "bars", force = false)
        host.invalidateLayout(display)
        host.onDisplayChanged(display)

        assertEquals("still clear of the bar", 1000 - 84 - 100, a.p.x)
        assertEquals(1000, fp.width)
        assertEquals(0, wm.updatesOf(a.view))
        assertEquals(0, wm.updatesOf(full))
        assertEquals("a one-off decision reads the last real one too", 84, host.environment(display)!!.covered.right)
    }

    @Test fun `a window added during a failed read is placed clear of the bars the last real read saw`() {
        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 84, 0))
        Docked().add()
        env[display] = failedRead()
        val b = Docked(); b.add()
        assertEquals(1000 - 84 - 100, b.p.x)
    }

    @Test fun `a failed read after a resize at the same rotation does not keep the old read`() {
        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 84, 0))
        val a = Docked(); a.add()
        val full = View(ctx)
        val fp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        host.addOverlayWindow(full, wm, fp, display, OverlayPlacement.CaptureSpace)
        // A resolution switch or a fold, no rotation: the read fails, and
        // its size is the new panel's, not the last read's.
        env[display] = failedRead(w = 1200, h = 2000)

        host.onDisplayChanged(display)

        assertEquals("re-pinned to the new size", 1200, fp.width)
        assertEquals("docked on the new edge, as best it can", 1200 - 100, a.p.x)
    }

    @Test fun `across a rotation a failed read places against the stand-in, and the next real read places again`() {
        val a = Docked(); a.add()
        env[display] = failedRead(w = 2000 - 84, h = 1000, rotation = 1)
        host.onDisplayChanged(display)
        assertEquals("on the rotated display, as best it can", 2000 - 84 - 100, a.p.x)

        // The next real read, unforced (a bar heard), finds the window laid
        // out against something else.
        env[display] = testEnvironment(2000, 1000, rotation = 1)
        host.refresh(display, "bars", force = false)
        assertEquals(2000 - 100, a.p.x)
        assertEquals(1000 - 100, a.p.y)
    }

    // ── Hearing the bars ──────────────────────────────────────────────────

    /** Insets as a window below the bars hears them, with the navigation
     *  bar [shown] or not. */
    private fun barsInsets(shown: Boolean): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 84))
        .setVisible(WindowInsets.Type.navigationBars(), shown)
        .setInsets(WindowInsets.Type.statusBars(), android.graphics.Insets.of(0, 60, 0, 0))
        .setVisible(WindowInsets.Type.statusBars(), true)
        .build()

    @Test fun `below the bars a bar shown or hidden refreshes the display once, and the window's own handler still runs`() {
        var ownDispatches = 0
        val a = object : View(ctx) {
            override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                ownDispatches++
                return super.onApplyWindowInsets(insets)
            }
        }
        val b = Docked()
        host.addOverlayWindow(a, wm, params(10, 10), display, OverlayPlacement.Placed { _, _ -> })
        b.add()
        // The windows' first layouts dispatch their insets too; then the
        // game hides its navigation bar.
        idle()
        a.dispatchApplyWindowInsets(barsInsets(shown = false))
        idle()
        env[display] = testEnvironment(1000, 2000, shownBars = Sides(0, 0, 0, 84))
        reads = 0
        ownDispatches = 0

        a.dispatchApplyWindowInsets(barsInsets(shown = true))
        b.view.dispatchApplyWindowInsets(barsInsets(shown = true))
        idle()

        assertEquals("one refresh for both windows hearing it", 1, reads)
        assertEquals(1, ownDispatches)
        assertEquals(2000 - 84 - 100, b.p.y)
    }

    @Test fun `a window's own move re-dispatches its insets and reads nothing`() {
        val a = Docked()
        a.add()
        idle()
        a.view.dispatchApplyWindowInsets(barsInsets(shown = true))
        idle()
        reads = 0
        // Every frame of the icon's drag and glide moves its window, and the
        // platform re-dispatches the insets of a window whose frame changed.
        repeat(5) {
            a.view.dispatchApplyWindowInsets(barsInsets(shown = true))
            idle()
        }
        assertEquals(0, reads)
    }

    @Test fun `a display event with nothing to place reads nothing`() {
        host.addOverlayWindow(View(ctx), wm, params(10, 10), display, OverlayPlacement.Content)
        reads = 0
        host.onDisplayChanged(display)
        host.onDisplayChanged(other)
        assertEquals(0, reads)
    }

    @Test fun `above the bars nothing listens to a window's insets`() {
        val a11y = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also {
            it.environmentReader = { id -> reads++; env[id] }
        }
        val a = Docked()
        a.add(h = a11y)
        idle()
        reads = 0
        a.view.dispatchApplyWindowInsets(WindowInsets.Builder().build())
        idle()
        assertEquals(0, reads)
        a11y.removeAll()
    }
}
