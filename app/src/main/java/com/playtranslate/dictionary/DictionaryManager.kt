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

private const val TAG = "DictionaryManager"

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
        val database = ensureOpen()
            ?: return@withContext Deinflector.tokenize(text).map { TokenWithReading(it, it, null) }

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
        "名詞", "動詞", "形容詞", "形容動詞", "副詞", "感動詞"
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

        // 1. Exact match narrowed by reading (if available)
        if (reading != null) {
            val narrowedIds = queryEntryIdsWithReading(database, word, reading)
            if (narrowedIds.isNotEmpty()) {
                Log.d(TAG, "lookup($word, reading=$reading): narrowed ids=$narrowedIds")
                return@withContext buildResponse(database, narrowedIds)
            }
        }

        // 2. Exact match (headword or reading table, no reading filter)
        val directIds = queryEntryIds(database, word)
        if (directIds.isNotEmpty()) {
            Log.d(TAG, "lookup($word): exact match ids=$directIds")
            return@withContext buildResponse(database, directIds)
        }

        // 2. Try de-inflected candidates (first dictionary hit wins)
        for (candidate in Deinflector.candidates(word)) {
            val ids = queryEntryIds(database, candidate.text)
            if (ids.isNotEmpty()) {
                return@withContext buildResponse(database, ids, candidate.reason)
            }
        }

        null
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
        database.rawQuery(
            "SELECT on_readings, kun_readings, jlpt, grade, stroke_count FROM kanjidic WHERE literal=?",
            arrayOf(literal.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val onReadings   = c.getString(0).split(',').filter { it.isNotBlank() }
            val kunReadings  = c.getString(1).split(',').filter { it.isNotBlank() }
            val jlpt         = c.getInt(2)
            val grade        = c.getInt(3)
            val strokeCount  = c.getInt(4)

            val (meanings, resolvedLang) = resolveKanjiMeanings(database, literal, targetLang)
            if (meanings.isEmpty()) return@withContext null
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

    /** Returns false if the on-device DB is missing required tables/columns.
     *
     *  Must probe `headword` (not `kanji`) post-rename. Pre-rename DBs — both
     *  the legacy bundled JMdict and any downloaded v1-era pack — don't have
     *  that table, so this returns false and the caller drops the DB; the
     *  stale-install cleanup in [LanguagePackStore.isInstalled] then deletes
     *  the file and routes the user through a fresh download. */
    private fun isSchemaUpToDate(dbFile: File): Boolean {
        return try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { tempDb ->
                tempDb.rawQuery("SELECT freq_score FROM entry LIMIT 1", null).use { }
                tempDb.rawQuery("SELECT text FROM headword LIMIT 1", null).use { }
                tempDb.rawQuery("SELECT misc FROM sense LIMIT 1", null).use { }
                tempDb.rawQuery("SELECT literal FROM kanjidic LIMIT 1", null).use { }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

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

    /** Query entries matching both a kanji form and a reading (narrowed search). */
    private fun queryEntryIdsWithReading(db: SQLiteDatabase, word: String, reading: String): List<Long> {
        val ids = mutableListOf<Long>()
        db.rawQuery(
            """SELECT DISTINCT h.entry_id
               FROM headword h
               JOIN entry e ON e.id = h.entry_id
               JOIN reading r ON r.entry_id = h.entry_id
               WHERE h.text = ? AND r.text = ?
               ORDER BY e.freq_score DESC LIMIT 8""",
            arrayOf(word, reading)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        return ids
    }

    private fun queryEntryIds(db: SQLiteDatabase, word: String): List<Long> {
        val ids = mutableListOf<Long>()

        db.rawQuery(
            "SELECT DISTINCT h.entry_id FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.text = ? ORDER BY e.freq_score DESC LIMIT 8",
            arrayOf(word)
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }

        if (ids.isEmpty()) {
            db.rawQuery(
                "SELECT DISTINCT r.entry_id FROM reading r JOIN entry e ON e.id = r.entry_id WHERE r.text = ? ORDER BY e.freq_score DESC LIMIT 8",
                arrayOf(word)
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
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
