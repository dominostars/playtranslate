package com.playtranslate.camera.tracker

/**
 * Tracker policy constants, ported from the offline-translator planar engine
 * (translator-rs `planar_engine.rs` defaults) and adapted where our pipeline
 * differs. Start values — tune against the debug pill on device.
 */
object TrackerConfig {
    /** ORB feature budget for anchors and drift-reset re-matches. */
    const val ORB_FEATURES = 1000

    /** Max LK-tracked correspondences per frame (benchmark: 300 pts = 1.3 ms). */
    const val MAX_TRACK_POINTS = 300

    /** LK window and pyramid depth (≈40 px motion ceiling at 3 levels). */
    const val LK_WIN_SIZE = 21.0
    const val LK_MAX_LEVEL = 3

    /** Forward-backward LK consistency threshold (px in CN space): a point
     *  whose backward track lands farther than this from its origin is
     *  dropped as unreliable. */
    const val LK_FB_EPS = 1.0

    /** RANSAC reprojection threshold (px in CN space). */
    const val RANSAC_REPROJ_PX = 3.0

    /** Inliers needed to LOCK a fresh anchor... */
    const val MIN_INLIERS_ACQUIRE = 25

    /** ...and the (lower, hysteresis) floor to KEEP a lock. */
    const val MIN_INLIERS_KEEP = 18

    /** Consecutive sub-[MIN_INLIERS_KEEP] frames before Locked → Lost. */
    const val FRAMES_TO_LOST = 5

    /** Lost frames (attempting anchor re-match) before giving up → Idle. */
    const val LOST_TO_IDLE_FRAMES = 30

    /** Re-detect ORB and re-match against the anchor every N frames to reset
     *  accumulated LK drift (benchmark: ~14 ms, still inside a 33 ms slot). */
    const val DRIFT_RESET_INTERVAL_FRAMES = 30

    /** Minimum frames between re-matches while STARVED (below the keep
     *  floor). Recovery attempts stay frequent enough to re-lock after a
     *  blur burst, without burning a full ORB pass every frame on a scene
     *  that's simply gone. */
    const val STARVED_REMATCH_INTERVAL_FRAMES = 5

    /** Re-acquire (fresh OCR) when the tracked scale drifts this far from the
     *  anchor's acquire scale — the text is now much nearer/farther, so both
     *  BRIEF-style descriptors and the rendered overlay resolution are stale. */
    const val SCALE_DRIFT_REACQUIRE = 1.35f

    /** Anchor staleness: while settled and Locked, refresh (re-OCR) anchors
     *  older than this. */
    const val ANCHOR_REFRESH_AGE_MS = 30_000L

    /** Floor between acquires. */
    const val ACQUIRE_COOLDOWN_MS = 250L

    /** Watchdog: if ACQUIRING persists this long with no completion callback
     *  (a dropped launch, a wedged OCR), revert to Idle rather than pinning
     *  the state machine — observed 47 s of pinned ACQUIRING on device when
     *  a granted acquire request was silently not launched. */
    const val ACQUIRE_TIMEOUT_MS = 30_000L

    /** EMA smoothing factor applied to the emitted homography (0..1, higher =
     *  snappier). Deliberate v1 simplification over the reference's 8-DoF EKF;
     *  same seam if an EKF upgrade is ever warranted. */
    const val H_SMOOTHING_ALPHA = 0.6f

    // ── Per-region refinement (Huawei US 12,190,612's per-text-line
    //    homographies, generalized to the overlay's warp unit: OCR groups for
    //    the translation flavor, OCR lines for furigana/pinyin) ─────────────

    /** Acquire-time good-features budget used to seed region tracing points
     *  (on top of the ORB anchor features). 600 originally; halved after Moto
     *  G diagnostics — 900 total points × 2 LK passes starved the acquire
     *  OCR on budget SoCs for marginal tracking benefit. */
    const val SEED_FEATURES = 250

    /** Hard cap on live correspondences (ORB matches + seeds). LK cost is
     *  linear in this. */
    const val TOTAL_POINT_CAP = 400

    /** Consecutive frames with the smoothed inlier count below
     *  [DEAD_ANCHOR_EMA_INLIERS] before a Locked anchor is declared dead →
     *  Idle. Catches the "pointed somewhere new but spurious re-matches keep
     *  the old lock limping" failure, which the consecutive-bad-frames
     *  hysteresis misses (rematch spikes reset it). Observed live on the
     *  Moto G (inliers 10-266 oscillating, state pinned LOCKED for 27 s). */
    const val DEAD_ANCHOR_FRAMES = 20

