package com.playtranslate.translationlog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The History sink: durable store of gated translation-log entries.
 * User-managed retention by contract — visible in Tools → History,
 * deletable per row, cleared explicitly; survives sessions (unlike the
 * [ContextRing]).
 *
 * Storage follows the house pattern ([com.playtranslate.yomitan.YomitanDataStore.openDb]):
 * `openOrCreateDatabase` under [Context.noBackupFilesDir] (app-managed
 * data stays out of Google Backup — LanguagePackStore convention),
 * `PRAGMA user_version` schema stamp. Unlike Yomitan's derived data this
 * is PRIMARY data, so a version bump must MIGRATE, never drop.
 *
 * Threading: every operation is a suspend fun confined to one
 * single-thread dispatcher — ordered writes, consistent reads, no locks.
 * Callers may invoke from any dispatcher.
 */
object TranslationHistoryStore {

    /** FIFO retention cap — pruned on insert, oldest first. ~5000 text
     *  rows is a few MB; a knob only if real usage demands one. */
    const val MAX_ROWS = 5000

    private const val SCHEMA_VERSION = 1

    data class HistoryEntry(
        val id: Long,
        val atMs: Long,
        val sourceText: String,
        val translation: String?,
        val sourceLang: String,
        val targetLang: String,
        val provenance: String,
        val sessionId: String,
        val normKey: String,
        val backendDisplayName: String?,
    )

    /** Bumped after every mutation — the History screen collects this to
     *  live-update while visible (dual-screen: the page can be on screen
     *  during auto-translate). StateFlow conflates bursts naturally. */
    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> get() = _revision

    /** Provenance values — stored as TEXT, stable once shipped. */
    const val PROVENANCE_AUTO = "auto"
    const val PROVENANCE_ONE_SHOT = "one_shot"
    const val PROVENANCE_LOOKUP = "lookup"
    const val PROVENANCE_CAMERA = "camera"

    /** Session ids minted per capture episode carry this prefix so the
     *  History UI can card-group them; legacy one_shot rows (which share
     *  construction-era session ids across unrelated captures) lack it and
     *  keep rendering as plain rows. */
    const val CAPTURE_SESSION_PREFIX = "cap:"

