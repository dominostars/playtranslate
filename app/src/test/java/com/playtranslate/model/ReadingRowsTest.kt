package com.playtranslate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DictionaryEntry.orderedReadingRows] — the word-detail header's
 * reading rows: common-use (rankScore) order, stable on ties, deduped by
 * reading, occurrence bolded in place. Pure JUnit; no Android.
 */
class ReadingRowsTest {

    private fun entry(vararg headwords: Headword) = DictionaryEntry(
        slug = headwords.firstOrNull()?.written ?: headwords.firstOrNull()?.reading ?: "?",
        isCommon = null,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = headwords.toList(),
        senses = emptyList(),
        freqScore = 0,
    )

    private fun ashita() = entry(
        Headword("明日", "あした", rankScore = 30),
        Headword("明日", "あす", rankScore = 20),
        Headword("明日", "みょうにち", rankScore = 10),
    )

    @Test fun `orders readings by rankScore descending`() {
        assertEquals(
            listOf("あした", "あす", "みょうにち"),
            ashita().orderedReadingRows(null).map { it.reading },
        )
    }

    @Test fun `reorders when rankScore disagrees with position`() {
        val e = entry(
            Headword("明日", "あした", rankScore = 10),
            Headword("明日", "あす", rankScore = 50),
            Headword("明日", "みょうにち", rankScore = 5),
        )
        assertEquals(
            listOf("あす", "あした", "みょうにち"),
            e.orderedReadingRows(null).map { it.reading },
        )
    }

    @Test fun `equal rankScore keeps position order (stable)`() {
        val e = entry(
            Headword("明日", "あした"),
            Headword("明日", "あす"),
            Headword("明日", "みょうにち"),
        )
        assertEquals(
            listOf("あした", "あす", "みょうにち"),
            e.orderedReadingRows(null).map { it.reading },
        )
    }

    @Test fun `bolds the occurrence reading in place, not pulled to the top`() {
        val rows = ashita().orderedReadingRows("あす")
        assertEquals("あす", rows.single { it.bolded }.reading)
        assertEquals(1, rows.indexOfFirst { it.bolded }) // stays 2nd by rank
    }

    @Test fun `null occurrence bolds nothing`() {
        assertTrue(ashita().orderedReadingRows(null).none { it.bolded })
    }

    @Test fun `unmatched occurrence bolds nothing`() {
        assertTrue(ashita().orderedReadingRows("あさひ").none { it.bolded })
    }

    @Test fun `dedupes by reading keeping the higher-rankScore headword`() {
        val e = entry(
            Headword("決まる", "きまる", rankScore = 10),
            Headword("極まる", "きまる", rankScore = 40),
        )
        val rows = e.orderedReadingRows(null)
        assertEquals(1, rows.size)
        assertEquals("きまる", rows.single().reading)
        assertEquals("極まる", rows.single().written)
    }

    @Test fun `skips reading-less (written-only) headwords`() {
        assertTrue(entry(Headword("cat", null)).orderedReadingRows(null).isEmpty())
    }

    @Test fun `carries written and pitch onto the row`() {
        val e = entry(Headword("明日", "あす", rankScore = 20, pitch = listOf(0)))
        val row = e.orderedReadingRows("あす").single()
        assertEquals("明日", row.written)
        assertEquals(listOf(0), row.pitch)
        assertTrue(row.bolded)
    }
}
