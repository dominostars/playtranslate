package com.playtranslate.translation.bergamot

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.MultiFileSha
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * MultiFile [ModelHelper] for one Bergamot translation direction (e.g. "ja-en").
 *
 * Catalog key `bergamot-<direction>`; the loose model / vocab / shortlist files
 * land in `noBackupFilesDir/models/bergamot-<direction>/` via the downloader's
 * MultiFile + gzip path (the catalog files carry `gzip:true` and Mozilla GCS
 * URLs). The aggregate-SHA `.sentinel` written after a verified install is the
 * install gate — re-derived from the current catalog so any catalog edit forces
 * a re-download.
 */
class BergamotModel(val direction: String) : ModelHelper {
    override val catalogKey: String = "$PREFIX$direction"
    private val dirName = "$PREFIX$direction"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$dirName").also { it.parentFile?.mkdirs() }

    override fun isDirectoryMode(): Boolean = true

    override fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val expected = entry.files?.let { MultiFileSha.aggregate(it) } ?: entry.sha256 ?: return false
        val dir = file(ctx)
        if (!dir.exists() || !dir.isDirectory) return false
        val sentinel = File(dir, SENTINEL)
        if (!sentinel.exists()) return false
        return try {
            sentinel.readText().trim().equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read sentinel at ${sentinel.absolutePath}: ${e.message}")
            false
        }
    }

    override fun expectedSize(ctx: Context): Long {
        val entry = catalogEntry(ctx) ?: return 0L
        return entry.files?.sumOf { it.size } ?: entry.size
    }

    override fun humanSize(ctx: Context): String = humanSize(expectedSize(ctx))

    override fun delete(ctx: Context): Boolean {
        val dirGone = file(ctx).let { if (!it.exists()) true else it.deleteRecursively() }
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        val tmpGone = File(file(ctx).parentFile, "${file(ctx).name}.tmp").let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        return dirGone && partialGone && tmpGone
    }

    companion object {
        const val PREFIX = "bergamot-"
        private const val SENTINEL = ".sentinel"
        private const val TAG = "BergamotModel"
    }
}
