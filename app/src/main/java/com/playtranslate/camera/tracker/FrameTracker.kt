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

    /** Previous CN gray frame (copy — the caller's Mat is reused). */
    private val prevGray = Mat()
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

    // Reused OpenCV buffers.
    private val lkStatus = MatOfByte()
    private val lkErr = MatOfFloat()
    private val lkWin = Size(TrackerConfig.LK_WIN_SIZE, TrackerConfig.LK_WIN_SIZE)

    /** Install a new anchor (takes ownership; releases the old one). Seeds
     *  correspondences by ORB-matching against [curGray] (≈ the keyframe at
     *  acquire time) plus the anchor's good-features tracing points. */
    fun installAnchor(newAnchor: Anchor, curGray: Mat): Int {
        anchor?.release()
        anchor = newAnchor
        framesSinceRematch = 0
        rematch(curGray)
        // Tracing-point seeds: at acquire, current ≈ keyframe, so the seed's
        // current position IS its anchor position. Capped — LK cost is linear
        // in live points and budget SoCs pay for every one.
        val seedBudget = TrackerConfig.TOTAL_POINT_CAP - anchorPts.size
        for ((i, p) in newAnchor.seedPts.withIndex()) {
            if (i >= seedBudget) break
            anchorPts.add(p)
            currentPts.add(Point(p.x, p.y))
        }
        recomputeBaselines()
        curGray.copyTo(prevGray)
        hasPrev = true
        return anchorPts.size
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

    fun clearAnchor() {
        anchor?.release()
        anchor = null
        anchorPts.clear()
        currentPts.clear()
        regionKeys = IntArray(0)
        regionRects = arrayOf()
        regionBaselines = IntArray(0)
        hasPrev = false
    }

    /**
     * Track one frame. [curGray] is the upright CN gray of the current frame
     * (caller-owned; copied internally for the next LK pass).
     */
    fun track(curGray: Mat): TrackMeasurement {
        if (anchor == null) return TrackMeasurement(null, 0, -1.0, 0)

        if (!hasPrev) {
            curGray.copyTo(prevGray)
            hasPrev = true
            return TrackMeasurement(null, 0, -1.0, anchorPts.size)
        }

        // Starved (blur, occlusion, or plain loss) → try a descriptor
        // re-match before declaring the frame unusable.
        if (currentPts.size < TrackerConfig.MIN_INLIERS_KEEP) {
            rematch(curGray)
            recomputeBaselines()
        } else if (++framesSinceRematch >= TrackerConfig.DRIFT_RESET_INTERVAL_FRAMES) {
            // Periodic drift reset (~14 ms on device — inside a 33 ms slot).
            rematch(curGray)
            recomputeBaselines()
        } else {
            stepLk(curGray)
        }

        val measurement = fitHomography()
        curGray.copyTo(prevGray)
        return measurement
    }

    /** Advance [currentPts] from [prevGray] to [curGray] with forward LK +
     *  backward consistency check, pruning failed points (and their anchor
     *  twins) in lockstep. Also records the median displacement. */
    private var lastMedianDisp = -1.0

    private fun stepLk(curGray: Mat) {
        lastMedianDisp = -1.0
        if (currentPts.isEmpty()) return
        val prevPts = MatOfPoint2f(*currentPts.toTypedArray())
        val nextPts = MatOfPoint2f()
        Video.calcOpticalFlowPyrLK(
            prevGray, curGray, prevPts, nextPts, lkStatus, lkErr,
            lkWin, TrackerConfig.LK_MAX_LEVEL,
        )
        // Backward pass: next → prev must land where we started.
        val backPts = MatOfPoint2f()
        val backStatus = MatOfByte()
        Video.calcOpticalFlowPyrLK(
            curGray, prevGray, nextPts, backPts, backStatus, lkErr,
            lkWin, TrackerConfig.LK_MAX_LEVEL,
        )

        val fwd = lkStatus.toArray()
        val bwd = backStatus.toArray()
        val next = nextPts.toArray()
        val back = backPts.toArray()
        val prev = prevPts.toArray()

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
        prevPts.release(); nextPts.release(); backPts.release(); backStatus.release()
    }

    /** ORB on the current frame + Hamming knn ratio-test match against the
     *  anchor descriptors. Replaces the correspondence set — anchor-space
     *  positions come straight from anchor keypoints, so LK drift resets. */
    private fun rematch(curGray: Mat) {
        val anchor = anchor ?: return
        framesSinceRematch = 0
        val kps = MatOfKeyPoint()
        val desc = Mat()
        orb.detectAndCompute(curGray, Mat(), kps, desc)
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
            if (m.isEmpty()) continue
            // Lowe ratio test — require the best match to clearly beat the
            // runner-up (or be the only candidate).
            if (m.size >= 2 && m[0].distance >= 0.75f * m[1].distance) continue
            newAnchor.add(anchorKps[m[0].queryIdx].pt)
            newCurrent.add(curKps[m[0].trainIdx].pt)
            if (newAnchor.size >= TrackerConfig.MAX_TRACK_POINTS) break
        }
        anchorPts = newAnchor
        currentPts = newCurrent
        lastMedianDisp = -1.0
        kps.release(); desc.release()
    }

    private fun fitHomography(): TrackMeasurement {
        if (anchorPts.size < 4) {
            return TrackMeasurement(null, 0, lastMedianDisp, anchorPts.size)
        }
        val src = MatOfPoint2f(*anchorPts.toTypedArray())
        val dst = MatOfPoint2f(*currentPts.toTypedArray())
        val mask = Mat()
        val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, TrackerConfig.RANSAC_REPROJ_PX, mask)
        src.release(); dst.release()
        if (h.empty()) {
            mask.release()
            return TrackMeasurement(null, 0, lastMedianDisp, anchorPts.size)
        }

        // Prune RANSAC outliers so they don't poison the next LK step.
        var inliers = 0
        val keepAnchor = ArrayList<Point>(anchorPts.size)
        val keepCurrent = ArrayList<Point>(currentPts.size)
        for (i in anchorPts.indices) {
            if (mask.get(i, 0)[0].toInt() == 1) {
                inliers++
                keepAnchor.add(anchorPts[i])
                keepCurrent.add(currentPts[i])
            }
        }
        anchorPts = keepAnchor
        currentPts = keepCurrent
        mask.release()

        val hArr = matToArray(h)
        h.release()
        val valid = Homography.isValid(hArr)

        val (perRegionH, perRegionSurvival) = fitRegions()
        return TrackMeasurement(
            hCn = if (valid) hArr else null,
            inliers = inliers,
            medianDispPx = lastMedianDisp,
            trackedPoints = anchorPts.size,
            perRegionH = perRegionH,
            perRegionSurvival = perRegionSurvival,
        )
    }

    /** Per-region refinement over the (already RANSAC-pruned) inlier set. */
    private fun fitRegions(): Pair<Map<Int, DoubleArray>, Map<Int, Float>> {
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
            val src = MatOfPoint2f(*memberAnchor.toTypedArray())
            val dst = MatOfPoint2f(*memberCurrent.toTypedArray())
            val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, TrackerConfig.RANSAC_REPROJ_PX)
            src.release(); dst.release()
            if (!h.empty()) {
                val arr = matToArray(h)
                if (Homography.isValid(arr)) hByKey[regionKeys[ri]] = arr
            }
            h.release()
        }
        return hByKey to survivalByKey
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
        orb.detectAndCompute(cnGray, Mat(), kps, desc)
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
        prevGray.release()
        lkStatus.release()
        lkErr.release()
    }
}
