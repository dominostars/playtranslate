package com.playtranslate.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.language.InflectionTag
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.KanjiDetail
import com.playtranslate.model.Sense
import com.playtranslate.model.kanaOnlyFrom
import com.playtranslate.model.preferDisplayable
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
    /** Hiragana reading. Single tokens: the tokenizer's surface reading.
     *  Re-globbed phrases (exact joins): the window tokens' readings
     *  concatenated — the homograph-disambiguation hint `lookup()` narrows
     *  by, with rank-order fallback when no entry matches it. Null for
     *  lemma-variant phrases and spans with reading-less members. */
    val reading: String?,
    /** Conjugation tags the [surface] expresses (e.g. 言わせて → [Causative,
     *  Te-form]); empty for uninflected words and phrase-matched spans. */
    val inflections: List<InflectionTag> = emptyList(),
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

    /** Per-handle capability cache for `headword.ke_pri`, parallel to
     *  [rankScoreSupport]. Both columns were added together in the ja-v2
     *  schema, but caching independently keeps each call site honest about
     *  what it actually needs and survives any future divergence. When
     *  ke_pri is absent (v1 packs), [buildEntry] leaves `Headword.hasPriority`
     *  false; no display decision reads it any more (see `isKanaOnly`), so
     *  v1 and v2 packs render headwords identically. */
    private val kePriSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val kePriSupportLock = Any()

    /** Per-handle capability cache for `reading.no_kanji`, parallel to
     *  [kePriSupport]. Probed once per open database, NOT per [buildEntry] —
     *  word lookup materializes many entries per sentence, so a
     *  `PRAGMA table_info` on each would tax interactive lookup latency.
     *  Absent on pre-column packs → [buildHeadwords] keeps positional pairing. */
    private val noKanjiSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val noKanjiSupportLock = Any()

    /** Per-handle capability cache for `headword.ke_inf` (ja-v5): the kanji
     *  form's JMdict info tags, read by [loadHeadwords] into
     *  `Headword.isSearchOnly` / `isRareForm`. Absent (v4 and older) → every
     *  flag false, every spelling display-eligible: the prior behaviour.
     *  Deliberately NOT part of [JmdictSchemaProbe]: probing it there would
     *  classify every v4 install as FORCE and close the additive-upgrade
     *  path (see project_pack_upgrade_flow). */
    private val keInfSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val keInfSupportLock = Any()

    /** Per-handle capability cache for `reading.uk_applicable`, parallel to
     *  [kePriSupport]. Shipped in the same ja-v2 schema as `rank_score`, but
     *  [batchCheckPhrases] dereferences it in its own SQL, so it probes its
     *  own column instead of trusting rank_score's presence to imply it
     *  (Codex review find, 2026-08-28). Absent → the kana-native tier fails
     *  open while the rank floor stays. */
    private val ukApplicableSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val ukApplicableSupportLock = Any()

    /** Per-handle capability cache for `headword.rank_score` — the KANJI-side
     *  counterpart of [rankScoreSupport]. Gates [PhraseMembership.priorityHeadwords]:
     *  a bare headword match isn't enough to admit a [Suspicion.CONVERB_CUT]
     *  candidate (押して is a genuine but unranked headword) — only a headword
     *  the pack ALSO ranks priority (`rank_score > 0`) may. Absent → fails
     *  open to plain headword membership. */
    private val headwordRankScoreSupport = WeakHashMap<SQLiteDatabase, Boolean>()
    private val headwordRankScoreSupportLock = Any()

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

    private fun hasKePri(db: SQLiteDatabase): Boolean {
        synchronized(kePriSupportLock) {
            kePriSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "headword", "ke_pri")
            kePriSupport[db] = supports
            return supports
        }
    }

    private fun hasUkApplicable(db: SQLiteDatabase): Boolean {
        synchronized(ukApplicableSupportLock) {
            ukApplicableSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "reading", "uk_applicable")
            ukApplicableSupport[db] = supports
            return supports
        }
    }

    private fun hasNoKanji(db: SQLiteDatabase): Boolean {
        synchronized(noKanjiSupportLock) {
            noKanjiSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "reading", "no_kanji")
            noKanjiSupport[db] = supports
            return supports
        }
    }

    private fun hasKeInf(db: SQLiteDatabase): Boolean {
        synchronized(keInfSupportLock) {
            keInfSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "headword", "ke_inf")
            keInfSupport[db] = supports
            return supports
        }
    }

    private fun hasHeadwordRankScore(db: SQLiteDatabase): Boolean {
        synchronized(headwordRankScoreSupportLock) {
            headwordRankScoreSupport[db]?.let { return it }
            val supports = checkColumnExists(db, "headword", "rank_score")
            headwordRankScoreSupport[db] = supports
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
     *
     * [phraseOracle], when non-null, is a second membership gate for n-gram
     * phrase candidates that miss JMdict (e.g. imported Yomitan term
     * dictionaries — expressions JMdict doesn't list). Only candidates
     * containing at least one kanji are offered to it: the oracle has no
     * analog of the JMdict `rank_score >= 0` guard, so kana-only joins
     * (に+は …) could otherwise fuse on a coincidental entry.
     */
    suspend fun tokenizeWithSurfaces(
        text: String,
        phraseOracle: (suspend (Set<String>) -> Set<String>)? = null,
    ): List<TokenWithReading> = withContext(Dispatchers.IO) {
        val tokens = SudachiJapaneseTokenizer.Provider.analyze(text)
        val spans = reglobSpansForTokens(tokens, phraseOracle)
            ?: return@withContext contentOnlyTokens(tokens)
        val result = spans.map {
            TokenWithReading(it.surface, it.lookupForm, it.reading, it.inflections)
        }
        Log.d(TAG, "tokenizeWithSurfaces: ${result.map { "(${it.surface} → ${it.lookupForm} [${it.reading}])" }}")
        result
    }

    /**
     * The n-gram re-glob over pre-analyzed [tokens], span-native: candidate
     * generation, batched membership (JMdict + optional imported-dict
     * oracle), then the greedy matcher. Null when the JMdict DB isn't ready
     * — callers fall back to content-token-only behavior. Exposed for the
     * JA annotator, which must reuse ONE analyze() pass for the whole
     * annotation instead of re-tokenizing per consumer.
     */
    internal suspend fun reglobSpansForTokens(
        tokens: List<JaToken>,
        phraseOracle: (suspend (Set<String>) -> Set<String>)? = null,
        /** Phrase lookupForms that must NOT fuse — the member-words split
         *  ([com.playtranslate.language.JapaneseEngine.memberWordsOf]) passes
         *  the expression being decomposed, so re-globbing its own tokens
         *  can't just fuse the whole expression back together. Filtered at
         *  candidacy, so shorter member-level joins (日本+語 → 日本語) still
         *  win their windows. */
        excludePhrases: Set<String> = emptySet(),
    ): List<ReglobSpan>? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null

        // Batch existence query: candidate N-gram phrases PLUS each content
        // token's dictionaryForm/normalizedForm (layer 1 — lets us pick the
        // form that actually resolves, e.g. キミ→君).
        val candidates = phraseCandidatesFor(tokens)
            .filter { it.lookupForm !in excludePhrases }
        // Single content-token forms (layer 1: dictionaryForm / normalizedForm).
        val formCandidates = mutableSetOf<String>()
        for (t in tokens) {
            if (!t.category.isContent) continue
            if (isLookupWorthy(t.dictionaryForm)) formCandidates.add(t.dictionaryForm)
            if (t.normalizedForm != t.dictionaryForm && isLookupWorthy(t.normalizedForm)) {
                formCandidates.add(t.normalizedForm)
            }
        }
        // Tiered membership (headword / primary reading / kana-native reading)
        // feeds the per-candidate Suspicion admissibility below. Single forms
        // match any headword/reading — lookup ranking disambiguates those later.
        val known = database.withRefcount {
            val phraseStrings = candidates.mapTo(mutableSetOf()) { it.lookupForm }
            batchCheckPhrases(database, phraseStrings) to batchCheckEntries(database, formCandidates)
        } ?: return@withContext null
        val (membership, knownForms) = known

        // Structural admissibility: joins that contradict Sudachi's parse are
        // vetoed (conjugation cuts) or held to the kana-native tier (function
        // runs) BEFORE the oracle sees them — a conjugation cut is wrong no
        // matter which dictionary lists the string. See [Suspicion].
        val admissible = admissiblePhraseCandidates(
            candidates, membership.headwords, membership.kanaNativeReadings,
            membership.priorityHeadwords,
        )
        var knownPhrases = membership.headwords + membership.readings

        // Imported-dictionary gate for JMdict misses. Runs OUTSIDE withRefcount
        // (the oracle suspends; the refcount lambda must not). Kanji-only: see doc.
        if (phraseOracle != null) {
            val forOracle = admissible.mapTo(mutableSetOf()) { it.lookupForm }
                .filterTo(mutableSetOf()) { oracleEligible(it, knownPhrases) }
            if (forOracle.isNotEmpty()) knownPhrases = knownPhrases + phraseOracle(forOracle)
        }

        reglobSpans(tokens, admissible, knownPhrases, knownForms)
    }

    /** Fallback when the JMdict DB isn't ready: content words on their own
     *  (no n-gram phrase detection, no normalizedForm probing). */
    private fun contentOnlyTokens(tokens: List<JaToken>): List<TokenWithReading> =
        tokens.filter { it.category.isContent }
            .map { TokenWithReading(it.surface, it.dictionaryForm, it.reading?.let(Deinflector::katakanaToHiragana)) }
            .filter { isLookupWorthy(it.lookupForm) }

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
     * Ranked prefix-completion candidates for a partial [query], for the
     * dictionary-search screen. Returns up to [limit] entries whose kanji
     * headword OR kana reading begins with [query], ordered exact-match-first
     * then by per-row [rank_score] (the same signal [queryEntryIds] ranks by),
     * each as a [TokenWithReading] whose [lookupForm] re-resolves to the full
     * entry via [lookup].
     *
     * Kana fold: the reading side scans both the hiragana and katakana
     * renderings of [query], because JMdict stores loanword readings in
     * katakana (テレビ) while users type hiragana (てれび).
     *
     * Falls back to a `entry.freq_score`-ordered scan on v1 packs without
     * `rank_score` (see [hasRankScore]). Empty when the DB isn't ready.
     */
    suspend fun searchPrefix(query: String, limit: Int = 20): List<TokenWithReading> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val database = ensureOpen() ?: return@withContext emptyList()
        database.withRefcount {
            val entryIds = prefixEntryIds(database, q, limit, hasRankScore(database))
            val seen = HashSet<String>()
            entryIds.mapNotNull { id ->
                val (written, reading) = primaryFormsForEntry(database, id)
                val display = written ?: reading ?: return@mapNotNull null
                if (!seen.add(display)) return@mapNotNull null
                TokenWithReading(
                    surface = display,
                    lookupForm = display,
                    reading = if (written != null) reading else null,
                )
            }
        } ?: emptyList()
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

                // Meanings may be empty (rare kanji with readings/stats but
                // no gloss in any pack language). Returned anyway: the JA
                // engine gates gloss-less rows itself, AFTER it has had the
                // chance to merge meanings from an imported dictionary —
                // nulling here would throw away the readings/stats floor.
                val (meanings, resolvedLang) = resolveKanjiMeanings(database, literal, targetLang)
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

    /**
     * Like [batchCheckEntries], but for multi-token N-gram phrases, tiered so
     * the caller can apply per-candidate [Suspicion] admissibility:
     *
     *  - [PhraseMembership.headwords] — matches a written form. Kanji surfaces
     *    can't collide by pronunciation; always admissible for a
     *    [Suspicion.CONJUGATION_CUT] or [Suspicion.FUNCTION_RUN] candidate.
     *  - [PhraseMembership.priorityHeadwords] — subset of [headwords] the
     *    pack ALSO ranks as priority (`headword.rank_score > 0`). The ONLY
     *    tier a [Suspicion.CONVERB_CUT] candidate may fuse from — see its doc.
     *  - [PhraseMembership.readings] — matches a PRIMARY reading
     *    (`rank_score >= 0`). The rank floor stops marginal positional
     *    readings (ここ+の → 九's position-2 counter stem, rank -20000).
     *  - [PhraseMembership.kanaNativeReadings] — subset of [readings] whose
     *    entry is a kana word: uk-tagged for that reading (`uk_applicable`,
     *    <stagr>-aware) or with no kanji headword at all. だから/でも/かな
     *    qualify; 手織り(ており)/殿(との) don't. This is the only tier a
     *    [Suspicion.FUNCTION_RUN] candidate may fuse from.
     *
     * Each SQL variant is guarded by a probe for the columns IT dereferences
     * (Codex review find: rank_score's presence must not be trusted to imply
     * uk_applicable, even though ja-v2 shipped them together). Degradation
     * ladder on older/partial schemas: no `uk_applicable` → rank floor
     * stays, kanaNativeReadings FAILS OPEN to the full reading set; no
     * `reading.rank_score` either → bare existence, everything fails open
     * (degraded pre-v2 behavior, same precedent as the ranking SQL; such
     * packs are force-upgraded); no `headword.rank_score` → priorityHeadwords
     * FAILS OPEN to the full headword set, so a CONVERB_CUT candidate admits
     * on a bare headword match exactly as a CONJUGATION_CUT does. The
     * [Suspicion.CONJUGATION_CUT] veto is structural and applies regardless
     * of pack schema.
     */
    private fun batchCheckPhrases(db: SQLiteDatabase, candidates: Set<String>): PhraseMembership {
        if (candidates.isEmpty()) return PhraseMembership(emptySet(), emptySet(), emptySet())
        val ranked = hasRankScore(db)
        val tiered = ranked && hasUkApplicable(db)
        val headwordRanked = hasHeadwordRankScore(db)
        val headwords = mutableSetOf<String>()
        val priorityHeadwords = mutableSetOf<String>()
        val readings = mutableSetOf<String>()
        val kanaNative = mutableSetOf<String>()
        for (chunk in candidates.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            if (headwordRanked) {
                db.rawQuery(
                    "SELECT text, MAX(rank_score) FROM headword WHERE text IN ($placeholders) GROUP BY text",
                    args,
                ).use { c ->
                    while (c.moveToNext()) {
                        val text = c.getString(0)
                        headwords.add(text)
                        if (c.getInt(1) > 0) priorityHeadwords.add(text)
                    }
                }
            } else {
                db.rawQuery("SELECT DISTINCT text FROM headword WHERE text IN ($placeholders)", args)
                    .use { c -> while (c.moveToNext()) headwords.add(c.getString(0)) }
                priorityHeadwords.addAll(headwords)
            }
            val remaining = chunk.filter { it !in headwords }
            if (remaining.isEmpty()) continue
            val ph2 = remaining.joinToString(",") { "?" }
            val args2 = remaining.toTypedArray()
            if (tiered) {
                db.rawQuery("$RANKED_PHRASE_READING_SQL ($ph2) GROUP BY r.text", args2).use { c ->
                    while (c.moveToNext()) {
                        readings.add(c.getString(0))
                        if (c.getInt(1) == 1) kanaNative.add(c.getString(0))
                    }
                }
            } else {
                val sql = if (ranked) {
                    "SELECT DISTINCT text FROM reading WHERE rank_score >= 0 AND text IN ($ph2)"
                } else {
                    "SELECT DISTINCT text FROM reading WHERE text IN ($ph2)"
                }
                db.rawQuery(sql, args2).use { c ->
                    while (c.moveToNext()) {
                        val text = c.getString(0)
                        readings.add(text)
                        kanaNative.add(text)
                    }
                }
            }
        }
        return PhraseMembership(headwords, readings, kanaNative, priorityHeadwords)
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

    /**
     * The entry's headword list — kanji forms + reading forms paired by
     * [buildHeadwords]. Shared VERBATIM by [buildEntry] and
     * [lookupReadingsOnly], so the readings-only path can never drift from
     * the full path's pairing.
     *
     * Kanji headwords: v2 packs carry `ke_pri` per form, surfaced as
     * `Headword.hasPriority` (informational: the kana-vs-kanji display rule
     * in `isKanaOnly` no longer reads it); v5 packs add `ke_inf`, the form's
     * JMdict info tags, surfaced as `isSearchOnly` (sK) and `isRareForm`
     * (rK/iK/oK/io/ik) so display can skip search-only spellings and rank
     * rare ones last; older packs degrade to "no priority known" and every
     * spelling display-eligible. Readings carry `uk_applicable` (v2+) as
     * `Headword.ukApplicable`, the stagr-aware per-reading uk scope that
     * narrows the entry-wide kana-only verdict at display. `no_kanji`
     * (JMdict re_nokanji) lets [buildHeadwords] drop readings never written
     * with the kanji; `rank_score` rides along for the word-detail reading
     * rows. ORDER BY position keeps `headwords` position-ordered —
     * firstOrNull() == primary, unchanged app-wide.
     */
    private fun loadHeadwords(db: SQLiteDatabase, idStr: String): List<Headword> {
        val kanjiForms = mutableListOf<JmKanjiForm>()
        val kePriColumn = hasKePri(db)
        val keInfColumn = hasKeInf(db)
        val kanjiCols = buildList {
            add("text")
            if (kePriColumn) add("ke_pri")
            if (keInfColumn) add("ke_inf")
        }.joinToString(", ")
        db.rawQuery(
            "SELECT $kanjiCols FROM headword WHERE entry_id=? ORDER BY position",
            arrayOf(idStr),
        ).use { c ->
            val priIdx = c.getColumnIndex("ke_pri")
            val infIdx = c.getColumnIndex("ke_inf")
            while (c.moveToNext()) {
                val info = if (infIdx >= 0) parseKeInf(c.getString(infIdx)) else emptySet()
                kanjiForms.add(
                    JmKanjiForm(
                        text = c.getString(0),
                        hasPriority = priIdx >= 0 && c.getString(priIdx).orEmpty().isNotEmpty(),
                        searchOnly = SEARCH_ONLY_TAG in info,
                        rareForm = info.any { it in RARE_FORM_TAGS },
                    )
                )
            }
        }
        val noKanjiColumn = hasNoKanji(db)
        val rankScoreColumn = hasRankScore(db)
        val ukApplicableColumn = hasUkApplicable(db)
        val readingCols = buildList {
            add("text")
            if (noKanjiColumn) add("no_kanji")
            if (rankScoreColumn) add("rank_score")
            if (ukApplicableColumn) add("uk_applicable")
        }.joinToString(", ")
        val readingForms = mutableListOf<JmReadingForm>()
        db.rawQuery(
            "SELECT $readingCols FROM reading WHERE entry_id=? ORDER BY position",
            arrayOf(idStr),
        ).use { c ->
            val noKanjiIdx = c.getColumnIndex("no_kanji")
            val rankIdx = c.getColumnIndex("rank_score")
            val ukIdx = c.getColumnIndex("uk_applicable")
            while (c.moveToNext()) {
                readingForms.add(
                    JmReadingForm(
                        text = c.getString(0),
                        noKanji = noKanjiIdx >= 0 && c.getInt(noKanjiIdx) != 0,
                        rankScore = if (rankIdx >= 0) c.getInt(rankIdx) else 0,
                        ukApplicable = ukIdx < 0 || c.getInt(ukIdx) != 0,
                    )
                )
            }
        }
        return buildHeadwords(kanjiForms, readingForms, noKanjiColumn)
    }

    /**
     * Readings-only resolution for the annotator: the SAME entry choice as
     * [lookup] (narrowed → direct → deinflection, identical ranked SQL) and
     * the SAME headword pairing ([loadHeadwords]) — but no senses, examples,
     * or imported enrichment. Returns a senses-free [DictionaryEntry]
     * skeleton (packId + headwords) or null when the pack has nothing —
     * callers fall back to the full two-store lookup, where imported-
     * dictionary synthesis may still resolve. Parity with the full path's
     * entry choice holds BY CONSTRUCTION (shared SQL, shared pairing, and
     * YomitanEnrichment.mergeImportedTerms anchors on the first pack entry
     * without reordering); cost is 2–3 indexed queries, which is what keeps
     * FULL-depth annotation affordable on the live cycle.
     */
    suspend fun lookupReadingsOnly(word: String, reading: String? = null): DictionaryEntry? =
        withContext(Dispatchers.IO) {
            val database = ensureOpen() ?: return@withContext null
            database.withRefcount {
                var id: Long? = null
                if (reading != null) {
                    id = queryEntryIdsWithReading(database, word, reading).firstOrNull()
                }
                if (id == null) id = queryEntryIds(database, word).firstOrNull()
                if (id == null) {
                    for (candidate in Deinflector.candidates(word)) {
                        id = queryEntryIds(database, candidate.text).firstOrNull()
                        if (id != null) break
                    }
                }
                val entryId = id ?: return@withRefcount null
                val headwords = loadHeadwords(database, entryId.toString())
                if (headwords.isEmpty()) return@withRefcount null
                DictionaryEntry(
                    slug = headwords.preferDisplayable()?.let { it.written ?: it.reading }
                        ?: entryId.toString(),
                    packId = entryId,
                    isCommon = null,
                    tags = emptyList(),
                    jlpt = emptyList(),
                    headwords = headwords,
                    senses = emptyList(),
                )
            }
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

        val headwords = loadHeadwords(db, idStr)
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
                val posList   = PosVocabulary.parse(c.getString(1))
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
            slug = headwords.preferDisplayable()?.let { it.written ?: it.reading } ?: idStr,
            packId = id,
            isCommon = isCommon,
            tags = emptyList(),
            jlpt = emptyList(),   // JMdict doesn't reliably carry JLPT levels
            headwords = headwords,
            senses = senses,
            freqScore = freqScore,
            isKanaOnly = kanaOnlyFrom(senses),
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
         * Max tokens the n-gram re-glob fuses into one JMdict-lookup phrase.
         * JMdict lists expressions (かもしれない, わけにはいかない, …) that Sudachi's
         * short-unit output splits into 4-6 morphemes; kuromoji split coarser, so
         * 4 used to suffice, but Sudachi pushed common expressions past it
         * (かもしれません=5, わけにはいかない=5). 8 covers the observed span with
         * headroom; the rank>=0 guard in [batchCheckPhrases] and the [Suspicion]
         * admissibility gate keep the wider window from re-introducing
         * reading-coincidence globs. Expressions that BEGIN inside a host word's
         * conjugation (ないわけにはいかない swallowing 行か's ない) are deliberately
         * OUT OF SCOPE — the app surfaces words, not grammar patterns — so the
         * window only needs to cover clean-start spans. Not derived from the
         * lexicon — a build-time max-span computation could replace it.
         */
        private const val REGLOB_WINDOW = 8

        internal fun isLookupWorthy(token: String): Boolean {
            if (token.isBlank()) return false
            if (token.all { it.code <= 0x007F }) return false
            if (token.length == 1 && token[0] in 'ぁ'..'ゖ') return false
            return true
        }

        /**
         * Whether a phrase candidate that missed JMdict may be offered to the
         * imported-dictionary phrase oracle. Kanji required: the oracle has
         * no analog of JMdict's `rank_score >= 0` guard, so a kana-only join
         * (に+は …) could fuse on a coincidental imported entry.
         */
        internal fun oracleEligible(lookupForm: String, knownPhrases: Set<String>): Boolean =
            lookupForm !in knownPhrases && lookupForm.any(Deinflector::isKanji)

        /**
         * One phrase candidate from the n-gram re-glob: the string to check
         * against a membership set (JMdict / imported dicts), plus how the
         * match materializes — surface span and tokens to advance past. Span
         * bookkeeping is computed once here so the matcher does no arithmetic.
         */
        internal data class PhraseCandidate(
            /** Window start in the token list. */
            val startIndex: Int,
            /** Tokens inside the window (excluding any trailing folded glue). */
            val windowLen: Int,
            /** String checked against the membership set; becomes the result's lookupForm. */
            val lookupForm: String,
            /** Actual input surfaces for the consumed span. */
            val surface: String,
            /** Total tokens to advance past on a match (windowLen + folded glue). */
            val tokensConsumed: Int,
            /** False = exact surface join; true = last-token lemma variant. */
            val isVariant: Boolean,
            /** How this window collides with the morphological parse, or null
             *  for a clean join. Decides admissibility — see [Suspicion]. */
            val suspicion: Suspicion? = null,
        )

        /**
         * A phrase window's structural collision with Sudachi's parse. The
         * analyzer already arbitrated the sentence's segmentation on trained
         * costs; a join existing in JMdict is not by itself grounds to overturn
         * it (て+おり is 手織り's reading — corpus-validated as the dominant
         * misglob class: した→下, してい→指定, てい→体…).
         *
         * [CONJUGATION_CUT] — the window severs an inflection: it starts at a
         * 接続助詞 with attachable material to its left (がどう after た; at a
         * line start or after punctuation nothing can be severed, so
         * 、ていうか stays fusable), starts on glue bound to an incomplete stem
         * (いただい|ており, 言って|たな), is itself an incomplete stem plus its
         * own AUXILIARY glue (した; see [CONVERB_CUT] for particle glue), or
         * ends at an incomplete stem whose glue sits just outside the window
         * (ことし|て, となり|ます). Vetoed outright: JMdict entries reachable
         * only this way are conjugation-spanning grammar patterns
         * (ないわけにはいかない) or reading coincidences — neither is a WORD the
         * app is trying to surface.
         *
         * [CONVERB_CUT] — an incomplete content stem followed by PARTICLE
         * glue only (押し|て, 従っ|て) — a fossilized-converb shape. Unlike
         * CONJUGATION_CUT's auxiliary case (知ら+せる→知らせる, a real v1 verb
         * that AGREES with the parse), JMdict genuinely lists exact joins of
         * this shape as headwords (押して, adv "forcibly") that DISAGREE with
         * it, so a written match needs the extra evidence of a priority tag
         * before overriding the fallback. Measured over JMdict 3.6.2 ×
         * SudachiDict-core (2889c7f8): of 800 headwords sharing the broader
         * stem+glue shape, restricting to particle-only glue selects 207,
         * none of which is a plain verb entry once the priority bar drops
         * the unranked ones — the 593 auxiliary-glue forms are untouched.
         *
         * "Incomplete" = 連用形/未然形/語幹 — forms that grammatically require
         * a continuation. 終止形-adjacent joins stay clean, which is what
         * keeps かもしれない matchable after 言われる.
         *
         * [FUNCTION_RUN] — no content morpheme in the window: pure
         * particle/aux(/prefix) runs. Not a conjugation matter — Sudachi
         * doesn't lexicalize compound function words that JMdict rightly
         * treats as words (だから, でも, かな, のか). Admitted only when the
         * matched entry is itself a kana word (uk-tagged or kanji-less),
         * which separates those from kanji readings with the same shape
         * (との=殿, なん=南, にお=鳰). Grammar cannot make this call — it is
         * a fact about the lexicon, not the sentence.
         *
         * [CONJUGATION_CUT]/[FUNCTION_RUN] validated against the P5 500-line
         * corpus (2026-08-28 A/B harness): 99/501 lines change, all
         * garbage-removal; だから/でも/かな/かもしれない/ストレスかいしょう keep
         * fusing.
         */
        internal enum class Suspicion { CONJUGATION_CUT, CONVERB_CUT, FUNCTION_RUN }

        /** Tiered phrase membership from [batchCheckPhrases] — see its doc. */
        internal data class PhraseMembership(
            val headwords: Set<String>,
            val readings: Set<String>,
            val kanaNativeReadings: Set<String>,
            /** Subset of [headwords] with `headword.rank_score > 0`. The score
             *  is built at pack time (scripts/build_jmdict.py,
             *  compute_headword_rank_score): +1M for a top-tier ke_pri tag
             *  (ichi1/news1/gai1/spec1/spec2), +1M for two or more frequency
             *  tags, minus 10k per headword position. So "> 0" means a
             *  top-tier or doubly-tagged form, NOT any ke_pri tag: 対して
             *  (news2,nf33) and 概して (news2,nf28) carry tags and score 0. The
             *  only tier a [Suspicion.CONVERB_CUT] candidate may fuse
             *  from — see [admissiblePhraseCandidates]. */
            val priorityHeadwords: Set<String> = emptySet(),
        )

        /**
         * Drop candidates whose [Suspicion] their membership tier can't
         * license: converb cuts (押して) need a PRIORITY headword; conjugation
         * cuts (した/知らせる) and function runs admit via any headword, plus a
         * kana-native reading for function runs. Clean candidates pass
         * through. Pure; the matcher then needs no admissibility knowledge.
         */
        internal fun admissiblePhraseCandidates(
            candidates: List<PhraseCandidate>,
            headwords: Set<String>,
            kanaNativeReadings: Set<String>,
            priorityHeadwords: Set<String> = emptySet(),
        ): List<PhraseCandidate> = candidates.filter { c ->
            when (c.suspicion) {
                null -> true
                Suspicion.CONJUGATION_CUT -> c.lookupForm in headwords
                Suspicion.CONVERB_CUT -> c.lookupForm in priorityHeadwords
                Suspicion.FUNCTION_RUN ->
                    c.lookupForm in headwords || c.lookupForm in kanaNativeReadings
            }
        }

        /** Inflection-form prefixes that grammatically require a continuation.
         *  A stem in one of these cannot end a word's surface on its own. */
        private val INCOMPLETE_INFLECTIONS = arrayOf("連用形", "未然形", "語幹")

        private val JaToken.hasIncompleteInflection: Boolean
            get() = inflectionForm?.let { f -> INCOMPLETE_INFLECTIONS.any(f::startsWith) } == true

        /**
         * Classify a candidate window's [Suspicion]. Lemma variants are never
         * suspect: candidate generation already restricts them to content
         * starts, and their whole mechanism is deliberate lemma-swap + glue
         * folding of the final stem.
         */
        internal fun suspicionFor(tokens: List<JaToken>, start: Int, windowLen: Int, isVariant: Boolean): Suspicion? {
            if (isVariant) return null
            val first = tokens[start]
            if (first.category.isConjugationGlue) {
                if (start > 0) {
                    val prev = tokens[start - 1]
                    // A 接続助詞-initial window severs only when there is
                    // attachable material to its left. Postpositions can't
                    // attach across punctuation or a line start, so a て
                    // there is expression-initial (、ていうか) — nothing to
                    // cut; the mirror shape below still protects clipped
                    // fragments (line-initial ており|ます). がどう keeps its
                    // veto: its neighbor た is real material.
                    if (first.isConjunctiveParticle && !prev.isPunctuation) {
                        return Suspicion.CONJUGATION_CUT
                    }
                    if (prev.isConjunctiveParticle) return Suspicion.CONJUGATION_CUT
                    if ((prev.category == JaCategory.AUX || prev.category.isContent) &&
                        prev.hasIncompleteInflection
                    ) return Suspicion.CONJUGATION_CUT
                }
                // Clean-context glue start: fall through to the mirror /
                // function-run shapes below.
            } else if (first.category.isContent && first.category.startsConjugation &&
                first.hasIncompleteInflection && tokens[start + 1].category.isConjugationGlue
            ) {
                // PARTICLE-only glue disagrees with the parse about part of
                // speech (押し|て); AUX glue derives a real word that agrees
                // with it (知ら|せる→知らせる). See [Suspicion.CONVERB_CUT].
                return if ((start + 1 until start + windowLen).all {
                        tokens[it].category == JaCategory.PARTICLE
                    }
                ) {
                    Suspicion.CONVERB_CUT
                } else {
                    Suspicion.CONJUGATION_CUT
                }
            }
            val last = tokens[start + windowLen - 1]
            if (last.category.isContent && last.category.startsConjugation &&
                last.hasIncompleteInflection &&
                start + windowLen < tokens.size &&
                tokens[start + windowLen].category.isConjugationGlue
            ) return Suspicion.CONJUGATION_CUT
            if ((start until start + windowLen).all {
                    tokens[it].category.isConjugationGlue || tokens[it].category == JaCategory.OTHER
                }
            ) return Suspicion.FUNCTION_RUN
            return null
        }

        /**
         * Generate every n-gram phrase candidate (REGLOB_WINDOW down to 2)
         * for the token stream. Pure — no database access; the caller checks
         * the candidates' lookupForms for membership.
         *
         * Two candidate shapes per window:
         *  - exact: the surfaces joined as-is (かもしれない);
         *  - lemma variant: for windows ENDING at an inflected conjugating
         *    content token, the last surface is swapped for its
         *    dictionaryForm (気+に+なっ → 気になる), and the trailing
         *    auxiliary/particle glue (た) is folded into the surface span /
         *    advance count — mirroring the single-token folding in
         *    [reglobTokens]. This lets dictionary headwords match inflected
         *    expressions (気になった) the exact join can't.
         */
        internal fun phraseCandidatesFor(tokens: List<JaToken>): List<PhraseCandidate> {
            val surfaces = tokens.map { it.surface }
            val out = mutableListOf<PhraseCandidate>()
            for (i in tokens.indices) {
                val maxN = minOf(REGLOB_WINDOW, tokens.size - i)
                for (n in maxN downTo 2) {
                    val phrase = surfaces.subList(i, i + n).joinToString("")
                    if (isLookupWorthy(phrase)) {
                        out.add(PhraseCandidate(
                            startIndex = i, windowLen = n, lookupForm = phrase,
                            surface = phrase, tokensConsumed = n, isVariant = false,
                            suspicion = suspicionFor(tokens, i, n, isVariant = false),
                        ))
                    }
                    // Lemma variant: window ends at an inflected verb/i-adjective.
                    // Must also START at a content token — expressions don't begin
                    // mid-grammar, and particle-led variants produce misglobs that
                    // swallow the particle (遠慮[はいらない] → はいる i.e. 入る;
                    // 掲示[がされて] → がする). Exact joins keep matching from any
                    // token (かもしれない starts at a particle by design).
                    if (!tokens[i].category.isContent) continue
                    val last = tokens[i + n - 1]
                    if (!last.category.isContent || !last.category.startsConjugation) continue
                    if (last.surface == last.dictionaryForm) continue
                    // 語幹 (bare stem) is an incomplete inflection awaiting a
                    // derivational continuation (良さ before そう) — lemma-swapping
                    // it would cut a span boundary through the derived word
                    // (方が良さ[そうだ] → 方が良い). Complete forms (命令形 こい,
                    // 連用形 深く) stay eligible.
                    if (last.inflectionForm?.startsWith("語幹") == true) continue
                    val lemmaPhrase =
                        surfaces.subList(i, i + n - 1).joinToString("") + last.dictionaryForm
                    if (lemmaPhrase == phrase || !isLookupWorthy(lemmaPhrase)) continue
                    var j = i + n
                    while (j < tokens.size && tokens[j].category.isConjugationGlue) j++
                    out.add(PhraseCandidate(
                        startIndex = i, windowLen = n, lookupForm = lemmaPhrase,
                        surface = surfaces.subList(i, j).joinToString(""),
                        tokensConsumed = j - i, isVariant = true,
                    ))
                }
            }
            return out
        }

        /**
         * One re-glob output span, token-native: [tokenStart]/[tokenCount]
         * index the RAW analyzer stream, so consumers keep offset provenance
         * (JaToken.begin/end) instead of re-finding surfaces by string.
         * [reading] keeps [TokenWithReading]'s semantics — the LOOKUP HINT:
         * stem reading for single-token spans, member concat for exact
         * phrases, null for lemma variants — glue readings deliberately
         * excluded; the annotator derives full-span concats from the raw
         * tokens itself.
         *
         * Spans may OVERLAP: single-token glue folding can consume the
         * opening particles of a phrase that then matches at its own start
         * (言われる folds かも, then かもしれない matches at か). The overlap
         * is load-bearing for the words list; SentenceAnnotator computes a
         * disjoint display cover from these spans, phrase-priority.
         */
        internal data class ReglobSpan(
            val tokenStart: Int,
            val tokenCount: Int,
            val surface: String,
            val lookupForm: String,
            val reading: String?,
            val inflections: List<InflectionTag>,
            val isPhrase: Boolean,
        )

        /** Legacy projection of [reglobSpans] — string output, no offsets.
         *  Kept for the words-pipeline call sites and their tests; behavior
         *  is byte-identical to the pre-span implementation. */
        internal fun reglobTokens(
            tokens: List<JaToken>,
            candidates: List<PhraseCandidate>,
            knownPhrases: Set<String>,
            knownForms: Set<String>,
        ): List<TokenWithReading> =
            reglobSpans(tokens, candidates, knownPhrases, knownForms)
                .map { TokenWithReading(it.surface, it.lookupForm, it.reading, it.inflections) }

        /**
         * Greedy left-to-right re-glob matcher plus single-token fallback.
         * Pure: all dictionary knowledge arrives pre-resolved in [knownPhrases]
         * (phrase candidates that passed their membership gate) and
         * [knownForms] (single content-token dictionaryForm/normalizedForm).
         *
         * At each position the longest matching window wins (exact surface
         * join before lemma variant at equal length); otherwise the token's
         * base form is emitted if it's a content word, with trailing
         * auxiliary/particle morphemes folded into a conjugating word's
         * surface span (e.g. ない after 使わ).
         */
        internal fun reglobSpans(
            tokens: List<JaToken>,
            candidates: List<PhraseCandidate>,
            knownPhrases: Set<String>,
            knownForms: Set<String>,
        ): List<ReglobSpan> {
            val byStart = candidates.groupBy { it.startIndex }.mapValues { (_, group) ->
                group.sortedWith(compareByDescending<PhraseCandidate> { it.windowLen }.thenBy { it.isVariant })
            }
            val result = mutableListOf<ReglobSpan>()
            var i = 0
            while (i < tokens.size) {
                val match = byStart[i]?.firstOrNull { it.lookupForm in knownPhrases }
                if (match != null) {
                    // Lemma-variant phrases (気になった → 気になる) carry a productive
                    // inflection on their final verb/adjective — tag it from that
                    // stem (window end) plus the glue folded after it. EXACT matches
                    // are frozen idioms (かもしれない); their ない/etc. aren't productive,
                    // so they stay untagged.
                    val inflections = if (match.isVariant) {
                        JapaneseInflectionAnalyzer.analyze(
                            tokens[i + match.windowLen - 1],
                            tokens.subList(i + match.windowLen, i + match.tokensConsumed),
                        )
                    } else {
                        emptyList()
                    }
                    // Phrase reading: hiragana concat of the window tokens'
                    // readings — the tokenizer's evidence for WHICH homograph
                    // entry this span is. lookup() narrows by it (彼+等 →
                    // かれら selects the かれら entry instead of rank-first
                    // あれら) and falls back to rank order when nothing
                    // matches — which is exactly the sandhi case (一+泊 →
                    // いちはく misses every entry; the いっぱく entry wins on
                    // rank as before). Lemma variants stay null: the final
                    // token's reading is its inflected surface's (なっ),
                    // which can never match the dictionary form's entry
                    // reading, so the hint would always miss.
                    val phraseReading = if (match.isVariant) null else {
                        val parts = tokens.subList(i, i + match.windowLen).map { it.reading }
                        if (parts.any { it.isNullOrEmpty() }) null
                        else Deinflector.katakanaToHiragana(parts.joinToString(""))
                    }
                    result.add(ReglobSpan(
                        tokenStart = i, tokenCount = match.tokensConsumed,
                        surface = match.surface, lookupForm = match.lookupForm,
                        reading = phraseReading, inflections = inflections,
                        isPhrase = true,
                    ))
                    i += match.tokensConsumed
                    continue
                }
                val t = tokens[i]
                if (t.category.isContent) {
                    // Layer 1: prefer dictionaryForm (lemma); fall back to
                    // normalizedForm when only the normalized variant resolves.
                    val lookupForm = when {
                        t.dictionaryForm in knownForms -> t.dictionaryForm
                        t.normalizedForm in knownForms -> t.normalizedForm
                        else -> t.dictionaryForm
                    }
                    if (isLookupWorthy(lookupForm)) {
                        var surfaceSpan = t.surface
                        val glue = mutableListOf<JaToken>()
                        if (t.category.startsConjugation) {
                            var j = i + 1
                            while (j < tokens.size && tokens[j].category.isConjugationGlue) {
                                surfaceSpan += tokens[j].surface
                                glue.add(tokens[j])
                                j++
                            }
                        }
                        val reading = t.reading?.let { Deinflector.katakanaToHiragana(it) }
                        result.add(ReglobSpan(
                            tokenStart = i, tokenCount = 1 + glue.size,
                            surface = surfaceSpan, lookupForm = lookupForm,
                            reading = reading,
                            inflections = JapaneseInflectionAnalyzer.analyze(t, glue),
                            isPhrase = false,
                        ))
                    }
                }
                // NOTE: i advances by ONE even after glue folding — the folded
                // tokens are revisited (they're non-content, so they emit
                // nothing themselves) BUT a phrase candidate starting inside
                // the folded glue still gets its chance to match (言われるかも
                // then かもしれない). That overlap is intentional; see
                // [ReglobSpan].
                i++
            }
            return result
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

        /** Reading-tier phrase membership: every primary reading
         *  (rank_score >= 0) with a per-text kana-native bit — 1 when some
         *  qualifying row is uk-applicable or its entry has no kanji headword.
         *  The caller appends the IN list and `GROUP BY r.text`. Verbatim-
         *  copied into `PhraseMembershipSqlTest`. */
        private const val RANKED_PHRASE_READING_SQL = """
            SELECT r.text,
                   MAX(CASE WHEN r.uk_applicable = 1
                             OR NOT EXISTS (SELECT 1 FROM headword h WHERE h.entry_id = r.entry_id)
                        THEN 1 ELSE 0 END)
            FROM reading r
            WHERE r.rank_score >= 0 AND r.text IN
        """

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
         * Ranked prefix-scan entry IDs for [query], the core of [searchPrefix].
         * Stateless on [db] (like [resolveKanjiMeanings]) so the ranking is
         * unit-testable against a fixture DB without the singleton/filesystem.
         *
         * UNION of a kanji `headword` arm and one kana `reading` arm per query
         * fold (hiragana + katakana), each adding [PREFIX_EXACT_BONUS] on an
         * exact match; the reading arm keeps the position-0 `uk_applicable`
         * bonus from [RANKED_QUERY_KANA]. `MAX(score)` dedupes per entry,
         * ordered desc, capped at [limit].
         *
         * [ranked] selects the v2+ per-row `rank_score` columns; false uses the
         * legacy `entry.freq_score` JOIN for v1 packs (mirrors LEGACY_QUERY_*).
         * Returns empty when [query] has no clean prefix upper bound (it ends
         * at the maximum code point — pathological, never real input).
         */
        internal fun prefixEntryIds(
            db: SQLiteDatabase,
            query: String,
            limit: Int,
            ranked: Boolean,
        ): List<Long> {
            val headUpper = prefixUpperBound(query) ?: return emptyList()
            val sql = StringBuilder()
            val args = mutableListOf<String>()
            if (ranked) {
                sql.append(
                    "SELECT entry_id, rank_score + (CASE WHEN text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END) AS score " +
                        "FROM headword WHERE text >= ? AND text < ?"
                )
                args.add(query); args.add(query); args.add(headUpper)
                for (variant in readingFolds(query)) {
                    val upper = prefixUpperBound(variant) ?: continue
                    sql.append(
                        " UNION ALL SELECT entry_id, rank_score " +
                            "+ (CASE WHEN position = 0 AND uk_applicable = 1 THEN 1500000 ELSE 0 END) " +
                            "+ (CASE WHEN text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END) AS score " +
                            "FROM reading WHERE text >= ? AND text < ?"
                    )
                    args.add(variant); args.add(variant); args.add(upper)
                }
            } else {
                sql.append(
                    "SELECT h.entry_id AS entry_id, e.freq_score + (CASE WHEN h.text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END) AS score " +
                        "FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.text >= ? AND h.text < ?"
                )
                args.add(query); args.add(query); args.add(headUpper)
                for (variant in readingFolds(query)) {
                    val upper = prefixUpperBound(variant) ?: continue
                    sql.append(
                        " UNION ALL SELECT r.entry_id AS entry_id, e.freq_score + (CASE WHEN r.text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END) AS score " +
                            "FROM reading r JOIN entry e ON e.id = r.entry_id WHERE r.text >= ? AND r.text < ?"
                    )
                    args.add(variant); args.add(variant); args.add(upper)
                }
            }
            val wrapped = "SELECT entry_id, MAX(score) AS s FROM ($sql) GROUP BY entry_id ORDER BY s DESC LIMIT ?"
            args.add(limit.toString())
            val ids = mutableListOf<Long>()
            db.rawQuery(wrapped, args.toTypedArray())
                .use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
            return ids
        }

        /** Both kana renderings of [query] to scan the reading table with —
         *  native readings are stored hiragana, loanwords katakana. */
        private fun readingFolds(query: String): Set<String> =
            setOf(Deinflector.katakanaToHiragana(query), Deinflector.hiraganaToKatakana(query))

        /** Primary (lowest-position) kanji headword and kana reading for [id].
         *  Written is null for pure-kana entries. Stateless on [db] for the
         *  same testability reason as [prefixEntryIds]. */
        internal fun primaryFormsForEntry(db: SQLiteDatabase, id: Long): Pair<String?, String?> {
            val idStr = id.toString()
            val written = db.rawQuery(
                "SELECT text FROM headword WHERE entry_id=? ORDER BY position LIMIT 1", arrayOf(idStr)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val reading = db.rawQuery(
                "SELECT text FROM reading WHERE entry_id=? ORDER BY position LIMIT 1", arrayOf(idStr)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            return written to reading
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

/** One kanji headword form for [buildHeadwords]: surface text, whether the
 *  source dictionary marks it priority/common (JMdict `ke_pri`), and its
 *  `ke_inf` verdicts — [searchOnly] (sK) and [rareForm] (rK/iK/oK/io/ik);
 *  both false on packs predating the column. */
internal data class JmKanjiForm(
    val text: String,
    val hasPriority: Boolean,
    val searchOnly: Boolean = false,
    val rareForm: Boolean = false,
)

/** One reading form for [buildHeadwords]: the kana, whether JMdict tags it
 *  `re_nokanji` (never written with the entry's kanji), its `rank_score`
 *  (common-use rank; 0 on packs predating the column), and whether a uk
 *  sense covers it (`uk_applicable`; true on packs predating the column). */
internal data class JmReadingForm(
    val text: String,
    val noKanji: Boolean,
    val rankScore: Int = 0,
    val ukApplicable: Boolean = true,
)

/** `headword.ke_inf` is the sorted, comma-joined set of JMdict entity names
 *  build_jmdict.py stored for the form ("ateji,rK", "sK"); empty = no tag. */
internal fun parseKeInf(raw: String?): Set<String> =
    raw.orEmpty().split(',').filterTo(mutableSetOf()) { it.isNotEmpty() }

/** JMdict search-only kanji form: lookup key only, never a headword. */
internal const val SEARCH_ONLY_TAG = "sK"

/** JMdict kanji-form tags that mark a rare, irregular or outdated spelling:
 *  rarely-used (rK), irregular kanji (iK), outdated kanji (oK), irregular
 *  okurigana (io), irregular kana usage (ik). Displayed when seen, ranked
 *  last otherwise. `ateji` is neither: it is how the word is written. */
internal val RARE_FORM_TAGS = setOf("rK", "iK", "oK", "io", "ik")

/**
 * Pair an entry's kanji forms with its readings into [Headword]s.
 *
 * JMdict carries no general kanji↔reading mapping (only `re_nokanji`, and the
 * `re_restr` restrictions we don't store). The historical heuristic pairs them
 * positionally (kanji[i] ↔ reading[i]), which silently drops every reading past
 * the first when a single kanji form has several — 明日 keeps only あした, losing
 * あす / みょうにち.
 *
 * When there is exactly ONE kanji form that loss is fixable without `re_restr`:
 * every kanji-compatible reading unambiguously belongs to that kanji, so we emit
 * one headword per such reading. `re_nokanji` readings (never written with the
 * kanji) are kept as kana-only headwords (`written = null`) rather than dropped,
 * so a lookup BY that kana still resolves to its own reading. Kanji-paired
 * readings lead in source order, so the primary stays first and every
 * `headwords.firstOrNull()` consumer is unchanged.
 *
 * Multiple kanji forms (where `re_restr` we lack would matter) or a pack without
 * the `no_kanji` column ([hasNoKanjiColumn] = false) keep the positional pairing
 * — byte-for-byte the prior behaviour, so older packs don't regress. Never
 * returns an empty list for an entry that had a kanji form (an all-`re_nokanji`
 * single-kanji entry keeps its readings rather than vanish at the caller's
 * empty-check).
 */
internal fun buildHeadwords(
    kanjiForms: List<JmKanjiForm>,
    readingForms: List<JmReadingForm>,
    hasNoKanjiColumn: Boolean,
): List<Headword> {
    if (kanjiForms.isEmpty()) {
        return readingForms.map {
            Headword(written = null, reading = it.text, rankScore = it.rankScore, ukApplicable = it.ukApplicable)
        }
    }
    if (kanjiForms.size == 1 && hasNoKanjiColumn && readingForms.isNotEmpty()) {
        val k = kanjiForms[0]
        val kanjiReadings = readingForms.filterNot { it.noKanji }
        // Pathological all-re_nokanji entry: pair them with the kanji rather than
        // vanish (keeps the entry; matches the old positional fallback).
        if (kanjiReadings.isEmpty()) {
            return readingForms.map { k.pairedWith(it) }
        }
        // Kanji-compatible readings pair with the kanji (primary stays first);
        // re_nokanji readings stay as kana-only headwords (written = null) so a
        // lookup BY that kana resolves to its own reading via headwordFor's
        // reading branch instead of falling back to the kanji pair.
        return kanjiReadings.map { k.pairedWith(it) } + readingForms.filter { it.noKanji }.map {
            Headword(written = null, reading = it.text, rankScore = it.rankScore, ukApplicable = it.ukApplicable)
        }
    }
    return kanjiForms.mapIndexed { i, k ->
        val r = readingForms.getOrNull(i)
        Headword(
            written = k.text,
            reading = r?.text ?: readingForms.firstOrNull()?.text,
            hasPriority = k.hasPriority,
            rankScore = r?.rankScore ?: 0,
            isSearchOnly = k.searchOnly,
            isRareForm = k.rareForm,
            ukApplicable = r?.ukApplicable ?: true,
        )
    }
}

/** The kanji×reading [Headword] for one pairing, carrying the form's ke_inf
 *  verdicts and the reading's uk scope. */
private fun JmKanjiForm.pairedWith(r: JmReadingForm): Headword = Headword(
    written = text,
    reading = r.text,
    hasPriority = hasPriority,
    rankScore = r.rankScore,
    isSearchOnly = searchOnly,
    isRareForm = rareForm,
    ukApplicable = r.ukApplicable,
)
