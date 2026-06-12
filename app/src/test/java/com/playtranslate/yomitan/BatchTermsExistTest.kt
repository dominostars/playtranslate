package com.playtranslate.yomitan

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Robolectric tests for [YomitanDataStore.batchTermsExistQuery] — the SQL
 * core of the re-glob phrase oracle — against a fixture database built with
 * the production `term` DDL. Pins the enabled-dict filter: a term present
 * only in a dict outside the allow-list must NOT gate a phrase glob, or the
 * glob's subsequent lookup would come back empty and the tokens would
 * vanish from the Words panel.
 */
@RunWith(RobolectricTestRunner::class)
class BatchTermsExistTest {

    private val tmp = createTempDirectory("batch-terms-exist").toFile()
    private val db: SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(File(tmp, "yomitan.sqlite"), null).apply {
            execSQL(
                "CREATE TABLE IF NOT EXISTS term (" +
                    "dict_id TEXT NOT NULL, term TEXT NOT NULL, reading TEXT NOT NULL, " +
                    "score REAL NOT NULL, defs TEXT NOT NULL, pos TEXT NOT NULL)"
            )
            execSQL("CREATE INDEX IF NOT EXISTS idx_term_term ON term(term)")
        }

    @After
    fun tearDown() {
        db.close()
        tmp.deleteRecursively()
    }

    private fun insert(dictId: String, term: String) {
        db.execSQL(
            "INSERT INTO term (dict_id, term, reading, score, defs, pos) VALUES (?, ?, ?, 0, '[]', '')",
            arrayOf(dictId, term, term),
        )
    }

    @Test
    fun `returns only candidates present in an enabled dict`() {
        insert("enabledA", "背に腹は代えられない")
        insert("disabledX", "後の祭り")
        val hits = YomitanDataStore.batchTermsExistQuery(
            db,
            setOf("背に腹は代えられない", "後の祭り", "存在しない表現"),
            listOf("enabledA"),
        )
        assertEquals(setOf("背に腹は代えられない"), hits)
    }

    @Test
    fun `term in both enabled and disabled dicts still matches`() {
        insert("enabledA", "気が利く")
        insert("disabledX", "気が利く")
        val hits = YomitanDataStore.batchTermsExistQuery(db, setOf("気が利く"), listOf("enabledA"))
        assertEquals(setOf("気が利く"), hits)
    }

    @Test
    fun `empty allow-list matches nothing`() {
        insert("enabledA", "気が利く")
        assertTrue(
            YomitanDataStore.batchTermsExistQuery(db, setOf("気が利く"), emptyList()).isEmpty()
        )
    }

    @Test
    fun `candidate sets beyond one chunk are fully checked`() {
        // 1200 candidates spans three 500-chunks; hits land in each chunk.
        insert("enabledA", "term0000")
        insert("enabledA", "term0600")
        insert("enabledA", "term1199")
        val candidates = (0 until 1200).mapTo(linkedSetOf()) { "term%04d".format(it) }
        val hits = YomitanDataStore.batchTermsExistQuery(db, candidates, listOf("enabledA"))
        assertEquals(setOf("term0000", "term0600", "term1199"), hits)
    }

    @Test
    fun `allow-list size cannot push binds past the parameter cap`() {
        // 1500 dict ids would blow SQLite's 999-variable limit if they were
        // bound in SQL; the in-memory filter keeps binds at the chunk size.
        insert("dict1499", "気が利く")
        insert("dict0001", "後の祭り")
        val dictIds = (0 until 1500).map { "dict%04d".format(it) }
        val candidates = (0 until 1000).mapTo(linkedSetOf()) { "cand%04d".format(it) }
            .plus(setOf("気が利く", "後の祭り"))
        val hits = YomitanDataStore.batchTermsExistQuery(db, candidates, dictIds)
        assertEquals(setOf("気が利く", "後の祭り"), hits)
    }
}
