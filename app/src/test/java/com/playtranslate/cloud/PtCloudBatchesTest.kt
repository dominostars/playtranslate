package com.playtranslate.cloud

import com.playtranslate.cloud.PtCloudBatches.Chunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a batch is cut into translate calls ([PtCloudBatches]), cap by cap:
 * texts a call, code points a call (counted as code points, never UTF-16
 * units), the body's bytes as the caller measures them, blanks left out with
 * every other text keeping its position, and the per-text cap that refuses a
 * batch before any call.
 */
class PtCloudBatchesTest {

    /** A body measure that never binds. */
    private val small: (List<String>) -> Int = { 100 }

    /** "𠀀" (U+20000) is one code point and two UTF-16 units. */
    private val wide = "𠀀"

    private fun texts(n: Int, prefix: String = "t") = List(n) { "$prefix$it" }

    @Test fun `nothing to send makes no call`() {
        assertEquals(emptyList<Chunk>(), PtCloudBatches.chunk(emptyList(), small))
        assertEquals(emptyList<Chunk>(), PtCloudBatches.chunk(listOf("", " ", "\n\t"), small))
    }

    @Test fun `64 texts are one call and 65 are two, the 65th at its own position`() {
        val sixtyFour = texts(64)
        assertEquals(listOf(Chunk((0 until 64).toList(), sixtyFour)), PtCloudBatches.chunk(sixtyFour, small))

        val sixtyFive = texts(65)
        assertEquals(
            listOf(Chunk((0 until 64).toList(), sixtyFive.take(64)), Chunk(listOf(64), listOf("t64"))),
            PtCloudBatches.chunk(sixtyFive, small),
        )
    }

    @Test fun `blanks are in no call and the rest keep their positions`() {
        assertEquals(
            listOf(Chunk(listOf(1, 3), listOf("a", "b"))),
            PtCloudBatches.chunk(listOf("", "a", "  ", "b"), small),
        )
    }

    @Test fun `blanks around the 64 text boundary count no slot`() {
        // 67 texts with 63 and 64 blank: 65 to send, so the 64th sent text
        // is position 65 and the second call holds position 66 alone.
        val input = texts(67).toMutableList().apply { this[63] = ""; this[64] = " " }
        val chunks = PtCloudBatches.chunk(input, small)
        assertEquals(2, chunks.size)
        assertEquals((0 until 63).toList() + 65, chunks[0].positions)
        assertEquals(chunks[0].positions.map { input[it] }, chunks[0].texts)
        assertEquals(Chunk(listOf(66), listOf("t66")), chunks[1])
    }

    @Test fun `a call holds at most 6,000 code points, counted as code points`() {
        // Three texts of 2,000 code points (4,000 UTF-16 units each): 6,000
        // code points fit in one call.
        val third = wide.repeat(2_000)
        assertEquals(1, PtCloudBatches.chunk(List(3) { third }, small).size)

        // One more code point goes to a second call.
        val input = List(3) { third } + "x"
        assertEquals(
            listOf(Chunk(listOf(0, 1, 2), List(3) { third }), Chunk(listOf(3), listOf("x"))),
            PtCloudBatches.chunk(input, small),
        )
        // 3,001 + 3,000 is over: two calls.
        assertEquals(2, PtCloudBatches.chunk(listOf("a".repeat(3_001), "b".repeat(3_000)), small).size)
    }

    @Test fun `a call's body stays strictly under 65,536 bytes as the caller measures it`() {
        // The measure stands for persona and context (60,000 bytes) plus
        // 2,000 a text: two texts make 64,000, three 66,000.
        val measure: (List<String>) -> Int = { 60_000 + 2_000 * it.size }
        assertEquals(
            listOf(Chunk(listOf(0, 1), listOf("a", "b")), Chunk(listOf(2), listOf("c"))),
            PtCloudBatches.chunk(listOf("a", "b", "c"), measure),
        )
        // Exactly the cap is over it; one byte less fits.
        assertEquals(2, PtCloudBatches.chunk(listOf("a", "b"), { if (it.size == 2) 65_536 else 100 }).size)
        assertEquals(1, PtCloudBatches.chunk(listOf("a", "b"), { if (it.size == 2) 65_535 else 100 }).size)
    }

    @Test fun `a text whose body alone reaches the cap is body-oversize, a blank or a fitting one is not`() {
        // 60,000 bytes of persona and context plus 2,000 a text: each text
        // fits by itself.
        assertFalse(PtCloudBatches.bodyOversize(listOf("a", "b")) { 60_000 + 2_000 * it.size })
        // Exactly the cap by itself is over it; one byte under fits.
        assertTrue(PtCloudBatches.bodyOversize(listOf("a", "b")) { if (it == listOf("b")) 65_536 else 100 })
        assertFalse(PtCloudBatches.bodyOversize(listOf("a", "b")) { if (it == listOf("b")) 65_535 else 100 })
        // A blank is never sent, so its body is never measured.
        assertFalse(PtCloudBatches.bodyOversize(listOf(" ".repeat(10))) { 70_000 })
    }

    @Test fun `a text over 4,000 code points is oversize, counted as code points`() {
        assertFalse(PtCloudBatches.oversize(listOf("a".repeat(4_000))))
        assertTrue(PtCloudBatches.oversize(listOf("a".repeat(4_001))))
        // 4,000 code points in 8,000 UTF-16 units: not oversize.
        assertFalse(PtCloudBatches.oversize(listOf(wide.repeat(4_000))))
        assertTrue(PtCloudBatches.oversize(listOf(wide.repeat(4_001))))
        // One oversize text makes the whole batch oversize.
        assertTrue(PtCloudBatches.oversize(listOf("ok", "a".repeat(4_001), "ok")))
        // A blank text is never sent, so no cap applies to it.
        assertFalse(PtCloudBatches.oversize(listOf(" ".repeat(5_000), "ok")))
    }
}
