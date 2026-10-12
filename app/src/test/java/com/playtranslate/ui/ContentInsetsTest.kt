package com.playtranslate.ui

import com.playtranslate.overlay.Sides
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the capture sheet's body and the workspace's card keep clear of,
 * from their own window's insets ([ContentInsets]): the bars shown, the
 * keyboard, and the display cutout on every side. A full-screen window of
 * ours is laid into the cutout, and Android puts one only on a short edge,
 * which is the bottom of a phone held upside down.
 */
class ContentInsetsTest {

    private fun shown(
        nav: Sides = Sides.NONE,
        navShown: Boolean = false,
        statusTop: Int = 0,
        statusShown: Boolean = false,
        cutout: Sides = Sides.NONE,
        ime: Int = 0,
        imeShown: Boolean = false,
    ) = ContentInsets.ofShown(nav, navShown, statusTop, statusShown, cutout, ime, imeShown)

    @Test fun `with the bars hidden the cutout counts on every side`() {
        assertEquals(
            ContentInsets(top = 90, left = 0, right = 0, bottom = 0, imeLift = 0),
            shown(cutout = Sides(0, 90, 0, 0)),
        )
        assertEquals("a notch at the bottom: a phone upside down", 90, shown(cutout = Sides(0, 0, 0, 90)).bottom)
        assertEquals(90, shown(cutout = Sides(90, 0, 0, 0)).left)
        assertEquals(90, shown(cutout = Sides(0, 0, 90, 0)).right)
    }

    @Test fun `a shown bar and the cutout on one side take the larger`() {
        val bottomBar = Sides(0, 0, 0, 132)
        assertEquals(132, shown(nav = bottomBar, navShown = true, cutout = Sides(0, 0, 0, 90)).bottom)
        assertEquals(90, shown(nav = bottomBar, navShown = false, cutout = Sides(0, 0, 0, 90)).bottom)
        val sideBar = Sides(0, 0, 132, 0)
        assertEquals("landscape three-button navigation", 132, shown(nav = sideBar, navShown = true).right)
        assertEquals(145, shown(statusTop = 145, statusShown = true, cutout = Sides(0, 90, 0, 0)).top)
    }

    @Test fun `the keyboard lifts the content only while it is up`() {
        assertEquals(800, shown(ime = 800, imeShown = true).imeLift)
        assertEquals(0, shown(ime = 800, imeShown = false).imeLift)
    }

    @Test fun `below R the stable insets tell the bar from the keyboard, and the cutout counts top and bottom`() {
        fun legacy(current: Int, cutoutBottom: Int = 0) =
            ContentInsets.ofLegacy(
                currentBottom = current, stableBottom = 126,
                currentTop = 63, stableTop = 63, cutoutTop = 0, cutoutBottom = cutoutBottom,
            )
        assertEquals("the bar shown", 126, legacy(current = 126).bottom)
        assertEquals("the bars hidden", 0, legacy(current = 0).bottom)
        val typing = legacy(current = 900)
        assertEquals(126, typing.bottom)
        assertEquals(900, typing.imeLift)
        assertEquals("a notch at the bottom, the bars hidden", 80, legacy(current = 0, cutoutBottom = 80).bottom)
        assertEquals(0, legacy(current = 126).left)
        assertEquals(63, legacy(current = 126).top)
    }
}
