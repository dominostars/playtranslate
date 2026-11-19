package com.playtranslate.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
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
 * Typing in the detached window: a text field of the page taking focus
 * makes the window focusable with the keyboard, and losing it gives the keys
 * back to the game; the window is raised by the keyboard's overlap, stays
 * raised while the keyboard shows (the overlap of a raised window reads
 * zero), and comes back down, the field unfocused, when the keyboard goes.
 */
@RunWith(RobolectricTestRunner::class)
class DetachedWindowTypingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val d0 = Display.DEFAULT_DISPLAY
    private val g = DetachedWindowGeometry
    private val room = g.room(1280, 800, Sides.NONE)
    private lateinit var overlayHost: OverlayHost
    private lateinit var window: DetachedWindow
    private lateinit var field: EditText

    private val host = object : DetachedWindow.Host {
        override fun onCapture(displayId: Int) {}
        override fun onToggleAuto(displayId: Int) {}
        override fun onEditRegion(displayId: Int) {}
        override fun onMinimize(displayId: Int) {}
        override fun onExit(displayId: Int) {}
        override fun onDocked(displayId: Int, side: DetachedWindowGeometry.Side, fingerY: Int) {}
        override fun onTouched(displayId: Int) {}
        override fun quickSettings(displayId: Int, leave: () -> Unit): List<QuickSetting> = emptyList()
        override fun regionIsFullScreen(displayId: Int) = true
        override fun exitTurnsOff() = true
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private val rest = Frame(400, 300, 420, 400)

    @Before fun setUp() {
        clearPrefs()
        prefs.setWindowBounds(d0, landscape = true, bounds = g.toBounds(rest, room, 1f))
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(d0)
        val displayCtx = ctx.createDisplayContext(display)
        val wm = RecordingWindowManager(OwnWindows.manager(displayCtx)!!)
        overlayHost = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        overlayHost.environmentReader = { testEnvironment(1280, 800) }
        window = DetachedWindow(displayCtx, wm, d0, overlayHost, prefs, host)
        assertTrue(window.show())
        field = EditText(ctx)
        window.setPage(FrameLayout(ctx).apply { addView(field, FrameLayout.LayoutParams(-1, -1)) })
        idle()
    }

    @After fun tearDown() {
        window.remove()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        clearPrefs()
    }

    private fun params() = window.root.layoutParams as WindowManager.LayoutParams

    private fun frameY() = params().y + 2

    private val focusable get() = params().flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0

    /** The keyboard as the window is told of it: shown or not, covering
     *  [overlap] px of the window's bottom. Everything it causes is
     *  synchronous, and the looper is not idled after it: the test runtime's
     *  own window would then dispatch its real insets, which have no
     *  keyboard, over the ones given here. */
    private fun keyboard(shown: Boolean, overlap: Int = 0) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, overlap))
            .setVisible(WindowInsetsCompat.Type.ime(), shown)
            .build()
        window.root.dispatchApplyWindowInsets(insets.toWindowInsets()!!)
    }

    @Test fun `at rest the window takes no key focus`() {
        assertFalse(focusable)
        assertEquals(rest.y, frameY())
    }

    @Test fun `a text field taking focus makes the window focusable for typing`() {
        assertTrue(field.requestFocus())
        idle()

        assertTrue(focusable)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            params().softInputMode,
        )
    }

    @Test fun `the field losing focus gives the keys back`() {
        field.requestFocus()
        idle()

        window.root.requestFocus()
        idle()

        assertFalse(focusable)
    }

    private fun key(action: Int, code: Int = KeyEvent.KEYCODE_BACK): Boolean =
        window.root.dispatchKeyEvent(KeyEvent(action, code))

    @Test fun `Back ends the typing on its release, and is not the window's while nothing is typed`() {
        assertFalse("nothing typed: not taken", key(KeyEvent.ACTION_DOWN))
        assertFalse(key(KeyEvent.ACTION_UP))

        field.requestFocus()
        idle()
        assertTrue("taken, and nothing ends on the press", key(KeyEvent.ACTION_DOWN))
        assertTrue(focusable)

        assertTrue(key(KeyEvent.ACTION_UP))
        idle()
        assertFalse("the keys are the game's again", focusable)
        assertFalse(field.hasFocus())
    }

    @Test fun `the keyboard raises the window by its overlap and it stays raised while the keyboard shows`() {
        field.requestFocus()
        idle()

        keyboard(shown = true, overlap = 120)
        assertEquals(rest.y - 120, frameY())

        // Raised, the window is clear of the keyboard: its inset reads zero.
        keyboard(shown = true, overlap = 0)
        assertEquals("still raised", rest.y - 120, frameY())
        assertTrue("still typing", focusable)
    }

    @Test fun `a keyboard that grows raises the window further`() {
        field.requestFocus()
        idle()
        keyboard(shown = true, overlap = 120)

        keyboard(shown = true, overlap = 40)

        assertEquals(rest.y - 160, frameY())
    }

    @Test fun `the keyboard going puts the window back and ends the typing`() {
        field.requestFocus()
        idle()
        keyboard(shown = true, overlap = 120)

        keyboard(shown = false)

        assertEquals(rest.y, frameY())
        assertFalse("the field gave its focus up", field.isFocused)
        assertFalse("and the window its key focus", focusable)
    }

    @Test fun `an inset before the keyboard has shown ends nothing`() {
        field.requestFocus()
        idle()

        // The window turning focusable dispatches insets while the keyboard
        // is still on its way up.
        keyboard(shown = false)

        assertTrue(field.isFocused)
        assertTrue(focusable)
    }

    @Test fun `the lift never takes the window above the room`() {
        field.requestFocus()
        idle()

        keyboard(shown = true, overlap = 5000)

        assertEquals(0, frameY())
    }
}
