package com.playtranslate.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.displaySizePx
import com.playtranslate.testutil.testEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [FloatingOverlayIcon.paintedDisc] describes the disc as onDraw paints it,
 * in screen pixels, for a frame to be read against ([GamePicture]): the
 * centre half a radius past the dock line (the view's centre) on the
 * off-screen side, the rim's stroke, the orbit's largest step and the
 * display's size. Nothing without a window to stand in.
 */
@RunWith(RobolectricTestRunner::class)
class FloatingOverlayIconPaintedDiscTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val density = ctx.resources.displayMetrics.density

    private fun docked(edge: FloatingOverlayIcon.Edge): Pair<FloatingOverlayIcon, WindowManager.LayoutParams> {
        val icon = FloatingOverlayIcon(ctx)
        val p = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        icon.params = p
        icon.setSlot(edge.ordinal, 0.5f)
        icon.layoutAtRest(testEnvironment(1080, 2400, belowBars = false), p)
        return icon to p
    }

    @Test fun `a right-docked icon's disc sits half a radius past the dock line and shows toward the left`() {
        val (icon, p) = docked(FloatingOverlayIcon.Edge.RIGHT)
        val d = icon.paintedDisc()!!
        val r = (IconDiscStyle.DIAMETER_DP * density).toInt() / 2f
        assertEquals(p.x + icon.viewSizePx / 2f, d.dockX, 0.01f)
        assertEquals(d.dockX + r / 2, d.cx, 0.01f)
        assertEquals(p.y + icon.viewSizePx / 2f, d.cy, 0.01f)
        assertEquals(r, d.radius, 0.01f)
        assertEquals(IconDiscStyle.BORDER_DP * density, d.strokePx, 0.01f)
        assertTrue(d.screenIsLeftOfDock)
        assertEquals(4, d.driftPx)
        val size = ctx.displaySizePx()
        assertEquals(size.x, d.displayWidth)
        assertEquals(size.y, d.displayHeight)
    }

    @Test fun `a left-docked icon mirrors`() {
        val (icon, p) = docked(FloatingOverlayIcon.Edge.LEFT)
        val d = icon.paintedDisc()!!
        assertEquals(p.x + icon.viewSizePx / 2f, d.dockX, 0.01f)
        assertEquals(d.dockX - d.radius / 2, d.cx, 0.01f)
        assertFalse(d.screenIsLeftOfDock)
    }

    @Test fun `while a gesture owns the window there is no disc`() {
        val (icon, _) = docked(FloatingOverlayIcon.Edge.RIGHT)
        assertTrue(icon.paintedDisc() != null)
        // A finger down: the gesture owns the window until it ends.
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try {
            icon.onTouchEvent(down)
        } finally {
            down.recycle()
        }
        assertNull(icon.paintedDisc())
    }

    @Test fun `without window params there is no disc`() {
        assertNull(FloatingOverlayIcon(ctx).paintedDisc())
    }
}
