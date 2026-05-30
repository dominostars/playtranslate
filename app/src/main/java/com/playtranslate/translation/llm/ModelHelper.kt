package com.playtranslate.translation.llm

import android.content.Context
import com.playtranslate.language.CatalogEntry
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for an on-device LLM model.
 *
 * Implementations are typically `object`s (one per model). The catalog entry
 * (`langpack_catalog.json`) is the source of truth for URL, expected size,
 * SHA-256, and license attribution. The existence of [file] at the catalog's
 * expected size is the source of truth for "is this model installed?" — and
 * that file only appears after SHA-256 verification, because the downloader
 * writes incoming bytes to [partialFile] and atomically renames to [file] only
 * after the hash matches. We deliberately do not track install state in prefs
 * so a manual `rm` leaves no stale "installed" indicator.
 */
interface ModelHelper {
    /** Catalog entry key under `app/src/main/assets/langpack_catalog.json`. */
    val catalogKey: String

    /** Returns the catalog entry, or null when the catalog hasn't been loaded
     *  (e.g. tests) or the entry is missing. */
    fun catalogEntry(ctx: Context): CatalogEntry?

    /**
     * For file-mode helpers (the GGUF default): the absolute path of the
     * verified single-file model on disk. The downloader's atomic-rename
     * guarantees a file at this path has passed SHA-256 verification.
     *
     * For directory-mode helpers (when [isDirectoryMode] is true): the
     * absolute path of the *extracted root directory* containing the model's
     * loose files (e.g. MNN's `llm.mnn`, `llm.mnn.weight`, `config.json`).
     * The downloader's zip-extract path writes a `.sentinel` file inside this
     * directory containing the catalog SHA-256 after a verified successful
     * extract; [isInstalled] checks that sentinel rather than a single-file
     * size, since the catalog `size` is the *zip's* size, not the extracted
     * footprint.
     *
     * Either way, implementations should ensure the parent directory exists.
     * Callers that need to disambiguate use [isDirectoryMode].
     */
    fun file(ctx: Context): File

    /**
     * True if [file] returns the *extracted root directory* of a zip-distributed
     * multi-file model (e.g. the MNN Qwen helper), false if it returns a
     * single verified file (the default — used by all GGUF models). The
     * downloader keys its [CommitStrategy] on this flag (see
     * `OnDeviceLlmDownloader`), so directory-mode helpers MUST override it.
     */
    fun isDirectoryMode(): Boolean = false

    /** Absolute path where in-flight download bytes land before verification.
     *  Persists across coroutine cancellation to support HTTP Range resume;
     *  only renamed to [file] after the streamed SHA-256 matches the catalog. */
    fun partialFile(ctx: Context): File {
        val final = file(ctx)
        return File(final.parentFile, "${final.name}.partial")
    }

    /** True when the on-disk file exists and matches the catalog's expected size. */
    fun isInstalled(ctx: Context): Boolean

    /** Catalog's expected file size in bytes. Returns 0 if the catalog entry is missing. */
    fun expectedSize(ctx: Context): Long

    /** Human-readable expected size, e.g. "2.19 GB". */
    fun humanSize(ctx: Context): String

    /** Best-effort delete of both the verified file and any leftover partial.
     *  Returns true if neither file remains after the call. */
    fun delete(ctx: Context): Boolean

    /** Delete only the in-flight download artifacts — the `.partial` file/zip
     *  and (directory-mode) the `.tmp` extract-staging dir — WITHOUT touching a
     *  fully-installed model. Used by the launch-time cleanup for deprecated
     *  catalog entries so a retired model's interrupted download can't resume,
     *  while a completed install is preserved (its row stays visible). */
    fun deletePartials(ctx: Context): Boolean {
        val partialGone = partialFile(ctx).let { if (!it.exists()) true else it.delete() }
        val tmpGone = if (isDirectoryMode()) {
            val final = file(ctx)
            File(final.parentFile, "${final.name}.tmp").let {
                if (!it.exists()) true else it.deleteRecursively()
            }
        } else true
        return partialGone && tmpGone
    }
}
