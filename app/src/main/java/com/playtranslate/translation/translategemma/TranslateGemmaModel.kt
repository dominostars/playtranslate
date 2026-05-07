package com.playtranslate.translation.translategemma

import android.content.Context
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for the TranslateGemma model.
 *
 * The model URL, expected size, SHA-256, and license metadata all live in
 * `app/src/main/assets/langpack_catalog.json` under the `engine-translategemma`
 * key. This file does NOT hardcode any of those — they're read fresh from the
 * catalog so app updates can change the model version, mirror URL, or hashes
 * without code edits.
 *
 * The on-disk file is the source of truth for "is the model installed?" — we
 * deliberately don't track this in prefs so the file can be deleted out-of-band
 * (cache-clearing utilities, manual /data inspection, etc.) without leaving the
 * UI in an inconsistent state.
 */
object TranslateGemmaModel {
    const val CATALOG_KEY = "engine-translategemma"
    private const val FILENAME = "translategemma-4b-it.Q4_K_M.gguf"

    fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, CATALOG_KEY)

    /** Absolute path where the GGUF lives. Auto-creates the parent dir. */
    fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$FILENAME").also { it.parentFile?.mkdirs() }

    /** True when the GGUF exists on disk and matches the catalog's expected size. */
    fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val f = file(ctx)
        return f.exists() && f.length() == entry.size
    }

    /** Expected size in bytes per the catalog. Returns 0 if catalog missing. */
    fun expectedSize(ctx: Context): Long = catalogEntry(ctx)?.size ?: 0L

    /** Human-readable size like "2.49 GB" — uses [humanSize] format. */
    fun humanSize(ctx: Context): String = humanSize(expectedSize(ctx))

    /** Best-effort delete. Returns true if the file no longer exists after the call. */
    fun delete(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) return true
        return f.delete()
    }
}

/**
 * Format a byte count as human-readable text. Decimal (10^9) units to match
 * how app stores and OS Settings display sizes.
 */
fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L     -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000L         -> "%d KB".format(bytes / 1_000L)
    else                    -> "$bytes B"
}