    private val dispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "TranslationHistoryStore").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var db: SQLiteDatabase? = null

    private fun openDb(ctx: Context): SQLiteDatabase {
        db?.let { return it }
        val file = File(File(ctx.applicationContext.noBackupFilesDir, "translationlog"), "history.sqlite")
        file.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        val version = database.rawQuery("PRAGMA user_version", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "at_ms INTEGER NOT NULL, " +
                "source_text TEXT NOT NULL, " +
                "translation TEXT, " +
                "source_lang TEXT NOT NULL, " +
                "target_lang TEXT NOT NULL, " +
                "provenance TEXT NOT NULL, " +
                "session_id TEXT NOT NULL, " +
                "norm_key TEXT NOT NULL, " +
                "rect_l INTEGER, rect_t INTEGER, rect_r INTEGER, rect_b INTEGER, " +
                "backend TEXT)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_at ON entries(at_ms)")
        // Per-session UI state (live-card collapse), not primary data —
        // created opportunistically (IF NOT EXISTS, no version bump) and
        // pruned against surviving rows on read.
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS collapsed_sessions (session_id TEXT PRIMARY KEY)"
        )
        if (version != SCHEMA_VERSION) {
            // v1 is the first schema; future versions add ALTER-based
            // migrations here (primary data — never drop).
            database.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
        db = database
        return database
    }

    suspend fun insert(
        ctx: Context,
        atMs: Long,
        sourceText: String,
        translation: String?,
        sourceLang: String,
        targetLang: String,
        provenance: String,
        sessionId: String,
        normKey: String,
        rect: android.graphics.Rect?,
        backendDisplayName: String?,
    ): Long = withContext(dispatcher) {
        val database = openDb(ctx)
        val id = database.insert("entries", null, ContentValues().apply {
            put("at_ms", atMs)
            put("source_text", sourceText)
            put("translation", translation)
            put("source_lang", sourceLang)
            put("target_lang", targetLang)
            put("provenance", provenance)
            put("session_id", sessionId)
            put("norm_key", normKey)
            if (rect != null) {
                put("rect_l", rect.left); put("rect_t", rect.top)
                put("rect_r", rect.right); put("rect_b", rect.bottom)
            }
            put("backend", backendDisplayName)
        })
        // Sessions with a row about to fall past the FIFO cap — captured
        // BEFORE the prune so their images can be reclaimed WITH the rows.
        // Empty until the table exceeds the cap, so the steady-state cost is
        // one indexed query; the row we just inserted is newest, so its own
        // session is never in here.
        val doomed = database.rawQuery(
            "SELECT DISTINCT session_id FROM entries WHERE id NOT IN " +
                "(SELECT id FROM entries ORDER BY id DESC LIMIT ?)",
            arrayOf(MAX_ROWS.toString()),
        ).use { c -> ArrayList<String>().apply { while (c.moveToNext()) add(c.getString(0)) } }
        database.execSQL(
            "DELETE FROM entries WHERE id NOT IN " +
                "(SELECT id FROM entries ORDER BY id DESC LIMIT $MAX_ROWS)"
        )
        reclaimOrphanImages(ctx, database, doomed)
        _revision.value++
        id
    }

    /** Delete the saved image of every session in [candidates] that no
     *  longer has any row — the invariant "image exists only while its
     *  session has rows" is enforced HERE, on every row-removal path, so
     *  no caller has to remember and [HistoryImageStore.sweep] is only ever
     *  a crash/race backstop. Runs on the store dispatcher; the actual file
     *  delete hops to the image store's thread so it can't race a save. */
    private suspend fun reclaimOrphanImages(
        ctx: Context,
        db: SQLiteDatabase,
        candidates: Collection<String>,
    ) {
        for (session in candidates) {
            val alive = db.rawQuery(
                "SELECT 1 FROM entries WHERE session_id = ? LIMIT 1", arrayOf(session),
            ).use { it.moveToFirst() }
            if (!alive) HistoryImageStore.deleteSession(ctx, session)
        }
    }

    /** Supersession (typewriter growth / punctuation completion): the
     *  fuller read overwrites the prior row in place. */
    suspend fun update(
        ctx: Context,
        rowId: Long,
        sourceText: String,
        translation: String?,
        atMs: Long,
        normKey: String,
    ): Unit = withContext(dispatcher) {
        openDb(ctx).update("entries", ContentValues().apply {
            put("source_text", sourceText)
            put("translation", translation)
            put("at_ms", atMs)
            put("norm_key", normKey)
        }, "id = ?", arrayOf(rowId.toString()))
        _revision.value++
    }

    /** Fill the NEWEST translation-less row bearing [normKey] with a
     *  translation that arrived after recording (history-tap re-translate,
     *  drag flows whose translation lands later than the lookup). Returns
     *  rows affected — 0 means no such row exists and the caller should
     *  record normally instead. */
    suspend fun attachTranslationByKey(
        ctx: Context,
        normKey: String,
        translation: String,
        sourceLang: String,
        targetLang: String,
        backendDisplayName: String?,
    ): Int = withContext(dispatcher) {
        val db = openDb(ctx)
        // Pair columns are part of the match: a translation produced under
        // one pair must never land on a row recorded under another.
        db.execSQL(
            "UPDATE entries SET translation = ?, backend = COALESCE(?, backend) WHERE id = (" +
                "SELECT id FROM entries WHERE norm_key = ? AND source_lang = ? AND target_lang = ? AND " +
                "(translation IS NULL OR translation = '') ORDER BY id DESC LIMIT 1)",
            arrayOf(translation, backendDisplayName, normKey, sourceLang, targetLang),
        )
        val affected = db.rawQuery("SELECT changes()", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (affected > 0) _revision.value++
        affected
    }

    /** Outcome of [attachCaptureTranslation] — see its contract. */
    enum class CaptureAttachOutcome { ATTACHED, ALREADY, NONE }

    /**
     * Idempotent, ATTACH-ONLY late fill for a DEFERRED capture's completion,
     * scoped to the capture's own [sessionId]. One store hop decides
     * everything (and the single-thread dispatcher orders it against any
     * concurrent completion of the same capture):
     *  1. a translation-less (session, key, pair) row exists → fill the
     *     newest one → [CaptureAttachOutcome.ATTACHED];
     *  2. a (session, key, pair) row exists but is already translated →
     *     [CaptureAttachOutcome.ALREADY] — a repeat completion (second
     *     surface, stash-reshow rebind, retry) is a no-op;
     *  3. no row at all → [CaptureAttachOutcome.NONE], and NOTHING is
     *     written. Deliberately no insert fallback: the rows a completion
     *     may fill are exactly the ones recorded at capture time — if the
     *     user cleared History (or the FIFO pruned them) since, the reveal
     *     must not resurrect pre-clear text, and a capture made while
     *     History was disabled has no rows and stays unrecorded even if the
     *     pref was enabled before the reveal.
     * Session-scoped on purpose, unlike [attachTranslationByKey]: the null
     * rows were written under this session, and another session's twin rows
     * must never receive this capture's translation.
     */
    suspend fun attachCaptureTranslation(
        ctx: Context,
        sessionId: String,
        normKey: String,
        translation: String,
        sourceLang: String,
        targetLang: String,
        backendDisplayName: String?,
    ): CaptureAttachOutcome = withContext(dispatcher) {
        val database = openDb(ctx)
        database.execSQL(
            "UPDATE entries SET translation = ?, backend = COALESCE(?, backend) WHERE id = (" +
                "SELECT id FROM entries WHERE session_id = ? AND norm_key = ? AND " +
                "source_lang = ? AND target_lang = ? AND " +
                "(translation IS NULL OR translation = '') ORDER BY id DESC LIMIT 1)",
            arrayOf(translation, backendDisplayName, sessionId, normKey, sourceLang, targetLang),
        )
        val affected = database.rawQuery("SELECT changes()", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (affected > 0) {
            _revision.value++
            return@withContext CaptureAttachOutcome.ATTACHED
        }
        val exists = database.rawQuery(
            "SELECT 1 FROM entries WHERE session_id = ? AND norm_key = ? AND " +
                "source_lang = ? AND target_lang = ? LIMIT 1",
            arrayOf(sessionId, normKey, sourceLang, targetLang),
        ).use { it.moveToFirst() }
        if (exists) CaptureAttachOutcome.ALREADY else CaptureAttachOutcome.NONE
    }

    /** Fill EXACTLY [id] with a translation produced after the fact (History
     *  row tap → results-page translate). Row identity is the caller's — no
     *  key matching, so twin rows sharing a normKey across sessions can
     *  never receive each other's translation. */
    suspend fun attachTranslationById(
        ctx: Context,
        id: Long,
        translation: String,
        backendDisplayName: String?,
    ): Unit = withContext(dispatcher) {
        openDb(ctx).execSQL(
            "UPDATE entries SET translation = ?, backend = COALESCE(?, backend) WHERE id = ?",
            arrayOf(translation, backendDisplayName, id.toString()),
        )
        _revision.value++
    }

    /** Newest first. */
    suspend fun recent(ctx: Context, limit: Int): List<HistoryEntry> = withContext(dispatcher) {
        val out = ArrayList<HistoryEntry>(limit)
        openDb(ctx).rawQuery(
            "SELECT id, at_ms, source_text, translation, source_lang, target_lang, " +
                "provenance, session_id, norm_key, backend FROM entries ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryEntry(
                        id = c.getLong(0),
                        atMs = c.getLong(1),
                        sourceText = c.getString(2),
                        translation = if (c.isNull(3)) null else c.getString(3),
                        sourceLang = c.getString(4),
                        targetLang = c.getString(5),
                        provenance = c.getString(6),
                        sessionId = c.getString(7),
                        normKey = c.getString(8),
                        backendDisplayName = if (c.isNull(9)) null else c.getString(9),
                    )
                )
            }
        }
        out
    }

    suspend fun delete(ctx: Context, id: Long): Unit = withContext(dispatcher) {
        val db = openDb(ctx)
        // Read the row's session before deleting so we can reclaim its image
        // if this was the session's last row.
        val session = db.rawQuery(
            "SELECT session_id FROM entries WHERE id = ?", arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        db.delete("entries", "id = ?", arrayOf(id.toString()))
        if (session != null) reclaimOrphanImages(ctx, db, listOf(session))
        _revision.value++
    }

    suspend fun clear(ctx: Context): Unit = withContext(dispatcher) {
        openDb(ctx).delete("entries", null, null)
        openDb(ctx).delete("collapsed_sessions", null, null)
        // Rows gone → every capture image is an orphan. Owned here so
        // "Clear history" wipes images atomically with the rows, not via a
        // caller that might forget.
        HistoryImageStore.clearAll(ctx)
        _revision.value++
    }

    /** Session ids the user collapsed in the History UI. Read-side prunes
     *  ids whose rows are gone (delete/FIFO/clear) so the set stays
     *  bounded by live sessions. No revision bump on either side: this is
     *  presentation state, not content. */
    suspend fun collapsedSessions(ctx: Context): Set<String> = withContext(dispatcher) {
        val db = openDb(ctx)
        db.execSQL(
            "DELETE FROM collapsed_sessions WHERE session_id NOT IN " +
                "(SELECT DISTINCT session_id FROM entries)"
        )
        val out = HashSet<String>()
        db.rawQuery("SELECT session_id FROM collapsed_sessions", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }

    suspend fun setSessionCollapsed(
        ctx: Context,
        sessionId: String,
        collapsed: Boolean,
    ): Unit = withContext(dispatcher) {
        val db = openDb(ctx)
        if (collapsed) {
            db.execSQL(
                "INSERT OR IGNORE INTO collapsed_sessions (session_id) VALUES (?)",
                arrayOf(sessionId),
            )
        } else {
            db.delete("collapsed_sessions", "session_id = ?", arrayOf(sessionId))
        }
    }

    suspend fun count(ctx: Context): Long = withContext(dispatcher) {
        openDb(ctx).rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
            c.moveToFirst(); c.getLong(0)
        }
    }

    /** Every session id with at least one surviving row — the reference
     *  set for [HistoryImageStore.sweep]'s orphan reconciliation. Full
     *  scan, but bounded by [MAX_ROWS]. */
    suspend fun distinctSessionIds(ctx: Context): Set<String> = withContext(dispatcher) {
        val out = HashSet<String>()
        openDb(ctx).rawQuery("SELECT DISTINCT session_id FROM entries", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }

    /** Tests only: drop the cached handle and delete the DB file so each
     *  test starts from an empty store. */
    @VisibleForTesting
    suspend fun resetForTest(ctx: Context): Unit = withContext(dispatcher) {
        db?.close()
        db = null
        File(File(ctx.applicationContext.noBackupFilesDir, "translationlog"), "history.sqlite").delete()
    }
}
