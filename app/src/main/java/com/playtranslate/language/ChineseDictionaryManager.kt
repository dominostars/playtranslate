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

    suspend fun preload() = ensureOpen()

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
        val primaryReading = readings.firstOrNull()?.let { numberedToToneMarks(it) }
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
                    .map { convertPinyinInBrackets(it) }
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

    /**
     * Converts numbered pinyin (e.g. "fu4 wu4") to tone-marked pinyin ("fù wù").
     * Handles syllables separated by spaces. Tone 5 (neutral) = no mark.
     * Preserves leading-letter capitalization so proper nouns like "Bei3 jing1"
     * render as "Běi jīng" rather than lowercased.
     */
    private fun numberedToToneMarks(numbered: String): String =
        numbered.split(' ').joinToString(" ") { syllable ->
            val tone = syllable.lastOrNull()?.digitToIntOrNull()
            if (tone == null || tone == 0) return@joinToString syllable
            val base = if (tone in 1..5) syllable.dropLast(1) else syllable
            if (tone == 5 || tone !in 1..4) return@joinToString base
            val wasCapital = base.firstOrNull()?.isUpperCase() == true
            val marked = applyToneMark(base.lowercase(), tone)
            if (wasCapital) marked.replaceFirstChar { it.uppercase() } else marked
        }

    /**
     * CC-CEDICT definitions embed pinyin cross-references in brackets, e.g.
     * `"capital of Hebei Province 河北省[He2 bei3 sheng3] in China"`. Convert
     * only the content inside `[...]` so numeric tones become accents while
     * surrounding English text (which may legitimately contain digits like
     * "H2O") is left untouched.
     */
    private fun convertPinyinInBrackets(text: String): String =
        PINYIN_BRACKET_REGEX.replace(text) { m ->
            "[${numberedToToneMarks(m.groupValues[1])}]"
        }

    private fun applyToneMark(syllable: String, tone: Int): String {
        // Standard rule: mark goes on 'a' or 'e' if present, 'o' in 'ou', else last vowel
        val vowels = "aeiouü"
        val toneMap = mapOf(
            'a' to arrayOf("ā", "á", "ǎ", "à"),
            'e' to arrayOf("ē", "é", "ě", "è"),
            'i' to arrayOf("ī", "í", "ǐ", "ì"),
            'o' to arrayOf("ō", "ó", "ǒ", "ò"),
            'u' to arrayOf("ū", "ú", "ǔ", "ù"),
            'ü' to arrayOf("ǖ", "ǘ", "ǚ", "ǜ"),
        )
        // Map v → ü (CC-CEDICT uses v for ü)
        val s = syllable.replace('v', 'ü')

        val idx = when {
            'a' in s -> s.indexOf('a')
            'e' in s -> s.indexOf('e')
            "ou" in s -> s.indexOf('o')
            else -> s.indexOfLast { it in vowels }
        }
        if (idx < 0) return s
        val vowel = s[idx]
        val marked = toneMap[vowel]?.getOrNull(tone - 1) ?: return s
        return s.substring(0, idx) + marked + s.substring(idx + 1)
    }

    companion object {
        private const val TAG = "ChineseDictMgr"
        private val PINYIN_BRACKET_REGEX = Regex("""\[([^\[\]]+)\]""")

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ChineseDictionaryManager? = null

        fun get(context: Context): ChineseDictionaryManager =
            instance ?: synchronized(this) {
                instance ?: ChineseDictionaryManager(context.applicationContext).also { instance = it }
            }
    }
}
