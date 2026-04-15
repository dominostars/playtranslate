package com.playtranslate.language

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * On-disk per-pack manifest. Written once on first boot for bundled packs;
 * Phase 3+ writes updated copies after successful downloads. Separate from
 * [LanguagePackCatalog] so that installed packs carry their own version
 * record independent of the catalog's advertised version — Phase 3's update
 * detection compares manifest.packVersion against catalog.packVersion to
 * decide whether an update is available.
 */
data class LanguagePackManifest(
    val langId: String,
    val schemaVersion: Int,   // manifest schema version (Phase 2 ships v1)
    val packVersion: Int,
    val appMinVersion: Int,
    val files: List<ManifestFile>,
    val totalSize: Long,
    val licenses: List<ManifestLicense>,
)

/** [sha256] is nullable because bundled packs don't need it — APK integrity covers them. */
data class ManifestFile(
    val path: String,
    val size: Long,
    val sha256: String? = null,
)

/** License attribution for one component inside a pack. Required by CC-BY-SA-4.0. */
data class ManifestLicense(
    val component: String,
    val license: String,
    val attribution: String,
)

/** Read/write helpers for [LanguagePackManifest] on disk. */
object LanguagePackManifestIO {
    private val writer: Gson = GsonBuilder().setPrettyPrinting().create()
    private val reader: Gson = Gson()

    fun read(file: File): LanguagePackManifest? = try {
        if (!file.exists()) null
        else reader.fromJson(file.readText(), LanguagePackManifest::class.java)
    } catch (_: Exception) {
        null
    }

    fun write(file: File, manifest: LanguagePackManifest) {
        file.parentFile?.mkdirs()
        file.writeText(writer.toJson(manifest))
    }
}
