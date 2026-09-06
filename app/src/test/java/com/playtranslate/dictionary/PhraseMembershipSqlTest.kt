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
 * Validates the reading-tier phrase-membership SQL: every primary reading
 * (rank_score >= 0) plus the per-text kana-native bit that gates
 * [DictionaryManager.Companion.Suspicion.FUNCTION_RUN] candidates —
 * 1 when some qualifying row is uk-applicable OR belongs to an entry with
 * no kanji headword.
 *
 * The SQL string is a verbatim copy of
 * `DictionaryManager.RANKED_PHRASE_READING_SQL` (plus the IN list and
 * GROUP BY the production code appends). If you change one, change the
 * other — the duplication is deliberate so this test is load-bearing.
 */
@RunWith(RobolectricTestRunner::class)
class PhraseMembershipSqlTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Verbatim copy of `DictionaryManager.RANKED_PHRASE_READING_SQL`, with
     *  a single-placeholder IN list and the GROUP BY appended the way
     *  `batchCheckPhrases` does. */
    private val SQL = """
        SELECT r.text,
               MAX(CASE WHEN r.uk_applicable = 1
                         OR NOT EXISTS (SELECT 1 FROM headword h WHERE h.entry_id = r.entry_id)
                    THEN 1 ELSE 0 END)
        FROM reading r
        WHERE r.rank_score >= 0 AND r.text IN (?) GROUP BY r.text
    """.trimIndent()

    @Test fun `kanji word's reading is a primary reading but NOT kana-native - the teori specimen`() {
        val db = openDb()
        // 手織り (1699550): ており at rank 0 — passes the rank floor, but the
        // entry has kanji headwords and no uk tag.
        insertReading(db, entryId = 1699550, text = "ており", rankScore = 0, ukApplicable = 0)
        insertHeadword(db, entryId = 1699550, text = "手織り")

        val (found, kanaNative) = run(db, "ており")
        assertTrue("ており" in found)
        assertTrue("ており" !in kanaNative)
    }

    @Test fun `uk-tagged reading is kana-native`() {
        val db = openDb()
        // かも知れない (1002970): uk-tagged — a kana word despite kanji forms.
        insertReading(db, entryId = 1002970, text = "かもしれない", rankScore = 1_000_000, ukApplicable = 1)
        insertHeadword(db, entryId = 1002970, text = "かも知れない")

        val (found, kanaNative) = run(db, "かもしれない")
        assertTrue("かもしれない" in found)
        assertTrue("かもしれない" in kanaNative)
    }

    @Test fun `entry with no kanji headword is kana-native`() {
        val db = openDb()
        // だから (1007310): kanji-less entry — kana-native without a uk tag.
        insertReading(db, entryId = 1007310, text = "だから", rankScore = 1_000_000, ukApplicable = 0)

        val (found, kanaNative) = run(db, "だから")
        assertTrue("だから" in found)
        assertTrue("だから" in kanaNative)
    }

    @Test fun `one kana-native entry among homophones is enough`() {
        val db = openDb()
        // かな: 仮名 (kanji, non-uk) shares the reading with the kanji-less
        // sentence particle. MAX over rows must yield kana-native.
        insertReading(db, entryId = 1590540, text = "かな", rankScore = 1_000_000, ukApplicable = 0)
        insertHeadword(db, entryId = 1590540, text = "仮名")
        insertReading(db, entryId = 1002940, text = "かな", rankScore = 1_000_000, ukApplicable = 0)

        val (_, kanaNative) = run(db, "かな")
        assertTrue("かな" in kanaNative)
    }

    @Test fun `negative rank stays excluded entirely - the kokono precedent`() {
        val db = openDb()
        // 九's position-penalized ここの stem: below the rank floor, so it is
        // in neither tier (pre-existing guard, unchanged).
        insertReading(db, entryId = 1578150, text = "ここの", rankScore = -20_000, ukApplicable = 0)

        val (found, kanaNative) = run(db, "ここの")
        assertTrue(found.isEmpty())
        assertTrue(kanaNative.isEmpty())
    }

    @Test fun `rank-only fallback SQL works on a schema without uk_applicable`() {
        // Codex review regression (2026-08-28): rank_score present but
        // uk_applicable absent. batchCheckPhrases probes each column its SQL
        // dereferences, so this schema takes the rank-only branch below —
        // rank floor kept, kana-native bit failing open at the Kotlin layer.
        val file = File(tmp.root, "rankonly.sqlite")
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE reading (entry_id INTEGER NOT NULL, text TEXT NOT NULL, rank_score INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("INSERT INTO reading (entry_id, text, rank_score) VALUES (1699550, 'ており', 0)")
        db.execSQL("INSERT INTO reading (entry_id, text, rank_score) VALUES (1578150, 'ここの', -20000)")

        // Verbatim copy of the rank-only branch in `batchCheckPhrases`.
        val found = mutableSetOf<String>()
        db.rawQuery(
            "SELECT DISTINCT text FROM reading WHERE rank_score >= 0 AND text IN (?, ?)",
            arrayOf("ており", "ここの"),
        ).use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
        assertEquals(setOf("ており"), found)
    }

    // ── Priority-headword tier (Suspicion.CONVERB_CUT's admission bar) ────
    // A CONVERB_CUT candidate may only fuse via a headword the pack ALSO
    // ranks as priority (`headword.rank_score > 0`) — plain headword
    // membership isn't enough (押して, adv "forcibly", is a genuine headword
    // without a ke_pri tag).

    /** Verbatim copy of `batchCheckPhrases`'s headword-rank query. */
    private val HEADWORD_RANK_SQL =
        "SELECT text, MAX(rank_score) FROM headword WHERE text IN (?, ?) GROUP BY text"

    @Test fun `a headword without a priority tag is found but not priority`() {
        val db = openDb()
        // 押して (1852820): adv "forcibly" — a genuine headword, rank_score 0.
        insertHeadword(db, entryId = 1852820, text = "押して", rankScore = 0)
        // 知らせる (1361140): ichi1 — rank_score > 0.
        insertHeadword(db, entryId = 1361140, text = "知らせる", rankScore = 1_000_000)

        val found = mutableSetOf<String>()
        val priority = mutableSetOf<String>()
        db.rawQuery(HEADWORD_RANK_SQL, arrayOf("押して", "知らせる")).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(0)
                found.add(text)
                if (c.getInt(1) > 0) priority.add(text)
            }
        }
        assertEquals(setOf("押して", "知らせる"), found)
        assertEquals(setOf("知らせる"), priority)
    }

    @Test fun `one priority row among homograph headwords is enough`() {
        val db = openDb()
        // MAX over rows: any priority-tagged homograph makes the text priority.
        insertHeadword(db, entryId = 1, text = "した", rankScore = 0)
        insertHeadword(db, entryId = 2, text = "した", rankScore = 1_000_000)

        var maxRank = Int.MIN_VALUE
        db.rawQuery(
            "SELECT text, MAX(rank_score) FROM headword WHERE text IN (?) GROUP BY text",
            arrayOf("した"),
        ).use { c -> if (c.moveToNext()) maxRank = c.getInt(1) }
        assertTrue(maxRank > 0)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun run(db: SQLiteDatabase, text: String): Pair<Set<String>, Set<String>> {
        val found = mutableSetOf<String>()
        val kanaNative = mutableSetOf<String>()
        db.rawQuery(SQL, arrayOf(text)).use { c ->
            while (c.moveToNext()) {
                found.add(c.getString(0))
                if (c.getInt(1) == 1) kanaNative.add(c.getString(0))
            }
        }
        return found to kanaNative
    }

    private fun openDb(): SQLiteDatabase {
        val file = File(tmp.root, "membership.sqlite")
        if (file.exists()) file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            """
            CREATE TABLE reading (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                text TEXT NOT NULL,
                rank_score INTEGER NOT NULL DEFAULT 0,
                uk_applicable INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE headword (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                text TEXT NOT NULL,
                rank_score INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        return db
    }

    private fun insertReading(db: SQLiteDatabase, entryId: Long, text: String, rankScore: Int, ukApplicable: Int) {
        db.execSQL(
            "INSERT INTO reading (entry_id, text, rank_score, uk_applicable) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(entryId, text, rankScore, ukApplicable),
        )
    }

    private fun insertHeadword(db: SQLiteDatabase, entryId: Long, text: String, rankScore: Int = 0) {
        db.execSQL(
            "INSERT INTO headword (entry_id, text, rank_score) VALUES (?, ?, ?)",
            arrayOf<Any>(entryId, text, rankScore),
        )
    }
}
