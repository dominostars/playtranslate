package com.playtranslate.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.RegionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The icon menu's drag-to-select is armed for the whole screen outside the
 * card while the menu is open, so a thumb brushing the screen on its way to
 * a stick drew a region: any box wider than slop on both axes was accepted,
 * a capture fired and the sliver became the display's active region.
 * Measured on the Thor (2026-10-07), brushes and flicks were down for 42 to
 * 85 ms and aimed drawings for 340 ms or more, while the box's size told them
 * apart not at all, since a flick leaves a region-sized box. So a drag
 * released within [FloatingIconMenu.MIN_DRAG_MS] selects nothing and the menu
 * comes back as it was. That restore also covers the rejected branch that
 * used to leave the card hidden and the last dim frame drawn until the next
 * tap, which the overlay then ate.
 */
@RunWith(RobolectricTestRunner::class)
class FloatingIconMenuDragGateTest {

    private class Harness {
        val menu = FloatingIconMenu(ApplicationProvider.getApplicationContext<Context>())
        var selected: RegionEntry? = null
        var dismissed = 0
        val card: View = menu.javaClass.getDeclaredField("menuCard")
            .apply { isAccessible = true }.get(menu) as View

        init {
            menu.onRegionSelected = { selected = it }
            menu.onDismiss = { dismissed++ }
            menu.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
            )
            menu.layout(0, 0, W, H)
        }

        /** A touch [atMs] after the down, far from the card, which sits at the
         *  origin until positioned. */
        fun touch(action: Int, x: Float, y: Float, atMs: Long) {
            val ev = MotionEvent.obtain(T0, T0 + atMs, action, x, y, 0)
            try { menu.onTouchEvent(ev) } finally { ev.recycle() }
        }

        fun down() = touch(MotionEvent.ACTION_DOWN, X0, Y0, 0)
        fun move(dx: Float, dy: Float, atMs: Long) = touch(MotionEvent.ACTION_MOVE, X0 + dx, Y0 + dy, atMs)
        fun up(dx: Float, dy: Float, atMs: Long) = touch(MotionEvent.ACTION_UP, X0 + dx, Y0 + dy, atMs)
    }

    @Test fun `a drag released within the floor selects nothing and the menu comes back`() {
        val h = Harness()
        h.down()
        h.move(200f, 150f, atMs = 30)
        assertFalse("the card hides while the box is drawn", h.card.isVisible)
        h.up(200f, 150f, atMs = FloatingIconMenu.MIN_DRAG_MS - 1)
        assertNull(h.selected)
        assertEquals("not a tap outside the card", 0, h.dismissed)
        assertTrue("the card is back", h.card.isVisible)
    }

    @Test fun `a drag held past the floor selects its box`() {
        val h = Harness()
        h.down()
        h.move(200f, 150f, atMs = 30)
        h.up(200f, 150f, atMs = FloatingIconMenu.MIN_DRAG_MS)
        val r = h.selected
        assertNotNull(r)
        assertEquals(Y0 / H, r!!.top, EPS)
        assertEquals((Y0 + 150f) / H, r.bottom, EPS)
        assertEquals(X0 / W, r.left, EPS)
        assertEquals((X0 + 200f) / W, r.right, EPS)
        assertEquals(0, h.dismissed)
    }

    @Test fun `a slow drag that is a line selects nothing and the menu comes back`() {
        val h = Harness()
        h.down()
        h.move(200f, 0f, atMs = 30)
        h.up(200f, 0f, atMs = 400)
        assertNull(h.selected)
        assertEquals(0, h.dismissed)
        assertTrue(h.card.isVisible)
    }

    @Test fun `a drag the system cancels brings the menu back`() {
        val h = Harness()
        h.down()
        h.move(200f, 150f, atMs = 30)
        assertFalse(h.card.isVisible)
        h.touch(MotionEvent.ACTION_CANCEL, X0 + 200f, Y0 + 150f, atMs = 400)
        assertNull(h.selected)
        assertEquals(0, h.dismissed)
        assertTrue(h.card.isVisible)
    }

    @Test fun `a tap outside the card still dismisses`() {
        val h = Harness()
        h.down()
        h.up(0f, 0f, atMs = 60)
        assertNull(h.selected)
        assertEquals(1, h.dismissed)
    }

    private companion object {
        const val W = 1920
        const val H = 1080
        const val X0 = 1400f
        const val Y0 = 700f
        const val T0 = 1000L
        const val EPS = 1e-5f
    }
}