    // ── Settle / motion (tracker-derived; the coarse luma grid this replaced
    //    needed per-device calibration and broke twice on the Moto G) ───────

    /** Median LK point displacement (CN px) at or below which a frame counts
     *  as still — the DEFAULT until the adaptive floor has enough samples.
     *  Sub-pixel when braced; ~1 px handheld-still on the devices measured. */
    const val SETTLE_DISP_CN_PX = 1.5

    /** Adaptive settle: the threshold derives from the device's own recent
     *  displacement distribution (p25 × [SETTLE_FLOOR_MULT], clamped), so a
     *  shakier sensor/hand doesn't lock the gate shut and a rock-steady rig
     *  doesn't treat sensor noise as motion. Magic-constant calibration has
     *  been this pipeline's most repeated failure. */
    const val SETTLE_ADAPT_WINDOW = 90
    const val SETTLE_ADAPT_MIN_SAMPLES = 30
    const val SETTLE_FLOOR_MULT = 2.5
    const val SETTLE_THRESHOLD_MIN = 0.8
    const val SETTLE_THRESHOLD_MAX = 4.0

    /** Consecutive still frames before the settle gate opens. */
    const val SETTLE_FRAMES = 3

    /** A frame with median displacement above this counts as deliberate
     *  motion: it resets the no-text acquire backoff (the scene is being
     *  re-aimed, so a fresh OCR is worth trying again soon). */
    const val MOTION_RESET_CN_PX = 8.0

    /** Escalating backoff between acquires that found no usable text, so a
     *  blank/textless scene isn't OCR-hammered every cooldown interval. */
    const val NO_TEXT_BACKOFF_BASE_MS = 1_000L
    const val NO_TEXT_BACKOFF_MAX_MS = 4_000L

    /** Good-features budget for the anchorless (Idle) motion probe. */
    const val PROBE_POINTS = 40

    /** Re-detect probe corners only when the LK-surviving set falls below
     *  this floor, or every [PROBE_RESEED_INTERVAL_FRAMES] frames.
     *  goodFeaturesToTrack is a whole-image pass (~2-5 ms on budget SoCs),
     *  and Idle is exactly the state the analysis backoff exists to make
     *  cheap — surviving corners keep measuring motion for free. */
    const val PROBE_RESEED_MIN_POINTS = 20
    const val PROBE_RESEED_INTERVAL_FRAMES = 30

    // ── Anchor LRU (re-lock on previously seen scenes without re-OCR) ─────

    /** Recently replaced anchors kept alive for instant re-lock. Each holds
     *  ~0.6 MB of Mats (CN gray + descriptors); display payload is boxes,
     *  not rasters. */
    const val ANCHOR_CACHE_SIZE = 3

    /** While Idle with a non-empty cache, try an ORB match against one
     *  cached anchor every N frames (round-robin). ORB costs ~40 ms on
     *  budget SoCs, so probing every frame would halve the fps; every 10th
     *  re-locks within ~0.5 s at ~25 fps. */
    const val RELOCK_PROBE_INTERVAL_FRAMES = 10

    /** EMA factor for the smoothed inlier count feeding the dead-anchor
     *  check (higher = snappier). */
    const val EMA_INLIERS_ALPHA = 0.3f

    /** Smoothed-inlier ceiling for the dead-anchor check. Calibrated from
     *  the 2026-07-07 Moto G capture: a dying anchor's spurious-rematch
     *  churn (10→266→17…) EMAs to ~40-150, while a healthy lock holds
     *  ~270-900 — so 100 separates them with margin on both sides. */
    const val DEAD_ANCHOR_EMA_INLIERS = 100f

    /** Minimum surviving correspondences inside a region's anchor rect to fit
     *  that region its own transform; below this it falls back to global H. */
    const val MIN_REGION_POINTS = 6

    /** Max deviation (CN px) between a region transform and the global
     *  homography, measured at the region's corners. A refinement is a small
     *  correction by definition; a wildly-diverging fit is a degenerate
     *  solution (near-collinear points on a small label), and rendering it
     *  smears the overlay across the screen. */
    const val REGION_MAX_DEVIATION_CN_PX = 30.0

    /** Region rect inflation (fraction of each dimension) when testing
     *  point membership — text corners sit ON the glyph edges. */
    const val REGION_INFLATE_FRAC = 0.25f

    /** Huawei-style re-OCR trigger: a region whose tracing-point survival
     *  ratio stays below this for [REGION_COLLAPSE_FRAMES] consecutive frames
     *  (while settled) forces a re-acquire — its content likely changed or
     *  got occluded. */
    const val REGION_SURVIVAL_REOCR = 0.35f
    const val REGION_COLLAPSE_FRAMES = 5
}
