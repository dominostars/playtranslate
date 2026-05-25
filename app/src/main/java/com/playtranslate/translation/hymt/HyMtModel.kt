package com.playtranslate.translation.hymt

import android.content.Context
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.MultiFileSha
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for the MNN-format Tencent
 * Hunyuan-MT 1.5 1.8B model. Mirrors
 * [com.playtranslate.translation.qwen.QwenMnnModel] in directory mode — MNN's
 * `Llm::createLLM` expects a `config.json` inside the model dir alongside
 * `llm.mnn`, `llm.mnn.weight`, and `tokenizer.txt`, so the helper points at
 * the *extracted root directory* rather than a single file.
 *
 * Catalog entry: `engine-hunyuan-mt1-5-1-8b-mnn` in
 * `app/src/main/assets/langpack_catalog.json`. Distribution uses the
 * **MultiFile** commit strategy in
 * [com.playtranslate.translation.llm.OnDeviceLlmDownloader]: the catalog
 * lists individual files (each with its own url + size + sha256) and the
 * downloader fetches each in sequence, verifies, atomic-renames inside
 * `<finalDir>.tmp/`, then writes an aggregate-SHA sentinel and safe-swaps.
 * We consume wangjazz's HuggingFace files directly, never re-hosting a
 * combined zip — that preserves the "user agent" liability posture under
 * the Tencent HY Community License.
 *
 * **License**: Tencent HY Community License — excludes EU/UK/SK from the
 * "Territory" definition. Gating happens in [com.playtranslate.region.RegionPolicy]
 * (catalog row hidden) and [com.playtranslate.ui.SettingsBottomSheet] (legal
 * attestation click-through before download). The model file itself doesn't
 * change behavior based on region; the gates are pure UX layers.
 *
 * Installation gate: after a successful MultiFile install, the downloader
 * writes `.sentinel` inside the final directory containing the aggregate
 * SHA computed from the catalog's `files` array via [MultiFileSha.aggregate].
 * [isInstalled] re-derives the expected aggregate from the *current*
 * catalog and compares against the sentinel — any catalog edit (per-file
 * sha change, file added/removed, path change) flips the aggregate and
 * triggers a re-download automatically.
 */
object HyMtModel : ModelHelper {
    override val catalogKey: String = "engine-hunyuan-mt1-5-1-8b-mnn"

    /** Directory name under `noBackupFilesDir/models/` where the extracted
     *  model lives. The zip's root entries land directly under here. */
    private const val DIR_NAME = "hunyuan-mt1-5-1-8b-mnn"

    /** Sentinel file written by the downloader after a successful install +
     *  swap. For MultiFile entries (the current shipping shape), contents =
     *  aggregate SHA-256 over the catalog's `files` list via
     *  [MultiFileSha.aggregate]. For legacy single-file/single-zip entries
     *  (none today, but kept for backward compat), contents = the catalog's
     *  top-level `sha256`. Absence (or mismatch) means an in-progress or
     *  partial install — [isInstalled] returns false. */
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
        // MultiFile entries have a null top-level sha256 by design — the
        // expected sentinel value comes from the aggregate-SHA util.
        // Legacy single-file/single-zip entries fall through to the
        // top-level sha256. Both branches must coexist so this function
        // never short-circuits on a legitimately null sha256.
        val expected = entry.files?.let { MultiFileSha.aggregate(it) }
            ?: entry.sha256
            ?: return false
        val dir = file(ctx)
        if (!dir.exists() || !dir.isDirectory) return false
        val sentinel = File(dir, SENTINEL_FILENAME)
        if (!sentinel.exists()) return false
        return try {
            sentinel.readText().trim().equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read sentinel at ${sentinel.absolutePath}: ${e.message}")
            false
        }
    }

    /**
     * Total expected bytes the user will need to download. For MultiFile
     * entries the catalog's top-level `size` is purely cosmetic (a
     * sum-of-files for human-readable JSON); we compute the authoritative
     * value on read so the storage preflight and "size" label can't drift
     * if the editor forgets to keep the top-level field in sync.
     */
    override fun expectedSize(ctx: Context): Long {
        val entry = catalogEntry(ctx) ?: return 0L
        return entry.files?.sumOf { it.size } ?: entry.size
    }

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

    private const val TAG = "HyMtModel"
}
