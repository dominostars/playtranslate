package com.playtranslate.ui

import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [YomitanStyledData.forGroups]: how a list surface's one shared payload
 * is cut down per row. A row with nothing structured takes null (the flat
 * tier, no renderer minted); a row with a structured sense shares the
 * glossary map and keeps only its own dictionaries' CSS.
 */
class YomitanStyledDataTest {

    private val payload = YomitanStyledData(
        structured = mapOf(11L to "{\"a\":1}", 12L to "{\"b\":2}"),
        dictStyles = mapOf("jitendex" to ".a{}", "other" to ".b{}", "unused" to ".c{}"),
        sourceLanguage = "ja",
    )

    private fun group(dictId: String, vararg rowids: Long?) = ImportedSenseGroup(
        source = dictId,
        senses = rowids.map { ImportedSense("def", scRowid = it) },
        dictId = dictId,
    )

    @Test
    fun `a row with no structured sense in the payload takes no payload`() {
        // A flat dictionary (no rowid) beside a rowid the fetch did not
        // return (nothing retained for it): still flat.
        assertNull(payload.forGroups(listOf(group("jitendex", null), group("other", 99L))))
        assertNull(payload.forGroups(emptyList()))
    }

    private fun rowWith(word: String, vararg groups: ImportedSenseGroup) = RowState(
        displayWord = word, reading = "", meaning = "", senses = emptyList(),
        importedGroups = groups.toList(), freqScore = 0, isCommon = false, surface = word,
    )

    @Test
    fun `the fetch covers the first cap rows with a structured sense, in list order, and no others`() {
        val s1 = group("jitendex", 1L)
        val s2 = group("jitendex", 2L, null)
        val s3 = group("jitendex", 3L)
        val plain = group("jmdict", null)
        val rows = listOf(
            rowWith("a"),                 // nothing imported
            rowWith("b", s1),
            rowWith("c", plain),          // imported, nothing structured: never styled, not counted
            rowWith("d", plain, s2),
            rowWith("e", s3),             // past the cap
        )
        assertEquals(listOf(s1, plain, s2), styledCandidateGroups(rows, cap = 2))
        assertEquals(listOf(s1, plain, s2, s3), styledCandidateGroups(rows, cap = 8))
        assertEquals(emptyList<ImportedSenseGroup>(), styledCandidateGroups(rows, cap = 0))
    }

    @Test
    fun `a row with a structured sense shares the glossaries and keeps only its dictionaries' css`() {
        val cut = payload.forGroups(listOf(group("jitendex", 11L, null), group("other", null)))!!
        assertSame(payload.structured, cut.structured)
        assertEquals(mapOf("jitendex" to ".a{}", "other" to ".b{}"), cut.dictStyles)
        assertEquals("ja", cut.sourceLanguage)
    }
}
