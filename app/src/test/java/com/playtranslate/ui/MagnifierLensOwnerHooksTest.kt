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
 * A sticky lens's owner hooks. [MagnifierLens.onOffCardDown]: set, a DOWN
 * off the card (beside its column, or in the column away from the card) goes
 * to the owner with its screen position, and the lens stays; unset, it
 * dismisses the lens, as every lens did before the hook. A touch outside the
 * window dismisses either way. [MagnifierLens.onBackKey]: set, B on the lens
 * goes to the owner and the lens stays; unset, B dismisses it; the
 * platform's Back callback does what B does, once the lens is sticky. A lens
 * takes the controller when one is attached as it turns sticky.
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
    private var backs = 0

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

    private fun backHooked() {
        lens.onBackKey = { backs++ }
    }

    private fun pressB(root: ViewGroup) {
        key(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)
        key(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)
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

    @Test fun `without the B hook B dismisses the lens, an off-card hook or not`() {
        hooked()
        val root = stickyLens()

        pressB(root)

        assertEquals(1, dismissals)
    }

    /** Back as the platform's callback delivers it (target 36 on Android
     *  16), where the key never arrives. */
    private fun systemBack(root: ViewGroup) {
        val callback = root.javaClass.getDeclaredField("backCallback")
            .apply { isAccessible = true }.get(root) as android.window.OnBackInvokedCallback
        callback.onBackInvoked()
    }

    @Test fun `the platform's Back does what B does, the owner's with the hook and the dismissal without`() {
        backHooked()
        val root = stickyLens()

        systemBack(root)
        assertEquals(1, backs)
        assertEquals(0, dismissals)

        lens.onBackKey = null
        systemBack(root)
        assertEquals(1, dismissals)
    }

    @Test fun `while the lens is not sticky the platform's Back is not its, before its first promotion or after a reset`() {
        backHooked()
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(screen.x - 10, screen.y - 40, screen.x, screen.y))
        shadowOf(Looper.getMainLooper()).idle()
        val root = wm.added.last() as ViewGroup

        systemBack(root)

        // Back to the zoom state, as a card switching words passes through:
        // the B hook stays wired from the promotion.
        assertTrue(lens.makeInteractive())
        lens.resetToZoom()
        systemBack(root)

        assertEquals(0, backs)
        assertEquals(0, dismissals)
    }

    @Test fun `with the B hook B goes to the owner and the lens stays`() {
        backHooked()
        val root = stickyLens()

        pressB(root)

        assertEquals(1, backs)
        assertEquals(0, dismissals)
        assertTrue(lens.isInteractive)
    }

    /** A gamepad attached, as a handheld's built-in controls are. */
    private fun controllerAttached() {
        val pad = ShadowInputDevice.makeInputDeviceNamed("pad")
        ReflectionHelpers.setField(pad, "mId", 42)
        ReflectionHelpers.setField(pad, "mSources", InputDevice.SOURCE_GAMEPAD)
        shadowOf(ctx.getSystemService(InputManager::class.java)).addInputDevice(pad)
        assertTrue(hasNavInputDevice(ctx))
    }

    @Test fun `a lens takes the controller when one is attached as it turns sticky`() {
        assertFalse("precondition: no controller", hasNavInputDevice(ctx))
        stickyLens()
        assertFalse("nothing to navigate with", lens.isConsumingController)

        controllerAttached()
        lens.resetToZoom()
        stickyLens()
        assertTrue(lens.isConsumingController)
    }

    @Test fun `the hooks survive a reset and a second promotion, as a card switching words does`() {
        hooked()
        backHooked()
        stickyLens()
        lens.resetToZoom()
        val root = stickyLens()

        touch(root, MotionEvent.ACTION_DOWN, 1f, 5f)
        pressB(root)

        assertEquals(1, downs.size)
        assertEquals(1, backs)
        assertEquals(0, dismissals)
    }

    // ── the shoulder buttons ([MagnifierLens.onShoulderKey]) ─────────────

    private val shoulders = mutableListOf<Boolean>()

    /** [code] [action]ed on [root], [repeat] times repeated; whether the
     *  window consumed it. */
    private fun sent(root: ViewGroup, action: Int, code: Int, repeat: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return root.dispatchKeyEvent(KeyEvent(now, now, action, code, repeat))
    }

    @Test fun `with the shoulder hook L1 and R1 go to the owner once a press, their repeats and releases consumed`() {
        lens.onShoulderKey = { shoulders += it }
        val root = stickyLens()

        assertTrue(sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1))
        assertTrue(sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, repeat = 1))
        assertTrue(sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1))
        assertTrue(sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1))
        assertTrue(sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1))

        assertEquals("R1 to the right, L1 to the left", listOf(true, false), shoulders)
        assertEquals("the owner closes the lens as it sees fit", 0, dismissals)
        assertTrue(lens.isInteractive)
    }

    @Test fun `without the shoulder hook L1 and R1 go unhandled`() {
        val root = stickyLens()

        assertFalse(sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1))
        assertFalse(sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1))

        assertEquals(0, dismissals)
        assertTrue(lens.isInteractive)
    }

    @Test fun `while the lens is not sticky the shoulder hook hears nothing, before its first promotion or after a reset`() {
        lens.onShoulderKey = { shoulders += it }
        val screen = ctx.displaySizePx()
        assertTrue(lens.show(screen.x - 10, screen.y - 40, screen.x, screen.y))
        shadowOf(Looper.getMainLooper()).idle()
        val root = wm.added.last() as ViewGroup

        sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)
        sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)

        // Back to the zoom state, as a card switching words passes through:
        // the hook stays wired from the promotion.
        assertTrue(lens.makeInteractive())
        lens.resetToZoom()
        assertFalse("a reset lens takes no press", sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1))
        sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)

        assertTrue(shoulders.isEmpty())
    }

    @Test fun `an owner that closes the lens at the press keeps its window until the release`() {
        controllerAttached()
        lens.onShoulderKey = { lens.dismiss() }
        val root = stickyLens()

        sent(root, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)

        assertEquals(1, dismissals)
        assertFalse(lens.isInteractive)
        assertTrue("the window stays, a key sink until the release", root.isAttachedToWindow)
        assertTrue("the release is the sink's", sent(root, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1))
        // The removal goes through the window churn gate, which may hold it
        // up to its linger cap.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse("then the window goes", root.isAttachedToWindow)
    }
}
