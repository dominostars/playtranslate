package com.playtranslate

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** JVM tests for [GlyphAnchors] — the A7 probe geometry. Uses Robolectric
 *  only for android.graphics.Rect. */
@RunWith(RobolectricTestRunner::class)
class GlyphAnchorsTest {

    @Test
    fun `horizontal box lays anchors along line centers`() {
        // 200×100 box at (100, 50), 2 lines, inset 8 → rows at the two
        // line-band centers inside the inset rect.
        val a = GlyphAnchors.forBox(Rect(100, 50, 300, 150), 2, vertical = false)
        assertEquals(2 * PinholeCalibration.GLYPH_ANCHORS_PER_LINE * 2, a.size)
        val insetTop = 58; val insetBottom = 142
        val lineH = (insetBottom - insetTop) / 2f
        val expectedY0 = (insetTop + 0.5f * lineH).toInt()
        val expectedY1 = (insetTop + 1.5f * lineH).toInt()
        // First line's anchors share y, span left→right inside the inset.
        assertEquals(expectedY0, a[1])
        assertEquals(108, a[0])            // line start = inset left
        assertEquals(292, a[a.size / 2 - 2]) // line end = inset right
        assertEquals(expectedY1, a[a.size - 1])
    }

    @Test
    fun `vertical box swaps axes and packs columns right to left`() {
        val a = GlyphAnchors.forBox(Rect(100, 50, 200, 350), 2, vertical = true)
        assertEquals(2 * PinholeCalibration.GLYPH_ANCHORS_PER_LINE * 2, a.size)
        // First column sits at the RIGHT side; anchors run top→bottom.
        assertTrue("first column x=${a[0]} should be right of center", a[0] > 150)
        assertEquals(58, a[1])   // first anchor y = inset top
        assertEquals(342, a[a.size / 2 - 1]) // first column's last y = inset bottom
        // Second column is left of the first.
        assertTrue(a[a.size / 2] < a[0])
    }

    @Test
    fun `degenerate rects yield no anchors`() {
        assertEquals(0, GlyphAnchors.forBox(Rect(0, 0, 20, 20), 1, false).size)
        assertEquals(0, GlyphAnchors.forBox(Rect(5, 5, 5, 200), 1, false).size)
    }

    @Test
    fun `line count is capped to the bitset budget`() {
        val a = GlyphAnchors.forBox(Rect(0, 0, 500, 500), 99, vertical = false)
        assertTrue(a.size / 2 <= GlyphAnchors.MAX_ANCHORS)
    }

    @Test
    fun `anchorNear hits within radius and misses outside`() {
        val a = GlyphAnchors.forBox(Rect(100, 50, 300, 150), 1, vertical = false)
        val r = PinholeCalibration.GLYPH_PROBE_RADIUS_PX
        val x0 = a[0]; val y0 = a[1]
        assertEquals(0, GlyphAnchors.anchorNear(a, x0, y0))
        assertEquals(0, GlyphAnchors.anchorNear(a, x0 + r, y0 - r))
        assertEquals(-1, GlyphAnchors.anchorNear(a, x0 + r + 1, y0))
        // A point near the SECOND anchor reports that index.
        assertEquals(1, GlyphAnchors.anchorNear(a, a[2], a[3]))
    }
}
