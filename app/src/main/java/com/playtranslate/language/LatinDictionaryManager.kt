package com.playtranslate.language

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only SQLite dictionary for Latin-script source languages. Shares the
 * JMdict-derived schema that `DictionaryManager` uses (entry / kanji /
 * reading / sense tables) so `scripts/build_en_dict.py` can produce
 * drop-in-compatible packs, but has a simpler lookup pipeline:
 *
 *  1. Exact-surface query against the `kanji` table (Latin headwords are
 *     stored in the `kanji.text` column — table name is a JMdict legacy
 *     we'll rename in a later cleanup).
 *  2. Stem-form fallback supplied by [LatinEngine] (Snowball Porter).
 *  3. Silent pass-through on miss (per decision 7 in the architecture doc).
 *
 * No de-inflection table. No N-gram phrase batching. No `kanjidic` table —
 * the Latin pack's `kanjidic` is empty by design, and this manager does
 * not probe or query it.
 *
 * Process-scoped singleton keyed on the `EN` source language. When Phase 6+
 * ships additional Latin languages (ES/FR/DE/…), this class should be
 * parameterized on `SourceLangId` so one singleton exists per Latin pack,
 * each opening its own SQLite handle.
 */
class LatinDictionaryManager private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    suspend fun preload() = ensureOpen()

    /**
     * Look up [surface] first, then [stemmed] (if different) as a fallback.
     * Returns null on miss — caller is expected to silent-pass-through.
     */
    suspend fun lookup(surface: String, stemmed: String?): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null

        val surfaceIds = queryEntryIds(database, surface.lowercase())
        if (surfaceIds.isNotEmpty()) {
            return@withContext buildResponse(database, surfaceIds)
        }

        if (stemmed != null && stemmed.lowercase() != surface.lowercase()) {
            val stemIds = queryEntryIds(database, stemmed.lowercase())
            if (stemIds.isNotEmpty()) {
                return@withContext buildResponse(database, stemIds, inflectionNote = "stem")
            }
        }

        null
    }

    fun close() {
        db?.close()
        db = null
    }

    // ── Init ──────────────────────────────────────────────────────────────

    private suspend fun ensureOpen(): SQLiteDatabase? = mutex.withLock {
        db?.let { return@withLock it }

        val dbFile = LanguagePackStore.dictDbFor(context, SourceLangId.EN)
        if (!dbFile.exists()) {
            Log.w(TAG, "EN pack missing at ${dbFile.absolutePath} — download it first")
            return@withLock null
        }

        if (!isSchemaUpToDate(dbFile)) {
            Log.w(TAG, "EN pack schema mismatch — marking uninstalled")
            return@withLock null
        }

        db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { Log.d(TAG, "EN pack opened (${dbFile.length() / 1_048_576} MB)") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open EN pack: ${e.message}")
            null
        }
        db
    }

    /**
     * Probes the three tables [lookup] actually queries: `entry`, `kanji`,
     * `sense`. Unlike the JA [com.playtranslate.dictionary.DictionaryManager.isSchemaUpToDate],
     * this does NOT require the `kanjidic` table — the Latin pack leaves it
     * empty or absent by design.
     */
    private fun isSchemaUpToDate(dbFile: File): Boolean {
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { tempDb ->
                tempDb.rawQuery("SELECT freq_score FROM entry LIMIT 1", null).use { }
                tempDb.rawQuery("SELECT text FROM kanji LIMIT 1", null).use { }
                tempDb.rawQuery("SELECT pos, glosses, misc FROM sense LIMIT 1", null).use { }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────

    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Long> {
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT DISTINCT k.entry_id FROM kanji k JOIN entry e ON e.id = k.entry_id WHERE k.text = ? ORDER BY e.freq_score DESC LIMIT 8",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    private fun buildResponse(
        db: SQLiteDatabase,
        entryIds: List<Long>,
        inflectionNote: String? = null,
    ): DictionaryResponse {
        val entries = entryIds.mapNotNull { buildEntry(db, it, inflectionNote) }
        return DictionaryResponse(entries = entries)
    }

    private fun buildEntry(db: SQLiteDatabase, id: Long, inflectionNote: String?): DictionaryEntry? {
        val idStr = id.toString()

        var isCommon = false
        var freqScore = 0
        db.rawQuery("SELECT is_common, freq_score FROM entry WHERE id=?", arrayOf(idStr)).use { c ->
            if (c.moveToFirst()) {
                isCommon  = c.getInt(0) == 1
                freqScore = c.getInt(1)
            }
        }

        val headwordTexts = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM kanji WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) headwordTexts.add(c.getString(0)) }
        if (headwordTexts.isEmpty()) return null

        val headwords = headwordTexts.map { Headword(written = it, reading = null) }

        val senses = mutableListOf<Sense>()
        db.rawQuery(
            "SELECT pos, glosses, misc FROM sense WHERE entry_id=? ORDER BY position LIMIT 8",
            arrayOf(idStr)
        ).use { c ->
            while (c.moveToNext()) {
                val posList   = c.getString(0).split(',').filter { it.isNotBlank() }
                val glossList = c.getString(1).split('\t').filter { it.isNotBlank() }
                val miscList  = c.getString(2).split('\t').filter { it.isNotBlank() }
                val finalPos  = if (inflectionNote != null && senses.isEmpty())
                    listOf("[$inflectionNote]") + posList
                else
                    posList
                senses.add(
                    Sense(
                        targetDefinitions = glossList,
                        partsOfSpeech = finalPos,
                        tags = emptyList(),
                        restrictions = emptyList(),
                        info = emptyList(),
                        misc = miscList,
                    )
                )
            }
        }
        if (senses.isEmpty()) return null

        return DictionaryEntry(
            slug = headwordTexts.first(),
            isCommon = isCommon,
            tags = emptyList(),
            jlpt = emptyList(),
            headwords = headwords,
            senses = senses,
            freqScore = freqScore,
        )
    }

    companion object {
        private const val TAG = "LatinDictionaryManager"

        // The stored context is context.applicationContext, which lives for
        // the entire process lifetime and cannot leak an Activity — so the
        // StaticFieldLeak warning here is a false positive.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LatinDictionaryManager? = null

        fun get(context: Context): LatinDictionaryManager =
            instance ?: synchronized(this) {
                instance ?: LatinDictionaryManager(context.applicationContext).also { instance = it }
            }
    }
}
