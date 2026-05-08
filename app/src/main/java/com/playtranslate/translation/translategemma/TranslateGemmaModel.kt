package com.playtranslate.translation.translategemma

import android.content.Context
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
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
object TranslateGemmaModel : ModelHelper {
    override val catalogKey: String = "engine-translategemma"
    private const val FILENAME = "translategemma-4b-it.Q4_0.gguf"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    /** Absolute path where the GGUF lives. Auto-creates the parent dir. */
    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$FILENAME").also { it.parentFile?.mkdirs() }

    /** True when the GGUF exists on disk and matches the catalog's expected size. */
    override fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val f = file(ctx)
        return f.exists() && f.length() == entry.size
    }

    /** Expected size in bytes per the catalog. Returns 0 if catalog missing. */
    override fun expectedSize(ctx: Context): Long = catalogEntry(ctx)?.size ?: 0L

    /** Human-readable size like "2.19 GB" — uses [humanSize] format. */
    override fun humanSize(ctx: Context): String = humanSize(expectedSize(ctx))

    /** Best-effort delete. Returns true if the file no longer exists after the call. */
    override fun delete(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) return true
        return f.delete()
    }
}
