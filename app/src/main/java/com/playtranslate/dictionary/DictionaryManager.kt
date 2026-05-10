package com.playtranslate.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.KanjiDetail
import com.playtranslate.model.Sense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.WeakHashMap

private const val TAG = "DictionaryManager"

/** Run [block] while holding an extra SQLite reference on the receiver, so a
 *  concurrent close()+reopen (e.g. PackUpgradeOrchestrator's post-install
 *  eviction) can't tear the underlying connection down mid-cursor.
 *  Returns null if the connection was already closed before we could
 *  acquire — caller treats that the same as "no result". */
private inline fun <T> SQLiteDatabase.withRefcount(block: () -> T): T? {
    try { acquireReference() } catch (_: IllegalStateException) { return null }
    try { return block() } finally { releaseReference() }
}

/** Result from [DictionaryManager.tokenizeWithSurfaces]. */
data class TokenWithReading(
    /** Text as it appears in the input (e.g. "使わない"). */
    val surface: String,
    /** Dictionary form for lookup (e.g. "使う"). */
    val lookupForm: String,
    /** Hiragana reading from Kuromoji, or null for multi-token phrases. */
    val reading: String?
)

/** A furigana annotation: reading text positioned over a kanji span within the original text. */
data class FuriganaToken(
    /** The kanji portion of the token (okurigana stripped). e.g. "聞" from "聞い". */
    val kanjiSurface: String,
    /** Hiragana reading for the kanji portion. e.g. "き" for "聞". */
    val reading: String,
    /** Character offset of [kanjiSurface] within the original input text. */
    val startOffset: Int,
    /** Character end offset (exclusive) of [kanjiSurface] within the original input text. */
    val endOffset: Int
)

/**
 * Offline Japanese dictionary backed by a JMdict SQLite database bundled
 * as an app asset.  The database is copied from assets to internal storage
 * on first use, then re-used on every subsequent launch.
 *
 * Drop-in replacement for the legacy JishoClient: [lookup] returns a
 * [DictionaryResponse] whose shape matches what the UI bottom sheets expect,
 * so no consumer changes beyond the call site.
 *
 * Obtain via [DictionaryManager.get] — one instance is kept for the lifetime
 * of the process.
 */
class DictionaryManager private constructor(private val context: Context) {

    private var db: SQLiteDatabase? = null
    private val mutex = Mutex()

    /** Per-handle capability cache for `reading.rank_score`. Keyed by
     *  SQLiteDatabase identity so an upgrade close()+reopen race can't
     *  mismatch a refcount-held old handle with a freshly-detected flag
     *  for a different handle. WeakHashMap auto-evicts entries when
     *  handles GC, so we don't pin closed connections.
     *
     *  When the column is missing the ranking SQL falls back to the legacy
     *  `entry.freq_score`-JOIN path — degraded ranking, no crashes — until
     *  the pack is upgraded. */
    private val rankScoreSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val rankScoreSupportLock = Any()

