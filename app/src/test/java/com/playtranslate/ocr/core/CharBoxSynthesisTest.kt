package com.playtranslate.ocr.core

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * Unit tests for [synthesizeEvenCharBoxes] — text-only → evenly-tiled per-char boxes.
 *
 * **Robolectric is mandatory:** `unitTests.isReturnDefaultValues = true` makes
 * `Rect.width()/height()` return 0 under plain JUnit, collapsing every cell (same
 * reason as [com.playtranslate.ocr.paddle.PaddleCharBoxesTest]).
 */
@RunWith(RobolectricTestRunner::class)
class CharBoxSynthesisTest {

    private fun assertNear(expected: Int, actual: Int, tol: Int = 1) =
        assertTrue("expected≈$expected got $actual", abs(expected - actual) <= tol)

    @Test
    fun `horizontal tiles left to right in equal contiguous cells`() {
        val bounds = Rect(0, 0, 300, 48)
        val chars = synthesizeEvenCharBoxes("あいう", OcrBox.upright(bounds), vertical = false)
        assertEquals(3, chars.size)
        assertEquals(listOf("あ", "い", "う"), chars.map { it.text })
        assertEquals(listOf(0, 1, 2), chars.map { it.charOffset })

        val rects = chars.map { it.box.bounds }
        rects.forEach { assertEquals(bounds.top, it.top); assertEquals(bounds.bottom, it.bottom) }
        assertEquals(0, rects.first().left)
        assertEquals(300, rects.last().right)
        for (i in 0 until rects.size - 1) {
            assertEquals("contiguous", rects[i].right, rects[i + 1].left)
            assertTrue("monotonic", rects[i].left < rects[i + 1].left)
        }
        rects.forEach { assertNear(100, it.width()) }
    }

    @Test
    fun `vertical tiles top to bottom in equal contiguous cells`() {
        val bounds = Rect(0, 0, 48, 300)
        val chars = synthesizeEvenCharBoxes("アイウ", OcrBox.upright(bounds), vertical = true)
        val rects = chars.map { it.box.bounds }
        rects.forEach { assertEquals(bounds.left, it.left); assertEquals(bounds.right, it.right) }
        assertEquals(0, rects.first().top)
        assertEquals(300, rects.last().bottom)
        for (i in 0 until rects.size - 1) {
            assertEquals("contiguous", rects[i].bottom, rects[i + 1].top)
            assertTrue("monotonic", rects[i].top < rects[i + 1].top)
        }
        rects.forEach { assertNear(100, it.height()) }
    }

    @Test
    fun `single character spans the whole box`() {
        val bounds = Rect(10, 20, 310, 68)
        val chars = synthesizeEvenCharBoxes("X", OcrBox.upright(bounds), vertical = false)
        assertEquals(1, chars.size)
        assertEquals(0, chars[0].charOffset)
        assertEquals(bounds, chars[0].box.bounds)
    }

    @Test
    fun `offset within a non-zero origin box stays in bounds`() {
        val bounds = Rect(100, 200, 220, 200 + 360) // vertical column, height 360
        val chars = synthesizeEvenCharBoxes("テスト", OcrBox.upright(bounds), vertical = true)
        assertEquals(3, chars.size)
        assertEquals(bounds.top, chars.first().box.bounds.top)
        assertEquals(bounds.bottom, chars.last().box.bounds.bottom)
        chars.forEach {
            assertEquals(bounds.left, it.box.bounds.left)
            assertEquals(bounds.right, it.box.bounds.right)
        }
    }

    @Test
    fun `empty text yields no boxes`() {
        assertTrue(synthesizeEvenCharBoxes("", OcrBox.upright(Rect(0, 0, 100, 20)), vertical = false).isEmpty())
    }

    @Test
    fun `char count exceeding the pixel span still yields non-zero-area boxes`() {
        // 40 chars down a 30px column (degenerate — a hallucinated over-long read): integer
        // cell tiling would collapse most cells to lo == hi without the guard.
        val chars = synthesizeEvenCharBoxes("あ".repeat(40), OcrBox.upright(Rect(0, 0, 20, 30)), vertical = true)
        assertEquals(40, chars.size)
        chars.forEach { assertTrue("no zero-area box", it.box.bounds.height() >= 1) }
    }

    @Test
    fun `rotated box tiles its baseline with upright cells`() {
        // Even tiling of a 300×48 strip at −20° (AABB centered 200,150): cell
        // centers land at u = −100, 0, +100 on the baseline; cells are upright,
        // oh tall — the same shape the CTC synthesizer and ML Kit produce.
        val box = OcrBox(Rect(51, 76, 349, 224), 300f, 48f, -20f)
        val chars = synthesizeEvenCharBoxes("あいう", box, vertical = false)
        assertEquals(3, chars.size)
        val cos = 0.93969
        val sin = -0.34202
        for ((i, u) in listOf(-100.0, 0.0, 100.0).withIndex()) {
            val c = chars[i].box.bounds
            assertNear((200 + u * cos).toInt(), c.centerX(), tol = 2)
            assertNear((150 + u * sin).toInt(), c.centerY(), tol = 2)
            assertNear(48, c.height(), tol = 1)
            assertEquals(i, chars[i].charOffset)
        }
    }
}
