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
 * per-script recognizer (`meiki-ja`, `paddle-rec-unified`, …). One instance per pack
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

    /** True iff this pack ships inside the APK (materialized on first use, see
     *  [ensureBundledMaterialized]) — it never downloads and has no reclaimable
     *  on-disk footprint, so the UI presents it as built-in (no size, no delete),
     *  like the ML Kit floor. */
    val isBundled: Boolean get() = catalogKey in BUNDLED_OCR_PACKS

    override fun isInstalled(ctx: Context): Boolean {
        // A bundled pack ships in the APK — always available; its files are
        // materialized to the models dir on first engine use ([ensureBundledMaterialized]),
        // so report installed even before that copy lands.
        if (catalogKey in BUNDLED_OCR_PACKS) return true
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

    /** If this is a bundled OCR pack, copy its files from the APK assets into the
     *  models dir when absent or stale — version-checked against the catalog's
     *  aggregate SHA, exactly like [isInstalled]'s sentinel. No-op for downloaded
     *  packs and for an up-to-date bundled copy. Called lazily on first engine use
     *  ([com.playtranslate.ocr.paddle.PaddleOcrBridge]); the copy lands once per
     *  (re)bundle, off the main thread, and only for users who actually use the
     *  bundled recognizer (the APK carries it either way). */
    fun ensureBundledMaterialized(ctx: Context) {
        val assetDir = BUNDLED_OCR_PACKS[catalogKey] ?: return
        val files = catalogEntry(ctx)?.files ?: return
        val expected = MultiFileSha.aggregate(files)
        val dir = file(ctx)
        val sentinel = File(dir, ".sentinel")
        if (files.all { File(dir, it.path).exists() } &&
            runCatching { sentinel.readText().trim() }.getOrNull().equals(expected, ignoreCase = true)) {
            return // already materialized + current
        }
        // Stage the whole pack into a sibling dir, then swap it in as ONE unit, so a
        // partial/failed copy can never leave a mixed-version pack the loader treats
        // as valid: the live dir is only ever a COMPLETE promoted pack, the prior
        // complete pack (staging failed before the swap), or absent (→ ML Kit floor,
        // re-materialized next launch). Mirrors the downloader's stage-then-promote.
        val staging = File(dir.parentFile, "$catalogKey.tmp").apply { deleteRecursively() }
        runCatching {
            staging.mkdirs()
            for (f in files) {
                ctx.assets.open("$assetDir/${f.path}").use { input ->
                    File(staging, f.path).outputStream().use { input.copyTo(it) }
                }
            }
            File(staging, ".sentinel").writeText(expected)
            if (dir.exists() && !dir.deleteRecursively()) error("could not clear old pack dir for $catalogKey")
            if (!staging.renameTo(dir)) error("could not promote staged pack for $catalogKey")
            Log.i(TAG, "materialized bundled OCR pack '$catalogKey'")
        }.onFailure {
            staging.deleteRecursively()
            Log.w(TAG, "bundled OCR pack '$catalogKey' materialize failed — using ML Kit", it)
        }
    }

    /** True iff the catalog has a *deliverable* entry for this pack: present AND
     *  with real (non-placeholder) per-file sizes + 64-hex SHA-256. Gates whether
     *  the engine needing this pack is offered to the user — so a pack with a
     *  missing or placeholder catalog entry never surfaces a broken
     *  "not installed / can't download" option. */
    fun isShippable(ctx: Context): Boolean {
        if (catalogKey in BUNDLED_OCR_PACKS) return true // shipped in the APK
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

    private companion object {
        const val TAG = "OcrPackModelHelper"

        /** OCR packs shipped inside the APK (catalogKey -> asset subdir under
         *  `assets/`). A bundled pack is always installed/shippable; its files are
         *  copied into the models dir on first engine use ([ensureBundledMaterialized]).
         *  The catalog entry is retained as the version source (aggregate SHA) and a
         *  download fallback. */
        val BUNDLED_OCR_PACKS: Map<String, String> = mapOf(
            "paddle-rec-unified" to "ocr/paddle-rec-unified",
        )
    }
}
