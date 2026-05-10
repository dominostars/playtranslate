package com.playtranslate.language

import android.app.Activity
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationManager
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.ui.OverlayProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Drives the user-facing pack-upgrade flow that fires after [LanguagePackStore.staleInstalledPacks]
 * returns a non-empty list at app launch (see `MainActivity.maybePromptForPackUpgrade`).
 *
 * Walks the stale list sequentially, presenting one [OverlayProgress] dialog
 * for the whole batch. Per-pack steps:
 *
 * 1. **Source-pack-only**: explicit `DictionaryManager.get(ctx).close()` —
 *    [com.playtranslate.dictionary.JapaneseEngine.close] is intentionally a
 *    no-op because `DictionaryManager` is a process-scoped singleton, so the
 *    SourceLanguageEngine eviction inside `uninstall` does NOT close the
 *    dict handle. Without this explicit close, after the directory is
 *    deleted and re-created, `DictionaryManager.db` still references the
 *    old (unlinked) inode and serves ghost results until process kill. The
 *    `instance` + `db = null` pattern in DictionaryManager already supports
 *    lazy reopen on next access.
 * 2. `LanguagePackStore.uninstall(...)` — already calls `releaseForPack`
 *    internally; do NOT pre-call it from here.
 * 3. `LanguagePackStore.install(...)` with progress callback updating the
 *    dialog's bar + byte-count message (mirrors [com.playtranslate.ui.TargetPackInstaller]).
 *
 * Target-pack steps mirror source: `uninstallTarget` (which internally
 * calls `TargetGlossDatabaseProvider.release`), then `installTarget`.
 *
 * After every pack reinstalls successfully, primes ML Kit translation
 * models for the user's currently-selected `(prefs.sourceLang, prefs.targetLang)`
 * pair plus the EN → target fallback (matches `TargetPackInstaller.ensureModels`).
 * This avoids the user hitting a second download surprise on first lookup.
 *
 * **Cancel semantics**: Cancel is enabled during the download phase
 * (single-flight, idempotent — `safeSwap` is per-pack atomic so partial
 * completion persists cleanly across pack boundaries). Cancel is disabled
 * during the ML-Kit priming phase (matches `TargetPackInstaller.kt:118-121`).
 * On mid-iteration cancel: completed packs stay installed, in-flight pack
 * rolls back via `LanguagePackStore.install`'s finally block, pending
 * packs not attempted, dialog dismisses. Next-launch scan re-fires for
 * whatever remained stale.
 */
