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
 * **Hy-MT2 1.8B** model — the successor to [HyMtModel] (Hunyuan-MT 1.5).
 * Same install mechanics, same directory mode: MNN's `Llm::createLLM` expects
 * a `config.json` inside the model dir alongside `llm.mnn`, `llm.mnn.weight`
 * and the tokenizer, so the helper points at the *extracted root directory*
 * rather than a single file.
 *
 * Catalog entry: `engine-hy-mt2-1-8b-mnn`. Distribution uses the **MultiFile**
 * commit strategy in [com.playtranslate.translation.llm.OnDeviceLlmDownloader],
 * the same as 1.5: the catalog lists individual files (each with its own
 * url + size + sha256), the downloader fetches each in sequence, verifies,
 * atomic-renames inside `<finalDir>.tmp/`, then writes an aggregate-SHA
 * sentinel and safe-swaps.
 *
 * **License**: Apache-2.0 (`LICENSE.txt` in `tencent/Hy-MT2-1.8B` is the plain
 * Apache text — no Territory clause, unlike the Tencent HY Community License
 * that governs 1.5). So this tier carries **no region gate and no legal
 * attestation**: [com.playtranslate.region.RegionPolicy] is not consulted
 * anywhere on this path, and the model is offered in the EU/UK/KR like any
 * other Apache tier.
 *
 * **Tokenizer file**: `tokenizer.mtok` (the newer MNN tokenizer container),
 * not 1.5's legacy `tokenizer.txt`. The name is carried in the bundle's own
 * `config.json` (`tokenizer_file`), so nothing here hard-codes it.
 *
 * Installation gate: after a successful MultiFile install the downloader
 * writes `.sentinel` inside the final directory containing the aggregate SHA
 * computed from the catalog's `files` array via [MultiFileSha.aggregate].
 * [isInstalled] re-derives the expected aggregate from the *current* catalog
 * and compares — any catalog edit (per-file sha change, file added/removed,
 * path change) flips the aggregate and triggers a re-download automatically.
 * That is also the swap path if we ever re-host the bundle ourselves.
 */
object HyMt2Model : ModelHelper {
    override val catalogKey: String = "engine-hy-mt2-1-8b-mnn"

    /** Directory name under `noBackupFilesDir/models/`. Distinct from 1.5's
     *  `hunyuan-mt1-5-1-8b-mnn`, so a user who keeps the retired 1.5 model
     *  keeps a working install of it side by side with this one. */
    private const val DIR_NAME = "hy-mt2-1-8b-mnn"

    /** Sentinel written by the downloader after a successful install + swap;
     *  contents = the aggregate SHA-256 over the catalog's `files` list.
     *  Absence (or mismatch) means an in-progress or partial install —
     *  [isInstalled] returns false. */
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
        // expected sentinel value comes from the aggregate-SHA util. The
        // legacy single-file/single-zip fallback is kept so this function
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

    override fun humanSize(ctx: Context): String = humanSize(ctx, expectedSize(ctx))

    override fun delete(ctx: Context): Boolean {
        val dirGone = file(ctx).let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        // Tmp staging directory (mid-install kill artifact) also wiped here so
        // a successful delete clears all related on-disk state.
        val tmpGone = File(file(ctx).parentFile, "${file(ctx).name}.tmp").let {
            if (!it.exists()) true else it.deleteRecursively()
        }
        return dirGone && partialGone && tmpGone
    }

    private const val TAG = "HyMt2Model"
}
