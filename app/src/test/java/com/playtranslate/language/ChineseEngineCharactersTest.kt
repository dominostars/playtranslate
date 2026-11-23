package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.HanziDetail
import com.playtranslate.model.HanziEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ChineseEngine.characterBreakdown], the Hanzi group of a word's page,
 * over a pack written in the test with the shipped schema: a character's
 * row holds every entry the pack has for it, the readings the word uses
 * first, the others as a lookup orders them (行 leads with xíng through the
 * app's own table, read from the real asset) and the surname last; one row
 * for each distinct ideograph the pack lists, in the word's order; a
 * Traditional reader's 里 without the entries of 裡 and 裏. No imported
 * dictionary is installed in the Robolectric app, so the meanings are the
 * pack's; what an imported one changes is pinned without a database in
 * ChineseEntryGroupsTest.
 *
 * Single test method on purpose, the constraint of
 * [ChineseDictionaryManagerTest]: the manager is a singleton bound to the
 * first Context that builds it, so fixture and manager share one method's
 * Robolectric app.
 */
@RunWith(RobolectricTestRunner::class)
class ChineseEngineCharactersTest {

    // Mutations: the row is the lookup's lead entry alone; the word's
    // reading is not handed to the row; a repeated character has two rows;
    // the Traditional engine reads the Simplified groups.
    @Test fun `a word's characters, each with every entry, the word's reading first`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        clearSingleton()
        writeFixture(ctx)
        val engine = ChineseEngine(ctx)

        // 沙: every entry, the surname last, each under its own pinyin.
        assertEquals(
            listOf(
                HanziDetail(
                    literal = '沙',
                    entries = listOf(
                        HanziEntry(listOf("shā"), listOf("granule", "sand", "CL:粒[lì]")),
                        HanziEntry(listOf("shà"), listOf("(dialect) to sift; to sieve")),
                        HanziEntry(listOf("Shā"), listOf("surname Sha")),
                    ),
                    isCommon = true, freqScore = 2, meaningsLang = "en", frequencies = emptyList<FrequencyTag>(),
                ),
            ),
            engine.characterBreakdown("沙", "shā"),
        )

        fun readings(word: String, reading: String?, with: ChineseEngine = engine) =
            runBlocking { with.characterBreakdown(word, reading) }
                .map { it.literal to (it as HanziDetail).entries.map { entry -> entry.readings.single() } }

        // 银行 yín háng: 行 leads with háng. With no reading, or one that is
        // not a syllable per character, it leads as a lookup of 行 does.
        assertEquals(listOf('银' to listOf("yín"), '行' to listOf("háng", "xíng", "héng")), readings("银行", "yín háng"))
        assertEquals(listOf('银' to listOf("yín"), '行' to listOf("xíng", "háng", "héng")), readings("银行", null))
        assertEquals(listOf('银' to listOf("yín"), '行' to listOf("xíng", "háng", "héng")), readings("银行", "yínháng"))
        assertEquals("xíng", engine.lookup("行", null)!!.entries.single().headwords.first().reading)
        // The syllable is the character's own, by its place in the word.
        assertEquals(listOf('行' to listOf("héng", "xíng", "háng"), '银' to listOf("yín")), readings("行银", "héng yín"))

        // One row for a repeated character, none for a character the pack
        // does not list or for text that is no ideograph, in the word's order.
        assertEquals(listOf('沙' to listOf("shā", "shà", "Shā")), readings("沙沙", "shā shā"))
        assertEquals(listOf('银' to listOf("yín")), readings("猫银", "māo yín"))
        assertEquals(emptyList<Pair<Char, List<String>>>(), readings("OK", null))
        assertEquals(listOf('银' to listOf("yín"), '沙' to listOf("shā", "shà", "Shā")), readings("银A沙", null))

        // A Traditional reader's 里 is 里 and the surname written 里; a
        // Simplified reader's is every 里, one lǐ entry and the surname.
        val hant = ChineseEngine(ctx, SourceLangId.ZH_HANT)
        val hantLi = hant.characterBreakdown("里", "lǐ").single() as HanziDetail
        assertEquals(
            listOf(
                HanziEntry(listOf("lǐ"), listOf("li, ancient measure of length, approx. 500 m", "neighborhood")),
                HanziEntry(listOf("Lǐ"), listOf("Li (surname)")),
            ),
            hantLi.entries,
        )
        // A Traditional character in a Simplified reader's text is the one
        // word written with it, under the form that reader sees.
        assertEquals(
            listOf(HanziEntry(listOf("lǐ"), listOf("lining", "interior", "inside", "internal", "also written 裏|里[lǐ]"))),
            (engine.characterBreakdown("裡", "lǐ").single() as HanziDetail).entries,
        )
        val hansLi = engine.characterBreakdown("里", "lǐ").single() as HanziDetail
        assertEquals(listOf(listOf("lǐ"), listOf("Lǐ")), hansLi.entries.map { it.readings })
        assertEquals(
            listOf(
                "lining", "interior", "inside", "internal", "also written 裏|里[lǐ]",
                "li, ancient measure of length, approx. 500 m", "neighborhood",
            ),
            hansLi.entries.first().meanings,
        )
    }

    private fun writeFixture(ctx: Context) {
        val dbFile = LanguagePackStore.dictDbFor(ctx, SourceLangId.ZH)
        dbFile.parentFile!!.mkdirs()
        if (dbFile.exists()) dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER NOT NULL DEFAULT 0, freq_score INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE headword (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL)")
            db.execSQL("CREATE TABLE reading (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, no_kanji INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE sense (entry_id INTEGER NOT NULL, position INTEGER NOT NULL, pos TEXT NOT NULL, glosses TEXT NOT NULL, misc TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE INDEX idx_headword_text ON headword(text)")
            fun line(id: Long, freq: Int, common: Boolean, simplified: String, traditional: String?, reading: String, glosses: List<String>) {
                db.execSQL("INSERT INTO entry VALUES ($id, ${if (common) 1 else 0}, $freq)")
                db.execSQL("INSERT INTO headword VALUES ($id, 0, '$simplified')")
                if (traditional != null) db.execSQL("INSERT INTO headword VALUES ($id, 1, '$traditional')")
                db.execSQL("INSERT INTO reading VALUES ($id, 0, '$reading', 0)")
                db.execSQL("INSERT INTO sense VALUES ($id, 0, '', '${glosses.joinToString("\t")}', '')")
            }
            // 沙 as the pack has it, the surname's line first; common only
            // in its last line, which makes the character common.
            line(1, 47, false, "沙", null, "Sha1", listOf("surname Sha"))
            line(2, 47, false, "沙", null, "sha1", listOf("granule", "sand", "CL:粒[li4]"))
            line(3, 47, true, "沙", null, "sha4", listOf("(dialect) to sift; to sieve"))
            // 行 in the pack's line order, alphabetical pinyin.
            line(4, 70, false, "行", null, "hang2", listOf("row", "line", "profession"))
            line(5, 70, false, "行", null, "heng2", listOf("used in 道行[dao4 heng2]"))
            line(6, 70, false, "行", null, "xing2", listOf("to walk", "to go"))
            line(7, 55, false, "银", "銀", "yin2", listOf("silver", "money"))
            line(8, 71, false, "里", "裏", "li3", listOf("variant of 裡|里[li3]"))
            line(9, 71, false, "里", "裡", "li3", listOf("lining", "interior", "inside", "internal", "also written 裏|里[li3]"))
            line(10, 71, false, "里", null, "Li3", listOf("Li (surname)"))
            line(11, 71, false, "里", null, "li3", listOf("li, ancient measure of length, approx. 500 m", "neighborhood"))
        }
    }

    /** Drop the singleton so [ChineseDictionaryManager.get] rebinds to this
     *  method's Robolectric context and the fixture just written. */
    private fun clearSingleton() {
        val field = ChineseDictionaryManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }
}
