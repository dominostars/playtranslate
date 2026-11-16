package com.playtranslate.audio.localaudio

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.audio.AudioCache
import com.playtranslate.audio.sources.LocalAudioSource
import com.playtranslate.dictionary.withRefcount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The imported local audio file ([LocalAudioStore.file]), read. It is the
 * SQLite database the desktop "Local Audio Server for Yomitan" add-on writes
 * for Android readers, of two tables:
 *
 *  - `entries(expression, reading, source, speaker, display, file)`: one row
 *    per recording of a written form;
 *  - `android(file, source, data)`: a recording's bytes.
 *
 * One read-only handle, opened on the first read and kept, as the built-in
 * dictionary's is ([com.playtranslate.dictionary.DictionaryManager]). Every
 * read runs on [Dispatchers.IO] and answers "nothing" when there is no file
 * or the read fails: a word with no recording is spoken by the voice, and
 * so is one whose recording could not be read.
 *
 * The two are not the same thing, and only the caller of a read may treat
 * them alike. A read that failed (a file that will not open, a page SQLite
 * calls corrupt, as in a file cut short, a clip too large for a cursor
 * row) is kept in [problem], in the words of whatever failed, for the page
 * the file is loaded on to show. Without it a damaged file would look, to
 * its user, like a file with fewer words in it.
 *
 * **Which file is loaded is decided here and nowhere else.** One lock is
 * held to open the handle, held by [clip] from its read until its caller
 * has stored what it keeps of the clip, and held by [replacing], the only
 * way the file is removed or replaced, from letting the handle go to
 * emptying the store of kept clips. There is no other way to close the
 * handle and no other place those clips are dropped. So:
 *
 *  - nothing opens the file while it is being removed or replaced: a
 *    lookup that arrives then waits, and opens whatever is there after;
 *  - nothing read from a file is stored once that file has gone: a clip
 *    being kept is kept before the removal starts, and the removal then
 *    takes it with the rest.
 *
 * A read that keeps nothing ([rows], the scans) opens under the lock and
 * runs outside it, so a scan of seconds never holds up a tap. One caught in
 * flight by a removal finishes on the handle it holds and answers from the
 * file that was there when it began; nothing of it outlives its caller.
 */
object LocalAudioDb {

    private const val TAG = "PtAudio"

    private const val SQL_ROWS =
        "SELECT source, reading, speaker, display, file FROM entries WHERE expression = ?"
    private const val SQL_CLIP = "SELECT data FROM android WHERE file = ? AND source = ? LIMIT 1"
    private const val SQL_SOURCES = "SELECT DISTINCT source FROM entries"
    private const val SQL_CLIP_FILES = "SELECT file FROM android"

    /** The columns the reads above name, asked for in [probe]. */
    private val PROBES = listOf(
        "SELECT expression, reading, source, speaker, display, file FROM entries LIMIT 1",
        "SELECT file, source, data FROM android LIMIT 1",
    )

    /**
     * The platform's own handler deletes a database file SQLite reports as
     * corrupt. This file is several gigabytes the user downloaded and
     * copied in; one bad page must cost the words on that page, not the
     * file. Remove is the user's.
     */
    private val KEEP_FILE = DatabaseErrorHandler { db ->
        Log.e(TAG, "Local audio: SQLite reports ${db.path} as corrupt; the file is kept")
    }

    /** Guards which file is loaded: see the class's own words on it. */
    private val mutex = Mutex()

    @Volatile
    private var db: SQLiteDatabase? = null

    /**
     * What last went wrong reading the file, in SQLite's or the system's
     * own words, or null while no read has failed. It is about the file
     * that is loaded: [replacing], which every removal and replacement
     * goes through, forgets it. Held in memory, so a new process knows of a
     * damaged page only once a read meets it; [check] finds a file that
     * will not open at all without waiting for one.
     */
    @Volatile
    var problem: String? = null
        private set

    /** Every recording of the written form [expression], in no order. */
    suspend fun rows(ctx: Context, expression: String): List<LocalAudioRow> =
        read(ctx, "rows of a word", emptyList()) { handle ->
            handle.rawQuery(SQL_ROWS, arrayOf(expression)).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            LocalAudioRow(
                                source = c.getString(0),
                                reading = c.getString(1),
                                speaker = c.getString(2),
                                display = c.getString(3),
                                file = c.getString(4),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * The bytes of the recording [file] of [source], handed to [keep],
     * whose answer is this one's. Null, with [keep] never called, when the
     * file holds none under that name. A clip too large for one cursor row
     * (the platform's limit is 2 MB) fails the read and is a miss, logged;
     * a word's recording is a few tens of kilobytes.
     *
     * [keep] is where the caller stores the clip (the source puts it in the
     * audio cache), and it runs before the file can be removed or replaced:
     * [replacing] waits for it. What it stores is therefore of the file
     * that is loaded while it stores it, and the removal that follows
     * finds it and takes it. Clips are read one at a time for this; one
     * takes milliseconds.
     */
    suspend fun <T> clip(ctx: Context, source: String, file: String, keep: suspend (ByteArray) -> T): T? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val handle = openLocked(ctx) ?: return@withLock null
                val bytes = query<ByteArray?>(handle, "clip $source/$file", null) {
                    handle.rawQuery(SQL_CLIP, arrayOf(file, source)).use { c ->
                        if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.isNotEmpty() } else null
                    }
                } ?: return@withLock null
                keep(bytes)
            }
        }

