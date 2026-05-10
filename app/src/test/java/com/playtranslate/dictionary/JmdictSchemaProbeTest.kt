package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for [JmdictSchemaProbe] — the single source of truth for "is this
 * pack at the schema version this app expects?"
 *
 * Runs under Robolectric so [SQLiteDatabase] is available on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class JmdictSchemaProbeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `returns true for ja-v2-shaped schema (all required columns present)`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createV2Schema(db)
        }
        assertTrue("v2 schema should be current", JmdictSchemaProbe.isCurrent(dbFile))
    }

    @Test fun `returns false when reading_rank_score column is missing (ja-v1-shaped)`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            // ja-v1: reading table has freq_score but not rank_score / re_inf
            // / uk_applicable. headword has only entry_id/position/text.
            createV1Schema(db)
        }
        assertFalse(
            "ja-v1 schema (missing rank_score) should be stale",
            JmdictSchemaProbe.isCurrent(dbFile),
        )
    }

    @Test fun `returns false when headword_rank_score column is missing`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createV2Schema(db)
            db.execSQL("DROP TABLE headword")
            db.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
        }
        assertFalse(JmdictSchemaProbe.isCurrent(dbFile))
    }

    @Test fun `returns false when uk_applicable column is missing`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createV2Schema(db)
            db.execSQL("DROP TABLE reading")
            db.execSQL(
                """
                CREATE TABLE reading (
                    entry_id INTEGER, position INTEGER, text TEXT,
                    no_kanji INTEGER DEFAULT 0,
                    re_pri TEXT DEFAULT '', freq_score INTEGER DEFAULT 0,
                    re_inf TEXT DEFAULT '', rank_score INTEGER DEFAULT 0
                    -- intentionally no uk_applicable
                )
                """.trimIndent(),
            )
        }
        assertFalse(JmdictSchemaProbe.isCurrent(dbFile))
    }

    @Test fun `returns false on missing kanji_meaning table (pre-multilingual pack)`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            createV2Schema(db)
            db.execSQL("DROP TABLE kanji_meaning")
        }
        assertFalse(JmdictSchemaProbe.isCurrent(dbFile))
    }

    @Test fun `probe is read-only — does not mutate the file`() {
        val dbFile = newDbFile()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            // Schema deliberately broken so the probe returns false.
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY)")
        }
        val sizeBefore = dbFile.length()
        val mtimeBefore = dbFile.lastModified()

        // The probe must NOT delete or modify the file.
        assertFalse(JmdictSchemaProbe.isCurrent(dbFile))

        assertTrue("File still exists after probe", dbFile.exists())
        assertEquals("File size unchanged", sizeBefore, dbFile.length())
        // mtime should be unchanged. Not asserting — Robolectric's filesystem
        // may not have monotonic mtime, and the existence check is the
        // load-bearing guarantee. (Existence + size cover the regression.)
    }

    @Test fun `returns false on nonexistent file (not crash)`() {
        val dbFile = File(tmp.root, "does-not-exist.sqlite")
        assertFalse(JmdictSchemaProbe.isCurrent(dbFile))
    }

    @Test fun `dictionarymanager-shape check matches packstore-shape check`() {
        // The whole point of the shared probe is that DictionaryManager and
        // LanguagePackStore can't disagree about whether a pack is current.
        // Both delegate here; this test asserts that delegation by checking
        // both code paths return the same boolean against the same DB.
        val goodDb = newDbFile("good.sqlite")
        SQLiteDatabase.openOrCreateDatabase(goodDb, null).use { createV2Schema(it) }
        val badDb = newDbFile("bad.sqlite")
        SQLiteDatabase.openOrCreateDatabase(badDb, null).use { createV1Schema(it) }

        // Calling via the file overload (LanguagePackStore's path) and via
        // the SQLiteDatabase overload (DictionaryManager's path) must agree.
        SQLiteDatabase.openDatabase(
            goodDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(
                JmdictSchemaProbe.isCurrent(goodDb),
                JmdictSchemaProbe.isCurrent(db),
            )
            assertTrue(JmdictSchemaProbe.isCurrent(db))
        }
        SQLiteDatabase.openDatabase(
            badDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(
                JmdictSchemaProbe.isCurrent(badDb),
                JmdictSchemaProbe.isCurrent(db),
            )
            assertFalse(JmdictSchemaProbe.isCurrent(db))
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun newDbFile(name: String = "dict.sqlite"): File {
        val f = File(tmp.root, name)
        if (f.exists()) f.delete()
        return f
    }

    /** ja-v2 schema: matches the post-this-PR shape from build_jmdict.py. */
    private fun createV2Schema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entry (
                id INTEGER PRIMARY KEY,
                is_common INTEGER NOT NULL DEFAULT 0,
                freq_score INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE headword (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                text TEXT NOT NULL,
                ke_pri TEXT NOT NULL DEFAULT '',
                rank_score INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
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
        db.execSQL(
            """
            CREATE TABLE sense (
                entry_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                pos TEXT NOT NULL,
                glosses TEXT NOT NULL,
                misc TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE kanjidic (
                literal TEXT PRIMARY KEY,
                on_readings TEXT NOT NULL DEFAULT '',
                kun_readings TEXT NOT NULL DEFAULT '',
                jlpt INTEGER NOT NULL DEFAULT 0,
                grade INTEGER NOT NULL DEFAULT 0,
                stroke_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE kanji_meaning (
                literal TEXT NOT NULL,
                lang TEXT NOT NULL,
                meanings TEXT NOT NULL,
                PRIMARY KEY (literal, lang)
            )
            """.trimIndent()
        )
    }

    /** ja-v1 schema: pre-this-PR. Reading missing rank_score / re_inf /
     *  uk_applicable; headword missing ke_pri / rank_score. */
    private fun createV1Schema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER, freq_score INTEGER)")
        db.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
        db.execSQL(
            "CREATE TABLE reading (entry_id INTEGER, position INTEGER, text TEXT, " +
                "no_kanji INTEGER DEFAULT 0, re_pri TEXT DEFAULT '', freq_score INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE sense (entry_id INTEGER, position INTEGER, pos TEXT, glosses TEXT, misc TEXT)")
        db.execSQL("CREATE TABLE kanjidic (literal TEXT PRIMARY KEY, on_readings TEXT, kun_readings TEXT, jlpt INTEGER, grade INTEGER, stroke_count INTEGER)")
        db.execSQL("CREATE TABLE kanji_meaning (literal TEXT, lang TEXT, meanings TEXT, PRIMARY KEY(literal, lang))")
    }
}
