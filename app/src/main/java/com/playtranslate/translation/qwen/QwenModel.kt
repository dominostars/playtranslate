package com.playtranslate.translation.qwen

import android.content.Context
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for the Qwen 2.5 1.5B Instruct model.
 *
 * The model URL, expected size, SHA-256, and license metadata all live in
 * `app/src/main/assets/langpack_catalog.json` under the `engine-qwen-1-5b` key.
 * This file does NOT hardcode any of those — they're read fresh from the catalog
 * so app updates can change the mirror URL or hashes without code edits.
 *
 * Mirrors the [com.playtranslate.translation.translategemma.TranslateGemmaModel]
 * shape; both implement [ModelHelper] so they share the [com.playtranslate.translation.llm.OnDeviceLlmDownloader]
 * pipeline.
 */
object QwenModel : ModelHelper {
    override val catalogKey: String = "engine-qwen-1-5b"
    private const val FILENAME = "qwen2.5-1.5b-instruct-q4_0.gguf"

    override fun catalogEntry(ctx: Context): CatalogEntry? =
        LanguagePackCatalogLoader.entryForKey(ctx, catalogKey)

    override fun file(ctx: Context): File =
        File(ctx.noBackupFilesDir, "models/$FILENAME").also { it.parentFile?.mkdirs() }

    override fun isInstalled(ctx: Context): Boolean {
        val entry = catalogEntry(ctx) ?: return false
        val f = file(ctx)
        return f.exists() && f.length() == entry.size
    }

    override fun expectedSize(ctx: Context): Long = catalogEntry(ctx)?.size ?: 0L

    override fun humanSize(ctx: Context): String = humanSize(expectedSize(ctx))

    override fun delete(ctx: Context): Boolean {
        val finalGone = file(ctx).let { if (!it.exists()) true else it.delete() }
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        return finalGone && partialGone
    }
}
