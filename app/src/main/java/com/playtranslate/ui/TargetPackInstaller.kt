package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.playtranslate.R
import com.playtranslate.preloadMlKitFallbackModels
import com.playtranslate.translation.bergamot.BergamotWarmup
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.translation.llm.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Downloads the target-gloss pack (if the catalog has one and it isn't
 * installed yet) plus the ML Kit translation models needed for a given
 * source → target pair, streaming progress through [InstallerUi]; on
 * completion calls [installAndLoad]'s `onSuccess` callback on the main
 * thread.
 *
 * Host-agnostic: the Activity hosts ([LanguageSetupActivity]'s target list,
 * [com.playtranslate.MainActivity]'s welcome Continue) use the
 * (activity, scope) constructor — bit-for-bit the old behavior — while the
 * floating workspace supplies its own in-window [InstallerUi]. The helper
 * intentionally does NOT write [com.playtranslate.Prefs.targetLang] — the
 * caller is responsible for committing prefs and deciding what to do next
 * (finish activity vs. advance onboarding vs. dismiss the workspace).
 *
 * Cancellation is silent — if the user cancels the progress popup, the job
 * is cancelled, the popup dismisses, and no callback fires. Errors surface
 * via [InstallerUi.error]; the caller's onSuccess simply doesn't fire.
 */
class TargetPackInstaller(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ui: InstallerUi,
) {
    /** The host seam: progress/error surfaces for the install flow. */
    interface InstallerUi {
        /** Progress popup; [onDismiss] must cancel the in-flight install. */
        fun progress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress
        fun error(reason: String)
        fun toast(text: String)
    }

    /** Activity-host behavior, unchanged: OverlayProgress rides the
     *  foreground activity's decor, errors are an AlertDialog, toasts are
     *  plain toasts. */
    constructor(activity: Activity, scope: CoroutineScope) : this(
        activity,
        scope,
        object : InstallerUi {
            override fun progress(
                title: String,
                onDismiss: (DismissReason) -> Unit,
            ): OverlayProgress =
                OverlayProgress.Builder(activity)
                    .setTitle(title)
                    .setOnDismiss(onDismiss)
                    .show()

            override fun error(reason: String) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.lang_download_error_title)
                    .setMessage(reason)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

            override fun toast(text: String) {
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
            }
        },
    )

    private val mainHandler = Handler(Looper.getMainLooper())
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

        val targetName = Locale.forLanguageTag(targetCode).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val needsTargetPack = targetCode != "en"
            && LanguagePackCatalogLoader.entryForKey(context, "target-$targetCode") != null
            && !LanguagePackStore.isTargetInstalled(context, targetCode)

        val dialog = ui.progress(targetName) { activeJob?.cancel() }

        if (needsTargetPack) {
            dialog.setMessage(context.getString(R.string.install_downloading_definitions))
            dialog.setProgress(0)
            activeJob = scope.launch {
                val result = LanguagePackStore.installTarget(
                    context.applicationContext, targetCode
                ) { progress ->
                    if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
                        val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
                        mainHandler.post {
                            dialog.setProgress(pct)
                            dialog.setMessage(
                                context.getString(
                                    R.string.install_downloading_definitions_with_bytes,
                                    humanSize(context, progress.bytesReceived),
                                    humanSize(context, progress.totalBytes)
                                )
                            )
                        }
                    }
                }
                when (result) {
                    is InstallResult.Success -> {
                        mainHandler.post {
                            dialog.setMessage(context.getString(R.string.lang_setup_preloading_message))
                            dialog.setIndeterminate(true)
                        }
                        runLoadThenFinish(dialog, sourceLangCode, targetCode, onSuccess)
                    }
                    is InstallResult.Failed -> {
                        dialog.dismiss()
                        ui.error(result.reason)
                    }
                    is InstallResult.Cancelled -> dialog.dismiss()
                }
            }
        } else {
            dialog.setMessage(context.getString(R.string.install_downloading_translation_model))
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
            // Best-effort: a failed ML Kit fallback download must not block the
            // language — the gloss pack is already installed and the online /
            // on-device backends don't need ML Kit. See [preloadMlKitFallbackModels].
            val mlKitReady = withContext(Dispatchers.IO) {
                // Prefer Bergamot (the default offline tier): download its model
                // for this pair and skip ML Kit on success. Falls back to ML Kit
                // for unsupported pairs / download failures.
                val warmed = BergamotWarmup.ensureForPair(
                    context, sourceLangCode, targetCode
                ) { i, n, recv, total ->
                    mainHandler.post {
                        dialog.showBergamotWarmupProgress(context, i, n, recv, total)
                    }
                }
                if (warmed) true
                else preloadMlKitFallbackModels(sourceLangCode, targetCode)
            }
            dialog.dismiss()
            if (!mlKitReady) {
                ui.toast(context.getString(R.string.lang_setup_offline_model_unavailable))
            }
            onSuccess()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // User tapped Cancel — dialog already dismissed, silent.
        } catch (e: Exception) {
            dialog.dismiss()
            ui.error(e.message ?: "Failed to download translation model")
        }
    }
}
