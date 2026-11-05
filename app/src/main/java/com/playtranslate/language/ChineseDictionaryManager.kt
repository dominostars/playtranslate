package com.playtranslate.language

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.hankcs.hanlp.dictionary.CustomDictionary
import com.playtranslate.dictionary.PREFIX_EXACT_BONUS
import com.playtranslate.dictionary.withRefcount
import com.playtranslate.dictionary.prefixUpperBound
import com.playtranslate.model.DictionaryResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One lookup's result: the entry it leads with, whether the caller's
 *  reading hint picked a group other than the one an unhinted lookup leads
 *  with, and the readings of the surface's other words
 *  ([ChineseEntryGroups.otherReadings]); the engine narrows the imported
 *  senses from the last two ([importNarrowing]). */
internal class GroupHit(val response: DictionaryResponse, val hinted: Boolean, val otherReadings: Set<String>)

/** What the heteronym pass needs of one surface: its [readings] in lead
 *  order ([ChineseEntryGroups.candidateReadings]) and whether the first is
 *  the reading the table names for the word ([ChineseEntryGroups.leadIsNamed]). */
internal class HeteronymCandidates(val readings: List<String>, val leadNamed: Boolean) {
    companion object {
        val NONE = HeteronymCandidates(emptyList(), leadNamed = false)
    }
}

/**
 * Read-only SQLite dictionary for Chinese, backed by a CC-CEDICT-derived
 * pack. Both Simplified and Traditional headwords are stored in the
 * `headword` table (position 0 = simplified, position 1 = traditional
 * when different), so a single `WHERE text = ?` query matches either
 * variant.
 *
 * No stemming or de-inflection — Chinese doesn't inflect. A lookup reads
 * every CC-CEDICT line of the surface and hands out ONE entry, the group
 * [ChineseEntryGroups] leads with; the other groups are the surface's
 * alternatives ([alternativeGroups]). Which reading leads where the pack
 * cannot say comes from the app's own table ([ChineseReadingDefaults]).
 * Silent pass-through on miss.
 *
 * Every query runs under [withRefcount], as the Japanese manager's do: a
 * pack evicted while a capture is still being read (an uninstall, the
 * upgrade flow's post-install close) answers "nothing" instead of throwing
 * out of the engine, whose annotation must degrade and never throw.
 */
