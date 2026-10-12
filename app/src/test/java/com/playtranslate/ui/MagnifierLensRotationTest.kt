package com.playtranslate.ui

import android.content.Context
import android.graphics.Insets
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The magnifier lens and its window's size. The window is sized to the
 * screen it was built for, so a lens left at the old size after a rotation
 * caught off-card taps on only part of the new screen (Moto G, 2026-10-09:
 * "tapping outside it doesn't always dismiss it"). A show for another screen
 * size now rebuilds the window at that size (the owner ends a session whose
 * display changed, [DragLookupController.onDisplayChanged], so this covers a
 * show that lands first), every show, dismissal and sticky transition
 * reports whether it had a
 * window to act on, a rebuild that cannot attach reports the lost lens
 * through its dismiss path, and a touch outside the window dismisses a
 * sticky lens from the root, the only view the platform's outside-touch
 * notification reaches (Codex adversarial reviews, 2026-10-10).
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensRotationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

    /** Records every window the lens adds, so the test can reach its root;
     *  [failNextAdd] refuses one add the way the platform does (a thrown
     *  BadTokenException), which the host reports as not attached. */
    private class RecordingWindowManager(real: WindowManager) : WindowManager by real {
        val added = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
        val removed = mutableListOf<View>()
        var failNextAdd = false
        private val real = real
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failNextAdd) {
                failNextAdd = false
                throw WindowManager.BadTokenException("test: window refused")
            }
            added += view to (params as WindowManager.LayoutParams)
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
    private var dismissals = 0

    init {
        lens.onDismiss = { dismissals++ }
    }

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private fun lastWindow() = wm.added.last()

    @Test fun `a show at another screen size rebuilds the window at that size`() {
        lens.show(100, 300, 720, 1604)
        val first = lastWindow()
        assertEquals(720, first.second.width)
        assertEquals(1604, first.second.height)

        lens.show(100, 300, 1604, 720)

        val second = lastWindow()
        assertTrue("a new root", second.first !== first.first)
        assertEquals(1604, second.second.width)
        assertEquals(720, second.second.height)
        assertEquals("a rebuild is not a dismissal", 0, dismissals)
    }

    @Test fun `a show at the same size reuses the window`() {
        lens.show(100, 300, 720, 1604)
        val first = lastWindow()
        lens.show(200, 400, 720, 1604)
        assertTrue(lastWindow().first === first.first)
        assertEquals(1, wm.added.size)
    }

    @Test fun `dismiss reports whether there was a window to dismiss`() {
        assertFalse("nothing shown yet", lens.dismiss())
        assertEquals(0, dismissals)
        lens.show(100, 300, 720, 1604)
        assertTrue(lens.dismiss())
        assertEquals(1, dismissals)
        assertFalse("already gone", lens.dismiss())
        assertEquals(1, dismissals)
    }

    @Test fun `a show whose rebuild cannot attach reports the lost lens and returns false`() {
        assertTrue(lens.show(100, 300, 720, 1604))
        lens.makeInteractive()
        wm.failNextAdd = true

        assertFalse(lens.show(100, 300, 1604, 720))

        assertEquals("the lens the user had is gone", 1, dismissals)
        assertFalse(lens.isInteractive)
        assertFalse("nothing to make sticky", lens.makeInteractive())
        assertEquals(1, wm.added.size)
    }

    @Test fun `a first show that cannot attach returns false without a dismissal`() {
        wm.failNextAdd = true
        assertFalse(lens.show(100, 300, 720, 1604))
        assertEquals("nothing was lost", 0, dismissals)
        assertFalse(lens.makeInteractive())
    }

    private fun sideBar(right: Int): WindowInsets =
        WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, right, 0))
            .setVisible(WindowInsets.Type.navigationBars(), true)
            .build()

    private fun column(root: View): Float = (root as ViewGroup).getChildAt(0).translationX

    /** The sticky arrow's offset inside the column. */
    private fun arrowOffset(): Int {
        val view = MagnifierLens::class.java.getDeclaredField("lensView")
            .apply { isAccessible = true }.get(lens)!!
        return view.javaClass.getDeclaredField("arrowOffsetX").apply { isAccessible = true }.getInt(view)
    }

    @Test fun `a side bar the window hears moves a still column inboard, with no further show`() {
        lens.show(720, 300, 720, 1604)   // the finger on the right edge: the column pins to it
        val root = lastWindow().first
        val atEdge = column(root)

        root.dispatchApplyWindowInsets(sideBar(84))

        assertEquals("inboard of the bar", atEdge - 84, column(root), 0f)
    }

    @Test fun `a sticky card follows the bar and keeps its arrow on the finger`() {
        lens.show(500, 300, 720, 1604)
        assertTrue(lens.makeInteractive())
        val root = lastWindow().first
        val columnBefore = column(root)
        val arrowBefore = arrowOffset()

        root.dispatchApplyWindowInsets(sideBar(84))

        val columnAfter = column(root)
        assertTrue("the column moved inboard", columnAfter < columnBefore)
        assertEquals(
            "the arrow stayed over the finger",
            (columnBefore - columnAfter).toInt(), arrowOffset() - arrowBefore,
        )
    }

    @Test fun `the bar going moves the column back out`() {
        lens.show(720, 300, 720, 1604)
        val root = lastWindow().first
        val atEdge = column(root)
        root.dispatchApplyWindowInsets(sideBar(84))
        assertEquals(atEdge - 84, column(root), 0f)

        root.dispatchApplyWindowInsets(sideBar(0))

        assertEquals(atEdge, column(root), 0f)
    }

    @Test fun `a touch outside the window dismisses a sticky lens from the root`() {
        lens.show(100, 300, 720, 1604)
        lens.makeInteractive()
        val root = lastWindow().first

        val outside = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        try { assertTrue(root.dispatchTouchEvent(outside)) } finally { outside.recycle() }

        assertEquals(1, dismissals)
        assertFalse(lens.isInteractive)
    }

    @Test fun `a touch outside the window leaves a zoom lens alone`() {
        lens.show(100, 300, 720, 1604)
        val root = lastWindow().first

        val outside = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_OUTSIDE, 0f, 0f, 0)
        try { root.dispatchTouchEvent(outside) } finally { outside.recycle() }

        assertEquals(0, dismissals)
    }
}
