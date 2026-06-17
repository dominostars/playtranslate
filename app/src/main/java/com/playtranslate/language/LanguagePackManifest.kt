package com.playtranslate.language

import com.playtranslate.PtJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * On-disk per-pack manifest. Written once when a pack is extracted
 * (bundled or downloaded) and then only read back for schema-version
 * checks in [LanguagePackStore.validateManifest].
 *
 * [packVersion] is informational — copied from the catalog entry when the
 * pack is installed, used for support / diagnostics only. The app never
 * compares it against the catalog's packVersion to decide whether to
 * re-download: pack refreshes happen via app releases, not background
 * catalog polling. See `project_pack_update_policy.md`.
 *
 * [schemaVersion] is enforced: packs whose schemaVersion exceeds
 * [LanguagePackStore.SUPPORTED_SCHEMA_VERSION] are rejected at install time.
 */
@Serializable
data class LanguagePackManifest(
    val langId: String,
    val schemaVersion: Int,   // manifest schema version — enforced at install
    val packVersion: Int,     // informational; not used for update detection
    val appMinVersion: Int,
    val files: List<ManifestFile>,
    val totalSize: Long,
    val licenses: List<ManifestLicense>,
)

/** [sha256] is nullable because bundled packs don't need it — APK integrity covers them. */
@Serializable
data class ManifestFile(
    val path: String,
    val size: Long,
    val sha256: String? = null,
)

/** License attribution for one component inside a pack. Required by CC-BY-SA-4.0. */
@Serializable
data class ManifestLicense(
    val component: String,
    val license: String,
    val attribution: String,
)

/** Read/write helpers for [LanguagePackManifest] on disk. */
object LanguagePackManifestIO {
    fun read(file: File): LanguagePackManifest? = try {
        if (!file.exists()) null
        else PtJson.lenient.decodeFromString<LanguagePackManifest>(file.readText())
    } catch (_: Exception) {
        null
    }

    fun write(file: File, manifest: LanguagePackManifest) {
        file.parentFile?.mkdirs()
        file.writeText(PtJson.pretty.encodeToString(manifest))
    }
}
