package com.playtranslate.camera.tracker

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
)

/**
 * Pure policy state machine for the camera tracker — no OpenCV, no Android
 * types, fully JVM-testable. Consumes per-frame [TrackMeasurement]s (from
 * [FrameTracker]) plus the session's settle/scene signals, owns the
 * Idle/Acquiring/Locked/Lost lifecycle and every re-OCR trigger:
 *
 *  - Idle + settled (+ scene changed) + cooldown → acquire.
 *  - Locked: EMA-smooth the homography; drop to Lost after
 *    [TrackerConfig.FRAMES_TO_LOST] consecutive sub-floor frames (hysteresis:
 *    the keep floor is lower than the acquire floor).
 *  - Locked + settled: staleness ([TrackerConfig.ANCHOR_REFRESH_AGE_MS]) or
 *    scale drift ([TrackerConfig.SCALE_DRIFT_REACQUIRE]) → re-acquire.
 *  - Lost: hide overlays, give the tracker's own re-match
 *    [TrackerConfig.LOST_TO_IDLE_FRAMES] frames to re-lock, then Idle (which
 *    re-OCRs on the next settle).
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

    /** Region key → consecutive frames with tracing-point survival below
     *  [TrackerConfig.REGION_SURVIVAL_REOCR] (the Huawei collapse trigger). */
    private val regionCollapseStreaks = HashMap<Int, Int>()

    /** Smoothed inlier count + consecutive scene-changed frames — together
     *  they detect a dead anchor that spurious re-matches keep limping. */
    private var emaInliers = 0f
    private var sceneChangedStreak = 0

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
            sceneChangedStreak = 0
        } else if (state == TrackState.ACQUIRING) {
            // Nothing usable (no text / OCR failed / weak features). Back to
            // Idle; the scene-change gate stops an immediate identical retry.
            state = TrackState.IDLE
        }
    }

    fun reset() {
        state = TrackState.IDLE
        smoothedH = null
        belowKeepStreak = 0
        lostFrames = 0
        lastAcquireRequestMs = 0L
        anchorCreatedAtMs = 0L
        regionCollapseStreaks.clear()
        emaInliers = 0f
        sceneChangedStreak = 0
    }

    fun onFrame(
        m: TrackMeasurement?,
        settled: Boolean,
        sceneChanged: Boolean,
        nowMs: Long = clock(),
    ): FrameDecision {
        return when (state) {
            TrackState.IDLE -> {
                if (settled && sceneChanged && cooldownElapsed(nowMs)) {
                    startAcquire(nowMs)
                    decision(null, requestAcquire = true, m)
                } else {
                    decision(null, requestAcquire = false, m)
                }
            }

            TrackState.ACQUIRING ->
                // Keep showing the previous anchor's overlays (if any) while
                // the new acquire runs — smoothing continues on stale state.
                trackLockedFrame(m, settled = false, sceneChanged = false, nowMs, allowTriggers = false)

            TrackState.LOCKED ->
                trackLockedFrame(m, settled, sceneChanged, nowMs, allowTriggers = true)

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
        settled: Boolean,
        sceneChanged: Boolean,
        nowMs: Long,
        allowTriggers: Boolean,
    ): FrameDecision {
        // Dead-anchor escape: the view stopped resembling the keyframe AND
        // smoothed inliers sit below the acquire floor — but momentary
        // rematch spikes keep resetting the consecutive-bad hysteresis, so
        // the lock would otherwise limp forever (observed 27 s on device).
        // Idle (not Lost): Lost's re-lock would bounce back to this anchor
        // on the next spurious spike; Idle re-OCRs on the next settle.
        if (allowTriggers) {
            emaInliers += TrackerConfig.EMA_INLIERS_ALPHA * ((m?.inliers ?: 0) - emaInliers)
            sceneChangedStreak = if (sceneChanged) sceneChangedStreak + 1 else 0
            if (sceneChangedStreak >= TrackerConfig.SCENE_LOSS_FRAMES &&
                emaInliers < TrackerConfig.DEAD_ANCHOR_EMA_INLIERS
            ) {
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

        // Per-region tracing-point collapse streaks (Huawei re-OCR trigger):
        // a region persistently losing its points means its content changed
        // or got occluded — the global fit can stay healthy through that.
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
        if (allowTriggers && settled && cooldownElapsed(nowMs)) {
            val scale = smoothedH?.let { Homography.scaleOf(it) } ?: 1f
            val stale = nowMs - anchorCreatedAtMs > TrackerConfig.ANCHOR_REFRESH_AGE_MS
            val scaleDrift = scale > TrackerConfig.SCALE_DRIFT_REACQUIRE ||
                scale < 1f / TrackerConfig.SCALE_DRIFT_REACQUIRE
            if (stale || scaleDrift || sceneChanged || regionCollapsed) {
                startAcquire(nowMs)
                acquire = true
            }
        }
        // Emit the last smoothed H even on a briefly-bad frame — a short
        // dropout shouldn't blank the overlays (hysteresis handles real loss).
        return decision(smoothedH, acquire, m)
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
        )
}
