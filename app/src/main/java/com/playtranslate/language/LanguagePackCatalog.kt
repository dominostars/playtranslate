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

    /**
     * `true` for engine entries (`type: "engine"`) that ship as a zip of
     * multiple loose files rather than a single GGUF. The
     * [com.playtranslate.translation.llm.OnDeviceLlmDownloader] downloads
     * the zip, SHA-verifies it, then extracts to a sibling `.tmp/` dir and
     * safe-swaps to the final dir (matching the language-pack pattern in
     * `LanguagePackStore`). The catalog's [size] and [sha256] are the
     * **zip's** integrity values, not the extracted footprint.
     *
     * `null` / omitted defaults to file-mode (the existing GGUF default for
     * every previously-shipped engine entry). The first directory-mode
     * entry is the MNN-backed Qwen (`engine-qwen-1-5b-mnn`).
     */
    val extract: Boolean? = null,

    /**
     * For models hosted as individual files instead of a single zip. When
     * non-null and non-empty, takes precedence over [url] / [sha256]: the
     * downloader's MultiFile commit strategy fetches each file
     * independently, verifies its per-file SHA-256, atomic-renames it
     * inside a staging dir, then writes a sentinel containing the
     * aggregate hash (`sha256` of sorted `<path>:<sha256>\n` lines) and
     * safe-swaps the staging dir into place.
     *
     * Entries that set both [files] and [url] are rejected by the model-
     * helper's catalog validator — pick one. [url] / [sha256] / [extract]
     * are the FileSwap / ZipExtract path; [files] is the MultiFile path.
     *
     * Introduced for the Hunyuan-MT 1.5 backend, which is canonically
     * hosted as individual files on
     * `huggingface.co/wangjazz/Hunyuan-MT1.5-1.8B-MNN` — we consume them
     * directly to stay in the "user agent" liability posture under the
     * Tencent HY Community License (we never assemble or redistribute a
     * combined zip).
     */
    val files: List<CatalogFile>? = null,

    /**
     * Generic "this entry is retired" marker. `true` (in JSON) means the thing
     * this entry describes is deprecated: on launch any in-flight
     * partial/staging artifacts for it are deleted, and in Settings its row is
     * shown only while the model is fully installed (so a not-installed
     * deprecated model can't be freshly downloaded) with a warning badge.
     *
     * Deliberately generic and data-driven — marking another entry deprecated
     * is a one-line catalog change, no new code. `null` / omitted = not
     * deprecated. Consumed by
     * [com.playtranslate.translation.llm.OnDeviceLlmBackend.isDeprecated] (the
     * UI gate) and the launch-time partial cleanup in `PlayTranslateApplication`.
     */
    val deprecated: Boolean? = null,

    /**
     * Transformer architecture for an NMT engine model (Bergamot/slimt), baked
     * from the model's Mozilla `metadata.json`
     * (`modelConfig.{enc-depth, dec-depth, transformer-heads, transformer-ffn-depth}`)
     * by `scripts/gen_bergamot_catalog.py` at catalog-build time. `null` /
     * omitted means "use the engine's documented default" — for Bergamot, the
     * base-memory 6/4/8/2 fallback in
     * [com.playtranslate.translation.bergamot.BergamotModelManager]. Only emitted
     * when a model's architecture differs from that default, so base-memory
     * entries stay clean.
     */
    val arch: EngineArch? = null,
)

/**
 * One file inside a multi-file catalog entry. Conceptually a per-file
 * triplet of `(url, integrity, layout)`:
 *
 * - [path] is the relative path the file lands at under the model's final
 *   directory. The downloader rejects path-traversal (`..`) and absolute
 *   paths via [com.playtranslate.language.PackIntegrity.isInside] before
 *   any disk write.
 * - [url] is the resolvable URL the downloader fetches from. Typically a
 *   HuggingFace `…/resolve/main/<path>` URL; LFS-backed files 302-redirect
 *   to the LFS CDN and OkHttp follows transparently.
 * - [size] is the expected byte length. Mismatch deletes the partial and
 *   returns `Outcome.Failed`.
 * - [sha256] is the expected hex SHA-256 (case-insensitive). Pinned so
 *   silent upstream rotation produces a clear download error.
 *
 * All four fields are required (declared non-null) but Gson reflection can
 * still populate nulls if a JSON entry omits a field. Downstream
 * validators (e.g. `HyMtModel.hasShippableCatalogEntry`) gate visibility
 * before any code touches these as non-null — same pattern the rest of
 * the catalog schema uses.
 */
data class CatalogFile(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** When true, [url] serves a gzip stream (no `Content-Encoding`, so the HTTP
     *  client doesn't auto-inflate); the downloader gunzips the payload and
     *  verifies [size]/[sha256] against the UNCOMPRESSED bytes. Used for Bergamot
     *  models served from Mozilla's GCS bucket. */
    val gzip: Boolean = false,
)

/**
 * Transformer layer counts for an NMT engine model (Bergamot/slimt). Read at
 * runtime by
 * [com.playtranslate.translation.bergamot.BergamotModelManager.filesFor] and
 * handed to the native loader — mismatched counts mis-load the weights into
 * fluent garbage, so these are the model's own `metadata.json` values baked into
 * the catalog by `scripts/gen_bergamot_catalog.py`, never a guess. All four are
 * required when present (the script always writes the complete set); a partial
 * or non-positive value is treated as absent and falls back to the engine
 * default.
 */
data class EngineArch(
    val encoderLayers: Int,
    val decoderLayers: Int,
    val feedForwardDepth: Int,
    val numHeads: Int,
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
