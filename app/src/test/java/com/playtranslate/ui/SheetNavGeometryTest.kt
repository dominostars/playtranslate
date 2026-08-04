package com.playtranslate.ui

import com.playtranslate.ui.SheetNavGeometry.Dir
import com.playtranslate.ui.SheetNavGeometry.NavRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SheetNavGeometry] — the pure spatial math under the capture
 * sheet's controller cursor. No Android, no Robolectric. Pins the beam
 * preference (a column walk stays in its column), the strictly-beyond rule
 * (the source rect passed among the candidates can never be chosen), and the
 * first-press selection order.
 */
class SheetNavGeometryTest {

    private fun rect(left: Int, top: Int, w: Int = 40, h: Int = 30) =
        NavRect(left, top, left + w, top + h)

    // ─── nextInDirection: basic movement ────────────────────────────────

    @Test fun `moves right along a header row`() {
        val row = listOf(rect(0, 0), rect(50, 0), rect(100, 0))
        assertEquals(1, SheetNavGeometry.nextInDirection(row[0], row, Dir.RIGHT))
        assertEquals(2, SheetNavGeometry.nextInDirection(row[1], row, Dir.RIGHT))
    }

    @Test fun `moves left along a header row`() {
        val row = listOf(rect(0, 0), rect(50, 0), rect(100, 0))
        assertEquals(1, SheetNavGeometry.nextInDirection(row[2], row, Dir.LEFT))
        assertEquals(0, SheetNavGeometry.nextInDirection(row[1], row, Dir.LEFT))
    }

    @Test fun `moves down between stacked rows`() {
        val items = listOf(rect(0, 0), rect(50, 0), rect(0, 100), rect(50, 100))
        assertEquals(2, SheetNavGeometry.nextInDirection(items[0], items, Dir.DOWN))
        assertEquals(3, SheetNavGeometry.nextInDirection(items[1], items, Dir.DOWN))
    }

    @Test fun `moves up between stacked rows`() {
        val items = listOf(rect(0, 0), rect(50, 0), rect(0, 100), rect(50, 100))
        assertEquals(0, SheetNavGeometry.nextInDirection(items[2], items, Dir.UP))
        assertEquals(1, SheetNavGeometry.nextInDirection(items[3], items, Dir.UP))
    }

    // ─── nextInDirection: edges + self-exclusion ────────────────────────

    @Test fun `nothing above the top row`() {
        val row = listOf(rect(0, 0), rect(50, 0))
        assertNull(SheetNavGeometry.nextInDirection(row[0], row, Dir.UP))
    }

    @Test fun `nothing right of the last item`() {
        val row = listOf(rect(0, 0), rect(50, 0))
        assertNull(SheetNavGeometry.nextInDirection(row[1], row, Dir.RIGHT))
    }

    @Test fun `the source rect itself is never chosen`() {
        // The cursor's own rect sits in the candidate list (callers pass the
        // full collection); zero axis distance must exclude it.
        val row = listOf(rect(0, 0), rect(50, 0))
        assertEquals(1, SheetNavGeometry.nextInDirection(row[0], row, Dir.RIGHT))
        assertNull(SheetNavGeometry.nextInDirection(row[1], listOf(row[1]), Dir.RIGHT))
    }

    @Test fun `empty candidates yield null`() {
        assertNull(SheetNavGeometry.nextInDirection(rect(0, 0), emptyList(), Dir.DOWN))
    }

    // ─── nextInDirection: beam preference ───────────────────────────────

    @Test fun `a far-but-aligned target beats a near-but-offset one going down`() {
        // Side-by-side columns: from a left-column item, DOWN should stay in
        // the left column (overlapping x) even when the right column has a
        // vertically-nearer item.
        val from = rect(0, 0)
        val candidates = listOf(
            rect(200, 40),   // near, but off in x (no overlap)
            rect(0, 120),    // farther, but same column
        )
        assertEquals(1, SheetNavGeometry.nextInDirection(from, candidates, Dir.DOWN))
    }

    @Test fun `overlap only counts on the cross axis`() {
        // Going RIGHT, the same-row candidate (y-overlap) wins over a closer
        // x-distance candidate in another row.
        val from = rect(0, 100)
        val candidates = listOf(
            rect(50, 0),     // nearer in x, wrong row
            rect(80, 100),   // same row
        )
        assertEquals(1, SheetNavGeometry.nextInDirection(from, candidates, Dir.RIGHT))
    }

    @Test fun `among aligned candidates the better-centered one wins`() {
        // Both below and overlapping; the perpendicular tiebreak prefers the
        // one whose center is closer to the source's center.
        val from = rect(100, 0, w = 100)
        val candidates = listOf(
            rect(180, 60, w = 100),  // overlaps, center offset 80
            rect(120, 60, w = 100),  // overlaps, center offset 20
        )
        assertEquals(1, SheetNavGeometry.nextInDirection(from, candidates, Dir.DOWN))
    }

    @Test fun `exact ties break toward the smaller index`() {
        // Source spans 100..140 (center 120). Both candidates sit below with
        // no x-overlap and mirrored centers (40 and 200): identical axis
        // distance, identical perpendicular distance — the strict less-than
        // keeps the first.
        val from = rect(100, 0)
        val candidates = listOf(rect(20, 100), rect(180, 100))
        assertEquals(0, SheetNavGeometry.nextInDirection(from, candidates, Dir.DOWN))
    }

    // ─── firstItem ──────────────────────────────────────────────────────

    @Test fun `first item is topmost then leftmost`() {
        val items = listOf(rect(50, 40), rect(10, 40), rect(0, 200))
        assertEquals(1, SheetNavGeometry.firstItem(items))
    }

    @Test fun `first item of an empty list is null`() {
        assertNull(SheetNavGeometry.firstItem(emptyList()))
    }

    @Test fun `single candidate is first`() {
        assertEquals(0, SheetNavGeometry.firstItem(listOf(rect(500, 500))))
    }
}
