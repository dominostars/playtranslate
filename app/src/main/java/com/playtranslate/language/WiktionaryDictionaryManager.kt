package com.playtranslate.language

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.language.inflection.InflectionParadigms
import com.playtranslate.dictionary.PREFIX_EXACT_BONUS
import com.playtranslate.dictionary.prefixUpperBound
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.DictionaryResponse
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.PosVocabulary
import com.playtranslate.model.Sense
import com.playtranslate.model.expressionFrom
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
 *     rows come in four flavors, distinguished by `position`:
 *     `0` = lemma surface, `1` = Snowball stem, `2` = redirect/morphology
 *     alias (mapped to the lemma's entry_id), `3` = folded variant (a
 *     casual/variant-spelling key, Arabic — see [ArabicFold]). The canonical
 *     surface/stem queries match positions 0–2; position 3 is reached ONLY by
 *     [lookup]'s folded fallback. The caller uses the matched position to pick
 *     an inflection marker ([markerForPosition]).
 *  2. Folded-variant fallback supplied by [LatinEngine] (Arabic [ArabicFold]).
 *  3. Stem-form fallback supplied by [LatinEngine] (Snowball Porter).
 *  4. Silent pass-through on miss (per decision 7 in the architecture doc).
 *
 * No de-inflection table. No `kanjidic` table — the Wiktionary pack's
 * `kanjidic` is empty by design, and this manager does not probe or query it.
 * [phrasesExist] is the batched membership gate for tap-time multi-word
 * expression matching ([LatinEngine.longestPhraseAt]).
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

    /** Whether this language has an
     *  [com.playtranslate.language.inflection.InflectionParadigm] to render the
     *  pack's inflection tables ([inflectionTable]). Gates the per-lookup blob
     *  read in [buildEntry] and the `[inflected]` marker's suppression: a pack
     *  can carry the tables for a language the app cannot label yet (the fleet
     *  rebuild gives every Wiktionary pack one), and such a language must keep
     *  today's marker and pay no extra query. */
    private val paradigm = InflectionParadigms[langId]
    private val readsInflections = paradigm != null

    /** `inflection_tagset` as id → tags, loaded beside [db] in [ensureOpen] and
     *  dropped with it in [close]: ids are interned per build, so a map that
     *  outlived a pack swap (a dev sideload, an in-process es-v4 → es-v5
     *  upgrade) would label the new pack's blobs with the old pack's tags.
     *  Empty when the pack predates the tables or the language reads none,
     *  and then the per-entry blob read is skipped ([inflectionFormsOf]): the
     *  one query at open stands in for a "no such table" failure per entry
     *  per lookup on every pack still at a pre-table version. */
    private var tagsets: Map<Int, Set<String>> = emptyMap()

    suspend fun preload() = ensureOpen()

    /**
     * Cascade: [surface] first, then [folded] (Arabic casual/variant-spelling
     * key, if supplied), then [stemmed] (if different) as a last fallback.
     * Returns null on miss — caller is expected to silent-pass-through.
     *
     * Match-type marker semantics (the `[…]` prefix on the first sense's POS
     * list, applied by [buildEntry] via [markerForPosition]):
     *  - Surface hit on a `headword.position=0` row  → no marker (direct lemma hit).
     *  - Surface hit on a `position=1` row           → `[stem]`
     *    (a surface OCR captured happened to equal a lemma's Snowball stem —
     *    rare but possible, e.g. English `run` is both surface and stem).
     *  - Surface hit on a `position=2` row           → `[inflected]`
     *    (Wiktionary `form_of`/`alt_of` or morphology alias — the user's surface
     *    is an inflected/alternate form of a lemma we're routing them to).
     *  - Folded-fallback branch (surface missed, we retried with the [ArabicFold]
     *    key against `position=3` rows) → always `[variant]` via [buildResponse]'s
     *    `forceNote`. Only this branch reaches position 3.
     *  - Stem-fallback branch (surface missed, we retried with the stemmer's
     *    output) → always `[stem]` via [buildResponse]'s `forceNote` param.
     *    We force the marker even if the fallback query matched a `position=0`
     *    row, because what we want to communicate is "we stemmed your query,"
     *    not "we stored this form."
     */
    suspend fun lookup(
        surface: String,
        stemmed: String?,
        folded: String? = null,
    ): DictionaryResponse? = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext null

        val surfaceLower = surface.lowercase(locale)
        val surfaceIds = queryEntryIds(database, surfaceLower)
        if (surfaceIds.isNotEmpty()) {
            return@withContext buildResponse(database, surfaceIds, surfaceLower = surfaceLower)
        }

        // Folded-variant fallback (Arabic casual/variant spellings — [ArabicFold]).
        // Runs on a surface miss EVEN when the folded key equals the surface,
        // because it queries a DIFFERENT tier: the deliberately-generated
        // position-3 fold rows only (not positions 0-2). Restricting to that
        // tier keeps a folded key that coincidentally equals an unrelated
        // canonical or alias headword from being surfaced and mislabeled
        // [variant].
        if (folded != null) {
            val foldedIds = queryEntryIds(database, folded.lowercase(locale), foldedTier = true)
            if (foldedIds.isNotEmpty()) {
                return@withContext buildResponse(database, foldedIds, forceNote = "variant")
            }
        }

        if (stemmed != null) {
            val stemmedLower = stemmed.lowercase(locale)
            if (stemmedLower != surfaceLower) {
                val stemIds = queryEntryIds(database, stemmedLower)
                if (stemIds.isNotEmpty()) {
                    // The key is the stem, but the readings are of the SURFACE: a
                    // form the alias pass missed (its page fell under the
                    // frequency cut) can still be a line of the lemma's blob.
                    return@withContext buildResponse(
                        database, stemIds, forceNote = "stem", surfaceLower = surfaceLower, exactSurface = true,
                    )
                }
            }
        }

        null
    }

    /**
     * Ranked prefix-completion candidate lemmas for a partial [query] — the
     * dictionary-search path. Returns up to [limit] distinct lemma surfaces
     * whose `position = 0` headword begins with [query], ordered
     * exact-match-first then by `freq_score`. Only position-0 rows participate:
     * position-1 stems and position-2 `form_of` aliases are internal index
     * entries that must not surface as user-visible words. Each result
     * re-resolves via [lookup].
     *
     * [query] is lowered with the pack's [locale] to match the lowercased
     * headwords (so Turkish `İ`/`I` fold the same way the pack built them).
     */
    suspend fun searchPrefix(query: String, limit: Int = 20): List<String> = withContext(Dispatchers.IO) {
        val database = ensureOpen() ?: return@withContext emptyList()
        val q = query.trim().lowercase(locale)
        if (q.isEmpty()) return@withContext emptyList()
        val upper = prefixUpperBound(q) ?: return@withContext emptyList()
        val words = mutableListOf<String>()
        database.rawQuery(
            "SELECT h.text AS t, " +
                "MAX(e.freq_score + (CASE WHEN h.text = ? THEN $PREFIX_EXACT_BONUS ELSE 0 END)) AS s " +
                "FROM headword h JOIN entry e ON e.id = h.entry_id " +
                "WHERE h.position = 0 AND h.text >= ? AND h.text < ? " +
                "GROUP BY h.text ORDER BY s DESC LIMIT ?",
            arrayOf(q, q, upper, limit.toString()),
        ).use { c -> while (c.moveToNext()) words.add(c.getString(0)) }
        words
    }

    /**
     * Batch existence gate for multi-word expression matching
     * ([LatinEngine.longestPhraseAt] and [LatinEngine.phrasesIn]): the
     * subset of [candidates] with a
     * lemma (position 0) or `form_of` alias (position 2) headword row — so
     * inflected phrase surfaces ("gave up") gate through their alias rows
     * exactly like the follow-up [lookup] will resolve them. Position-1 STEM
     * rows are deliberately excluded, unlike [lookup]'s surface tier: the
     * build Snowball-stems the whole joined phrase, producing garbage keys
     * that collide with real word sequences ("that is" stems to "that i",
     * which would phrase-match every "that I" in ordinary text — the en pack
     * carries ~40k such stem-only multi-word keys). Nothing legitimate is
     * lost: lemmas gate via 0, inflected surfaces via 2. Each candidate is
     * lowercased with the pack's locale for the query; the returned set
     * contains the candidates AS PASSED. Keys whose entries are pure `&lit`
     * cross-reference stubs are also excluded — see [phrasesExistQuery].
     * Empty when the pack isn't openable, degrading callers to single-word
     * behavior.
     */
    suspend fun phrasesExist(candidates: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptySet()
        val database = ensureOpen() ?: return@withContext emptySet()
        val byLower = candidates.groupBy { it.lowercase(locale) }
        phrasesExistQuery(database, byLower.keys)
            .flatMapTo(mutableSetOf()) { byLower[it].orEmpty() }
    }

    /**
     * The whole inflection table of the pack entry [entryId] (its `inflection`
     * blob decoded through `inflection_tagset`), for the detail page's
     * conjugation card: every form with its kaikki tags, in table order. Null
     * when the pack predates the tables, when the entry has none, or when this
     * language has no paradigm to render them.
     */
    suspend fun inflectionTable(entryId: Long): List<InflectionForm>? = withContext(Dispatchers.IO) {
        if (!readsInflections) return@withContext null
        val database = ensureOpen() ?: return@withContext null
        inflectionFormsOf(database, entryId.toString())
    }

    fun close() {
        db?.close()
        db = null
        tagsets = emptyMap()
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
        tagsets = if (readsInflections) db?.let { loadTagsets(it) }.orEmpty() else emptyMap()
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
     *
     * Sort order is match position ASC → freq_score DESC → entry_id ASC.
     *  - `pos ASC` FIRST keeps direct-lemma matches (position 0) ahead of
     *    stem matches (1) and `form_of` aliases (2). Otherwise a query
     *    string that's both a standalone lemma AND an inflected alias of
     *    another entry surfaces the alias's parent as primary, mislabeling
     *    the popup header with a different word. Position must dominate
     *    frequency: with freq_score sorted first, any alias parent more
     *    frequent than the word's own entry won — "hinder" surfaced "hind",
     *    "running" surfaced "run", German "es" surfaced "das" (4k affected
     *    surfaces in the en pack alone). The alias parent still appears in
     *    the response, after the word's own entries, with its [inflected]
     *    marker.
     *  - `freq_score DESC` ranks entries within a position tier.
     *  - `entry_id ASC` is the freq_score tiebreaker. Wiktionary sections
     *    are typically Noun → Verb → Adjective → Interjection in
     *    dictionary order; the kaikki extractor emits them in that order
     *    so lower entry_id == more "primary" POS. Without the tiebreaker,
     *    ties (common since each POS-split entry inherits the same
     *    frequency stats) sort arbitrarily — e.g. `surprise` would put
     *    intj before noun.
     */
    private fun queryEntryIds(
        db: SQLiteDatabase,
        word: String,
        foldedTier: Boolean = false,
    ): List<Hit> {
        // Two DISJOINT query modes:
        //  - canonical (surface, stem): positions 0-2.
        //  - folded fallback in [lookup]: position 3 EXACTLY — the
        //    deliberately-generated fold-key tier. Querying ONLY that tier
        //    (rather than all positions) is what keeps lossy fold collisions
        //    isolated: a folded key that happens to coincide with an unrelated
        //    canonical lemma or alias (positions 0-2) must NOT be surfaced and
        //    mislabeled [variant]. The fold fallback resolves via the fold rows
        //    it was built around, nothing else.
        // No-op distinction for non-Arabic packs — they have no position-3 rows,
        // so canonical lookups never call this with foldedTier=true.
        val positionClause = if (foldedTier) " AND h.position = 3" else " AND h.position <= 2"
        val results = mutableListOf<Hit>()
        db.rawQuery(
            "SELECT h.entry_id, MIN(h.position) AS pos, " +
                "MAX(CASE WHEN h.position = 2 THEN 1 ELSE 0 END) AS alias FROM headword h " +
                "JOIN entry e ON e.id = h.entry_id " +
                "WHERE h.text = ?$positionClause " +
                "GROUP BY h.entry_id " +
                "ORDER BY pos ASC, e.freq_score DESC, h.entry_id ASC LIMIT 8",
            arrayOf(word)
        ).use { c ->
            while (c.moveToNext()) results.add(Hit(c.getLong(0), c.getInt(1), c.getInt(2) == 1))
        }
        return results
    }

    /** One entry a key matched: its lowest matching headword position (the
     *  ranking tier and the marker) and whether an alias row (position 2)
     *  matched too, independently of that minimum. poner's Snowball stem row
     *  is `pon`, which is also its imperative alias: the minimum says stem,
     *  the alias flag says the key is a form with readings. */
    private data class Hit(val entryId: Long, val minPosition: Int, val hasAlias: Boolean)

    /**
     * Materializes [idsWithPos] into a full [DictionaryResponse], deriving
     * each entry's inflection marker from its match position.
     *
     * [forceNote] overrides the position-based derivation — used by
     * [lookup]'s stem-fallback branch, which always wants `[stem]`
     * regardless of which position the stem query happened to match.
     *
     * [surfaceLower] is the lowercased key the SURFACE branch matched; an
     * entry it matched on the alias tier gets its readings of that key
     * ([DictionaryEntry.formReadings]). The stem branch passes nothing: its
     * key is the Snowball stem, which in es-v4 equals some alias text 949
     * times (ve, the imperative of ver, is also the stem of ves), so filtering
     * the blob by it would attach a real form's label to a surface that is
     * not that form.
     */
    private fun buildResponse(
        db: SQLiteDatabase,
        hits: List<Hit>,
        forceNote: String? = null,
        surfaceLower: String? = null,
        exactSurface: Boolean = false,
    ): DictionaryResponse {
        val entries = hits.mapNotNull { hit ->
            // Readings whenever the entry has an alias row for the key, not
            // only when that row is its lowest: `pon` is poner's stem row AND
            // its imperative alias, and the label must win over `[stem]`. On
            // the stem branch ([exactSurface]) the blob is filtered by the
            // surface itself, which is safe where filtering by the stem was
            // not: a blob line equal to the surface IS that form.
            buildEntry(
                db, hit.entryId, forceNote ?: markerForPosition(hit.minPosition),
                surfaceLower.takeIf { exactSurface || hit.hasAlias },
            )
        }
        return DictionaryResponse(entries = entries)
    }

    /**
     * Single source of truth mapping a matched `headword.position` to the
     * inflection marker shown on the first sense's POS list:
     *  - `0` direct lemma → no marker
     *  - `1` Snowball stem → `[stem]`
     *  - `2` `form_of` / morphology alias → `[inflected]`
     *  - `3` folded variant (casual/variant spelling, Arabic) → `[variant]`
     *
     * [lookup]'s fallback branches additionally force `[stem]`/`[variant]` (they
     * communicate "we stemmed/folded your query," even when that query happened
     * to match a lower-position row).
     */
    private fun markerForPosition(position: Int): String? = when (position) {
        0 -> null
        1 -> "stem"
        3 -> "variant"
        else -> "inflected"  // 2 = alias; any future tier defaults here
    }

    private fun buildEntry(
        db: SQLiteDatabase,
        id: Long,
        inflectionNote: String?,
        surfaceForReadings: String? = null,
    ): DictionaryEntry? {
        val idStr = id.toString()

        // The pack's readings of the looked-up surface as a form of this entry
        // (hablamos → hablar's present and preterite lines), for the conjugation
        // line; read only where a paradigm renders them, and kept only when it
        // can put words to them. A reading the paradigm has no label for (a
        // mis solas, tagged first-person singular and nothing else; 31 of the
        // es oracle's 297,210 pages) would otherwise withhold the `[inflected]`
        // marker below and draw a line of just the surface.
        val formReadings = if (paradigm != null && surfaceForReadings != null) {
            inflectionFormsOf(db, idStr)
                ?.filter { it.surface == surfaceForReadings && !it.fromPronominalVariant }
                .orEmpty()
                .takeIf { paradigm.label(it) != null }
                .orEmpty()
        } else {
            emptyList()
        }

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
                val posList   = PosVocabulary.parse(c.getString(1))
                val glossList = c.getString(2).split('\t').filter { it.isNotBlank() }
                val miscList  = c.getString(3).split('\t').filter { it.isNotBlank() }
                // The marker is withheld when the entry carries labelled readings
                // of the surface: the conjugation line then says what the
                // bracket token only hinted at.
                val finalPos  = if (inflectionNote != null && formReadings.isEmpty() && senses.isEmpty())
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
            packId = id,
            isCommon = isCommon,
            tags = emptyList(),
            jlpt = emptyList(),
            headwords = headwords,
            senses = senses,
            isExpression = expressionFrom(senses),
            // DB stores 0-100 (fine-grained, used for ORDER BY). Normalize to
            // 0-5 for display consistency with JMdict's 0-5 star scale.
            freqScore = (freqScore * 5 / 100).coerceIn(0, 5),
            formReadings = formReadings,
        )
    }

    /** `inflection_tagset` as id → tags; empty for a pack built before the tables. */
    private fun loadTagsets(database: SQLiteDatabase): Map<Int, Set<String>> = try {
        val out = HashMap<Int, Set<String>>()
        database.rawQuery("SELECT id, tags FROM inflection_tagset", null).use { c ->
            while (c.moveToNext()) {
                out[c.getInt(0)] = c.getString(1).split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() }
            }
        }
        out
    } catch (_: android.database.sqlite.SQLiteException) {
        emptyMap() // pack built before the inflection tables
    }

    /** The entry's `inflection` blob decoded: one [InflectionForm] per line
     *  (`<tagset id>\t<surface>`, table order). Null for a pack without the
     *  table or an entry without a blob; a line naming an unknown tagset id
     *  is dropped. A pack without tagsets has no table to read (the builder
     *  writes both or neither, and verify_pack holds it to that), so the
     *  query is not run: on a pre-table pack it would throw, and be caught,
     *  once per entry per lookup. */
    private fun inflectionFormsOf(db: SQLiteDatabase, idStr: String): List<InflectionForm>? {
        if (tagsets.isEmpty()) return null
        val blob = try {
            db.rawQuery("SELECT forms FROM inflection WHERE entry_id=?", arrayOf(idStr)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null // pack built before the inflection tables
        } ?: return null
        return blob.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val tags = line.substring(0, tab).toIntOrNull()?.let(tagsets::get) ?: return@mapNotNull null
            InflectionForm(line.substring(tab + 1), tags)
        }.toList()
    }

    companion object {
        private const val TAG = "WiktionaryDictMgr"

        /** Wiktionary's `&lit` cross-reference template rendering — the
         *  "definition" of a phrase Wiktionary itself marks non-idiomatic
         *  ("Used other than figuratively or idiomatically: see do, you.").
         *  An entry whose every sense starts with this defines nothing. */
        private const val LIT_STUB_PREFIX = "Used other than figuratively or idiomatically"

        /** SQL core of [phrasesExist], separated so tests drive it against a
         *  fixture DB. Returns the subset of (already locale-lowercased)
         *  [keys] with a position-0 lemma or position-2 alias row —
         *  position-1 phrase stems are garbage match keys (see
         *  [phrasesExist]) and position-3 fold rows stay fallback-only —
         *  whose entry carries at least one REAL sense: keys reaching only
         *  `&lit` cross-reference stubs ([LIT_STUB_PREFIX] — the en pack has
         *  179 such all-stub phrases, "do you", "want to", …) are dropped at
         *  candidacy, so the longest-match tie-breaks fall through to the
         *  next candidate instead of surfacing an entry that defines
         *  nothing. Entries with a stub ALONGSIDE real senses ("open the
         *  door") still gate. The prefix test is per sense ROW (a row's
         *  tab-joined glosses mixing a stub prefix with a real gloss would
         *  read as stub — doesn't occur: `&lit` is always its own sense).
         *  Glosses are English in every pack (all built from en-Wiktionary),
         *  so one prefix covers all languages. FUTURE PACK REBUILD: drop
         *  all-stub multi-word entries at build time
         *  (scripts/wiktionary_filters.py) so typed lookup stops surfacing
         *  them too; this gate then filters nothing and stays as a safety.
         *  Chunked so an oversized caller can't blow SQLite's bind-argument
         *  limit (phrase windows pass a handful). */
        internal fun phrasesExistQuery(db: SQLiteDatabase, keys: Collection<String>): Set<String> {
            val found = mutableSetOf<String>()
            for (chunk in keys.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.rawQuery(
                    "SELECT DISTINCT h.text FROM headword h" +
                        " WHERE h.position IN (0, 2) AND h.text IN ($placeholders)" +
                        " AND EXISTS (SELECT 1 FROM sense s WHERE s.entry_id = h.entry_id" +
                        " AND s.glosses NOT LIKE ?)",
                    (chunk + "$LIT_STUB_PREFIX%").toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) found.add(c.getString(0))
                }
            }
            return found
        }

        @SuppressLint("StaticFieldLeak")
        private val instances = java.util.concurrent.ConcurrentHashMap<SourceLangId, WiktionaryDictionaryManager>()

        fun get(context: Context, langId: SourceLangId = SourceLangId.EN): WiktionaryDictionaryManager =
            instances.getOrPut(langId) {
                WiktionaryDictionaryManager(context.applicationContext, langId)
            }
    }
}
