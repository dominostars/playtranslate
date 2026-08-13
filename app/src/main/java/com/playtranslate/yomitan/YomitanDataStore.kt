package com.playtranslate.yomitan

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.core.database.sqlite.transaction
import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.model.FrequencyTag
import com.playtranslate.model.ImportedKanji
import com.playtranslate.model.ImportedSenseGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * App-wide read facade over the runtime data derived from imported Yomitan
 * dictionaries.
 *
 * ARCHITECTURE RULE: outside the `yomitan` package, the only importable Yomitan
 * symbols are this store (the data facade — UI consumes plain model fields like
 * `Headword.pitch`), the source-language engines' enrichment call sites, the
 * settings page, and the launch-time auto-update trigger
 * (`YomitanAutoUpdateOrchestrator.maybeRun`, wired from `MainActivity.onResume` —
 * fire-and-forget orchestration, not data-type leakage). Nowhere else.
 * Future DATA types (terms, kanji) add a table + an ingestor + a typed query
 * method HERE — never a new store class with its own lifecycle.
 *
 * Storage: one SQLite DB (`noBackupFilesDir/yomitan/yomitan.sqlite`) holding
 * every derived table, ingested at import time from the source file the
 * importer hands over (a temp zip or a collection dump — nothing is retained
 * afterwards; [YomitanDictionaryStore] keeps only each dictionary's
 * index.json). The DB is therefore the ONLY copy of a dictionary's data: on a
 * schema-version bump the tables drop and every registry entry with no
 * re-ingestable source becomes "outdated" ([outdatedDictIds]) until the user
 * re-imports it (or the auto-updater re-downloads a URL-bearing deck).
 * Conflicts between dictionaries resolve by the per-section priority order
 * the user set on the Yomitan settings page.
 */
object YomitanDataStore {

    private const val TAG = "YomitanData"

    /** Bump to drop all derived tables on next use. Flattening-rule changes
     *  need a bump too — flattened text is baked into the term rows at
     *  ingest. BUMPING IS EXPENSIVE FOR USERS: source zips are not retained,
     *  so every installed dictionary goes "outdated" (warning rows in
     *  settings) until the user re-imports it or the auto-updater re-downloads
     *  a URL-bearing deck. v7: term ingest gates the JA-only headword-echo
     *  strip on source language, so non-JA dicts keep their leading-headword
     *  text. v8: structured-content retention — `term_sc` keeps each
     *  structured glossary's raw JSON (deflated) beside the flat row, plus
     *  `media` and `dict_styles` for the styled renderer. NOTE the raw JSON
     *  is stored source-verbatim precisely so RENDERER changes never need a
     *  bump — only changes to what ingest stores do. */
    private const val SCHEMA_VERSION = 8

    private val TERM_BANK = Regex("""term_bank_\d+\.json""")
    private val TAG_BANK = Regex("""tag_bank_\d+\.json""")
    private val TERM_META_BANK = Regex("""term_meta_bank_\d+\.json""")
    private val KANJI_BANK = Regex("""kanji_bank_\d+\.json""")
    private val KANJI_META_BANK = Regex("""kanji_meta_bank_\d+\.json""")

    /** Media extensions ingested from a dictionary zip — Yomitan's importer
     *  whitelist (media type is derived from the extension, there as here)
     *  plus webp. Anything else in the zip is not renderable dictionary
     *  media and is skipped. */
    private val IMAGE_EXTENSIONS = setOf(
        "apng", "avif", "bmp", "gif", "ico", "cur", "jpg", "jpeg", "jfif",
        "pjpeg", "pjp", "png", "svg", "tif", "tiff", "webp",
    )

    /** Per-file cap for ingested media — a hostile zip can't balloon the DB
     *  through one entry (the importer's 3x-source disk guard bounds the
     *  total). Real dictionary graphics run tens of KB. */
    private const val MAX_MEDIA_FILE_BYTES = 10L * 1024 * 1024

    /** Cap for a dictionary's styles.css, mirroring the index.json cap's
     *  intent — a stylesheet is config, not payload. */
    private const val MAX_STYLES_BYTES = 512 * 1024

    /** Guards DB open/ingest/purge and [cache] (re)builds. Reads go through
     *  [ready] which only takes the lock until initialized. */
    private val mutex = Mutex()

    private var db: SQLiteDatabase? = null

    /** Per-consuming-language capability caches, keyed by the normalized
     *  primary subtag ("ja","zh","ko",…). Built under [mutex]; a
     *  ConcurrentHashMap so the fast path in [ready] reads without the lock.
     *  Empty until first use; cleared on [invalidate] and the import/delete
     *  hooks. */
    private val caches = java.util.concurrent.ConcurrentHashMap<String, CapabilityCache>()

    /** Language-INDEPENDENT init, guarded by [mutex]: the registry snapshot and
     *  the per-dict on/kun split, computed once after [reconcileLocked] and
     *  reused across every language key (so reconcile doesn't re-run per
     *  language). [registrySnapshot] is volatile because [invalidate] nulls it
     *  outside the lock; [splitsByDict] is only touched under the lock and is
     *  recomputed whenever the snapshot is rebuilt. */
    @Volatile
    private var registrySnapshot: YomitanRegistry? = null
    private var splitsByDict: Map<String, Boolean> = emptyMap()

    private class CapabilityCache(
        /** PITCH_ACCENT section's dict ids, priority order. Empty → no pitch
         *  capability installed; pitch queries return immediately. */
        val pitchPriority: List<String>,
        /** FREQUENCY section's (dict id, chip label) in display order, where
         *  the label is the user alias when set, else the title. Empty → no
         *  frequency capability installed; queries return immediately. */
        val freqDicts: List<Pair<String, String>>,
        /** Per-dictionary accent override (ARGB) keyed by dict id, across all
         *  categories; null entry → no override (default neutral rendering).
         *  Feeds freq chips, kanji-frequency text, and term-group titles. */
        val dictColors: Map<String, Int?>,
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
        /** Whether any enabled TERMS dict retained structured glossaries
         *  (`term_sc` rows) — the styled renderer's warm-up gate. */
        val hasStructuredTerms: Boolean,
        /** Enabled TERMS dicts' styles.css text keyed by dict id — parsed
         *  and scoped by the renderer, cached here so a lookup never
         *  re-reads it. */
        val dictStyles: Map<String, String>,
        /** User toggle (registry-wide): render retained structured content
         *  with dictionary styling on the WebView surfaces. OFF = flat
         *  tier everywhere, structured rows stay dormant. */
        val stylingEnabled: Boolean,
    )

