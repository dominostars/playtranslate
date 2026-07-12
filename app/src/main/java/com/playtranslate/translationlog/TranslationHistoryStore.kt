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
        database.execSQL(
            "DELETE FROM entries WHERE id NOT IN " +
                "(SELECT id FROM entries ORDER BY id DESC LIMIT $MAX_ROWS)"
        )
        _revision.value++
        id
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
        backendDisplayName: String?,
    ): Int = withContext(dispatcher) {
        val db = openDb(ctx)
        db.execSQL(
            "UPDATE entries SET translation = ?, backend = COALESCE(?, backend) WHERE id = (" +
                "SELECT id FROM entries WHERE norm_key = ? AND " +
                "(translation IS NULL OR translation = '') ORDER BY id DESC LIMIT 1)",
            arrayOf(translation, backendDisplayName, normKey),
        )
        val affected = db.rawQuery("SELECT changes()", null).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (affected > 0) _revision.value++
        affected
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
        openDb(ctx).delete("entries", "id = ?", arrayOf(id.toString()))
        _revision.value++
    }

    suspend fun clear(ctx: Context): Unit = withContext(dispatcher) {
        openDb(ctx).delete("entries", null, null)
        _revision.value++
    }

    suspend fun count(ctx: Context): Long = withContext(dispatcher) {
        openDb(ctx).rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
            c.moveToFirst(); c.getLong(0)
        }
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
