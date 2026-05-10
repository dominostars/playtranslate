package com.playtranslate.language

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Parsed form of `assets/langpack_catalog.json`. Bundled inside the APK and
 * enumerates every supported source language + where to fetch non-bundled
 * packs. Phase 2 ships a catalog containing only the `ja` entry. Adding a new
 * supported source language requires an app release that updates the JSON.
 */
data class LanguagePackCatalog(
    val catalogVersion: Int,
    val packs: Map<String, CatalogEntry>,
)

/**
 * One catalog row. [licenses] is bundled inside the catalog so the manifest
 * writer can copy it verbatim when bootstrapping a bundled pack; [url] and
 * [sha256] are null for bundled packs (the APK itself guarantees integrity).
 */
data class CatalogEntry(
    val display: String,
    val script: String,
    val bundled: Boolean,
    val packVersion: Int,
    val size: Long,
    // Nullable because Gson reflection bypasses Kotlin constructor defaults
    // when the field is absent from JSON — see writeManifestIfMissing for the
    // `orEmpty()` coalesce.
    val licenses: List<ManifestLicense>? = null,
    val url: String? = null,
    val sha256: String? = null,
    val coverageNote: String? = null,
    /** null or "source" for source packs (backward compat), "target" for target gloss packs. */
    val type: String? = null,
    /**
     * Lowest on-disk packVersion that can take the **additive upgrade** path
     * to the current [packVersion] — meaning the existing pack stays on disk
     * during install and only gets atomically swapped after the new pack is
     * verified, so cancellation / failure / network drop leaves the user
     * with a usable pack.
     *
     * - `null` (or omitted in JSON) → no version qualifies for additive;
     *   ALL stale packs are FORCE (the existing pre-uninstall + install flow).
     * - `1` → on-disk packVersion ≥ 1 takes ADDITIVE.
     * - Comparison is `onDisk.packVersion >= additiveFromVersion`. Below
     *   the boundary is FORCE; at-or-above is ADDITIVE.
     *
     * Bump this field to force users below a breaking change through clean
     * reinstall; leave it stable to allow incremental upgrades within a
     * compatibility window. See `project_pack_update_policy.md`.
     */
    val additiveFromVersion: Int? = null,
)

/**
 * Reads and caches the catalog. Called from [com.playtranslate.dictionary.DictionaryManager]
 * to resolve bundled-pack metadata during first-boot bootstrap, and from the
 * Settings language picker to build the list of available source languages.
 */
object LanguagePackCatalogLoader {
    private const val ASSET_PATH = "langpack_catalog.json"
    private const val TAG = "LangPackCatalog"

    @Volatile private var cached: LanguagePackCatalog? = null

    fun load(ctx: Context): LanguagePackCatalog = cached ?: synchronized(this) {
        cached ?: run {
            val json = ctx.applicationContext.assets.open(ASSET_PATH)
                .bufferedReader().use { it.readText() }
            val parsed = Gson().fromJson(json, LanguagePackCatalog::class.java)
                ?: error("langpack_catalog.json parsed to null")
            cached = parsed
            Log.d(TAG, "Loaded catalog v${parsed.catalogVersion}, ${parsed.packs.size} pack(s)")
            parsed
        }
    }

    fun entryFor(ctx: Context, id: SourceLangId): CatalogEntry? = try {
        load(ctx).packs[id.packId.code]
    } catch (e: Exception) {
        Log.w(TAG, "Catalog unavailable: ${e.message}")
        null
    }

    /** Look up a catalog entry by its raw key (e.g. "target-fr"). */
    fun entryForKey(ctx: Context, key: String): CatalogEntry? = try {
        load(ctx).packs[key]
    } catch (e: Exception) {
        Log.w(TAG, "Catalog unavailable: ${e.message}")
        null
    }
}
