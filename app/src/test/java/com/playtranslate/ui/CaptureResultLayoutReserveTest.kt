package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.Sides
import com.playtranslate.overlay.OwnWindows
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The capture sheet decides one or two columns once, at build, against the
 * screen width less the sides it reserves: a side navigation bar's inset
 * whether or not the bar is showing, and the display cutout. A bar the game
 * shows later under the non-focusable sheet then only pads the body; it
 * never squeezes two columns below their minimum (Codex reviews,
 * 2026-10-11).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureResultLayoutReserveTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private var sheet: CaptureResultOverlay? = null

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    /** Opens a 1280x720 sheet reserving [reserveRight] px on the right, or,
     *  with null, whatever its own display's environment reserves (none on
     *  the test display). */
    private fun open(reserveRight: Int?): Boolean {
        val overlay = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = overlay
        if (reserveRight != null) overlay.sideReserveReader = { Sides(0, 0, reserveRight, 0) }
        // Density 1 here: 1280 px is 1280 dp, two 640 dp sections.
        overlay.show(1280, 720)
        shadowOf(Looper.getMainLooper()).idle()
        return CaptureResultOverlay::class.java.getDeclaredField("isSideBySide")
            .apply { isAccessible = true }.getBoolean(overlay)
    }

    @Test fun `a wide screen with nothing reserved gets two columns`() {
        assertTrue(open(reserveRight = 0))
    }

    @Test fun `the reserve is read from the sheet's own display`() {
        assertTrue("no bars on the test display", open(reserveRight = null))
    }

    @Test fun `a reserved side that would squeeze the columns keeps one`() {
        // 1280 less 700 leaves 580: under two 300 dp sections.
        assertFalse(open(reserveRight = 700))
    }
}
