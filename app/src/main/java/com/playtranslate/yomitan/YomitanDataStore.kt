package com.playtranslate.yomitan

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.ImportedKanji
import com.playtranslate.model.ImportedSenseGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * App-wide read facade over the runtime data derived from imported Yomitan
 * dictionaries.
 *
 * ARCHITECTURE RULE: this is the ONLY Yomitan type that code outside the
 * `yomitan` package may import. The word "Yomitan" appears in this package,
 * the source-language engines' enrichment call sites, and the settings page —
 * nowhere else. UI consumes plain model fields (e.g. `Headword.pitch`).
 * Future data types (terms, kanji) add a table + an ingestor + a typed
 * query method HERE — never a new store class with its own lifecycle.
 *
 * Storage: one SQLite DB (`noBackupFilesDir/yomitan/yomitan.sqlite`) holding
 * every derived table, ingested from the zips [YomitanDictionaryStore] keeps
 * whole. The DB is disposable — it is rebuilt from the zips whenever the
 * schema version bumps or the registry and `ingested_dicts` disagree
 * ([reconcile]). Conflicts between dictionaries resolve by the per-section
 * priority order the user set on the Yomitan settings page.
 */
object YomitanDataStore {

    private const val TAG = "YomitanData"

    /** Bump to force a drop-and-reingest of all derived tables on next use.
     *  Flattening-rule changes need a bump too — flattened text is baked
     *  into the term rows at ingest. */
    private const val SCHEMA_VERSION = 6

    /** The source language imported data currently serves: all consumers
     *  are the Japanese engine, so the capability cache filters
     *  dictionaries to JA-source packs at build. */
    private const val CONSUMING_SOURCE_LANG = "ja"

    private val TERM_BANK = Regex("""term_bank_\d+\.json""")
    private val TAG_BANK = Regex("""tag_bank_\d+\.json""")
    private val TERM_META_BANK = Regex("""term_meta_bank_\d+\.json""")
    private val KANJI_BANK = Regex("""kanji_bank_\d+\.json""")
    private val KANJI_META_BANK = Regex("""kanji_meta_bank_\d+\.json""")

    /** Guards DB open/ingest/purge and [cache] (re)builds. Reads go through
     *  [ready] which only takes the lock until initialized. */
    private val mutex = Mutex()

    private var db: SQLiteDatabase? = null

    /** Registry-derived state for fast query gating; null until first use or
     *  after [invalidate]. */
    @Volatile
    private var cache: CapabilityCache? = null

    private class CapabilityCache(
        /** PITCH_ACCENT section's dict ids, priority order. Empty → no pitch
         *  capability installed; pitch queries return immediately. */
        val pitchPriority: List<String>,
        /** FREQUENCY section's (dict id, chip label) in display order, where
         *  the label is the user alias when set, else the title. Empty → no
         *  frequency capability installed; queries return immediately. */
        val freqDicts: List<Pair<String, String>>,
        /** KANJI section's dictionaries in priority order — first dict with
         *  a character wins its whole entry. */
        val kanjiDicts: List<KanjiDictMeta>,
        /** KANJI_FREQUENCY section's (dict id, chip label) in display
         *  order; same show-all semantics as [freqDicts]. */
        val kanjiFreqDicts: List<Pair<String, String>>,
        /** TERMS section's (dict id, group label) in display order — every
         *  dict with definitions for a word contributes a group. */
        val termDicts: List<Pair<String, String>>,
        /** User toggle: only the highest-priority TERMS dict with results
         *  contributes its group (see [TermMerge.merge]). */
        val termsSingleDictionary: Boolean,
    )

    /** Result of [termSensesFor]: the per-dictionary definition groups in
     *  display order, plus the reading the lookup resolved to —
     *  the caller's reading when it supplied one, else the first matching
     *  row's stored reading (what entry synthesis needs for a word the
     *  built-in pack lacks). */
    data class TermLookup(
        val groups: List<ImportedSenseGroup>,
        val resolvedReading: String?,
        /** Single-dictionary mode with an imported group winning: the
         *  built-in pack counts as the lowest-priority source, so its
         *  senses must be excluded by the caller. False whenever [groups]
         *  is empty — the pack is then the dictionary that "has results". */
        val suppressesPackSenses: Boolean = false,
    )

    private class KanjiDictMeta(
        val id: String,
        /** index.json targetLanguage; null when undeclared (treated as "en"). */
        val targetLanguage: String?,
        /** Whether the dict ever populates the onyomi field. Dicts that
         *  never do (JPDB Kanji's usage-ranked single list) don't follow
         *  the on/kun convention — their readings render as one neutral
         *  combined line. Derived from ingested rows at cache build. */
        val splitsReadings: Boolean,
    )

