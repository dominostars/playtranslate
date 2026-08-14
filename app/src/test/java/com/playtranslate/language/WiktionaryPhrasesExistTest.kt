package com.playtranslate.language

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins [WiktionaryDictionaryManager.phrasesExistQuery], the batched
 * membership gate behind [LatinEngine.longestPhraseAt]. The tier contract
 * mirrors [WiktionaryDictionaryManager.lookup]'s canonical surface query:
 * positions 0-2 (lemma / stem / form_of alias) gate a phrase — so inflected
 * surfaces like "gave up" pass through their alias rows — while position-3
 * fold rows stay reachable only through the Arabic folded fallback and must
 * NOT gate.
 */
@RunWith(RobolectricTestRunner::class)
class WiktionaryPhrasesExistTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixtureDb(): SQLiteDatabase {
        val f = File(tmp.root, "phrases.sqlite")
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL(
            "CREATE TABLE headword (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL)"
        )
        db.execSQL("INSERT INTO headword VALUES (1, 0, 'give up')")     // lemma
        db.execSQL("INSERT INTO headword VALUES (1, 2, 'gave up')")     // form_of alias
        db.execSQL("INSERT INTO headword VALUES (2, 0, 'a great deal')")
        db.execSQL("INSERT INTO headword VALUES (3, 3, 'fold only')")   // fold tier — must not gate
        db.execSQL("INSERT INTO headword VALUES (4, 0, 'single')")
        return db
    }

    @Test fun `matches lemma and alias rows, ignores fold rows`() {
        fixtureDb().use { db ->
            assertEquals(
                setOf("give up", "gave up", "a great deal"),
                WiktionaryDictionaryManager.phrasesExistQuery(
                    db,
                    listOf("give up", "gave up", "a great deal", "fold only", "not a phrase"),
                ),
            )
        }
    }

    @Test fun `empty input yields empty output`() {
        fixtureDb().use { db ->
            assertEquals(
                emptySet<String>(),
                WiktionaryDictionaryManager.phrasesExistQuery(db, emptyList()),
            )
        }
    }

    @Test fun `chunking survives more keys than one IN clause chunk`() {
        fixtureDb().use { db ->
            val filler = (0 until 1200).map { "filler $it" }
            assertEquals(
                setOf("give up"),
                WiktionaryDictionaryManager.phrasesExistQuery(db, filler + "give up"),
            )
        }
    }
}
