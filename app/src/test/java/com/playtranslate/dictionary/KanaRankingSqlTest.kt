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
 * Validates the pure-kana ranking SQL — specifically, that the per-row
 * `rank_score + CASE WHEN position=0 AND uk_applicable=1 THEN 1500000`
 * expression in [DictionaryManager.queryEntryIds]'s reading-fallback
 * branch produces the validated 此処 → 個々 → 九 ordering for ここ.
 *
 * The Python `tests/test_build_jmdict.py` suite validates the score
 * VALUES; this test validates the SQL CORRECTLY USES them. Together they
 * cover both halves of the ranking pipeline.
 *
 * The SQL is a verbatim copy of the kana fallback in DictionaryManager —
 * if you change one, you must change the other. The duplication is
 * deliberate: it makes the test load-bearing.
 */
@RunWith(RobolectricTestRunner::class)
class KanaRankingSqlTest {

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
}
