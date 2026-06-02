package com.playtranslate.ocr.registry

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.MultiFileSha
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * [ModelHelper] for one downloadable OCR model pack — Meiki, or a PaddleOCR
 * per-script recognizer (`meiki-ja`, `paddle-rec-cjk`, …). One instance per pack
 * key; the catalog entry (`type:"ocr"`, MultiFile) is the source of truth for
 * files/sizes/sha. A **data-driven, parameterized** copy of
 * [com.playtranslate.translation.hymt.HyMtModel] (directory-mode MultiFile +
 * aggregate-SHA sentinel) so we don't hand-write one `object` per pack. Installs
 * under `noBackupFilesDir/models/<catalogKey>/` (where the other MNN model
 * artifacts live — NOT `langpacks/`).
 */
class OcrPackModelHelper(override val catalogKey: String) : ModelHelper {

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$catalogKey").also { it.parentFile?.mkdirs() }

    override fun isDirectoryMode(): Boolean = true

    override fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val expected = entry.files?.let { MultiFileSha.aggregate(it) } ?: entry.sha256 ?: return false
        val dir = file(ctx)
        if (!dir.exists() || !dir.isDirectory) return false
        val sentinel = File(dir, ".sentinel")
        if (!sentinel.exists()) return false
        return try {
            sentinel.readText().trim().equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "sentinel read failed for $catalogKey: ${e.message}"); false
        }
    }

    /** True iff the catalog has a *deliverable* entry for this pack: present AND
     *  with real (non-placeholder) per-file sizes + 64-hex SHA-256. Gates whether
     *  the engine needing this pack is offered to the user — so a pack that isn't
     *  authored/hosted yet (e.g. `paddle-rec-latin`) never surfaces a broken
     *  "not installed / can't download" option. */
    fun isShippable(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val files = entry.files
        return if (files != null) {
            files.isNotEmpty() && files.all { it.size > 0L && OnDeviceLlmDownloader.SHA_HEX_REGEX.matches(it.sha256) }
        } else {
            entry.size > 0L && entry.sha256?.let { OnDeviceLlmDownloader.SHA_HEX_REGEX.matches(it) } == true
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
        val tmpGone = File(file(ctx).parentFile, "${file(ctx).name}.tmp")
            .let { if (!it.exists()) true else it.deleteRecursively() }
        return dirGone && partialGone && tmpGone
    }

    private companion object { const val TAG = "OcrPackModelHelper" }
}
