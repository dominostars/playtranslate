package com.playtranslate.language

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.playtranslate.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-disk layout of language packs. Each pack lives at:
 *
 *     noBackupFilesDir/langpacks/<lang-code>/
 *         dict.sqlite    — the dictionary DB
 *         manifest.json  — per-pack schema/version metadata
 *
 * Provides path resolution plus [install] / [installTarget] / [uninstall].
 * There is intentionally NO `hasUpdate()` or background pack refresh —
 * packs only change when the user installs an app update that ships a new
 * catalog entry with a different URL/sha256 for an already-installed pack,
 * and that case is handled implicitly because the new app build's
 * [LanguagePackCatalog] carries the new metadata. See memory file
 * `project_pack_update_policy.md` for the full rationale.
 *
 * Uses [Context.noBackupFilesDir] rather than [Context.filesDir] so the pack
 * data never ends up in Google Backup. The app currently sets
 * `android:allowBackup="false"` globally so the distinction is moot today, but
 * keeping pack data out of backup paths is the correct long-term layout.
 */
object LanguagePackStore {

    fun rootDir(ctx: Context): File =
        File(ctx.applicationContext.noBackupFilesDir, "langpacks")

    /** Directory for a source pack. Variants sharing a pack (e.g. ZH_HANT)
     *  resolve to the same directory via [SourceLangId.packId]. */
    fun dirFor(ctx: Context, id: SourceLangId): File =
        File(rootDir(ctx), id.packId.code)

    fun dictDbFor(ctx: Context, id: SourceLangId): File =
        File(dirFor(ctx, id), "dict.sqlite")

    fun manifestFileFor(ctx: Context, id: SourceLangId): File =
        File(dirFor(ctx, id), "manifest.json")

    /** A pack is "installed" when both its DB and its manifest are present
     *  in the pack directory. For JA we additionally schema-validate the DB
     *  and proactively delete any directory whose DB fails the check, so
     *  stale pre-`headword`-rename packs can't sit on disk poisoning runtime
     *  queries — forcing a redownload is cheaper than writing a migration.
     *
     *  Any legacy pre-LanguagePackStore copy at `databases/jmdict.db` is
     *  eagerly deleted on sight: tokenizer binaries are no longer bundled
     *  in the APK, so that file alone can't power the JA engine regardless
     *  of its schema. Everyone routes through `install()` for a fresh
     *  pack with both the DB and the `tokenizer/` payload.
     */
    fun isInstalled(ctx: Context, id: SourceLangId): Boolean {
        if (id == SourceLangId.JA) {
            val legacy = ctx.getDatabasePath("jmdict.db")
            if (legacy.exists() && legacy.delete()) {
                Log.d(TAG, "Purged orphaned legacy JMdict DB at ${legacy.path}")
            }
        }
        val primaryDb = dictDbFor(ctx, id)
        if (primaryDb.exists() && manifestFileFor(ctx, id).exists()) {
            if (id == SourceLangId.JA && !isJmdictSchemaCurrent(primaryDb)) {
                val dir = dirFor(ctx, id)
                if (dir.deleteRecursively()) {
                    Log.d(TAG, "Deleted stale-schema JA pack at ${dir.path}")
                }
                return false
            }
            return true
        }
        return false
    }

    /** Returns false if the on-device DB is missing tables/columns the
     *  current runtime queries. Delegates to [JmdictSchemaProbe] so this
     *  and [com.playtranslate.dictionary.DictionaryManager.isSchemaUpToDate]
     *  share one definition. */
    internal fun isJmdictSchemaCurrent(dbFile: File): Boolean =
        com.playtranslate.dictionary.JmdictSchemaProbe.isCurrent(dbFile)

    // ── Target gloss packs ──────────────────────────────────────────────

    fun targetDirFor(ctx: Context, targetLang: String): File =
        File(rootDir(ctx), "target-$targetLang")

    fun targetIndexFstFor(ctx: Context, targetLang: String): File =
        File(targetDirFor(ctx, targetLang), "index.fst")

