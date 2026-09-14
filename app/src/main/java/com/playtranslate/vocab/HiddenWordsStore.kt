package com.playtranslate.vocab

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.playtranslate.language.SourceLangId
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Words the user has hidden, per source language: a hidden word renders as a
 * minimized stub in the results words list and in a sentence card's Words
 * section, and never lands on a sentence card (filtered once, at
 * [com.playtranslate.ui.sendSentenceCard]). Keyed by the row's lemma
 * (`RowState.displayWord` / `WordEntry.word`), the same key the deck badges
 * and the sheet's target set use.
 *
 * PRIMARY, user-curated data expected to reach thousands of rows (a
 * management list and an "hide every target word in my decks" import are
 * planned), so this is the SQLite house pattern
 * ([com.playtranslate.translationlog.TranslationHistoryStore]) rather than a
 * SharedPreferences string set: per-row `reading`, `hidden_at` and `source`
 * are carried from day one so those features need no schema change, and a
 * version bump must MIGRATE (ALTER), never drop.
 *
 * Two deliberate departures from the History store:
 *  - The file lives under [Context.filesDir] ([BACKUP_PATH]), not
 *    noBackupFilesDir: the backup rules include exactly this path (the only
 *    app data that rides Auto Backup / device transfer). Renaming it means
 *    editing the rules; `BackupRulesTest` pins the pair.
 *  - No cached connection. Every disk operation opens, runs, and closes, so
 *    the file on disk is self-contained (rollback journal, no open handle)
 *    whenever Auto Backup copies it while the app is running. Reads never
 *    touch the disk after the per-language load: render paths call
 *    [snapshot] synchronously against the in-memory sets.
 *
 * Threading: every disk operation and every cache mutation runs on one
 * single-thread dispatcher — a load enqueued by [snapshot] is ordered before
 * any later [setHidden] on the same thread, so a write can never be
 * overwritten by a stale load. [cache] is an immutable map of immutable sets
 * replaced wholesale, so [snapshot] on any thread sees a consistent set.
 */
object HiddenWordsStore {

    private const val TAG = "HiddenWords"
    private const val SCHEMA_VERSION = 1

    /** Store file path relative to [Context.filesDir]; the backup rules
     *  (`res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`)
     *  include this exact path. */
    const val BACKUP_PATH = "vocab/hidden_words.sqlite"

    /** `source` values — stored as TEXT, stable once shipped. A future deck
     *  import adds its own value; nothing here changes. */
    const val SOURCE_MANUAL = "manual"

    data class Entry(
        val lang: String,
        val word: String,
        val reading: String?,
        val hiddenAtMs: Long,
        val source: String,
    )

    /** Bumped on every mutation and on every successful per-language load,
     *  found rows or not. The results list and the sentence card collect it
     *  to re-render their rows (a toggle in the sheet while the list is on
     *  the other screen, a load that completes after rows were first drawn —
     *  that second case is also when a host applies its hidden-last ORDER,
     *  so the bump must come even for an empty set, or a host that drew
     *  before the load would treat the user's first toggle as that first
     *  ordering and move the row). */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> get() = _revision

