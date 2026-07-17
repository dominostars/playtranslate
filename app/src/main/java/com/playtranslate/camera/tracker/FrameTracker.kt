package com.playtranslate.camera.tracker

import android.graphics.Rect
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs

/**
 * Reference scene captured at acquire time (the "canonical" frame the
 * overlays and the per-frame homography are anchored to). Owns its Mats;
 * call [release] when replaced.
 */
class Anchor(
    val id: Long,
    /** Upright canonical-space gray keyframe. */
    val cnGray: Mat,
    val keypoints: MatOfKeyPoint,
    val descriptors: Mat,
    /** Acquire-time good-features corners (CN coords) that seed region
     *  tracing points — the Huawei-patent "tracing point" role. */
    val seedPts: List<Point>,
    /** AnalysisUpright dims of the keyframe this anchor was built from. */
    val auWidth: Int,
    val auHeight: Int,
    /** Uniform AU→CN downscale factor (CN px = AU px × this). */
    val cnScale: Double,
    val createdAtMs: Long,
) {
    fun release() {
        cnGray.release()
        keypoints.release()
        descriptors.release()
    }
}

/** One frame's tracking output, in plain types so policy code stays
 *  OpenCV-free and JVM-testable. */
data class TrackMeasurement(
    /** Anchor-CN → current-CN global homography, or null when fitting failed. */
    val hCn: DoubleArray?,
    /** RANSAC inlier count (0 when fitting failed). */
    val inliers: Int,
    /** Median per-point displacement since the previous frame (CN px) —
     *  the tracker-side motion/settle signal. Negative when unknown. */
    val medianDispPx: Double,
    /** Correspondences still alive after this frame. */
    val trackedPoints: Int,
    /** Per-region refinements (region key → anchor-CN→current-CN H) for
     *  regions with enough surviving points; absent keys fall back to [hCn]. */
    val perRegionH: Map<Int, DoubleArray> = emptyMap(),
    /** Region key → surviving-points / baseline ratio (0..1). Baselines reset
     *  at install/rematch, so this measures decay since the last correspondence
     *  refresh — the Huawei tracing-point-ratio re-OCR signal. */
    val perRegionSurvival: Map<Int, Float> = emptyMap(),
    /** CN dims of the frame this measurement was made on — the engine's
     *  display-deadband probe geometry (0 when synthesized in tests). */
    val frameW: Int = 0,
    val frameH: Int = 0,
)

/**
 * Per-frame planar tracking against an [Anchor]: pyramidal Lucas-Kanade flow
 * (with a forward-backward consistency check) sustains anchor↔current
 * correspondences frame to frame, RANSAC fits the global homography, and a
 * periodic ORB re-match against the anchor descriptors resets accumulated LK
 * drift — the KLT-sustains / descriptors-correct split of the reference
 * design (translator-rs klt.rs + coarse_tracker.rs).
 *
 * Per-region refinement (Huawei US 12,190,612, generalized): the session
 * registers tracked regions (overlay warp units — OCR groups or lines) as
 * anchor-space rects; membership of a correspondence is simply "its anchor
 * position falls inside the rect", so it survives re-matches statelessly.
 * Regions with ≥ [TrackerConfig.MIN_REGION_POINTS] surviving inliers get
 * their own RANSAC homography; the rest ride the global one.
 *
 * Analysis-thread only. Owns scratch Mats; [release] on teardown.
 */
class FrameTracker {

