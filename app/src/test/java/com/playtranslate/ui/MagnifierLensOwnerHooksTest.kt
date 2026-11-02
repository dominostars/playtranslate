package com.playtranslate.ui

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.displaySizePx
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * A sticky lens's owner hook ([MagnifierLens.onOffCardDown]): set, a DOWN
 * off the card (beside its column, or in the column away from the card) goes
 * to the owner with its screen position, and the lens stays; unset, it
 * dismisses the lens, as every lens did before the hook. A touch outside the
 * window dismisses either way, and B on a focused lens dismisses it.
 */
@RunWith(RobolectricTestRunner::class)
class MagnifierLensOwnerHooksTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

    private class RecordingWindowManager(private val real: WindowManager) : WindowManager by real {
        val added = mutableListOf<View>()
        override fun addView(view: View, params: ViewGroup.LayoutParams) {
            added += view
            real.addView(view, params)
        }
    }

    private val wm = RecordingWindowManager(OwnWindows.manager(ctx)!!)
    private val lens = MagnifierLens(ctx, wm, Display.DEFAULT_DISPLAY, host)
    private var dismissals = 0
    private val downs = mutableListOf<Pair<Float, Float>>()

    @Before fun setUp() {
        lens.onDismiss = { dismissals++ }
    }

    @After fun tearDown() {
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    /** A sticky lens anchored near the right edge, low on the screen, so
     *  its column leaves room on the left and the card leaves room above;
     *  returns its full-screen root window. */
    private fun stickyLens(): ViewGroup {
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(screen.x - 10, screen.y - 40, screen.x, screen.y))
        assertTrue(lens.makeInteractive())
        shadowOf(Looper.getMainLooper()).idle()
        return wm.added.last() as ViewGroup
    }

    private fun touch(root: ViewGroup, action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, x, y, 0)
        return root.dispatchTouchEvent(ev).also { ev.recycle() }
    }

    private fun key(root: ViewGroup, action: Int, code: Int) {
        root.dispatchKeyEvent(KeyEvent(action, code))
    }

    private fun hooked() {
        lens.onOffCardDown = { x, y -> downs += x to y }
    }

    @Test fun `with the hook a DOWN beside the column goes to the owner and the lens stays`() {
        hooked()
        val root = stickyLens()

        assertTrue(touch(root, MotionEvent.ACTION_DOWN, 1f, 5f))

        assertEquals(listOf(1f to 5f), downs)
        assertEquals(0, dismissals)
        assertTrue(lens.isInteractive)
    }

    @Test fun `with the hook a DOWN in the column away from the card goes to the owner too`() {
        hooked()
        val root = stickyLens()
        val screen = ctx.displaySizePx()

        // Near the right edge (inside the column), at the very top: the card
        // sits low, above the anchor.
        touch(root, MotionEvent.ACTION_DOWN, (screen.x - 20).toFloat(), 2f)

        assertEquals(listOf((screen.x - 20).toFloat() to 2f), downs)
        assertEquals(0, dismissals)
        assertTrue(lens.isInteractive)
    }

    @Test fun `without the hook a DOWN beside the column dismisses the lens`() {
        val root = stickyLens()

        touch(root, MotionEvent.ACTION_DOWN, 1f, 5f)

        assertEquals(1, dismissals)
        assertFalse(lens.isInteractive)
    }

    @Test fun `a touch outside the window dismisses the lens, hook or not`() {
        hooked()
        val root = stickyLens()

        touch(root, MotionEvent.ACTION_OUTSIDE, 0f, 0f)

        assertEquals(1, dismissals)
        assertEquals(emptyList<Pair<Float, Float>>(), downs)
    }

    @Test fun `B dismisses the lens, hook or not`() {
        hooked()
        val root = stickyLens()

        key(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        key(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)

        assertEquals(1, dismissals)
    }

    /** A gamepad attached, as a handheld's built-in controls are. */
    private fun controllerAttached() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
        assertTrue(hasNavInputDevice(ctx))
    }

    @Test fun `with a controller attached a lens takes it, and one built not to leaves it to its owner`() {
        controllerAttached()

        stickyLens()
        assertTrue("the default lens holds the controller", lens.isConsumingController)

        val owned = MagnifierLens(ctx, wm, Display.DEFAULT_DISPLAY, host, takesControllerFocus = false)
        val screen = ctx.displaySizePx()
        assertTrue(owned.show(10, screen.y - 40, screen.x, screen.y))
        assertTrue(owned.makeInteractive())
        assertTrue(owned.isInteractive)
        assertFalse("this one leaves the controller to its owner", owned.isConsumingController)
        owned.dismiss()
    }

    @Test fun `the hooks survive a reset and a second promotion, as a card switching words does`() {
        hooked()
        stickyLens()
        lens.resetToZoom()
        val root = stickyLens()

        touch(root, MotionEvent.ACTION_DOWN, 1f, 5f)

        assertEquals(1, downs.size)
        assertEquals(0, dismissals)
    }
}
