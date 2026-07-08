package com.playtranslate.camera

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import com.playtranslate.camera.tracker.FrameTracker
import com.playtranslate.camera.tracker.Homography
import com.playtranslate.camera.tracker.TrackState
import com.playtranslate.camera.tracker.TrackerConfig
import com.playtranslate.camera.tracker.TrackerEngine
import kotlin.math.abs
import kotlin.random.Random

/**
 * Phase-2 perf spike (camera plan §7): measure the per-stage cost of the
 * tracker's OpenCV primitives at canonical resolution (960 long edge) on a
 * real device, BEFORE building the tracker around them. Budget to confirm:
 * the per-frame stages (pyramidal LK + RANSAC homography) must fit well
 * inside ~25 ms; ORB detect+compute and descriptor matching run async
 * (acquire / periodic drift reset) so they only need to stay sub-~100 ms.
 *
 * Synthetic text-page-like frame: gray page, dark text-line bars, salt
 * noise for corner texture; the "next" frame is a known small homography
 * (hand-jitter scale translate+rotate) so LK/RANSAC do real work and the
 * recovered H can be sanity-checked against ground truth.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest
 *        -Pandroid.testInstrumentationRunnerArguments.class=com.playtranslate.camera.CameraTrackerBenchmark
 * Read the "CamBench" logcat lines (also echoed into the test output).
 */
@RunWith(AndroidJUnit4::class)
class CameraTrackerBenchmark {

    private companion object {
        const val TAG = "CamBench"
        const val CN_W = 540 // portrait canonical: 540×960
        const val CN_H = 960
        const val ORB_FEATURES = 1000
        const val LK_POINTS = 300
        const val WARMUP = 3
        const val RUNS = 20
    }

    private fun ensureOpenCv() {
        check(OpenCVLoader.initLocal()) { "OpenCV initLocal() failed" }
        Log.i(TAG, "OpenCV ${Core.VERSION}")
    }

    /** Text-page-like synthetic frame: light page, dark text-line bars,
     *  sparse salt noise. Deterministic via [seed]. */
    private fun syntheticPage(seed: Int): Mat {
        val rnd = Random(seed)
        val img = Mat(CN_H, CN_W, CvType.CV_8UC1, Scalar(200.0))
        // Text-line bars: short dark rectangles in ragged rows.
        var y = 40
        while (y < CN_H - 40) {
            var x = 20 + rnd.nextInt(40)
            val lineH = 12 + rnd.nextInt(10)
            while (x < CN_W - 60) {
                val wordW = 25 + rnd.nextInt(70)
                Imgproc.rectangle(
                    img, Point(x.toDouble(), y.toDouble()),
                    Point((x + wordW).toDouble(), (y + lineH).toDouble()),
                    Scalar(30.0 + rnd.nextInt(40)), -1,
                )
                x += wordW + 8 + rnd.nextInt(18)
            }
            y += lineH + 14 + rnd.nextInt(16)
        }
        // Salt noise for corner texture off the text.
        repeat(1500) {
            val px = rnd.nextInt(CN_W)
            val py = rnd.nextInt(CN_H)
            img.put(py, px, rnd.nextDouble(0.0, 255.0))
        }
        return img
    }

    /** Hand-jitter-scale ground-truth homography: ~5 px translate + 0.6° rotate. */
    private fun jitterHomography(): Mat {
        val affine = Imgproc.getRotationMatrix2D(Point(CN_W / 2.0, CN_H / 2.0), 0.6, 1.0)
        val h = Mat.eye(3, 3, CvType.CV_64F)
        for (r in 0 until 2) for (c in 0 until 3) h.put(r, c, affine.get(r, c)[0])
        h.put(0, 2, h.get(0, 2)[0] + 5.0)
        h.put(1, 2, h.get(1, 2)[0] - 3.0)
        return h
    }

