package com.playtranslate.ocr.paddle

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * Unit tests for [synthesizeCharBoxes] — CTC firing fractions → per-character boxes.
 *
 * **Robolectric is mandatory:** `unitTests.isReturnDefaultValues = true` (app
 * build.gradle.kts) makes `Rect.width()/height()` return 0 under plain JUnit, which
 * would collapse every cell. The whole house of geometry tests (e.g. LineAssemblyTest)
 * runs under Robolectric for this reason.
 *
 * The tiling is asserted via its invariants — cells are contiguous (one box's far edge
 * IS the next box's near edge), monotonic, and span ≈ the full box — rather than exact
 * pixels, since the mirrored end edges drift by sub-pixel float (intended; see plan).
 */
@RunWith(RobolectricTestRunner::class)
class PaddleCharBoxesTest {

    private fun dc(text: String, offset: Int, frac: Float) =
        PaddleOcrSession.DecodedChar(text, offset, frac)

    private fun assertNear(expected: Int, actual: Int, tol: Int = 2) =
        assertTrue("expected≈$expected got $actual", abs(expected - actual) <= tol)

    @Test
    fun `horizontal evenly-spaced tiles left to right across the box`() {
        val bounds = Rect(0, 0, 300, 48)
        val chars = synthesizeCharBoxes(
            listOf(dc("あ", 0, 1f / 6f), dc("い", 1, 3f / 6f), dc("う", 2, 5f / 6f)),
            bounds, stripVertical = false,
        )
        assertEquals(3, chars.size)
        assertEquals(listOf("あ", "い", "う"), chars.map { it.text })
        assertEquals(listOf(0, 1, 2), chars.map { it.charOffset })

        val rects = chars.map { it.box.bounds }
        // Cross-axis spans the full box height; reading axis covers ≈ [0, width].
        rects.forEach { assertEquals(bounds.top, it.top); assertEquals(bounds.bottom, it.bottom) }
        assertNear(0, rects.first().left)
        assertNear(300, rects.last().right)
        // Contiguous + monotonic; evenly-spaced input → ≈ equal thirds.
        for (i in 0 until rects.size - 1) {
            assertEquals("contiguous", rects[i].right, rects[i + 1].left)
            assertTrue("monotonic", rects[i].left < rects[i + 1].left)
        }
        rects.forEach { assertNear(100, it.width()) }
    }

    @Test
    fun `vertical tiles top to bottom down the column`() {
        val bounds = Rect(0, 0, 48, 300)
        val chars = synthesizeCharBoxes(
            listOf(dc("ア", 0, 1f / 6f), dc("イ", 1, 3f / 6f), dc("ウ", 2, 5f / 6f)),
            bounds, stripVertical = true,
        )
        val rects = chars.map { it.box.bounds }
        // Cross-axis spans the full column width; reading axis runs down ≈ [0, height].
        rects.forEach { assertEquals(bounds.left, it.left); assertEquals(bounds.right, it.right) }
        assertNear(0, rects.first().top)
        assertNear(300, rects.last().bottom)
        for (i in 0 until rects.size - 1) {
            assertEquals("contiguous", rects[i].bottom, rects[i + 1].top)
            assertTrue("monotonic", rects[i].top < rects[i + 1].top)
        }
    }

    @Test
    fun `single character spans the whole box`() {
        val bounds = Rect(10, 20, 310, 68)
        val chars = synthesizeCharBoxes(listOf(dc("X", 5, 0.5f)), bounds, stripVertical = false)
        assertEquals(1, chars.size)
        assertEquals(5, chars[0].charOffset)
        assertEquals(bounds, chars[0].box.bounds)
    }

    @Test
    fun `clustered firing positions yield non-uniform cells`() {
        val bounds = Rect(0, 0, 1000, 48)
        // First two glyphs fire close together, the third far away.
        val chars = synthesizeCharBoxes(
            listOf(dc("a", 0, 0.10f), dc("b", 1, 0.15f), dc("c", 2, 0.80f)),
            bounds, stripVertical = false,
        )
        val rects = chars.map { it.box.bounds }
        // Contiguous regardless of spacing.
        for (i in 0 until rects.size - 1) assertEquals(rects[i].right, rects[i + 1].left)
        // The clustered first glyph gets the narrowest cell; the isolated last the widest.
        assertTrue("first narrower than middle", rects[0].width() < rects[1].width())
        assertTrue("last widest", rects[2].width() > rects[1].width())
        assertNear(125, rects[0].right)   // midpoint(0.10,0.15)*1000
        assertNear(475, rects[1].right)   // midpoint(0.15,0.80)*1000
    }

    @Test
    fun `empty input yields no boxes`() {
        assertTrue(synthesizeCharBoxes(emptyList(), Rect(0, 0, 100, 20), false).isEmpty())
    }
}
