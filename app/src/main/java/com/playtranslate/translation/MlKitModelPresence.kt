package com.playtranslate.translation

import android.os.SystemClock
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers "are ML Kit's models for source→target ALREADY downloaded?" without
 * ever triggering a download. [MlKitBackend.isUsable] is deliberately always
 * true and its translate lazily downloads a cold pair's model — correct for
 * the waterfall's last-resort floor, wrong for the short-text offline route,
 * which must never spend the user's data/storage on a routing optimization.
 *
 * Needed set = {source, target, English}: ML Kit pivots every pair through
 * English, mirroring [OfflineModelReclaimer.isOfflineTranslationReady].
 *
 * Caching: a positive verdict is sticky per pair (models only disappear via
 * [OfflineModelReclaimer], which calls [invalidate] around the delete); the
 * downloaded-codes set behind a negative verdict is re-probed after a short
 * TTL so a model the user downloads mid-session is noticed without a GMS
 * round-trip per capture cycle. The probe is time-bounded — a sick GMS (a
 * known failure class) must not stall the translation batch — and failure
 * yields the empty set → "not ready" → the caller fails open to online.
 *
 * [invalidate] bumps a generation; an in-flight probe that started before the
 * bump discards its result instead of republishing a stale set. Residual
 * accepted window: a probe that reads GMS DURING a delete can re-stick a pair
 * until the post-delete [invalidate] lands; and a user clearing GMS storage
 * outside the app never invalidates — in both cases the failure mode is one
 * unwanted background download, not a wrong translation.
 */
internal object MlKitModelPresence {
    private const val PROBE_TTL_MS = 60_000L
    private const val PROBE_TIMEOUT_MS = 3_000L

    private val readyPairs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    @Volatile private var cachedCodes: Set<String>? = null
    @Volatile private var cachedAtMs: Long = 0L

    /** Test seams: injectable probe + clock. */
    internal var probeOverrideForTest: (suspend () -> Set<String>)? = null
    internal var clockOverrideForTest: (() -> Long)? = null

    suspend fun hasModelsFor(source: String, target: String): Boolean {
        val pair = "$source>$target"
        if (pair in readyPairs) return true
        val startedGen = generation
        val needed = setOf(source, target, TranslateLanguage.ENGLISH)
        // ONE downloadedCodes() call per check — `all { it in downloadedCodes() }`
        // would probe per language code and let a re-probe after an
        // invalidate republish the very set the invalidate targeted.
        val downloaded = downloadedCodes()
        val ready = needed.all { it in downloaded }
        // Stick the positive only if no invalidate landed while probing — a
        // stale in-flight probe must not re-create the verdict a delete just
        // killed. The RETURN may still be one-shot stale (this cycle routes to
        // ML Kit against a just-deleted model → at worst one re-download);
        // stickiness is the part that must not survive.
        if (ready && generation == startedGen) readyPairs.add(pair)
        return ready
    }

    /** Drop every cached verdict — called by [OfflineModelReclaimer] around a
     *  model delete (before AND after, so a probe racing the delete can't
     *  leave a re-stuck pair behind). The generation bump makes any in-flight
     *  probe discard its result rather than republish it. */
    fun invalidate() {
        generation++
        readyPairs.clear()
        cachedCodes = null
    }

    private suspend fun downloadedCodes(): Set<String> = mutex.withLock {
        val now = clockOverrideForTest?.invoke() ?: SystemClock.elapsedRealtime()
        cachedCodes?.takeIf { now - cachedAtMs < PROBE_TTL_MS }?.let { return@withLock it }
        val startedGen = generation
        val probed = probeOverrideForTest?.invoke()
            ?: withTimeoutOrNull(PROBE_TIMEOUT_MS) { OfflineModelReclaimer.downloadedMlKitCodes() }
            ?: emptySet()
        // Publish only if no invalidate landed while the probe was in flight —
        // a stale set republished after a delete would resurrect the verdict
        // the invalidate just killed.
        if (generation == startedGen) {
            cachedCodes = probed
            cachedAtMs = now
        }
        probed
    }
}
