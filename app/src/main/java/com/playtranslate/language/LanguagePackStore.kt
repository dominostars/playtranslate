package com.playtranslate.language

import android.content.Context
import com.playtranslate.BuildConfig
import java.io.File

/**
 * On-disk layout of language packs. Each pack lives at:
 *
 *     noBackupFilesDir/langpacks/<lang-code>/
 *         dict.sqlite    — the dictionary DB
 *         manifest.json  — per-pack schema/version metadata
 *
 * Phase 2's surface is read-only path resolution + bundled-pack bootstrap.
 * Phase 3 adds `install()`, `uninstall()`, `hasUpdate()`, and `installedPacks()`.
 *
 * Uses [Context.noBackupFilesDir] rather than [Context.filesDir] so the pack
 * data never ends up in Google Backup. The app currently sets
 * `android:allowBackup="false"` globally so the distinction is moot today, but
 * keeping pack data out of backup paths is the correct long-term layout.
 */
object LanguagePackStore {

    fun rootDir(ctx: Context): File =
        File(ctx.applicationContext.noBackupFilesDir, "langpacks")

    fun dirFor(ctx: Context, id: SourceLangId): File =
        File(rootDir(ctx), id.code)

    fun dictDbFor(ctx: Context, id: SourceLangId): File =
        File(dirFor(ctx, id), "dict.sqlite")

    fun manifestFileFor(ctx: Context, id: SourceLangId): File =
        File(dirFor(ctx, id), "manifest.json")

    /** A pack is "installed" when both its DB and its manifest are present. */
    fun isInstalled(ctx: Context, id: SourceLangId): Boolean =
        dictDbFor(ctx, id).exists() && manifestFileFor(ctx, id).exists()

    /**
     * Writes the manifest for a bundled pack if it isn't already present.
     * Idempotent — subsequent boots no-op. Called from [com.playtranslate.dictionary.DictionaryManager.ensureOpen]
     * after the DB is known to be open and valid.
     */
    fun writeManifestIfMissing(ctx: Context, id: SourceLangId, entry: CatalogEntry) {
        val file = manifestFileFor(ctx, id)
        if (file.exists()) return
        val dbFile = dictDbFor(ctx, id)
        val actualSize = if (dbFile.exists()) dbFile.length() else entry.size
        val manifest = LanguagePackManifest(
            langId = id.code,
            schemaVersion = 1,
            packVersion = entry.packVersion,
            appMinVersion = BuildConfig.VERSION_CODE,
            files = listOf(ManifestFile(path = "dict.sqlite", size = actualSize, sha256 = null)),
            totalSize = actualSize,
            licenses = entry.licenses.orEmpty(),
        )
        LanguagePackManifestIO.write(file, manifest)
    }
}
