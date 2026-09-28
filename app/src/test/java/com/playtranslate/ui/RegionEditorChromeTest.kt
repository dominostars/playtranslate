package com.playtranslate.ui

import com.playtranslate.ui.RegionEditorChrome.Edge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins where the region editors put their pill and bar: on top, unless the
 * region reaches under the top chrome and not under the bottom chrome, and
 * the stack's offsets (pill outermost, bar past it). The editors' own
 * placement, on open and at each drag's end, is pinned by
 * RegionEditorChromePlacementTest.
 */
class RegionEditorChromeTest {

    private fun edge(top: Float, bottom: Float, topEnd: Float = 0.2f, bottomStart: Float = 0.8f) =
        RegionEditorChrome.edgeFor(top..bottom, topEnd, bottomStart)

    @Test fun `no existing region sits on top`() {
        assertEquals(Edge.TOP, RegionEditorChrome.edgeFor(null, 0.2f, 0.8f))
    }

    @Test fun `region under the top only moves the chrome to the bottom`() {
        assertEquals(Edge.BOTTOM, edge(0f, 0.5f))
        assertEquals(Edge.BOTTOM, edge(0.19f, 0.3f))
    }

    @Test fun `region under the bottom only keeps the top`() {
        assertEquals(Edge.TOP, edge(0.6f, 1f))
    }

    @Test fun `region under both keeps the top`() {
        assertEquals(Edge.TOP, edge(0f, 1f))
        assertEquals(Edge.TOP, edge(0.1f, 0.81f))
    }

    @Test fun `region under neither keeps the top`() {
        assertEquals(Edge.TOP, edge(0.3f, 0.7f))
    }

    @Test fun `edges touching the chrome's boundary are not under it`() {
        assertEquals(Edge.TOP, edge(0.2f, 0.5f))
        assertEquals(Edge.BOTTOM, edge(0.1f, 0.8f))
    }

    @Test fun `top and bottom reaches are judged separately`() {
        // A deeper top chrome (a status-bar inset) and a shallower bottom one.
        assertEquals(Edge.BOTTOM, edge(0.25f, 0.85f, topEnd = 0.3f, bottomStart = 0.9f))
        assertEquals(Edge.TOP, edge(0.25f, 0.95f, topEnd = 0.3f, bottomStart = 0.9f))
    }

    @Test fun `stack offsets put the pill outermost and the bar past it`() {
        assertEquals(32, RegionEditorChrome.pillOffsetPx(2f))
        assertEquals(32 + 40 + 16, RegionEditorChrome.barOffsetPx(pillHeightPx = 40, dp = 2f))
        assertEquals(32 + 40 + 16 + 72, RegionEditorChrome.stackDepthPx(40, 72, 2f))
    }
}