    /** Per-target on-disk manifest. The zip's manifest.json lands here as
     *  part of the existing extract+swap flow — `staleInstalledPacks` reads
     *  this file directly to compare on-disk packVersion against catalog. */
    fun targetManifestFileFor(ctx: Context, targetLang: String): File =
        File(targetDirFor(ctx, targetLang), "manifest.json")

    /** English target needs no pack — definitions are already in every source pack.
     *  All three FST payload files must be present; a partial directory left
     *  by an interrupted install or a manual file delete would otherwise look
     *  installed to the picker / installer while [FstTargetGlossDatabase.open]
     *  refuses to load it, soft-locking the user on English fallback with no
     *  reinstall affordance.
     *
     *  Also requires `manifest.json` so [staleInstalledPacks] can compare its
     *  on-disk packVersion to the catalog's. Older builds didn't ship a
     *  target manifest; the target-pack build scripts have been writing one
     *  since well before this change shipped, but the require lets us treat
     *  manifest absence as "needs re-install" cleanly. */
    fun isTargetInstalled(ctx: Context, targetLang: String): Boolean {
        if (targetLang == "en") return true
        val dir = targetDirFor(ctx, targetLang)
        return File(dir, "index.fst").exists() &&
            File(dir, "data.bin").exists() &&
            File(dir, "strings.bin").exists() &&
            File(dir, "manifest.json").exists()
    }

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
            ensureActive()

            // 2. Whole-file SHA-256 verify against catalog's advertised hash
            onProgress(DownloadProgress.Verifying)
            val actualSha = PackIntegrity.sha256Hex(zipFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return@withContext InstallResult.Failed(
                    "SHA-256 mismatch for ${id.code}: expected=$expectedSha actual=$actualSha"
                )
            }

            // 3. Extract to the temp dir (atomic swap happens in step 5)
            ensureActive()
            onProgress(DownloadProgress.Extracting)
            PackIntegrity.extractZip(zipFile, tmpDir)
            ensureActive()

            // 4. Manifest check — pack must contain a manifest.json naming
            //    every file inside the pack, with matching sizes. Per-file
            //    sha256 is optional; when provided, it's enforced.
            validateManifest(tmpDir)?.let {
                return@withContext InstallResult.Failed(it)
            }

            // 5. Rollback-safe swap: old pack is backed up, new pack promoted,
            //    backup deleted only after the new pack is confirmed in place.
            safeSwap(tmpDir, finalDir)

