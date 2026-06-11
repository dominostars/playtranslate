package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Tests for the dictionary-search prefix scan — [DictionaryManager.prefixEntryIds],
 * [DictionaryManager.primaryFormsForEntry], and [prefixUpperBound]. These are
 * the load-bearing, stateless cores of [DictionaryManager.searchPrefix]; pinning
 * them on a fixture DB keeps the ranking contract honest without the singleton
 * or a real pack.
 *
 * Runs under Robolectric because [SQLiteDatabase] is Android-only. The fixture
 * mirrors the JMdict `entry` / `headword` / `reading` schema with the columns
 * the scan reads (`rank_score`, `uk_applicable`, `freq_score`).
 */
@RunWith(RobolectricTestRunner::class)
class SearchPrefixTest {

    private lateinit var tmp: File
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        tmp = createTempDirectory("search-prefix").toFile()
        db = SQLiteDatabase.openOrCreateDatabase(File(tmp, "dict.sqlite"), null)
        db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, freq_score INTEGER NOT NULL DEFAULT 0)")
        db.execSQL(
            "CREATE TABLE headword (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "text TEXT NOT NULL, rank_score INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE reading (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, " +
                "text TEXT NOT NULL, rank_score INTEGER NOT NULL DEFAULT 0, " +
                "uk_applicable INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_headword_text ON headword(text)")
        db.execSQL("CREATE INDEX idx_reading_text ON reading(text)")

        // ── Exact-vs-prefix (ranked): exact must win despite lower freq ──
        entry(10, freq = 0); headword(10, "ab", rank = 0)
        entry(11, freq = 0); headword(11, "abc", rank = 5_000_000)

        // ── Within prefix bucket, higher rank_score first ──
        entry(20, freq = 0); headword(20, "prefix", rank = 100)
        entry(21, freq = 0); headword(21, "press", rank = 200)

        // ── Kana fold: hiragana query matches a katakana (loanword) reading ──
        entry(30, freq = 0); reading(30, "テレビ", rank = 100)

        // ── Legacy (no rank_score): orders by entry.freq_score ──
        entry(40, freq = 10); headword(40, "bar", rank = 0)
        entry(41, freq = 90); headword(41, "bat", rank = 0)

        // ── Range upper bound: "ca" must not pull in "czar" or "dog" ──
        entry(50, freq = 0); headword(50, "cat", rank = 0)
        entry(51, freq = 0); headword(51, "czar", rank = 0)
        entry(52, freq = 0); headword(52, "dog", rank = 0)

        // ── uk position-0 bonus participates in the reading-arm score ──
        entry(60, freq = 0); headword(60, "此処", rank = 0); reading(60, "ここ", rank = 0, uk = 1)
        entry(61, freq = 0); headword(61, "個々", rank = 0); reading(61, "ここ", rank = 500_000, uk = 0)

        // ── Pure-kana entry for primaryFormsForEntry ──
        entry(70, freq = 0); headword(70, "食べる", rank = 0); reading(70, "たべる", rank = 0)
    }

    @After fun tearDown() {
        db.close()
        tmp.deleteRecursively()
    }

    private fun entry(id: Int, freq: Int) =
        db.execSQL("INSERT INTO entry (id, freq_score) VALUES (?, ?)", arrayOf<Any>(id, freq))

    private fun headword(entryId: Int, text: String, rank: Int, position: Int = 0) =
        db.execSQL(
            "INSERT INTO headword (entry_id, position, text, rank_score) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(entryId, position, text, rank),
        )

    private fun reading(entryId: Int, text: String, rank: Int, uk: Int = 0, position: Int = 0) =
        db.execSQL(
            "INSERT INTO reading (entry_id, position, text, rank_score, uk_applicable) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(entryId, position, text, rank, uk),
        )

    @Test fun `exact match is pinned above a more frequent prefix match`() {
        val ids = DictionaryManager.prefixEntryIds(db, "ab", limit = 20, ranked = true)
        assertEquals(listOf(10L, 11L), ids)
    }

    @Test fun `within the prefix bucket higher rank_score sorts first`() {
        val ids = DictionaryManager.prefixEntryIds(db, "pre", limit = 20, ranked = true)
        assertEquals(listOf(21L, 20L), ids)
    }

    @Test fun `hiragana query matches a katakana reading via the kana fold`() {
        val ids = DictionaryManager.prefixEntryIds(db, "てれ", limit = 20, ranked = true)
        assertEquals(listOf(30L), ids)
    }

    @Test fun `legacy path without rank_score orders by entry freq_score`() {
        val ids = DictionaryManager.prefixEntryIds(db, "ba", limit = 20, ranked = false)
        assertEquals(listOf(41L, 40L), ids)
    }

    @Test fun `range scan does not over-match past the prefix`() {
        val ids = DictionaryManager.prefixEntryIds(db, "ca", limit = 20, ranked = true)
        assertEquals(listOf(50L), ids)
        assertFalse(ids.contains(51L))   // "czar" starts with c, not ca
        assertFalse(ids.contains(52L))   // "dog"
    }

    @Test fun `uk position-0 reading outranks a higher-base reading on the same exact query`() {
        // Both readings equal the query exactly (so both get the exact bonus);
        // the +1.5M uk-position-0 bonus must outweigh entry 61's higher base.
        val ids = DictionaryManager.prefixEntryIds(db, "ここ", limit = 20, ranked = true)
        assertEquals(listOf(60L, 61L), ids)
    }

    @Test fun `limit caps the number of results`() {
        // "cat" + "czar" both match the "c" prefix; limit=1 must return one.
        val ids = DictionaryManager.prefixEntryIds(db, "c", limit = 1, ranked = true)
        assertEquals(1, ids.size)
        assertTrue(ids.first() in listOf(50L, 51L))
    }

    @Test fun `primaryFormsForEntry returns written and reading for a kanji entry`() {
        assertEquals("食べる" to "たべる", DictionaryManager.primaryFormsForEntry(db, 70L))
    }

    @Test fun `primaryFormsForEntry returns null written for a kana-only entry`() {
        val (written, reading) = DictionaryManager.primaryFormsForEntry(db, 30L)
        assertNull(written)
        assertEquals("テレビ", reading)
    }

    @Test fun `prefixUpperBound brackets the prefix range`() {
        // The bound is strictly greater than any string starting with the prefix
        // and excludes the next sibling: "たべ" ≤ "たべる" < bound ≤ "たほ".
        val bound = prefixUpperBound("たべ")!!
        assertTrue("たべ" < bound)
        assertTrue("たべる" < bound)
        assertTrue("たぼ" >= bound)        // next char past べ is no longer in range
        assertEquals("たぺ", bound)         // べ (U+3079) + 1 = ぺ (U+307A)
    }

    @Test fun `prefixUpperBound returns null on empty input`() {
        assertNull(prefixUpperBound(""))
    }
}