    private val orb = ORB.create(TrackerConfig.ORB_FEATURES)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)

    private var anchor: Anchor? = null

    /** Previous CN gray frame — a BORROWED reference to the caller's Mat.
     *  [track]'s input contract: curGray must stay valid (and unmodified)
     *  until the next [track] call; [CnFrameConverter] double-buffers for
     *  exactly this, saving a ~0.5 MB copy per frame. The one exception is
     *  the install-without-prior-frame path, which copies the keyframe into
     *  [ownedPrevKeyframe] (the keyframe's owner recycles it under us). */
    private var prevGray: Mat? = null
    private val ownedPrevKeyframe = Mat()
    private var hasPrev = false

    /** Live correspondences: positions in anchor space (fixed) and in the
     *  current frame (updated by LK each frame). Parallel arrays. */
    private var anchorPts = ArrayList<Point>()
    private var currentPts = ArrayList<Point>()

    /** Tracked regions: (key, anchor-CN rect, pre-inflated). */
    private var regionKeys = IntArray(0)
    private var regionRects = arrayOf<Rect>()

    /** Correspondences inside each region right after the last install /
     *  rematch — the survival-ratio denominator. */
    private var regionBaselines = IntArray(0)

    private var framesSinceRematch = 0

    // Reused OpenCV buffers. Every OpenCV Java object wraps NATIVE memory
    // reclaimed only by finalizers — per-call allocations on hot paths grow
    // native memory with no Java-heap pressure to trigger GC.
    private val lkStatus = MatOfByte()
    private val lkErr = MatOfFloat()
    private val lkWin = Size(TrackerConfig.LK_WIN_SIZE, TrackerConfig.LK_WIN_SIZE)
    private val emptyMask = Mat()
    private val affineInliers = Mat()
    private val lkPrevPts = MatOfPoint2f()
    private val lkNextPts = MatOfPoint2f()
    private val lkBackPts = MatOfPoint2f()
    private val lkBackStatus = MatOfByte()
    // Shared by the sequential fit paths (pruneToVerified → fitRegions →
    // probeAnchor); never live across two of them at once.
    private val fitSrc = MatOfPoint2f()
    private val fitDst = MatOfPoint2f()
    private val fitMask = Mat()

    /** Install a new anchor (takes ownership; releases the old one AND
     *  drops its correspondences, so live replacement is safe without a
     *  prior [detachAnchor] — the correspondence set must never outlive the
     *  anchor it describes, and that invariant lives HERE, not in caller
     *  discipline: [rematch] deliberately keeps the current points when it
     *  can't match (load-bearing for mid-track drift-reset recovery), which
     *  in the install role would let a featureless keyframe "verify" against
     *  the previous scene's points).
     *
     *  Correspondences are seeded by ORB-matching the anchor against the
     *  LIVE previous frame when one exists — NOT the keyframe. A slow OCR
     *  can outlive the scene (user walked away mid-acquire); matching
     *  against the keyframe always "succeeds" (it matches itself) and used
     *  to lock stale overlays onto a departed scene. Matching against the
     *  live frame makes a stale completion fail to lock naturally.
     *
     *  The matches are then RANSAC-verified and pruned to inliers, and the
     *  returned lock criterion is that VERIFIED count: a raw ratio-test
     *  count is position-invariant — it proves the scene is visible, not
     *  that it sits where the keyframe saw it — and can be high on
     *  repetitive glyph patterns that agree on no geometry. Tracing-point
     *  seeds enter at their positions PROJECTED through the verified fit;
     *  seeding them at raw keyframe positions after the view drifted (slow
     *  OCR) or moved (re-aimed re-lock) built a large RANSAC consensus for
     *  "nothing moved" that outvoted the true motion and pinned overlays
     *  to the old position until the next drift reset. */
    fun installAnchor(newAnchor: Anchor, keyframeGray: Mat): Int {
        anchor?.release()
        anchor = newAnchor
        anchorPts.clear()
        currentPts.clear()
        framesSinceRematch = 0
        val seedTarget = prevGray.takeIf { hasPrev } ?: keyframeGray
        rematch(seedTarget)
        val (h, verified) = pruneToVerified()
        if (h != null && verified >= TrackerConfig.MIN_INLIERS_ACQUIRE) {
            addProjectedSeeds(newAnchor.seedPts, h, seedTarget.cols(), seedTarget.rows())
        }
        recomputeBaselines()
        if (!hasPrev) {
            keyframeGray.copyTo(ownedPrevKeyframe)
            prevGray = ownedPrevKeyframe
            hasPrev = true
        }
        return verified
    }

    /** Tracing-point seeds at their [h]-projected current positions, capped
     *  by [TrackerConfig.TOTAL_POINT_CAP] (LK cost is linear in live points
     *  and budget SoCs pay for every one). Projections landing off-frame are
     *  skipped — LK would only fail them next frame. */
    private fun addProjectedSeeds(seeds: List<Point>, h: DoubleArray, frameW: Int, frameH: Int) {
        var budget = TrackerConfig.TOTAL_POINT_CAP - anchorPts.size
        for (p in seeds) {
            if (budget <= 0) break
            val q = Homography.project(h, p.x, p.y)
            val qx = q[0].toDouble()
            val qy = q[1].toDouble()
            if (qx < 0 || qy < 0 || qx >= frameW || qy >= frameH) continue
            anchorPts.add(p)
            currentPts.add(Point(qx, qy))
            budget--
        }
    }

    /** Register the overlay warp units to refine per-region (anchor-CN
     *  rects). Safe to call whenever the flavor changes; membership is
     *  geometric so no correspondence state needs rebuilding. */
    fun setTrackRegions(regions: List<Pair<Int, Rect>>) {
        regionKeys = IntArray(regions.size) { regions[it].first }
        regionRects = Array(regions.size) { inflate(regions[it].second) }
        regionBaselines = IntArray(regions.size)
        recomputeBaselines()
    }

    private fun inflate(r: Rect): Rect {
        val dx = (r.width() * TrackerConfig.REGION_INFLATE_FRAC).toInt()
        val dy = (r.height() * TrackerConfig.REGION_INFLATE_FRAC).toInt()
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun recomputeBaselines() {
        if (regionBaselines.size != regionRects.size) regionBaselines = IntArray(regionRects.size)
        for (ri in regionRects.indices) {
            var n = 0
            val rect = regionRects[ri]
            for (p in anchorPts) if (rect.contains(p.x.toInt(), p.y.toInt())) n++
            regionBaselines[ri] = n
        }
    }

    fun hasAnchor(): Boolean = anchor != null
    fun anchorId(): Long = anchor?.id ?: -1L
    fun currentAnchor(): Anchor? = anchor

    /** Remove and return the current anchor WITHOUT releasing it — the
     *  caller takes ownership (anchor-LRU handoff). */
    fun detachAnchor(): Anchor? {
        val a = anchor ?: return null
        anchor = null
        anchorPts.clear()
        currentPts.clear()
        regionKeys = IntArray(0)
        regionRects = arrayOf()
        regionBaselines = IntArray(0)
        return a
    }

    /** A [probeAnchor] result: RANSAC-verified inlier correspondences and
     *  their fitted anchor→current homography, sufficient to install the
     *  anchor without repeating the ORB pass. */
    class RelockProbe internal constructor(
        /** Verified inlier count — the caller's re-lock criterion. */
        val inliers: Int,
        internal val srcPts: List<Point>,
        internal val dstPts: List<Point>,
        internal val h: DoubleArray,
        internal val frameW: Int,
        internal val frameH: Int,
    )

    /** Geometrically VERIFY [candidate] against [curGray] — the re-lock
     *  probe. Ratio-test descriptor matches are fit with a RANSAC homography
     *  and only its inliers count: raw descriptor count alone can be high on
     *  repetitive text/UI patterns while the matches agree on no geometry,
     *  and re-locking on that restores stale overlays over the wrong scene.
     *  Read-only: no tracker state changes. Null when no verifiable geometry
     *  exists; a strong probe feeds [installFromProbe] directly. */
    fun probeAnchor(candidate: Anchor, curGray: Mat): RelockProbe? {
        if (candidate.descriptors.empty()) return null
        val kps = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(curGray, emptyMask, kps, desc)
        if (desc.empty()) {
            kps.release(); desc.release()
            return null
        }
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(candidate.descriptors, desc, knn, 2)
        val candidateKps = candidate.keypoints.toArray()
        val curKps = kps.toArray()
        val srcPts = ArrayList<Point>()
        val dstPts = ArrayList<Point>()
        for (pair in knn) {
            val m = pair.toArray()
            if (m.isNotEmpty() && !(m.size >= 2 && m[0].distance >= 0.75f * m[1].distance)) {
                srcPts.add(candidateKps[m[0].queryIdx].pt)
                dstPts.add(curKps[m[0].trainIdx].pt)
            }
            pair.release()
        }
        kps.release(); desc.release()
        if (srcPts.size < 4) return null

        fitSrc.fromArray(*srcPts.toTypedArray())
        fitDst.fromArray(*dstPts.toTypedArray())
        val h = Calib3d.findHomography(fitSrc, fitDst, Calib3d.RANSAC, TrackerConfig.RANSAC_REPROJ_PX, fitMask)
        if (h.empty()) {
            h.release() // empty Mat still owns a native header
            return null
        }
        val hArr = matToArray(h)
        h.release()
        if (!Homography.isValid(hArr)) return null
        val inSrc = ArrayList<Point>()
        val inDst = ArrayList<Point>()
        for (i in srcPts.indices) {
            if (fitMask.get(i, 0)[0].toInt() == 1) {
                inSrc.add(srcPts[i])
                inDst.add(dstPts[i])
            }
        }
        return RelockProbe(inSrc.size, inSrc, inDst, hArr, curGray.cols(), curGray.rows())
    }

    /** Verified match count of [candidate] against [curGray] — see
     *  [probeAnchor]. */
    fun verifiedMatchCount(candidate: Anchor, curGray: Mat): Int =
        probeAnchor(candidate, curGray)?.inliers ?: 0

    /** Install [newAnchor] from its successful [probeAnchor] result — the
     *  re-lock path. Takes ownership. The probe's verified inliers become
     *  the live correspondence set (no second ORB pass), and tracing-point
     *  seeds enter at their H-projected positions: the probe proved the
     *  scene is VISIBLE, not that it sits where the keyframe saw it. */
    fun installFromProbe(newAnchor: Anchor, probe: RelockProbe): Int {
        anchor?.release()
        anchor = newAnchor
        framesSinceRematch = 0
        anchorPts = ArrayList(probe.srcPts.take(TrackerConfig.MAX_TRACK_POINTS))
        currentPts = ArrayList(probe.dstPts.take(TrackerConfig.MAX_TRACK_POINTS))
        lastMedianDisp = -1.0
        addProjectedSeeds(newAnchor.seedPts, probe.h, probe.frameW, probe.frameH)
        recomputeBaselines()
        return probe.inliers
    }

    fun clearAnchor() {
        anchor?.release()
        anchor = null
        anchorPts.clear()
        currentPts.clear()
        regionKeys = IntArray(0)
        regionRects = arrayOf()
        regionBaselines = IntArray(0)
        hasPrev = false
        prevGray = null // borrowed — never released here
    }

    /** Probe points for anchorless motion measurement (Idle). */
    private var probePts: List<Point> = emptyList()

    /**
     * Track one frame. [curGray] is the upright CN gray of the current frame,
     * caller-owned and HELD BY REFERENCE as the next call's previous frame —
     * it must stay valid and unmodified until the next [track] call (the
     * converter double-buffers for exactly this).
     *
     * With no anchor installed this degrades to a motion probe: a few dozen
     * good-features corners LK-tracked frame-to-frame purely for the median
     * displacement, which drives the engine's settle gate while Idle.
     */
    fun track(curGray: Mat): TrackMeasurement {
        if (anchor == null) return probeMotion(curGray)

        if (!hasPrev) {
            prevGray = curGray
            hasPrev = true
            return TrackMeasurement(
                null, 0, -1.0, anchorPts.size,
                frameW = curGray.cols(), frameH = curGray.rows(),
            )
        }

        // Starved (blur, occlusion, or plain loss) → try a descriptor
        // re-match before declaring the frame unusable. Throttled: when the
        // scene is genuinely gone, an every-frame re-match burns ~14 ms per
        // frame recovering the same handful of spurious points.
        var didRematch = false
        if (currentPts.size < TrackerConfig.MIN_INLIERS_KEEP) {
            if (++framesSinceRematch >= TrackerConfig.STARVED_REMATCH_INTERVAL_FRAMES) {
                rematch(curGray)
                recomputeBaselines()
                didRematch = true
            } else {
                stepLk(curGray)
            }
        } else if (++framesSinceRematch >= TrackerConfig.DRIFT_RESET_INTERVAL_FRAMES) {
            // Periodic drift reset (~14 ms on device — inside a 33 ms slot).
            rematch(curGray)
            recomputeBaselines()
            didRematch = true
        } else {
            stepLk(curGray)
        }

        val measurement = fitHomography(curGray.cols(), curGray.rows())
        if (didRematch) {
            // A re-match replaces the correspondence set with pure ORB
            // matches, dropping the tracing-point seeds — without
            // replenishment, small regions starve below MIN_REGION_POINTS
            // after the first drift reset and ride the global homography for
            // the anchor's remaining life. Re-seed through the just-verified
            // geometry (healthy fits only); a wrong H can't survive this —
            // the seeds' next LK+RANSAC round prunes them along with it.
            val a = anchor
            val h = measurement.hCn
            if (a != null && h != null && measurement.inliers >= TrackerConfig.MIN_INLIERS_KEEP) {
                addProjectedSeeds(a.seedPts, h, curGray.cols(), curGray.rows())
            }
            recomputeBaselines()
        }
        prevGray = curGray
        return measurement
    }

    /** Frames since the probe corners were last re-detected. */
    private var probeFramesSinceReseed = 0

    /** Anchorless motion probe: forward-LK the previous frame's probe corners
     *  and report their median displacement. Surviving corners carry over —
     *  goodFeaturesToTrack is a whole-image pass, and Idle is the state the
     *  analysis backoff exists to make cheap — with a re-detect when the set
     *  starves or ages out (drifted corners lose distribution). */
    private fun probeMotion(curGray: Mat): TrackMeasurement {
        var motion = -1.0
        val survivors = ArrayList<Point>(probePts.size)
        val prevFrame = prevGray
        if (hasPrev && prevFrame != null && probePts.isNotEmpty()) {
            lkPrevPts.fromArray(*probePts.toTypedArray())
            Video.calcOpticalFlowPyrLK(
                prevFrame, curGray, lkPrevPts, lkNextPts, lkStatus, lkErr,
                lkWin, TrackerConfig.LK_MAX_LEVEL,
            )
            val status = lkStatus.toArray()
            val prevArr = lkPrevPts.toArray()
            val nextArr = lkNextPts.toArray()
            val disps = ArrayList<Double>(prevArr.size)
            for (i in prevArr.indices) {
                if (status.getOrNull(i)?.toInt() == 1) {
                    disps.add(abs(nextArr[i].x - prevArr[i].x) + abs(nextArr[i].y - prevArr[i].y))
                    survivors.add(nextArr[i])
                }
            }
            if (disps.isNotEmpty()) {
                disps.sort()
                motion = disps[disps.size / 2]
            }
        }
        probeFramesSinceReseed++
        if (survivors.size < TrackerConfig.PROBE_RESEED_MIN_POINTS ||
            probeFramesSinceReseed >= TrackerConfig.PROBE_RESEED_INTERVAL_FRAMES
        ) {
            val corners = MatOfPoint()
            Imgproc.goodFeaturesToTrack(curGray, corners, TrackerConfig.PROBE_POINTS, 0.01, 12.0)
            probePts = corners.toArray().toList()
            corners.release()
            probeFramesSinceReseed = 0
        } else {
            probePts = survivors
        }
        prevGray = curGray
        hasPrev = true
        return TrackMeasurement(null, 0, motion, 0, frameW = curGray.cols(), frameH = curGray.rows())
    }

    /** Advance [currentPts] from [prevGray] to [curGray] with forward LK +
     *  backward consistency check, pruning failed points (and their anchor
     *  twins) in lockstep. Also records the median displacement. */
    private var lastMedianDisp = -1.0

    private fun stepLk(curGray: Mat) {
        lastMedianDisp = -1.0
        if (currentPts.isEmpty()) return
        val prevFrame = prevGray ?: return
        lkPrevPts.fromArray(*currentPts.toTypedArray())
        Video.calcOpticalFlowPyrLK(
            prevFrame, curGray, lkPrevPts, lkNextPts, lkStatus, lkErr,
            lkWin, TrackerConfig.LK_MAX_LEVEL,
        )
        // Backward pass: next → prev must land where we started.
        Video.calcOpticalFlowPyrLK(
            curGray, prevFrame, lkNextPts, lkBackPts, lkBackStatus, lkErr,
            lkWin, TrackerConfig.LK_MAX_LEVEL,
        )

        val fwd = lkStatus.toArray()
        val bwd = lkBackStatus.toArray()
        val next = lkNextPts.toArray()
        val back = lkBackPts.toArray()
        val prev = lkPrevPts.toArray()

        val newAnchor = ArrayList<Point>(next.size)
        val newCurrent = ArrayList<Point>(next.size)
        val disps = ArrayList<Double>(next.size)
        for (i in next.indices) {
            if (fwd[i].toInt() != 1 || bwd.getOrNull(i)?.toInt() != 1) continue
            val fbErr = abs(back[i].x - prev[i].x) + abs(back[i].y - prev[i].y)
            if (fbErr > TrackerConfig.LK_FB_EPS) continue
            newAnchor.add(anchorPts[i])
            newCurrent.add(next[i])
            disps.add(abs(next[i].x - prev[i].x) + abs(next[i].y - prev[i].y))
        }
        anchorPts = newAnchor
        currentPts = newCurrent
        if (disps.isNotEmpty()) {
            disps.sort()
            lastMedianDisp = disps[disps.size / 2]
        }
    }

    /** ORB on the current frame + Hamming knn ratio-test match against the
     *  anchor descriptors. Replaces the correspondence set — anchor-space
     *  positions come straight from anchor keypoints, so LK drift resets. */
    private fun rematch(curGray: Mat) {
        val anchor = anchor ?: return
        framesSinceRematch = 0
        val kps = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(curGray, emptyMask, kps, desc)
        if (desc.empty() || anchor.descriptors.empty()) {
            kps.release(); desc.release()
            return
        }
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(anchor.descriptors, desc, knn, 2)
        val anchorKps = anchor.keypoints.toArray()
        val curKps = kps.toArray()

        val newAnchor = ArrayList<Point>()
        val newCurrent = ArrayList<Point>()
        for (pair in knn) {
            val m = pair.toArray()
            // Lowe ratio test — require the best match to clearly beat the
            // runner-up (or be the only candidate).
            val accepted = m.isNotEmpty() &&
                !(m.size >= 2 && m[0].distance >= 0.75f * m[1].distance) &&
                newAnchor.size < TrackerConfig.MAX_TRACK_POINTS
            if (accepted) {
                newAnchor.add(anchorKps[m[0].queryIdx].pt)
                newCurrent.add(curKps[m[0].trainIdx].pt)
            }
            pair.release()
        }
        anchorPts = newAnchor
        currentPts = newCurrent
        lastMedianDisp = -1.0
        kps.release(); desc.release()
    }

    private fun fitHomography(frameW: Int, frameH: Int): TrackMeasurement {
        val (hArr, inliers) = pruneToVerified()
        if (hArr == null) {
            return TrackMeasurement(
                null, 0, lastMedianDisp, anchorPts.size,
                frameW = frameW, frameH = frameH,
            )
        }
        val (perRegionH, perRegionSurvival) = fitRegions(hArr)
        return TrackMeasurement(
            hCn = hArr,
            inliers = inliers,
            medianDispPx = lastMedianDisp,
            trackedPoints = anchorPts.size,
            perRegionH = perRegionH,
            perRegionSurvival = perRegionSurvival,
            frameW = frameW,
            frameH = frameH,
        )
    }

    /** RANSAC-fit the live correspondence set and prune it to inliers (so
     *  outliers don't poison the next LK step). Returns the fitted
     *  anchor→current homography and the inlier count; (null, 0) when the
     *  set is unfittable or the fit degenerate — the set is then left
     *  unpruned (a degenerate fit's mask means nothing). */
    private fun pruneToVerified(): Pair<DoubleArray?, Int> {
        if (anchorPts.size < 4) return null to 0
        fitSrc.fromArray(*anchorPts.toTypedArray())
        fitDst.fromArray(*currentPts.toTypedArray())
        val h = Calib3d.findHomography(fitSrc, fitDst, Calib3d.RANSAC, TrackerConfig.RANSAC_REPROJ_PX, fitMask)
        if (h.empty()) {
            h.release() // empty Mat still owns a native header
            return null to 0
        }
        val hArr = matToArray(h)
        h.release()
        if (!Homography.isValid(hArr)) return null to 0
        val keepAnchor = ArrayList<Point>(anchorPts.size)
        val keepCurrent = ArrayList<Point>(currentPts.size)
        for (i in anchorPts.indices) {
            if (fitMask.get(i, 0)[0].toInt() == 1) {
                keepAnchor.add(anchorPts[i])
                keepCurrent.add(currentPts[i])
            }
        }
        anchorPts = keepAnchor
        currentPts = keepCurrent
        return hArr to keepAnchor.size
    }

    /** Per-region refinement over the (already RANSAC-pruned) inlier set. */
    private fun fitRegions(globalH: DoubleArray?): Pair<Map<Int, DoubleArray>, Map<Int, Float>> {
        if (regionRects.isEmpty()) return emptyMap<Int, DoubleArray>() to emptyMap()
        val hByKey = HashMap<Int, DoubleArray>()
        val survivalByKey = HashMap<Int, Float>()
        val memberAnchor = ArrayList<Point>()
        val memberCurrent = ArrayList<Point>()
        for (ri in regionRects.indices) {
            val rect = regionRects[ri]
            memberAnchor.clear()
            memberCurrent.clear()
            for (i in anchorPts.indices) {
                val p = anchorPts[i]
                if (rect.contains(p.x.toInt(), p.y.toInt())) {
                    memberAnchor.add(p)
                    memberCurrent.add(currentPts[i])
                }
            }
            val baseline = regionBaselines.getOrElse(ri) { 0 }
            if (baseline > 0) {
                survivalByKey[regionKeys[ri]] =
                    (memberAnchor.size.toFloat() / baseline).coerceAtMost(1f)
            }
            if (memberAnchor.size < TrackerConfig.MIN_REGION_POINTS) continue
            // 4-DoF similarity, NOT a full homography: a small label's points
            // sit nearly on a line, and an 8-DoF perspective fit on
            // near-collinear points is degenerate — tiny noise produced wild
            // matrices that smeared overlays across the screen on device. A
            // similarity (rotate+scale+translate) is well-posed from 2 points
            // and can't generate perspective slivers.
            fitSrc.fromArray(*memberAnchor.toTypedArray())
            fitDst.fromArray(*memberCurrent.toTypedArray())
            val affine = Calib3d.estimateAffinePartial2D(
                fitSrc, fitDst, affineInliers, Calib3d.RANSAC, TrackerConfig.RANSAC_REPROJ_PX, 2000, 0.99, 10,
            )
            if (!affine.empty()) {
                val arr = doubleArrayOf(
                    affine.get(0, 0)[0], affine.get(0, 1)[0], affine.get(0, 2)[0],
                    affine.get(1, 0)[0], affine.get(1, 1)[0], affine.get(1, 2)[0],
                    0.0, 0.0, 1.0,
                )
                if (Homography.isValid(arr) && regionAgreesWithGlobal(arr, globalH, regionRects[ri])) {
                    hByKey[regionKeys[ri]] = arr
                }
            }
            affine.release()
        }
        return hByKey to survivalByKey
    }

    /** A refinement must stay a refinement: reject region transforms whose
     *  corner projections diverge from the global homography by more than
     *  [TrackerConfig.REGION_MAX_DEVIATION_CN_PX] — those are degenerate fits,
     *  and the global H is the safer rendering. */
    private fun regionAgreesWithGlobal(regionH: DoubleArray, globalH: DoubleArray?, rect: Rect): Boolean {
        if (globalH == null) return false
        val corners = arrayOf(
            rect.left.toDouble() to rect.top.toDouble(),
            rect.right.toDouble() to rect.top.toDouble(),
            rect.left.toDouble() to rect.bottom.toDouble(),
            rect.right.toDouble() to rect.bottom.toDouble(),
        )
        for ((x, y) in corners) {
            val a = Homography.project(regionH, x, y)
            val b = Homography.project(globalH, x, y)
            val dev = abs(a[0] - b[0]) + abs(a[1] - b[1])
            if (dev > TrackerConfig.REGION_MAX_DEVIATION_CN_PX) return false
        }
        return true
    }

    private fun matToArray(h: Mat): DoubleArray {
        val arr = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) arr[r * 3 + c] = h.get(r, c)[0]
        return Homography.normalize(arr)
    }

    /** Build an [Anchor] from an upright CN gray keyframe (clones [cnGray]):
     *  ORB features for matching + good-features corners as region tracing
     *  points. */
    fun buildAnchor(
        cnGray: Mat,
        id: Long,
        auWidth: Int,
        auHeight: Int,
        cnScale: Double,
        nowMs: Long,
    ): Anchor {
        val kps = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(cnGray, emptyMask, kps, desc)
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(cnGray, corners, TrackerConfig.SEED_FEATURES, 0.01, 6.0)
        val seeds = corners.toArray().toList()
        corners.release()
        return Anchor(
            id = id,
            cnGray = cnGray.clone(),
            keypoints = kps,
            descriptors = desc,
            seedPts = seeds,
            auWidth = auWidth,
            auHeight = auHeight,
            cnScale = cnScale,
            createdAtMs = nowMs,
        )
    }

    fun release() {
        clearAnchor()
        ownedPrevKeyframe.release()
        lkStatus.release()
        lkErr.release()
        emptyMask.release()
        affineInliers.release()
        lkPrevPts.release()
        lkNextPts.release()
        lkBackPts.release()
        lkBackStatus.release()
        fitSrc.release()
        fitDst.release()
        fitMask.release()
    }
}
