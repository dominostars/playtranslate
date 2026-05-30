package com.playtranslate.translation.qwen

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * Directory-mode [ModelHelper] for the MNN-format **Qwen 3.5 4B** model — the
 * Apache-2.0 high-quality on-device LLM tier (spike judge-leader, ahead of
 * Gemma 4 E2B and HyMt 1.5; see `mnn-spike/QWEN35_SPIKE_REPORT.md`). Same
 * install/sentinel mechanics as [QwenMnnModel]; only the catalog key and
 * on-disk directory differ.
 *
 * Catalog entry: `engine-qwen-3-5-4b-mnn`. Distribution: a single zip on
 * HuggingFace (`playtranslate/qwen3.5-4b-mnn`); `extract: true` drives the
 * [com.playtranslate.translation.llm.OnDeviceLlmDownloader] ZipExtract path.
 */
object Qwen35Mnn4bModel : ModelHelper {
    override val catalogKey: String = "engine-qwen-3-5-4b-mnn"

    private const val DIR_NAME = "qwen-3-5-4b-mnn"
    private const val SENTINEL_FILENAME = ".sentinel"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$DIR_NAME").also { it.parentFile?.mkdirs() }

    override fun isDirectoryMode(): Boolean = true

    /** The path passed to `Llm::createLLM` — the model directory's `config.json`. */
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
        val dirGone = file(ctx).let { if (!it.exists()) true else it.deleteRecursively() }
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        val tmpGone = File(file(ctx).parentFile, "${file(ctx).name}.tmp").let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        return dirGone && partialGone && tmpGone
    }

    private const val TAG = "Qwen35Mnn4bModel"
}
