package com.playtranslate.language

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Unit tests for [LanguagePackStore.isJmdictSchemaCurrent].
 *
 * Runs under Robolectric because [SQLiteDatabase] is Android-only.
 * Guards the upgrade-gating schema check: if any of the runtime-queried
 * columns disappear from a legacy JMdict DB, [LanguagePackStore.isInstalled]
 * must route the user through onboarding so they download a fresh pack
 * instead of landing on a stale DB that [com.playtranslate.dictionary.DictionaryManager]
 * will later delete anyway. A silent regression (e.g. someone adds a new
 * schema column and forgets to validate it here) leads to upgraders on
 * stale builds failing lookups with no onboarding recovery.
 */
@RunWith(RobolectricTestRunner::class)
class IsJmdictSchemaCurrentTest {

    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = createTempDirectory("jmdict-schema").toFile()
    }

    @After fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun createDb(name: String, setup: (SQLiteDatabase) -> Unit): File {
        val file = File(tmp, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use(setup)
        return file
    }

    private fun currentSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entry (id INTEGER, freq_score INTEGER)")
        db.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
        db.execSQL("CREATE TABLE sense (id INTEGER, misc TEXT)")
        db.execSQL("CREATE TABLE kanjidic (literal TEXT)")
    }

    @Test fun `returns true when all validated columns and tables are present`() {
        val db = createDb("current.sqlite") { currentSchema(it) }
        assertTrue(LanguagePackStore.isJmdictSchemaCurrent(db))
    }

    @Test fun `returns false when entry freq_score column is missing`() {
        val db = createDb("no-freq-score.sqlite") { d ->
            d.execSQL("CREATE TABLE entry (id INTEGER)")
            d.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
            d.execSQL("CREATE TABLE sense (id INTEGER, misc TEXT)")
            d.execSQL("CREATE TABLE kanjidic (literal TEXT)")
        }
        assertFalse(LanguagePackStore.isJmdictSchemaCurrent(db))
    }

    @Test fun `returns false when headword table is missing (pre-rename DB)`() {
        // This is the regression scenario: a legacy JMdict pack still has
        // the old `kanji` table but not `headword`. The check must fail so
        // isInstalled deletes the stale file and routes the user through
        // a fresh download instead of landing on a DB whose runtime queries
        // will blow up with "no such table: headword".
        val db = createDb("legacy-kanji-only.sqlite") { d ->
            d.execSQL("CREATE TABLE entry (id INTEGER, freq_score INTEGER)")
            d.execSQL("CREATE TABLE kanji (entry_id INTEGER, position INTEGER, text TEXT)")
            d.execSQL("CREATE TABLE sense (id INTEGER, misc TEXT)")
            d.execSQL("CREATE TABLE kanjidic (literal TEXT)")
        }
        assertFalse(LanguagePackStore.isJmdictSchemaCurrent(db))
    }

    @Test fun `returns false when sense misc column is missing`() {
        val db = createDb("no-misc.sqlite") { d ->
            d.execSQL("CREATE TABLE entry (id INTEGER, freq_score INTEGER)")
            d.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
            d.execSQL("CREATE TABLE sense (id INTEGER)")
            d.execSQL("CREATE TABLE kanjidic (literal TEXT)")
        }
        assertFalse(LanguagePackStore.isJmdictSchemaCurrent(db))
    }

    @Test fun `returns false when kanjidic table is missing`() {
        val db = createDb("no-kanjidic.sqlite") { d ->
            d.execSQL("CREATE TABLE entry (id INTEGER, freq_score INTEGER)")
            d.execSQL("CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT)")
            d.execSQL("CREATE TABLE sense (id INTEGER, misc TEXT)")
        }
        assertFalse(LanguagePackStore.isJmdictSchemaCurrent(db))
    }

    @Test fun `returns false for a non-existent file`() {
        val ghost = File(tmp, "does-not-exist.sqlite")
        assertFalse(ghost.exists())
        assertFalse(LanguagePackStore.isJmdictSchemaCurrent(ghost))
    }
}
