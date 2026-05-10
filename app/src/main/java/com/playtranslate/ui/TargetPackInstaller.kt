package com.playtranslate.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.playtranslate.R
import com.playtranslate.TranslationManager
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Downloads the target-gloss pack (if the catalog has one and it isn't
 * installed yet) plus the ML Kit translation models needed for a given
 * source → target pair. Shows the shared progress popup anchored to the
 * hosting [activity]; on completion calls [installAndLoad]'s `onSuccess`
 * callback on the main thread.
 *
 * Reused by both [LanguageSetupActivity] (when the user picks a target from
 * the list) and [com.playtranslate.MainActivity] (when the user hits
 * Continue on the welcome page with a computed-default target). The helper
 * intentionally does NOT write [com.playtranslate.Prefs.targetLang] — the
 * caller is responsible for committing prefs and deciding what to do next
 * (finish activity vs. advance onboarding).
 *
 * Cancellation is silent — if the user taps Cancel in the progress popup,
 * the job is cancelled, the popup dismisses, and no callback fires. Errors
 * surface via the standard lang-download-error AlertDialog; the caller's
 * onSuccess simply doesn't fire.
 */
class TargetPackInstaller(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {

    private var activeJob: Job? = null

    fun installAndLoad(
        sourceLangCode: String,
        targetCode: String,
        onSuccess: () -> Unit,
    ) {
        // Single-flight: if an install is already in progress, ignore the
        // re-entry. Protects against rapid double-taps on the welcome
        // page's Continue button (the install dialog's scrim blocks most
        // but not all double-taps in-frame) and similarly for picker rows.
        if (activeJob?.isActive == true) return

        val targetName = Locale(targetCode).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val needsTargetPack = targetCode != "en"
            && LanguagePackCatalogLoader.entryForKey(activity, "target-$targetCode") != null
            && !LanguagePackStore.isTargetInstalled(activity, targetCode)

        val dialog = buildPopupDialog(targetName)

        if (needsTargetPack) {
            dialog.setMessage("Downloading $targetName definitions")
            dialog.setProgress(0)
            activeJob = scope.launch {
                val result = LanguagePackStore.installTarget(
                    activity.applicationContext, targetCode
                ) { progress ->
                    if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
                        val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
                        activity.runOnUiThread { dialog.setProgress(pct) }
                    }
                }
                when (result) {
                    is InstallResult.Success -> {
                        activity.runOnUiThread {
                            dialog.setMessage("Loading $targetName")
                            dialog.setIndeterminate(true)
                        }
                        runLoadThenFinish(dialog, sourceLangCode, targetCode, onSuccess)
                    }
                    is InstallResult.Failed -> {
                        dialog.dismiss()
                        showErrorPopup(result.reason)
                    }
                    is InstallResult.Cancelled -> dialog.dismiss()
                }
            }
        } else {
            dialog.setMessage("Downloading $targetName")
            dialog.setIndeterminate(true)
            activeJob = scope.launch {
                runLoadThenFinish(dialog, sourceLangCode, targetCode, onSuccess)
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    private suspend fun runLoadThenFinish(
        dialog: OverlayProgress,
        sourceLangCode: String,
        targetCode: String,
        onSuccess: () -> Unit,
    ) {
        try {
            withContext(Dispatchers.IO) { ensureModels(sourceLangCode, targetCode) }
            dialog.dismiss()
            onSuccess()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // User tapped Cancel — dialog already dismissed, silent.
        } catch (e: Exception) {
            dialog.dismiss()
            showErrorPopup(e.message ?: "Failed to download translation model")
        }
    }

    private suspend fun ensureModels(sourceLangCode: String, targetCode: String) {
        val tm = TranslationManager(sourceLangCode, targetCode)
        try { tm.ensureModelReady() } finally { tm.close() }
        if (targetCode != "en") {
            // EN→target model for definition-translation fallback
            val enTm = TranslationManager("en", targetCode)
            try { enTm.ensureModelReady() } finally { enTm.close() }
        }
    }

    private fun buildPopupDialog(title: String): OverlayProgress =
        OverlayProgress.Builder(activity)
            .setTitle(title)
            .setOnCancel { activeJob?.cancel() }
            .showInActivity(activity)

    private fun showErrorPopup(reason: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.lang_download_error_title)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
