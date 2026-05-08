package com.playtranslate.translation.llm

import android.content.Context
import com.playtranslate.language.CatalogEntry
import java.io.File

/**
 * Manifest-backed paths and integrity helpers for an on-device LLM model.
 *
 * Implementations are typically `object`s (one per model). The catalog entry
 * (`langpack_catalog.json`) is the source of truth for URL, expected size,
 * SHA-256, and license attribution. The on-disk file's existence + size match
 * is the source of truth for "is this model installed?" — we deliberately do
 * not track install state in prefs so the file can be deleted out-of-band
 * without leaving the UI in an inconsistent state.
 */
interface ModelHelper {
    /** Catalog entry key under `app/src/main/assets/langpack_catalog.json`. */
    val catalogKey: String

    /** Returns the catalog entry, or null when the catalog hasn't been loaded
     *  (e.g. tests) or the entry is missing. */
    fun catalogEntry(ctx: Context): CatalogEntry?

    /** Absolute path where the GGUF lives on disk. Implementations should
     *  ensure the parent directory exists. */
    fun file(ctx: Context): File

    /** True when the on-disk file exists and matches the catalog's expected size. */
    fun isInstalled(ctx: Context): Boolean

    /** Catalog's expected file size in bytes. Returns 0 if the catalog entry is missing. */
    fun expectedSize(ctx: Context): Long

    /** Human-readable expected size, e.g. "2.19 GB". */
    fun humanSize(ctx: Context): String

    /** Best-effort delete. Returns true if the file no longer exists after the call. */
    fun delete(ctx: Context): Boolean
}
