package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.KanjiDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [JapaneseEngine.characterBreakdown], the Kanji group of a word's page,
 * over a pack written in the test with the shipped schema: one row for each
 * distinct kanji of the word the pack has a meaning for, in the word's
 * order, kana left out, each with KANJIDIC2's readings and numbers and its
 * meanings in the language asked for where the pack has them. The word's
 * reading changes nothing here: a kanji is one entry with all its readings.
 * No imported dictionary is installed in the Robolectric app.
 *
 * Single test method on purpose: the manager is a singleton bound to the
 * first Context that builds it, so fixture and manager share one method's
 * Robolectric app.
 */
@RunWith(RobolectricTestRunner::class)
class JapaneseEngineCharactersTest {

    // Mutations: the engine answers no row; a repeated kanji has two rows;
    // the language asked for is not handed on.
    @Test fun `a word's kanji, one row each in the word's order, in the language asked for`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        DictionaryManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        writeFixture(ctx)
        val engine = JapaneseEngine(ctx)

        val gaku = KanjiDetail(
            literal = '学', meanings = listOf("study", "learning"), meaningsLang = "en",
            onReadings = listOf("ガク"), kunReadings = listOf("まな.ぶ"), jlpt = 5, grade = 1, strokeCount = 8,
        )
        val sei = KanjiDetail(
            literal = '生', meanings = listOf("life", "birth"), meaningsLang = "en",
            onReadings = listOf("セイ", "ショウ"), kunReadings = listOf("い.きる"), jlpt = 5, grade = 1, strokeCount = 5,
        )
        assertEquals(listOf(gaku, sei), engine.characterBreakdown("学生", "がくせい"))
        // The reading is no part of a kanji's row.
        assertEquals(listOf(gaku, sei), engine.characterBreakdown("学生", null))
        // The word's order, a repeated kanji once, kana and a kanji the
        // pack has no meaning for left out.
        assertEquals(listOf('生', '学'), engine.characterBreakdown("生きる学生の猫", null).map { it.literal })
        assertEquals(emptyList<Char>(), engine.characterBreakdown("かもしれない", null).map { it.literal })
        // The language asked for where the pack has it, English where not.
        val french = engine.characterBreakdown("学生", null, "fr").map { it as KanjiDetail }
        assertEquals(listOf(listOf("étude") to "fr", listOf("life", "birth") to "en"), french.map { it.meanings to it.meaningsLang })
    }

    private fun writeFixture(ctx: Context) {
        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.JA)
        dbFile.parentFile!!.mkdirs()
        if (dbFile.exists()) dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER NOT NULL DEFAULT 0, freq_score INTEGER NOT NULL DEFAULT 0)")
            db.execSQL(
                "CREATE TABLE headword (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, " +
                    "ke_pri TEXT NOT NULL DEFAULT '', rank_score INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE reading (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, " +
                    "no_kanji INTEGER NOT NULL DEFAULT 0, re_pri TEXT NOT NULL DEFAULT '', freq_score INTEGER NOT NULL DEFAULT 0, " +
                    "re_inf TEXT NOT NULL DEFAULT '', rank_score INTEGER NOT NULL DEFAULT 0, uk_applicable INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE sense (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, pos TEXT NOT NULL, glosses TEXT NOT NULL, misc TEXT NOT NULL DEFAULT '')")
            db.execSQL(
                "CREATE TABLE kanjidic (literal TEXT PRIMARY KEY, on_readings TEXT NOT NULL DEFAULT '', kun_readings TEXT NOT NULL DEFAULT '', " +
                    "jlpt INTEGER NOT NULL DEFAULT 0, grade INTEGER NOT NULL DEFAULT 0, stroke_count INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE kanji_meaning (literal TEXT NOT NULL, lang TEXT NOT NULL, meanings TEXT NOT NULL, PRIMARY KEY (literal, lang))")
            db.execSQL("INSERT INTO kanjidic VALUES ('学', 'ガク', 'まな.ぶ', 5, 1, 8)")
            db.execSQL("INSERT INTO kanjidic VALUES ('生', 'セイ,ショウ', 'い.きる', 5, 1, 5)")
            // 猫 has readings and no meaning in any language: no row.
            db.execSQL("INSERT INTO kanjidic VALUES ('猫', 'ビョウ', 'ねこ', 2, 8, 11)")
            db.execSQL("INSERT INTO kanji_meaning VALUES ('学', 'en', 'study\tlearning')")
            db.execSQL("INSERT INTO kanji_meaning VALUES ('学', 'fr', 'étude')")
            db.execSQL("INSERT INTO kanji_meaning VALUES ('生', 'en', 'life\tbirth')")
        }
    }
}
