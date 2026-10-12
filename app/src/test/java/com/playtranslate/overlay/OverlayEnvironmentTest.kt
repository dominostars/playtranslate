package com.playtranslate.overlay

import com.playtranslate.testutil.testEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each policy keeps a window clear of, per backend. Below the bars
 * (MediaProjection) a shown bar covers our windows; above them
 * (accessibility) nothing does, and only the cutout and the bars' room for
 * the pills count.
 */
class OverlayEnvironmentTest {

    private val cutout = Sides(0, 90, 0, 0)
    private val shown = Sides(0, 60, 0, 0)
    private val reserved = Sides(0, 60, 0, 84)

    private fun env(belowBars: Boolean) =
        testEnvironment(1080, 2400, belowBars = belowBars, cutout = cutout, shownBars = shown, reservedBars = reserved)

    @Test fun `below the bars, covered is the cutout and the bars shown`() {
        assertEquals(Sides(0, 90, 0, 0), env(belowBars = true).covered)
        assertEquals(Sides(0, 60, 0, 84).max(cutout), env(belowBars = true).fittedReserved)
    }

    @Test fun `above the bars, covered is the cutout alone, shown or reserved`() {
        assertEquals(cutout, env(belowBars = false).covered)
        assertEquals(cutout, env(belowBars = false).fittedReserved)
    }

    @Test fun `on Android 11 the reserve above the bars counts the bars the window is padded by`() {
        // Android 11 sends every bar to every window: the accessibility
        // backend's sheet hears a side bar it lies above and pads its body
        // by it (ContentInsets), so its columns must be decided without it.
        val android11Above = testEnvironment(
            1080, 2400, belowBars = false, hearsBars = true,
            cutout = cutout, shownBars = shown, reservedBars = Sides(0, 60, 84, 0),
        )
        assertEquals(Sides(0, 90, 84, 0), android11Above.fittedReserved)
        assertEquals("nothing lies over it", cutout, android11Above.covered)
    }

    @Test fun `the bar room counts every bar shown or not on both backends`() {
        assertEquals(Sides(0, 90, 0, 84), env(belowBars = true).barRoom)
        assertEquals(Sides(0, 90, 0, 84), env(belowBars = false).barRoom)
    }

    @Test fun `a bar shown below the bars moves covered, and shown and hidden are different environments`() {
        val hidden = testEnvironment(1080, 2400, shownBars = Sides.NONE, reservedBars = reserved)
        val visible = testEnvironment(1080, 2400, shownBars = Sides(0, 0, 0, 84), reservedBars = reserved)
        assertEquals(0, hidden.covered.bottom)
        assertEquals(84, visible.covered.bottom)
        assert(hidden != visible)
    }

    @Test fun `with no insets known every policy is empty`() {
        val e = testEnvironment(1080, 2400, insetsKnown = false)
        assertEquals(Sides.NONE, e.covered)
        assertEquals(Sides.NONE, e.fittedReserved)
        assertEquals(Sides.NONE, e.barRoom)
    }

    @Test fun `fitted is what the platform's own fit cleared in v3,3,1, per backend and version`() {
        assertEquals("below the bars: what covers", env(belowBars = true).covered, env(belowBars = true).fitted)
        assertEquals("above them on 12 and up: the cutout", cutout, env(belowBars = false).fitted)
        val android11Above = testEnvironment(
            1080, 2400, belowBars = false, hearsBars = true,
            cutout = cutout, shownBars = Sides(0, 60, 0, 84), reservedBars = reserved,
        )
        assertEquals("above them on 11: the shown bars too", Sides(0, 90, 0, 84), android11Above.fitted)
        assertEquals("the icon's dock still ignores them there", cutout, android11Above.covered)
    }

    @Test fun `windows hear the bars below them from R, and above them only on Android 11`() {
        val q = android.os.Build.VERSION_CODES.Q
        val r = android.os.Build.VERSION_CODES.R
        val s = android.os.Build.VERSION_CODES.S
        assertEquals(false, OverlayEnvironment.hearsBars(belowBars = true, sdk = q))
        assertEquals(true, OverlayEnvironment.hearsBars(belowBars = true, sdk = r))
        assertEquals(true, OverlayEnvironment.hearsBars(belowBars = true, sdk = s))
        assertEquals(false, OverlayEnvironment.hearsBars(belowBars = false, sdk = q))
        assertEquals(true, OverlayEnvironment.hearsBars(belowBars = false, sdk = r))
        assertEquals(false, OverlayEnvironment.hearsBars(belowBars = false, sdk = s))
    }
}
