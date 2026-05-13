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

    /** Absolute path where the verified GGUF lives on disk. A file at this path
     *  is guaranteed (by the downloader's atomic-rename) to have passed SHA-256
     *  verification. Implementations should ensure the parent directory exists. */
    fun file(ctx: Context): File

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
}
