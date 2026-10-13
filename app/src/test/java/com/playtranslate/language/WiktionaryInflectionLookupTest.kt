package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins how [WiktionaryDictionaryManager] reads the pack's `inflection` tables:
 * an alias-tier (position 2) match carries the entry's readings of the
 * looked-up surface ([com.playtranslate.model.DictionaryEntry.formReadings])
 * and drops the `[inflected]` marker; lemma and stem matches carry none; a
 * pack without the tables, and a language without a paradigm, behave as
 * before; [WiktionaryDictionaryManager.inflectionTable] returns the whole
 * blob; and the tagset map is dropped with the handle on [close].
 *
 * Same singleton constraint as [WiktionaryLookupRankingTest]: the manager
 * binds to the first Context that builds it, so every method clears the
 * instance cache and writes its own fixture before getting a manager.
 */
@RunWith(RobolectricTestRunner::class)
class WiktionaryInflectionLookupTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `an alias match carries the surface's readings and no marker`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        val response = manager.lookup(surface = "hablamos", stemmed = "habl")
        assertNotNull(response)
        val hablar = response!!.entries.single()
        assertEquals("hablar", hablar.slug)
        assertEquals(1L, hablar.packId)
        assertEquals(
            listOf(
                InflectionForm("hablamos", setOf("first-person", "indicative", "plural", "present")),
                InflectionForm("hablamos", setOf("first-person", "indicative", "plural", "preterite")),
            ),
            hablar.formReadings,
        )
        assertEquals(listOf("verb"), hablar.senses.first().partsOfSpeech)
    }

    @Test fun `readings are per entry when a surface is a lemma and another entry's form`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        val entries = manager.lookup(surface = "vino", stemmed = "vin")!!.entries
        assertEquals(listOf("vino", "venir"), entries.map { it.slug })
        assertTrue(entries[0].formReadings.isEmpty())
        assertEquals(listOf("noun"), entries[0].senses.first().partsOfSpeech)
        assertEquals(
            listOf(InflectionForm("vino", setOf("indicative", "preterite", "singular", "third-person"))),
            entries[1].formReadings,
        )
        assertEquals(listOf("verb"), entries[1].senses.first().partsOfSpeech)
    }

    @Test fun `a key that is both the stem row and an alias reads as the form, not the stem`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        // poner: Snowball stem `pon` (position 1) and the tú imperative `pon` (position 2).
        val poner = manager.lookup(surface = "pon", stemmed = "pon")!!.entries.single()
        assertEquals("poner", poner.slug)
        assertEquals(
            listOf(InflectionForm("pon", setOf("imperative", "informal", "second-person", "singular"))),
            poner.formReadings,
        )
        assertEquals(listOf("verb"), poner.senses.first().partsOfSpeech)
    }

    @Test fun `a lemma match and a stem match carry no readings`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        val lemma = manager.lookup(surface = "hablar", stemmed = "habl")!!.entries.single()
        assertTrue(lemma.formReadings.isEmpty())
        assertEquals(listOf("verb"), lemma.senses.first().partsOfSpeech)

        // Not an alias and not in the blob; the Snowball stem row resolves it and keeps [stem].
        val stem = manager.lookup(surface = "hablaríamos", stemmed = "habl")!!.entries.single()
        assertEquals("hablar", stem.slug)
        assertTrue(stem.formReadings.isEmpty())
        assertEquals("[stem]", stem.senses.first().partsOfSpeech.first())
    }

    @Test fun `a stem-branch hit whose surface is a blob line reads as that form`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        // `hablaréis` has no alias row (its page fell under the cut) but the
        // blob has it; the stem row `habl` resolves it and the reading wins.
        val hit = manager.lookup(surface = "hablaréis", stemmed = "habl")!!.entries.single()
        assertEquals("hablar", hit.slug)
        assertEquals(
            listOf(InflectionForm("hablaréis", setOf("future", "indicative", "plural", "second-person"))),
            hit.formReadings,
        )
        assertEquals(listOf("verb"), hit.senses.first().partsOfSpeech)
    }

    @Test fun `a reading the paradigm cannot label is dropped and the marker stays`() = runBlocking {
        // `a mis solas` is tagged first-person singular and nothing else (a
        // form-of page of a prepositional phrase); the Spanish paradigm has
        // no words for that, so the entry keeps the marker and draws no line.
        val manager = fixture(SourceLangId.ES, withTables = true)
        val aSolas = manager.lookup(surface = "a mis solas", stemmed = "a mis solas")!!.entries.single()
        assertEquals("a solas", aSolas.slug)
        assertTrue(aSolas.formReadings.isEmpty())
        assertEquals("[inflected]", aSolas.senses.first().partsOfSpeech.first())
    }

    @Test fun `a pack without the tables keeps the marker and has no table`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = false)
        val hablar = manager.lookup(surface = "hablamos", stemmed = "habl")!!.entries.single()
        assertTrue(hablar.formReadings.isEmpty())
        assertEquals("[inflected]", hablar.senses.first().partsOfSpeech.first())
        assertNull(manager.inflectionTable(1L))
    }

    @Test fun `a language without a paradigm keeps the marker even on a pack with the tables`() = runBlocking {
        val manager = fixture(SourceLangId.VI, withTables = true)
        val hablar = manager.lookup(surface = "hablamos", stemmed = "habl")!!.entries.single()
        assertTrue(hablar.formReadings.isEmpty())
        assertEquals("[inflected]", hablar.senses.first().partsOfSpeech.first())
        assertNull(manager.inflectionTable(1L))
    }

    @Test fun `inflectionTable returns the whole blob in table order`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        val table = manager.inflectionTable(1L)
        assertEquals(
            listOf("hablar", "hablamos", "hablamos", "hablo", "hablamos", "hablaréis"),
            table!!.map { it.surface },
        )
        assertEquals(setOf("infinitive"), table[0].tags)
        assertTrue(table[4].fromPronominalVariant)
        assertNull(manager.inflectionTable(99L))
    }

    @Test fun `close drops the tagset map so a swapped pack reads its own tags`() = runBlocking {
        val manager = fixture(SourceLangId.ES, withTables = true)
        assertEquals(
            setOf("first-person", "indicative", "plural", "present"),
            manager.lookup(surface = "hablamos", stemmed = null)!!.entries.single().formReadings[0].tags,
        )
        manager.close()
        // The same tagset id now means something else in the replacement pack.
        writeFixture(SourceLangId.ES, withTables = true, tagset2 = "gerund")
        assertEquals(
            setOf("gerund"),
            manager.lookup(surface = "hablamos", stemmed = null)!!.entries.single().formReadings[0].tags,
        )
    }

    // ── fixture ─────────────────────────────────────────────────────────

    private fun fixture(lang: SourceLangId, withTables: Boolean): WiktionaryDictionaryManager {
        clearManagerCache()
        writeFixture(lang, withTables)
        return WiktionaryDictionaryManager.get(ctx, lang)
    }

    /** Five entries: 1 hablar (verb; aliases hablamos, hablo; stem habl),
     *  2 venir (verb; alias vino), 3 vino (noun lemma), 4 poner (verb; pon is
     *  both its stem row and an alias), 5 a solas (phrase; alias a mis solas,
     *  whose only reading has no label). Blobs for 1, 2, 4 and 5. */
    private fun writeFixture(
        lang: SourceLangId,
        withTables: Boolean,
        tagset2: String = "first-person indicative plural present",
    ) {
        val dbFile = LanguagePackStore.dictDbFor(ctx, lang)
        dbFile.parentFile!!.mkdirs()
        if (dbFile.exists()) dbFile.delete()
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
            db.execSQL("INSERT INTO entry VALUES (1, 1, 80)")
            db.execSQL("INSERT INTO headword VALUES (1, 0, 'hablar')")
            db.execSQL("INSERT INTO headword VALUES (1, 1, 'habl')")
            db.execSQL("INSERT INTO headword VALUES (1, 2, 'hablamos')")
            db.execSQL("INSERT INTO headword VALUES (1, 2, 'hablo')")
            db.execSQL("INSERT INTO sense VALUES (1, 0, 'verb', 'to talk', '')")
            db.execSQL("INSERT INTO entry VALUES (2, 1, 85)")
            db.execSQL("INSERT INTO headword VALUES (2, 0, 'venir')")
            db.execSQL("INSERT INTO headword VALUES (2, 2, 'vino')")
            db.execSQL("INSERT INTO sense VALUES (2, 0, 'verb', 'to come', '')")
            db.execSQL("INSERT INTO entry VALUES (3, 1, 70)")
            db.execSQL("INSERT INTO headword VALUES (3, 0, 'vino')")
            db.execSQL("INSERT INTO sense VALUES (3, 0, 'noun', 'wine', '')")
            db.execSQL("INSERT INTO entry VALUES (4, 1, 88)")
            db.execSQL("INSERT INTO headword VALUES (4, 0, 'poner')")
            db.execSQL("INSERT INTO headword VALUES (4, 1, 'pon')")
            db.execSQL("INSERT INTO headword VALUES (4, 2, 'pon')")
            db.execSQL("INSERT INTO sense VALUES (4, 0, 'verb', 'to put', '')")
            db.execSQL("INSERT INTO entry VALUES (5, 0, 40)")
            db.execSQL("INSERT INTO headword VALUES (5, 0, 'a solas')")
            db.execSQL("INSERT INTO headword VALUES (5, 2, 'a mis solas')")
            db.execSQL("INSERT INTO sense VALUES (5, 0, 'prep_phrase', 'alone, by oneself', '')")
            if (withTables) {
                db.execSQL("CREATE TABLE inflection_tagset (id INTEGER PRIMARY KEY, tags TEXT NOT NULL)")
                db.execSQL("CREATE TABLE inflection (entry_id INTEGER PRIMARY KEY, forms TEXT NOT NULL)")
                db.execSQL("INSERT INTO inflection_tagset VALUES (1, 'infinitive')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (2, '$tagset2')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (3, 'first-person indicative plural preterite')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (4, 'first-person indicative present singular')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (5, 'indicative preterite singular third-person')")
                // A second table's row (the builder's table-N tag) under the
                // same surface: never a reading, still part of the blob.
                db.execSQL("INSERT INTO inflection_tagset VALUES (6, 'first-person indicative plural present pronominal table-2')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (8, 'future indicative plural second-person')")
                db.execSQL("INSERT INTO inflection VALUES (1, '1\thablar\n2\thablamos\n3\thablamos\n4\thablo\n6\thablamos\n8\thablaréis')")
                db.execSQL("INSERT INTO inflection VALUES (2, '1\tvenir\n5\tvino')")
                db.execSQL("INSERT INTO inflection_tagset VALUES (7, 'imperative informal second-person singular')")
                db.execSQL("INSERT INTO inflection VALUES (4, '1\tponer\n7\tpon')")
                // A form-of page's line the Spanish paradigm cannot label.
                db.execSQL("INSERT INTO inflection_tagset VALUES (9, 'first-person form-of singular')")
                db.execSQL("INSERT INTO inflection VALUES (5, '9\ta mis solas')")
            }
        }
    }

    private fun clearManagerCache() {
        val field = WiktionaryDictionaryManager::class.java.getDeclaredField("instances")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
