package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [VerticalTextLayout] — the View-free packing math behind
 * [VerticalTextView]. No Robolectric needed: `compute` is arithmetic and
 * `splitGraphemes` uses only `java.text.BreakIterator`.
 *
 * Tests pin lineSpacing/colSpacing to 1.0 so the expected cell size / row /
 * column counts are easy to reason about. cellSize (a binary-searched float)
 * is asserted within an epsilon; integer row/col counts are asserted exactly.
 */
class VerticalTextLayoutTest {

    // ── compute ──────────────────────────────────────────────────────────

    @Test
    fun compute_shortText_singleColumn_sizedToWidth() {
        // 4 cells in a tall narrow box: one column, cell size limited by the
        // 100px width (not the ample 400px height).
        val l = VerticalTextLayout.compute(
            graphemeCount = 4, width = 100f, height = 400f, pad = 0f,
            minPx = 6f, maxPx = 200f, lineSpacing = 1f, colSpacing = 1f,
        )
        assertEquals(1, l.cols)
        assertEquals(4, l.rows)
        assertEquals(100f, l.cellSize, 1f)
    }

    @Test
    fun compute_longText_wrapsIntoColumns() {
        // 10 cells in a 100×200 box wraps to 2 columns of 5 at cell size 40.
        val l = VerticalTextLayout.compute(
            graphemeCount = 10, width = 100f, height = 200f, pad = 0f,
            minPx = 6f, maxPx = 200f, lineSpacing = 1f, colSpacing = 1f,
        )
        assertEquals(2, l.cols)
        assertEquals(5, l.rows)
        assertEquals(40f, l.cellSize, 1f)
    }

    @Test
    fun compute_tooMuchText_clampsToMinAndCapsColumnsWithinWidth() {
        // 100 cells in a tiny 20×20 box can't fit even at the 6px floor. Size
        // clamps to min AND the drawn column count is capped to the width, so
        // the layout stays INSIDE the box (trailing cells dropped) rather than
        // drawing outside the rect that pinhole detection / OCR masking track.
        val availW = 20f
        val l = VerticalTextLayout.compute(
            graphemeCount = 100, width = availW, height = 20f, pad = 0f,
            minPx = 6f, maxPx = 200f, lineSpacing = 1f, colSpacing = 1f,
        )
        assertEquals(6f, l.cellSize, 0.001f)
        assertEquals(3, l.rows)   // floor(20 / 6)
        assertEquals(3, l.cols)   // capped to floor(20 / 6) — NOT ceil(100/3)=34
        assertTrue(
            "drawn columns must fit within the available width",
            l.cols * l.colStep <= availW,
        )
    }

    @Test
    fun compute_boxTooNarrowForOneColumn_drawsNothing() {
        // availW (4) < colStep at the 6px floor (6) — not even one column fits.
        // cols must be 0 (NOT coerced to 1), so the view draws only its
        // background and never paints a glyph outside the tracked child bounds.
        val l = VerticalTextLayout.compute(
            graphemeCount = 3, width = 4f, height = 40f, pad = 0f,
            minPx = 6f, maxPx = 200f, lineSpacing = 1f, colSpacing = 1f,
        )
        assertEquals(0, l.cols)
        assertEquals(0, l.rows * l.cols)  // drawCount → nothing rendered
    }

    // ── splitGraphemes ───────────────────────────────────────────────────

    @Test
    fun split_basicLatin() {
        assertEquals(listOf("a", "b", "c"), VerticalTextLayout.splitGraphemes("abc"))
    }

    @Test
    fun split_emptyIsEmpty() {
        assertEquals(emptyList<String>(), VerticalTextLayout.splitGraphemes(""))
    }

    @Test
    fun split_dropsNewlines_keepsSpaces() {
        assertEquals(listOf("a", "b"), VerticalTextLayout.splitGraphemes("a\nb"))
        // Spaces are kept — a blank cell is correct vertical-Korean word spacing.
        assertEquals(listOf("a", " ", "b"), VerticalTextLayout.splitGraphemes("a b"))
    }

    @Test
    fun split_cjkOnePerCell() {
        assertEquals(listOf("한", "국", "어"), VerticalTextLayout.splitGraphemes("한국어"))
    }

    @Test
    fun split_surrogatePairStaysOneCell() {
        // U+2000B (CJK Ext-B) is one grapheme but two UTF-16 code units.
        val cells = VerticalTextLayout.splitGraphemes("a𠀋b")
        assertEquals(3, cells.size)
        assertEquals(2, cells[1].length)
    }
}