class ChineseDictionaryManager private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    @Volatile private var customDictInjected = false
    private val customDictMutex = Mutex()

    /** Which reading leads where the pack cannot say; read once from assets. */
    private val readingDefaults: ChineseReadingDefaults by lazy { ChineseReadingDefaults.load(context) }

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
     * dict.sqlite) closes the gap without rebuilding the pack. The
     * other direction, a HanLP term CC-CEDICT does not list, is the
     * engine's narrowing ([ChineseWordMatch]).
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
                // A pack closed under the walk leaves the guard unset, so
                // the next preload injects.
                database.withRefcount {
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
                } ?: return@withContext
                Log.d(
                    TAG,
                    "Injected $added CC-CEDICT entries into HanLP CustomDictionary in ${System.currentTimeMillis() - started} ms",
                )
                customDictInjected = true
            }
        }
    }

    /**
     * The one entry [surface] leads with ([ChineseEntryGroups]), or with a
     * [reading] hint the group that reading names; a hint no group matches
     * falls back to the default group, never to null, the contract of the
     * Japanese pack's reading step. Null only when the pack has no line
     * for [surface].
     */
    suspend fun lookup(surface: String, preferTraditional: Boolean = false, reading: String? = null): DictionaryResponse? =
        lookupGroup(surface, preferTraditional, reading)?.response

    /** [lookup] with whether the hint picked a non-default group. */
    internal suspend fun lookupGroup(surface: String, preferTraditional: Boolean, reading: String?): GroupHit? =
        withContext(Dispatchers.IO) {
            val groups = groupsOf(surface, preferTraditional)
            if (groups.isEmpty()) return@withContext null
            val index = ChineseEntryGroups.hintedIndex(groups, reading) ?: 0
            GroupHit(
                DictionaryResponse(entries = listOf(ChineseEntryGroups.toEntry(groups[index]))),
                hinted = index != 0,
                otherReadings = ChineseEntryGroups.otherReadings(groups, index),
            )
        }

    /** Every group of [surface] in lead order; the engine drops the one a
     *  lookup led with and offers the rest as "Also matches". */
    internal suspend fun alternativeGroups(surface: String, preferTraditional: Boolean): List<EntryGroup> =
        withContext(Dispatchers.IO) { groupsOf(surface, preferTraditional) }

    /**
     * What the heteronym pass needs of [surface]: its tone-marked readings in
     * lead order with proper nouns left out, so the first is the reading an
     * unhinted [lookup] leads with in the same script (the pass emits a
     * correction only when the context reading differs from it) and a
     * surname never counts as a heteronym of the common word; and whether
     * that first reading is the one the table names, which decides what the
     * pass counts as context.
     */
    internal suspend fun heteronymCandidates(surface: String, preferTraditional: Boolean): HeteronymCandidates =
        withContext(Dispatchers.IO) {
            val groups = groupsOf(surface, preferTraditional)
            HeteronymCandidates(
                ChineseEntryGroups.candidateReadings(groups),
                ChineseEntryGroups.leadIsNamed(groups, readingDefaults::leadReading),
            )
        }

    /**
     * Which of [candidates] the pack lists as a headword, either script. The
     * listed-word oracle behind the engine's narrowing and member split; one
     * query per 500 candidates, under SQLite's bind limit. Null when no pack
     * is open, or the pack closed under the probe, which the engine tells
     * from "none listed": with no pack it leaves HanLP's terms whole instead
     * of splitting every one.
     */
    suspend fun existingHeadwords(candidates: Collection<String>): Set<String>? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null
        if (candidates.isEmpty()) return@withContext emptySet()
        database.withRefcount {
            val found = HashSet<String>()
            for (chunk in candidates.distinct().chunked(EXISTS_CHUNK)) {
                val marks = chunk.joinToString(",") { "?" }
                database.rawQuery("SELECT DISTINCT text FROM headword WHERE text IN ($marks)", chunk.toTypedArray())
                    .use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
            }
            found
        }
    }

    /**
     * Ranked prefix-completion candidate words for a partial [query] — the
     * dictionary-search path. Returns up to [limit] display headwords whose
     * Simplified OR Traditional form begins with [query] (both scripts live in
     * the `headword` table), ordered exact-match-first then by `freq_score`.
     * Each result is a headword string that re-resolves via [lookup].
     *
     * Deduped to one entry per row, with the display form picked in the
     * caller's preferred script ([preferTraditional]) as [primaryHeadword]
     * does — so a word never appears twice (once per script).
     */
    suspend fun searchPrefix(
        query: String,
        limit: Int = 20,
        preferTraditional: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val database = ensureOpen() ?: return@withContext emptyList()
        val upper = prefixUpperBound(q) ?: return@withContext emptyList()
        database.withRefcount {
            val ids = mutableListOf<Long>()
            database.rawQuery(
                "SELECT h.entry_id AS eid, " +
                    "MAX(e.freq_score + (CASE WHEN h.text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END)) AS s " +
                    "FROM headword h JOIN entry e ON e.id = h.entry_id " +
                    "WHERE h.text >= ? AND h.text < ? " +
                    "GROUP BY h.entry_id ORDER BY s DESC LIMIT ?",
                arrayOf(q, q, upper, limit.toString()),
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            ids.mapNotNull { id -> primaryHeadword(database, id, preferTraditional) }.distinct()
        } ?: emptyList()
    }

    /** Display headword for [id] in the caller's preferred script (position
     *  0 = simplified, position 1 = traditional). */
    private fun primaryHeadword(db: SQLiteDatabase, id: Long, preferTraditional: Boolean): String? {
        val forms = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM headword WHERE entry_id=? ORDER BY position",
            arrayOf(id.toString()),
        ).use { c -> while (c.moveToNext()) forms.add(c.getString(0)) }
        if (forms.isEmpty()) return null
        return if (preferTraditional && forms.size > 1) forms[1] else forms[0]
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

    /** [ChineseEntryGroups.groups] over the pack's lines for [surface]; none
     *  when no pack is open or it closed under the query. Must run on IO. */
    private suspend fun groupsOf(surface: String, preferTraditional: Boolean): List<EntryGroup> {
        val database = ensureOpen() ?: return emptyList()
        val lines = database.withRefcount { queryLines(database, surface) } ?: return emptyList()
        return ChineseEntryGroups.groups(lines, surface, preferTraditional, readingDefaults::leadReading)
    }

    /**
     * Every CC-CEDICT line whose simplified or traditional form is [word]:
     * one query for the entry, its reading and its sense (the pack writes
     * one of each per line), one for the lines' written forms.
     */
    private fun queryLines(db: SQLiteDatabase, word: String): List<CedictLine> {
        class Row(val id: Long, val freq: Int, val common: Boolean, val reading: String, val pos: String, val glosses: List<String>, val misc: List<String>)
        val rows = mutableListOf<Row>()
        db.rawQuery(
            "SELECT DISTINCT e.id, e.freq_score, e.is_common, r.text, s.pos, s.glosses, s.misc " +
                "FROM headword h JOIN entry e ON e.id = h.entry_id " +
                "JOIN reading r ON r.entry_id = e.id AND r.position = 0 " +
                "JOIN sense s ON s.entry_id = e.id AND s.position = 0 " +
                "WHERE h.text = ? ORDER BY e.id",
            arrayOf(word),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Row(
                    id = c.getLong(0), freq = c.getInt(1), common = c.getInt(2) == 1,
                    reading = c.getString(3) ?: "", pos = c.getString(4) ?: "",
                    glosses = c.getString(5).orEmpty().split('\t').filter { it.isNotBlank() },
                    misc = c.getString(6).orEmpty().split('\t').filter { it.isNotBlank() },
                ))
            }
        }
        if (rows.isEmpty()) return emptyList()
        val forms = HashMap<Long, MutableList<String>>()
        val marks = rows.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT entry_id, text FROM headword WHERE entry_id IN ($marks) ORDER BY entry_id, position",
            rows.map { it.id.toString() }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) forms.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
        }
        return rows.mapNotNull { row ->
            val written = forms[row.id] ?: return@mapNotNull null
            if (row.glosses.isEmpty()) return@mapNotNull null
            CedictLine(
                id = row.id, freqScore = row.freq, isCommon = row.common, reading = row.reading,
                simplified = written[0], traditional = written.getOrNull(1),
                pos = row.pos, glosses = row.glosses, misc = row.misc,
            )
        }
    }

    companion object {
        private const val TAG = "ChineseDictMgr"
        private const val EXISTS_CHUNK = 500

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ChineseDictionaryManager? = null

        fun get(context: Context): ChineseDictionaryManager =
            instance ?: synchronized(this) {
                instance ?: ChineseDictionaryManager(context.applicationContext).also { instance = it }
            }
    }
}
