package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [WiktionaryDictionaryManager.lookup]'s entry ordering: the match
 * POSITION tier dominates frequency. A surface that is both its own lemma
 * (position 0) and a `form_of` alias (position 2) of a more frequent entry
 * must surface its OWN entry first — the alias parent follows, with its
 * `[inflected]` marker.
 *
 * The fixture replays the shipped en pack's "hinder" shape, where the old
 * `freq_score DESC, pos ASC` ordering surfaced the wrong word: "hinder" is
 * a lemma (freq 27) AND an archaic alias of "hind" (freq 29), so every
 * lookup of "hinder" displayed "hind". Same failure across packs: "running"
 * showed "run", German "es" showed "das", Spanish "las" showed "la".
 *
 * Single test method on purpose — same singleton/context constraint as
 * [ArabicFoldLookupTest]: the manager binds to the first Context that
 * builds it, so fixture and manager must share one method's Robolectric app.
 */
@RunWith(RobolectricTestRunner::class)
class WiktionaryLookupRankingTest {

    @Test fun `own lemma outranks a more frequent alias parent`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        clearManagerCache()

        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.EN)
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE entry (id INTEGER PRIMARY KEY, " +
                    "is_common INTEGER NOT NULL DEFAULT 0, freq_score INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE headword (entry_id INTEGER NOT NULL, " +
                    "position INTEGER NOT NULL, text TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE sense (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, " +
                    "pos TEXT NOT NULL, glosses TEXT NOT NULL, misc TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL("CREATE INDEX idx_headword_text ON headword(text)")
            // Entry 1: "hind" — MORE frequent, and "hinder" is its alias.
            db.execSQL("INSERT INTO entry VALUES (1, 0, 29)")
            db.execSQL("INSERT INTO headword VALUES (1, 0, 'hind')")
            db.execSQL("INSERT INTO headword VALUES (1, 2, 'hinder')")
            db.execSQL("INSERT INTO sense VALUES (1, 0, 'adj', 'located at the rear', '')")
            // Entry 2: "hinder" — the queried word's OWN lemma, less frequent.
            db.execSQL("INSERT INTO entry VALUES (2, 0, 27)")
            db.execSQL("INSERT INTO headword VALUES (2, 0, 'hinder')")
            db.execSQL("INSERT INTO sense VALUES (2, 0, 'verb', 'to make difficult', '')")
        }
        val manager = WiktionaryDictionaryManager.get(ctx, SourceLangId.EN)

        val response = manager.lookup(surface = "hinder", stemmed = null)
        assertNotNull(response)
        val entries = response!!.entries
        assertEquals(2, entries.size)

        // The word's own lemma entry leads, unmarked.
        assertEquals("hinder", entries[0].headwords.first().written)
        assertEquals(listOf("verb"), entries[0].senses.first().partsOfSpeech)

        // The more frequent alias parent follows, carrying [inflected].
        assertEquals("hind", entries[1].headwords.first().written)
        assertEquals("[inflected]", entries[1].senses.first().partsOfSpeech.firstOrNull())
    }

    /** Drop any cached singleton so [WiktionaryDictionaryManager.get] rebinds to
     *  this test method's Robolectric context (and our freshly-written fixture). */
    private fun clearManagerCache() {
        val field = WiktionaryDictionaryManager::class.java.getDeclaredField("instances")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
