package com.playtranslate.ui

import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the results page puts a capture's presentation up on the game display
 * by itself ([presentsCaptureOnEdge]): only on the edge where boxes arrive,
 * only while nothing is up, and never in None, the mode being the one the
 * capture asked for itself (tap-to-lookup's Definitions) over the remembered
 * one. Every cell.
 */
class CapturePresentationRulesTest {

    private val data = OneShotOverlayData(
        boxes = emptyList(),
        cropLeft = 0, cropTop = 0, screenshotW = 1, screenshotH = 1,
        cropWidth = 1, cropHeight = 1,
        text = emptyList(),
    )
    private val boxes = OnScreenBoxes(data, displayId = 0)
    private val other = OnScreenBoxes(data, displayId = 1)

    @Test fun `the edge is boxes arriving where there were none`() {
        for (mode in OverlayMode.entries.filter { it != OverlayMode.NONE }) {
            assertEquals("$mode arriving", true, presentsCaptureOnEdge(null, boxes, shownNow = false, rememberedMode = mode))
            assertEquals("$mode replay of the same", false, presentsCaptureOnEdge(boxes, boxes, shownNow = false, rememberedMode = mode))
            assertEquals("$mode a fill", false, presentsCaptureOnEdge(boxes, other, shownNow = false, rememberedMode = mode))
            assertEquals("$mode leaving", false, presentsCaptureOnEdge(boxes, null, shownNow = false, rememberedMode = mode))
            assertEquals("$mode nothing to nothing", false, presentsCaptureOnEdge(null, null, shownNow = false, rememberedMode = mode))
        }
    }

    @Test fun `a window already up is left alone`() {
        for (mode in OverlayMode.entries) {
            assertEquals(mode.name, false, presentsCaptureOnEdge(null, boxes, shownNow = true, rememberedMode = mode))
        }
    }

    @Test fun `none never presents`() {
        assertEquals(false, presentsCaptureOnEdge(null, boxes, shownNow = false, rememberedMode = OverlayMode.NONE))
    }

    @Test fun `the mode a capture asked for itself wins over the remembered one`() {
        val lookup = boxes.copy(modeOverride = OverlayMode.DEFINITIONS)
        assertEquals(
            "tap-to-lookup presents under a remembered None",
            true, presentsCaptureOnEdge(null, lookup, shownNow = false, rememberedMode = OverlayMode.NONE),
        )
        val quiet = boxes.copy(modeOverride = OverlayMode.NONE)
        assertEquals(
            "and a capture that asked for None presents nothing",
            false, presentsCaptureOnEdge(null, quiet, shownNow = false, rememberedMode = OverlayMode.TRANSLATION),
        )
    }
}
