package com.playtranslate.camera.tracker

import kotlin.math.min

/** Lifecycle of the camera tracker, mirroring the reference planar engine. */
enum class TrackState { IDLE, ACQUIRING, LOCKED, LOST }

/** What the pipeline should do after a frame. */
data class FrameDecision(
    val state: TrackState,
    /** Smoothed anchor-CN→current-CN homography to render overlays with, or
     *  null when overlays should hide (Idle / Lost / no fix yet). */
    val hCn: DoubleArray?,
    /** Per-region homography refinements (raw, unsmoothed — the small deltas
     *  they add over [hCn] don't benefit from EMA). Regions absent here ride
     *  the global homography. Empty whenever [hCn] is null. */
    val perRegionHCn: Map<Int, DoubleArray>,
    /** True when the session should start a fresh acquire (keyframe → OCR →
     *  new anchor). At most one in flight; the engine enforces the cooldown. */
    val requestAcquire: Boolean,
    /** Debug-pill fields. */
    val inliers: Int,
    val scale: Float,
    val settled: Boolean,
)

/**
 * Pure policy state machine for the camera tracker — no OpenCV, no Android
 * types, fully JVM-testable. Consumes per-frame [TrackMeasurement]s (from
 * [FrameTracker], including the anchorless motion probe while Idle), owns
 * the Idle/Acquiring/Locked/Lost lifecycle and every re-OCR trigger.
 *
 * Motion/settle is derived from ONE source: the tracker's median LK point
 * displacement. (An earlier design ran a parallel coarse-luma-grid detector;
 * it needed per-device calibration and broke twice on real sensors —
 * auto-exposure drift, AF convergence — before being deleted.)
 *
 * Triggers:
 *  - Idle + settled + cooldown (+ escalating no-text backoff) → acquire.
 *  - Locked: EMA-smooth the homography; drop to Lost after
 *    [TrackerConfig.FRAMES_TO_LOST] consecutive sub-floor frames.
 *  - Locked + settled: staleness, scale drift, or per-region tracing-point
 *    collapse → re-acquire.
 *  - Locked + smoothed inliers under [TrackerConfig.DEAD_ANCHOR_EMA_INLIERS]
 *    for [TrackerConfig.DEAD_ANCHOR_FRAMES] → Idle (dead anchor: spurious
 *    re-matches would otherwise keep a stale lock limping forever).
 *  - Acquiring: watchdog back to Idle if no completion arrives.
 */
