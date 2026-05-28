package com.playtranslate.translation.llm

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import com.playtranslate.language.CatalogEntry
import com.playtranslate.language.CatalogFile
import com.playtranslate.language.LanguagePackDownloader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PackIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Drives a manifest-backed download of an on-device LLM artifact.
 *
 * Two commit modes, picked per-entry from the catalog's [extract] flag (and
 * mirrored by [ModelHelper.isDirectoryMode] on the receiving side):
 *
 * **FileSwap** (legacy default — GGUFs):
 *  1. Pre-flight checks (RAM / free storage).
 *  2. Streamed download via [LanguagePackDownloader] into
 *     [ModelHelper.partialFile] (resumable across cancel-by-dismiss).
 *  3. Streaming SHA-256 over the partial.
 *  4. Atomic replace `<file>.partial` → `<file>` (`Files.move` with
 *     `ATOMIC_MOVE` + `REPLACE_EXISTING`). Only after this rename does
 *     [ModelHelper.isInstalled] return true.
 *
 * **ZipExtract** (engine zips — currently just the MNN Qwen):
 *  1. Same pre-flights.
 *  2. Same streamed download — the partial here is the *zip*.
 *  3. Same streaming SHA-256 over the zip.
 *  4. Extract into a sibling `<final>.tmp/` directory (path-traversal-safe
 *     via [PackIntegrity.extractZip]).
 *  5. Directory swap via [LanguagePackStore.safeSwap] (same rollback-safe
 *     pattern language packs use).
 *  6. Write `<final>/.sentinel` containing the catalog sha256, then delete
 *     the verified zip partial. The sentinel is what
 *     [ModelHelper.isInstalled] checks in directory mode (the file-mode
 *     "match catalog size" check doesn't apply when the catalog's size is
 *     the zip's compressed size, not the extracted footprint).
 *
 * Lifted from the previous TG-specific `TranslateGemmaDownloader`;
 * parameterized via [modelHelper] and [totalMemFloorBytes] so siblings
 * (Qwen GGUF, Qwen MNN, ...) reuse the same pipeline.
 */
class OnDeviceLlmDownloader(
    private val context: Context,
    private val modelHelper: ModelHelper,
    private val totalMemFloorBytes: Long,
    private val httpDownloader: LanguagePackDownloader = LanguagePackDownloader(),
) {

    sealed interface Progress {
        data class Downloading(val received: Long, val total: Long) : Progress
        data object Verifying : Progress

        /**
         * Emitted between [Verifying] and the final commit for directory-mode
         * (zip-extract) entries. [extractedBytes] is currently always 0 — the
         * underlying [PackIntegrity.extractZip] doesn't have a per-entry
         * callback and the extracted MNN model is small enough (~900 MB
         * uncompressed, single-digit seconds on Thor) that an indeterminate
         * spinner is fine. Plumb a real counter through if extract time grows
         * past the "user notices" threshold.
         */
        data class Extracting(val extractedBytes: Long, val totalUncompressed: Long?) : Progress
    }

    enum class CommitStrategy {
        /** Default for single-file GGUFs — `Files.move` atomic rename. */
        FileSwap,
        /** For engine entries that ship as a zip of multiple files — see kdoc above. */
        ZipExtract,
        /**
         * For engine entries hosted as individual files (catalog
         * [CatalogEntry.files] populated). Each file fetched, verified,
         * and atomic-renamed inside `<finalDir>.tmp/`; aggregate-SHA
         * sentinel written; safe-swap of `<finalDir>.tmp/` → `<finalDir>/`.
         * Used to consume the wangjazz Hunyuan-MT 1.5 bundle without
         * re-hosting (preserves "user agent" liability posture under the
         * Tencent HY Community License).
         */
        MultiFile,
    }

    sealed interface Outcome {
        data object Success : Outcome
        data class Refused(val reason: String) : Outcome
        data class Failed(val reason: String, val cause: Throwable? = null) : Outcome

        /**
         * Caller cancelled mid-flight. Partial file may still be on disk;
         * caller decides whether to keep it (for resume) or delete it.
         */
        data object Cancelled : Outcome
    }

    /**
     * Run the full download → verify → commit pipeline. Honors coroutine
     * cancellation; the underlying [LanguagePackDownloader] cancels the
     * OkHttp call when the surrounding coroutine is cancelled.
     */
    suspend fun run(
        onProgress: (Progress) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val entry = modelHelper.catalogEntry(context)
            ?: return@withContext Outcome.Failed(
                "Catalog entry missing for ${modelHelper.catalogKey}",
            )

        // MultiFile dispatch must run BEFORE the legacy url/sha256 null guards
        // below: MultiFile entries have those fields intentionally null
        // (replaced by [CatalogEntry.files]). Leaving the null guards in front
        // would short-circuit every MultiFile dispatch into "Catalog URL
        // missing" before the strategy could be selected (Codex review).
        val multiFiles = entry.files
        if (multiFiles != null && multiFiles.isNotEmpty()) {
            return@withContext runMultiFile(entry, multiFiles, onProgress)
        }

        val url = entry.url
            ?: return@withContext Outcome.Failed(
                "Catalog URL missing for ${modelHelper.catalogKey}",
            )
        val expectedSize = entry.size
        val expectedSha = entry.sha256
            ?: return@withContext Outcome.Failed(
                "Catalog SHA256 missing for ${modelHelper.catalogKey}",
            )

        // Strategy decision: trust the helper's isDirectoryMode() as the
        // source of truth. The catalog's `extract: true` should match, but
        // we don't gate on it — a helper override is enough to opt in.
        val strategy = if (modelHelper.isDirectoryMode()) {
            CommitStrategy.ZipExtract
        } else {
            CommitStrategy.FileSwap
        }

        // -- Pre-flights -----------------------------------------------------------
        preflightRam()?.let { return@withContext Outcome.Refused(it) }
        preflightStorage(expectedSize, strategy)?.let { return@withContext Outcome.Refused(it) }
        // Metered-network is a *warning* the caller surfaces BEFORE calling run().
        // We don't gate here — the caller is responsible for prompting the user.

        val finalFile = modelHelper.file(context)
        val partial = modelHelper.partialFile(context)
        Log.i(
            TAG,
            "Starting download ($strategy): $url -> ${partial.absolutePath} (expected $expectedSize bytes)",
        )

        // -- Download --------------------------------------------------------------
        try {
            httpDownloader.download(url, partial) { p ->
                val total = if (p.totalBytes > 0) p.totalBytes else expectedSize
                onProgress(Progress.Downloading(p.bytesReceived, total))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return@withContext Outcome.Cancelled
        } catch (e: Exception) {
            // Don't delete the partial file — resume on next attempt.
            Log.w(TAG, "Download interrupted: ${e.message}")
            return@withContext Outcome.Failed(
                "Download interrupted: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        // -- Verify ----------------------------------------------------------------
        coroutineContext.ensureActive()
        onProgress(Progress.Verifying)

        val actualSize = partial.length()
        if (actualSize != expectedSize) {
            partial.delete()
            return@withContext Outcome.Failed(
                "Size mismatch (got $actualSize, expected $expectedSize)",
            )
        }

        val actualSha = computeSha256(partial)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            partial.delete()
            return@withContext Outcome.Failed(
                "SHA-256 mismatch (got $actualSha, expected $expectedSha)",
            )
        }

        // -- Commit ----------------------------------------------------------------
        when (strategy) {
            CommitStrategy.FileSwap -> commitFileSwap(partial, finalFile)?.let { return@withContext it }
            CommitStrategy.ZipExtract -> commitZipExtract(partial, finalFile, expectedSha, onProgress)?.let { return@withContext it }
            CommitStrategy.MultiFile -> error(
                "MultiFile commit reached the legacy commit branch — early dispatch should have returned"
            )
        }

        Log.i(TAG, "Download + verify + commit succeeded: ${finalFile.absolutePath}")
        Outcome.Success
    }

    /**
     * FileSwap commit: atomic-rename the verified partial onto [finalFile].
     * Returns null on success, or a populated [Outcome.Failed] on error
     * (caller short-circuits with the returned outcome).
     */
    private fun commitFileSwap(partial: File, finalFile: File): Outcome.Failed? = try {
        // Atomic replace: rename(2) either succeeds atomically or leaves both
        // paths untouched. REPLACE_EXISTING covers in-place model upgrades
        // (catalog ships v2 at the same filename). On failure, the verified
        // partial is preserved for a retry AND any previous install at
        // finalFile keeps serving — no path where we delete one and fail to
        // land the other. Both paths are under noBackupFilesDir (same FS), so
        // ATOMIC_MOVE is honored by ext4/f2fs without falling back.
        Files.move(
            partial.toPath(),
            finalFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to commit verified model to ${finalFile.absolutePath}", e)
        Outcome.Failed(
            "Failed to commit verified model to ${finalFile.absolutePath}: " +
                (e.message ?: e.javaClass.simpleName),
            e,
        )
    }

    /**
     * ZipExtract commit: extract the verified zip partial to `<finalDir>.tmp/`,
     * directory-swap onto [finalDir], write the sentinel containing the
     * catalog sha (so [ModelHelper.isInstalled] knows the extract was
     * complete), then delete the now-redundant zip partial.
     *
     * Returns null on success, or a populated [Outcome.Failed] on error.
     */
    private suspend fun commitZipExtract(
        zipPartial: File,
        finalDir: File,
        expectedSha: String,
        onProgress: (Progress) -> Unit,
    ): Outcome.Failed? {
        val tmpDir = tmpDirFor(finalDir)
        // Wipe any leftover .tmp from a kill mid-extract (which deletePartial()
        // already covers on the next launch, but be belt-and-suspenders here
        // — the failed install path also needs a clean slate).
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        return try {
            onProgress(Progress.Extracting(0L, null))
            PackIntegrity.extractZip(zipPartial, tmpDir)
            // Write the sentinel INSIDE tmpDir, before safeSwap. Two
            // properties that buys:
            //   1. The directory rename promotes contents AND install gate
            //      atomically — finalDir is never observed with a sentinel
            //      and missing files, or files and a missing sentinel.
            //   2. If something deletes tmpDir between extract and swap —
            //      e.g. the cancel handler's deletePartial() racing this
            //      coroutine on Dispatchers.IO — safeSwap throws
            //      (LanguagePackStore now refuses an empty/missing src) and
            //      finalDir is left untouched. The catch below returns
            //      Outcome.Failed; the previous good install survives.
            // Closes the [high] in Codex's 2026-05-22 adversarial review.
            File(tmpDir, ".sentinel").writeText(expectedSha)
            // Deterministic cancellation pickup before the destructive ops.
            // Without this, a cancel that fired during extract is only
            // observed at the next suspending call — safeSwap is synchronous,
            // so cancellation wouldn't surface until the OkHttp-level
            // suspend points up the stack.
            coroutineContext.ensureActive()
            LanguagePackStore.safeSwap(tmpDir, finalDir)
            // Delete the verified zip — already extracted; keeping it would
            // double the on-disk cost of the model.
            zipPartial.delete()
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CancellationException extends Exception in the JVM, so the
            // broad catch below would otherwise swallow it and map an
            // intentional user-cancel into Outcome.Failed (visible as a
            // "Download failed" toast + ERROR-level logcat). Rethrow here
            // so the coroutine teardown completes naturally; the caller's
            // viewLifecycleOwner.lifecycleScope handles the dismissal.
            // Codex review (2026-05-22, [P2]).
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract/install zip to ${finalDir.absolutePath}", e)
            // Don't delete zipPartial here — it's SHA-verified and could
            // support a retry without re-downloading. deletePartial() is the
            // explicit "give up, start over" path.
            Outcome.Failed(
                "Failed to extract/install zip to ${finalDir.absolutePath}: " +
                    (e.message ?: e.javaClass.simpleName),
                e,
            )
        }
    }

    /** The sibling staging directory used by ZipExtract: `<final>.tmp`. */
    private fun tmpDirFor(finalDir: File): File =
        File(finalDir.parentFile, "${finalDir.name}.tmp")

    /**
     * MultiFile commit strategy: catalog ships [CatalogEntry.files] (a list
     * of per-file URL/size/sha256 triples). Used when the model's canonical
     * upstream hosts the bundle as individual files instead of a zip —
     * notably the wangjazz Hunyuan-MT 1.5 MNN bundle, which we consume
     * directly to preserve the "user agent" liability posture under the
     * Tencent HY Community License (we never re-host a combined zip).
     *
     * Flow:
     *  1. Validate every file's size > 0, sha256 hex, path safe (no `..`,
     *     no absolute paths — same canonical-prefix check the zip path
     *     uses, via [PackIntegrity.isInside]).
     *  2. Preflight RAM + storage (`totalBytes = sum of per-file sizes`
     *     plus 5%/100 MB headroom; per-file `.partial`s are atomic-renamed
     *     in place inside `tmpDir`, and safeSwap uses `renameTo` in the
     *     happy path, so the peak stays ~1× — same posture as
     *     [preflightStorage]).
     *  3. Per file (sequential):
     *     - download to `<tmpDir>/<path>.partial` via the existing
     *       [LanguagePackDownloader.download], which gets HTTP-Range
     *       resume for free.
     *     - verify size + SHA-256; mismatch → delete partial, fail.
     *     - atomic-rename `<path>.partial` → `<path>` *inside tmpDir*.
     *       After this rename the file is considered "verified" — a
     *       re-entry will skip it via the length-equals-expected check.
     *  4. Write `<tmpDir>/.sentinel` containing the aggregate SHA via
     *     [MultiFileSha.aggregate]. Written BEFORE safeSwap so the
     *     directory rename promotes contents AND install gate
     *     atomically.
     *  5. [LanguagePackStore.safeSwap] promotes `<tmpDir>` → `<finalDir>`.
     *
     * Sequential not parallel: predictable progress, no HF rate-limit
     * risk, simpler resume semantics; bandwidth is the bottleneck at
     * ~1 GB anyway.
     */
    private suspend fun runMultiFile(
        entry: CatalogEntry,
        files: List<CatalogFile>,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val finalDir = modelHelper.file(context)
        val tmpDir = tmpDirFor(finalDir)

        // 1. Validate before touching disk.
        for (f in files) {
            if (f.size <= 0L) {
                return Outcome.Failed("MultiFile entry ${modelHelper.catalogKey}: ${f.path} size <= 0")
            }
            if (!SHA_HEX_REGEX.matches(f.sha256)) {
                return Outcome.Failed("MultiFile entry ${modelHelper.catalogKey}: ${f.path} sha256 malformed")
            }
            if (f.path.isBlank()) {
                return Outcome.Failed("MultiFile entry ${modelHelper.catalogKey}: blank path")
            }
            // Path-traversal defense: resolve `<tmpDir>/<f.path>` and confirm
            // the canonical form lives strictly under tmpDir. Same check
            // PackIntegrity.extractZip uses per zip entry.
            if (!PackIntegrity.isInside(tmpDir, File(tmpDir, f.path))) {
                return Outcome.Failed("MultiFile entry ${modelHelper.catalogKey}: path traversal: ${f.path}")
            }
        }

        // 2. Preflights.
        preflightRam()?.let { return Outcome.Refused(it) }
        val totalBytes = files.sumOf { it.size }
        preflightStorageMultiFile(totalBytes, tmpDir, files)?.let { return Outcome.Refused(it) }

        tmpDir.mkdirs()
        Log.i(
            TAG,
            "Starting MultiFile download (${files.size} files, $totalBytes bytes) to ${tmpDir.absolutePath}",
        )

        // 3. Per-file download → verify → atomic-rename inside tmpDir.
        var committedBytes = 0L
        for (f in files) {
            val verifiedPath = File(tmpDir, f.path).apply { parentFile?.mkdirs() }
            val partial = File(tmpDir, "${f.path}.partial").apply { parentFile?.mkdirs() }

            // Skip-if-verified resume: a prior attempt may have already
            // completed this file's atomic-rename. Re-verify the SHA-256
            // before trusting it — Codex review (2026-05-25) flagged that
            // length-only would let a catalog upgrade that changes a
            // file's *content* at the same byte length (e.g. an upstream
            // re-quant that produces a different bit pattern at the same
            // size) reuse stale bytes and then stamp them with the NEW
            // aggregate sentinel, silently shipping a broken install.
            //
            // Re-hash is bounded: only fires on re-entry after a cancel
            // or process kill mid-download, the file is already on local
            // disk, and the hash cost is sequential 64 KB reads (a
            // single-digit-seconds scan of the 1 GB weight file on Thor).
            // Cheaper than re-downloading on the common case (matching
            // SHA → skip download); explicit safety net on the drift
            // case (SHA mismatch → delete stale, fall through to fresh
            // download below).
            if (verifiedPath.exists() && verifiedPath.length() == f.size) {
                val priorSha = computeSha256(verifiedPath)
                if (priorSha.equals(f.sha256, ignoreCase = true)) {
                    Log.i(TAG, "skip-if-verified: ${f.path} (${f.size} bytes, SHA matched)")
                    committedBytes += f.size
                    onProgress(Progress.Downloading(committedBytes, totalBytes))
                    continue
                }
                Log.w(
                    TAG,
                    "skip-if-verified: ${f.path} stale (SHA drift vs catalog) — deleting and re-downloading",
                )
                if (!verifiedPath.delete()) {
                    return Outcome.Failed(
                        "Failed to delete stale staged file ${verifiedPath.absolutePath}",
                    )
                }
            }

            // Capture the "base" before this file's download so the
            // running progress total accounts for already-committed files.
            val baseCommitted = committedBytes
            try {
                httpDownloader.download(f.url, partial) { p ->
                    onProgress(Progress.Downloading(baseCommitted + p.bytesReceived, totalBytes))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                return Outcome.Cancelled
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${f.path} interrupted: ${e.message}")
                return Outcome.Failed(
                    "Download of ${f.path} failed: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }

            coroutineContext.ensureActive()
            onProgress(Progress.Verifying)
            val actualSize = partial.length()
            if (actualSize != f.size) {
                partial.delete()
                return Outcome.Failed(
                    "MultiFile size mismatch on ${f.path} (got $actualSize, expected ${f.size})",
                )
            }
            val actualSha = computeSha256(partial)
            if (!actualSha.equals(f.sha256, ignoreCase = true)) {
                partial.delete()
                return Outcome.Failed(
                    "MultiFile SHA-256 mismatch on ${f.path}",
                )
            }

            // Atomic rename inside tmpDir. After this rename the file is
            // "verified"; the skip-if-verified branch above trusts it on
            // re-entry. Same atomic-rename properties as commitFileSwap —
            // either succeeds atomically or leaves both paths untouched.
            try {
                Files.move(
                    partial.toPath(),
                    verifiedPath.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                return Outcome.Failed(
                    "Failed to commit ${f.path} inside tmpDir: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            committedBytes += f.size
            onProgress(Progress.Downloading(committedBytes, totalBytes))
        }

        // 4. Sentinel BEFORE safeSwap — same atomicity property the
        // ZipExtract path documents at length above. The
        // [MultiFileSha.aggregate] util is the single source of truth so
        // the writer here and the reader (HyMtModel.isInstalled) can't
        // drift.
        val aggregate = MultiFileSha.aggregate(files)
        try {
            File(tmpDir, ".sentinel").writeText(aggregate)
        } catch (e: Exception) {
            return Outcome.Failed(
                "Failed to write sentinel in ${tmpDir.absolutePath}: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        // 5. safeSwap promotes tmpDir → finalDir. Deterministic
        // cancellation pickup before the destructive op.
        coroutineContext.ensureActive()
        return try {
            LanguagePackStore.safeSwap(tmpDir, finalDir)
            Log.i(TAG, "MultiFile install succeeded: ${finalDir.absolutePath}")
            Outcome.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Same rationale as the ZipExtract path — rethrow so the
            // intentional user-cancel doesn't get mapped to Outcome.Failed.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swap tmpDir to ${finalDir.absolutePath}", e)
            Outcome.Failed(
                "Failed to install to ${finalDir.absolutePath}: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    /**
     * Storage preflight for [CommitStrategy.MultiFile]. Each per-file
     * `.partial` is atomic-renamed in place inside `tmpDir`, so the
     * download peaks at ~1× the bundle size; no zip-extract expansion
     * cost like [CommitStrategy.ZipExtract] has. [LanguagePackStore.safeSwap]
     * uses [File.renameTo] in the happy path (metadata-only), so the
     * upgrade swap window doesn't add disk pressure either — matches
     * the headroom posture in [preflightStorage].
     *
     * Already-on-disk per-file partials (from a prior resumable download)
     * are subtracted from the remaining-bytes-needed estimate.
     */
    private fun preflightStorageMultiFile(
        totalBytes: Long,
        tmpDir: File,
        files: List<CatalogFile>,
    ): String? {
        val dir = context.noBackupFilesDir
        val sf = StatFs(dir.absolutePath)
        val free = sf.availableBytes
        // Count bytes already on disk from prior attempts (per-file
        // partials + verified-renamed siblings inside tmpDir).
        val alreadyOnDisk = files.sumOf { f ->
            val verified = File(tmpDir, f.path)
            val partial = File(tmpDir, "${f.path}.partial")
            when {
                verified.exists() && verified.length() == f.size -> f.size
                partial.exists() -> partial.length().coerceAtMost(f.size)
                else -> 0L
            }
        }
        val remaining = (totalBytes - alreadyOnDisk).coerceAtLeast(0L)
        // 5% headroom + 100 MB minimum on remaining bytes.
        val needed = (remaining * 105 / 100).coerceAtLeast(remaining + 100_000_000L)
        if (free < needed) {
            return "Need ${humanSize(needed)} free, only ${humanSize(free)} available"
        }
        return null
    }

    /**
     * Delete any partial file *and* any orphan extract directory. Use on
     * explicit user cancel (not on transient failure). File-mode helpers
     * only have a `.partial`; directory-mode helpers also have a `.tmp/`
     * staging directory that a mid-extract kill may have left behind.
     */
    fun deletePartial() {
        val f = modelHelper.partialFile(context)
        if (f.exists()) {
            val ok = f.delete()
            Log.i(TAG, "Deleted partial file: ${f.absolutePath} ok=$ok")
        }
        if (modelHelper.isDirectoryMode()) {
            val tmp = tmpDirFor(modelHelper.file(context))
            if (tmp.exists()) {
                val ok = tmp.deleteRecursively()
                Log.i(TAG, "Deleted partial tmp dir: ${tmp.absolutePath} ok=$ok")
            }
        }
    }

    // -- Pre-flight helpers --------------------------------------------------------

    private fun preflightRam(): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.totalMem < totalMemFloorBytes) {
            val totalGb = mi.totalMem / 1_000_000_000
            val needGb = totalMemFloorBytes / 1_000_000_000
            return "Insufficient RAM ($totalGb GB total, need $needGb GB)"
        }
        return null
    }

    private fun preflightStorage(expectedSize: Long, strategy: CommitStrategy): String? {
        val dir = context.noBackupFilesDir
        val sf = StatFs(dir.absolutePath)
        val free = sf.availableBytes
        // Partial-file accounting. After a download is interrupted we keep the
        // partial on disk for resume (LanguagePackDownloader uses HTTP Range);
        // the bytes we still need to *fetch* are (expectedSize - alreadyOnDisk).
        val partial = modelHelper.partialFile(context)
        val alreadyOnDisk = if (partial.exists()) partial.length() else 0L
        val remainingBytes = (expectedSize - alreadyOnDisk).coerceAtLeast(0L)
        // 5% headroom + 100 MB minimum.
        var needed = (remainingBytes * 105 / 100).coerceAtLeast(remainingBytes + 100_000_000L)
        // ZipExtract additionally needs room for the extracted contents
        // (which the partial zip will hold compressed). Without an
        // `extractedSize` catalog field we don't know exactly — budget 2.5×
        // the zip size as a heuristic. The MNN Qwen zip is ~840 MB
        // compressed, ~1.0 GB uncompressed (mostly already-quantized
        // .mnn.weight, which compresses poorly), so 2.5× is comfortable.
        if (strategy == CommitStrategy.ZipExtract) {
            needed += (expectedSize * 25 / 10)
        }
        if (free < needed) {
            return "Need ${humanSize(needed)} free, only ${humanSize(free)} available"
        }
        return null
    }

    /**
     * Returns true if the active default network is metered (cellular,
     * tethered hotspot, or a Wi-Fi the user / OEM / carrier has marked
     * metered in Android Settings).
     *
     * Logs the active transports and the four capabilities that tend to
     * matter for the "why is this prompting me on Wi-Fi?" support
     * question. The classifier is the system's — there is no in-app fix
     * for a misclassified network — so the log line exists to let an
     * exported log answer the diagnosis question without the user having
     * to dig through Network usage settings. Read the line as:
     *
     *   transports=[WIFI] NOT_METERED=false → Wi-Fi marked metered
     *     (Settings → Network usage, hotspot, or carrier flag).
     *   transports=[CELLULAR] → actually on cellular.
     *   transports=[VPN, …] → a VPN is masking the underlying transport.
     */
    fun isCurrentNetworkMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        if (active == null) {
            Log.i(TAG, "Metered check: no active default network → unmetered")
            return false
        }
        val caps = cm.getNetworkCapabilities(active)
        if (caps == null) {
            Log.i(TAG, "Metered check: active network but no NetworkCapabilities → unmetered")
            return false
        }
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val tempNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        val notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        val metered = !notMetered
        Log.i(
            TAG,
            "Metered check: transports=$transports NOT_METERED=$notMetered " +
                "TEMP_NOT_METERED=$tempNotMetered NOT_VPN=$notVpn NOT_ROAMING=$notRoaming " +
                "→ isMetered=$metered",
        )
        return metered
    }

    private suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "OnDeviceLlmDownloader"

        /** Hex SHA-256 = exactly 64 hexits, case-insensitive. Shared between
         *  [runMultiFile] (per-file validation) and
         *  `HyMtModel.hasShippableCatalogEntry` (catalog visibility gate). */
        val SHA_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }
}
