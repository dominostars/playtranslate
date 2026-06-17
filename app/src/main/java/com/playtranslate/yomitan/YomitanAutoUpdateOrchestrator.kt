package com.playtranslate.yomitan

import android.util.Log
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Launch-time, silent auto-updater for installed Yomitan dictionaries. Honors
 * both hard constraints:
 *
 *  1. Never interrupts active use — runs entirely on [PlayTranslateApplication.appScope]
 *     (never an Activity, so no blocking dialog is even possible), and the only
 *     DB-mutating step (the apply, inside [YomitanUpdater.updateOne]) is gated on
 *     [isAppBusy]; the network check + download are safe during active use.
 *  2. No concurrent duplicate updates for the same deck — the scan is
 *     single-flight ([scanJob]) and processes decks sequentially, so a deck
 *     can't update twice at once. Downloads are serialized (not parallel) so
 *     each sizes itself against the live free space; see [runScan].
 *
 * No background service / WorkManager / polling — matches the established pack
 * policy (updates are checked at launch, debounced, never polled).
 */
object YomitanAutoUpdateOrchestrator {

    private const val TAG = "YomitanAutoUpdate"
    private val DEBOUNCE_MS = TimeUnit.HOURS.toMillis(24)

    /** The in-flight scan, if any. Single-flight guard: a new trigger while one
     *  is active is a no-op. */
    private val scanJob = AtomicReference<Job?>(null)

    /**
     * Trigger from a launch/resume path (e.g. `MainActivity.onResume`). Debounced
     * (~24h) and single-flight; fire-and-forget on the app scope. Safe to call on
     * every resume — debounce + the active-scan guard collapse repeats.
     */
    fun maybeRun(app: PlayTranslateApplication) {
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        if (now - prefs.lastYomitanUpdateCheckMs < DEBOUNCE_MS) return
        if (scanJob.get()?.isActive == true) return
        // Consume the debounce up front (matches UpdateChecker) so a failed or
        // empty scan doesn't re-fire on every resume.
        prefs.lastYomitanUpdateCheckMs = now

        val job = app.appScope.launch {
            try {
                // One-time: arm pre-existing decks that were imported before the
                // update-metadata fields existed.
                if (!prefs.yomitanUpdateBackfillDone) {
                    YomitanDictionaryStore.backfillUpdateMetadata(app)
                    prefs.yomitanUpdateBackfillDone = true
                }
                runScan(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
            }
        }
        scanJob.set(job)
    }

    private suspend fun runScan(app: PlayTranslateApplication) {
        val registry = YomitanDictionaryStore.load(app)
        val updatable = registry.dictionaries.filter {
            it.isUpdatable && it.indexUrl != null && it.autoUpdate
        }
        if (updatable.isEmpty()) return
        Log.i(TAG, "checking ${updatable.size} updatable Yomitan dictionaries")
        // Process decks ONE AT A TIME. updateOne sizes each download against the
        // CURRENT free space (an absolute ceiling + a free-space margin); run
        // concurrently, N downloads would each observe the same free space and
        // could collectively blow past the margin and fill the cache filesystem,
        // so the bound only holds when downloads are serialized. Updates are rare
        // and background (24h-debounced), so the lost parallelism is immaterial;
        // the single-flight scan still prevents same-deck overlap. updateOne never
        // throws except on cancellation (which correctly stops the whole scan).
        for (dict in updatable) {
            YomitanUpdater.updateOne(app, dict, isBusy = ::isAppBusy)
        }
    }

    /** True when a translation session is in progress (live mode or either
     *  one-shot capture path), via the single [CaptureService.isCapturing]
     *  predicate — the apply defers rather than disrupt it. */
    private fun isAppBusy(): Boolean = CaptureService.instance?.isCapturing ?: false
}
