package com.playtranslate.language

import android.content.Context
import android.util.Log
import com.playtranslate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Downloads, verifies, extracts, and atomically swaps the pack for [id]
     * from its catalog URL. Reports progress via [onProgress]. Returns
     * [InstallResult.Success] on completion or [InstallResult.Failed] with a
     * human-readable reason on any error.
     *
     * Safe to cancel mid-flight: the calling coroutine's cancellation
     * propagates through the OkHttp call and file IO; the `finally` block
     * scrubs the partial download and temp-dir residue so a retry starts
     * from a clean state. The existing installed pack (if any) is not
     * touched until the final atomic swap — a cancelled install leaves the
     * previous version intact.
     */
    suspend fun install(
        ctx: Context,
        id: SourceLangId,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val entry = LanguagePackCatalogLoader.entryFor(app, id)
            ?: return@withContext InstallResult.Failed("No catalog entry for ${id.code}")
        if (entry.bundled) {
            return@withContext InstallResult.Failed("${id.code} is a bundled pack; cannot download")
        }
        val url = entry.url
            ?: return@withContext InstallResult.Failed("Catalog entry for ${id.code} has no url")
        val expectedSha = entry.sha256
            ?: return@withContext InstallResult.Failed("Catalog entry for ${id.code} has no sha256")

        val root = rootDir(app).apply { mkdirs() }
        val zipFile = File(root, "${id.code}.downloading.zip")
        val tmpDir = File(root, "${id.code}.tmp")
        val finalDir = dirFor(app, id)

        // Clean any leftovers from a previous failed attempt.
        if (zipFile.exists()) zipFile.delete()
        if (tmpDir.exists()) tmpDir.deleteRecursively()

        try {
            // 1. Stream the zip
            LanguagePackDownloader().download(url, zipFile) { onProgress(it) }

            // 2. Whole-file SHA-256 verify against catalog's advertised hash
            onProgress(DownloadProgress.Verifying)
            val actualSha = PackIntegrity.sha256Hex(zipFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return@withContext InstallResult.Failed(
                    "SHA-256 mismatch for ${id.code}: expected=$expectedSha actual=$actualSha"
                )
            }

            // 3. Extract to the temp dir (atomic swap happens in step 5)
            onProgress(DownloadProgress.Extracting)
            PackIntegrity.extractZip(zipFile, tmpDir)

            // 4. Manifest check — pack must contain a manifest.json naming
            //    every file inside the pack, with matching sizes. Per-file
            //    sha256 is optional; when provided, it's enforced.
            val tmpManifestFile = File(tmpDir, "manifest.json")
            val tmpManifest = LanguagePackManifestIO.read(tmpManifestFile)
                ?: return@withContext InstallResult.Failed("Extracted pack has no manifest.json")
            for (f in tmpManifest.files) {
                val inDir = File(tmpDir, f.path)
                if (!inDir.exists()) {
                    return@withContext InstallResult.Failed("Manifest file missing in pack: ${f.path}")
                }
                if (inDir.length() != f.size) {
                    return@withContext InstallResult.Failed(
                        "Manifest size mismatch for ${f.path}: expected=${f.size} actual=${inDir.length()}"
                    )
                }
                val fileSha = f.sha256
                if (fileSha != null) {
                    val actual = PackIntegrity.sha256Hex(inDir)
                    if (!actual.equals(fileSha, ignoreCase = true)) {
                        return@withContext InstallResult.Failed("Per-file SHA-256 mismatch on ${f.path}")
                    }
                }
            }

            // 5. Atomic-ish swap. Not POSIX-atomic (delete + rename is two
            //    operations), but safe under interruption: the partial state
            //    either keeps the old pack or surfaces an absent pack on
            //    next boot, which LanguagePackStore.isInstalled treats as
            //    uninstalled. Next install retry works cleanly.
            if (finalDir.exists()) finalDir.deleteRecursively()
            val renamed = try { tmpDir.renameTo(finalDir) } catch (_: Exception) { false }
            if (!renamed) {
                // Cross-mount or permission fallback: copy then delete.
                copyDirectory(tmpDir, finalDir)
                tmpDir.deleteRecursively()
            }

            Log.d(TAG, "Installed pack ${id.code} from $url (${zipFile.length()} bytes)")
            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: "Unknown install error", e)
        } finally {
            if (zipFile.exists()) zipFile.delete()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }

    /**
     * Removes an installed pack's directory. Returns true if the pack was
     * present and is now gone. No-op (returns false) if the pack wasn't
     * installed. Safe to call on a bundled pack — the directory gets
     * repopulated on next [com.playtranslate.dictionary.DictionaryManager.ensureOpen]
     * via the asset copy + manifest bootstrap path.
     */
    fun uninstall(ctx: Context, id: SourceLangId): Boolean {
        val dir = dirFor(ctx.applicationContext, id)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    private fun copyDirectory(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { child ->
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectory(child, target)
            } else {
                child.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private const val TAG = "LanguagePackStore"
}
