package com.playtranslate.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import com.playtranslate.BuildConfig
import com.playtranslate.language.PackIntegrity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure logic for the in-app self-update path: where the downloaded APK lives,
 * whether this build is even eligible to self-update, and whether a downloaded
 * file is safe to hand to the system package installer.
 *
 * The system installer's own failure modes are opaque ("App not installed",
 * silent downgrade rejection), so [validateDownloaded] front-runs every one we
 * can detect — size, checksum, package identity, version direction, signing
 * cert — and the UI maps each rung to a specific message with a browser
 * fallback. The installer's confirm sheet remains the final authority; nothing
 * here installs anything.
 *
 * All validation that opens the APK runs on Dispatchers.IO via the suspend
 * entry points: `GET_SIGNING_CERTIFICATES` verifies the APK signing block,
 * which reads (and hashes) the whole ~128 MB file.
 */
object ApkUpdateManager {

    /** SHA-256 (lowercase hex, no separators) of the certificate that signs
     *  published GitHub releases — read from the v2.4.1 release asset via
     *  `apksigner verify --print-certs`. Builds signed with any other cert
     *  (debug keystores on other machines, self-built forks) can't install a
     *  release APK over themselves, so [ownSignatureIsReleaseCert] gates the
     *  in-app download offer and the UI falls back to the browser link. If the
     *  release key ever rotates this constant goes stale and the gate fails
     *  closed — in-app updates silently revert to the browser path. */
    private const val RELEASE_SIGNING_CERT_SHA256 =
        "fb7aac750eeae3f832eca6ab7eb32fe65149602a17d702242b8b3f69212902b8"

    /** Free-space headroom beyond the remaining download bytes. */
    private const val STORAGE_HEADROOM_BYTES = 100_000_000L

    fun updatesDir(context: Context): File = File(context.cacheDir, "updates")

    /** Tag-keyed so a new release never Range-resumes onto a superseded tag's
     *  bytes — [com.playtranslate.language.LanguagePackDownloader] resumes
     *  blindly into whatever file it's given. */
    fun apkFileFor(context: Context, tag: String): File =
        File(updatesDir(context), "update-$tag.apk")

    /** Deletes everything in the updates dir except [keepTag]'s APK (whole or
     *  partial). Called at download start so retries of the current tag keep
     *  their resumable bytes while stale tags' files go away. */
    fun sweepStale(context: Context, keepTag: String) {
        val keep = apkFileFor(context, keepTag).name
        updatesDir(context).listFiles()?.forEach { f ->
            if (f.name != keep) f.deleteRecursively()
        }
    }

    fun cleanupAll(context: Context) {
        updatesDir(context).deleteRecursively()
    }

    /** Returns null when there's room to finish the download, else the byte
     *  count the failure message should ask the user to free up. */
    fun preflightStorage(context: Context, assetSize: Long, partialBytes: Long): Long? {
        val needed = (assetSize - partialBytes).coerceAtLeast(0L) + STORAGE_HEADROOM_BYTES
        val available = StatFs(context.cacheDir.absolutePath).availableBytes
        return if (available >= needed) null else needed
    }

    /** Whether this install is signed with the release cert — the only builds
     *  the published APK can install over. */
    fun ownSignatureIsReleaseCert(context: Context): Boolean =
        ownCertSha256(context) == RELEASE_SIGNING_CERT_SHA256

    private fun ownCertSha256(context: Context): String? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Validation rung that failed; the UI maps each to a specific message.
     *  [Incomplete] and [ChecksumMismatch] are transfer problems worth a
     *  retry; the rest mean the file (or this build) can never install. */
    enum class ValidationFailure {
        Incomplete, ChecksumMismatch, WrongPackage, NotNewer, SignatureMismatch,
    }

    /**
     * Full post-download ladder, cheapest first. [assetSha256] is the
     * lowercase hex digest from the GitHub asset's `digest` field; null (older
     * releases predating that API field) skips only the checksum rung — the
     * structural rungs still gate.
     */
    suspend fun validateDownloaded(
        context: Context,
        apk: File,
        assetSize: Long,
        assetSha256: String?,
    ): ValidationFailure? = withContext(Dispatchers.IO) {
        if (apk.length() != assetSize) return@withContext ValidationFailure.Incomplete
        if (assetSha256 != null && PackIntegrity.sha256Hex(apk) != assetSha256) {
            return@withContext ValidationFailure.ChecksumMismatch
        }
        validateStructural(context, apk)
    }

    /**
     * The archive-identity rungs: parses + verifies the APK's signing block
     * (whole-file work — call from Dispatchers.IO) and checks package name,
     * version direction, and cert match against this build. Also the cheap
     * re-validation for an APK that already passed [validateDownloaded] in a
     * previous process — private cache dir, so no checksum re-hash.
     */
    private fun validateStructural(context: Context, apk: File): ValidationFailure? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return ValidationFailure.WrongPackage
        if (info.packageName != context.packageName) return ValidationFailure.WrongPackage
        if (info.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return ValidationFailure.NotNewer
        }
        val archiveSigner = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: return ValidationFailure.SignatureMismatch
        val archiveCert = MessageDigest.getInstance("SHA-256")
            .digest(archiveSigner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (archiveCert != ownCertSha256(context)) return ValidationFailure.SignatureMismatch
        return null
    }

    /** Re-check for the resume path (process died between validation and
     *  install): returns the cached APK for [tag] if it still validates
     *  structurally, else null. Call from Dispatchers.IO. */
    fun validateCachedStructural(context: Context, tag: String): File? {
        val apk = apkFileFor(context, tag)
        if (!apk.isFile) return null
        return if (validateStructural(context, apk) == null) apk else null
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Settings screen for the "install unknown apps" grant. NOTE: flipping
     *  that toggle may kill this process (it's an AppOp change) — persist any
     *  state you need BEFORE sending the user there. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /** Hands the validated APK to the system package installer (confirm sheet
     *  included). ACTION_VIEW rather than ACTION_INSTALL_PACKAGE — the latter
     *  is deprecated from API 29, our minSdk. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "com.playtranslate.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