    private val dispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "HiddenWordsStore").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Language code → hidden lemmas. Written only on [dispatcher]; the
     *  whole map is replaced on every change (immutable values), so a reader
     *  on any thread sees one consistent set with no lock held across a
     *  render. Absent key = not loaded yet. */
    @Volatile
    private var cache: Map<String, Set<String>> = emptyMap()

    /** Languages whose load has been REQUESTED (off-dispatcher dedupe for
     *  [warm]); the load itself is idempotent against [cache]. */
    private val loadsRequested = HashSet<String>()
    private val lock = Any()

    fun fileFor(ctx: Context): File = File(ctx.applicationContext.filesDir, BACKUP_PATH)

    /**
     * The hidden lemmas for [lang], synchronously, for render paths (row
     * binds) that cannot suspend. Before the language's first load completes
     * this is empty and the load is triggered; its completion bumps
     * [revision], which is how a host that already drew rows learns to
     * re-draw them. The send funnel uses [loaded] instead, which waits.
     */
    fun snapshot(ctx: Context, lang: SourceLangId): Set<String> {
        cache[lang.code]?.let { return it }
        warm(ctx, lang)
        return emptySet()
    }

    /** Whether [lang]'s set has loaded, i.e. whether [snapshot] is the disk
     *  truth. Hosts read this BEFORE [snapshot] when they order a fresh list:
     *  true means the snapshot they then take is the loaded one; false means
     *  the ordering is provisional and the load's revision bump redoes it.
     *  Stays false after a FAILED load (see [ensureLoaded]). */
    fun isLoaded(lang: SourceLangId): Boolean = cache.containsKey(lang.code)

    /** Start loading [lang] in the background (idempotent). Called from the
     *  Application for the current source language so the common case has no
     *  stub pop-in on cold start; [snapshot] calls it on demand for the rest. */
    fun warm(ctx: Context, lang: SourceLangId) {
        val app = ctx.applicationContext
        val first = synchronized(lock) { loadsRequested.add(lang.code) }
        if (first) scope.launch { ensureLoaded(app, lang.code) }
    }

    /** The hidden lemmas for [lang] after its load has completed; empty when
     *  the store is unavailable (the read failed, and this call retried it). */
    suspend fun loaded(ctx: Context, lang: SourceLangId): Set<String> =
        withContext(dispatcher) { ensureLoaded(ctx.applicationContext, lang.code) ?: emptySet() }

    /**
     * Hide or show [word] for [lang]. Returns true when the requested state
     * holds on disk: a no-op (no revision bump) when the word is already in
     * that state, else after the write has landed. Returns false, with the
     * cache and revision UNTOUCHED, when the language's set cannot be read
     * (a write against a set that is not the disk truth could skip a needed
     * row) or the write fails: an exception (disk full, a file that cannot be
     * opened, corruption), or an insert that wrote no row, the platform's
     * documented -1 signal. All logged. The rows then keep showing the state
     * that is actually persisted, and the caller tells the user. Updating the
     * cache first would show a toggle as done for the session and silently
     * revert it on the next launch (adversarial-review finding). A delete
     * that finds no row is success: the goal state already holds on disk.
     */
    suspend fun setHidden(
        ctx: Context,
        lang: SourceLangId,
        word: String,
        reading: String?,
        hidden: Boolean,
        source: String = SOURCE_MANUAL,
    ): Boolean = withContext(dispatcher) {
        val app = ctx.applicationContext
        val current = ensureLoaded(app, lang.code) ?: return@withContext false
        if ((word in current) == hidden) return@withContext true
        try {
            val wrote = withDb(app) { db ->
                if (hidden) {
                    val rowId = db.insertWithOnConflict(
                        "hidden_words", null,
                        ContentValues().apply {
                            put("lang", lang.code)
                            put("word", word)
                            put("reading", reading?.takeIf { it.isNotBlank() })
                            put("hidden_at", System.currentTimeMillis())
                            put("source", source)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    rowId != -1L
                } else {
                    db.delete("hidden_words", "lang = ? AND word = ?", arrayOf(lang.code, word))
                    true
                }
            }
            if (!wrote) {
                Log.e(TAG, "persist wrote no row (${lang.code}, hidden=$hidden)")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "persist failed (${lang.code}, hidden=$hidden)", e)
            return@withContext false
        }
        cache = cache + (lang.code to if (hidden) current + word else current - word)
        _revision.value++
        true
    }

    /** A card just LANDED with [word] on it on purpose (a highlighted target
     *  on a sentence card, the subject of a word card): the user is studying
     *  the word, so it is not hidden any more. Called only after a
     *  successful send, never when an editor merely opens — cancelling an
     *  editor must leave the store alone. */
    suspend fun unhideForCard(ctx: Context, lang: SourceLangId, word: String): Boolean =
        setHidden(ctx, lang, word, reading = null, hidden = false)

    /** Every hidden row for [lang], newest first (ties by insertion order). */
    suspend fun entries(ctx: Context, lang: SourceLangId): List<Entry> = withContext(dispatcher) {
        withDb(ctx.applicationContext) { db ->
            val out = ArrayList<Entry>()
            db.rawQuery(
                "SELECT lang, word, reading, hidden_at, source FROM hidden_words " +
                    "WHERE lang = ? ORDER BY hidden_at DESC, rowid DESC",
                arrayOf(lang.code),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Entry(
                            lang = c.getString(0),
                            word = c.getString(1),
                            reading = if (c.isNull(2)) null else c.getString(2),
                            hiddenAtMs = c.getLong(3),
                            source = c.getString(4),
                        )
                    )
                }
            }
            out
        }
    }

    // ── Dispatcher-confined internals ────────────────────────────────

    /**
     * Load [lang] from disk into [cache] unless already there. Null when the
     * read fails (logged): the language then stays UNLOADED — nothing cached,
     * [isLoaded] false, [snapshot] empty, every write refused — until a later
     * [loaded] or [setHidden] retries the read here, or the next process
     * does. [snapshot] does not retry (its [warm] is deduped), so a broken
     * disk is not hit once per row bind. Caching an empty set instead would
     * let [setHidden]'s no-op check trust a set that is not the disk truth:
     * an un-hide of a word hidden on disk would be skipped, and the word
     * would return after restart. Every successful fresh load bumps
     * [revision], empty or not (see [revision] for why an empty one must).
     */
    private fun ensureLoaded(app: Context, lang: String): Set<String>? {
        cache[lang]?.let { return it }
        val set: Set<String> = try {
            withDb(app) { db ->
                val out = HashSet<String>()
                db.rawQuery("SELECT word FROM hidden_words WHERE lang = ?", arrayOf(lang)).use { c ->
                    while (c.moveToNext()) out.add(c.getString(0))
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed ($lang)", e)
            return null
        }
        cache = cache + (lang to set)
        _revision.value++
        return set
    }

    /** Open, ensure the schema, run [block], close — always, so no handle is
     *  held while Auto Backup may copy the file. */
    private fun <T> withDb(app: Context, block: (SQLiteDatabase) -> T): T {
        val file = fileFor(app)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            ensureSchema(db)
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        val version = db.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hidden_words (" +
                "lang TEXT NOT NULL, " +
                "word TEXT NOT NULL, " +
                "reading TEXT, " +
                "hidden_at INTEGER NOT NULL, " +
                "source TEXT NOT NULL, " +
                "PRIMARY KEY (lang, word))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_hidden_words_at ON hidden_words(lang, hidden_at)"
        )
        if (version < SCHEMA_VERSION) {
            // v1 is the first schema; future versions add ALTER-based
            // migrations here (primary data — never drop). A file stamped
            // AHEAD of us (restored from a newer build) is left as it is:
            // the columns we know are read, the stamp is not lowered.
            db.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }

    // ── Test seams ──────────────────────────────────────────────────

    /** Tests only: forget the caches and delete the file so each test
     *  starts from an empty store. Ordered on the dispatcher behind any
     *  in-flight load. */
    @VisibleForTesting
    suspend fun resetForTest(ctx: Context): Unit = withContext(dispatcher) {
        cache = emptyMap()
        synchronized(lock) { loadsRequested.clear() }
        val file = fileFor(ctx)
        file.delete()
        File(file.path + "-journal").delete()
    }

    /** Tests only: forget the caches but keep the file — a process restart
     *  against the same disk state (restore-at-install, an app update). */
    @VisibleForTesting
    suspend fun dropCacheForTest(): Unit = withContext(dispatcher) {
        cache = emptyMap()
        synchronized(lock) { loadsRequested.clear() }
    }
}
