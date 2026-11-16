package com.playtranslate.audio.localaudio

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import com.playtranslate.language.PackIntegrity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** How an import ended. A cancelled import ends in no result: its
 *  cancellation is rethrown. */
sealed interface LocalAudioImportResult {
    data object Imported : LocalAudioImportResult

    /** The picked file is not an SQLite database. Nothing was copied. */
    data object NotSqlite : LocalAudioImportResult

    /** An SQLite database without the tables or columns read here;
     *  [reason] is what SQLite said of the first one missing. */
    data class InvalidFormat(val reason: String) : LocalAudioImportResult

    /** The file, or the copy of it, is shorter than the size its own
     *  header records or the size its picker gave: a download or a copy
     *  that was cut short. */
    data class Incomplete(val haveBytes: Long, val expectedBytes: Long) : LocalAudioImportResult

    data class InsufficientSpace(val neededBytes: Long, val freeBytes: Long) : LocalAudioImportResult

    /** The copy failed; [message] is the system's own, when it gave one. */
    data class IoError(val message: String?) : LocalAudioImportResult
}

/** Where an import is, for whoever shows it. */
sealed interface LocalAudioImportStep {
    /** [total] is -1 when neither the picker nor the file says. */
    data class Copying(val copiedBytes: Long, val totalBytes: Long) : LocalAudioImportStep

    /** The copy is whole and is being checked; no longer a matter of bytes. */
    data object Checking : LocalAudioImportStep
}

/**
 * The imported local audio file on disk: where it is, how it gets there and
 * how it leaves. The user picks it (the `android.db` of a local audio
 * collection); it is copied into the app, because the platform's SQLite
 * opens a path and a picked document has none.
 *
 * It lives under `noBackupFilesDir`, beside the language packs and the
 * models: several gigabytes that belong in no backup and no device
 * transfer. "Imported" is that the file exists ([isImported]); nothing else
 * records it, so nothing can disagree with it.
 */
object LocalAudioStore {

    private const val TAG = "PtAudio"
    private const val DIR = "local-audio"
    private const val NAME = "android.db"

    private const val CHUNK_BYTES = 1 shl 20
    private const val PROGRESS_STEP_BYTES = 8L shl 20

    /** An SQLite file begins with this, and its first 100 bytes are its
     *  header. */
    private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 100

    /** What SQLite may leave beside a database it opened. */
    private val SIDECARS = listOf("-journal", "-wal", "-shm")

    /** One import at a time, and never a [sweepPart] under one. */
    private val importing = Mutex()

    fun file(ctx: Context): File = File(File(ctx.noBackupFilesDir, DIR), NAME)

    private fun partFile(ctx: Context): File = File(file(ctx).path + ".part")

    fun isImported(ctx: Context): Boolean = file(ctx).isFile

    fun sizeBytes(ctx: Context): Long = file(ctx).length()

    /**
     * Copies the document at [uri] into the app as the local audio file.
     *
     * Before anything is copied: the document must start as an SQLite file
     * does, must not be shorter than its own header says it is, and must
     * fit, with the headroom every large download here keeps (5%, at least
     * 100 MB). The copy goes to a part file beside the destination; a copy
     * that came out short, or that lacks the tables read here
     * ([LocalAudioDb.probe]), is refused; a whole one is flushed and renamed
     * into place. Every other end, a cancel included, deletes the part.
     *
     * Known and left: one document is picked, so a database whose last
     * writer left committed rows in a `-wal` file beside it is copied
     * without them. Nothing here can see that file, and a database in that
     * mode is not refused: one that passed through an app which only read
     * it is in the same mode and is whole. The file the desktop add-on
     * writes is closed by it and has no such file beside it.
     *
     * [onStep] is called on the copying thread.
     */
    suspend fun import(
        ctx: Context,
        uri: Uri,
        onStep: (LocalAudioImportStep) -> Unit,
    ): LocalAudioImportResult = withContext(Dispatchers.IO) {
        importing.withLock {
            val dest = file(ctx)
            val part = partFile(ctx)
            var committed = false
            try {
                dest.parentFile?.mkdirs()
                val input = ctx.contentResolver.openInputStream(uri)
                    ?: return@withLock LocalAudioImportResult.IoError(null)
                val outcome = input.use { copyChecked(ctx, uri, it, part, onStep) }
                if (outcome != null) return@withLock outcome

                onStep(LocalAudioImportStep.Checking)
                val refusal = LocalAudioDb.probe(part)
                // The check opened the part as a database, which can leave
                // SQLite's own files beside it.
                deleteSidecars(part)
                if (refusal != null) return@withLock LocalAudioImportResult.InvalidFormat(refusal)

                // The last moment a cancel leaves nothing: the copy's flush
                // to disk and the check both take time a Cancel can land in.
                coroutineContext.ensureActive()
                // Into place in one step with the handle and the kept clips
                // of whatever file was here before, were there one.
                LocalAudioDb.replacing(ctx) { PackIntegrity.atomicReplace(part, dest) }
                committed = true
                LocalAudioImportResult.Imported
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Local audio: import failed", e)
                LocalAudioImportResult.IoError(e.message)
            } finally {
                if (!committed) deleteWithSidecars(part)
            }
        }
    }