            Log.d(TAG, "Installed pack ${id.code} from $url (${zipFile.length()} bytes)")
            InstallResult.Success
        } catch (_: CancellationException) {
            InstallResult.Cancelled
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: "Unknown install error", e)
        } finally {
            if (zipFile.exists()) zipFile.delete()
            if (tmpDir.exists()) tmpDir.deleteRecursively()
        }
    }

    /**
     * Downloads, verifies, extracts, and installs a target gloss pack.
     * Follows the same download→SHA-256→extract→swap pattern as [install].
     */
    suspend fun installTarget(
        ctx: Context,
        targetLang: String,
        onProgress: (DownloadProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val catalogKey = "target-$targetLang"
        val entry = LanguagePackCatalogLoader.entryForKey(app, catalogKey)
            ?: return@withContext InstallResult.Failed("No catalog entry for $catalogKey")
        if (entry.bundled) {
            return@withContext InstallResult.Failed("$catalogKey is a bundled pack; cannot download")
        }
        val url = entry.url
            ?: return@withContext InstallResult.Failed("Catalog entry for $catalogKey has no url")
        val expectedSha = entry.sha256
            ?: return@withContext InstallResult.Failed("Catalog entry for $catalogKey has no sha256")

        val root = rootDir(app).apply { mkdirs() }
        val zipFile = File(root, "$catalogKey.downloading.zip")
        val tmpDir = File(root, "$catalogKey.tmp")
        val finalDir = targetDirFor(app, targetLang)

        if (zipFile.exists()) zipFile.delete()
        if (tmpDir.exists()) tmpDir.deleteRecursively()

        try {
            // 1. Stream the zip
            LanguagePackDownloader().download(url, zipFile) { onProgress(it) }
            ensureActive()

            // 2. SHA-256 verify
            onProgress(DownloadProgress.Verifying)
            val actualSha = PackIntegrity.sha256Hex(zipFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return@withContext InstallResult.Failed(
                    "SHA-256 mismatch for $catalogKey: expected=$expectedSha actual=$actualSha"
                )
            }

            // 3. Extract
            ensureActive()
            onProgress(DownloadProgress.Extracting)
            PackIntegrity.extractZip(zipFile, tmpDir)
            ensureActive()

            // 4. Manifest check
            validateManifest(tmpDir)?.let {
                return@withContext InstallResult.Failed(it)
            }

            // 5. Rollback-safe swap
            safeSwap(tmpDir, finalDir)

            Log.d(TAG, "Installed target pack $catalogKey from $url (${zipFile.length()} bytes)")
            InstallResult.Success
        } catch (_: CancellationException) {
            InstallResult.Cancelled
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
     *
     * Also evicts every engine whose variant shares this pack (via
     * [SourceLangId.packId]) from [SourceLanguageEngines]'s process-scoped
     * cache. Without this, a warm engine would keep serving tokenizer + dict
     * state from the now-deleted pack directory until the process restarts —
     * the `isInstalled()` gate would correctly say "missing," but already-
     * resolved engine references would still tokenize against stale data.
     * Sibling variants (e.g. ZH_HANT shares ZH's pack) must be evicted too,
     * or uninstalling one would leave the other serving deleted files.
     * Releasing also closes each engine's dict DB handle.
     */
    fun uninstall(ctx: Context, id: SourceLangId): Boolean {
        SourceLanguageEngines.releaseForPack(id.packId)
        val dir = dirFor(ctx.applicationContext, id)
        return if (dir.exists()) dir.deleteRecursively() else false
        // OCR recognizers cached in OcrManager are NOT released here:
        // recognise() reads from the cache and then calls client.process()
        // without holding a lock against close(), so closing on uninstall
        // can race with an in-flight capture (CaptureService may still be
        // running when settings invokes uninstall). Recognizers are freed
        // instead from PlayTranslateApplication.onTrimMemory, which only
        // fires when no foreground service is alive.
    }

    /**
     * Removes an installed target gloss pack's directory. Returns true if the
     * pack was present and is now gone; no-op (returns false) otherwise.
     * English is never "installed" so calling with "en" is a no-op.
     *
     * Also evicts the cached [FstTargetGlossDatabase] handle so future lookups
     * reopen from disk — otherwise warm callers would keep querying a handle
     * pointing at a now-deleted file. The ML Kit translation model is a
     * separate on-device asset owned by Google Play Services and is not
     * touched here.
     */
    fun uninstallTarget(ctx: Context, targetLang: String): Boolean {
        if (targetLang == "en") return false
        TargetGlossDatabaseProvider.release(targetLang)
        val dir = targetDirFor(ctx.applicationContext, targetLang)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    /**
     * Validate a manifest inside an extracted pack directory. Returns null on
     * success, or an error message describing the first failure.
     */
    private suspend fun validateManifest(tmpDir: File): String? {
        val manifestFile = File(tmpDir, "manifest.json")
        val manifest = LanguagePackManifestIO.read(manifestFile)
            ?: return "Extracted pack has no manifest.json"
        if (manifest.schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return "Pack schema v${manifest.schemaVersion} not supported (max v$SUPPORTED_SCHEMA_VERSION)"
        }
        if (manifest.appMinVersion > com.playtranslate.BuildConfig.VERSION_CODE) {
            return "Pack requires app version ${manifest.appMinVersion}, current is ${com.playtranslate.BuildConfig.VERSION_CODE}"
        }
        for (f in manifest.files) {
            val inDir = File(tmpDir, f.path)
            if (!inDir.exists()) return "Manifest file missing in pack: ${f.path}"
            if (inDir.length() != f.size) {
                return "Manifest size mismatch for ${f.path}: expected=${f.size} actual=${inDir.length()}"
            }
            val fileSha = f.sha256
            if (fileSha != null) {
                val actual = PackIntegrity.sha256Hex(inDir)
                if (!actual.equals(fileSha, ignoreCase = true)) {
                    return "Per-file SHA-256 mismatch on ${f.path}"
                }
            }
        }
        return null
    }

    /**
     * Rollback-safe directory swap: moves [tmpDir] into [finalDir] without
     * deleting the old pack until the new one is confirmed in place.
     * If the swap fails, the old pack is restored so the user never loses
     * a working install.
     */
    internal fun safeSwap(tmpDir: File, finalDir: File) {
        val backupDir = File(finalDir.parentFile, finalDir.name + ".old")
        if (backupDir.exists()) backupDir.deleteRecursively()

        // Step 1: move old pack to backup (if it exists)
        if (finalDir.exists()) {
            val backedUp = try { finalDir.renameTo(backupDir) } catch (_: Exception) { false }
            if (!backedUp) {
                copyDirectory(finalDir, backupDir)
                finalDir.deleteRecursively()
            }
        }

        // Step 2: promote new pack
        val promoted = try { tmpDir.renameTo(finalDir) } catch (_: Exception) { false }
        if (!promoted) {
            try {
                copyDirectory(tmpDir, finalDir)
                tmpDir.deleteRecursively()
            } catch (e: Exception) {
                // Promotion failed — restore from backup
                if (backupDir.exists() && !finalDir.exists()) {
                    try { backupDir.renameTo(finalDir) } catch (_: Exception) {
                        copyDirectory(backupDir, finalDir)
                    }
                }
                throw e
            }
        }

        // Step 3: delete backup (new pack is confirmed)
        if (backupDir.exists()) backupDir.deleteRecursively()
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

    // ── Stale-pack scan (launch-time upgrade detection) ─────────────────

    /**
     * Returns the list of currently-installed packs whose on-disk
     * manifest's packVersion is older than the catalog's. Run synchronously
     * at app launch (it's just a few file reads). Caller decides whether
     * to prompt the user for a re-download.
     *
     * **Filters out**:
     * - Bundled packs (catalog manages those via APK assets, not download).
     * - Engine packs (`type = "engine"`) — those are managed by
     *   `LlamaTranslator`/`OnDeviceLlmBackend`, not LanguagePackStore.
     * - Packs not currently installed (per spec, only previously-installed
     *   packs prompt the upgrade alert; first-time install goes through
     *   the onboarding flow instead).
     *
     * **Reads manifests directly via [LanguagePackManifestIO] rather than
     * routing through [isInstalled] / [isTargetInstalled]**, because those
     * auto-delete on schema mismatch and would race with this scan. The
     * upgrade orchestrator owns the controlled-deletion path.
     *
     * Source corruption backstop: if a source pack's on-disk DB fails the
     * shared schema probe ([com.playtranslate.dictionary.JmdictSchemaProbe]),
     * it's marked stale even when packVersion would otherwise match —
     * forcing a re-download for any structurally-broken pack.
     *
     * For source packs the returned `sourceLangId` is **always the packId
     * variant** (e.g. always JA, never a sibling variant if any are
     * introduced) so callers can pass it straight to `releaseForPack`,
     * `dirFor`, etc., where collapsing to the canonical pack matters.
     */
    fun staleInstalledPacks(ctx: Context): List<StalePack> {
        val app = ctx.applicationContext
        val catalog = LanguagePackCatalogLoader.load(app)
        val out = mutableListOf<StalePack>()

        for ((key, entry) in catalog.packs) {
            if (entry.bundled) continue

            // Pack-type guard: accept null/"source"/"target", skip engine
            // and any other unknown type. Required, not just defensive —
            // engine packs (engine-translategemma, engine-qwen-1-5b) live
            // outside LanguagePackStore and would crash SourceLangId.fromCode.
            val type = entry.type
            if (type != null && type != "source" && type != "target") continue

            val isTarget = key.startsWith("target-") || type == "target"

            val manifestFile: File
            val sourceLangId: SourceLangId?
            val targetLangCode: String?
            if (isTarget) {
                targetLangCode = key.removePrefix("target-")
                manifestFile = targetManifestFileFor(app, targetLangCode)
                sourceLangId = null
            } else {
                // Source: map catalog key to SourceLangId.packId so ZH and
                // ZH_HANT (if it ever appears as a separate catalog key)
                // collapse to the canonical pack.
                val sid = SourceLangId.fromCode(key)?.packId ?: continue
                manifestFile = manifestFileFor(app, sid)
                sourceLangId = sid
                targetLangCode = null
            }

            // Skip never-installed packs.
            if (!manifestFile.exists()) continue

            val manifest = LanguagePackManifestIO.read(manifestFile) ?: continue

            val versionStale = manifest.packVersion < entry.packVersion
            val schemaStale = sourceLangId == SourceLangId.JA &&
                !com.playtranslate.dictionary.JmdictSchemaProbe.isCurrent(
                    dictDbFor(app, sourceLangId)
                )

            if (versionStale || schemaStale) {
                // Classify FORCE vs ADDITIVE. Schema-broken always FORCE
                // (corruption/structural-break needs clean reinstall). Otherwise
                // the catalog's additiveFromVersion gates eligibility: if the
                // on-disk packVersion is at-or-above that boundary, the pack
                // can be upgraded without pre-uninstall (safeSwap preserves
                // the old pack until the new one is verified). Below the
                // boundary — or if the catalog declares no additive baseline —
                // the existing pre-uninstall + install flow runs.
                val mode = when {
                    schemaStale -> UpgradeMode.FORCE
                    entry.additiveFromVersion == null -> UpgradeMode.FORCE
                    manifest.packVersion >= entry.additiveFromVersion -> UpgradeMode.ADDITIVE
                    else -> UpgradeMode.FORCE
                }

                out += StalePack(
                    catalogKey = key,
                    displayName = entry.display,
                    kind = if (isTarget) PackKind.TARGET else PackKind.SOURCE,
                    targetLangCode = targetLangCode,
                    sourceLangId = sourceLangId,
                    upgradeMode = mode,
                )
            }
        }

        return out
    }

    private const val TAG = "LanguagePackStore"
    private const val SUPPORTED_SCHEMA_VERSION = 1
}

/** Whether a [StalePack] refers to a source-language pack or a target gloss
 *  pack. The two install/uninstall paths are different
 *  ([LanguagePackStore.install] vs [LanguagePackStore.installTarget]). */
enum class PackKind { SOURCE, TARGET }

/** Pack-upgrade strategy decided at staleness-scan time.
 *
 *  - [FORCE]: existing pre-uninstall + install flow. Used when the on-disk
 *    pack is below the catalog's `additiveFromVersion` boundary, when the
 *    catalog declares no additive baseline, or when the schema probe finds
 *    structural corruption.
 *  - [ADDITIVE]: install runs WITHOUT a pre-uninstall. The existing pack
 *    stays usable on disk; `LanguagePackStore.install`'s `safeSwap` backs
 *    up the old dir before promoting the new one and restores on failure.
 *    Mid-download cancellation or network failure leaves the user with a
 *    working pack instead of nothing. */
enum class UpgradeMode { FORCE, ADDITIVE }

/** One pack identified by [LanguagePackStore.staleInstalledPacks] as needing
 *  a re-download. Carries enough context for the upgrade orchestrator to
 *  drive its install/uninstall calls without re-querying the catalog.
 *
 *  [sourceLangId] is always the **packId variant** (e.g. always JA) so
 *  `releaseForPack` and `dirFor` see the canonical pack, not a sibling.
 *
 *  [upgradeMode] determines whether the orchestrator pre-uninstalls before
 *  the new install. */
data class StalePack(
    val catalogKey: String,
    val displayName: String,
    val kind: PackKind,
    val upgradeMode: UpgradeMode,
    val targetLangCode: String? = null,
    val sourceLangId: SourceLangId? = null,
)