class TrackerEngine(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var state: TrackState = TrackState.IDLE
        private set

    private var smoothedH: DoubleArray? = null
    private var belowKeepStreak = 0
    private var lostFrames = 0
    private var lastAcquireRequestMs = 0L
    private var anchorCreatedAtMs = 0L

    /** Consecutive still frames (median displacement ≤ threshold). Unknown
     *  displacement (rematch frames, first frame) neither advances nor
     *  resets it. */
    private var stillFrames = 0

    /** Rolling window of known displacements feeding the adaptive settle
     *  threshold (see [settleThreshold]). */
    private val dispWindow = DoubleArray(TrackerConfig.SETTLE_ADAPT_WINDOW)
    private var dispCount = 0
    private var dispCursor = 0
    private var cachedThreshold = TrackerConfig.SETTLE_DISP_CN_PX
    private var insertsSinceRecalc = 0

    /** Consecutive acquires that produced no usable text; drives the
     *  escalating backoff. Reset by deliberate motion (re-aiming). */
    private var noTextFailures = 0

    /** Region key → consecutive frames with tracing-point survival below
     *  [TrackerConfig.REGION_SURVIVAL_REOCR] (the Huawei collapse trigger). */
    private val regionCollapseStreaks = HashMap<Int, Int>()

    /** Smoothed inlier count + streak below the dead-anchor floor. */
    private var emaInliers = 0f
    private var deadAnchorStreak = 0

    // ── Acquire lifecycle: the engine is the ONE writer ────────────────────
    // A FrameDecision.requestAcquire is an OFFER; nothing changes until the
    // session actually launches and calls [beginAcquire]. Completion must
    // quote the id it was given — a completion whose id is no longer active
    // (watchdog fired, reset happened) is structurally ignored. This
    // replaces the session-side acquireInFlight flag whose divergence from
    // engine state once pinned the machine for 47 s.

    private var activeAcquireId = 0L
    private var nextAcquireId = 1L

    /** The session IS launching an acquire now. Returns the acquire id to
     *  quote on completion, or 0 if refused (one already active). */
    fun beginAcquire(nowMs: Long = clock()): Long {
        if (state == TrackState.ACQUIRING) return 0L
        state = TrackState.ACQUIRING
        lastAcquireRequestMs = nowMs
        activeAcquireId = nextAcquireId++
        return activeAcquireId
    }

    /** True while [acquireId] is the acquire the engine is waiting on. The
     *  session checks this before installing an anchor from a completion. */
    fun isAcquireActive(acquireId: Long): Boolean =
        state == TrackState.ACQUIRING && acquireId == activeAcquireId

    /** Completion for [acquireId]; ignored unless it is still the active
     *  acquire. [locked] true when a new anchor was installed with enough
     *  live-frame matches to trust. */
    fun finishAcquire(acquireId: Long, locked: Boolean, nowMs: Long = clock()) {
        if (!isAcquireActive(acquireId)) return
        activeAcquireId = 0L
        onAcquireFinished(locked, nowMs)
    }

    private fun onAcquireFinished(locked: Boolean, nowMs: Long = clock()) {
        if (locked) {
            state = TrackState.LOCKED
            anchorCreatedAtMs = nowMs
            smoothedH = null
            belowKeepStreak = 0
            lostFrames = 0
            regionCollapseStreaks.clear()
            emaInliers = TrackerConfig.DEAD_ANCHOR_EMA_INLIERS
            deadAnchorStreak = 0
            noTextFailures = 0
        } else if (state == TrackState.ACQUIRING) {
            // Nothing usable (no text / OCR failed / weak features). Back to
            // Idle; the escalating backoff stops an immediate identical retry.
            state = TrackState.IDLE
            noTextFailures++
        }
    }

    fun reset() {
        state = TrackState.IDLE
        activeAcquireId = 0L
        smoothedH = null
        belowKeepStreak = 0
        lostFrames = 0
        lastAcquireRequestMs = 0L
        anchorCreatedAtMs = 0L
        stillFrames = 0
        noTextFailures = 0
        regionCollapseStreaks.clear()
        emaInliers = 0f
        deadAnchorStreak = 0
        dispCount = 0
        dispCursor = 0
        insertsSinceRecalc = 0
        cachedThreshold = TrackerConfig.SETTLE_DISP_CN_PX
    }

    /**
     * [canAcquire] is the session's launch capacity (no acquire coroutine in
     * flight). The engine must never transition to ACQUIRING unless the
     * session can actually launch — a granted-but-dropped request pins the
     * state machine forever (no completion ever arrives).
     */
    fun onFrame(
        m: TrackMeasurement?,
        canAcquire: Boolean = true,
        nowMs: Long = clock(),
    ): FrameDecision {
        updateStillness(m)

        return when (state) {
            TrackState.IDLE -> {
                val offer = isSettled() && canAcquire && cooldownElapsed(nowMs) && backoffElapsed(nowMs)
                decision(null, requestAcquire = offer, m)
            }

            TrackState.ACQUIRING -> {
                // Watchdog: a wedged or lost acquire must not pin the state
                // machine — every trigger is disabled while ACQUIRING.
                if (nowMs - lastAcquireRequestMs > TrackerConfig.ACQUIRE_TIMEOUT_MS) {
                    state = TrackState.IDLE
                    activeAcquireId = 0L
                    smoothedH = null
                    decision(null, requestAcquire = false, m)
                } else {
                    // Keep showing the previous anchor's overlays (if any)
                    // while the new acquire runs.
                    trackLockedFrame(m, nowMs, allowTriggers = false, canAcquire = false)
                }
            }

            TrackState.LOCKED ->
                trackLockedFrame(m, nowMs, allowTriggers = true, canAcquire = canAcquire)

            TrackState.LOST -> {
                val recovered = m?.hCn != null && m.inliers >= TrackerConfig.MIN_INLIERS_ACQUIRE
                if (recovered) {
                    state = TrackState.LOCKED
                    belowKeepStreak = 0
                    lostFrames = 0
                    smoothedH = m!!.hCn!!.copyOf() // snap, don't blend across the gap
                    decision(smoothedH, requestAcquire = false, m)
                } else if (++lostFrames >= TrackerConfig.LOST_TO_IDLE_FRAMES) {
                    state = TrackState.IDLE
                    decision(null, requestAcquire = false, m)
                } else {
                    decision(null, requestAcquire = false, m)
                }
            }
        }
    }

    private fun trackLockedFrame(
        m: TrackMeasurement?,
        nowMs: Long,
        allowTriggers: Boolean,
        canAcquire: Boolean,
    ): FrameDecision {
        // Dead-anchor escape: smoothed inliers sitting under the floor while
        // momentary rematch spikes keep resetting the consecutive-bad
        // hysteresis (observed 27 s of a stale limping lock on device).
        // Idle, not Lost: Lost's re-lock would bounce back to this anchor on
        // the next spurious spike; Idle re-OCRs on the next settle.
        if (allowTriggers) {
            emaInliers += TrackerConfig.EMA_INLIERS_ALPHA * ((m?.inliers ?: 0) - emaInliers)
            deadAnchorStreak =
                if (emaInliers < TrackerConfig.DEAD_ANCHOR_EMA_INLIERS) deadAnchorStreak + 1 else 0
            if (deadAnchorStreak >= TrackerConfig.DEAD_ANCHOR_FRAMES) {
                state = TrackState.IDLE
                smoothedH = null
                return decision(null, requestAcquire = false, m)
            }
        }

        // Loss detection runs in BOTH Locked and Acquiring. It used to be
        // disabled while Acquiring (to prevent acquire-stacking), which also
        // froze the hysteresis — walking away mid-acquire left stale
        // overlays warping for the whole OCR duration (16 s observed on a
        // slow engine). While Acquiring we only HIDE (state stays Acquiring
        // so the completion callback still lands); Locked transitions to
        // Lost as before.
        val good = m?.hCn != null && m.inliers >= TrackerConfig.MIN_INLIERS_KEEP
        if (good) {
            belowKeepStreak = 0
            val fresh = m!!.hCn!!
            val smoothed = smoothedH
            if (smoothed == null) {
                smoothedH = fresh.copyOf()
            } else {
                Homography.emaInPlace(smoothed, fresh, TrackerConfig.H_SMOOTHING_ALPHA)
            }
        } else if (++belowKeepStreak >= TrackerConfig.FRAMES_TO_LOST) {
            if (allowTriggers) {
                state = TrackState.LOST
                lostFrames = 0
            } else {
                smoothedH = null // hide; completion decides what's next
            }
            return decision(null, requestAcquire = false, m)
        }

        // Per-region tracing-point collapse streaks (Huawei re-OCR trigger).
        if (allowTriggers && m != null) {
            for ((key, survival) in m.perRegionSurvival) {
                if (survival < TrackerConfig.REGION_SURVIVAL_REOCR) {
                    regionCollapseStreaks[key] = (regionCollapseStreaks[key] ?: 0) + 1
                } else {
                    regionCollapseStreaks.remove(key)
                }
            }
        }
        val regionCollapsed =
            regionCollapseStreaks.values.any { it >= TrackerConfig.REGION_COLLAPSE_FRAMES }

        // Re-acquire triggers (Locked only, settled only — OCR needs a sharp
        // frame, and a mid-pan refresh would anchor to a smear).
        var acquire = false
        if (allowTriggers && isSettled() && canAcquire && cooldownElapsed(nowMs)) {
            val scale = smoothedH?.let { Homography.scaleOf(it) } ?: 1f
            val stale = nowMs - anchorCreatedAtMs > TrackerConfig.ANCHOR_REFRESH_AGE_MS
            val scaleDrift = scale > TrackerConfig.SCALE_DRIFT_REACQUIRE ||
                scale < 1f / TrackerConfig.SCALE_DRIFT_REACQUIRE
            if (stale || scaleDrift || regionCollapsed) {
                acquire = true // an OFFER — the session launches, then beginAcquire()
            }
        }
        // Emit the last smoothed H even on a briefly-bad frame — a short
        // dropout shouldn't blank the overlays (hysteresis handles real loss).
        return decision(smoothedH, acquire, m)
    }

    // ── Motion / settle (single source: tracker median displacement) ──────

    private fun updateStillness(m: TrackMeasurement?) {
        val disp = m?.medianDispPx ?: -1.0
        if (disp >= 0) recordDisp(disp)
        when {
            disp < 0 -> Unit // unknown (rematch frame / first frame): neutral
            disp <= settleThreshold() -> stillFrames++
            else -> {
                stillFrames = 0
                // Deliberate re-aiming: a textless scene verdict no longer
                // applies, so retry OCR promptly on the next settle.
                if (disp > TrackerConfig.MOTION_RESET_CN_PX) noTextFailures = 0
            }
        }
    }

    private fun recordDisp(disp: Double) {
        dispWindow[dispCursor] = disp
        dispCursor = (dispCursor + 1) % dispWindow.size
        if (dispCount < dispWindow.size) dispCount++
        if (++insertsSinceRecalc >= 15) {
            insertsSinceRecalc = 0
            cachedThreshold = computeThreshold()
        }
    }

    /** Current settle threshold: default until the window has enough
     *  samples, then p25-of-recent-displacements × mult, clamped. */
    fun settleThreshold(): Double = cachedThreshold

    private fun computeThreshold(): Double {
        if (dispCount < TrackerConfig.SETTLE_ADAPT_MIN_SAMPLES) {
            return TrackerConfig.SETTLE_DISP_CN_PX
        }
        val sorted = dispWindow.copyOf(dispCount).also { it.sort() }
        val floor = sorted[dispCount / 4]
        return (floor * TrackerConfig.SETTLE_FLOOR_MULT)
            .coerceIn(TrackerConfig.SETTLE_THRESHOLD_MIN, TrackerConfig.SETTLE_THRESHOLD_MAX)
    }

    private fun isSettled(): Boolean = stillFrames >= TrackerConfig.SETTLE_FRAMES

    private fun backoffElapsed(nowMs: Long): Boolean {
        if (noTextFailures == 0) return true
        val shift = min(noTextFailures - 1, 8)
        val backoff = min(
            TrackerConfig.NO_TEXT_BACKOFF_BASE_MS shl shift,
            TrackerConfig.NO_TEXT_BACKOFF_MAX_MS,
        )
        return nowMs - lastAcquireRequestMs >= backoff
    }

    private fun cooldownElapsed(nowMs: Long): Boolean =
        nowMs - lastAcquireRequestMs >= TrackerConfig.ACQUIRE_COOLDOWN_MS

    private fun decision(h: DoubleArray?, requestAcquire: Boolean, m: TrackMeasurement?): FrameDecision =
        FrameDecision(
            state = state,
            hCn = h,
            perRegionHCn = if (h != null && m != null) m.perRegionH else emptyMap(),
            requestAcquire = requestAcquire,
            inliers = m?.inliers ?: 0,
            scale = h?.let { Homography.scaleOf(it) } ?: 0f,
            settled = isSettled(),
        )
}
