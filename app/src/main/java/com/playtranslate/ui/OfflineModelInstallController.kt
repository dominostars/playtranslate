package com.playtranslate.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.ModelHelper
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.translation.mnn.MnnTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the offline-model install flows for [TranslationServicesActivity].
 *
 * The four MNN tiers (Qwen-MNN, Qwen-3.5, Gemma-E2B, Hunyuan-MT) share ONE
 * descriptor-driven flow — an [OfflineModel] descriptor plus [download] /
 * [enableInstalled] / [disable] — replacing the three near-identical bespoke
 * copies the old SettingsBottomSheet carried. Hunyuan-MT adds its one-time
 * legal attestation via the optional [OfflineModel.legal] gate.
 *
 * Bergamot is a deliberate sibling, not a descriptor: its per-language-pair,
 * multi-direction install, flat RAM floor, and per-direction eviction don't fit
 * the MNN descriptor, so it keeps dedicated methods exposing the same surface.
 *
 * OverlayProgress/OverlayAlert attach to the activity decorView and the download
 * jobs run on the activity lifecycleScope with an idempotent finally-dismiss —
 * the same invariants the flows had as Fragment methods. Switch refreshes +
 * status re-renders route through [binder].
 */
class OfflineModelInstallController(
    private val activity: AppCompatActivity,
    private val binder: TranslationServicesBinder,
) {

    /** One MNN offline tier. [setEnabled] writes the per-backend enabled pref;
     *  [refreshSwitch] reverts the row's optimistic flip on cancel. */
    class OfflineModel(
        val backendId: String,
        val model: ModelHelper,
        val setEnabled: (Context, Boolean) -> Unit,
        val refreshSwitch: () -> Unit,
        @StringRes val displayName: Int,
        @StringRes val statusDownloading: Int,
        @StringRes val statusVerifying: Int,
        @StringRes val downloadFailed: Int,
        @StringRes val meteredTitle: Int,
        @StringRes val meteredMessage: Int,
        @StringRes val disableTitle: Int,
        @StringRes val disableMessage: Int,
        @StringRes val disableKeep: Int,
        @StringRes val disableDelete: Int,
        /** Optional one-time pre-download attestation (Hunyuan-MT only). */
        val legal: LegalGate? = null,
    )

    /** A one-time pre-download attestation persisted in [accepted]/[accept]. */
    class LegalGate(
        val accepted: (Context) -> Boolean,
        val accept: (Context) -> Unit,
        @StringRes val title: Int,
        @StringRes val message: Int,
        @StringRes val agree: Int,
        @StringRes val cancel: Int,
    )

    val qwenMnn = OfflineModel(
        backendId = "qwen_mnn",
        model = com.playtranslate.translation.qwen.QwenMnnModel,
        setEnabled = { c, v -> Prefs(c).qwenMnnEnabled = v },
        refreshSwitch = { binder.refreshQwenMnnSwitch() },
        displayName = R.string.qwen_mnn_display_name,
        statusDownloading = R.string.qwen_mnn_status_downloading,
        statusVerifying = R.string.qwen_mnn_status_verifying,
        downloadFailed = R.string.qwen_mnn_download_failed,
        meteredTitle = R.string.qwen_mnn_metered_warning_title,
        meteredMessage = R.string.qwen_mnn_metered_warning_message,
        disableTitle = R.string.qwen_mnn_disable_title,
        disableMessage = R.string.qwen_mnn_disable_message,
        disableKeep = R.string.qwen_mnn_disable_keep,
        disableDelete = R.string.qwen_mnn_disable_delete,
    )

    val qwen35 = OfflineModel(
        backendId = "qwen35_mnn_2b",
        model = com.playtranslate.translation.qwen.Qwen35Mnn2bModel,
        setEnabled = { c, v -> Prefs(c).qwen35Mnn2bEnabled = v },
        refreshSwitch = { binder.refreshQwen35Mnn2bSwitch() },
        displayName = R.string.qwen35_2b_mnn_display_name,
        statusDownloading = R.string.qwen35_2b_mnn_status_downloading,
        statusVerifying = R.string.qwen35_2b_mnn_status_verifying,
        downloadFailed = R.string.qwen35_2b_mnn_download_failed,
        meteredTitle = R.string.qwen35_2b_mnn_metered_warning_title,
        meteredMessage = R.string.qwen35_2b_mnn_metered_warning_message,
        disableTitle = R.string.qwen35_2b_mnn_disable_title,
        disableMessage = R.string.qwen35_2b_mnn_disable_message,
        disableKeep = R.string.qwen35_2b_mnn_disable_keep,
        disableDelete = R.string.qwen35_2b_mnn_disable_delete,
    )

    val gemma = OfflineModel(
        backendId = "gemma_e2b_mnn",
        model = com.playtranslate.translation.gemma.GemmaE2BMnnModel,
        setEnabled = { c, v -> Prefs(c).gemmaE2bEnabled = v },
        refreshSwitch = { binder.refreshGemmaE2bSwitch() },
        displayName = R.string.gemma_e2b_mnn_display_name,
        statusDownloading = R.string.gemma_e2b_mnn_status_downloading,
        statusVerifying = R.string.gemma_e2b_mnn_status_verifying,
        downloadFailed = R.string.gemma_e2b_mnn_download_failed,
        meteredTitle = R.string.gemma_e2b_mnn_metered_warning_title,
        meteredMessage = R.string.gemma_e2b_mnn_metered_warning_message,
        disableTitle = R.string.gemma_e2b_mnn_disable_title,
        disableMessage = R.string.gemma_e2b_mnn_disable_message,
        disableKeep = R.string.gemma_e2b_mnn_disable_keep,
        disableDelete = R.string.gemma_e2b_mnn_disable_delete,
    )

    val hymt = OfflineModel(
        backendId = "hymt_mnn",
        model = com.playtranslate.translation.hymt.HyMtModel,
        setEnabled = { c, v -> Prefs(c).hyMtEnabled = v },
        refreshSwitch = { binder.refreshHyMtSwitch() },
        displayName = R.string.hymt_display_name,
        statusDownloading = R.string.hymt_status_downloading,
        statusVerifying = R.string.hymt_status_verifying,
        downloadFailed = R.string.hymt_download_failed,
        meteredTitle = R.string.hymt_metered_warning_title,
        meteredMessage = R.string.hymt_metered_warning_message,
        disableTitle = R.string.hymt_disable_title,
        disableMessage = R.string.hymt_disable_message,
        disableKeep = R.string.hymt_disable_keep,
        disableDelete = R.string.hymt_disable_delete,
        legal = LegalGate(
            accepted = { Prefs(it).hyMtLegalAccepted },
            accept = { Prefs(it).hyMtLegalAccepted = true },
            title = R.string.hymt_legal_title,
            message = R.string.hymt_legal_message,
            agree = R.string.hymt_legal_agree,
            cancel = R.string.hymt_legal_cancel,
        ),
    )

    private val downloadJobs = mutableMapOf<String, Job?>()

    // ── Generic MNN flow ─────────────────────────────────────────────────

    /** Tap the row when [m] is installed but off — gate on available memory,
     *  then enable (with a delete-instead option in the low-memory alert). */
    fun enableInstalled(m: OfflineModel) {
        val backend = TranslationBackendRegistry.byId(m.backendId) as? OnDeviceLlmBackend ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = activity.getString(m.displayName),
            onProceed = { m.setEnabled(activity, true) },
            allowDelete = true,
            onDelete = {
                m.model.delete(activity)
                activity.lifecycleScope.launch {
                    MnnTranslator.getInstance(activity).unloadModel()
                }
                binder.refreshAllBackendStatuses()
            },
            onCancel = { m.refreshSwitch() },
        )
    }

    /** Tap the row when [m] isn't installed — availMem gate, then (HyMt) the
     *  legal attestation, then the metered-network warning, then download. */
    fun download(m: OfflineModel) {
        if (isDownloadInFlight(downloadJobs[m.backendId], m.backendId)) return
        val backend = TranslationBackendRegistry.byId(m.backendId) as? OnDeviceLlmBackend ?: return
        val downloader = OnDeviceLlmDownloader(
            context = activity,
            modelHelper = m.model,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = activity.getString(m.displayName),
            onProceed = { withLegalGate(m) { showDownloadDialogPostGate(downloader, m) } },
        )
    }

    private fun withLegalGate(m: OfflineModel, proceed: () -> Unit) {
        val legal = m.legal
        if (legal == null || legal.accepted(activity)) {
            proceed()
            return
        }
        OverlayAlert.Builder(activity)
            .setTitle(activity.getString(legal.title))
            .setMessage(activity.getString(legal.message))
            .hideIcon()
            .addButton(activity.getString(legal.agree), activity.themeColor(R.attr.ptAccent)) {
                legal.accept(activity)
                proceed()
            }
            .addCancelButton(activity.getString(legal.cancel)) { m.refreshSwitch() }
            .show()
    }

    private fun showDownloadDialogPostGate(downloader: OnDeviceLlmDownloader, m: OfflineModel) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = m.model.humanSize(activity)
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(m.meteredTitle)
                .setMessage(activity.getString(m.meteredMessage, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ -> runDownload(downloader, m) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runDownload(downloader, m)
    }

    private fun runDownload(downloader: OnDeviceLlmDownloader, m: OfflineModel) {
        val sizeStr = m.model.humanSize(activity)
        val progressDialog = OverlayProgress.Builder(activity)
            .setTitle(activity.getString(m.displayName))
            .setMessage(activity.getString(m.statusDownloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnDismiss { reason ->
                downloadJobs[m.backendId]?.cancel()
                if (reason == DismissReason.USER) downloader.deletePartial()
                binder.refreshAllBackendStatuses()
            }
            .show()

        binder.setBackendDownloading(m.backendId, true)
        downloadJobs[m.backendId] = activity.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    activity.runOnUiThread {
                        when (progress) {
                            is OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = humanSize(progress.received)
                                val total = humanSize(progress.total)
                                progressDialog.setMessage(activity.getString(m.statusDownloading, recv, total))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(((progress.received * 100) / progress.total).toInt())
                                }
                            }
                            is OnDeviceLlmDownloader.Progress.Verifying -> {
                                progressDialog.setMessage(activity.getString(m.statusVerifying))
                                progressDialog.setProgress(100)
                            }
                            is OnDeviceLlmDownloader.Progress.Extracting -> {
                                progressDialog.setMessage(activity.getString(R.string.model_download_extracting))
                                progressDialog.setProgress(100)
                            }
                        }
                    }
                }
                if (activity.isFinishing) return@launch
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is OnDeviceLlmDownloader.Outcome.Success -> {
                            m.setEnabled(activity, true)
                            binder.refreshAllBackendStatuses()
                        }
                        is OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                activity, activity.getString(m.downloadFailed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            binder.refreshAllBackendStatuses()
                        }
                        is OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                activity, activity.getString(m.downloadFailed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            binder.refreshAllBackendStatuses()
                        }
                        is OnDeviceLlmDownloader.Outcome.Cancelled -> binder.refreshAllBackendStatuses()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!activity.isFinishing) {
                    activity.runOnUiThread {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(
                            activity,
                            activity.getString(m.downloadFailed, e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        binder.refreshAllBackendStatuses()
                    }
                }
            } finally {
                progressDialog.dismiss()
                binder.setBackendDownloading(m.backendId, false)
                downloadJobs[m.backendId] = null
            }
        }
    }

    fun disable(m: OfflineModel) {
        val sizeStr = m.model.humanSize(activity)
        OverlayAlert.Builder(activity)
            .setTitle(activity.getString(m.disableTitle))
            .setMessage(activity.getString(m.disableMessage, sizeStr))
            .hideIcon()
            .addButton(activity.getString(m.disableKeep), activity.themeColor(R.attr.ptAccent)) {
                m.setEnabled(activity, false)
            }
            .addButton(
                activity.getString(m.disableDelete),
                activity.themeColor(R.attr.ptDivider),
                activity.themeColor(R.attr.ptDanger),
            ) {
                m.setEnabled(activity, false)
                m.model.delete(activity)
                activity.lifecycleScope.launch {
                    MnnTranslator.getInstance(activity).unloadModel()
                }
                binder.refreshAllBackendStatuses()
            }
            .addCancelButton { m.refreshSwitch() }
            .show()
    }

    private fun checkAvailMemAndProceed(
        backend: OnDeviceLlmBackend,
        modelDisplayName: String,
        onProceed: () -> Unit,
        allowDelete: Boolean = false,
        onDelete: (() -> Unit)? = null,
        onCancel: () -> Unit = {},
    ) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem >= backend.availMemFloorBytes) {
            onProceed()
            return
        }
        val needStr = formatGb(backend.availMemFloorBytes)
        val freeStr = formatGb(mi.availMem)
        val builder = OverlayAlert.Builder(activity)
            .setTitle(activity.getString(R.string.llm_low_memory_title))
            .setMessage(activity.getString(R.string.llm_low_memory_message, modelDisplayName, needStr, freeStr))
            .hideIcon()
            .addButton(activity.getString(R.string.llm_low_memory_recheck), activity.themeColor(R.attr.ptAccent)) {
                checkAvailMemAndProceed(backend, modelDisplayName, onProceed, allowDelete, onDelete, onCancel)
            }
        if (allowDelete && onDelete != null) {
            builder.addButton(
                activity.getString(R.string.llm_low_memory_delete),
                activity.themeColor(R.attr.ptDanger),
                android.graphics.Color.WHITE,
            ) {
                onDelete()
                onCancel()
            }
        }
        builder.addCancelButton(activity.getString(R.string.llm_low_memory_cancel)) { onCancel() }
        builder.show()
    }

    private fun isDownloadInFlight(job: Job?, label: String): Boolean {
        if (job?.isActive == true) {
            android.util.Log.d("OfflineModelInstall", "$label download already in flight; ignoring tap")
            return true
        }
        return false
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB" else "%.1f GB".format(gb)
    }

    // ── Bergamot sibling (per-pair NMT, no descriptor) ───────────────────

    private var bergamotDownloadJob: Job? = null
    private val bergamotMemFloorBytes = 700_000_000L

    fun enableInstalledBergamot() {
        Prefs(activity).bergamotEnabled = true // SP listener refreshes the row
    }

    fun downloadBergamot() {
        if (isDownloadInFlight(bergamotDownloadJob, "Bergamot")) return
        val manager = com.playtranslate.translation.bergamot.BergamotModelManager(activity)
        val source = SourceLanguageProfiles[Prefs(activity).sourceLangId].translationCode
        val target = Prefs(activity).targetLang
        val dirs = manager.requiredDirections(source, target)
        if (dirs == null) { binder.refreshBergamotSwitch(); return }

        val progressDialog = OverlayProgress.Builder(activity)
            .setTitle(activity.getString(R.string.bergamot_row_title))
            .setMessage(activity.getString(R.string.bergamot_status_downloading, "0 B", "…"))
            .setProgress(0)
            .setOnDismiss {
                bergamotDownloadJob?.cancel()
                binder.refreshAllBackendStatuses()
            }
            .show()

        binder.setBackendDownloading("bergamot", true)
        bergamotDownloadJob = activity.lifecycleScope.launch {
            try {
                var outcome: OnDeviceLlmDownloader.Outcome = OnDeviceLlmDownloader.Outcome.Success
                for (dir in dirs) {
                    val helper = com.playtranslate.translation.bergamot.BergamotModel(dir)
                    if (helper.isInstalled(activity)) continue
                    val downloader = OnDeviceLlmDownloader(
                        context = activity,
                        modelHelper = helper,
                        totalMemFloorBytes = bergamotMemFloorBytes,
                    )
                    outcome = downloader.run { progress ->
                        if (progress is OnDeviceLlmDownloader.Progress.Downloading) {
                            val recv = humanSize(progress.received)
                            val total = humanSize(progress.total)
                            activity.runOnUiThread {
                                progressDialog.setMessage(activity.getString(R.string.bergamot_status_downloading, recv, total))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(((progress.received * 100) / progress.total).toInt())
                                }
                            }
                        }
                    }
                    if (outcome !is OnDeviceLlmDownloader.Outcome.Success) break
                }
                if (activity.isFinishing) return@launch
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is OnDeviceLlmDownloader.Outcome.Success ->
                            if (manager.isInstalled(source, target)) Prefs(activity).bergamotEnabled = true
                        is OnDeviceLlmDownloader.Outcome.Cancelled -> { /* user bailed */ }
                        else -> android.widget.Toast.makeText(
                            activity, R.string.bergamot_download_failed, android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    binder.refreshAllBackendStatuses()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!activity.isFinishing) activity.runOnUiThread {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        activity, R.string.bergamot_download_failed, android.widget.Toast.LENGTH_LONG,
                    ).show()
                    binder.refreshAllBackendStatuses()
                }
            } finally {
                progressDialog.dismiss()
                binder.setBackendDownloading("bergamot", false)
                bergamotDownloadJob = null
            }
        }
    }

    fun disableBergamot() {
        OverlayAlert.Builder(activity)
            .setTitle(activity.getString(R.string.bergamot_disable_title))
            .setMessage(activity.getString(R.string.bergamot_disable_message))
            .hideIcon()
            .addButton(activity.getString(R.string.bergamot_disable_keep), activity.themeColor(R.attr.ptAccent)) {
                Prefs(activity).bergamotEnabled = false
            }
            .addButton(
                activity.getString(R.string.bergamot_disable_delete),
                activity.themeColor(R.attr.ptDivider),
                activity.themeColor(R.attr.ptDanger),
            ) {
                Prefs(activity).bergamotEnabled = false
                val manager = com.playtranslate.translation.bergamot.BergamotModelManager(activity)
                val dirs = manager.requiredDirections(
                    SourceLanguageProfiles[Prefs(activity).sourceLangId].translationCode,
                    Prefs(activity).targetLang,
                )
                activity.lifecycleScope.launch {
                    dirs?.forEach { dir ->
                        com.playtranslate.translation.bergamot.BergamotModel(dir).delete(activity)
                        com.playtranslate.translation.bergamot.BergamotTranslator
                            .getInstance(activity).evictDirection(dir)
                    }
                    binder.refreshAllBackendStatuses()
                }
            }
            .addCancelButton { binder.refreshBergamotSwitch() }
            .show()
    }
}
