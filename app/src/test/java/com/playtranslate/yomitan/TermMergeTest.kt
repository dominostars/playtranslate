package com.playtranslate.yomitan

import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermMergeTest {

    private val order = listOf("dictA" to "Dict A", "dictB" to "Dict B")

    private fun row(dict: String, reading: String, score: Double, vararg defs: String) =
        TermMerge.Row(dict, reading, score, defs.toList())

    private fun YomitanDataStore.TermLookup.definitionsOf(group: Int): List<String> =
        groups[group].senses.map { it.definition }

    private fun merge(
        rows: List<TermMerge.Row>,
        normalizedReading: String?,
        normalizedTerm: String = "端",
        singleDictionary: Boolean = false,
    ) = TermMerge.merge(rows, order, normalizedReading, normalizedTerm, singleDictionary)

    @Test
    fun `groups follow section order regardless of row order`() {
        val result = merge(
            rows = listOf(row("dictB", "ねこ", 0.0, "b-def"), row("dictA", "ねこ", 0.0, "a-def")),
            normalizedReading = null,
        )
        assertEquals(
            listOf(
                ImportedSenseGroup("Dict A", listOf(ImportedSense("a-def")), dictId = "dictA"),
                ImportedSenseGroup("Dict B", listOf(ImportedSense("b-def")), dictId = "dictB"),
            ),
            result.groups,
        )
    }

    @Test
    fun `score sorts within a dict, descending, ties keep bank order`() {
        val result = merge(
            rows = listOf(
                row("dictA", "ねこ", 10.0, "low"),
                row("dictA", "ねこ", 50.0, "high"),
                row("dictA", "ねこ", 50.0, "high-second"),
            ),
            normalizedReading = null,
        )
        assertEquals(listOf("high", "high-second", "low"), result.definitionsOf(0))
    }

    @Test
    fun `one entry's glossary items join as one sense`() {
        val result = merge(
            rows = listOf(row("dictA", "ねこ", 0.0, "cat", "feline")),
            normalizedReading = null,
        )
        assertEquals(listOf("cat; feline"), result.definitionsOf(0))
    }

    @Test
    fun `pos tag names join for display`() {
        val result = merge(
            rows = listOf(TermMerge.Row("dictA", "ねこ", 0.0, listOf("cat"), pos = "n exp")),
            normalizedReading = null,
        )
        assertEquals(listOf(ImportedSense("cat", pos = "n, exp")), result.groups.single().senses)
    }

    @Test
    fun `matching reading narrows rows`() {
        val result = merge(
            rows = listOf(row("dictA", "はし", 0.0, "edge"), row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
        )
        assertEquals(listOf("edge"), result.definitionsOf(0))
        assertEquals("はし", result.resolvedReading)
    }

    @Test
    fun `unmatched reading yields nothing - homographs never cross`() {
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
        )
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
    }

    @Test
    fun `blank-reading sentinel rows match any supplied reading`() {
        // A sloppy conversion stored no reading for the kanji term — the
        // ingest sentinel is reading == term. It must not go silent under
        // hard disambiguation.
        val result = merge(
            rows = listOf(row("dictA", "端", 0.0, "undisambiguated def")),
            normalizedReading = "はし",
            normalizedTerm = "端",
        )
        assertEquals(listOf("undisambiguated def"), result.definitionsOf(0))
    }

    @Test
    fun `null reading resolves from the first dict in section order`() {
        val result = merge(
            rows = listOf(row("dictB", "よみビー", 0.0, "b"), row("dictA", "よみえー", 0.0, "a")),
            normalizedReading = null,
        )
        assertEquals("よみえー", result.resolvedReading)
    }

    @Test
    fun `single-dictionary mode keeps only the highest-priority group`() {
        val result = merge(
            rows = listOf(row("dictB", "ねこ", 0.0, "b-def"), row("dictA", "ねこ", 0.0, "a-def")),
            normalizedReading = null,
            singleDictionary = true,
        )
        assertEquals(
            listOf(ImportedSenseGroup("Dict A", listOf(ImportedSense("a-def")), dictId = "dictA")),
            result.groups,
        )
    }

    @Test
    fun `single-dictionary mode falls through dicts without results`() {
        // dictA has rows, but reading narrowing removes them all — dictB is
        // the first dict WITH results and must win, not an empty lookup.
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame"), row("dictB", "はし", 0.0, "edge")),
            normalizedReading = "はし",
            singleDictionary = true,
        )
        assertEquals(
            listOf(ImportedSenseGroup("Dict B", listOf(ImportedSense("edge")), dictId = "dictB")),
            result.groups,
        )
    }

    @Test
    fun `single-dictionary mode suppresses pack senses when a group wins`() {
        val result = merge(
            rows = listOf(row("dictA", "ねこ", 0.0, "a-def")),
            normalizedReading = null,
            singleDictionary = true,
        )
        assertEquals(true, result.suppressesPackSenses)
    }

    @Test
    fun `single-dictionary mode with no surviving group leaves the pack alone`() {
        // The pack is the implicit last dictionary in the priority order —
        // with every imported row narrowed away it IS the one with results.
        val result = merge(
            rows = listOf(row("dictA", "はじ", 0.0, "shame")),
            normalizedReading = "はし",
            singleDictionary = true,
        )
        assertEquals(false, result.suppressesPackSenses)
    }

    @Test
    fun `multi-dictionary mode never suppresses pack senses`() {
        val result = merge(
            rows = listOf(row("dictA", "ねこ", 0.0, "a-def")),
            normalizedReading = null,
        )
        assertEquals(false, result.suppressesPackSenses)
    }

    @Test
    fun `scRowid rides each sense through the merge`() {
        val result = merge(
            rows = listOf(
                TermMerge.Row("dictA", "ねこ", 5.0, listOf("styled"), scRowid = 42L),
                TermMerge.Row("dictA", "ねこ", 1.0, listOf("flat")),
            ),
            normalizedReading = null,
        )
        assertEquals(
            listOf(42L, null),
            result.groups.single().senses.map { it.scRowid },
        )
    }

    @Test
    fun `dicts not in the section order are dropped`() {
        val result = merge(
            rows = listOf(row("ghost", "ねこ", 0.0, "stale")),
            normalizedReading = null,
        )
        assertEquals(emptyList<ImportedSenseGroup>(), result.groups)
        assertNull(result.resolvedReading)
    }
}