    /**
     * Checks the head of [input] and copies it whole to [part]. Null when
     * the copy is whole; otherwise why there is none.
     */
    private suspend fun copyChecked(
        ctx: Context,
        uri: Uri,
        input: InputStream,
        part: File,
        onStep: (LocalAudioImportStep) -> Unit,
    ): LocalAudioImportResult? {
        val header = ByteArray(HEADER_BYTES)
        if (readFully(input, header) < HEADER_BYTES || !header.startsWith(MAGIC)) {
            return LocalAudioImportResult.NotSqlite
        }
        val expected = sizeInHeader(header)
        val declared = declaredLength(ctx, uri)
        if (expected > 0 && declared in 1 until expected) {
            return LocalAudioImportResult.Incomplete(declared, expected)
        }
        val total = maxOf(declared, expected)
        if (total > 0) {
            val free = StatFs(ctx.noBackupFilesDir.absolutePath).availableBytes
            val needed = (total * 105 / 100).coerceAtLeast(total + 100_000_000L)
            if (free < needed) return LocalAudioImportResult.InsufficientSpace(needed, free)
        }

        var copied = header.size.toLong()
        FileOutputStream(part).use { out ->
            out.write(header)
            val buffer = ByteArray(CHUNK_BYTES)
            var reported = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                copied += read
                if (copied - reported >= PROGRESS_STEP_BYTES) {
                    reported = copied
                    onStep(LocalAudioImportStep.Copying(copied, total))
                }
            }
            // On disk before the rename: a file that exists is the whole
            // record of an import, so it must not exist with its end still
            // unwritten when the power goes.
            out.fd.sync()
        }
        return shortfall(copied, declared, expected)
    }

    /**
     * Whether a copy of [copied] bytes came out short, against the sizes
     * that are known: the one the picker gave for the document ([declared])
     * and the one the file's own header records ([expected]), each -1 when
     * there is none. The larger of the two is the whole, since either can
     * be missing and neither can overstate a file that is all there.
     *
     * With neither known the whole is -1, which no copy is short of: a
     * copy that cannot be measured is taken as whole. A file that then
     * turns out to lack pages says so where it is read
     * ([LocalAudioDb.problem]).
     */
    internal fun shortfall(copied: Long, declared: Long, expected: Long): LocalAudioImportResult.Incomplete? {
        val whole = maxOf(declared, expected)
        return if (copied < whole) LocalAudioImportResult.Incomplete(copied, whole) else null
    }

    /**
     * The file's size as its own header records it (the page size times the
     * page count), or -1 when the header does not vouch for the count:
     * SQLite's file format takes the count as valid only when it is not
     * zero and the header's two change counters agree.
     *
     * This is what tells a download that was cut short from a whole file.
     * A cut file still opens and still has its tables; it only lacks the
     * words on the pages that never arrived.
     */
    internal fun sizeInHeader(header: ByteArray): Long {
        fun u16(at: Int) = ((header[at].toInt() and 0xFF) shl 8) or (header[at + 1].toInt() and 0xFF)
        fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        val pageSize = u16(16).let { if (it == 1) 65_536 else it }
        val pages = u32(28)
        val countIsCurrent = u32(24) == u32(92)
        return if (pages > 0 && countIsCurrent) pages * pageSize else -1L
    }

    /** The document's length as its provider gives it, or -1. */
    private fun declaredLength(ctx: Context, uri: Uri): Long =
        runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull()?.takeIf { it > 0 } ?: -1L

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var filled = 0
        while (filled < into.size) {
            val read = input.read(into, filled, into.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * Logs one line saying what the imported file is: its size, its sources
     * and its clips by file extension. The extension is how a clip's format
     * is known, and Ogg/Opus, which most collections are in, does not play
     * on AnkiMobile or AnkiWeb.
     *
     * Two scans of the file's indexes, seconds on a file of gigabytes, so
     * it is no part of [import]: an import is done when the file is in
     * place, and its popup must not stand, Cancel and all, over an import
     * that can no longer be cancelled.
     */
    suspend fun logSummary(ctx: Context) {
        Log.i(
            TAG,
            "Local audio imported: bytes=${sizeBytes(ctx)} sources=${LocalAudioDb.sources(ctx)} " +
                "clips=${LocalAudioDb.clipsByExtension(ctx)}",
        )
    }

    /** Deletes the file, and with it what was read from it: the open handle
     *  and the clips kept in the audio cache. One step that no read comes
     *  between ([LocalAudioDb.replacing]): a word tapped at that moment
     *  neither reopens the file nor puts a clip of it back. */
    suspend fun remove(ctx: Context) = withContext(Dispatchers.IO) {
        LocalAudioDb.replacing(ctx) { deleteWithSidecars(file(ctx)) }
    }

    /**
     * Deletes the part file a killed process left. Asked at launch, since
     * gigabytes that nothing will finish cannot wait for the page to be
     * opened. It waits for an import that is running, whose part is its
     * own, and finds nothing after it.
     */
    suspend fun sweepPart(ctx: Context) = withContext(Dispatchers.IO) {
        importing.withLock { deleteWithSidecars(partFile(ctx)) }
    }

    private fun deleteWithSidecars(file: File) {
        file.delete()
        deleteSidecars(file)
    }

    private fun deleteSidecars(file: File) {
        SIDECARS.forEach { File(file.path + it).delete() }
    }
}
