package com.playtranslate.ui

import com.playtranslate.overlay.Sides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IconDock]: where the floating icon's window goes for a dock slot, per
 * rotation and bar side. Numbers are the Moto G (2025)'s: a 720x1604 display
 * at 280 dpi, an 84 px (48 dp) three-button navigation bar that sits at the
 * bottom in portrait, on the right in rotation 90 and on the left in rotation
 * 270, an 80 dp (140 px) icon window and the 48 dp (84 px) gesture clearance.
 * The window's centre sits on the dock line, so x is the line less half the
 * window.
 */
class IconDockTest {

    private val view = 140
    private val half = view / 2
    private val gesture = 84
    private val bar = 84
    private val none = Sides.NONE

    private fun place(screenW: Int, screenH: Int, insets: Sides, left: Boolean, f: Float = 0.5f) =
        IconDock.place(screenW, screenH, insets, left, f, view, gesture)

    @Test fun `portrait with the bar at the bottom puts both docks on the display edge`() {
        val insets = Sides(0, 0, 0, bar)
        assertEquals(720 - half, place(720, 1604, insets, left = false).x)
        assertEquals(-half, place(720, 1604, insets, left = true).x)
    }

    @Test fun `a bar on the bottom edge lifts the range by its height, and the whole disc clears it`() {
        val insets = Sides(0, 0, 0, bar)
        val shown = IconDock.verticalRange(1604, insets, gesture)
        val hidden = IconDock.verticalRange(1604, none, gesture)
        assertEquals(84..1520, hidden)
        assertEquals("the clearance inside the bar's dock line", 84..(1604 - bar - gesture), shown)
        val lowest = IconDock.centreY(1604, insets, gesture, 1f)
        assertTrue("the disc's bottom above the bar", lowest + half <= 1604 - bar)
        assertEquals(802 - half, place(720, 1604, none, left = false).y)
    }

    @Test fun `rotation 90 with the bar on the right moves the right dock inboard and leaves the left`() {
        val insets = Sides(0, 0, bar, 0)
        assertEquals("line on the bar's inner edge", 1604 - bar - half, place(1604, 720, insets, left = false).x)
        assertEquals(-half, place(1604, 720, insets, left = true).x)
    }

    @Test fun `rotation 270 with the bar on the left moves the left dock inboard and keeps the right on the display edge`() {
        val insets = Sides(bar, 0, 0, 0)
        assertEquals(bar - half, place(1604, 720, insets, left = true).x)
        assertEquals("not past the display (the platform shift was 1618)", 1604 - half, place(1604, 720, insets, left = false).x)
    }

    @Test fun `a hidden bar contributes nothing so an immersive game keeps the docks on the display edges`() {
        assertEquals(-half, place(1604, 720, none, left = true).x)
        assertEquals(1604 - half, place(1604, 720, none, left = false).x)
    }

    @Test fun `a cutout on the dock's side moves the line inboard like a bar`() {
        val insets = Sides(90, 0, 0, 0)
        assertEquals(90 - half, place(1604, 720, insets, left = true).x)
    }

    @Test fun `a top inset lifts the range's top the same way, each edge by its own inset`() {
        val range = IconDock.verticalRange(1604, Sides(0, 70, 0, 120), gesture)
        assertEquals((70 + gesture)..(1604 - 120 - gesture), range)
    }

    @Test fun `fraction round-trips through centreY, and an unset fraction reads as the middle`() {
        val insets = Sides(0, 0, 0, bar)
        for (f in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            val cy = IconDock.centreY(1604, insets, gesture, f)
            assertEquals(f, IconDock.fraction(cy, 1604, insets, gesture), 0.001f)
        }
        assertEquals(IconDock.centreY(1604, insets, gesture, 0.5f), IconDock.centreY(1604, insets, gesture, -1f))
        assertEquals(IconDock.centreY(1604, insets, gesture, 0.5f), IconDock.centreY(1604, insets, gesture, 7f))
    }

    @Test fun `a screen shorter than the two clearances collapses the range to the top clearance`() {
        assertEquals(84..84, IconDock.verticalRange(100, none, gesture))
        assertEquals(84, IconDock.centreY(100, none, gesture, 1f))
        assertEquals(0.5f, IconDock.fraction(84, 100, none, gesture), 0f)
    }
}