    /** All access to [rankScoreSupport] — read AND write — happens inside
     *  the lock. WeakHashMap's `get` can internally expunge stale entries,
     *  so even reads mutate the structure; serializing every operation
     *  prevents the map from being torn during a concurrent expunge.
     *  Spelling out get / put / return inline (vs. `getOrPut`) makes the
     *  invariant visible at a glance. */
    private fun hasRankScore(db: SQLiteDatabase): Boolean {
        synchronized(rankScoreSupportLock) {
            rankScoreSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "reading", "rank_score")
            rankScoreSupport[db] = supports
            return supports
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Warm up the database (copy from assets if needed, then open).
     * Safe to call multiple times; only the first call does real work.
     * Call from a background coroutine early in app startup.
     */
    suspend fun preload() = ensureOpen()

    /**
     * Tokenises [text] into a list of dictionary-form words and idiomatic phrases
     * suitable for bulk dictionary lookup (the "Words" panel).
     *
     * Algorithm (greedy left-to-right):
     *  1. Kuromoji splits [text] into raw tokens.
     *  2. At each position, try joining 4 → 2 adjacent token surfaces into a
     *     phrase and check if the phrase exists in JMdict.
     *  3. If a multi-token phrase matches, emit it and advance past all its tokens.
     *  4. Otherwise emit the single token's base form (if it's a content word).
     *
     * This handles set expressions like かもしれない (か+も+しれ+ない) that
     * Kuromoji splits grammatically but JMdict stores as a single entry.
     *
     * Falls back to [Deinflector.tokenize] if the database is not ready.
     */
    suspend fun tokenize(text: String): List<String> =
        tokenizeWithSurfaces(text).map { it.lookupForm }.distinct()

    /**
     * Tokenizes [text] and returns pairs of (surface span, lookup form).
     *
     * The surface span is the text as it appears in the input (e.g. "使わない")
     * — useful for position mapping. The lookup form is the dictionary form
     * (e.g. "使う") — used for dictionary lookup.
     *
     * For verbs/adjectives, the surface span includes following auxiliary
     * tokens that are part of the conjugation (e.g. ない after 使わ).
     *
     * Falls back to [Deinflector.tokenize] if the database is not ready.
     */
    suspend fun tokenizeWithSurfaces(text: String): List<TokenWithReading> = withContext(Dispatchers.IO) {
        val deinflectorFallback = { Deinflector.tokenize(text).map { TokenWithReading(it, it, null) } }
        val database = ensureOpen() ?: return@withContext deinflectorFallback()
        database.withRefcount {
            val tokens   = Deinflector.rawTokenInfos(text)
            val surfaces = tokens.map { it.surface }
            val result   = mutableListOf<TokenWithReading>()

            // Batch-query all candidate N-grams upfront (2 queries instead of ~60)
            val candidates = mutableSetOf<String>()
            for (i in tokens.indices) {
                val maxN = minOf(4, tokens.size - i)
                for (n in maxN downTo 2) {
                    val phrase = surfaces.subList(i, i + n).joinToString("")
                    if (isLookupWorthy(phrase)) candidates.add(phrase)
                }
            }
            val knownPhrases = batchCheckEntries(database, candidates)

            var i = 0
            while (i < tokens.size) {
                // Try multi-token N-grams (4 down to 2) at the current position.
                var advanced = false
                val maxN = minOf(4, tokens.size - i)
                for (n in maxN downTo 2) {
                    val phrase = surfaces.subList(i, i + n).joinToString("")
                    if (phrase in knownPhrases) {
                        result.add(TokenWithReading(phrase, phrase, reading = null))
                        i += n
                        advanced = true
                        break
                    }
                }

                if (!advanced) {
                    val t = tokens[i]
                    if (isContentWord(t.pos)) {
                        val lookupForm = t.baseForm ?: t.surface
                        if (isLookupWorthy(lookupForm)) {
                            // Gather the surface span: for verbs/adjectives, include
                            // following auxiliary tokens (e.g. ない after 使わ)
                            var surfaceSpan = t.surface
                            if (t.pos == "動詞" || t.pos == "形容詞") {
                                var j = i + 1
                                while (j < tokens.size && tokens[j].pos in setOf("助動詞", "助詞")) {
                                    surfaceSpan += tokens[j].surface
                                    j++
                                }
                            }
                            val reading = t.reading?.let { Deinflector.katakanaToHiragana(it) }
                            result.add(TokenWithReading(surfaceSpan, lookupForm, reading))
                        }
                    }
                    i++
                }
            }

            Log.d(TAG, "tokenizeWithSurfaces: ${result.map { "(${it.surface} → ${it.lookupForm} [${it.reading}])" }}")
            result
        } ?: deinflectorFallback()
    }

    /**
     * Tokenize text for furigana display using raw Kuromoji morphemes.
     *
     * Each token is processed independently with its conjugation-aware reading
     * (e.g. 来た → き for 来, 聞い → き for 聞). Compound words with internal
     * kana are split at shared boundaries (取り出す → と over 取, だ over 出).
     *
     * No database queries — uses only Kuromoji + in-memory splitting.
     */
    fun tokenizeForFurigana(text: String): List<FuriganaToken> {
        val tokens = Deinflector.tokenizeWithReadings(text)
        val result = mutableListOf<FuriganaToken>()
        var offset = 0

        for (tok in tokens) {
            if (!tok.hasKanji || tok.reading == null || tok.reading == tok.surface) {
                offset += tok.surface.length
                continue
            }

            val parts = Deinflector.splitFurigana(tok.surface, tok.reading)
            var partOffset = 0
            for (part in parts) {
                if (part.reading != null) {
                    result += FuriganaToken(
                        kanjiSurface = part.text,
                        reading = part.reading,
                        startOffset = offset + partOffset,
                        endOffset = offset + partOffset + part.text.length
                    )
                }
                partOffset += part.text.length
            }
            offset += tok.surface.length
        }
        return result
    }

    private fun isContentWord(pos: String?): Boolean = pos in setOf(
        "名詞", "動詞", "形容詞", "形容動詞", "副詞", "感動詞",
        // 接続詞 (conjunction): もっとも, しかし, そして, けれども — all in JMdict
        // and worth a tap. Without this, IPADIC's もっとも (tagged 接続詞 even
        // for the 最も "most" sense in some sentences) drops out before lookup.
        "接続詞",
        // 連体詞 (prenominal adjectival): この, その, あの, どの, 大きな, 小さな,
        // ある, 我が — common demonstratives and pre-nominals, all in JMdict.
        "連体詞",
    )

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.all { it.code <= 0x007F }) return false
        if (token.length == 1 && token[0] in '\u3041'..'\u3096') return false
        return true
    }

    /**
     * Look up [word] in the local JMdict database.
     *
     * If no direct match is found, de-inflection candidates are tried in
     * order.  Returns null if nothing matches or the database isn't ready.
     *
     * This is a suspend function; do NOT call on the main thread.
     */
    suspend fun lookup(word: String, reading: String? = null): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null
        database.withRefcount {
            // 1. Exact match narrowed by reading (if available)
            if (reading != null) {
                val narrowedIds = queryEntryIdsWithReading(database, word, reading)
                if (narrowedIds.isNotEmpty()) {
                    Log.d(TAG, "lookup($word, reading=$reading): narrowed ids=$narrowedIds")
                    return@withRefcount buildResponse(database, narrowedIds)
                }
            }

            // 2. Exact match (headword or reading table, no reading filter)
            val directIds = queryEntryIds(database, word)
            if (directIds.isNotEmpty()) {
                Log.d(TAG, "lookup($word): exact match ids=$directIds")
                return@withRefcount buildResponse(database, directIds)
            }

            // 3. Try de-inflected candidates (first dictionary hit wins)
            for (candidate in Deinflector.candidates(word)) {
                val ids = queryEntryIds(database, candidate.text)
                if (ids.isNotEmpty()) {
                    return@withRefcount buildResponse(database, ids, candidate.reason)
                }
            }

            null
        }
    }

    /**
     * Look up a single kanji character in KANJIDIC2. Returns null if not found
     * or the database isn't ready. Call from a background coroutine.
     *
     * Meanings resolve as follows:
     *  1. `kanji_meaning(literal, [targetLang])` if the pack ships glosses in
     *     the requested language (KANJIDIC2 natively has en/fr/es/pt).
     *  2. `kanji_meaning(literal, "en")` otherwise.
     *
     * The resolved language is returned on [KanjiDetail.meaningsLang] so the
     * caller can decide whether to machine-translate before display.
     */
    suspend fun lookupKanji(literal: Char, targetLang: String = "en"): KanjiDetail? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null
        database.withRefcount {
            database.rawQuery(
                "SELECT on_readings, kun_readings, jlpt, grade, stroke_count FROM kanjidic WHERE literal=?",
                arrayOf(literal.toString())
            ).use { c ->
                if (!c.moveToFirst()) return@withRefcount null
                val onReadings   = c.getString(0).split(',').filter { it.isNotBlank() }
                val kunReadings  = c.getString(1).split(',').filter { it.isNotBlank() }
                val jlpt         = c.getInt(2)
                val grade        = c.getInt(3)
                val strokeCount  = c.getInt(4)

                val (meanings, resolvedLang) = resolveKanjiMeanings(database, literal, targetLang)
                if (meanings.isEmpty()) return@withRefcount null
                KanjiDetail(
                    literal      = literal,
                    meanings     = meanings,
                    meaningsLang = resolvedLang,
                    onReadings   = onReadings,
                    kunReadings  = kunReadings,
                    jlpt         = jlpt,
                    grade        = grade,
                    strokeCount  = strokeCount,
                )
            }
        }
    }

    fun close() {
        db?.close()
        db = null
    }

    // ── Initialisation ────────────────────────────────────────────────────

    private suspend fun ensureOpen(): SQLiteDatabase? = mutex.withLock {
        db?.let { return@withLock it }

        val dbFile = LanguagePackStore.dictDbFor(context, SourceLangId.JA)

        if (dbFile.exists() && !isSchemaUpToDate(dbFile)) {
            // Should be unreachable: LanguagePackStore.isInstalled schema-
            // validates before callers reach us. Keep the guard as
            // defense-in-depth.
            Log.w(TAG, "JMdict schema outdated at ${dbFile.absolutePath} — deleting; user must re-run onboarding")
            dbFile.delete()
        }

        if (!dbFile.exists()) {
            Log.w(TAG, "JMdict pack not installed at ${dbFile.absolutePath}; lookups will return empty")
            return@withLock null
        }

        db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { Log.d(TAG, "JMdict opened (${dbFile.length() / 1_048_576} MB)") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open JMdict: ${e.message}")
            null
        }

        // Capability detection for `reading.rank_score` happens lazily on
        // first query via hasRankScore(db) — keyed per-handle so race-safe
        // against close()+reopen during an upgrade.

        // Bootstrap the bundled-pack manifest once the DB is known-good.
        // Idempotent — writeManifestIfMissing no-ops on subsequent boots.
        if (db != null) {
            LanguagePackCatalogLoader.entryFor(context, SourceLangId.JA)?.let { entry ->
                try {
                    LanguagePackStore.writeManifestIfMissing(context, SourceLangId.JA, entry)
                } catch (e: Exception) {
                    Log.w(TAG, "Manifest write failed: ${e.message}")
                }
            }
        }

        db
    }

    /** True if [table] has [column] in its current schema. Cheap: a PRAGMA
     *  table_info call (metadata, no scan). Used at DB-open time to decide
     *  which ranking SQL to dispatch. */
    private fun checkColumnExists(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(nameIdx) else null }
                .any { it == column }
        }

    /** Returns false if the on-device DB is missing required tables/columns.
     *  Delegates to [JmdictSchemaProbe] so this and
     *  [com.playtranslate.language.LanguagePackStore.isJmdictSchemaCurrent]
     *  share one definition. */
    private fun isSchemaUpToDate(dbFile: File): Boolean =
        JmdictSchemaProbe.isCurrent(dbFile)

    // ── Database queries ──────────────────────────────────────────────────

    /**
     * Returns entry IDs matching [word] as a kanji (written headword) or
     * reading form, up to 8, sorted by frequency (most common first).
     */
    /** Fast existence check — no JOIN, no sorting. Used by tokenization. */
    private fun hasEntry(db: SQLiteDatabase, word: String): Boolean {
        db.rawQuery("SELECT 1 FROM headword WHERE text = ? LIMIT 1", arrayOf(word))
            .use { if (it.moveToFirst()) return true }
        db.rawQuery("SELECT 1 FROM reading WHERE text = ? LIMIT 1", arrayOf(word))
            .use { if (it.moveToFirst()) return true }
        return false
    }

    /**
     * Batch existence check: returns the subset of [candidates] that exist
     * in the headword or reading tables. Uses 2 queries with IN (...) instead
     * of one query per candidate.
     */
    private fun batchCheckEntries(db: SQLiteDatabase, candidates: Set<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        // SQLite limit is 999 params; split into chunks if needed
        for (chunk in candidates.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            db.rawQuery("SELECT DISTINCT text FROM headword WHERE text IN ($placeholders)", args)
                .use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
            // Only query reading table for candidates not already found in headword
            val remaining = chunk.filter { it !in found }
            if (remaining.isNotEmpty()) {
                val ph2 = remaining.joinToString(",") { "?" }
                val args2 = remaining.toTypedArray()
                db.rawQuery("SELECT DISTINCT text FROM reading WHERE text IN ($ph2)", args2)
                    .use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
            }
        }
        return found
    }

    /** Query entries matching both a kanji form and a reading (narrowed
     *  search). Ranks by the sum of per-headword and per-reading rank
     *  scores, both precomputed at pack-build time from JMdict priority
     *  tags + position. No uk-bonus here — the kanji form is explicit user
     *  input that already disambiguates.
     *
     *  Falls back to legacy `entry.freq_score`-JOIN SQL when the on-disk
     *  pack lacks `reading.rank_score` (see [hasRankScore]). */
    private fun queryEntryIdsWithReading(db: SQLiteDatabase, word: String, reading: String): List<Long> {
        val ids = mutableListOf<Long>()
        val sql = if (hasRankScore(db)) RANKED_QUERY_KANJI_WITH_READING else LEGACY_QUERY_KANJI_WITH_READING
        db.rawQuery(sql, arrayOf(word, reading))
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Long> {
        val ids = mutableListOf<Long>()

        // Kanji-form path. NEW: rank by per-headword score (priority +
        // position penalty). OLD: ORDER BY entry.freq_score DESC via JOIN.
        val kanjiSql = if (hasRankScore(db)) RANKED_QUERY_KANJI else LEGACY_QUERY_KANJI
        db.rawQuery(kanjiSql, arrayOf(word))
            .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }

        if (ids.isEmpty()) {
            // Pure-kana fallback. RANKED: per-reading rank_score plus a
            // +1.5M uk-bonus when the matched reading is at position 0 AND
            // the entry has a uk-tagged sense applicable to this reading
            // (precomputed into reading.uk_applicable at build time, with
            // <stagr> restrictions respected). Validated empirically to
            // flip 此処 above 個々 for ここ without raising 鴇 above 時 for
            // とき (鴇's とき is at position=1, gate filters it out).
            // LEGACY: ORDER BY entry.freq_score DESC via JOIN — degraded
            // but functional ranking when rank_score is absent.
            val kanaSql = if (hasRankScore(db)) RANKED_QUERY_KANA else LEGACY_QUERY_KANA
            db.rawQuery(kanaSql, arrayOf(word))
                .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        }

        return ids
    }

    private fun buildResponse(
        db: SQLiteDatabase,
        entryIds: List<Long>,
        inflectionNote: String? = null
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

        val kanjiForms = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM headword WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) kanjiForms.add(c.getString(0)) }

        val readingForms = mutableListOf<String>()
        db.rawQuery(
            "SELECT text FROM reading WHERE entry_id=? ORDER BY position",
            arrayOf(idStr)
        ).use { c -> while (c.moveToNext()) readingForms.add(c.getString(0)) }

        val headwords = if (kanjiForms.isNotEmpty()) {
            kanjiForms.mapIndexed { i, k ->
                Headword(written = k, reading = readingForms.getOrNull(i) ?: readingForms.firstOrNull())
            }
        } else {
            readingForms.map { Headword(written = null, reading = it) }
        }
        if (headwords.isEmpty()) return null

        // Tatoeba example sentences keyed by sense_position. The `example`
        // table is optional: JA packs that predate the Tatoeba indexing
        // pass (build_jmdict.py without --tatoeba-dir) won't have it, so a
        // missing-table SQLiteException degrades silently to "no examples."
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
            slug = kanjiForms.firstOrNull() ?: readingForms.firstOrNull() ?: idStr,
            isCommon = isCommon,
            tags = emptyList(),
            jlpt = emptyList(),   // JMdict doesn't reliably carry JLPT levels
            headwords = headwords,
            senses = senses,
            freqScore = freqScore
        )
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    companion object {
        // The stored context is context.applicationContext, which lives for
        // the entire process lifetime and cannot leak an Activity — so the
        // StaticFieldLeak warning here is a false positive.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: DictionaryManager? = null

        fun get(context: Context): DictionaryManager =
            instance ?: synchronized(this) {
                instance ?: DictionaryManager(context.applicationContext).also { instance = it }
            }

        // ── Ranking SQL constants ─────────────────────────────────────────
        // Two parallel sets selected by [hasRankScore] per handle:
        //   RANKED_QUERY_* — uses per-row rank_score / uk_applicable columns;
        //                    full ranking incl. the pure-kana uk-bonus.
        //   LEGACY_QUERY_* — falls back to entry.freq_score JOIN when those
        //                    columns are absent on the on-disk pack.
        // The LEGACY path is degraded but functional; queries succeed and
        // the dictionary stays usable while the user upgrades to a pack
        // that carries the rank_score column.

        private const val RANKED_QUERY_KANJI = """
            SELECT entry_id FROM headword
            WHERE text = ?
            GROUP BY entry_id
            ORDER BY MAX(rank_score) DESC
            LIMIT 8
        """

        private const val RANKED_QUERY_KANA = """
            SELECT entry_id FROM reading
            WHERE text = ?
            GROUP BY entry_id
            ORDER BY MAX(
                rank_score
                + CASE WHEN position = 0 AND uk_applicable = 1
                       THEN 1500000 ELSE 0 END
            ) DESC
            LIMIT 8
        """

        private const val RANKED_QUERY_KANJI_WITH_READING = """
            SELECT h.entry_id
            FROM headword h
            JOIN reading r ON r.entry_id = h.entry_id
            WHERE h.text = ? AND r.text = ?
            GROUP BY h.entry_id
            ORDER BY MAX(h.rank_score + r.rank_score) DESC
            LIMIT 8
        """

        private const val LEGACY_QUERY_KANJI = """
            SELECT DISTINCT h.entry_id FROM headword h
            JOIN entry e ON e.id = h.entry_id
            WHERE h.text = ?
            ORDER BY e.freq_score DESC LIMIT 8
        """

        private const val LEGACY_QUERY_KANA = """
            SELECT DISTINCT r.entry_id FROM reading r
            JOIN entry e ON e.id = r.entry_id
            WHERE r.text = ?
            ORDER BY e.freq_score DESC LIMIT 8
        """

        private const val LEGACY_QUERY_KANJI_WITH_READING = """
            SELECT DISTINCT h.entry_id FROM headword h
            JOIN entry e ON e.id = h.entry_id
            JOIN reading r ON r.entry_id = h.entry_id
            WHERE h.text = ? AND r.text = ?
            ORDER BY e.freq_score DESC LIMIT 8
        """

        /**
         * Resolve meanings for [literal] in [targetLang] with English fallback.
         * Returns the meanings list plus the language code they actually came
         * from ("en" when we fell back, or the request was already English).
         * Empty list if neither the requested language nor English have a row.
         *
         * Stateless wrapper around the [kanji_meaning] schema so the lookup
         * order is testable in isolation from the [DictionaryManager]
         * singleton + filesystem cache.
         */
        internal fun resolveKanjiMeanings(
            database: SQLiteDatabase,
            literal: Char,
            targetLang: String,
        ): Pair<List<String>, String> {
            fun query(lang: String): List<String>? =
                database.rawQuery(
                    "SELECT meanings FROM kanji_meaning WHERE literal=? AND lang=?",
                    arrayOf(literal.toString(), lang),
                ).use { c ->
                    if (!c.moveToFirst()) null
                    else c.getString(0).split('\t').filter { it.isNotBlank() }
                }

            if (targetLang != "en") {
                query(targetLang)?.let { if (it.isNotEmpty()) return it to targetLang }
            }
            val english = query("en") ?: emptyList()
            return english to "en"
        }
    }
}
