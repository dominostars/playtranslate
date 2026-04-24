package com.playtranslate.language

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only SQLite dictionary for Wiktionary-derived source packs. Shares
 * the JMdict schema that `DictionaryManager` uses (entry / headword /
 * reading / sense tables) so `scripts/build_latin_dict.py` can produce
 * drop-in-compatible packs, with a simpler lookup pipeline than JA:
 *
 *  1. Exact-surface query against the `headword` table. The `headword`
 *     rows come in three flavors, distinguished by `position`:
 *     `0` = lemma surface, `1` = Snowball stem, `2` = redirect alias
 *     (Wiktionary `form_of`/`alt_of` mapped to the lemma's entry_id).
 *     A single `WHERE text = ?` query matches all three; the caller uses
 *     the matched position to pick an inflection marker.
 *  2. Stem-form fallback supplied by [LatinEngine] (Snowball Porter).
 *  3. Silent pass-through on miss (per decision 7 in the architecture doc).
 *
 * No de-inflection table. No N-gram phrase batching. No `kanjidic` table —
 * the Wiktionary pack's `kanjidic` is empty by design, and this manager
 * does not probe or query it.
 *
 * Process-scoped singleton keyed on [SourceLangId]. One instance per source
 * language (EN, ES, FR, …, KO), each opening its own SQLite pack.
 *
 * Despite the "Wiktionary" name, the runtime query code is script-agnostic.
 * Callers from non-Latin engines (e.g. [KoreanEngine]) pass `stemmed = null`
 * and rely on lemma-exact matches, because their engine already lemmatized
 * the surface upstream.
 */
class WiktionaryDictionaryManager private constructor(
    private val context: Context,
    private val langId: SourceLangId,
) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    // Turkish case mapping differs from the Unicode default (`I` → `ı`,
    // `İ` → `i`). Matching the pack's lowercased headwords requires
    // locale-aware casing here instead of the default `.lowercase()`.
    private val locale = langId.locale

    suspend fun preload() = ensureOpen()

    /**
     * Look up [surface] first, then [stemmed] (if different) as a fallback.
     * Returns null on miss — caller is expected to silent-pass-through.
     *
     * Match-type marker semantics (the `[…]` prefix on the first sense's POS
     * list, applied by [buildEntry]):
     *  - Surface hit on a `headword.position=0` row  → no marker (direct lemma hit).
     *  - Surface hit on a `position=1` row           → `[stem]`
     *    (a surface OCR captured happened to equal a lemma's Snowball stem —
     *    rare but possible, e.g. English `run` is both surface and stem).
     *  - Surface hit on a `position=2` row           → `[inflected]`
     *    (Wiktionary `form_of`/`alt_of` alias — the user's surface is an
     *    inflected/alternate form of a lemma we're routing them to).
     *  - Stem-fallback branch (surface missed, we retried with the stemmer's
     *    output) → always `[stem]` via [buildResponse]'s `forceNote` param.
     *    We force `[stem]` even if the stem query matched a `position=0`
     *    row, because what we want to communicate is "we stemmed your query,"
     *    not "we stored this form."
     */
    suspend fun lookup(surface: String, stemmed: String?): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null

        val surfaceLower = surface.lowercase(locale)
        val surfaceIds = queryEntryIds(database, surfaceLower)
        if (surfaceIds.isNotEmpty()) {
            return@withContext buildResponse(database, surfaceIds)
        }

        if (stemmed != null) {
            val stemmedLower = stemmed.lowercase(locale)
            if (stemmedLower != surfaceLower) {
                val stemIds = queryEntryIds(database, stemmedLower)
                if (stemIds.isNotEmpty()) {
                    return@withContext buildResponse(database, stemIds, forceNote = "stem")
                }
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

        val dbFile = LanguagePackStore.dictDbFor(context, langId)
        if (!dbFile.exists()) {
            Log.w(TAG, "${langId.code} pack missing at ${dbFile.absolutePath} — download it first")
            return@withLock null
        }

        if (!isSchemaUpToDate(dbFile)) {
            Log.w(TAG, "${langId.code} pack schema mismatch — marking uninstalled")
            return@withLock null
        }

        db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { Log.d(TAG, "${langId.code} pack opened (${dbFile.length() / 1_048_576} MB)") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ${langId.code} pack: ${e.message}")
            null
        }
        db
    }

    /**
     * Probes the three tables [lookup] actually queries: `entry`, `headword`,
     * `sense`. Unlike the JA [com.playtranslate.dictionary.DictionaryManager.isSchemaUpToDate],
     * this does NOT require the `kanjidic` table — the Latin pack leaves it
     * empty or absent by design.
     */
    private fun isSchemaUpToDate(dbFile: File): Boolean = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { tempDb ->
            tempDb.rawQuery("SELECT freq_score FROM entry LIMIT 1", null).use { }
            tempDb.rawQuery("SELECT text FROM headword LIMIT 1", null).use { }
            tempDb.rawQuery("SELECT pos, glosses, misc FROM sense LIMIT 1", null).use { }
            // `example` table is optional: packs built before the usage-examples
            // feature don't have it. Callers of [buildEntry] treat a missing
            // table as "no examples for this entry," matching the old behavior.
        }
        true
    } catch (_: Exception) {
        false
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns up to 8 entry IDs whose headword rows match [word], paired
     * with the smallest position at which each entry matched. `GROUP BY +
     * MIN(position)` means each entry appears once with its best-priority
     * match position (0 = direct lemma, 1 = stem, 2 = form_of alias). The
     * caller uses that position to derive the POS marker shown to users.
     */
    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Pair<Long, Int>> {
        val results = mutableListOf<Pair<Long, Int>>()
        db.rawQuery(
            "SELECT h.entry_id, MIN(h.position) AS pos FROM headword h " +
                "JOIN entry e ON e.id = h.entry_id " +
                "WHERE h.text = ? " +
                "GROUP BY h.entry_id " +
                "ORDER BY e.freq_score DESC LIMIT 8",
            arrayOf(word)
        ).use { c ->
            while (c.moveToNext()) results.add(c.getLong(0) to c.getInt(1))
        }
        return results
    }

    /**
     * Materializes [idsWithPos] into a full [DictionaryResponse], deriving
     * each entry's inflection marker from its match position.
     *
     * [forceNote] overrides the position-based derivation — used by
     * [lookup]'s stem-fallback branch, which always wants `[stem]`
     * regardless of which position the stem query happened to match.
     */
    private fun buildResponse(
        db: SQLiteDatabase,
        idsWithPos: List<Pair<Long, Int>>,
        forceNote: String? = null,
    ): DictionaryResponse {
        val entries = idsWithPos.mapNotNull { (id, pos) ->
            val note = forceNote ?: when (pos) {
                0 -> null
                1 -> "stem"
                else -> "inflected"
            }
            buildEntry(db, id, note)
        }
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

        // Only position-0 rows are real lemma surfaces. Position 1 = stem
        // rows, position 2+ = form_of aliases — both are internal index
        // entries that must NOT appear in the user-visible headword list.
        val headwordTexts = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM headword WHERE entry_id=? AND position=0 ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) headwordTexts.add(c.getString(0)) }
        if (headwordTexts.isEmpty()) return null

        val headwords = headwordTexts.map { Headword(written = it, reading = null) }

        // Examples keyed by sense_position — attached below as each sense
        // row is materialized. Grouped upfront so we only run one SELECT per
        // entry instead of one per sense. The `example` table is optional:
        // packs built before the usage-examples feature don't have it, so a
        // missing-table exception degrades silently to "no examples."
        val examplesBySense = mutableMapOf<Int, MutableList<Example>>()
        try {
            db.rawQuery(
                "SELECT sense_position, text, translation FROM example " +
                    "WHERE entry_id=? ORDER BY sense_position, position",
                arrayOf(idStr)
            ).use { c ->
                while (c.moveToNext()) {
                    val sensePos = c.getInt(0)
                    val text = c.getString(1)
                    val translation = c.getString(2) ?: ""
                    examplesBySense.getOrPut(sensePos) { mutableListOf() }
                        .add(Example(text = text, translation = translation))
                }
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            // Older pack without the example table — leave examplesBySense empty.
        }

        val senses = mutableListOf<Sense>()
        db.rawQuery(
            "SELECT position, pos, glosses, misc FROM sense WHERE entry_id=? ORDER BY position LIMIT 8",
            arrayOf(idStr)
        ).use { c ->
            while (c.moveToNext()) {
                val sensePos  = c.getInt(0)
                val posList   = c.getString(1).split(',').filter { it.isNotBlank() }
                val glossList = c.getString(2).split('\t').filter { it.isNotBlank() }
                val miscList  = c.getString(3).split('\t').filter { it.isNotBlank() }
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
                        examples = examplesBySense[sensePos].orEmpty(),
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
            // DB stores 0-100 (fine-grained, used for ORDER BY). Normalize to
            // 0-5 for display consistency with JMdict's 0-5 star scale.
            freqScore = (freqScore * 5 / 100).coerceIn(0, 5),
        )
    }

    companion object {
        private const val TAG = "WiktionaryDictMgr"

        @SuppressLint("StaticFieldLeak")
        private val instances = java.util.concurrent.ConcurrentHashMap<SourceLangId, WiktionaryDictionaryManager>()

        fun get(context: Context, langId: SourceLangId = SourceLangId.EN): WiktionaryDictionaryManager =
            instances.getOrPut(langId) {
                WiktionaryDictionaryManager(context.applicationContext, langId)
            }
    }
}
