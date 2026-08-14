package com.playtranslate.yomitan

import android.util.Log
import com.playtranslate.CaptureService
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Launch-time, silent auto-updater + auto-healer for installed Yomitan
 * dictionaries. Honors both hard constraints:
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
 * Two cadences share the single-flight job: the full update scan (~24h,
 * [DEBOUNCE_MS]) and the heal pass ([HEAL_DEBOUNCE_MS], ~15 min) — the latter
 * re-downloads OUTDATED URL-bearing decks (rows dropped by a schema bump)
 * without waiting a day. Offline, a heal attempt costs one failed index probe
 * and the settings warning rows simply persist until connectivity returns.
 * Also hosts the one-time retained-zip sweep and update-metadata backfill.
 *
 * No background service / WorkManager / polling — matches the established pack
 * policy (updates are checked at launch, debounced, never polled).
 */
object YomitanAutoUpdateOrchestrator {

    private const val TAG = "YomitanAutoUpdate"
    private val DEBOUNCE_MS = TimeUnit.HOURS.toMillis(24)
    private val HEAL_DEBOUNCE_MS = TimeUnit.MINUTES.toMillis(15)

    /** The in-flight scan OR manual per-deck update, if any. Single-flight
     *  guard: a new trigger while one is active is a no-op. Claimed via
     *  [tryClaimSlot] BEFORE the job starts, so there is no window where two
     *  holders both pass the check and race the same deck's download+apply. */
    private val scanJob = AtomicReference<Job?>(null)

    /**
     * Claims the single-flight slot for [job] unless a previously claimed job
     * still holds it. Shared with the detail page's manual "Check for
     * updates" flow: both callers create their job UNSTARTED
     * ([CoroutineStart.LAZY]), claim, and only then start it. The holding
     * predicate is NOT-COMPLETED, never isActive — a claimed lazy job is in
     * the NEW state (not active, not completed) until start(), and treating
     * NEW as free would let a concurrent claimant CAS over it and run two
     * update flows into the same temp path + apply (Codex adversarial catch;
     * unreachable while every claimant is main-thread, but the invariant must
     * not depend on caller threading). The slot self-releases when the job
     * completes or cancels — so the claimant's contract is: START the claimed
     * job, or CANCEL it (as the failed-claim path and any abort must), else
     * a NEW job wedges the slot forever.
     */
    fun tryClaimSlot(job: Job): Boolean {
        while (true) {
            val current = scanJob.get()
            if (current != null && !current.isCompleted) return false
            if (scanJob.compareAndSet(current, job)) return true
        }
    }

    /**
     * Trigger from a launch/resume path (e.g. `MainActivity.onResume`).
     * Debounced and single-flight; fire-and-forget on the app scope. Safe to
     * call on every resume — the debounces + the active-scan guard collapse
     * repeats; the steady-state heal cost is one cheap outdated-set query per
     * [HEAL_DEBOUNCE_MS].
     */
    fun maybeRun(app: PlayTranslateApplication) {
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        val fullDue = now - prefs.lastYomitanUpdateCheckMs >= DEBOUNCE_MS
        val healDue = now - prefs.lastYomitanHealAttemptMs >= HEAL_DEBOUNCE_MS
        if (!fullDue && !healDue) return

        // LAZY: the job must hold the slot BEFORE it can run (see tryClaimSlot).
        val job = app.appScope.launch(start = CoroutineStart.LAZY) {
            try {
                // One-time: delete the legacy retained zips (index.json is
                // extracted first; a still-un-ingested deck keeps its zip and
                // the sweep retries next launch). Runs before the backfill so
                // the metadata read hits the persisted index.json (it falls
                // back to the zip either way).
                if (!prefs.yomitanZipSweepDone) {
                    if (YomitanDictionaryStore.sweepRetainedZips(app)) {
                        prefs.yomitanZipSweepDone = true
                    }
                }
                // One-time: arm pre-existing decks that were imported before the
                // update-metadata fields existed.
                if (!prefs.yomitanUpdateBackfillDone) {
                    YomitanDictionaryStore.backfillUpdateMetadata(app)
                    prefs.yomitanUpdateBackfillDone = true
                }
                if (fullDue) {
                    runScan(app)
                }
                // Heal AFTER the scan: a deck that is both outdated and has a
                // newer remote revision heals via the normal update swap and
                // drops out of the outdated set re-read here.
                val outdated = YomitanDataStore.outdatedDictIds(app)
                prefs.lastYomitanHealAttemptMs = now
                if (outdated.isNotEmpty()) runHeal(app, outdated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
            }
        }
        if (!tryClaimSlot(job)) {
            job.cancel()
            return
        }
        if (fullDue) {
            // Consume the debounce up front (matches UpdateChecker) so a failed
            // or empty scan doesn't re-fire on every resume. After the claim,
            // so a refused trigger doesn't burn the window.
            prefs.lastYomitanUpdateCheckMs = now
        }
        job.start()
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

    /** Re-downloads + re-ingests outdated decks that have a source URL and
     *  haven't opted out — silently, sequentially (same free-space reasoning
     *  as [runScan]). Failures (offline, dead host) leave the deck outdated;
     *  the warning UI stays truthful and the next due pass retries.
     *  Dump-sourced decks (`isUpdatable=false`) are never healable here —
     *  manual re-import is their only path. */
    private suspend fun runHeal(app: PlayTranslateApplication, outdated: Set<String>) {
        val registry = YomitanDictionaryStore.load(app)
        val healable = registry.dictionaries.filter {
            it.id in outdated && it.isUpdatable && it.indexUrl != null && it.autoUpdate
        }
        if (healable.isEmpty()) return
        Log.i(TAG, "healing ${healable.size} outdated Yomitan dictionaries")
        for (dict in healable) {
            YomitanUpdater.updateOne(app, dict, isBusy = ::isAppBusy, force = true)
        }
    }

    /** True when a translation session is in progress (live mode or either
     *  one-shot capture path), via the single [CaptureService.isCapturing]
     *  predicate — the apply defers rather than disrupt it. Public because the
     *  manual update flow gates its apply on the same predicate. */
    fun isAppBusy(): Boolean = CaptureService.instance?.isCapturing ?: false
}