class PackUpgradeOrchestrator(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {

    private var activeJob: Job? = null

    /**
     * Starts the upgrade flow for [stalePacks]. Single-flight: a re-entry
     * while already running is ignored.
     *
     * [onComplete] fires whether the flow succeeded, failed, or was
     * cancelled — callers use it to resume any deferred init they paused
     * to wait for the upgrade outcome (e.g., `MainActivity.onCreate`'s
     * onboarding setup runs from this callback regardless of outcome).
     */
    fun upgradeAll(stalePacks: List<StalePack>, onComplete: () -> Unit) {
        if (activeJob?.isActive == true) return
        if (stalePacks.isEmpty()) {
            onComplete()
            return
        }

        val dialog = OverlayProgress.Builder(activity)
            .setTitle(activity.getString(R.string.pack_upgrade_progress_title))
            .setOnCancel { activeJob?.cancel() }
            .showInActivity(activity)

        activeJob = scope.launch {
            val outcome = try {
                runUpgrade(stalePacks, dialog)
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                activity.runOnUiThread { dialog.dismiss() }
                Outcome.Cancelled
            }

            // Whatever outcome, surface to caller. Errors are shown via the
            // standard error dialog inline; success/cancel just dismiss.
            activity.runOnUiThread { onComplete() }

            if (outcome is Outcome.Failed) {
                showErrorPopup(outcome.reason)
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    private suspend fun runUpgrade(
        stalePacks: List<StalePack>,
        dialog: OverlayProgress,
    ): Outcome {
        for (pack in stalePacks) {
            val packLabel = labelFor(pack)
            activity.runOnUiThread {
                dialog.setProgress(0)
                dialog.setMessage(
                    activity.getString(R.string.pack_upgrade_progress_format, packLabel)
                )
            }

            val result: InstallResult = withContext(Dispatchers.IO) {
                when (pack.kind) {
                    PackKind.SOURCE -> upgradeSourcePack(pack, dialog, packLabel)
                    PackKind.TARGET -> upgradeTargetPack(pack, dialog, packLabel)
                }
            }

            when (result) {
                is InstallResult.Success -> { /* loop to next pack */ }
                is InstallResult.Failed -> return Outcome.Failed(
                    "Failed to install ${pack.displayName}: ${result.reason}"
                )
                is InstallResult.Cancelled -> {
                    activity.runOnUiThread { dialog.dismiss() }
                    return Outcome.Cancelled
                }
            }
        }

        // All packs upgraded — prime ML Kit so the user doesn't hit a
        // second download surprise on first lookup. Disable cancel during
        // this phase per the orchestrator's contract.
        activity.runOnUiThread {
            dialog.hideCancel()
            dialog.setIndeterminate(true)
            dialog.setMessage(activity.getString(R.string.pack_upgrade_priming_models))
        }
        try {
            withContext(Dispatchers.IO) { primeMlKit() }
        } catch (e: Exception) {
            // Priming failure isn't worth blocking on — the packs are
            // installed, the user can still use them. ML Kit will retry
            // lazily on first translate. Log and proceed.
            Log.w(TAG, "ML Kit priming failed (non-fatal): ${e.message}")
        }

        activity.runOnUiThread { dialog.dismiss() }
        return Outcome.Success
    }

    private suspend fun upgradeSourcePack(
        pack: StalePack,
        dialog: OverlayProgress,
        packLabel: String,
    ): InstallResult {
        val sid = pack.sourceLangId ?: return InstallResult.Failed(
            "Source pack ${pack.catalogKey} has no sourceLangId"
        )
        val app = activity.applicationContext

        // Step 1: explicit dict handle close. Required for BOTH FORCE and
        // ADDITIVE modes. JapaneseEngine.close() is a no-op (DictionaryManager
        // is a process-scoped singleton), so without this explicit close the
        // singleton retains its SQLite handle to the OLD inode. After install's
        // safeSwap renames the old dir to backup and promotes the new dir
        // into place, lookups would still go to the unlinked inode (returning
        // stale data) until the process restarts. Lazy reopen on next ensureOpen
        // picks up the new pack.
        if (sid == SourceLangId.JA) {
            DictionaryManager.get(app).close()
        }

        // Step 2 (FORCE only): pre-uninstall. ADDITIVE skips this — install's
        // safeSwap atomically backs up the old pack before promoting the new
        // one and restores on failure, so the user keeps a working pack
        // through any cancellation / network drop / SHA mismatch.
        if (pack.upgradeMode == UpgradeMode.FORCE) {
            LanguagePackStore.uninstall(app, sid)
        }

        // Step 3: install with progress callback.
        return LanguagePackStore.install(app, sid) { progress ->
            reportProgress(dialog, packLabel, progress)
        }
    }

    private suspend fun upgradeTargetPack(
        pack: StalePack,
        dialog: OverlayProgress,
        packLabel: String,
    ): InstallResult {
        val lang = pack.targetLangCode ?: return InstallResult.Failed(
            "Target pack ${pack.catalogKey} has no targetLangCode"
        )
        val app = activity.applicationContext

        // FORCE only: pre-uninstall (calls TargetGlossDatabaseProvider.release
        // internally per line 343). ADDITIVE skips — installTarget's safeSwap
        // preserves the old pack until the new one is verified.
        if (pack.upgradeMode == UpgradeMode.FORCE) {
            LanguagePackStore.uninstallTarget(app, lang)
        }

        return LanguagePackStore.installTarget(app, lang) { progress ->
            reportProgress(dialog, packLabel, progress)
        }
    }

    private fun reportProgress(
        dialog: OverlayProgress,
        packLabel: String,
        progress: DownloadProgress,
    ) {
        if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
            val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
            activity.runOnUiThread {
                dialog.setProgress(pct)
                dialog.setMessage(
                    activity.getString(
                        R.string.pack_upgrade_progress_format_with_bytes,
                        packLabel,
                        humanSize(progress.bytesReceived),
                        humanSize(progress.totalBytes),
                    )
                )
            }
        }
    }

    private suspend fun primeMlKit() {
        val prefs = Prefs(activity.applicationContext)
        val sourceLang = prefs.sourceLang
        val targetLang = prefs.targetLang

        // Mirrors TargetPackInstaller.ensureModels (line 128-135). The
        // (en, target) preload covers the definition-translation fallback
        // path the dictionary uses for non-English target languages.
        val tm = TranslationManager(sourceLang, targetLang)
        try { tm.ensureModelReady() } finally { tm.close() }
        if (targetLang != "en") {
            val enTm = TranslationManager("en", targetLang)
            try { enTm.ensureModelReady() } finally { enTm.close() }
        }
    }

    private fun labelFor(pack: StalePack): String = when (pack.kind) {
        PackKind.SOURCE -> activity.getString(
            R.string.pack_upgrade_label_source,
            pack.sourceLangId?.displayName(Locale.getDefault()) ?: pack.displayName,
        )
        PackKind.TARGET -> activity.getString(
            R.string.pack_upgrade_label_target,
            pack.targetLangCode?.let {
                Locale(it).getDisplayLanguage(Locale.getDefault())
                    .replaceFirstChar { c -> c.uppercase(Locale.getDefault()) }
            } ?: pack.displayName,
        )
    }

    private fun showErrorPopup(reason: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.lang_download_error_title)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private sealed interface Outcome {
        data object Success : Outcome
        data object Cancelled : Outcome
        data class Failed(val reason: String) : Outcome
    }

    companion object {
        private const val TAG = "PackUpgradeOrch"

        /** Convenience: pretty multi-line summary of stale packs for the
         *  initial OverlayAlert body, formatted as one entry per line. */
        fun describeForAlert(activity: Activity, stalePacks: List<StalePack>): String =
            stalePacks.joinToString("\n") { pack ->
                when (pack.kind) {
                    PackKind.SOURCE -> activity.getString(
                        R.string.pack_upgrade_label_source,
                        pack.sourceLangId?.displayName(Locale.getDefault())
                            ?: pack.displayName,
                    )
                    PackKind.TARGET -> activity.getString(
                        R.string.pack_upgrade_label_target,
                        pack.targetLangCode?.let {
                            Locale(it).getDisplayLanguage(Locale.getDefault())
                                .replaceFirstChar { c -> c.uppercase(Locale.getDefault()) }
                        } ?: pack.displayName,
                    )
                }
            }
    }
}
