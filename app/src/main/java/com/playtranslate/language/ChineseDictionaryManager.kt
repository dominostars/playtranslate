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
 * Read-only SQLite dictionary for Chinese, backed by a CC-CEDICT-derived
 * pack. Both Simplified and Traditional headwords are stored in the `kanji`
 * table (position 0 = simplified, position 1 = traditional when different),
 * so a single `WHERE text = ?` query matches either variant.
 *
 * No stemming or de-inflection — Chinese doesn't inflect. Lookup is
 * direct surface match with silent pass-through on miss.
 */
class ChineseDictionaryManager private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    suspend fun preload() = ensureOpen()

    suspend fun lookup(surface: String): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null
        val ids = queryEntryIds(database, surface)
        if (ids.isNotEmpty()) buildResponse(database, ids) else null
    }

    fun close() {
        db?.close()
        db = null
    }

    private suspend fun ensureOpen(): SQLiteDatabase? = mutex.withLock {
        db?.let { return@withLock it }

        val dbFile = LanguagePackStore.dictDbFor(context, SourceLangId.ZH)
        if (!dbFile.exists()) {
            Log.w(TAG, "ZH pack missing at ${dbFile.absolutePath}")
            return@withLock null
        }

        if (!isSchemaUpToDate(dbFile)) {
            Log.w(TAG, "ZH pack schema mismatch")
            return@withLock null
        }

        db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { Log.d(TAG, "ZH pack opened (${dbFile.length() / 1_048_576} MB)") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ZH pack: ${e.message}")
            null
        }
        db
    }

    private fun isSchemaUpToDate(dbFile: File): Boolean = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { tempDb ->
            tempDb.rawQuery("SELECT freq_score FROM entry LIMIT 1", null).use { }
            tempDb.rawQuery("SELECT text FROM kanji LIMIT 1", null).use { }
            tempDb.rawQuery("SELECT pos, glosses, misc FROM sense LIMIT 1", null).use { }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Long> {
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT DISTINCT k.entry_id FROM kanji k JOIN entry e ON e.id = k.entry_id WHERE k.text = ? ORDER BY e.freq_score DESC LIMIT 8",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    private fun buildResponse(db: SQLiteDatabase, entryIds: List<Long>): DictionaryResponse {
        val entries = entryIds.mapNotNull { buildEntry(db, it) }
        return DictionaryResponse(entries = entries)
    }

    private fun buildEntry(db: SQLiteDatabase, id: Long): DictionaryEntry? {
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

        // CC-CEDICT stores pinyin in the reading table
        val readings = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM reading WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) readings.add(c.getString(0)) }

        val headwords = headwordTexts.mapIndexed { i, written ->
            Headword(written = written, reading = readings.getOrNull(i))
        }

        val senses = mutableListOf<Sense>()
        db.rawQuery(
            "SELECT pos, glosses, misc FROM sense WHERE entry_id=? ORDER BY position LIMIT 8",
            arrayOf(idStr)
        ).use { c ->
            while (c.moveToNext()) {
                val posList   = c.getString(0).split(',').filter { it.isNotBlank() }
                val glossList = c.getString(1).split('\t').filter { it.isNotBlank() }
                val miscList  = c.getString(2).split('\t').filter { it.isNotBlank() }
                senses.add(
                    Sense(
                        targetDefinitions = glossList,
                        partsOfSpeech = posList,
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
            freqScore = (freqScore * 5 / 100).coerceIn(0, 5),
        )
    }

    companion object {
        private const val TAG = "ChineseDictMgr"

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ChineseDictionaryManager? = null

        fun get(context: Context): ChineseDictionaryManager =
            instance ?: synchronized(this) {
                instance ?: ChineseDictionaryManager(context.applicationContext).also { instance = it }
            }
    }
}
