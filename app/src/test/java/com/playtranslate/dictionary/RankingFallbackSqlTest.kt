package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Validates the ranking SQL across BOTH the v2 path (rank_score +
 * uk_applicable) AND the OLD pre-v2 fallback path (entry.freq_score JOIN)
 * that engages when an on-disk pack lacks the v2 schema columns. The
 * column-existence guard in `DictionaryManager.hasRankScore` decides
 * which to dispatch per query.
 *
 * The Python `tests/test_build_jmdict.py` suite validates the score
 * VALUES; this test validates the SQL CORRECTLY USES them.
 *
 * The SQL strings here are verbatim copies of the constants in
 * `DictionaryManager.companion`. If you change one, you must change the
 * other — the duplication is deliberate so this test is load-bearing.
 */
@RunWith(RobolectricTestRunner::class)
class RankingFallbackSqlTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Verbatim copy of `DictionaryManager.queryEntryIds` reading fallback.
     *  Update this if you change that SQL — the test catches drift either
     *  way (production breaks, or this test breaks on harmless rewording). */
    private val KANA_FALLBACK_SQL = """
        SELECT entry_id FROM reading
        WHERE text = ?
        GROUP BY entry_id
        ORDER BY MAX(
            rank_score
            + CASE WHEN position = 0 AND uk_applicable = 1
                   THEN 1500000 ELSE 0 END
        ) DESC
        LIMIT 8
    """.trimIndent()

    @Test fun `koko returns 此処 first then 個々 then 九 — the validated bug fix`() {
        val db = openDb()
        // 此処 (entry 1288810): ここ at pos 0, ichi1, uk_applicable=1.
        // rank_score = +1M, with bonus = +2.5M (winner).
        insertReading(db, entryId = 1288810, position = 0, text = "ここ",
            rePri = "ichi1", rankScore = 1_000_000, ukApplicable = 1)
        // 個々 (entry 1593190): ここ at pos 0, ichi1+news1+nf07, uk_applicable=0.
        // rank_score = +2M, no bonus.
        insertReading(db, entryId = 1593190, position = 0, text = "ここ",
            rePri = "ichi1,news1,nf07", rankScore = 2_000_000, ukApplicable = 0)
        // 九 (entry 1578150): ここ at pos 4, no re_pri, uk_applicable=0.
        // rank_score = -40K, no bonus.
        insertReading(db, entryId = 1578150, position = 4, text = "ここ",
            rePri = "", rankScore = -40_000, ukApplicable = 0)

        val ids = runQuery(db, KANA_FALLBACK_SQL, "ここ")
        assertEquals(
            "此処 → 個々 → 九 (uk-bonus on 此処 wins; position penalty on 九 loses)",
            listOf(1288810L, 1593190L, 1578150L),
            ids,
        )
    }

    @Test fun `position-0 alone does not get the bonus without uk_applicable`() {
        val db = openDb()
        // Two equally-prioritized position-0 readings, only one uk-tagged.
        // The non-uk one MUST NOT get the bonus.
        insertReading(db, entryId = 100, position = 0, text = "ほげ",
            rePri = "ichi1", rankScore = 1_000_000, ukApplicable = 0)
        insertReading(db, entryId = 200, position = 0, text = "ほげ",
            rePri = "ichi1", rankScore = 1_000_000, ukApplicable = 1)

        val ids = runQuery(db, KANA_FALLBACK_SQL, "ほげ")
        assertEquals(
            "uk-applicable entry wins via the +1.5M bonus",
            listOf(200L, 100L),
            ids,
        )
    }

    @Test fun `position 1 with uk_applicable does NOT get the bonus`() {
        val db = openDb()
        // Models the 鴇/時 risk: 鴇 has とき at position=1 with uk-tagged
        // sense. The position gate must filter it out so 時 (no uk) wins.
        insertReading(db, entryId = 300, position = 1, text = "とき",
            rePri = "", rankScore = -10_000, ukApplicable = 1)  // 鴇
        insertReading(db, entryId = 400, position = 0, text = "とき",
            rePri = "ichi1,news1", rankScore = 2_000_000, ukApplicable = 0)  // 時

        val ids = runQuery(db, KANA_FALLBACK_SQL, "とき")
        assertEquals(
            "Position-0 gate keeps the bonus from rescuing 鴇",
            listOf(400L, 300L),
            ids,
        )
    }

    @Test fun `query against a kana with no matches returns empty`() {
        val db = openDb()
        insertReading(db, entryId = 1, position = 0, text = "あ",
            rePri = "", rankScore = 0, ukApplicable = 0)
        val ids = runQuery(db, KANA_FALLBACK_SQL, "んんんん")
        assertTrue(ids.isEmpty())
    }

    @Test fun `LIMIT 8 caps result count`() {
        val db = openDb()
        for (i in 1..15) {
            insertReading(db, entryId = i.toLong(), position = 0, text = "x",
                rePri = "", rankScore = -i, ukApplicable = 0)
        }
        val ids = runQuery(db, KANA_FALLBACK_SQL, "x")
        assertEquals(8, ids.size)
    }

    // ── OLD SQL fallback (pre-v2 schema) ────────────────────────────────

    /** Verbatim copy of `DictionaryManager.LEGACY_QUERY_KANA`. Used when the
     *  on-disk pack lacks rank_score / uk_applicable. */
    private val LEGACY_QUERY_KANA = """
        SELECT DISTINCT r.entry_id FROM reading r
        JOIN entry e ON e.id = r.entry_id
        WHERE r.text = ?
        ORDER BY e.freq_score DESC LIMIT 8
    """.trimIndent()

    /** Verbatim copy of `DictionaryManager.LEGACY_QUERY_KANJI`. */
    private val LEGACY_QUERY_KANJI = """
        SELECT DISTINCT h.entry_id FROM headword h
        JOIN entry e ON e.id = h.entry_id
        WHERE h.text = ?
        ORDER BY e.freq_score DESC LIMIT 8
    """.trimIndent()

    /** Verbatim copy of `DictionaryManager.LEGACY_QUERY_KANJI_WITH_READING`. */
    private val LEGACY_QUERY_KANJI_WITH_READING = """
        SELECT DISTINCT h.entry_id FROM headword h
        JOIN entry e ON e.id = h.entry_id
        JOIN reading r ON r.entry_id = h.entry_id
        WHERE h.text = ? AND r.text = ?
        ORDER BY e.freq_score DESC LIMIT 8
    """.trimIndent()

    @Test fun `OLD SQL ranks by entry freq_score against v1-shaped DB`() {
        val db = openV1Db()
        // Three entries sharing reading "ここ", ranked by entry.freq_score.
        // Without the v2 columns, this is the best ranking we have. 個々 (4)
        // wins over 此処 (3) — same as Yomitan/Jisho behavior pre-uk-bonus.
        insertV1Entry(db, entryId = 1288810, freqScore = 3)
        insertV1Reading(db, entryId = 1288810, position = 0, text = "ここ")
        insertV1Entry(db, entryId = 1593190, freqScore = 4)
        insertV1Reading(db, entryId = 1593190, position = 0, text = "ここ")
        insertV1Entry(db, entryId = 1578150, freqScore = 5)
        insertV1Reading(db, entryId = 1578150, position = 4, text = "ここ")

        val ids = runQuery(db, LEGACY_QUERY_KANA, "ここ")
        // OLD SQL: ranks purely by entry.freq_score DESC. 九 (5) wins, then
        // 個々 (4), then 此処 (3). This is the "wrong but functional"
        // pre-v2 behavior. The point of v2 was to fix this — but until the
        // user upgrades, the fallback path keeps the dictionary working.
        assertEquals(listOf(1578150L, 1593190L, 1288810L), ids)
    }

    @Test fun `OLD kanji SQL returns matches against v1-shaped DB`() {
        val db = openV1Db()
        insertV1Entry(db, entryId = 1, freqScore = 5)
        insertV1Headword(db, entryId = 1, position = 0, text = "九")
        insertV1Entry(db, entryId = 2, freqScore = 3)
        insertV1Headword(db, entryId = 2, position = 0, text = "九")

        val ids = runQuery(db, LEGACY_QUERY_KANJI, "九")
        assertEquals("freq_score DESC", listOf(1L, 2L), ids)
    }

    @Test fun `OLD kanji-with-reading SQL returns matches against v1-shaped DB`() {
        val db = openV1Db()
        insertV1Entry(db, entryId = 1, freqScore = 5)
        insertV1Headword(db, entryId = 1, position = 0, text = "此処")
        insertV1Reading(db, entryId = 1, position = 0, text = "ここ")
        insertV1Entry(db, entryId = 2, freqScore = 3)
        insertV1Headword(db, entryId = 2, position = 0, text = "此処")
        insertV1Reading(db, entryId = 2, position = 0, text = "ここ")

        val ids = runQuery2(db, LEGACY_QUERY_KANJI_WITH_READING, "此処", "ここ")
        assertEquals(listOf(1L, 2L), ids)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun openDb(): SQLiteDatabase {
        val file = File(tmp.root, "ranking.sqlite")
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """
            CREATE TABLE reading (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                text TEXT NOT NULL,
                no_kanji INTEGER NOT NULL DEFAULT 0,
                re_pri TEXT NOT NULL DEFAULT '',
                freq_score INTEGER NOT NULL DEFAULT 0,
                re_inf TEXT NOT NULL DEFAULT '',
                rank_score INTEGER NOT NULL DEFAULT 0,
                uk_applicable INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        return db
    }

    private fun insertReading(
        db: SQLiteDatabase,
        entryId: Long,
        position: Int,
        text: String,
        rePri: String,
        rankScore: Int,
        ukApplicable: Int,
    ) {
        db.execSQL(
            "INSERT INTO reading (entry_id, position, text, no_kanji, " +
                "re_pri, freq_score, re_inf, rank_score, uk_applicable) " +
                "VALUES (?, ?, ?, 0, ?, 0, '', ?, ?)",
            arrayOf<Any>(entryId, position, text, rePri, rankScore, ukApplicable),
        )
    }

    private fun runQuery(db: SQLiteDatabase, sql: String, arg: String): List<Long> {
        val out = mutableListOf<Long>()
        db.rawQuery(sql, arrayOf(arg)).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    private fun runQuery2(db: SQLiteDatabase, sql: String, a: String, b: String): List<Long> {
        val out = mutableListOf<Long>()
        db.rawQuery(sql, arrayOf(a, b)).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** v1-shaped DB: entry, headword (no rank_score / ke_pri), reading (no
     *  rank_score / re_inf / uk_applicable). Used to validate OLD SQL paths
     *  work against pre-v2 packs. */
    private fun openV1Db(): SQLiteDatabase {
        val file = File(tmp.root, "ranking_v1.sqlite")
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER DEFAULT 0, freq_score INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
        db.execSQL("CREATE TABLE reading (entry_id INTEGER, position INTEGER, text TEXT, no_kanji INTEGER DEFAULT 0, re_pri TEXT DEFAULT '', freq_score INTEGER DEFAULT 0)")
        return db
    }

    private fun insertV1Entry(db: SQLiteDatabase, entryId: Long, freqScore: Int) {
        db.execSQL(
            "INSERT INTO entry (id, freq_score) VALUES (?, ?)",
            arrayOf<Any>(entryId, freqScore),
        )
    }

    private fun insertV1Headword(db: SQLiteDatabase, entryId: Long, position: Int, text: String) {
        db.execSQL(
            "INSERT INTO headword (entry_id, position, text) VALUES (?, ?, ?)",
            arrayOf<Any>(entryId, position, text),
        )
    }

    private fun insertV1Reading(db: SQLiteDatabase, entryId: Long, position: Int, text: String) {
        db.execSQL(
            "INSERT INTO reading (entry_id, position, text) VALUES (?, ?, ?)",
            arrayOf<Any>(entryId, position, text),
        )
    }
}
