package com.playtranslate.translation.llm

import com.playtranslate.language.CatalogFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [MultiFileSha.aggregate]. The aggregate is the sentinel
 * value written by the MultiFile commit strategy and re-derived from the
 * catalog at `isInstalled()` time. Drift between the writer and the
 * reader would brick the install gate, so the contract is pinned with
 * dedicated tests:
 *
 *   - Stable under input list reordering (sort by path is the contract).
 *   - Bit-flip sensitive on any per-file sha or path.
 *   - Catalog membership changes (add/remove/rename) flip the aggregate.
 *   - Case-insensitive on the sha (lowercased before hashing).
 *
 * Pure JUnit; no Robolectric, no Android Context needed.
 */
class MultiFileShaTest {

    private fun cf(
        path: String,
        sha: String = "0".repeat(64),
        url: String = "https://example.test/$path",
        size: Long = 100L,
    ) = CatalogFile(path = path, url = url, size = size, sha256 = sha)

    @Test
    fun `single file produces a 64-char lowercase hex digest`() {
        val agg = MultiFileSha.aggregate(listOf(cf("foo.txt", "abc".padEnd(64, '0'))))
        assertEquals("64-char hex", 64, agg.length)
        assertEquals("lowercase only", agg.lowercase(), agg)
        assertEquals("hex chars only", agg, agg.filter { it in "0123456789abcdef" })
    }

    @Test
    fun `aggregate is stable under input reordering`() {
        // Sort-by-path is the contract: the JSON editor can reorder the
        // files array without producing a different sentinel.
        val a = listOf(
            cf("alpha.bin", "a".repeat(64)),
            cf("beta.bin", "b".repeat(64)),
            cf("gamma.bin", "c".repeat(64)),
        )
        val reshuffled = listOf(a[2], a[0], a[1])
        assertEquals(MultiFileSha.aggregate(a), MultiFileSha.aggregate(reshuffled))
    }

    @Test
    fun `bit-flip on any sha256 flips the aggregate`() {
        val before = MultiFileSha.aggregate(listOf(
            cf("a.bin", "a".repeat(64)),
            cf("b.bin", "b".repeat(64)),
        ))
        val after = MultiFileSha.aggregate(listOf(
            cf("a.bin", "a".repeat(64)),
            cf("b.bin", "b".repeat(63) + "c"), // last char flipped
        ))
        assertNotEquals(before, after)
    }

    @Test
    fun `adding a file flips the aggregate`() {
        val before = MultiFileSha.aggregate(listOf(cf("a.bin", "a".repeat(64))))
        val after = MultiFileSha.aggregate(listOf(
            cf("a.bin", "a".repeat(64)),
            cf("b.bin", "b".repeat(64)),
        ))
        assertNotEquals(before, after)
    }

    @Test
    fun `removing a file flips the aggregate`() {
        val before = MultiFileSha.aggregate(listOf(
            cf("a.bin", "a".repeat(64)),
            cf("b.bin", "b".repeat(64)),
        ))
        val after = MultiFileSha.aggregate(listOf(cf("a.bin", "a".repeat(64))))
        assertNotEquals(before, after)
    }

    @Test
    fun `renaming a path flips the aggregate`() {
        val before = MultiFileSha.aggregate(listOf(cf("foo.bin", "a".repeat(64))))
        val after = MultiFileSha.aggregate(listOf(cf("foo-renamed.bin", "a".repeat(64))))
        assertNotEquals(before, after)
    }

    @Test
    fun `sha case-insensitivity — uppercase and lowercase produce the same aggregate`() {
        val lower = MultiFileSha.aggregate(listOf(cf("x.bin", "deadbeef".repeat(8))))
        val upper = MultiFileSha.aggregate(listOf(cf("x.bin", "DEADBEEF".repeat(8))))
        assertEquals(lower, upper)
    }

    @Test
    fun `URL and size are NOT part of the aggregate`() {
        // The contract is `<path>:<sha256>\n` — url and size aren't
        // mixed in. Catalog editors should be able to change the URL
        // (e.g. mirror migration) or size without flipping the sentinel
        // *as long as the SHA stays the same* (which it should, since
        // SHA-256 is the content hash). This pins the contract.
        val same = MultiFileSha.aggregate(listOf(cf("p.bin", "a".repeat(64),
            url = "https://x.test/p.bin", size = 100L)))
        val urlChanged = MultiFileSha.aggregate(listOf(cf("p.bin", "a".repeat(64),
            url = "https://y.test/p.bin", size = 100L)))
        val sizeChanged = MultiFileSha.aggregate(listOf(cf("p.bin", "a".repeat(64),
            url = "https://x.test/p.bin", size = 200L)))
        assertEquals(same, urlChanged)
        assertEquals(same, sizeChanged)
    }

    @Test
    fun `empty list returns a stable but distinguishable digest`() {
        // hasShippableCatalogEntry rejects empty lists before the
        // aggregate is computed, so this is more of a defense-in-depth
        // assertion than a real path. We just want it to NOT throw and
        // to differ from any single-file aggregate.
        val emptyAgg = MultiFileSha.aggregate(emptyList())
        val singleAgg = MultiFileSha.aggregate(listOf(cf("a.bin", "a".repeat(64))))
        assertEquals(64, emptyAgg.length)
        assertNotEquals(emptyAgg, singleAgg)
    }
}