    /** Renderer-facing slice of the capability cache — what a surface needs
     *  to decide styled-vs-flat and to style what it fetched. */
    class StylingCaps(
        val stylingActive: Boolean,
        val stylesByDict: Map<String, String>,
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
        sourceLanguage: String,
        pairs: Collection<Pair<String, String>>,
    ): Map<Pair<String, String>, List<Int>> = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx, sourceLanguage)
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
        sourceLanguage: String,
        pairs: Collection<Pair<String, String>>,
    ): Map<Pair<String, String>, List<FrequencyTag>> = withContext(Dispatchers.IO) {
        if (pairs.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx, sourceLanguage)
        if (caps.freqDicts.isEmpty()) return@withContext emptyMap()

        // rows[term] = (readingOrNull, dictId, display, valueOrNull) in rowid
        // (bank) order; a NULL reading means the datum applies to every reading
        // of the term. [value] is the dictionary's sortable number (NULL for
        // pure-string data) — surfaced for frequency-sort aggregation.
        data class FreqRow(
            val reading: String?,
            val dictId: String,
            val display: String,
            val value: Double?,
        )
        val rows = HashMap<String, MutableList<FreqRow>>()
        pairs.map { it.first }.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            database.rawQuery(
                "SELECT term, reading, dict_id, display, value FROM frequency " +
                    "WHERE term IN ($placeholders) ORDER BY rowid",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    rows.getOrPut(c.getString(0)) { mutableListOf() }
                        .add(FreqRow(
                            c.getString(1), c.getString(2), c.getString(3),
                            if (c.isNull(4)) null else c.getDouble(4),
                        ))
                }
            }
        }
        if (rows.isEmpty()) return@withContext emptyMap()

        buildMap {
            for (pair in pairs) {
                val candidates = rows[pair.first] ?: continue
                val normalized = Deinflector.katakanaToHiragana(pair.second)
                // dictId -> first-seen-order distinct displays, plus the first
                // non-null numeric value per dict (one rank per dict feeds the
                // frequency-sort harmonic mean).
                val byDict = HashMap<String, LinkedHashSet<String>>()
                val valueByDict = HashMap<String, Double>()
                for (row in candidates) {
                    if (row.reading == null || row.reading == normalized) {
                        byDict.getOrPut(row.dictId) { LinkedHashSet() }.add(row.display)
                        if (row.value != null && row.dictId !in valueByDict) {
                            valueByDict[row.dictId] = row.value
                        }
                    }
                }
                val tags = caps.freqDicts.mapNotNull { (dictId, label) ->
                    byDict[dictId]?.let {
                        FrequencyTag(
                            label, it.joinToString(" · "),
                            caps.dictColors[dictId], valueByDict[dictId],
                        )
                    }
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
        sourceLanguage: String,
        chars: Collection<Char>,
    ): Map<Char, ImportedKanji> = withContext(Dispatchers.IO) {
        if (chars.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx, sourceLanguage)
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
        sourceLanguage: String,
        chars: Collection<Char>,
    ): Map<Char, List<FrequencyTag>> = withContext(Dispatchers.IO) {
        if (chars.isEmpty()) return@withContext emptyMap()
        val (database, caps) = ready(ctx, sourceLanguage)
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
                    byDict[dictId]?.let {
                        FrequencyTag(label, it.joinToString(" · "), caps.dictColors[dictId])
                    }
                }
                if (tags.isNotEmpty()) put(char, tags)
            }
        }
    }

    /** True when at least one TERMS dictionary is installed — lets the JA
     *  engine skip its whole candidate loop (one probe instead of a no-op
     *  query per deinflection candidate). */
    suspend fun hasTermDictionaries(ctx: Context, sourceLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            ready(ctx, sourceLanguage).second.termDicts.isNotEmpty()
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
        sourceLanguage: String,
        term: String,
        reading: String?,
    ): TermLookup = withContext(Dispatchers.IO) {
        val empty = TermLookup(emptyList(), reading)
        if (term.isEmpty()) return@withContext empty
        val (database, caps) = ready(ctx, sourceLanguage)
        if (caps.termDicts.isEmpty()) return@withContext empty

        val rows = mutableListOf<TermMerge.Row>()
        // The LEFT JOIN's only contribution is whether a structured-glossary
        // sidecar row exists (rowid-keyed, so it's a primary-key probe per
        // row) — the blob itself is fetched later, and only by surfaces that
        // actually render styled content ([structuredGlossaries]).
        database.rawQuery(
            "SELECT t.dict_id, t.reading, t.score, t.defs, t.pos, t.rowid, " +
                "(sc.term_rowid IS NOT NULL) " +
                "FROM term t LEFT JOIN term_sc sc ON sc.term_rowid = t.rowid " +
                "WHERE t.term = ? ORDER BY t.rowid",
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
                        scRowid = c.getLong(5).takeIf { c.getInt(6) == 1 },
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
            dictColors = caps.dictColors,
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
        sourceLanguage: String,
        candidates: Set<String>,
    ): Set<String> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptySet()
        val (database, caps) = ready(ctx, sourceLanguage)
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

    /**
     * Styled-rendering capability for [sourceLanguage]'s surfaces:
     * [StylingCaps.stylingActive] is the one gate render code checks (user
     * toggle AND at least one enabled TERMS dict retained structured rows);
     * [StylingCaps.stylesByDict] is each enabled dict's raw styles.css for
     * the renderer to scope. Cheap after first call (capability cache).
     */
    suspend fun stylingFor(ctx: Context, sourceLanguage: String): StylingCaps =
        withContext(Dispatchers.IO) {
            val (_, caps) = ready(ctx, sourceLanguage)
            StylingCaps(
                stylingActive = caps.stylingEnabled && caps.hasStructuredTerms,
                stylesByDict = if (caps.stylingEnabled) caps.dictStyles else emptyMap(),
            )
        }

    /**
     * Batch fetch of retained structured glossaries by `term` rowid (the
     * [ImportedSense.scRowid] values a lookup carried out). Values are the
     * glossary array's raw JSON. Rows that are missing or fail inflation
     * are simply absent — the caller renders those senses flat.
     */
    suspend fun structuredGlossaries(
        ctx: Context,
        sourceLanguage: String,
        rowids: Collection<Long>,
    ): Map<Long, String> = withContext(Dispatchers.IO) {
        if (rowids.isEmpty()) return@withContext emptyMap()
        val (database, _) = ready(ctx, sourceLanguage)
        buildMap {
            rowids.distinct().chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                database.rawQuery(
                    "SELECT term_rowid, content FROM term_sc " +
                        "WHERE term_rowid IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        Zlib.inflate(c.getBlob(1))?.let {
                            put(c.getLong(0), it.toString(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
    }

    /** One dictionary media blob by its zip-relative [path] — the styled
     *  renderer's request-interception source. Null when absent (the image
     *  simply doesn't render). */
    suspend fun mediaBlob(
        ctx: Context,
        sourceLanguage: String,
        dictId: String,
        path: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val (database, _) = ready(ctx, sourceLanguage)
        database.rawQuery(
            "SELECT content FROM media WHERE dict_id = ? AND path = ?",
            arrayOf(dictId, path),
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    // ── Lifecycle hooks (called by YomitanDictionaryStore) ──────────────

    /** Ingests [dictionary]'s derived rows from [source] and REPORTS success,
     *  WITHOUT touching the capability caches. The import/update paths use this
     *  to PROVE the deck is queryable before committing its registry entry (see
     *  [com.playtranslate.yomitan.YomitanDictionaryStore.applyUpdate]).
     *  [ingestLocked] is transactional, so a failure rolls back cleanly (no
     *  partial rows, not marked ingested). Caches are deliberately NOT cleared
     *  here: the not-yet-registered new id would otherwise look like an orphan to
     *  a [reconcileLocked] triggered between this and the registry commit; the
     *  committer clears them ([invalidate] / onDictDeleted) once the registry is
     *  consistent. */
    suspend fun tryIngest(ctx: Context, dictionary: YomitanDictionary, source: File): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    ingestLocked(openDb(ctx), dictionary, source)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "ingest failed for ${dictionary.id}", e)
                    false
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
            registrySnapshot = null
            caches.clear()
        }
    }

    /** Drops cached registry-derived state (priority orders, capability gates)
     *  across all languages so the next query rebuilds from the current
     *  registry. Called after reorder/toggle/alias/accent edits.
     *
     *  Acquires [mutex] — the SAME lock [ready] publishes under — so it can't
     *  interleave INSIDE a rebuild. It runs strictly before or after one; an
     *  "after" wipes any cache a concurrent [ready] just published from a
     *  now-stale snapshot, so the next query rebuilds fresh. Without the lock,
     *  a settings edit racing an in-flight rebuild could leave the lock-free
     *  fast path serving stale priority/labels/colors until the next
     *  invalidation or restart. The in-lock hooks ([onDictImported]/
     *  [onDictDeleted]) clear inline rather than call this — [mutex] is not
     *  re-entrant. */
    suspend fun invalidate() = mutex.withLock {
        registrySnapshot = null
        caches.clear()
    }

    /**
     * Registry dictionaries with NO ingested rows — after a schema bump (or an
     * interrupted install) these contribute nothing to lookups and need a
     * re-import (or an auto-updater re-download) to come back. Forces a
     * reconcile first, so any dictionary that still has a re-ingestable legacy
     * zip heals before being reported. Feeds the settings warning UI, the
     * one-time zip sweep, and the auto-heal pass.
     */
    suspend fun outdatedDictIds(ctx: Context): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val database = openDb(ctx)
            val registry = initLocked(ctx, database)
            val ingested = mutableSetOf<String>()
            database.rawQuery("SELECT dict_id FROM ingested_dicts", null).use { c ->
                while (c.moveToNext()) ingested += c.getString(0)
            }
            registry.dictionaries.mapTo(mutableSetOf()) { it.id } - ingested
        }
    }

    /** Whether dictionary [id] currently has ingested rows (is NOT outdated). */
    suspend fun isIngested(ctx: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            openDb(ctx).rawQuery(
                "SELECT 1 FROM ingested_dicts WHERE dict_id = ?", arrayOf(id),
            ).use { it.moveToFirst() }
        }
    }

    // ── Init / reconcile ────────────────────────────────────────────────

    /** Language-INDEPENDENT init, under [mutex]: registry load + reconcile +
     *  the per-dict on/kun split aggregate, run ONCE per init cycle and shared
     *  by [ready] and [outdatedDictIds]. Returns a local so a concurrent
     *  invalidate() nulling the field mid-flight can't NPE the caller. */
    private suspend fun initLocked(ctx: Context, database: SQLiteDatabase): YomitanRegistry =
        registrySnapshot ?: run {
            val loaded = YomitanDictionaryStore.load(ctx)
            reconcileLocked(ctx, database, loaded)
            // Per-dict on/kun convention check (post-reconcile, so rows
            // are settled): a dict that never fills onyomi ships a
            // combined readings list, not the split.
            splitsByDict = buildMap {
                database.rawQuery(
                    "SELECT dict_id, MAX(CASE WHEN onyomi != '' THEN 1 ELSE 0 END) " +
                        "FROM kanji GROUP BY dict_id",
                    null,
                ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getInt(1) == 1) }
            }
            registrySnapshot = loaded
            loaded
        }

    private suspend fun ready(ctx: Context, lang: String): Pair<SQLiteDatabase, CapabilityCache> {
        // Canonicalize so a stray "zh-Hant"/"JA" can't create a redundant
        // (still correct, thanks to matchesSourceLanguage) map entry.
        val key = lang.split('-', '_').first().lowercase(java.util.Locale.ROOT)
        // Fast path once built; entries only drop on invalidate/hooks.
        caches[key]?.let { caps -> db?.let { return it to caps } }
        return mutex.withLock {
            val database = openDb(ctx)
            val registry = initLocked(ctx, database)
            val caps = caches.getOrPut(key) {
                // Queries gate on source-language match HERE, not in the
                // registry: the settings page manages all imports regardless
                // of language; only lookups filter, each consuming language
                // from its own cache.
                fun ordered(category: YomitanCategory) = registry.orderedFor(category)
                    .filter { it.matchesSourceLanguage(key) }
                CapabilityCache(
                    pitchPriority = ordered(YomitanCategory.PITCH_ACCENT)
                        .map { it.id },
                    freqDicts = ordered(YomitanCategory.FREQUENCY)
                        .map { it.id to (it.alias ?: it.title) },
                    dictColors = registry.dictionaries
                        .filter { it.matchesSourceLanguage(key) }
                        .associate { it.id to it.accentColor },
                    kanjiDicts = ordered(YomitanCategory.KANJI)
                        .map { KanjiDictMeta(it.id, it.targetLanguage, splitsByDict[it.id] ?: true) },
                    kanjiFreqDicts = ordered(YomitanCategory.KANJI_FREQUENCY)
                        .map { it.id to (it.alias ?: it.title) },
                    termDicts = ordered(YomitanCategory.TERMS)
                        .map { it.id to (it.alias ?: it.title) },
                    termsSingleDictionary = registry.termsSingleDictionary,
                    hasStructuredTerms = ordered(YomitanCategory.TERMS)
                        .map { it.id }
                        .let { ids -> ids.isNotEmpty() && hasStructuredRows(database, ids) },
                    dictStyles = loadDictStyles(
                        database,
                        ordered(YomitanCategory.TERMS).map { it.id },
                    ),
                    stylingEnabled = registry.dictionaryStyling,
                )
            }
            database to caps
        }
    }

    private fun hasStructuredRows(database: SQLiteDatabase, dictIds: List<String>): Boolean {
        val placeholders = dictIds.joinToString(",") { "?" }
        return database.rawQuery(
            "SELECT 1 FROM term_sc WHERE dict_id IN ($placeholders) LIMIT 1",
            dictIds.toTypedArray(),
        ).use { it.moveToFirst() }
    }

    private fun loadDictStyles(
        database: SQLiteDatabase,
        dictIds: List<String>,
    ): Map<String, String> {
        if (dictIds.isEmpty()) return emptyMap()
        val placeholders = dictIds.joinToString(",") { "?" }
        return buildMap {
            database.rawQuery(
                "SELECT dict_id, css FROM dict_styles WHERE dict_id IN ($placeholders)",
                dictIds.toTypedArray(),
            ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    private fun openDb(ctx: Context): SQLiteDatabase {
        db?.let { return it }
        val file = File(YomitanDictionaryStore.rootDir(ctx), "yomitan.sqlite")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        // Derived data: on any schema change, nuke rather than migrate. With no
        // retained source, the affected dictionaries become "outdated" (warning
        // rows in settings) until re-imported / re-downloaded — see
        // [SCHEMA_VERSION] before bumping.
        val version = database.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (version != SCHEMA_VERSION) {
            database.execSQL("DROP TABLE IF EXISTS pitch")
            database.execSQL("DROP TABLE IF EXISTS frequency")
            database.execSQL("DROP TABLE IF EXISTS kanji")
            database.execSQL("DROP TABLE IF EXISTS kanji_frequency")
            database.execSQL("DROP TABLE IF EXISTS term")
            database.execSQL("DROP TABLE IF EXISTS term_sc")
            database.execSQL("DROP TABLE IF EXISTS media")
            database.execSQL("DROP TABLE IF EXISTS dict_styles")
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
        // Structured-glossary sidecar, one row per `term` row whose glossary
        // carried structured-content/image items: the glossary array's raw
        // JSON, zlib-deflated. INTEGER PRIMARY KEY aliases the term row's
        // rowid — O(1) fetch, zero footprint for plain-text rows, and a
        // SEPARATE table so the hot `term` pages stay compact for the
        // lookup/phrase-oracle queries.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS term_sc (" +
                "term_rowid INTEGER PRIMARY KEY, dict_id TEXT NOT NULL, " +
                "content BLOB NOT NULL)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_term_sc_dict ON term_sc(dict_id)"
        )
        // Dictionary media (structured-content images), keyed exactly as
        // Yomitan keys its media store: (dictionary, zip-relative path).
        // Blobs are stored as shipped — image formats are already compressed.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS media (" +
                "dict_id TEXT NOT NULL, path TEXT NOT NULL, content BLOB NOT NULL, " +
                "PRIMARY KEY (dict_id, path))"
        )
        // Per-dictionary styles.css text. A table rather than a file beside
        // index.json so the CSS shares the exact lifecycle of the term_sc
        // rows it styles: same ingest transaction, same purge, dropped
        // together on a schema bump.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS dict_styles (" +
                "dict_id TEXT PRIMARY KEY, css TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS ingested_dicts (dict_id TEXT PRIMARY KEY)"
        )
        db = database
        return database
    }

    /** Purges rows of dicts no longer in the registry, and — LEGACY-ONLY —
     *  ingests registry dicts missing from `ingested_dicts` when their
     *  pre-sweep retained zip still exists on disk. Post-sweep there is no
     *  local source: such dicts stay un-ingested, which IS the "outdated"
     *  state [outdatedDictIds] reports and the settings UI surfaces. */
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
            val legacyZip = YomitanDictionaryStore.zipFile(ctx, dict.id)
            if (!legacyZip.exists()) continue // outdated — no source to heal from
            try {
                ingestLocked(database, dict, legacyZip)
            } catch (e: Exception) {
                // Leave un-marked so the next reconcile retries; the
                // transaction in ingestLocked keeps the DB consistent.
                Log.w(TAG, "reconcile: ingest failed for ${dict.id}", e)
            }
        }
    }

    private fun purgeLocked(database: SQLiteDatabase, dictId: String) {
        database.transaction {
            database.delete("pitch", "dict_id = ?", arrayOf(dictId))
            database.delete("frequency", "dict_id = ?", arrayOf(dictId))
            database.delete("kanji", "dict_id = ?", arrayOf(dictId))
            database.delete("kanji_frequency", "dict_id = ?", arrayOf(dictId))
            database.delete("term", "dict_id = ?", arrayOf(dictId))
            database.delete("term_sc", "dict_id = ?", arrayOf(dictId))
            database.delete("media", "dict_id = ?", arrayOf(dictId))
            database.delete("dict_styles", "dict_id = ?", arrayOf(dictId))
            database.delete("ingested_dicts", "dict_id = ?", arrayOf(dictId))
        }
    }

    // ── Ingestors (one per data type) ───────────────────────────────────

    /** Ingests everything this store derives from [dictionary]'s [source] zip,
     *  atomically: delete-then-insert inside one transaction, marking
     *  `ingested_dicts` last, so a mid-ingest crash can't half-apply or
     *  double-apply. */
    private fun ingestLocked(database: SQLiteDatabase, dictionary: YomitanDictionary, source: File) {
        database.transaction {
            database.delete("pitch", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("frequency", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("kanji", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("kanji_frequency", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("term", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("term_sc", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("media", "dict_id = ?", arrayOf(dictionary.id))
            database.delete("dict_styles", "dict_id = ?", arrayOf(dictionary.id))
            if (YomitanCategory.PITCH_ACCENT in dictionary.categories) {
                ingestPitch(database, dictionary.id, source)
            }
            if (YomitanCategory.FREQUENCY in dictionary.categories) {
                ingestFreq(database, dictionary.id, source)
            }
            if (YomitanCategory.KANJI in dictionary.categories) {
                ingestKanji(database, dictionary.id, source)
            }
            if (YomitanCategory.KANJI_FREQUENCY in dictionary.categories) {
                ingestKanjiFreq(database, dictionary.id, source)
            }
            if (YomitanCategory.TERMS in dictionary.categories) {
                // The JA-tuned headword-echo strip runs for dicts that match
                // "ja": declared-JA, plus undeclared ones (now a wildcard —
                // almost always legacy JA). It only strips a 【】 that echoes
                // the headword, a near-no-op on other scripts, so stripping an
                // undeclared deck is safe even when it isn't actually Japanese.
                ingestTerms(
                    database, dictionary.id, source,
                    applyHeadwordEchoStrip = dictionary.matchesSourceLanguage("ja"),
                )
            }
            // Category-independent: styles.css + media serve whatever
            // structured content the term pass retained.
            ingestMediaAndStyles(database, dictionary.id, source)
            database.execSQL(
                "INSERT OR REPLACE INTO ingested_dicts (dict_id) VALUES (?)",
                arrayOf(dictionary.id),
            )
        }
    }

    /** Retains the zip's renderable sidecars for the styled renderer: the
     *  root styles.css (whole-text, size-capped) and every image-extension
     *  entry keyed by its zip-relative path — exactly the path structured
     *  content references ([media] table mirrors Yomitan's
     *  (dictionary, path) store). Oversized entries skip with a log; a
     *  dictionary with none of either simply inserts nothing. */
    private fun ingestMediaAndStyles(database: SQLiteDatabase, dictId: String, zipFile: File) {
        val mediaInsert = database.compileStatement(
            "INSERT OR REPLACE INTO media (dict_id, path, content) VALUES (?, ?, ?)"
        )
        var mediaRows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (entry.name == "styles.css") {
                    if (entry.size > MAX_STYLES_BYTES) {
                        Log.w(TAG, "$dictId: styles.css over cap (${entry.size}B), skipped")
                        continue
                    }
                    val css = zip.getInputStream(entry).use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    if (css.isNotBlank()) {
                        database.execSQL(
                            "INSERT OR REPLACE INTO dict_styles (dict_id, css) VALUES (?, ?)",
                            arrayOf(dictId, css),
                        )
                    }
                    continue
                }
                val ext = entry.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (ext !in IMAGE_EXTENSIONS) continue
                if (entry.size > MAX_MEDIA_FILE_BYTES) {
                    Log.w(TAG, "$dictId: media ${entry.name} over cap (${entry.size}B), skipped")
                    continue
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                mediaInsert.bindString(1, dictId)
                mediaInsert.bindString(2, entry.name)
                mediaInsert.bindBlob(3, bytes)
                mediaInsert.executeInsert()
                mediaRows++
            }
        }
        if (mediaRows > 0) Log.i(TAG, "ingested $mediaRows media files for $dictId")
    }

    /** Streams `term_meta_bank_*.json` mode-`pitch` entries from the [zipFile]
     *  source into the `pitch` table. Integer downstep positions only — the
     *  schema's H/L string patterns are skipped (logged once per file). */
    private fun ingestPitch(database: SQLiteDatabase, dictId: String, zipFile: File) {
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

    /** Streams `term_meta_bank_*.json` mode-`freq` entries from the [zipFile]
     *  source into the `frequency` table. The data element's four schema shapes
     *  are handled by [FreqData]; unparseable entries are skipped (logged
     *  once per file). */
    private fun ingestFreq(database: SQLiteDatabase, dictId: String, zipFile: File) {
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

    /** Streams `kanji_bank_*.json` entries from the [zipFile] source into the
     *  `kanji` table. Entries are fixed-position 6-element arrays
     *  [char, onyomi, kunyomi, tags, meanings[], stats{}] — tags and stats
     *  are discarded (stats keys are dictionary-specific; the built-in
     *  KANJIDIC2 stays the source for numeric stats). */
    private fun ingestKanji(database: SQLiteDatabase, dictId: String, zipFile: File) {
        val insert = database.compileStatement(
            "INSERT INTO kanji (dict_id, character, onyomi, kunyomi, meanings) VALUES (?, ?, ?, ?, ?)"
        )
        // KANJIDIC-lineage dicts pack their per-kanji frequency rank into the
        // kanji_bank stats object (no kanji_meta_bank), so the same pass that
        // fills `kanji` also harvests `freq` into `kanji_frequency`. Detection
        // ([YomitanDictionaryStore.parseAndValidate]) gives such dicts the
        // KANJI_FREQUENCY category so [kanjiFrequencyFor] actually returns
        // these rows; without that the dict isn't in the freq section and the
        // rows sit unread.
        val freqInsert = database.compileStatement(
            "INSERT INTO kanji_frequency (dict_id, character, display, value) VALUES (?, ?, ?, ?)"
        )
        var rows = 0
        var freqRows = 0
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.name.contains('/') || !KANJI_BANK.matches(entry.name)) continue
                var skippedEntries = 0
                zip.getInputStream(entry).use { input ->
                    JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val parsed = KanjiBankEntry.parse(reader)
                            if (parsed == null || parsed.character.isEmpty()) {
                                skippedEntries++
                                continue
                            }

                            insert.bindString(1, dictId)
                            insert.bindString(2, parsed.character)
                            insert.bindString(3, parsed.onyomi)
                            insert.bindString(4, parsed.kunyomi)
                            insert.bindString(5, KanjiData.encodeMeanings(parsed.meanings))
                            insert.executeInsert()
                            rows++

                            parsed.freq?.let { f ->
                                freqInsert.bindString(1, dictId)
                                freqInsert.bindString(2, parsed.character)
                                freqInsert.bindString(3, f.display)
                                f.value?.let { freqInsert.bindDouble(4, it) } ?: freqInsert.bindNull(4)
                                freqInsert.executeInsert()
                                freqRows++
                            }
                        }
                        reader.endArray()
                    }
                }
                if (skippedEntries > 0) {
                    Log.i(TAG, "$dictId/${entry.name}: skipped $skippedEntries malformed kanji entries")
                }
            }
        }
        Log.i(TAG, "ingested $rows kanji rows ($freqRows with freq) for $dictId")
    }

    /** Streams `kanji_meta_bank_*.json` mode-`freq` entries into the
     *  `kanji_frequency` table. The data element shares the term-frequency
     *  shapes minus the reading wrapper, so [FreqData] handles it (any
     *  stray reading qualifier is ignored — kanji have no reading
     *  dimension). */
    private fun ingestKanjiFreq(database: SQLiteDatabase, dictId: String, zipFile: File) {
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
    private fun ingestTerms(
        database: SQLiteDatabase,
        dictId: String,
        zipFile: File,
        applyHeadwordEchoStrip: Boolean,
    ) {
        val insert = database.compileStatement(
            "INSERT INTO term (dict_id, term, reading, score, defs, pos) VALUES (?, ?, ?, ?, ?, ?)"
        )
        val scInsert = database.compileStatement(
            "INSERT INTO term_sc (term_rowid, dict_id, content) VALUES (?, ?, ?)"
        )
        var rows = 0
        var scRows = 0
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
                            val defs = resolveTermDefs(
                                parsed.defs,
                                parsed.term,
                                parsed.reading.ifBlank { parsed.term },
                                applyHeadwordEchoStrip,
                            )
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
                            val rowid = insert.executeInsert()
                            parsed.scJson?.let { sc ->
                                scInsert.bindLong(1, rowid)
                                scInsert.bindString(2, dictId)
                                scInsert.bindBlob(3, Zlib.deflate(sc.toByteArray(Charsets.UTF_8)))
                                scInsert.executeInsert()
                                scRows++
                            }
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
        Log.i(TAG, "ingested $rows term rows for $dictId ($scRows structured)")
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

    /** Per-entry definition resolution for term ingest, extracted so tests can
     *  drive the JA-only headword-echo gate without a zip fixture. JA-source
     *  dicts strip monolingual headword echoes (e.g. 「ねこ【猫】」) via
     *  [TermGlossary.stripHeadwordEcho]; every other language preserves the
     *  glossary text — that 【】 matching mis-fires on non-JA scripts (Chinese
     *  POS markers like 【名】) — dropping only blanks to keep the
     *  empties-skipped invariant the caller relies on. */
    internal fun resolveTermDefs(
        rawDefs: List<String>,
        term: String,
        reading: String,
        applyHeadwordEchoStrip: Boolean,
    ): List<String> =
        if (applyHeadwordEchoStrip) {
            rawDefs.mapNotNull { TermGlossary.stripHeadwordEcho(it, term, reading) }
        } else {
            rawDefs.filter { it.isNotBlank() }
        }

    // ── Collection-dump ingest (Yomitan "Export Dictionary Collection") ──

    /** Thrown when the stream is not a readable Yomitan collection export; the
     *  importer maps it to [YomitanImportResult.InvalidFormat] with the message
     *  as the diagnostic detail line. */
    class DumpFormatException(message: String) : Exception(message)

    /** Outcome of [ingestCollectionDump]: [imported] entries have their rows
     *  ingested + marked and await registry commit by the caller;
     *  [skippedExisting] titles were left untouched because an identical
     *  revision is already installed and ingested. */
    class DumpIngestResult(
        val imported: List<YomitanDictionary>,
        val skippedExisting: List<String>,
    )

    /**
     * Ingests every dictionary in a Yomitan Dexie collection dump ("Export
     * Dictionary Collection") streamed from [open]. Dexie dump layout:
     * `{formatName: "dexie", data: {databaseName: "dict", data: [{tableName,
     * rows: [...]}, ...]}}` with the `dictionaries` roster streaming first,
     * then kanji/kanjiMeta/media/tagMeta/termMeta/terms. Rows are named-field
     * objects carrying the same values as the corresponding bank entries (the
     * `glossary` value is byte-identical structured content), each tagged with
     * its `dictionary` title for routing; media (dropped at ingest anyway) and
     * styles stream past untouched.
     *
     * All inserts run in ONE transaction: cancel or crash rolls everything
     * back (no rows, nothing marked ingested, and the caller never wrote
     * registry entries — reconcile purges any orphans from a post-commit
     * crash). Like [tryIngest], caches are deliberately NOT cleared here; the
     * caller invalidates after its registry commit. Throws
     * [DumpFormatException] on structural problems; Gson parse errors
     * propagate for the caller to map.
     *
     * [existingByTitle] is the current registry keyed by title: a dump dict
     * whose derived id matches an already-ingested same-title entry is skipped
     * whole (idempotent re-import); everything else is (re)ingested and
     * returned for the caller's supersede-or-append registry commit.
     */
    suspend fun ingestCollectionDump(
        ctx: Context,
        sourceBytes: Long,
        existingByTitle: Map<String, YomitanDictionary>,
        open: () -> InputStream,
    ): DumpIngestResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val database = openDb(ctx)
            val cc = coroutineContext
            val ingested = mutableSetOf<String>()
            database.rawQuery("SELECT dict_id FROM ingested_dicts", null).use { c ->
                while (c.moveToNext()) ingested += c.getString(0)
            }
            val dicts: LinkedHashMap<String, DumpDict>
            open().use { input ->
                JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
                    dicts = streamDump(reader, database, existingByTitle, ingested, cc)
                }
            }
            val now = System.currentTimeMillis()
            DumpIngestResult(
                imported = dicts.values
                    .filter { !it.skip && it.categories.isNotEmpty() }
                    .map { d ->
                        YomitanDictionary(
                            id = d.id,
                            title = d.title,
                            revision = d.revision,
                            description = d.description,
                            author = d.author,
                            format = d.format,
                            categories = YomitanCategory.entries.filter { it in d.categories },
                            sizeBytes = sourceBytes,
                            importedAtMs = now,
                            sourceLanguage = d.sourceLanguage,
                            targetLanguage = d.targetLanguage,
                            frequencyMode = d.frequencyMode,
                            // No source URL travels with a dump — such dicts sit
                            // outside the auto-updater (and auto-heal).
                            isUpdatable = false,
                        )
                    },
                skippedExisting = dicts.values.filter { it.skip }.map { it.title },
            )
        }
    }

    /** One dump dictionary being accumulated: roster metadata + the categories
     *  and POS-tag set its streamed rows establish. [skip] = an identical
     *  revision is already installed and ingested; its rows stream past. */
    private class DumpDict(
        val id: String,
        val title: String,
        val revision: String?,
        val format: Int,
        val description: String?,
        val author: String?,
        val sourceLanguage: String?,
        val targetLanguage: String?,
        val frequencyMode: String?,
        /** Roster-carried styles.css text (the dump has no zip to read one
         *  from) — lands in `dict_styles` with the dict's other rows. */
        val styles: String?,
        val skip: Boolean,
    ) {
        val categories = mutableSetOf<YomitanCategory>()
        val posTags = mutableSetOf<String>()

        /** Same gate as [ingestLocked]'s term pass: declared-JA plus
         *  undeclared (wildcard) decks get the headword-echo strip. */
        val echoStrip: Boolean
            get() {
                val declared = sourceLanguage?.split('-', '_')?.first() ?: return true
                return declared.equals("ja", ignoreCase = true)
            }
    }

    private class DumpInserts(database: SQLiteDatabase) {
        val pitch: SQLiteStatement = database.compileStatement(
            "INSERT INTO pitch (dict_id, term, reading, variant, downstep) VALUES (?, ?, ?, ?, ?)"
        )
        val freq: SQLiteStatement = database.compileStatement(
            "INSERT INTO frequency (dict_id, term, reading, display, value) VALUES (?, ?, ?, ?, ?)"
        )
        val kanji: SQLiteStatement = database.compileStatement(
            "INSERT INTO kanji (dict_id, character, onyomi, kunyomi, meanings) VALUES (?, ?, ?, ?, ?)"
        )
        val kanjiFreq: SQLiteStatement = database.compileStatement(
            "INSERT INTO kanji_frequency (dict_id, character, display, value) VALUES (?, ?, ?, ?)"
        )
        val term: SQLiteStatement = database.compileStatement(
            "INSERT INTO term (dict_id, term, reading, score, defs, pos) VALUES (?, ?, ?, ?, ?, ?)"
        )
        val termSc: SQLiteStatement = database.compileStatement(
            "INSERT INTO term_sc (term_rowid, dict_id, content) VALUES (?, ?, ?)"
        )
        val media: SQLiteStatement = database.compileStatement(
            "INSERT OR REPLACE INTO media (dict_id, path, content) VALUES (?, ?, ?)"
        )
    }

    private fun streamDump(
        reader: JsonReader,
        database: SQLiteDatabase,
        existingByTitle: Map<String, YomitanDictionary>,
        ingested: Set<String>,
        cc: CoroutineContext,
    ): LinkedHashMap<String, DumpDict> {
        var sawDexie = false
        var dicts: LinkedHashMap<String, DumpDict>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "formatName" -> sawDexie = stringOrNull(reader) == "dexie"
                "data" -> {
                    if (!sawDexie) {
                        throw DumpFormatException("Not a Dexie database export (formatName is not \"dexie\")")
                    }
                    dicts = streamDumpData(reader, database, existingByTitle, ingested, cc)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return dicts ?: throw DumpFormatException("No data object in the export")
    }

    private fun streamDumpData(
        reader: JsonReader,
        database: SQLiteDatabase,
        existingByTitle: Map<String, YomitanDictionary>,
        ingested: Set<String>,
        cc: CoroutineContext,
    ): LinkedHashMap<String, DumpDict> {
        var dicts: LinkedHashMap<String, DumpDict>? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "databaseName" -> {
                    val name = stringOrNull(reader)
                    if (name != null && name != "dict") {
                        throw DumpFormatException(
                            "Export is of database \"$name\", not Yomitan's dictionary collection"
                        )
                    }
                }
                "data" -> dicts = streamDumpTables(reader, database, existingByTitle, ingested, cc)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return dicts ?: throw DumpFormatException("No table data in the export")
    }

    /** The tables array: roster first (establishes ids and skip decisions),
     *  then ONE transaction over every remaining table's inserts, marking
     *  `ingested_dicts` last — the dump-wide analog of [ingestLocked]. */
    private fun streamDumpTables(
        reader: JsonReader,
        database: SQLiteDatabase,
        existingByTitle: Map<String, YomitanDictionary>,
        ingested: Set<String>,
        cc: CoroutineContext,
    ): LinkedHashMap<String, DumpDict> {
        reader.beginArray()
        if (!reader.hasNext()) throw DumpFormatException("Export contains no tables")
        val dicts = readRosterTable(reader, existingByTitle, ingested)
        if (dicts.isEmpty()) throw DumpFormatException("Export contains no dictionaries")
        val inserts = DumpInserts(database)
        database.beginTransaction()
        try {
            for (d in dicts.values) {
                if (d.skip) continue
                database.delete("pitch", "dict_id = ?", arrayOf(d.id))
                database.delete("frequency", "dict_id = ?", arrayOf(d.id))
                database.delete("kanji", "dict_id = ?", arrayOf(d.id))
                database.delete("kanji_frequency", "dict_id = ?", arrayOf(d.id))
                database.delete("term", "dict_id = ?", arrayOf(d.id))
                database.delete("term_sc", "dict_id = ?", arrayOf(d.id))
                database.delete("media", "dict_id = ?", arrayOf(d.id))
                database.delete("dict_styles", "dict_id = ?", arrayOf(d.id))
                d.styles?.takeIf { it.isNotBlank() }?.let { css ->
                    database.execSQL(
                        "INSERT OR REPLACE INTO dict_styles (dict_id, css) VALUES (?, ?)",
                        arrayOf(d.id, css),
                    )
                }
            }
            while (reader.hasNext()) readDumpTable(reader, dicts, inserts, cc)
            for (d in dicts.values) {
                if (d.skip || d.categories.isEmpty()) continue
                database.execSQL(
                    "INSERT OR REPLACE INTO ingested_dicts (dict_id) VALUES (?)",
                    arrayOf(d.id),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        reader.endArray()
        return dicts
    }

    private fun readRosterTable(
        reader: JsonReader,
        existingByTitle: Map<String, YomitanDictionary>,
        ingested: Set<String>,
    ): LinkedHashMap<String, DumpDict> {
        val dicts = LinkedHashMap<String, DumpDict>()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            throw DumpFormatException("Malformed table list")
        }
        reader.beginObject()
        var tableName: String? = null
        var sawRows = false
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tableName" -> tableName = stringOrNull(reader)
                "rows" -> {
                    if (tableName != "dictionaries") {
                        throw DumpFormatException(
                            "Expected the dictionaries roster first, found table \"$tableName\""
                        )
                    }
                    sawRows = true
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        reader.skipValue()
                    } else {
                        reader.beginArray()
                        while (reader.hasNext()) readRosterRow(reader, dicts, existingByTitle, ingested)
                        reader.endArray()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (!sawRows) throw DumpFormatException("Dictionaries roster has no rows")
        return dicts
    }

    private fun readRosterRow(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        existingByTitle: Map<String, YomitanDictionary>,
        ingested: Set<String>,
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        val f = RosterFields()
        readDumpRowObject(reader) { name -> readRosterField(name, reader, f) }
        val title = f.title?.trim().orEmpty()
        if (title.isEmpty()) return
        val revision = f.revision?.trim()?.takeIf { it.isNotEmpty() }
        val id = dumpDictId(title, revision)
        val existing = existingByTitle[title]
        // Same-title duplicates within one dump: last row wins (Yomitan keys
        // its collection by title, so a dupe means a hand-edited file).
        dicts[title] = DumpDict(
            id = id,
            title = title,
            revision = revision,
            format = f.format ?: 3,
            description = f.description?.trim()?.takeIf { it.isNotEmpty() },
            author = f.author?.trim()?.takeIf { it.isNotEmpty() },
            sourceLanguage = f.sourceLanguage?.trim()?.takeIf { it.isNotEmpty() },
            targetLanguage = f.targetLanguage?.trim()?.takeIf { it.isNotEmpty() },
            frequencyMode = f.frequencyMode?.trim()?.takeIf { it.isNotEmpty() },
            styles = f.styles?.takeIf { it.isNotBlank() && it.length <= MAX_STYLES_BYTES },
            skip = existing != null && existing.id == id && id in ingested,
        )
    }

    private class RosterFields {
        var title: String? = null
        var revision: String? = null
        var format: Int? = null
        var description: String? = null
        var author: String? = null
        var sourceLanguage: String? = null
        var targetLanguage: String? = null
        var frequencyMode: String? = null
        var styles: String? = null
    }

    private fun readRosterField(name: String, reader: JsonReader, f: RosterFields) {
        when (name) {
            "title" -> f.title = stringOrNull(reader)
            "revision" -> f.revision = stringOrNull(reader)
            "version", "format" -> f.format =
                if (reader.peek() == JsonToken.NUMBER) reader.nextInt()
                else { reader.skipValue(); f.format }
            "description" -> f.description = stringOrNull(reader)
            "author" -> f.author = stringOrNull(reader)
            "sourceLanguage" -> f.sourceLanguage = stringOrNull(reader)
            "targetLanguage" -> f.targetLanguage = stringOrNull(reader)
            "frequencyMode" -> f.frequencyMode = stringOrNull(reader)
            "styles" -> f.styles = stringOrNull(reader)
            else -> reader.skipValue()
        }
    }

    /** Content id for a dump-sourced dictionary. There is no zip to hash, so
     *  the id derives from (title, revision) — stable across re-imports of the
     *  same export, different across revisions so a newer dump supersedes
     *  cleanly by title. */
    private fun dumpDictId(title: String, revision: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$title\u0000${revision.orEmpty()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun readDumpTable(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        var tableName: String? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tableName" -> tableName = stringOrNull(reader)
                "rows" -> when (tableName) {
                    "tagMeta" -> readTagMetaRows(reader, dicts, cc)
                    "termMeta" -> readTermMetaRows(reader, dicts, inserts, cc)
                    "terms" -> readTermRows(reader, dicts, inserts, cc)
                    "kanji" -> readKanjiRows(reader, dicts, inserts, cc)
                    "kanjiMeta" -> readKanjiMetaRows(reader, dicts, inserts, cc)
                    "media" -> readMediaRows(reader, dicts, inserts, cc)
                    // Unknown tables stream past untouched.
                    else -> reader.skipValue()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    /** Iterates a table's rows array, invoking [row] with the reader
     *  positioned at each row object. Cancellation-cooperative — the dump's
     *  terms table alone can run to a million-plus rows. */
    private inline fun readDumpRows(reader: JsonReader, cc: CoroutineContext, row: () -> Unit) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        var n = 0
        while (reader.hasNext()) {
            if (++n % 1024 == 0) cc.ensureActive()
            if (reader.peek() == JsonToken.BEGIN_OBJECT) row() else reader.skipValue()
        }
        reader.endArray()
    }

    /** Reads one dump row object, dispatching each field name to [field] with
     *  the reader positioned at its value ([field] MUST consume it). Rows come
     *  in two shapes: bare objects (tables with a named `++id` primary key —
     *  terms, media) and Dexie's outbound wrapper
     *  `{"$": [primaryKey, {fields}], "$types": …}` for tables with hidden
     *  keys — which in real Yomitan exports is MOST of them (dictionaries,
     *  tagMeta, termMeta, kanji, kanjiMeta; verified against a real export).
     *  Both shapes unwrap transparently here; `$types` and other unknown
     *  outer fields fall through to [field], whose `else` skips them. */
    private inline fun readDumpRowObject(reader: JsonReader, field: (String) -> Unit) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "$" -> {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                        reader.skipValue()
                        continue
                    }
                    reader.beginArray()
                    if (reader.hasNext()) reader.skipValue() // primary key
                    if (reader.hasNext() && reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) field(reader.nextName())
                        reader.endObject()
                    }
                    while (reader.hasNext()) reader.skipValue()
                    reader.endArray()
                }
                else -> field(name)
            }
        }
        reader.endObject()
    }

    private fun readTagMetaRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        cc: CoroutineContext,
    ) = readDumpRows(reader, cc) {
        var name: String? = null
        var category: String? = null
        var dictTitle: String? = null
        readDumpRowObject(reader) { field ->
            when (field) {
                "name" -> name = stringOrNull(reader)
                "category" -> category = stringOrNull(reader)
                "dictionary" -> dictTitle = stringOrNull(reader)
                else -> reader.skipValue()
            }
        }
        val tagName = name
        if (category == "partOfSpeech" && !tagName.isNullOrEmpty()) {
            dicts[dictTitle]?.takeUnless { it.skip }?.posTags?.add(tagName)
        }
    }

    private fun readTermMetaRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) {
        var pitchRows = 0
        var freqRows = 0
        readDumpRows(reader, cc) {
            var expression: String? = null
            var mode: String? = null
            var data: JsonElement? = null
            var dictTitle: String? = null
            readDumpRowObject(reader) { field ->
                when (field) {
                    "expression" -> expression = stringOrNull(reader)
                    "mode" -> mode = stringOrNull(reader)
                    // Field order in a dump row is not guaranteed and `data`'s
                    // interpretation depends on `mode`, so buffer the (small)
                    // element and interpret at row end.
                    "data" -> data = JsonParser.parseReader(reader)
                    "dictionary" -> dictTitle = stringOrNull(reader)
                    else -> reader.skipValue()
                }
            }
            val dict = dicts[dictTitle]
            val expr = expression
            if (dict != null && !dict.skip && !expr.isNullOrEmpty()) {
                when (mode) {
                    "pitch" -> pitchRows += insertDumpPitch(inserts.pitch, dict, expr, data)
                    "freq" -> {
                        val row = data?.let { parseFreqElement(it) }
                        if (row != null) {
                            inserts.freq.bindString(1, dict.id)
                            inserts.freq.bindString(2, expr)
                            row.reading
                                ?.let { inserts.freq.bindString(3, Deinflector.katakanaToHiragana(it)) }
                                ?: inserts.freq.bindNull(3)
                            inserts.freq.bindString(4, row.display)
                            row.value?.let { inserts.freq.bindDouble(5, it) } ?: inserts.freq.bindNull(5)
                            inserts.freq.executeInsert()
                            dict.categories += YomitanCategory.FREQUENCY
                            freqRows++
                        }
                    }
                    "ipa" -> dict.categories += YomitanCategory.PRONUNCIATION
                }
            }
        }
        Log.i(TAG, "dump: ingested $pitchRows pitch + $freqRows frequency rows")
    }

    /** Pitch data element: `{reading?, pitches: [{position: int|pattern}…]}`.
     *  Mirrors the zip ingestor — integer downsteps only. Returns the number
     *  of rows inserted. */
    private fun insertDumpPitch(
        stmt: SQLiteStatement,
        dict: DumpDict,
        term: String,
        data: JsonElement?,
    ): Int {
        val obj = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return 0
        val reading = obj.get("reading")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: term
        val pitches = obj.get("pitches")?.takeIf { it.isJsonArray }?.asJsonArray ?: return 0
        val normalized = Deinflector.katakanaToHiragana(reading)
        var variant = 0
        for (p in pitches) {
            val position = p.takeIf { it.isJsonObject }?.asJsonObject?.get("position") ?: continue
            if (!(position.isJsonPrimitive && position.asJsonPrimitive.isNumber)) continue
            stmt.bindString(1, dict.id)
            stmt.bindString(2, term)
            stmt.bindString(3, normalized)
            stmt.bindLong(4, variant.toLong())
            stmt.bindLong(5, position.asInt.toLong())
            stmt.executeInsert()
            variant++
        }
        if (variant > 0) dict.categories += YomitanCategory.PITCH_ACCENT
        return variant
    }

    /** [FreqData.parse] over a buffered element (dump rows are
     *  field-order-free, so `data` is buffered as a tree; re-reading its
     *  compact form keeps the four-shape handling in one place). */
    private fun parseFreqElement(data: JsonElement): FreqData.Row? =
        JsonReader(StringReader(data.toString())).use { FreqData.parse(it) }

    private fun readTermRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) {
        var rows = 0
        var scRows = 0
        var skipped = 0
        readDumpRows(reader, cc) {
            var expression: String? = null
            var reading = ""
            var definitionTags = ""
            var score = 0.0
            var defs: List<String> = emptyList()
            var scJson: String? = null
            var dictTitle: String? = null
            readDumpRowObject(reader) { field ->
                when (field) {
                    "expression" -> expression = stringOrNull(reader)
                    "reading" -> reading = stringOrNull(reader).orEmpty()
                    "definitionTags" -> definitionTags = stringOrNull(reader).orEmpty()
                    "score" -> score =
                        if (reader.peek() == JsonToken.NUMBER) reader.nextDouble()
                        else { reader.skipValue(); 0.0 }
                    // Byte-identical structured content to a term_bank glossary —
                    // the same tee as [TermEntry.parse]: buffer the subtree,
                    // retain its serialized form when structured, flatten via
                    // the unchanged streaming flattener.
                    "glossary" ->
                        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                            val glossary = JsonParser.parseReader(reader)
                            val serialized = glossary.toString()
                            defs = JsonReader(StringReader(serialized))
                                .use { TermGlossary.parseGlossary(it) }
                            if (glossaryHasStructuredItems(glossary)) scJson = serialized
                        } else {
                            reader.skipValue()
                        }
                    "dictionary" -> dictTitle = stringOrNull(reader)
                    else -> reader.skipValue() // rules, sequence, termTags, id, $types
                }
            }
            val dict = dicts[dictTitle]
            val expr = expression
            if (dict == null || dict.skip || expr.isNullOrEmpty()) {
                skipped++
                return@readDumpRows
            }
            val resolved = resolveTermDefs(
                defs, expr, reading.ifBlank { expr }, dict.echoStrip,
            )
            if (resolved.isEmpty()) {
                skipped++
                return@readDumpRows
            }
            val normalizedReading = Deinflector.katakanaToHiragana(reading.ifBlank { expr })
            val pos = definitionTags.split(' ')
                .filter { it.isNotEmpty() && it in dict.posTags }
                .joinToString(" ")
            inserts.term.bindString(1, dict.id)
            inserts.term.bindString(2, expr)
            inserts.term.bindString(3, normalizedReading)
            inserts.term.bindDouble(4, score)
            inserts.term.bindString(5, KanjiData.encodeMeanings(resolved))
            inserts.term.bindString(6, pos)
            val rowid = inserts.term.executeInsert()
            scJson?.let { sc ->
                inserts.termSc.bindLong(1, rowid)
                inserts.termSc.bindString(2, dict.id)
                inserts.termSc.bindBlob(3, Zlib.deflate(sc.toByteArray(Charsets.UTF_8)))
                inserts.termSc.executeInsert()
                scRows++
            }
            dict.categories += YomitanCategory.TERMS
            rows++
        }
        Log.i(TAG, "dump: ingested $rows term rows ($scRows structured, $skipped text-less/unroutable skipped)")
    }

    /** Dump-side twin of [TermEntry]'s structured-item check (that one is
     *  private to the entry parser's flow; this one takes the already
     *  buffered element). */
    private fun glossaryHasStructuredItems(glossary: JsonElement): Boolean {
        if (!glossary.isJsonArray) return false
        return glossary.asJsonArray.any { item ->
            item.isJsonObject &&
                item.asJsonObject.get("type")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString.let { it == "structured-content" || it == "image" }
        }
    }

    /** Dump media rows: `{dictionary, path, mediaType, width, height,
     *  content(base64)}` (bare rows — media has a named `++id` key). Only
     *  image-extension paths land (same whitelist as the zip pass); decode
     *  failures skip the row. */
    private fun readMediaRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) {
        var rows = 0
        readDumpRows(reader, cc) {
            var path: String? = null
            var content: String? = null
            var dictTitle: String? = null
            readDumpRowObject(reader) { field ->
                when (field) {
                    "path" -> path = stringOrNull(reader)
                    "content" -> content = stringOrNull(reader)
                    "dictionary" -> dictTitle = stringOrNull(reader)
                    else -> reader.skipValue() // mediaType, width, height, id, $types
                }
            }
            val dict = dicts[dictTitle]
            val p = path
            val c = content
            if (dict == null || dict.skip || p.isNullOrEmpty() || c.isNullOrEmpty()) return@readDumpRows
            val ext = p.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (ext !in IMAGE_EXTENSIONS) return@readDumpRows
            val bytes = try {
                // Mime decoder: tolerant of the line-wrapping some exporters
                // emit, still rejects non-base64 garbage. Pure JVM.
                java.util.Base64.getMimeDecoder().decode(c)
            } catch (_: IllegalArgumentException) {
                return@readDumpRows
            }
            if (bytes.size > MAX_MEDIA_FILE_BYTES) return@readDumpRows
            inserts.media.bindString(1, dict.id)
            inserts.media.bindString(2, p)
            inserts.media.bindBlob(3, bytes)
            inserts.media.executeInsert()
            rows++
        }
        if (rows > 0) Log.i(TAG, "dump: ingested $rows media files")
    }

    private fun readKanjiRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) {
        var rows = 0
        var freqRows = 0
        readDumpRows(reader, cc) {
            var character: String? = null
            var onyomi = ""
            var kunyomi = ""
            var meanings: List<String> = emptyList()
            var stats: JsonElement? = null
            var dictTitle: String? = null
            readDumpRowObject(reader) { field ->
                when (field) {
                    "character" -> character = stringOrNull(reader)
                    "onyomi" -> onyomi = stringOrNull(reader).orEmpty()
                    "kunyomi" -> kunyomi = stringOrNull(reader).orEmpty()
                    "meanings" -> meanings = readStringArray(reader)
                    "stats" -> stats = JsonParser.parseReader(reader)
                    "dictionary" -> dictTitle = stringOrNull(reader)
                    else -> reader.skipValue() // tags, $types
                }
            }
            val dict = dicts[dictTitle]
            val char = character
            if (dict == null || dict.skip || char.isNullOrEmpty()) return@readDumpRows
            inserts.kanji.bindString(1, dict.id)
            inserts.kanji.bindString(2, char)
            inserts.kanji.bindString(3, onyomi)
            inserts.kanji.bindString(4, kunyomi)
            inserts.kanji.bindString(5, KanjiData.encodeMeanings(meanings))
            inserts.kanji.executeInsert()
            dict.categories += YomitanCategory.KANJI
            rows++
            // KANJIDIC-lineage freq stat — same harvest as the zip ingestor.
            val freq = stats?.takeIf { it.isJsonObject }?.asJsonObject?.get("freq")
                ?.let { parseFreqElement(it) }
            if (freq != null) {
                inserts.kanjiFreq.bindString(1, dict.id)
                inserts.kanjiFreq.bindString(2, char)
                inserts.kanjiFreq.bindString(3, freq.display)
                freq.value?.let { inserts.kanjiFreq.bindDouble(4, it) } ?: inserts.kanjiFreq.bindNull(4)
                inserts.kanjiFreq.executeInsert()
                dict.categories += YomitanCategory.KANJI_FREQUENCY
                freqRows++
            }
        }
        Log.i(TAG, "dump: ingested $rows kanji rows ($freqRows with freq)")
    }

    private fun readKanjiMetaRows(
        reader: JsonReader,
        dicts: LinkedHashMap<String, DumpDict>,
        inserts: DumpInserts,
        cc: CoroutineContext,
    ) = readDumpRows(reader, cc) {
        var character: String? = null
        var mode: String? = null
        var data: JsonElement? = null
        var dictTitle: String? = null
        readDumpRowObject(reader) { field ->
            when (field) {
                "character", "expression" -> character = stringOrNull(reader)
                "mode" -> mode = stringOrNull(reader)
                "data" -> data = JsonParser.parseReader(reader)
                "dictionary" -> dictTitle = stringOrNull(reader)
                else -> reader.skipValue()
            }
        }
        val dict = dicts[dictTitle]
        val char = character
        if (dict != null && !dict.skip && mode == "freq" && !char.isNullOrEmpty()) {
            val row = data?.let { parseFreqElement(it) }
            if (row != null) {
                inserts.kanjiFreq.bindString(1, dict.id)
                inserts.kanjiFreq.bindString(2, char)
                inserts.kanjiFreq.bindString(3, row.display)
                row.value?.let { inserts.kanjiFreq.bindDouble(4, it) } ?: inserts.kanjiFreq.bindNull(4)
                inserts.kanjiFreq.executeInsert()
                dict.categories += YomitanCategory.KANJI_FREQUENCY
            }
        }
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val out = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) out += reader.nextString() else reader.skipValue()
        }
        reader.endArray()
        return out
    }

    private fun stringOrNull(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.STRING) reader.nextString()
        else { reader.skipValue(); null }
}
