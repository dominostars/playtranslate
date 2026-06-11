package com.playtranslate.yomitan

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.model.FrequencyTag
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

    /** Bump to force a drop-and-reingest of all derived tables on next use. */
    private const val SCHEMA_VERSION = 2

    private val TERM_META_BANK = Regex("""term_meta_bank_\d+\.json""")

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
                CapabilityCache(
                    pitchPriority = registry.orderedFor(YomitanCategory.PITCH_ACCENT)
                        .map { it.id },
                    freqDicts = registry.orderedFor(YomitanCategory.FREQUENCY)
                        .map { it.id to (it.alias ?: it.title) },
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
            if (YomitanCategory.PITCH_ACCENT in dictionary.categories) {
                ingestPitch(ctx, database, dictionary.id)
            }
            if (YomitanCategory.FREQUENCY in dictionary.categories) {
                ingestFreq(ctx, database, dictionary.id)
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
}
