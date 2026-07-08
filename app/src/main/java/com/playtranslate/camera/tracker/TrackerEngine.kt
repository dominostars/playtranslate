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

    /** Consecutive acquires that produced no usable text; drives the
     *  escalating backoff. Reset by deliberate motion (re-aiming). */
    private var noTextFailures = 0

    /** Region key → consecutive frames with tracing-point survival below
     *  [TrackerConfig.REGION_SURVIVAL_REOCR] (the Huawei collapse trigger). */
    private val regionCollapseStreaks = HashMap<Int, Int>()

    /** Smoothed inlier count + streak below the dead-anchor floor. */
    private var emaInliers = 0f
    private var deadAnchorStreak = 0

    /** Session callback: an acquire it launched finished. [locked] true when
     *  a new anchor was installed with enough inliers to trust. */
    fun onAcquireFinished(locked: Boolean, nowMs: Long = clock()) {
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
                if (isSettled() && canAcquire && cooldownElapsed(nowMs) && backoffElapsed(nowMs)) {
                    startAcquire(nowMs)
                    decision(null, requestAcquire = true, m)
                } else {
                    decision(null, requestAcquire = false, m)
                }
            }

            TrackState.ACQUIRING -> {
                // Watchdog: a wedged or lost acquire must not pin the state
                // machine — every trigger is disabled while ACQUIRING.
                if (nowMs - lastAcquireRequestMs > TrackerConfig.ACQUIRE_TIMEOUT_MS) {
                    state = TrackState.IDLE
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
        } else if (allowTriggers) {
            if (++belowKeepStreak >= TrackerConfig.FRAMES_TO_LOST) {
                state = TrackState.LOST
                lostFrames = 0
                return decision(null, requestAcquire = false, m)
            }
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
                startAcquire(nowMs)
                acquire = true
            }
        }
        // Emit the last smoothed H even on a briefly-bad frame — a short
        // dropout shouldn't blank the overlays (hysteresis handles real loss).
        return decision(smoothedH, acquire, m)
    }

    // ── Motion / settle (single source: tracker median displacement) ──────

    private fun updateStillness(m: TrackMeasurement?) {
        val disp = m?.medianDispPx ?: -1.0
        when {
            disp < 0 -> Unit // unknown (rematch frame / first frame): neutral
            disp <= TrackerConfig.SETTLE_DISP_CN_PX -> stillFrames++
            else -> {
                stillFrames = 0
                // Deliberate re-aiming: a textless scene verdict no longer
                // applies, so retry OCR promptly on the next settle.
                if (disp > TrackerConfig.MOTION_RESET_CN_PX) noTextFailures = 0
            }
        }
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

    private fun startAcquire(nowMs: Long) {
        state = TrackState.ACQUIRING
        lastAcquireRequestMs = nowMs
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
