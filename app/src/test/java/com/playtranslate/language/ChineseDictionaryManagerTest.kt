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
 * [ChineseDictionaryManager] over a pack written in the test with the
 * shipped schema (`scripts/build_zh_dict.py`): a lookup hands out one entry
 * per reading group with the group rule's tie-break, a reading hint picks a
 * group and says so, the other groups are the alternatives, the heteronym
 * candidates follow the lookup's order, and the headword probe answers the
 * narrowing. Lines mirror the shipped pack's 包, 里, 东西, 放 and 在.
 *
 * Single test method on purpose, the constraint of
 * [WiktionaryLookupRankingTest]: the manager is a singleton bound to the
 * first Context that builds it, so fixture and manager share one method's
 * Robolectric app.
 */
@RunWith(RobolectricTestRunner::class)
class ChineseDictionaryManagerTest {

    @Test fun `one entry per reading group, hints, alternatives, candidates and the headword probe`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        clearSingleton()
        writeFixture(ctx)
        val dict = ChineseDictionaryManager.get(ctx)

        // A Simplified reader's 里: one lǐ entry, a sense per defining line,
        // the cross-reference line dropped, the surname never leading.
        val li = dict.lookup("里")!!.entries.single()
        assertEquals("lǐ", li.headwords.first().reading)
        assertEquals(listOf("里", "裏", "裡"), li.headwords.map { it.written })
        assertEquals(listOf("lining", "li, ancient measure of length, approx. 500 m"), li.senses.map { it.targetDefinitions.first() })
        assertEquals("also written 裏|里[lǐ]", li.senses[0].targetDefinitions.last())
        assertEquals(4L, li.packId)
        assertEquals("里", li.slug)

        // A hint picks the surname group and says so; a hint for the default
        // group, or one no group matches, is not a pick.
        val surname = dict.lookupGroup("里", preferTraditional = false, reading = "Lǐ")!!
        assertTrue(surname.hinted)
        assertEquals(listOf("Li (surname)"), surname.response.entries.single().senses.single().targetDefinitions)
        assertEquals(5L, surname.response.entries.single().packId)
        assertFalse(dict.lookupGroup("里", preferTraditional = false, reading = "lǐ")!!.hinted)
        val unmatched = dict.lookupGroup("里", preferTraditional = false, reading = "zzz")!!
        assertFalse(unmatched.hinted)
        assertEquals(4L, unmatched.response.entries.single().packId)

        // The common word leads the proper noun although its line comes second.
        assertEquals("bāo", dict.lookup("包")!!.entries.single().headwords.first().reading)
        assertEquals("to cover", dict.lookup("包")!!.entries.single().senses.single().targetDefinitions.first())

        // Alternatives: every group in lead order; the engine drops the primary's.
        assertEquals(listOf("lǐ" to 4L, "Lǐ" to 5L), dict.alternativeGroups("里", preferTraditional = false).map { it.reading to it.packId })

        // Heteronym candidates: lead order, proper nouns left out.
        assertEquals(listOf("lǐ"), dict.candidateReadings("里"))
        assertEquals(listOf("bāo"), dict.candidateReadings("包"))
        assertEquals(listOf("dōng xi", "dōng xī"), dict.candidateReadings("东西"))
        assertEquals("dōng xi", dict.lookup("东西")!!.entries.single().headwords.first().reading)
        val eastWest = dict.lookupGroup("东西", preferTraditional = false, reading = "dōng xī")!!
        assertTrue(eastWest.hinted)
        assertEquals("east and west", eastWest.response.entries.single().senses.single().targetDefinitions.single())

        // A Traditional reader: the tapped form leads, 裡 and 裏 are their own
        // groups, and 裡 looked up on its own is one group.
        val hant = dict.lookup("里", preferTraditional = true)!!.entries.single()
        assertEquals(6L, hant.packId)
        assertEquals(listOf("里"), hant.headwords.map { it.written })
        assertEquals(listOf("里", "裡", "裏", "里"), dict.alternativeGroups("里", preferTraditional = true).map { it.displayedForm })
        val inside = dict.lookup("裡", preferTraditional = true)!!.entries.single()
        assertEquals(listOf("裡", "里"), inside.headwords.map { it.written })
        assertEquals("lining", inside.senses.single().targetDefinitions.first())
        assertEquals(listOf("lǐ"), dict.candidateReadings("里", preferTraditional = true))

        // The headword probe: either script, a word the pack lacks is absent.
        assertEquals(setOf("放", "在", "东西", "裡"), dict.existingHeadwords(listOf("放", "在", "放在", "东西", "裡", "包里")))
        assertEquals(emptySet<String>(), dict.existingHeadwords(emptyList()))
        assertNotNull(dict.lookup("放"))
        assertNull(dict.lookup("放在"))

        // The pack closed under the manager (an eviction while a capture is
        // still being read): every query answers "nothing" and none throws.
        closeHandleUnder(dict)
        assertNull(dict.lookup("里"))
        assertNull(dict.lookupGroup("里", preferTraditional = false, reading = "Lǐ"))
        assertEquals(emptyList<EntryGroup>(), dict.alternativeGroups("里", preferTraditional = false))
        assertEquals(emptyList<String>(), dict.candidateReadings("里"))
        assertNull(dict.existingHeadwords(listOf("放")))
        assertEquals(emptyList<String>(), dict.searchPrefix("里"))
    }

    /** Close the SQLite handle the manager holds, as a pack eviction does,
     *  without telling the manager, so its next query meets a closed handle. */
    private fun closeHandleUnder(dict: ChineseDictionaryManager) {
        val field = ChineseDictionaryManager::class.java.getDeclaredField("db")
        field.isAccessible = true
        (field.get(dict) as SQLiteDatabase).close()
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
            fun line(id: Long, freq: Int, simplified: String, traditional: String?, reading: String, glosses: List<String>) {
                db.execSQL("INSERT INTO entry VALUES ($id, 1, $freq)")
                db.execSQL("INSERT INTO headword VALUES ($id, 0, '$simplified')")
                if (traditional != null) db.execSQL("INSERT INTO headword VALUES ($id, 1, '$traditional')")
                db.execSQL("INSERT INTO reading VALUES ($id, 0, '$reading', 0)")
                db.execSQL("INSERT INTO sense VALUES ($id, 0, '', '${glosses.joinToString("\t")}', '')")
            }
            line(1, 55, "包", null, "Bao1", listOf("surname Bao"))
            line(2, 55, "包", null, "bao1", listOf("to cover", "to wrap"))
            line(3, 71, "里", "裏", "li3", listOf("variant of 裡|里[li3]"))
            line(4, 71, "里", "裡", "li3", listOf("lining", "interior", "inside", "internal", "also written 裏|里[li3]"))
            line(5, 71, "里", null, "Li3", listOf("Li (surname)"))
            line(6, 71, "里", null, "li3", listOf("li, ancient measure of length, approx. 500 m", "neighborhood"))
            line(7, 60, "东西", "東西", "dong1 xi1", listOf("east and west"))
            line(8, 70, "东西", "東西", "dong1 xi5", listOf("thing", "stuff"))
            line(9, 58, "放", null, "fang4", listOf("to put", "to place"))
            line(10, 86, "在", null, "zai4", listOf("to exist; to be alive", "(of sb or sth) to be (located) at"))
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
