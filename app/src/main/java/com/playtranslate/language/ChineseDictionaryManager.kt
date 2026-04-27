package com.playtranslate.language

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hankcs.hanlp.dictionary.CustomDictionary
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
 * pack. Both Simplified and Traditional headwords are stored in the
 * `headword` table (position 0 = simplified, position 1 = traditional
 * when different), so a single `WHERE text = ?` query matches either
 * variant.
 *
 * No stemming or de-inflection — Chinese doesn't inflect. Lookup is
 * direct surface match with silent pass-through on miss.
 */
class ChineseDictionaryManager private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    @Volatile private var customDictInjected = false
    private val customDictMutex = Mutex()

    suspend fun preload() = ensureOpen()

    /**
     * Walks every length-≥2 headword in the open pack and adds it to
     * HanLP's [CustomDictionary] runtime BinTrie. Idempotent across
     * calls; cheap to invoke from [ChineseEngine.preload] regardless
     * of warm/cold state.
     *
     * Why we need this: HanLP portable-1.8.4 ships only the *mini*
     * CoreNatureDictionary, which doesn't know modern compounds like
     * 赋能 / 用户体验 / 用户名 — HanLP segments them into single chars
     * even on whitespace-clean input, and the downstream dict.sqlite
     * lookup never sees the compound surface. Augmenting HanLP's
     * runtime trie with every CC-CEDICT headword (already in
     * dict.sqlite) closes the gap without rebuilding the pack.
     *
     * Why runtime instead of the pack's CustomDictionary.txt: HanLP
     * ships its CustomDictionary as a precompiled .txt.bin only — the
     * .txt source isn't recoverable. Replacing the .bin with one
     * derived from CC-CEDICT loses HanLP's curated everyday compounds
     * (很好, 魔法石, …). Adding via [CustomDictionary.add] populates
     * a separate runtime BinTrie that ViterbiSegment consults
     * alongside the static DAT, so neither set is displaced.
     *
     * Why skip single-char entries: HanLP's CoreNatureDictionary
     * already covers single hanzi with carefully tuned frequencies,
     * and adding our own with a uniform freq distorts Viterbi enough
     * to split known compounds (e.g. 很好 → 很 + 好). Single-char
     * dictionary lookups still resolve via [lookup] regardless.
     *
     * Pack-lifecycle note: the [customDictInjected] guard is keyed to
     * the no-in-process-pack-refresh policy (project_pack_update_policy.md).
     * If that policy ever loosens — hasUpdate(), background refresh, or
     * any path that swaps pack content while the process is live — this
     * needs a reset on uninstall AND a remove() pass for stale words,
     * because HanLP's runtime BinTrie is JVM-global.
     */
    suspend fun injectCustomDictEntriesOnce() {
        if (customDictInjected) return
        customDictMutex.withLock {
            if (customDictInjected) return@withLock
            withContext(Dispatchers.IO) {
                val database = ensureOpen() ?: return@withContext
                val started = System.currentTimeMillis()
                var added = 0
                database.rawQuery(
                    "SELECT DISTINCT h.text, e.freq_score FROM headword h " +
                        "JOIN entry e ON e.id = h.entry_id WHERE LENGTH(h.text) >= 2",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val word = c.getString(0) ?: continue
                        val freq = maxOf(1, c.getInt(1))
                        // HanLP's add() takes either a bare word (assumed
                        // n 1) or a word + nature/freq spec. Use "n <freq>"
                        // so freq carries through; POS isn't read
                        // anywhere in the app.
                        CustomDictionary.add(word, "n $freq")
                        added++
                    }
                }
                Log.d(
                    TAG,
                    "Injected $added CC-CEDICT entries into HanLP CustomDictionary in ${System.currentTimeMillis() - started} ms",
                )
                customDictInjected = true
            }
        }
    }

    suspend fun lookup(surface: String, preferTraditional: Boolean = false): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null
        val ids = queryEntryIds(database, surface)
        if (ids.isNotEmpty()) buildResponse(database, ids, preferTraditional) else null
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
            tempDb.rawQuery("SELECT text FROM headword LIMIT 1", null).use { }
            tempDb.rawQuery("SELECT pos, glosses, misc FROM sense LIMIT 1", null).use { }
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Long> {
        val ids = mutableListOf<Long>()
        db.rawQuery(
            "SELECT DISTINCT h.entry_id FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.text = ? ORDER BY e.freq_score DESC LIMIT 8",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    private fun buildResponse(db: SQLiteDatabase, entryIds: List<Long>, preferTraditional: Boolean = false): DictionaryResponse {
        val entries = entryIds.mapNotNull { buildEntry(db, it, preferTraditional) }
        return DictionaryResponse(entries = entries)
    }

    private fun buildEntry(db: SQLiteDatabase, id: Long, preferTraditional: Boolean = false): DictionaryEntry? {
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
            "SELECT text FROM headword WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) headwordTexts.add(c.getString(0)) }
        if (headwordTexts.isEmpty()) return null

        // CC-CEDICT stores pinyin in the reading table
        val readings = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM reading WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) readings.add(c.getString(0)) }

        // CC-CEDICT has one reading per entry shared by all headword forms
        val primaryReading = readings.firstOrNull()?.let { PinyinFormatter.numberedToToneMarks(it) }
        val headwords = headwordTexts.map { written ->
            Headword(written = written, reading = primaryReading)
        }.let { if (preferTraditional && it.size > 1) it.reversed() else it }

        val senses = mutableListOf<Sense>()
        db.rawQuery(
            "SELECT pos, glosses, misc FROM sense WHERE entry_id=? ORDER BY position LIMIT 8",
            arrayOf(idStr)
        ).use { c ->
            while (c.moveToNext()) {
                val posList   = c.getString(0).split(',').filter { it.isNotBlank() }
                val glossList = c.getString(1).split('\t').filter { it.isNotBlank() }
                    .map { PinyinFormatter.convertPinyinInBrackets(it) }
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
