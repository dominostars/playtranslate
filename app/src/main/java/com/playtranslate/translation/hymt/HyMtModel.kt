package com.playtranslate.translation.hymt

import android.content.Context
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.humanSize
import java.io.File

object HyMtModel : ModelHelper {
    override val catalogKey: String = "engine-hymt-1-5b"
    private const val FILENAME = "Hy-MT1.5-1.8B-2bit.gguf"

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
