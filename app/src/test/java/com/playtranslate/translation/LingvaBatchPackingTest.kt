package com.playtranslate.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LingvaBackend.packChunks] — the greedy first-fit packer that turns the
 * gtx URL cap into a chunk boundary. The field problem it replaces: the
 * cap used to be a preflight throw, and the registry's per-text retry
 * then fanned a text-heavy page out as N parallel requests against the
 * same per-IP limiter batching exists to protect. Pure index math, so
 * every boundary is pinned here without a client; the request sequence
 * built on it is covered by [LingvaBatchRequestTest].
 */
class LingvaBatchPackingTest {

    /** Full URL length of one chunk under the packer's cost model:
     *  prefix plus `&` plus each param. */
    private fun urlLength(prefix: Int, lengths: List<Int>, range: IntRange): Int =
        prefix + range.sumOf { 1 + lengths[it] }

    @Test fun `everything that fits goes in one chunk`() {
        val lengths = listOf(10, 20, 30)
        val chunks = LingvaBackend.packChunks(prefixLength = 5, paramLengths = lengths, maxUrlLength = 100)
        assertEquals(listOf(0..2), chunks)
    }

    @Test fun `a chunk exactly at the cap is allowed`() {
        // 5 + (1+10) + (1+20) + (1+30) = 68: the old preflight threw only
        // on strictly greater, so equal must still pack as one request.
        val lengths = listOf(10, 20, 30)
        assertEquals(listOf(0..2), LingvaBackend.packChunks(5, lengths, 68))
        assertEquals(listOf(0..1, 2..2), LingvaBackend.packChunks(5, lengths, 67))
    }

    @Test fun `splits at the cap into contiguous ordered chunks`() {
        // Six equal params of cost 10 under a cap that holds three:
        // 4 + 3*10 = 34 fits, 44 does not.
        val lengths = List(6) { 9 }
        val chunks = LingvaBackend.packChunks(prefixLength = 4, paramLengths = lengths, maxUrlLength = 34)
        assertEquals(listOf(0..2, 3..5), chunks)
        chunks.forEach { r ->
            assertTrue("chunk $r overruns", urlLength(4, lengths, r) <= 34)
        }
    }

    @Test fun `prefix length counts against the cap`() {
        val lengths = List(4) { 9 }
        // Cap 40 holds four params with no prefix, three with prefix 4,
        // two with prefix 20.
        assertEquals(listOf(0..3), LingvaBackend.packChunks(0, lengths, 40))
        assertEquals(listOf(0..2, 3..3), LingvaBackend.packChunks(4, lengths, 40))
        assertEquals(listOf(0..1, 2..3), LingvaBackend.packChunks(20, lengths, 40))
    }

    @Test fun `a param that alone overruns the cap gets its own chunk`() {
        // The oversize param is neither dropped nor allowed to drag its
        // neighbours into an overrun: it is isolated and sent as-is.
        val lengths = listOf(10, 500, 10, 10)
        val chunks = LingvaBackend.packChunks(prefixLength = 5, paramLengths = lengths, maxUrlLength = 60)
        assertEquals(listOf(0..0, 1..1, 2..3), chunks)
    }

    @Test fun `every index lands in exactly one chunk, in order`() {
        val lengths = listOf(50, 3, 3, 3, 700, 3, 60, 60, 3)
        val chunks = LingvaBackend.packChunks(prefixLength = 30, paramLengths = lengths, maxUrlLength = 100)
        val covered = chunks.flatMap { it.toList() }
        assertEquals(lengths.indices.toList(), covered)
        chunks.forEach { r ->
            val fits = urlLength(30, lengths, r) <= 100
            val lone = r.first == r.last
            assertTrue("chunk $r overruns and is not a lone oversize param", fits || lone)
        }
    }

    @Test fun `greedy packing is the fewest chunks for a fixed order`() {
        // Any split of an in-order sequence into cap-respecting runs
        // needs at least as many runs as first-fit produces; check the
        // count against a brute-force minimum over all cut positions.
        val lengths = listOf(20, 30, 10, 40, 5, 5, 35, 25)
        val prefix = 10
        val cap = 70
        val greedy = LingvaBackend.packChunks(prefix, lengths, cap).size
        var best = Int.MAX_VALUE
        val n = lengths.size
        for (mask in 0 until (1 shl (n - 1))) {
            var start = 0
            var count = 0
            var ok = true
            for (i in 0 until n) {
                val cut = i == n - 1 || (mask shr i) and 1 == 1
                if (cut) {
                    if (urlLength(prefix, lengths, start..i) > cap) { ok = false; break }
                    count++
                    start = i + 1
                }
            }
            if (ok) best = minOf(best, count)
        }
        assertEquals(best, greedy)
    }

    @Test fun `empty input packs to no chunks`() {
        assertEquals(emptyList<IntRange>(), LingvaBackend.packChunks(80, emptyList(), 6144))
    }
}
