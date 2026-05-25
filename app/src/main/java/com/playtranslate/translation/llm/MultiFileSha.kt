package com.playtranslate.translation.llm

import com.playtranslate.language.CatalogFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic aggregate SHA-256 over a [CatalogFile] list. Used by the
 * MultiFile commit strategy as the single value written to the model
 * directory's `.sentinel`, and re-derived from the catalog at
 * `isInstalled()` time. A catalog edit that touches any per-file `path`
 * or `sha256` (or that adds/removes a file) flips the aggregate, which
 * makes the existing single-value sentinel-mismatch path detect catalog
 * upgrades automatically.
 *
 * **Contract:**
 * - Sort by `path` (natural String / Unicode codepoint ordering). Two
 *   catalog edits that reorder the JSON array without changing values
 *   yield the same aggregate.
 * - Per-file `sha256` is lowercased before hashing so a case-sensitivity
 *   slip in upstream tooling doesn't produce a different aggregate from
 *   the same content.
 * - The hashed message is `"<path>:<sha256>\n"` per file, encoded UTF-8.
 *   Both fields contribute; either changing flips the aggregate.
 * - Returns lowercase 64-char hex.
 *
 * **Not a security boundary.** This is an integrity tag for catalog-vs-
 * disk-state comparison, not a defense against an active attacker — the
 * per-file SHA-256 pins (which the downloader verifies before commit)
 * are the real integrity check. The aggregate just makes catalog
 * upgrades and partial-install detection cheap.
 *
 * Single source of truth so the writer (downloader) and the reader
 * (`HyMtModel.isInstalled()`) can't drift. Tests in
 * `app/src/test/java/com/playtranslate/translation/llm/MultiFileShaTest.kt`.
 */
object MultiFileSha {

    fun aggregate(files: List<CatalogFile>): String {
        val md = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.path }.forEach { f ->
            md.update("${f.path}:${f.sha256.lowercase(Locale.ROOT)}\n".toByteArray(Charsets.UTF_8))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
