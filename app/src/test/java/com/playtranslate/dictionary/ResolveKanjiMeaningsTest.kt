package com.playtranslate.dictionary

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Tests for [DictionaryManager.Companion.resolveKanjiMeanings] — the
 * per-language fallback used when the user's target language doesn't match
 * the meanings KANJIDIC2 ships natively for a given kanji.
 *
 * Runs under Robolectric because [SQLiteDatabase] is Android-only. The
 * test fixture builds a tiny `kanji_meaning` table with a representative
 * mix: one kanji that has en/fr/es coverage, one with en-only, and one
 * that has *no* English row (to pin the empty-fallback behavior).
 */
@RunWith(RobolectricTestRunner::class)
class ResolveKanjiMeaningsTest {

    private lateinit var tmp: File
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        tmp = createTempDirectory("kanji-meaning").toFile()
        db = SQLiteDatabase.openOrCreateDatabase(File(tmp, "dict.sqlite"), null)
        db.execSQL(
            """
            CREATE TABLE kanji_meaning (
                literal TEXT NOT NULL,
                lang    TEXT NOT NULL,
                meanings TEXT NOT NULL,
                PRIMARY KEY (literal, lang)
            )
            """.trimIndent()
        )
        // 水 — full coverage
        insert("水", "en", "water", "fluid", "liquid")
        insert("水", "fr", "eau", "liquide")
        insert("水", "es", "agua", "líquido")
        // 火 — English only (KANJIDIC2 has thousands of these)
        insert("火", "en", "fire", "flame")
        // 龦 — non-English row only, no English fallback row at all.
        // Models the rare case of a CJK extension character without
        // English coverage; the function must return an empty list and
        // the resolved lang must still be "en" so the caller's
        // .isEmpty() check kicks the entry out.
        insert("龦", "fr", "chevauchement")
    }

    @After fun tearDown() {
        db.close()
        tmp.deleteRecursively()
    }

    private fun insert(literal: String, lang: String, vararg meanings: String) {
        db.execSQL(
            "INSERT INTO kanji_meaning VALUES (?, ?, ?)",
            arrayOf(literal, lang, meanings.joinToString("\t")),
        )
    }

    @Test fun `target language hit returns native meanings tagged with that lang`() {
        val (meanings, lang) = DictionaryManager.resolveKanjiMeanings(db, '水', "fr")
        assertEquals(listOf("eau", "liquide"), meanings)
        assertEquals("fr", lang)
    }

    @Test fun `falls back to English when target language has no row`() {
        // 火 has no German row. The function must downgrade the request
        // to English so the caller can still show *something*, and tag
        // the result so the UI knows to MT it on the way to display.
        val (meanings, lang) = DictionaryManager.resolveKanjiMeanings(db, '火', "de")
        assertEquals(listOf("fire", "flame"), meanings)
        assertEquals("en", lang)
    }

    @Test fun `english target skips the non-en query and reads en directly`() {
        // Pin that targetLang == "en" doesn't accidentally try a "kanji_meaning
        // WHERE lang='en'" lookup twice. The behavior is observable through
        // the returned lang code (always "en") and meanings list.
        val (meanings, lang) = DictionaryManager.resolveKanjiMeanings(db, '水', "en")
        assertEquals(listOf("water", "fluid", "liquid"), meanings)
        assertEquals("en", lang)
    }

    @Test fun `unknown literal returns empty list with en fallback tag`() {
        val (meanings, lang) = DictionaryManager.resolveKanjiMeanings(db, '不', "en")
        assertTrue(meanings.isEmpty())
        assertEquals("en", lang)
    }

    @Test fun `unknown literal still returns en fallback tag for non-en target`() {
        val (meanings, lang) = DictionaryManager.resolveKanjiMeanings(db, '不', "fr")
        assertTrue(meanings.isEmpty())
        assertEquals("en", lang)
    }

    @Test fun `non-en row exists but no en row falls through to empty en`() {
        // 龦 has fr but no en. Ask for fr — we get fr. Ask for de — we
        // get an empty list tagged "en" because the en fallback row is
        // missing too. lookupKanji's caller drops these via meanings.isEmpty().
        val (frMeanings, frLang) = DictionaryManager.resolveKanjiMeanings(db, '龦', "fr")
        assertEquals(listOf("chevauchement"), frMeanings)
        assertEquals("fr", frLang)

        val (deMeanings, deLang) = DictionaryManager.resolveKanjiMeanings(db, '龦', "de")
        assertTrue(deMeanings.isEmpty())
        assertEquals("en", deLang)
    }

    @Test fun `tab separator splits multi-meaning rows correctly`() {
        // The build script joins each language's meanings with \t. A
        // change to that delimiter would break this test before it could
        // ship a corrupt pack to users.
        val (meanings, _) = DictionaryManager.resolveKanjiMeanings(db, '水', "es")
        assertEquals(listOf("agua", "líquido"), meanings)
    }
}