    // ── Public read API ─────────────────────────────────────────────────

    /**
     * Batched pitch lookup. [pairs] are (term, reading) as they appear on
     * dictionary headwords — readings are normalized internally (katakana →
     * hiragana), and the result map is keyed by the pairs AS PASSED. Each
     * value is the winning dictionary's downstep variants in stored order.
     * Returns empty immediately when no pitch dictionary is installed.
     */
    suspend fun pitchFor(
        ctx: Context,
        pairs: Collection<Pair<String, String>>,
    ): Map<Pair<String, String>, List<Int>> = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx)
        if (caps.pitchPriority.isEmpty()) return@withContext emptyMap()

        // One query over the distinct terms; reading filtering + priority
        // resolution happen in memory (lookups carry a handful of terms).
        val terms = pairs.map { it.first }.distinct()
        // rows[term to normalizedReading] = dictId -> ordered downsteps
        val rows = HashMap<Pair<String, String>, HashMap<String, MutableList<Int>>>()
        terms.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT term, reading, dict_id, downstep FROM pitch " +
                    "WHERE term IN ($placeholders) ORDER BY variant",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(0) to c.getString(1)
                    rows.getOrPut(key) { HashMap() }
                        .getOrPut(c.getString(2)) { mutableListOf() }
                        .add(c.getInt(3))
                }
            }
        }
        if (rows.isEmpty()) return@withContext emptyMap()

        buildMap {
            for (pair in pairs) {
                val byDict = rows[pair.first to Deinflector.katakanaToHiragana(pair.second)]
                    ?: continue
                val winner = caps.pitchPriority.firstOrNull { byDict.containsKey(it) }
                    ?: continue
                put(pair, byDict.getValue(winner).distinct())
            }
        }
    }

    /**
     * Batched frequency lookup. [pairs] are (term, reading) as on dictionary
     * headwords; readings are normalized internally and the result map is
     * keyed by the pairs AS PASSED. Unlike pitch (first dictionary wins),
     * frequency returns one [FrequencyTag] per FREQUENCY-section dictionary
     * that has data — each is an independent data point — in the section's
     * display order. A dictionary's multiple values for one pair are joined
     * into a single tag. Returns empty immediately when no frequency
     * dictionary is installed.
     */
    suspend fun frequencyFor(
        ctx: Context,
        pairs: Collection<Pair<String, String>>,
    ): Map<Pair<String, String>, List<FrequencyTag>> = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx)
        if (caps.freqDicts.isEmpty()) return@withContext emptyMap()

        // rows[term] = (readingOrNull, dictId, display) in rowid (bank) order;
        // a NULL reading means the datum applies to every reading of the term.
        val rows = HashMap<String, MutableList<Triple<String?, String, String>>>()
        pairs.map { it.first }.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT term, reading, dict_id, display FROM frequency " +
                    "WHERE term IN ($placeholders) ORDER BY rowid",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    rows.getOrPut(c.getString(0)) { mutableListOf() }
                        .add(Triple(c.getString(1), c.getString(2), c.getString(3)))
                }
            }
        }
        if (rows.isEmpty()) return@withContext emptyMap()

        buildMap {
            for (pair in pairs) {
                val candidates = rows[pair.first] ?: continue
                val normalized = Deinflector.katakanaToHiragana(pair.second)
                // dictId -> first-seen-order distinct displays
                val byDict = HashMap<String, LinkedHashSet<String>>()
                for ((reading, dictId, display) in candidates) {
                    if (reading == null || reading == normalized) {
                        byDict.getOrPut(dictId) { LinkedHashSet() }.add(display)
                    }
                }
                val tags = caps.freqDicts.mapNotNull { (dictId, label) ->
                    byDict[dictId]?.let { FrequencyTag(label, it.joinToString(" · ")) }
                }
                if (tags.isNotEmpty()) put(pair, tags)
            }
        }
    }

    /**
     * Batched kanji-content lookup. Per character, the first KANJI-section
     * dictionary with an entry wins the WHOLE entry (no per-field mixing
     * across imports — the engine's merge against the built-in KANJIDIC2
     * floor handles per-field fallback). Returns empty immediately when no
     * kanji dictionary is installed.
     */
    suspend fun kanjiFor(
        ctx: Context,
        chars: Collection<Char>,
    ): Map<Char, ImportedKanji> = withContext(Dispatchers.IO) {
        if (chars.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx)
        if (caps.kanjiDicts.isEmpty()) return@withContext emptyMap()

        // rows[character] = dictId -> (onyomi, kunyomi, encodedMeanings)
        val rows = HashMap<String, HashMap<String, Triple<String, String, String>>>()
        chars.map { it.toString() }.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT character, dict_id, onyomi, kunyomi, meanings FROM kanji " +
                    "WHERE character IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    rows.getOrPut(c.getString(0)) { HashMap() }
                        .putIfAbsent(c.getString(1), Triple(c.getString(2), c.getString(3), c.getString(4)))
                }
            }
        }
        if (rows.isEmpty()) return@withContext emptyMap()

        buildMap {
            for (char in chars) {
                val byDict = rows[char.toString()] ?: continue
                val winner = caps.kanjiDicts.firstOrNull { byDict.containsKey(it.id) } ?: continue
                val (onyomi, kunyomi, meanings) = byDict.getValue(winner.id)
                val on = KanjiData.splitReadings(onyomi)
                val kun = KanjiData.splitReadings(kunyomi)
                put(
                    char,
                    ImportedKanji(
                        meanings = KanjiData.decodeMeanings(meanings),
                        onReadings = if (winner.splitsReadings) on else emptyList(),
                        kunReadings = if (winner.splitsReadings) kun else emptyList(),
                        meaningsLang = winner.targetLanguage ?: "en",
                        // Non-splitting dicts get their list back as-is —
                        // labelling it KUN would be a lie.
                        combinedReadings = if (winner.splitsReadings) emptyList() else on + kun,
                    ),
                )
            }
        }
    }

    /**
     * Batched kanji-frequency lookup — [frequencyFor] semantics per
     * character: one [FrequencyTag] per KANJI_FREQUENCY-section dictionary
     * with data, all of them, in section order; per-dict values joined.
     */
    suspend fun kanjiFrequencyFor(
        ctx: Context,
        chars: Collection<Char>,
    ): Map<Char, List<FrequencyTag>> = withContext(Dispatchers.IO) {
        if (chars.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx)
        if (caps.kanjiFreqDicts.isEmpty()) return@withContext emptyMap()

        // rows[character] = (dictId, display) in rowid (bank) order.
        val rows = HashMap<String, MutableList<Pair<String, String>>>()
        chars.map { it.toString() }.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT character, dict_id, display FROM kanji_frequency " +
                    "WHERE character IN ($placeholders) ORDER BY rowid",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    rows.getOrPut(c.getString(0)) { mutableListOf() }
                        .add(c.getString(1) to c.getString(2))
                }
            }
        }
        if (rows.isEmpty()) return@withContext emptyMap()

        buildMap {
            for (char in chars) {
                val candidates = rows[char.toString()] ?: continue
                val byDict = HashMap<String, LinkedHashSet<String>>()
                for ((dictId, display) in candidates) {
                    byDict.getOrPut(dictId) { LinkedHashSet() }.add(display)
                }
                val tags = caps.kanjiFreqDicts.mapNotNull { (dictId, label) ->
                    byDict[dictId]?.let { FrequencyTag(label, it.joinToString(" · ")) }
                }
                if (tags.isNotEmpty()) put(char, tags)
            }
        }
    }

    /** True when at least one TERMS dictionary is installed — lets the JA
     *  engine skip its whole candidate loop (one probe instead of a no-op
     *  query per deinflection candidate). */
    suspend fun hasTermDictionaries(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        ready(ctx).second.termDicts.isNotEmpty()
    }

    /**
     * Imported-term definition lookup for one candidate form. Every
     * TERMS-section dictionary with definitions contributes a group, in
     * section order; within a dict, entries sort by their bank score
     * (descending). A supplied [reading] is a hard disambiguator (see
     * [TermMerge.merge] — homograph content must not attach to the wrong
     * word). Returns a [TermLookup] with empty groups when nothing matches
     * (or no term dictionary is installed).
     */
    suspend fun termSensesFor(
        ctx: Context,
        term: String,
        reading: String?,
    ): TermLookup = withContext(Dispatchers.IO) {
        val empty = TermLookup(emptyList(), reading)
        if (term.isEmpty()) return@withContext empty
        val (database, caps) = ready(ctx)
        if (caps.termDicts.isEmpty()) return@withContext empty

        val rows = mutableListOf<TermMerge.Row>()
        database.rawQuery(
            "SELECT dict_id, reading, score, defs, pos FROM term WHERE term = ? ORDER BY rowid",
            arrayOf(term),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    TermMerge.Row(
                        dictId = c.getString(0),
                        reading = c.getString(1),
                        score = c.getDouble(2),
                        defs = KanjiData.decodeMeanings(c.getString(3)),
                        pos = c.getString(4),
                    )
                )
            }
        }
        if (rows.isEmpty()) return@withContext empty
        TermMerge.merge(
            rows = rows,
            dictOrder = caps.termDicts,
            normalizedReading = reading?.let(Deinflector::katakanaToHiragana),
            normalizedTerm = Deinflector.katakanaToHiragana(term),
            singleDictionary = caps.termsSingleDictionary,
        )
    }

    /**
     * Batch existence gate for the tokenizer's n-gram phrase re-glob:
     * returns the subset of [candidates] present in an ENABLED terms
     * dictionary. The dict allow-list matters — it must be the same set
     * [termSensesFor] surfaces, or a disabled/orphan dict could glob a
     * phrase whose subsequent lookup returns nothing and the underlying
     * tokens would vanish from the Words panel.
     */
    suspend fun batchTermsExist(
        ctx: Context,
        candidates: Set<String>,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptySet()
        val (database, caps) = ready(ctx)
        if (caps.termDicts.isEmpty()) return@withContext emptySet()
        batchTermsExistQuery(database, candidates, caps.termDicts.map { it.first })
    }

    /** SQL core of [batchTermsExist], separated so tests can drive it
     *  against a fixture database without the singleton's reconcile path. */
    internal fun batchTermsExistQuery(
        database: SQLiteDatabase,
        candidates: Set<String>,
        enabledDictIds: List<String>,
    ): Set<String> {
        if (enabledDictIds.isEmpty()) return emptySet()
        // The allow-list filters in memory, not in SQL: binds stay at the
        // candidate chunk size (≤500, under SQLite's 999-parameter cap)
        // no matter how many dictionaries are enabled.
        val enabled = enabledDictIds.toHashSet()
        val found = mutableSetOf<String>()
        for (chunk in candidates.chunked(500)) {
            val termPlaceholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT term, dict_id FROM term WHERE term IN ($termPlaceholders)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) in enabled) found.add(c.getString(0))
                }
            }
        }
        return found
    }

    // ── Lifecycle hooks (called by YomitanDictionaryStore) ──────────────

    /** Eagerly ingests a freshly imported dictionary so its data is queryable
     *  before the first lookup. Ingest failures are logged, not surfaced —
     *  [reconcile] retries on next use. */
    suspend fun onDictImported(ctx: Context, dictionary: YomitanDictionary) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ingestLocked(ctx, openDb(ctx), dictionary)
                } catch (e: Exception) {
                    Log.w(TAG, "eager ingest failed for ${dictionary.id}", e)
                } finally {
                    // The cache is REGISTRY-derived and the registry changed
                    // the moment the import committed — stale regardless of
                    // ingest outcome. Unconditional, matching onDictDeleted;
                    // leaving it inside the try would freeze a pre-import
                    // "no pitch installed" gate until process restart.
                    cache = null
                }
            }
        }

    /** Purges a deleted dictionary's rows across all derived tables. */
    suspend fun onDictDeleted(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                purgeLocked(openDb(ctx), id)
            } catch (e: Exception) {
                Log.w(TAG, "purge failed for $id", e)
            }
            cache = null
        }
    }

    /** Drops cached registry-derived state (priority orders, capability
     *  gates). Cheap; next query reloads. Called after reorders. */
    fun invalidate() {
        cache = null
    }

    // ── Init / reconcile ────────────────────────────────────────────────

    private suspend fun ready(ctx: Context): Pair<SQLiteDatabase, CapabilityCache> {
        // Fast path once initialized; cache only goes null on invalidate/hooks.
        cache?.let { caps -> db?.let { return it to caps } }
        return mutex.withLock {
            val database = openDb(ctx)
            val caps = cache ?: run {
                val registry = YomitanDictionaryStore.load(ctx)
                reconcileLocked(ctx, database, registry)
                // Per-dict on/kun convention check (post-reconcile, so rows
                // are settled): a dict that never fills onyomi ships a
                // combined readings list, not the split.
                val splitsByDict = HashMap<String, Boolean>()
                database.rawQuery(
                    "SELECT dict_id, MAX(CASE WHEN onyomi != '' THEN 1 ELSE 0 END) " +
                        "FROM kanji GROUP BY dict_id",
                    null,
                ).use { c ->
                    while (c.moveToNext()) splitsByDict[c.getString(0)] = c.getInt(1) == 1
                }
                // Queries gate on source-language match here, NOT in the
                // registry: the settings page manages all imports regardless
                // of language; only lookups filter. Every consumer today is
                // the Japanese engine, so the match target is the constant —
                // if another engine ever consumes imported data, this
                // becomes a query parameter / per-language cache instead.
                fun ordered(category: YomitanCategory) = registry.orderedFor(category)
                    .filter { it.matchesSourceLanguage(CONSUMING_SOURCE_LANG) }
                CapabilityCache(
                    pitchPriority = ordered(YomitanCategory.PITCH_ACCENT)
                        .map { it.id },
                    freqDicts = ordered(YomitanCategory.FREQUENCY)
                        .map { it.id to (it.alias ?: it.title) },
                    kanjiDicts = ordered(YomitanCategory.KANJI)
                        .map { KanjiDictMeta(it.id, it.targetLanguage, splitsByDict[it.id] ?: true) },
                    kanjiFreqDicts = ordered(YomitanCategory.KANJI_FREQUENCY)
                        .map { it.id to (it.alias ?: it.title) },
                    termDicts = ordered(YomitanCategory.TERMS)
                        .map { it.id to (it.alias ?: it.title) },
                    termsSingleDictionary = registry.termsSingleDictionary,
                ).also { cache = it }
            }
            database to caps
        }
    }

    private fun openDb(ctx: Context): SQLiteDatabase {
        db?.let { return it }
        val file = File(YomitanDictionaryStore.rootDir(ctx), "yomitan.sqlite")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        // Derived data: on any schema change, nuke and let reconcile re-ingest
        // from the stored zips rather than migrating.
        val version = database.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (version != SCHEMA_VERSION) {
            database.execSQL("DROP TABLE IF EXISTS pitch")
            database.execSQL("DROP TABLE IF EXISTS frequency")
            database.execSQL("DROP TABLE IF EXISTS kanji")
            database.execSQL("DROP TABLE IF EXISTS kanji_frequency")
            database.execSQL("DROP TABLE IF EXISTS term")
            database.execSQL("DROP TABLE IF EXISTS ingested_dicts")
            database.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS pitch (" +
                "dict_id TEXT NOT NULL, term TEXT NOT NULL, reading TEXT NOT NULL, " +
                "variant INTEGER NOT NULL, downstep INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_pitch_term ON pitch(term, reading)"
        )
        // [reading] NULL = the datum applies to every reading of [term].
        // [value] is the sortable number when the source shape carries one
        // (NULL for pure-string data) — stored for future ranking use only.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS frequency (" +
                "dict_id TEXT NOT NULL, term TEXT NOT NULL, reading TEXT, " +
                "display TEXT NOT NULL, value REAL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_freq_term ON frequency(term)"
        )
        // on/kun keep the bank's raw space-separated form (split at query
        // time); meanings are a JSON array — arbitrary strings would collide
        // with any join separator.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS kanji (" +
                "dict_id TEXT NOT NULL, character TEXT NOT NULL, " +
                "onyomi TEXT NOT NULL, kunyomi TEXT NOT NULL, meanings TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_kanji_char ON kanji(character)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS kanji_frequency (" +
                "dict_id TEXT NOT NULL, character TEXT NOT NULL, " +
                "display TEXT NOT NULL, value REAL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_kanji_freq_char ON kanji_frequency(character)"
        )
        // One row per term_bank ENTRY (a term can carry several entries
        // with their own scores). [reading] is normalized hiragana; a blank
        // bank reading means "same as term" and is stored as such. [defs]
        // is a JSON array of flattened definition strings; [pos] is the
        // entry's tag_bank-resolved part-of-speech names (space-joined,
        // '' when untagged).
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS term (" +
                "dict_id TEXT NOT NULL, term TEXT NOT NULL, reading TEXT NOT NULL, " +
                "score REAL NOT NULL, defs TEXT NOT NULL, pos TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_term_term ON term(term)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS ingested_dicts (dict_id TEXT PRIMARY KEY)"
        )
        db = database
        return database
    }

    /** Ingests registry dicts missing from `ingested_dicts`; purges rows of
     *  dicts no longer in the registry. */
    private fun reconcileLocked(ctx: Context, database: SQLiteDatabase, registry: YomitanRegistry) {
        val ingested = mutableSetOf<String>()
        database.rawQuery("SELECT dict_id FROM ingested_dicts", null).use { c ->
            while (c.moveToNext()) ingested += c.getString(0)
        }
        val registryIds = registry.dictionaries.map { it.id }.toSet()
        for (orphan in ingested - registryIds) {
            Log.i(TAG, "reconcile: purging orphan $orphan")
            purgeLocked(database, orphan)
        }
        for (dict in registry.dictionaries) {
            if (dict.id in ingested) continue
            try {
                ingestLocked(ctx, database, dict)
            } catch (e: Exception) {
                // Leave un-marked so the next reconcile retries; the
                // transaction in ingestLocked keeps the DB consistent.
                Log.w(TAG, "reconcile: ingest failed for ${dict.id}", e)
            }
        }
    }

    private fun purgeLocked(database: SQLiteDatabase, dictId: String) {
        database.beginTransaction()
        try {
            database.delete("pitch", "dict_id = ?", arrayOf(dictId))
            database.delete("frequency", "dict_id = ?", arrayOf(dictId))
            database.delete("kanji", "dict_id = ?", arrayOf(dictId))
            database.delete("kanji_frequency", "dict_id = ?", arrayOf(dictId))
            database.delete("term", "dict_id = ?", arrayOf(dictId))
            database.delete("ingested_dicts", "dict_id = ?", arrayOf(dictId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    // ── Ingestors (one per data type) ───────────────────────────────────

    /** Ingests everything this store derives from [dictionary], atomically:
     *  delete-then-insert inside one transaction, marking `ingested_dicts`
     *  last, so a mid-ingest crash can't half-apply or double-apply. */
    private fun ingestLocked(ctx: Context, database: SQLiteDatabase, dictionary: YomitanDictionary) {
        database.beginTransaction()
        try {
            database.delete("pitch", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("frequency", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("kanji", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("kanji_frequency", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("term", "dict_id = ?", arrayOf(dictionary.id))
            if (YomitanCategory.PITCH_ACCENT in dictionary.categories) {
                ingestPitch(ctx, database, dictionary.id)
            }
            if (YomitanCategory.FREQUENCY in dictionary.categories) {
                ingestFreq(ctx, database, dictionary.id)
            }
            if (YomitanCategory.KANJI in dictionary.categories) {
                ingestKanji(ctx, database, dictionary.id)
            }
            if (YomitanCategory.KANJI_FREQUENCY in dictionary.categories) {
                ingestKanjiFreq(ctx, database, dictionary.id)
            }
            if (YomitanCategory.TERMS in dictionary.categories) {
                ingestTerms(ctx, database, dictionary.id)
            }
            database.execSQL(
                "INSERT OR REPLACE INTO ingested_dicts (dict_id) VALUES (?)",
                arrayOf(dictionary.id),
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Streams `term_meta_bank_*.json` mode-`pitch` entries from the stored
     *  zip into the `pitch` table. Integer downstep positions only — the
     *  schema's H/L string patterns are skipped (logged once per file). */
    private fun ingestPitch(ctx: Context, database: SQLiteDatabase, dictId: String) {
        val zipFile = YomitanDictionaryStore.zipFile(ctx, dictId)
        val insert = database.compileStatement(
            "INSERT INTO pitch (dict_id, term, reading, variant, downstep) VALUES (?, ?, ?, ?, ?)"
        )
        var rows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !TERM_META_BANK.matches(entry.name)) continue
                var skippedPatterns = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray()
                            val term = reader.nextString()
                            val mode = reader.nextString()
                            if (mode != "pitch") {
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                                continue
                            }
                            var reading = term
                            val downsteps = mutableListOf<Int>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "reading" -> reading = reader.nextString()
                                    "pitches" -> {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            reader.beginObject()
                                            while (reader.hasNext()) {
                                                when (reader.nextName()) {
                                                    "position" ->
                                                        if (reader.peek() == JsonToken.NUMBER) {
                                                            downsteps += reader.nextInt()
                                                        } else {
                                                            skippedPatterns++
                                                            reader.skipValue()
                                                        }
                                                    else -> reader.skipValue()
                                                }
                                            }
                                            reader.endObject()
                                        }
                                        reader.endArray()
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()

                            val normalized = Deinflector.katakanaToHiragana(reading)
                            downsteps.forEachIndexed { variant, downstep ->
                                insert.bindString(1, dictId)
                                insert.bindString(2, term)
                                insert.bindString(3, normalized)
                                insert.bindLong(4, variant.toLong())
                                insert.bindLong(5, downstep.toLong())
                                insert.executeInsert()
                                rows++
                            }
                        }
                        reader.endArray()
                    }
                }
                if (skippedPatterns > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedPatterns H/L-pattern positions")
                }
            }
        }
        Log.i(TAG, "ingested $rows pitch rows for $dictId")
    }

    /** Streams `term_meta_bank_*.json` mode-`freq` entries from the stored
     *  zip into the `frequency` table. The data element's four schema shapes
     *  are handled by [FreqData]; unparseable entries are skipped (logged
     *  once per file). */
    private fun ingestFreq(ctx: Context, database: SQLiteDatabase, dictId: String) {
        val zipFile = YomitanDictionaryStore.zipFile(ctx, dictId)
        val insert = database.compileStatement(
            "INSERT INTO frequency (dict_id, term, reading, display, value) VALUES (?, ?, ?, ?, ?)"
        )
        var rows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !TERM_META_BANK.matches(entry.name)) continue
                var skippedEntries = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray()
                            val term = reader.nextString()
                            val mode = reader.nextString()
                            if (mode != "freq") {
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                                continue
                            }
                            val row = FreqData.parse(reader)
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                            if (row == null) {
                                skippedEntries++
                                continue
                            }

                            insert.bindString(1, dictId)
                            insert.bindString(2, term)
                            row.reading
                                ?.let { insert.bindString(3, Deinflector.katakanaToHiragana(it)) }
                                ?: insert.bindNull(3)
                            insert.bindString(4, row.display)
                            row.value?.let { insert.bindDouble(5, it) } ?: insert.bindNull(5)
                            insert.executeInsert()
                            rows++
                        }
                        reader.endArray()
                    }
                }
                if (skippedEntries > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedEntries unparseable freq entries")
                }
            }
        }
        Log.i(TAG, "ingested $rows frequency rows for $dictId")
    }

    /** Streams `kanji_bank_*.json` entries from the stored zip into the
     *  `kanji` table. Entries are fixed-position 6-element arrays
     *  [char, onyomi, kunyomi, tags, meanings[], stats{}] — tags and stats
     *  are discarded (stats keys are dictionary-specific; the built-in
     *  KANJIDIC2 stays the source for numeric stats). */
    private fun ingestKanji(ctx: Context, database: SQLiteDatabase, dictId: String) {
        val zipFile = YomitanDictionaryStore.zipFile(ctx, dictId)
        val insert = database.compileStatement(
            "INSERT INTO kanji (dict_id, character, onyomi, kunyomi, meanings) VALUES (?, ?, ?, ?, ?)"
        )
        var rows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !KANJI_BANK.matches(entry.name)) continue
                var skippedEntries = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray()
                            val character = reader.nextString()
                            // Defensive peeks: a short or oddly-typed entry is
                            // skipped, never allowed to derail the stream.
                            var onyomi = ""
                            var kunyomi = ""
                            var meanings: List<String>? = null
                            var valid = true
                            if (reader.peek() == JsonToken.STRING) onyomi = reader.nextString() else valid = false
                            if (valid && reader.peek() == JsonToken.STRING) kunyomi = reader.nextString() else valid = false
                            if (valid && reader.peek() == JsonToken.STRING) reader.skipValue() else valid = false // tags
                            if (valid && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                val list = mutableListOf<String>()
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    if (reader.peek() == JsonToken.STRING) list.add(reader.nextString())
                                    else reader.skipValue()
                                }
                                reader.endArray()
                                meanings = list
                            } else {
                                valid = false
                            }
                            while (reader.hasNext()) reader.skipValue() // stats + extras
                            reader.endArray()
                            if (!valid || character.isEmpty()) {
                                skippedEntries++
                                continue
                            }

                            insert.bindString(1, dictId)
                            insert.bindString(2, character)
                            insert.bindString(3, onyomi)
                            insert.bindString(4, kunyomi)
                            insert.bindString(5, KanjiData.encodeMeanings(meanings.orEmpty()))
                            insert.executeInsert()
                            rows++
                        }
                        reader.endArray()
                    }
                }
                if (skippedEntries > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedEntries malformed kanji entries")
                }
            }
        }
        Log.i(TAG, "ingested $rows kanji rows for $dictId")
    }

    /** Streams `kanji_meta_bank_*.json` mode-`freq` entries into the
     *  `kanji_frequency` table. The data element shares the term-frequency
     *  shapes minus the reading wrapper, so [FreqData] handles it (any
     *  stray reading qualifier is ignored — kanji have no reading
     *  dimension). */
    private fun ingestKanjiFreq(ctx: Context, database: SQLiteDatabase, dictId: String) {
        val zipFile = YomitanDictionaryStore.zipFile(ctx, dictId)
        val insert = database.compileStatement(
            "INSERT INTO kanji_frequency (dict_id, character, display, value) VALUES (?, ?, ?, ?)"
        )
        var rows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !KANJI_META_BANK.matches(entry.name)) continue
                var skippedEntries = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray()
                            val character = reader.nextString()
                            val mode = reader.nextString()
                            if (mode != "freq") {
                                while (reader.hasNext()) reader.skipValue()
                                reader.endArray()
                                continue
                            }
                            val row = FreqData.parse(reader)
                            while (reader.hasNext()) reader.skipValue()
                            reader.endArray()
                            if (row == null || character.isEmpty()) {
                                skippedEntries++
                                continue
                            }

                            insert.bindString(1, dictId)
                            insert.bindString(2, character)
                            insert.bindString(3, row.display)
                            row.value?.let { insert.bindDouble(4, it) } ?: insert.bindNull(4)
                            insert.executeInsert()
                            rows++
                        }
                        reader.endArray()
                    }
                }
                if (skippedEntries > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedEntries unparseable kanji freq entries")
                }
            }
        }
        Log.i(TAG, "ingested $rows kanji frequency rows for $dictId")
    }

    /** Streams `term_bank_*.json` entries into the `term` table. Entries
     *  are 8-element arrays [term, reading, defTags, rules, score,
     *  glossary[], sequence, termTags]; glossaries flatten through
     *  [TermGlossary] (headword echoes stripped) and entries with no
     *  surviving text (image-only, redirect-only, echo-only) are skipped.
     *  Per-entry parsing is defensive — a malformed entry skips, never
     *  aborts the dictionary (which would loop reconcile retries forever
     *  on a dict that can't ever succeed). */
    private fun ingestTerms(ctx: Context, database: SQLiteDatabase, dictId: String) {
        val zipFile = YomitanDictionaryStore.zipFile(ctx, dictId)
        val insert = database.compileStatement(
            "INSERT INTO term (dict_id, term, reading, score, defs, pos) VALUES (?, ?, ?, ?, ?, ?)"
        )
        var rows = 0
        ZipFile(zipFile).use { zip ->
            // tag_bank pass first: which tag names mean part-of-speech.
            // (Zip entry order is arbitrary, so this can't ride the term
            // pass.)
            val posTags = collectPosTags(zip)
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !TERM_BANK.matches(entry.name)) continue
                var skippedEntries = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val parsed = TermEntry.parse(reader)
                            if (parsed == null) {
                                skippedEntries++
                                continue
                            }
                            val defs = parsed.defs.mapNotNull {
                                TermGlossary.stripHeadwordEcho(
                                    it, parsed.term, parsed.reading.ifBlank { parsed.term },
                                )
                            }
                            if (parsed.term.isEmpty() || defs.isEmpty()) {
                                skippedEntries++
                                continue
                            }

                            val reading =
                                Deinflector.katakanaToHiragana(parsed.reading.ifBlank { parsed.term })
                            val pos = parsed.defTags.split(' ')
                                .filter { it.isNotEmpty() && it in posTags }
                                .joinToString(" ")
                            insert.bindString(1, dictId)
                            insert.bindString(2, parsed.term)
                            insert.bindString(3, reading)
                            insert.bindDouble(4, parsed.score)
                            insert.bindString(5, KanjiData.encodeMeanings(defs))
                            insert.bindString(6, pos)
                            insert.executeInsert()
                            rows++
                        }
                        reader.endArray()
                    }
                }
                if (skippedEntries > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedEntries text-less/malformed term entries")
                }
            }
        }
        Log.i(TAG, "ingested $rows term rows for $dictId")
    }

    /** Tag names the dictionary's tag banks declare with category
     *  "partOfSpeech" — the ecosystem convention (JMdict, Jitendex).
     *  tag_bank entries are [name, category, order, notes, score]. */
    private fun collectPosTags(zip: ZipFile): Set<String> {
        val posTags = mutableSetOf<String>()
        for (entry in zip.entries()) {
            if (entry.name.contains('/') || !TAG_BANK.matches(entry.name)) continue
            zip.getInputStream(entry).use { input ->
                JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                            reader.skipValue()
                            continue
                        }
                        reader.beginArray()
                        val name =
                            if (reader.peek() == JsonToken.STRING) reader.nextString() else ""
                        val category =
                            if (reader.peek() == JsonToken.STRING) reader.nextString() else ""
                        while (reader.hasNext()) reader.skipValue()
                        reader.endArray()
                        if (name.isNotEmpty() && category == "partOfSpeech") posTags += name
                    }
                    reader.endArray()
                }
            }
        }
        return posTags
    }
}
