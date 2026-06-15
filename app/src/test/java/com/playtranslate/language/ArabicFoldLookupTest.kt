package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end guard for the Arabic casual/variant-spelling fold fallback in
 * [WiktionaryDictionaryManager.lookup]. Builds a fixture pack with a canonical
 * lemma (position 0) and its folded variant (position 3), then drives the real
 * lookup cascade and pins the three properties the fold design depends on:
 *
 *  1. The position ceiling — the canonical surface query (`position <= 2`) does
 *     NOT see the position-3 fold row, so with no fold key the casual spelling
 *     misses (no collision noise on canonical lookups).
 *  2. The fold fallback resolves the casual spelling to the lemma, tags it
 *     `[variant]`, and keeps the DISPLAYED headword the un-folded lemma —
 *     folding is a lookup key only, never a display form.
 *  3. The canonical spelling still hits directly with no marker.
 *
 * Single test method on purpose: [WiktionaryDictionaryManager] is a
 * process-static singleton that binds to the first [Context] that builds it,
 * while Robolectric gives each test method a fresh app/filesystem — so the
 * fixture and the manager must share one method's context. The reflective cache
 * clear drops any instance an earlier test class left bound to a stale context.
 */
@RunWith(RobolectricTestRunner::class)
class ArabicFoldLookupTest {

    @Test fun `arabic fold fallback resolves casual spellings without corrupting display`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        clearManagerCache()

        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.AR)
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
            // أنا "I" — canonical lemma (position 0, hamza preserved = display
            // form) + its folded variant انا (position 3, hamza dropped = key).
            db.execSQL("INSERT INTO entry VALUES (1, 0, 50)")
            db.execSQL("INSERT INTO headword VALUES (1, 0, ?)", arrayOf<Any>("أنا"))
            db.execSQL("INSERT INTO headword VALUES (1, 3, ?)", arrayOf<Any>("انا"))
            db.execSQL("INSERT INTO sense VALUES (1, 0, 'pron', ?, '')", arrayOf<Any>("I\tme"))
            // Collision fixture for the fold-tier isolation check:
            //  - entry 2 أنى "whence" → its folded key اني lives at position 3
            //    (the INTENDED fold target).
            //  - entry 3 اني — an UNRELATED lemma whose canonical (position-0)
            //    spelling coincidentally equals that same folded key اني, with NO
            //    position-3 row, and a HIGHER freq_score so it would sort first.
            // A fold lookup of اني must return only entry 2, never entry 3.
            db.execSQL("INSERT INTO entry VALUES (2, 0, 40)")
            db.execSQL("INSERT INTO headword VALUES (2, 0, ?)", arrayOf<Any>("أنى"))
            db.execSQL("INSERT INTO headword VALUES (2, 3, ?)", arrayOf<Any>("اني"))
            db.execSQL("INSERT INTO sense VALUES (2, 0, 'adv', ?, '')", arrayOf<Any>("whence"))
            db.execSQL("INSERT INTO entry VALUES (3, 0, 90)")
            db.execSQL("INSERT INTO headword VALUES (3, 0, ?)", arrayOf<Any>("اني"))
            db.execSQL("INSERT INTO sense VALUES (3, 0, 'noun', ?, '')", arrayOf<Any>("DECOY"))
        }
        val manager = WiktionaryDictionaryManager.get(ctx, SourceLangId.AR)

        // 1. Position ceiling: "انا" is only a position-3 fold row; the canonical
        //    surface query (position<=2) excludes it, so no fold key → miss.
        assertNull(manager.lookup(surface = "انا", stemmed = null, folded = null))

        // 2. Fold fallback resolves the casual spelling, tags [variant], and the
        //    display headword stays the un-folded lemma.
        val variant = manager.lookup(surface = "انا", stemmed = null, folded = "انا")
        assertNotNull(variant)
        val ve = variant!!.entries.single()
        assertEquals("أنا", ve.headwords.first().written)
        assertEquals("[variant]", ve.senses.first().partsOfSpeech.firstOrNull())

        // 3. Canonical spelling hits directly via the surface query, no marker.
        val canon = manager.lookup(surface = "أنا", stemmed = null, folded = "انا")
        assertNotNull(canon)
        val ce = canon!!.entries.single()
        assertEquals("أنا", ce.headwords.first().written)
        assertTrue(
            "canonical hit must not be tagged [variant]",
            ce.senses.first().partsOfSpeech.firstOrNull() != "[variant]",
        )

        // 4. Fold-tier isolation: a fold key (اني) that coincides with an
        //    unrelated canonical lemma (entry 3, higher freq) must resolve ONLY
        //    via the position-3 row (entry 2 أنى) — never surface the position-0
        //    decoy. "أني" misses canonically; fold("أني") = اني.
        val foldKey = ArabicFold.fold("أني")
        assertEquals("اني", foldKey)
        val isolated = manager.lookup(surface = "أني", stemmed = null, folded = foldKey)
        assertNotNull(isolated)
        val written = isolated!!.entries.map { it.headwords.first().written }
        assertTrue("intended position-3 target أنى must be returned", written.contains("أنى"))
        assertFalse(
            "the position-0 decoy اني must NOT be surfaced by the fold fallback",
            written.contains("اني"),
        )
    }

    /** Drop any cached singleton so [WiktionaryDictionaryManager.get] rebinds to
     *  this test method's Robolectric context (and our freshly-written fixture). */
    private fun clearManagerCache() {
        val field = WiktionaryDictionaryManager::class.java.getDeclaredField("instances")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
