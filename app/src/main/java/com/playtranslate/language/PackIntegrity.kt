package com.playtranslate.language

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * File integrity helpers for language pack installs.
 *
 * - [sha256Hex] hashes a file with streaming IO so the whole contents never
 *   need to fit in memory (packs can be tens of MB).
 * - [extractZip] unpacks a zip into a directory, rejecting entries whose
 *   paths contain `..` to block path-traversal attacks from a malicious
 *   (or corrupted) pack. No per-entry hash check — Phase 3 verifies the
 *   whole-zip SHA-256 against the bundled catalog before calling this.
 */
object PackIntegrity {

    /** Returns the lowercase hex SHA-256 of [file]'s contents. Streams so the whole
     *  file never sits in memory (packs/models are tens of MB to multi-GB), and is
     *  cancellable mid-stream so a user cancel during hashing is honored promptly. */
    suspend fun sha256Hex(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Atomically replace [to] with [from] on the same filesystem: `rename(2)`
     * either succeeds wholly or leaves both paths untouched, and `REPLACE_EXISTING`
     * covers in-place upgrades (a new version landing at the same name). The single
     * commit primitive for installing a fully-written/verified file — the model
     * downloader's FileSwap + MultiFile strategies and the bundled OCR detector copy
     * all land through here. [from] and [to] must be on the same filesystem (e.g.
     * both under `noBackupFilesDir`) so `ATOMIC_MOVE` is honored by ext4/f2fs
     * without a silent copy fallback. Throws on failure (caller decides how to
     * surface it); on failure both paths stay intact, so a previous install at [to]
     * keeps serving and [from] survives for a retry.
     */
    fun atomicReplace(from: File, to: File) {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /**
     * Extracts every entry in [zipFile] into [targetDir], creating
     * subdirectories as needed. Entries whose paths contain `..` are
     * skipped (path-traversal defense). [targetDir] is created if absent.
     */
    suspend fun extractZip(zipFile: File, targetDir: File) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    val out = File(targetDir, entry.name)
                    if (!isInside(targetDir, out)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                } finally {
                    zis.closeEntry()
                }
            }
        }
    }

    /**
     * `true` iff [candidate]'s canonical path lies strictly under
     * [targetDir]'s canonical path. Battle-tested path-traversal defense
     * used by both [extractZip] (per zip entry) and the MultiFile
     * downloader strategy (per catalog file path). Resolves `..` and
     * symlinks via [java.io.File.getCanonicalPath]; rejects absolute
     * paths because `File("/anchor", "/etc/passwd")` returns
     * `/etc/passwd`, whose canonical form will not start with the
     * target's canonical prefix.
     *
     * Returns `false` on IOException during canonicalization (extremely
     * unlikely on a noBackupFilesDir path, but the conservative answer
     * is "not inside" — better to skip than to write somewhere unsafe).
     */
    fun isInside(targetDir: File, candidate: File): Boolean = try {
        candidate.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
    } catch (e: java.io.IOException) {
        false
    }
}
