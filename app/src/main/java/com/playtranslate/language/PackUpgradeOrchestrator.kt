package com.playtranslate.language

import android.app.Activity
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.dictionary.SudachiJapaneseTokenizer
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.ui.OverlayProgress
import com.playtranslate.ui.showBergamotWarmupProgress
import com.playtranslate.ui.showOcrDownloadProgress
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
 * 1. **Source-pack-only**: explicit `DictionaryManager.get(ctx).close()` +
 *    `SudachiJapaneseTokenizer.Provider.close()`. [com.playtranslate.dictionary.JapaneseEngine.close]
 *    now DOES release both handles, but it only runs when the engine cache
 *    evicts — i.e. via `uninstall` → `releaseForPack`. ADDITIVE upgrades never
 *    call `uninstall`, so the orchestrator closes directly here (belt-and-
 *    suspenders on the FORCE path, where `uninstall` would close anyway).
 *    Without it, after the directory is swapped `DictionaryManager.db` and the
 *    Sudachi mmap still reference the old (unlinked) inode and serve ghost
 *    results until process kill; both reopen lazily on next access.
 * 2. `LanguagePackStore.uninstall(...)` — already calls `releaseForPack`
 *    internally; do NOT pre-call it from here.
 * 3. `LanguagePackStore.install(...)` with progress callback updating the
 *    dialog's bar + byte-count message (mirrors [com.playtranslate.ui.TargetPackInstaller]).
 *
 * Target-pack steps mirror source: `uninstallTarget` (which internally
 * calls `TargetGlossDatabaseProvider.release`), then `installTarget`.
 *
 * After every pack reinstalls successfully, primes the active pair's offline
 * assets — translation models and the source's OCR recognizer — for the
 * user's currently-selected `(prefs.sourceLangId, prefs.targetLang)` pair via
 * the shared [primeActivePair]. This avoids a second download surprise on
 * first lookup or first capture, and keeps this flow in lockstep with the
 * source-selection flow (which once differed here by priming translation but
 * skipping OCR).
 *
 * **Cancel semantics**: Cancel stays available throughout — the per-pack
 * download phase (single-flight, idempotent — `safeSwap` is per-pack atomic
 * so partial completion persists cleanly across pack boundaries) and the
 * post-upgrade priming phase (translation + OCR are best-effort and
 * idempotent, so cancelling just stops the model fetch). Cancel, back-press,
 * and activity-pause all route through the same dismiss → `activeJob.cancel()`
 * path. On mid-flight cancel: completed packs stay installed, any in-flight
 * pack rolls back via `LanguagePackStore.install`'s finally block, pending
 * packs are not attempted, partially primed models retry lazily (OCR falls
 * back to the ML Kit floor), the dialog dismisses, and the next-launch scan
 * re-fires for whatever remained stale.
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
            // Same cleanup either way — cancelling the job triggers
            // LanguagePackStore.install's finally to roll back the in-flight
            // pack while completed packs stay installed. Next launch's
            // staleness scan re-prompts for whatever remains.
            .setOnDismiss { activeJob?.cancel() }
            .show()

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
                is InstallResult.Failed -> {
                    activity.runOnUiThread { dialog.dismiss() }
                    return Outcome.Failed(
                        "Failed to install ${pack.displayName}: ${result.reason}"
                    )
                }
                is InstallResult.Cancelled -> {
                    activity.runOnUiThread { dialog.dismiss() }
                    return Outcome.Cancelled
                }
            }
        }

        // All packs upgraded — prime the active pair's offline assets
        // (translation models + the source's OCR recognizer) so the user
        // doesn't hit a second download surprise on first lookup or first
        // capture. Shared with the source-selection flow via [primeActivePair]
        // so the two can't disagree on what a pair needs — this flow used to
        // prime translation but silently skip OCR. Cancel stays available:
        // priming is best-effort and idempotent, so cancelling here just stops
        // the model fetch — the packs are already installed, and the models
        // retry lazily (OCR falls back to the ML Kit floor).
        activity.runOnUiThread {
            dialog.setIndeterminate(true)
            dialog.setMessage(activity.getString(R.string.pack_upgrade_priming_models))
        }
        try {
            withContext(Dispatchers.IO) {
                val prefs = Prefs(activity.applicationContext)
                primeActivePair(
                    activity.applicationContext,
                    prefs.sourceLangId,
                    prefs.targetLang,
                    onWarmup = { i, n, recv, total ->
                        activity.runOnUiThread {
                            dialog.showBergamotWarmupProgress(activity, i, n, recv, total)
                        }
                    },
                    onOcr = { recv, total ->
                        activity.runOnUiThread {
                            dialog.showOcrDownloadProgress(activity, recv, total)
                        }
                    },
                )
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Priming failure isn't worth blocking on — the packs are
            // installed, the user can still use them. Offline translation
            // retries lazily on first translate; OCR falls back to the ML Kit
            // floor. (primeActivePair logs per-asset failures; this only
            // catches the unexpected.)
            Log.w(TAG, "model priming failed (non-fatal): ${e.message}")
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
        // ADDITIVE modes. JapaneseEngine.close() now releases these handles,
        // but it only fires on engine-cache eviction (uninstall →
        // releaseForPack), and ADDITIVE never uninstalls — so close directly
        // here. Without it, after install's safeSwap renames the old dir to
        // backup and promotes the new dir, the singleton's SQLite handle (and
        // the Sudachi mmap) still point at the OLD unlinked inode, returning
        // stale data until process restart. Lazy reopen on next ensureOpen
        // picks up the new pack.
        if (sid == SourceLangId.JA) {
            DictionaryManager.get(app).close()
            // Release Sudachi's mmap'd system_*.dic before safeSwap renames the
            // old pack dir, so we don't keep a handle to the unlinked .dic.
            SudachiJapaneseTokenizer.Provider.close()
        }

        // Step 2 (FORCE only): pre-uninstall. ADDITIVE skips this — install's
        // safeSwap atomically backs up the old pack before promoting the new
        // one and restores on failure, so the user keeps a working pack
        // through any cancellation / network drop / SHA mismatch.
        if (pack.upgradeMode == UpgradeMode.FORCE) {
            LanguagePackStore.uninstall(app, sid)
        }

        // Step 3: install with progress callback.
        val result = LanguagePackStore.install(app, sid) { progress ->
            reportProgress(dialog, packLabel, progress)
        }

        // Step 4: post-install eviction (BOTH FORCE and ADDITIVE). Step 1
        // closed the handles at start-of-flight, but in ADDITIVE mode the OLD
        // pack stays on disk during the long download — any background path
        // (CaptureService live-mode, in-flight tokenization, drag-word handlers)
        // can reopen DictionaryManager against the OLD inode and reopen the
        // Sudachi Provider against the OLD system_*.dic. After safeSwap both
        // stay bound to the unlinked old inodes until process death.
        //
        // JapaneseEngine.close() now releases both handles, but it only fires
        // on engine-cache eviction (uninstall → releaseForPack), and ADDITIVE
        // never uninstalls — so close directly, then drop the cached engine:
        //   1. DictionaryManager.close() — next ensureOpen reads the new
        //      dict.sqlite at the same path.
        //   2. SudachiJapaneseTokenizer.Provider.close() — next analyze reloads
        //      the new pack's system_*.dic.
        //   3. SourceLanguageEngines.releaseForPack(packId) — drops the cached
        //      JapaneseEngine so the next get() re-inits the Provider pack dir.
        //
        // Refcounting keeps any in-flight cursor valid; only NEW lookups
        // pick up the new pack. Stale-data window shrinks from "until
        // process kill" to "any in-flight lookup that started before this
        // post-install eviction." Per Codex review findings 2026-05-10.
        if (result is InstallResult.Success && sid == SourceLangId.JA) {
            DictionaryManager.get(app).close()
            // Close the old Sudachi Dictionary too; releaseForPack then drops the
            // cached JapaneseEngine so the next get() re-inits the Provider against
            // the new pack's system_*.dic.
            SudachiJapaneseTokenizer.Provider.close()
            SourceLanguageEngines.releaseForPack(sid.packId)
        }
        return result
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

        val result = LanguagePackStore.installTarget(app, lang) { progress ->
            reportProgress(dialog, packLabel, progress)
        }

        // Same post-install eviction as the source path, for the same
        // reason: a background lookup during the long download could call
        // TargetGlossDatabaseProvider.get(lang) and cache an
        // FstTargetGlossDatabase pointed at the OLD FST blob. After
        // safeSwap promotes the new files, the cached handle stays bound
        // to the unlinked old inode until process death. Release after
        // success forces the next get() to reopen against the new files.
        if (result is InstallResult.Success) {
            TargetGlossDatabaseProvider.release(lang)
        }
        return result
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

    private fun labelFor(pack: StalePack): String = when (pack.kind) {
        PackKind.SOURCE -> activity.getString(
            R.string.pack_upgrade_label_source,
            pack.sourceLangId?.displayName(Locale.getDefault()) ?: pack.displayName,
        )
        PackKind.TARGET -> activity.getString(
            R.string.pack_upgrade_label_target,
            pack.targetLangCode?.let {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())
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
                            Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())
                                .replaceFirstChar { c -> c.uppercase(Locale.getDefault()) }
                        } ?: pack.displayName,
                    )
                }
            }
    }
}
