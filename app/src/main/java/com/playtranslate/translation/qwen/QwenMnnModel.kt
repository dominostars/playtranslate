package com.playtranslate.translation.qwen

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for the MNN-format Qwen 2.5 1.5B
 * Instruct model. Mirrors [QwenModel] (the legacy GGUF helper) but in
 * directory mode — MNN's `Llm::createLLM` expects a `config.json` inside the
 * model dir alongside `llm.mnn`, `llm.mnn.weight`, and `tokenizer.txt`, so the
 * helper points at the *extracted root directory* rather than a single file.
 *
 * Catalog entry: `engine-qwen-1-5b-mnn` (added to `langpack_catalog.json` in
 * task #19). Distribution: a single zip on HuggingFace under the
 * `playtranslate` org; the catalog's `size` / `sha256` are the **zip's**
 * integrity values, not the extracted footprint. The
 * [com.playtranslate.translation.llm.OnDeviceLlmDownloader]'s ZipExtract path
 * (driven by [isDirectoryMode]) handles the extract + atomic directory swap.
 *
 * Installation gate: after a successful download → verify → extract → swap,
 * the downloader writes `.sentinel` inside the final directory containing the
 * catalog sha. [isInstalled] checks that sentinel matches — the file-mode
 * "match catalog size" trick doesn't apply since catalog size is the zip's
 * compressed size, not the extracted footprint.
 */
object QwenMnnModel : ModelHelper {
    override val catalogKey: String = "engine-qwen-1-5b-mnn"

    /** Directory name under `noBackupFilesDir/models/` where the extracted
     *  model lives. The zip's root entries land directly under here. */
    private const val DIR_NAME = "qwen-1-5b-mnn"

    /** Sentinel file written by the downloader after a successful extract +
     *  swap. Contents = catalog sha256. Absence (or mismatch) means an
     *  in-progress / partial install — [isInstalled] returns false. */
    private const val SENTINEL_FILENAME = ".sentinel"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$DIR_NAME").also { it.parentFile?.mkdirs() }

    override fun isDirectoryMode(): Boolean = true

    /**
     * The path passed to `Llm::createLLM` — the model directory's
     * `config.json`. Convenience getter; not on the [ModelHelper] interface
     * because file-mode helpers wouldn't use it.
     */
    fun configFile(ctx: Context): File = File(file(ctx), "config.json")

    override fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val expectedSha = entry.sha256 ?: return false
        val dir = file(ctx)
        if (!dir.exists() || !dir.isDirectory) return false
        val sentinel = File(dir, SENTINEL_FILENAME)
        if (!sentinel.exists()) return false
        return try {
            sentinel.readText().trim().equals(expectedSha, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read sentinel at ${sentinel.absolutePath}: ${e.message}")
            false
        }
    }

    override fun expectedSize(ctx: Context): Long = catalogEntry(ctx)?.size ?: 0L

    override fun humanSize(ctx: Context): String = humanSize(expectedSize(ctx))

    override fun delete(ctx: Context): Boolean {
        val dirGone = file(ctx).let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        // Tmp staging directory (mid-extract kill artifact) also wiped here so
        // a successful delete clears all related on-disk state.
        val tmpGone = File(file(ctx).parentFile, "${file(ctx).name}.tmp").let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        return dirGone && partialGone && tmpGone
    }

    private const val TAG = "QwenMnnModel"
}