    /** The sources the file holds recordings of, in the order they are
     *  offered ([LocalAudioLookup.ordered]), or null when they could not be
     *  read, which is not the same as a file that holds none. A scan of the
     *  whole index: for the line a load logs, not for a tap. */
    suspend fun sources(ctx: Context): List<String>? =
        read<List<String>?>(ctx, "sources", null) { handle ->
            handle.rawQuery(SQL_SOURCES, null).use { c ->
                LocalAudioLookup.ordered(buildList { while (c.moveToNext()) add(c.getString(0)) })
            }
        }

    /** How many clips the file holds per file extension ("opus", "mp3"),
     *  for the line an import logs. A scan of the clip index. */
    suspend fun clipsByExtension(ctx: Context): Map<String, Int> =
        read(ctx, "clips by extension", emptyMap()) { handle ->
            handle.rawQuery(SQL_CLIP_FILES, null).use { c ->
                val counts = sortedMapOf<String, Int>()
                while (c.moveToNext()) {
                    val extension = c.getString(0).substringAfterLast('.', "").lowercase()
                    counts[extension] = (counts[extension] ?: 0) + 1
                }
                counts
            }
        }

    /**
     * Opens the file and asks it for its tables, as [probe] asks a file
     * being imported: what a file that is no longer a database of
     * recordings answers lands in [problem]. Nothing is read from the
     * tables, so this costs no more than opening the file. With no file
     * there is nothing to check.
     */
    suspend fun check(ctx: Context) {
        read(ctx, "the tables", Unit) { handle ->
            PROBES.forEach { sql -> handle.rawQuery(sql, null).use { } }
        }
    }

    /**
     * The one way the loaded file is removed or replaced. [change] does the
     * removing or the replacing (deletes the file, renames another into its
     * place); around it, in one step that no read can come between:
     *
     *  1. the handle on the file is let go, with what was known to be wrong
     *     with that file;
     *  2. [change] runs;
     *  3. the clips kept from the old file are dropped from the audio cache.
     *
     * A read that arrives meanwhile opens whatever file is there once this
     * is done. A [change] that throws leaves the kept clips alone: the file
     * they are of is still the one loaded.
     *
     * One thing made from the old file can still land afterwards: a card's
     * conversion of one of its clips ([com.playtranslate.audio.CardAudio]),
     * under way at the time. It is kept under its clip's own bytes, so it
     * is only ever found again by that same audio.
     */
    suspend fun <T> replacing(ctx: Context, change: suspend () -> T): T = mutex.withLock {
        db?.close()
        db = null
        problem = null
        val changed = change()
        AudioCache(ctx).dropClips(LocalAudioSource.ID)
        changed
    }

    /**
     * Whether [file] has the two tables with the columns read here. Null
     * when it has; otherwise what SQLite said of the first thing missing
     * ("no such table: android"), which is what the user is shown.
     *
     * Nothing is read from the tables: naming a table and its columns in a
     * statement is enough for SQLite to refuse one that is not there.
     */
    fun probe(file: File): String? = try {
        open(file).use { handle ->
            PROBES.forEach { sql -> handle.rawQuery(sql, null).use { } }
        }
        null
    } catch (e: Exception) {
        words(e)
    }

    /** What [e] says, without what the platform adds after it: its message
     *  goes on with a result code and the statement ("... (code 1
     *  SQLITE_ERROR): , while compiling: ..."). */
    private fun words(e: Exception): String =
        e.message?.substringBefore(" (code")?.trim()?.ifEmpty { null } ?: e.javaClass.simpleName

    /** A read that keeps nothing: the handle is opened under the lock, and
     *  the read itself runs outside it. */
    private suspend fun <T> read(ctx: Context, what: String, miss: T, block: (SQLiteDatabase) -> T): T =
        withContext(Dispatchers.IO) {
            val handle = mutex.withLock { openLocked(ctx) } ?: return@withContext miss
            query(handle, what, miss) { block(handle) }
        }

    /** [block] against [handle], with a failure kept as the file's
     *  [problem] and answered as [miss]. */
    private fun <T> query(handle: SQLiteDatabase, what: String, miss: T, block: () -> T): T =
        try {
            // Null from withRefcount: the handle was closed (the file was
            // removed) before this read could hold it.
            handle.withRefcount(block) ?: miss
        } catch (e: Exception) {
            Log.w(TAG, "Local audio: reading $what failed: ${e.message}")
            problem = words(e)
            miss
        }

    /** The handle on the loaded file, opened if it is not. Only ever called
     *  with the lock held: this is the one place the file is opened. */
    private fun openLocked(ctx: Context): SQLiteDatabase? {
        db?.let { return it }
        val file = LocalAudioStore.file(ctx)
        if (!file.isFile) return null
        return try {
            open(file).also { db = it }
        } catch (e: Exception) {
            Log.e(TAG, "Local audio: ${file.name} could not be opened: ${e.message}")
            problem = words(e)
            null
        }
    }

    /** Read-only, and no journal mode set: a read-only handle cannot change
     *  one, and the attempt is a crash. */
    private fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY, KEEP_FILE)
}
