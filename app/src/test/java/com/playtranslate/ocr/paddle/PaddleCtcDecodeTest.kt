package com.playtranslate.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PaddleOcrSession.decodeCtc] — the pure CTC greedy decode that now
 * also recovers each character's firing position. Plain JUnit: the decode touches no
 * Android types (FloatArray/IntArray in, [PaddleOcrSession.CropResult] out).
 *
 * The load-bearing new invariants live here, not in the geometry helper:
 *  - [PaddleOcrSession.DecodedChar.charOffset] is the UTF-16 index into the decoded
 *    text (so a non-BMP glyph advances the next offset by 2);
 *  - a repeat separated by a blank decodes to TWO characters (standard CTC) with two
 *    monotonically increasing firing fractions;
 *  - an all-blank strip decodes to nothing.
 */
class PaddleCtcDecodeTest {

    /** Build [1,T,C] logits (row-major) whose per-timestep argmax is [argmaxPerStep]. */
    private fun logits(argmaxPerStep: List<Int>, c: Int): Pair<FloatArray, IntArray> {
        val t = argmaxPerStep.size
        val arr = FloatArray(t * c)
        for (ti in argmaxPerStep.indices) arr[ti * c + argmaxPerStep[ti]] = 1f
        return arr to intArrayOf(1, t, c)
    }

    private fun decode(argmaxPerStep: List<Int>, labels: List<String>): PaddleOcrSession.CropResult {
        val (data, shape) = logits(argmaxPerStep, labels.size)
        return PaddleOcrSession.decodeCtc(data, shape, labels)
    }

    @Test
    fun `basic decode carries text, UTF-16 offsets, and monotonic fractions`() {
        val labels = listOf("", "A", "B", "C")          // index 0 = blank
        val r = decode(listOf(1, 2, 3), labels)
        assertEquals("ABC", r.text)
        assertEquals(listOf("A", "B", "C"), r.chars.map { it.text })
        assertEquals(listOf(0, 1, 2), r.chars.map { it.charOffset })
        // (ti+0.5)/t for t=3
        assertEquals(0.5f / 3f, r.chars[0].firingFraction, 1e-4f)
        assertEquals(1.5f / 3f, r.chars[1].firingFraction, 1e-4f)
        assertEquals(2.5f / 3f, r.chars[2].firingFraction, 1e-4f)
        assertTrue(r.chars.zipWithNext().all { (a, b) -> a.firingFraction < b.firingFraction })
    }

    @Test
    fun `adjacent identical timesteps collapse to one character`() {
        val r = decode(listOf(1, 1, 2), listOf("", "A", "B"))
        assertEquals("AB", r.text)
        assertEquals(listOf(0, 1), r.chars.map { it.charOffset })
    }

    @Test
    fun `repeat separated by a blank decodes to two characters`() {
        // A, blank, A → "AA": the blank breaks the run so the second A is re-emitted.
        val r = decode(listOf(1, 0, 1), listOf("", "A"))
        assertEquals("AA", r.text)
        assertEquals(2, r.chars.size)
        assertEquals(listOf(0, 1), r.chars.map { it.charOffset })
        assertTrue(r.chars[0].firingFraction < r.chars[1].firingFraction)
    }

    @Test
    fun `surrogate-pair label advances the next offset by two`() {
        // 𠮷 (U+20BB7) is two UTF-16 code units; the offset after it must be 2.
        val kanji = "𠮷"
        val r = decode(listOf(1, 2), listOf("", kanji, "Y"))
        assertEquals(kanji + "Y", r.text)
        assertEquals(kanji, r.chars[0].text)
        assertEquals(0, r.chars[0].charOffset)   // offset of the high surrogate
        assertEquals("Y", r.chars[1].text)
        assertEquals(2, r.chars[1].charOffset)   // not 1 — surrogate pair occupied 0..1
    }

    @Test
    fun `all-blank strip decodes to empty`() {
        val r = decode(listOf(0, 0, 0), listOf("", "A"))
        assertEquals("", r.text)
        assertTrue(r.chars.isEmpty())
        assertEquals(0f, r.confidence, 1e-6f)
    }
}
