package com.playtranslate.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.playtranslate.BuildConfig
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.UpdateChecker
import com.playtranslate.language.LanguagePackDownloader
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.update.ApkUpdateManager
import com.playtranslate.update.ApkUpdateManager.ValidationFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the in-app update flow: the "update available" dialog, the APK
 * download (OverlayProgress + lifecycleScope, cloned from
 * [OfflineModelInstallController]'s shape), the [ApkUpdateManager] validation
 * ladder, and the hand-off to the system package installer.
 *
 * Builds not signed with the release cert (debug keystores on other machines,
 * self-built forks) can't install the release APK over themselves, so they
 * keep the browser-only dialog — see
 * [ApkUpdateManager.ownSignatureIsReleaseCert].
 *
 * The install hand-off can outlive this process: granting the
 * "install unknown apps" AppOp may kill the app, and the user can abandon the
 * system confirm sheet. `prefs.updateDownloadedTag` (set only after the full
 * validation ladder passes) plus [resumeIfPending] bridge those gaps — the
 * next resume relaunches the system installer for the already-downloaded APK
 * automatically instead of re-downloading 128 MB. Tapping Download & install
 * is the opt-in; the system confirm sheet stays as the final gate, so no
 * extra in-app confirmation sits between grant and install.
 */
class UpdateInstallController(private val activity: AppCompatActivity) {

    private val prefs = Prefs(activity)
    private var downloadJob: Job? = null

    /** Set when the user detours to the release page from the update dialog.
     *  OverlayAlert dismisses on host-activity pause (browser in front) and
     *  the 24h debounce would block a re-offer, so [reshowIfPending] re-shows
     *  the dialog once they come back — reading the notes then tapping
     *  Download must not cost a day's wait. */
    private var pendingReshow: UpdateChecker.Release? = null

    /**
     * Entry point from the debounced update check. Resumes the installer
     * hand-off directly when this tag's APK is already downloaded + validated
     * (the user opted in by tapping Download & install; the system confirm
     * sheet remains the final gate), else shows the update dialog (download
     * button only when this build can actually self-update).
     */
    suspend fun promptUpdate(release: UpdateChecker.Release) {
        if (prefs.updateDownloadedTag == release.tag) {
            val cached = withContext(Dispatchers.IO) {
                ApkUpdateManager.validateCachedStructural(activity, release.tag)
            }
            if (cached != null) {
                fireInstall(cached)
                return
            }
            prefs.updateDownloadedTag = ""
        }

        val canSelfUpdate = release.apkUrl != null && release.apkSize > 0 &&
            ApkUpdateManager.ownSignatureIsReleaseCert(activity)
        Log.i(
            TAG,
            "offering ${release.tag}: apkAsset=${release.apkUrl != null} selfUpdate=$canSelfUpdate",
        )

        val message = buildString {
            append(activity.getString(R.string.update_dialog_message, release.tag))
            if (canSelfUpdate) {
                append("\n\n")
                append(
                    activity.getString(
                        R.string.update_dialog_size_note, humanSize(activity, release.apkSize),
                    ),
                )
                if (OnDeviceLlmDownloader.isMetered(activity)) {
                    append('\n')
                    append(activity.getString(R.string.update_dialog_metered_note))
                }
            }
        }

        val builder = OverlayAlert.Builder(activity)
            .hideIcon()
            .setTitle(activity.getString(R.string.update_dialog_title))
            .setMessage(message)
        if (canSelfUpdate) {
            builder.addButton(
                activity.getString(R.string.update_dialog_download),
                activity.themeColor(R.attr.ptAccent),
                leadingIconRes = R.drawable.ic_download,
            ) {
                start(release)
            }
            builder.addButton(
                activity.getString(R.string.update_dialog_view_release),
                activity.themeColor(R.attr.ptDivider),
                activity.themeColor(R.attr.ptText),
            ) {
                pendingReshow = release
                openReleasePage(release.url)
            }
        } else {
            builder.addButton(
                activity.getString(R.string.update_dialog_view_release),
                activity.themeColor(R.attr.ptAccent),
            ) {
                openReleasePage(release.url)
            }
        }
        builder
            .addButton(
                activity.getString(R.string.update_dialog_skip),
                activity.themeColor(R.attr.ptDivider),
                activity.themeColor(R.attr.ptDanger),
            ) {
                prefs.updateCheckSkippedTag = release.tag
            }
            .addCancelButton(activity.getString(R.string.update_dialog_ask_again_later))
            .show()
    }

    /**
     * Flow recovery: when a validated APK is still pending (the
     * unknown-sources grant bounced us to Settings and possibly killed the
     * process, or the user abandoned the installer sheet), resume the
     * installer hand-off automatically — the user already opted in by
     * tapping Download & install, and the system confirm sheet remains the
     * final gate. Returns true when it took this resume cycle — the caller
     * then skips the regular update check so surfaces don't stack. Runs at
     * most once per process; after that the regular (24h-debounced) check
     * path routes back here via [promptUpdate]'s cached-tag branch.
     */
    fun resumeIfPending(): Boolean {
        val tag = prefs.updateDownloadedTag
        if (tag.isEmpty()) return false
        if (!UpdateChecker.isNewer(tag, BuildConfig.VERSION_NAME)) {
            // The pending update was installed (we're now running it) — or the
            // tag is stale garbage. Either way the cached APK is done.
            prefs.updateDownloadedTag = ""
            activity.lifecycleScope.launch(Dispatchers.IO) {
                ApkUpdateManager.cleanupAll(activity)
            }
            return false
        }
        if (promptedThisProcess) return false
        promptedThisProcess = true
        activity.lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                ApkUpdateManager.validateCachedStructural(activity, tag)
            }
            if (apk == null) {
                prefs.updateDownloadedTag = ""
                withContext(Dispatchers.IO) { ApkUpdateManager.cleanupAll(activity) }
            } else {
                fireInstall(apk)
            }
        }
        return true
    }

    /** Re-shows the update dialog after a "View release" browser detour (see
     *  [pendingReshow]). Returns true when it took this resume cycle. */
    fun reshowIfPending(): Boolean {
        val release = pendingReshow ?: return false
        pendingReshow = null
        activity.lifecycleScope.launch { promptUpdate(release) }
        return true
    }

    /** Download + validate + install. Single-flight; re-entry while a
     *  download is live is a no-op. */
    private fun start(release: UpdateChecker.Release) {
        if (downloadJob?.isActive == true) return
        val apkUrl = release.apkUrl ?: return
        val apk = ApkUpdateManager.apkFileFor(activity, release.tag)

        val progress = OverlayProgress.Builder(activity)
            .setTitle(activity.getString(R.string.update_progress_title))
            .setMessage(
                activity.getString(
                    R.string.update_progress_downloading,
                    humanSize(activity, 0L), humanSize(activity, release.apkSize),
                ),
            )
            .setProgress(0)
            // Cancel stops the transfer but KEEPS the partial file (diverges
            // from OfflineModelInstallController): release-asset URLs are
            // immutable per tag, so a later attempt Range-resumes instead of
            // re-pulling 128 MB. sweepStale() reclaims it once superseded.
            .setOnDismiss { downloadJob?.cancel() }
            .show()

        downloadJob = activity.lifecycleScope.launch {
            try {
                val needed = withContext(Dispatchers.IO) {
                    ApkUpdateManager.sweepStale(activity, release.tag)
                    ApkUpdateManager.preflightStorage(activity, release.apkSize, apk.length())
                }
                if (needed != null) {
                    progress.dismiss()
                    showNoSpaceError(needed, release)
                    return@launch
                }

                // Mihon-style throttle: repaint only when the integer percent
                // advances and ≥200ms passed — 64KB chunks would otherwise
                // spam the main thread ~2000×.
                var lastPct = -1
                var lastTick = 0L
                LanguagePackDownloader().download(
                    url = apkUrl,
                    destination = apk,
                    maxBytes = release.apkSize,
                ) { p ->
                    val pct = if (p.totalBytes > 0) {
                        ((p.bytesReceived * 100) / p.totalBytes).toInt()
                    } else 0
                    val now = System.currentTimeMillis()
                    if (pct > lastPct && now - lastTick >= 200) {
                        lastPct = pct
                        lastTick = now
                        activity.runOnUiThread {
                            progress.setProgress(pct)
                            progress.setMessage(
                                activity.getString(
                                    R.string.update_progress_downloading,
                                    humanSize(activity, p.bytesReceived), humanSize(activity, p.totalBytes),
                                ),
                            )
                        }
                    }
                }

                progress.setMessage(activity.getString(R.string.update_progress_verifying))
                progress.setIndeterminate(true)
                val failure = ApkUpdateManager.validateDownloaded(
                    activity, apk, release.apkSize, release.apkSha256,
                )
                if (failure != null) {
                    Log.w(TAG, "downloaded ${release.tag} failed validation: $failure")
                    withContext(Dispatchers.IO) { apk.delete() }
                    progress.dismiss()
                    showValidationError(failure, release)
                    return@launch
                }

                // Persist BEFORE the installer hand-off: the unknown-sources
                // grant can kill this process, and resumeIfPending() needs the
                // tag to re-offer the APK without another download.
                prefs.updateDownloadedTag = release.tag
                progress.dismiss()
                fireInstall(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                if (!activity.isFinishing) {
                    progress.dismiss()
                    // Transport failures get the same retry UX as a truncated
                    // file — the partial is kept, so Try again resumes.
                    showValidationError(ValidationFailure.Incomplete, release)
                }
            } finally {
                progress.dismiss()
                downloadJob = null
            }
        }
    }

    /** Hand the validated APK to the system installer, detouring through the
     *  "install unknown apps" settings screen when the grant is missing. */
    private fun fireInstall(apk: File) {
        if (!ApkUpdateManager.canInstall(activity)) {
            OverlayAlert.Builder(activity)
                .hideIcon()
                .setTitle(activity.getString(R.string.update_unknown_sources_title))
                .setMessage(activity.getString(R.string.update_unknown_sources_message))
                .addButton(
                    activity.getString(R.string.update_unknown_sources_button),
                    activity.themeColor(R.attr.ptAccent),
                ) {
                    try {
                        activity.startActivity(
                            ApkUpdateManager.unknownSourcesSettingsIntent(activity),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "unknown-sources settings launch failed", e)
                    }
                }
                .addCancelButton(activity.getString(R.string.update_dialog_ask_again_later))
                .show()
            return
        }
        try {
            activity.startActivity(ApkUpdateManager.installIntent(activity, apk))
        } catch (e: Exception) {
            Log.w(TAG, "installer launch failed", e)
            Toast.makeText(
                activity,
                activity.getString(R.string.update_error_install_launch),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showValidationError(failure: ValidationFailure, release: UpdateChecker.Release) {
        val messageRes = when (failure) {
            ValidationFailure.Incomplete -> R.string.update_error_incomplete
            ValidationFailure.ChecksumMismatch -> R.string.update_error_verification
            ValidationFailure.WrongPackage -> R.string.update_error_wrong_package
            ValidationFailure.NotNewer -> R.string.update_error_downgrade
            ValidationFailure.SignatureMismatch -> R.string.update_error_signature
        }
        val retryable = failure == ValidationFailure.Incomplete ||
            failure == ValidationFailure.ChecksumMismatch
        val builder = OverlayAlert.Builder(activity)
            .hideIcon()
            .setTitle(activity.getString(R.string.update_error_title))
            .setMessage(activity.getString(messageRes))
        if (retryable) {
            builder.addButton(
                activity.getString(R.string.update_error_retry),
                activity.themeColor(R.attr.ptAccent),
            ) {
                start(release)
            }
        }
        builder
            .addButton(
                activity.getString(R.string.update_dialog_view_release),
                if (retryable) activity.themeColor(R.attr.ptDivider) else activity.themeColor(R.attr.ptAccent),
                if (retryable) activity.themeColor(R.attr.ptText) else activity.themeColor(R.attr.ptCard),
            ) {
                openReleasePage(release.url)
            }
            .addCancelButton()
            .show()
    }

    private fun showNoSpaceError(neededBytes: Long, release: UpdateChecker.Release) {
        OverlayAlert.Builder(activity)
            .hideIcon()
            .setTitle(activity.getString(R.string.update_error_title))
            .setMessage(
                activity.getString(
                    R.string.update_error_no_space, humanSize(activity, neededBytes),
                ),
            )
            .addButton(
                activity.getString(R.string.update_error_retry),
                activity.themeColor(R.attr.ptAccent),
            ) {
                start(release)
            }
            .addButton(
                activity.getString(R.string.update_dialog_view_release),
                activity.themeColor(R.attr.ptDivider),
                activity.themeColor(R.attr.ptText),
            ) {
                openReleasePage(release.url)
            }
            .addCancelButton()
            .show()
    }

    private fun openReleasePage(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_no_browser_available),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    companion object {
        private const val TAG = "UpdateInstall"

        /** Process-wide so an activity recreation doesn't re-prompt. */
        private var promptedThisProcess = false
    }
}
