package com.playtranslate.yomitan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [summarizeBatch] — the pure counting + truncation behind the
 * multi-file import summary. Pure JVM (no Context).
 */
class YomitanBatchSummaryTest {

    private fun success(title: String) = title to YomitanImportResult.Success(
        YomitanDictionary(
            id = title, title = title, format = 3,
            categories = listOf(YomitanCategory.TERMS), sizeBytes = 0, importedAtMs = 0,
        ),
    )
    private fun dup(title: String) = title to YomitanImportResult.Duplicate(title)
    private fun invalid(name: String) = name to YomitanImportResult.InvalidFormat("bad bank")
    private fun noSpace(name: String) = name to YomitanImportResult.InsufficientSpace(1, 0)
    private fun io(name: String) = name to YomitanImportResult.IoError
    private fun skipped(name: String) = name to YomitanImportResult.Skipped("update-only")

    @Test fun `all success — only the count, no failure groups`() {
        val t = summarizeBatch(listOf(success("A"), success("B"), success("C")), maxPerGroup = 3)
        assertEquals(3, t.importedCount)
        assertEquals(3, t.totalSelected)
        assertTrue(
            t.duplicates.isEmpty && t.invalid.isEmpty && t.noSpace.isEmpty && t.failed.isEmpty,
        )
    }

    @Test fun `mixed outcomes land in the right groups`() {
        val t = summarizeBatch(
            listOf(success("A"), dup("B"), invalid("c.zip"), io("d.zip")), maxPerGroup = 3,
        )
        assertEquals(1, t.importedCount)
        assertEquals(4, t.totalSelected)
        assertEquals(listOf("B"), t.duplicates.examples)
        assertEquals(listOf("c.zip"), t.invalid.examples)
        assertEquals(listOf("d.zip"), t.failed.examples)
    }

    @Test fun `all fail — importedCount is zero`() {
        val t = summarizeBatch(listOf(invalid("a.zip"), io("b.zip")), maxPerGroup = 3)
        assertEquals(0, t.importedCount)
    }

    @Test fun `truncation — exactly maxPerGroup has no overflow`() {
        val t = summarizeBatch(listOf(dup("A"), dup("B"), dup("C")), maxPerGroup = 3)
        assertEquals(listOf("A", "B", "C"), t.duplicates.examples)
        assertEquals(0, t.duplicates.overflow)
    }

    @Test fun `truncation — maxPerGroup plus one overflows by one`() {
        val t = summarizeBatch(listOf(dup("A"), dup("B"), dup("C"), dup("D")), maxPerGroup = 3)
        assertEquals(listOf("A", "B", "C"), t.duplicates.examples)
        assertEquals(1, t.duplicates.overflow)
    }

    @Test fun `out-of-space does not stop the batch — a later file still imports`() {
        // A big no-space failure followed by a smaller success: both are present,
        // the success is counted, and the no-space file is named on its own line.
        val t = summarizeBatch(listOf(noSpace("big.zip"), success("small")), maxPerGroup = 3)
        assertEquals(1, t.importedCount)
        assertEquals(2, t.totalSelected)
        assertEquals(listOf("big.zip"), t.noSpace.examples)
    }

    @Test fun `skipped result is ignored — neither imported nor failed`() {
        val t = summarizeBatch(listOf(success("A"), skipped("ghost")), maxPerGroup = 3)
        assertEquals(1, t.importedCount)
        assertTrue(
            t.duplicates.isEmpty && t.invalid.isEmpty && t.noSpace.isEmpty && t.failed.isEmpty,
        )
    }
}