    private inline fun medianMs(block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val times = DoubleArray(RUNS)
        for (i in 0 until RUNS) {
            val t0 = System.nanoTime()
            block()
            times[i] = (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[RUNS / 2]
    }

    @Test
    fun benchmarkTrackerPrimitives() {
        ensureOpenCv()
        val base = syntheticPage(7)
        val hTrue = jitterHomography()
        val warped = Mat()
        Imgproc.warpPerspective(base, warped, hTrue, Size(CN_W.toDouble(), CN_H.toDouble()))

        val results = StringBuilder("tracker primitives @${CN_W}x$CN_H (median of $RUNS):\n")

        // ── Async-path stages (acquire / periodic drift reset) ──
        val orb = ORB.create(ORB_FEATURES)
        val baseKps = MatOfKeyPoint()
        val baseDesc = Mat()
        val orbMs = medianMs {
            baseKps.release(); baseDesc.release()
            orb.detectAndCompute(base, Mat(), baseKps, baseDesc)
        }
        results.append("  ORB detect+compute (${baseKps.rows()} kps): %.1f ms\n".format(orbMs))

        val warpKps = MatOfKeyPoint()
        val warpDesc = Mat()
        orb.detectAndCompute(warped, Mat(), warpKps, warpDesc)
        val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
        val matchMs = medianMs {
            val knn = mutableListOf<org.opencv.core.MatOfDMatch>()
            matcher.knnMatch(baseDesc, warpDesc, knn, 2)
        }
        results.append("  BF-Hamming knnMatch(2): %.1f ms\n".format(matchMs))

        val gftt = MatOfPoint()
        val gfttMs = medianMs {
            Imgproc.goodFeaturesToTrack(base, gftt, LK_POINTS, 0.01, 8.0)
        }
        results.append("  goodFeaturesToTrack($LK_POINTS): %.1f ms\n".format(gfttMs))

        // ── Per-frame stages (must fit the ~25 ms budget) ──
        val prevPts = MatOfPoint2f(*gftt.toArray().map { Point(it.x, it.y) }.toTypedArray())
        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val lkMs = medianMs {
            Video.calcOpticalFlowPyrLK(
                base, warped, prevPts, nextPts, status, err,
                Size(21.0, 21.0), 3,
            )
        }
        results.append("  pyrLK(${prevPts.rows()} pts, win21, lvl3): %.1f ms\n".format(lkMs))

        val good = status.toArray()
        val src = mutableListOf<Point>()
        val dst = mutableListOf<Point>()
        val prevArr = prevPts.toArray()
        val nextArr = nextPts.toArray()
        for (i in good.indices) {
            if (good[i].toInt() == 1) {
                src.add(prevArr[i]); dst.add(nextArr[i])
            }
        }
        val srcMat = MatOfPoint2f(*src.toTypedArray())
        val dstMat = MatOfPoint2f(*dst.toTypedArray())
        var hEst = Mat()
        val ransacMs = medianMs {
            hEst = Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, 3.0)
        }
        results.append("  findHomography RANSAC (${src.size} corr): %.1f ms\n".format(ransacMs))
        results.append(
            "  PER-FRAME total (LK+RANSAC): %.1f ms | async total (ORB+match): %.1f ms"
                .format(lkMs + ransacMs, orbMs + matchMs)
        )
        Log.i(TAG, results.toString())
        println(results.toString())

        // Sanity: the recovered homography reproduces ground truth (project a
        // corner through both, compare within 2 px).
        assertTrue("homography estimation returned empty", !hEst.empty())
        val probe = Point(120.0, 200.0)
        fun project(h: Mat, p: Point): Point {
            val x = h.get(0, 0)[0] * p.x + h.get(0, 1)[0] * p.y + h.get(0, 2)[0]
            val y = h.get(1, 0)[0] * p.x + h.get(1, 1)[0] * p.y + h.get(1, 2)[0]
            val w = h.get(2, 0)[0] * p.x + h.get(2, 1)[0] * p.y + h.get(2, 2)[0]
            return Point(x / w, y / w)
        }
        val pTrue = project(hTrue, probe)
        val pEst = project(hEst, probe)
        assertTrue(
            "recovered H off ground truth: true=$pTrue est=$pEst",
            abs(pTrue.x - pEst.x) < 2.0 && abs(pTrue.y - pEst.y) < 2.0,
        )
    }

    /**
     * Adversarial re-lock probe: an anchor must be verifiable against a
     * warped view of ITS OWN scene, and must NOT verify against a different
     * page with the same text-like texture statistics — raw descriptor
     * counts alone pass on repetitive patterns (glyphs match glyphs), which
     * is exactly how a false re-lock would restore stale overlays over the
     * wrong scene.
     */
    @Test
    fun relockProbeRejectsWrongSceneAcceptsOwnScene() {
        ensureOpenCv()
        val tracker = FrameTracker()
        val pageA = syntheticPage(21)
        val pageB = syntheticPage(99) // same generator, different layout
        val warpedA = Mat()
        val hTrue = jitterHomography()
        Imgproc.warpPerspective(pageA, warpedA, hTrue, Size(CN_W.toDouble(), CN_H.toDouble()))
        try {
            val anchor = tracker.buildAnchor(pageA, 7L, 1080, 1920, 0.5, 0L)
            val ownScene = tracker.verifiedMatchCount(anchor, warpedA)
            val wrongScene = tracker.verifiedMatchCount(anchor, pageB)
            Log.i(TAG, "relock probe: ownScene=$ownScene wrongScene=$wrongScene")
            println("relock probe: ownScene=$ownScene wrongScene=$wrongScene")
            assertTrue(
                "own scene must verify (got $ownScene)",
                ownScene >= TrackerConfig.MIN_INLIERS_ACQUIRE,
            )
            assertTrue(
                "wrong scene must NOT verify (got $wrongScene)",
                wrongScene < TrackerConfig.MIN_INLIERS_ACQUIRE,
            )
            anchor.release()
        } finally {
            tracker.release()
            pageA.release()
            pageB.release()
            warpedA.release()
            hTrue.release()
        }
    }

    /**
     * End-to-end synthetic trace of the production tracker (FrameTracker +
     * TrackerEngine, the exact classes CameraSession drives): install an
     * anchor, then feed a 60-frame continuous pan/rotate sequence with known
     * ground-truth homographies. Asserts the engine stays LOCKED, the emitted
     * (EMA-smoothed) homography tracks ground truth within a few px, and the
     * per-region refinements are produced. Covers everything but the camera,
     * OCR, and UI.
     */
    @Test
    fun syntheticPanSequenceStaysLockedAndAccurate() {
        ensureOpenCv()
        var nowMs = 1_000_000L
        val tracker = FrameTracker()
        val engine = TrackerEngine(clock = { nowMs })
        val base = syntheticPage(11)

        try {
            // Acquire, exactly as CameraSession does it: settle is derived
            // from measurement displacement, so feed still probe frames.
            var requested = false
            repeat(TrackerConfig.SETTLE_FRAMES + 1) {
                if (engine.onFrame(
                        com.playtranslate.camera.tracker.TrackMeasurement(null, 0, 0.5, 0),
                        nowMs = nowMs,
                    ).requestAcquire
                ) requested = true
            }
            assertTrue(requested)
            val acquireId = engine.beginAcquire(nowMs = nowMs)
            assertTrue(acquireId != 0L)
            val anchor = tracker.buildAnchor(base, 1L, 1080, 1920, 0.5, nowMs)
            val seeded = tracker.installAnchor(anchor, base)
            engine.finishAcquire(acquireId, locked = seeded >= TrackerConfig.MIN_INLIERS_ACQUIRE, nowMs = nowMs)
            assertTrue("only $seeded correspondences seeded", seeded >= TrackerConfig.MIN_INLIERS_ACQUIRE)
            // Two tracked regions over text rows (CN coords).
            tracker.setTrackRegions(
                listOf(
                    0 to android.graphics.Rect(40, 100, 500, 300),
                    1 to android.graphics.Rect(40, 500, 500, 750),
                )
            )

            val cur = Mat()
            var lockedFrames = 0
            var regionFrames = 0
            var maxErr = 0.0
            val probe = Point(270.0, 480.0)
            for (f in 1..60) {
                nowMs += 33
                // Continuous pan + slow rotation, always warped fresh from base.
                val affine = Imgproc.getRotationMatrix2D(Point(CN_W / 2.0, CN_H / 2.0), 0.02 * f, 1.0)
                val hTrue = Mat.eye(3, 3, CvType.CV_64F)
                for (r in 0 until 2) for (c in 0 until 3) hTrue.put(r, c, affine.get(r, c)[0])
                hTrue.put(0, 2, hTrue.get(0, 2)[0] + 1.2 * f)
                hTrue.put(1, 2, hTrue.get(1, 2)[0] - 0.8 * f)
                Imgproc.warpPerspective(base, cur, hTrue, Size(CN_W.toDouble(), CN_H.toDouble()))

                val m = tracker.track(cur)
                val d = engine.onFrame(m, nowMs = nowMs)
                if (d.state == TrackState.LOCKED && d.hCn != null) {
                    lockedFrames++
                    if (d.perRegionHCn.size == 2) regionFrames++
                    val est = Homography.project(d.hCn!!, probe.x, probe.y)
                    val tx = hTrue.get(0, 0)[0] * probe.x + hTrue.get(0, 1)[0] * probe.y + hTrue.get(0, 2)[0]
                    val ty = hTrue.get(1, 0)[0] * probe.x + hTrue.get(1, 1)[0] * probe.y + hTrue.get(1, 2)[0]
                    val err = abs(est[0] - tx) + abs(est[1] - ty)
                    if (err > maxErr) maxErr = err
                }
                hTrue.release()
                affine.release()
            }
            Log.i(TAG, "pan sequence: $lockedFrames/60 locked, $regionFrames with both region Hs, maxErr=%.2fpx".format(maxErr))
            println("pan sequence: $lockedFrames/60 locked, $regionFrames with both region Hs, maxErr=%.2f px".format(maxErr))

            assertTrue("locked only $lockedFrames/60 frames", lockedFrames >= 54)
            assertTrue("per-region Hs on only $regionFrames frames", regionFrames >= 45)
            // EMA lags a continuously-moving target by ~(1-α)/α of the
            // per-frame delta (~1.3 px here); anything under 5 px is healthy.
            assertTrue("max projection error %.2f px".format(maxErr), maxErr < 5.0)
            cur.release()
        } finally {
            tracker.release()
            base.release()
        }
    }
}
