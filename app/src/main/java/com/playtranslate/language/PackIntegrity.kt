package com.playtranslate.language

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

    /** Returns the lowercase hex SHA-256 of [file]'s contents. */
    suspend fun sha256Hex(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
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
                    if (!out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) continue
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
}
