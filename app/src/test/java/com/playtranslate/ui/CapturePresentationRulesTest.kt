package com.playtranslate.ui

import com.playtranslate.OverlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the results page puts a capture's presentation up on the game display
 * by itself ([presentsCaptureOnEdge]): only on the edge where boxes arrive,
 * only while nothing is up, and never in None. Every cell.
 */
class CapturePresentationRulesTest {

    private val boxes = Any()
    private val other = Any()

    @Test fun `the edge is boxes arriving where there were none`() {
        for (mode in OverlayMode.entries.filter { it != OverlayMode.NONE }) {
            assertEquals("$mode arriving", true, presentsCaptureOnEdge(null, boxes, shownNow = false, mode = mode))
            assertEquals("$mode replay of the same", false, presentsCaptureOnEdge(boxes, boxes, shownNow = false, mode = mode))
            assertEquals("$mode a fill", false, presentsCaptureOnEdge(boxes, other, shownNow = false, mode = mode))
            assertEquals("$mode leaving", false, presentsCaptureOnEdge(boxes, null, shownNow = false, mode = mode))
            assertEquals("$mode nothing to nothing", false, presentsCaptureOnEdge(null, null, shownNow = false, mode = mode))
        }
    }

    @Test fun `a window already up is left alone`() {
        for (mode in OverlayMode.entries) {
            assertEquals(mode.name, false, presentsCaptureOnEdge(null, boxes, shownNow = true, mode = mode))
        }
    }

    @Test fun `none never presents`() {
        assertEquals(false, presentsCaptureOnEdge(null, boxes, shownNow = false, mode = OverlayMode.NONE))
    }
}
