package com.playtranslate.translation.gemma

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for the MNN-format Gemma 4 E2B
 * Instruct model. Mirrors [com.playtranslate.translation.qwen.QwenMnnModel] —
 * directory-mode: MNN's `Llm::createLLM` expects a `config.json` inside the
 * model dir alongside `llm.mnn`, `llm.mnn.weight`, `tokenizer.mtok`, and (for
 * Gemma 4) the PLE table `ple_embeddings_int4.bin`.
 *
 * Catalog entry: `engine-gemma-4-e2b-mnn`. Distribution: a single zip on
 * HuggingFace under the `playtranslate` org. The catalog's `size` / `sha256`
 * are the **zip's** integrity values, not the extracted footprint.
 * [com.playtranslate.translation.llm.OnDeviceLlmDownloader]'s ZipExtract path
 * (driven by [isDirectoryMode]) handles the extract + atomic directory swap;
 * sentinel-file gate at the end matches the catalog sha.
 *
 * Phase 1 ships the multimodal-as-distributed bundle from `taobao-mnn`; Phase 2
 * converts `principled-intelligence/gemma-4-E2B-it-text-only` ourselves via
 * `llmexport.py` to drop the inert audio/visual weights (~780 MB on disk and
 * resident RAM). Either bundle's `config.json` resolves to the same MNN
 * directory layout, so this helper doesn't need to know which is on device.
 */
object GemmaE2BMnnModel : ModelHelper {
    override val catalogKey: String = "engine-gemma-4-e2b-mnn"

    /** Directory name under `noBackupFilesDir/models/`. */
    private const val DIR_NAME = "gemma-4-e2b-mnn"

    /** Sentinel file written by the downloader after a successful extract +
     *  swap. Contents = catalog sha256. Absence (or mismatch) means an
     *  in-progress / partial install — [isInstalled] returns false. */
    private const val SENTINEL_FILENAME = ".sentinel"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$DIR_NAME").also { it.parentFile?.mkdirs() }

    override fun isDirectoryMode(): Boolean = true

    /** Path passed to `Llm::createLLM` — the model directory's `config.json`. */
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

    private const val TAG = "GemmaE2BMnnModel"
}
