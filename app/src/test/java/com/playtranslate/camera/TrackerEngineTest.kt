package com.playtranslate.camera

import com.playtranslate.camera.tracker.Homography
import com.playtranslate.camera.tracker.TrackMeasurement
import com.playtranslate.camera.tracker.TrackState
import com.playtranslate.camera.tracker.TrackerConfig
import com.playtranslate.camera.tracker.TrackerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the tracker policy state machine — synthetic
 * [TrackMeasurement] sequences, no camera, no OpenCV (camera plan §9).
 */
class TrackerEngineTest {

    private var nowMs = 1_000_000L
    private fun engine() = TrackerEngine(clock = { nowMs })

    private fun goodMeasurement(
        inliers: Int = 100,
        scale: Double = 1.0,
    ) = TrackMeasurement(
        hCn = doubleArrayOf(scale, 0.0, 2.0, 0.0, scale, -1.0, 0.0, 0.0, 1.0),
        inliers = inliers,
        medianDispPx = 0.4,
        trackedPoints = inliers,
    )

    private val noTrack = TrackMeasurement(null, 0, -1.0, 0)

    /** Drive an engine from IDLE into LOCKED via a successful acquire. */
    private fun lockEngine(e: TrackerEngine) {
        val d = e.onFrame(noTrack, settled = true, sceneChanged = true)
        assertTrue(d.requestAcquire)
        assertEquals(TrackState.ACQUIRING, e.state)
        e.onAcquireFinished(locked = true, nowMs = nowMs)
        assertEquals(TrackState.LOCKED, e.state)
    }

    @Test
    fun idleAcquiresOnlyWhenSettledAndSceneChanged() {
        val e = engine()
        assertFalse(e.onFrame(noTrack, settled = false, sceneChanged = true).requestAcquire)
        assertFalse(e.onFrame(noTrack, settled = true, sceneChanged = false).requestAcquire)
        assertTrue(e.onFrame(noTrack, settled = true, sceneChanged = true).requestAcquire)
    }

    @Test
    fun acquireCooldownBlocksImmediateRetry() {
        val e = engine()
        assertTrue(e.onFrame(noTrack, settled = true, sceneChanged = true).requestAcquire)
        e.onAcquireFinished(locked = false, nowMs = nowMs) // failed → IDLE
        assertEquals(TrackState.IDLE, e.state)
        // Same instant: cooldown blocks.
        assertFalse(e.onFrame(noTrack, settled = true, sceneChanged = true).requestAcquire)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        assertTrue(e.onFrame(noTrack, settled = true, sceneChanged = true).requestAcquire)
    }

    @Test
    fun lockedEmitsSmoothedHomography() {
        val e = engine()
        lockEngine(e)
        val d = e.onFrame(goodMeasurement(), settled = false, sceneChanged = false)
        assertEquals(TrackState.LOCKED, d.state)
        assertNotNull(d.hCn)
        assertEquals(100, d.inliers)
    }

    @Test
    fun briefDropoutKeepsLastHomography_hysteresis() {
        val e = engine()
        lockEngine(e)
        e.onFrame(goodMeasurement(), settled = false, sceneChanged = false)
        // A few bad frames (below FRAMES_TO_LOST) must not blank the overlay.
        repeat(TrackerConfig.FRAMES_TO_LOST - 1) {
            val d = e.onFrame(noTrack, settled = false, sceneChanged = false)
            assertEquals(TrackState.LOCKED, d.state)
            assertNotNull("dropout frame $it should keep last H", d.hCn)
        }
        // One more pushes it over the edge → LOST, overlays hidden.
        val lost = e.onFrame(noTrack, settled = false, sceneChanged = false)
        assertEquals(TrackState.LOST, lost.state)
        assertNull(lost.hCn)
    }

    @Test
    fun lostRelocksOnStrongMeasurementAndSnaps() {
        val e = engine()
        lockEngine(e)
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack, settled = false, sceneChanged = false) }
        assertEquals(TrackState.LOST, e.state)
        // Weak recovery (below the acquire floor) does NOT re-lock.
        val weak = e.onFrame(goodMeasurement(inliers = TrackerConfig.MIN_INLIERS_ACQUIRE - 1), false, false)
        assertEquals(TrackState.LOST, weak.state)
        // Strong recovery re-locks and emits immediately.
        val strong = e.onFrame(goodMeasurement(inliers = 80), false, false)
        assertEquals(TrackState.LOCKED, strong.state)
        assertNotNull(strong.hCn)
    }

    @Test
    fun lostDecaysToIdleAfterGrace() {
        val e = engine()
        lockEngine(e)
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack, settled = false, sceneChanged = false) }
        assertEquals(TrackState.LOST, e.state)
        repeat(TrackerConfig.LOST_TO_IDLE_FRAMES) { e.onFrame(noTrack, settled = false, sceneChanged = false) }
        assertEquals(TrackState.IDLE, e.state)
    }

    @Test
    fun stalenessRefreshFiresOnlyWhenSettled() {
        val e = engine()
        lockEngine(e)
        e.onFrame(goodMeasurement(), settled = false, sceneChanged = false)
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        // Moving: no refresh.
        assertFalse(e.onFrame(goodMeasurement(), settled = false, sceneChanged = false).requestAcquire)
        // Settled: refresh fires (and keeps emitting the current H meanwhile).
        val d = e.onFrame(goodMeasurement(), settled = true, sceneChanged = false)
        assertTrue(d.requestAcquire)
        assertNotNull(d.hCn)
        assertEquals(TrackState.ACQUIRING, e.state)
    }

    @Test
    fun scaleDriftTriggersReacquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        // Zoomed way in: scale beyond the drift threshold.
        val drifted = goodMeasurement(scale = TrackerConfig.SCALE_DRIFT_REACQUIRE + 0.1)
        // Feed a couple frames so the smoothed H reflects the drifted scale.
        e.onFrame(drifted, settled = false, sceneChanged = false)
        e.onFrame(drifted, settled = false, sceneChanged = false)
        val d = e.onFrame(drifted, settled = true, sceneChanged = false)
        assertTrue(d.requestAcquire)
    }

    @Test
    fun acquiringNeverStacksASecondAcquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        assertTrue(e.onFrame(goodMeasurement(), settled = true, sceneChanged = true).requestAcquire)
        assertEquals(TrackState.ACQUIRING, e.state)
        // While acquiring, no further acquire requests regardless of signals.
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        assertFalse(e.onFrame(goodMeasurement(), settled = true, sceneChanged = true).requestAcquire)
    }

    @Test
    fun regionSurvivalCollapseTriggersReacquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        // One region's tracing points collapse while global tracking stays
        // healthy — the Huawei per-line re-OCR trigger.
        val collapsed = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.2f))
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(
                "streak frame $it must not fire yet",
                e.onFrame(collapsed, settled = true, sceneChanged = false).requestAcquire,
            )
        }
        assertTrue(e.onFrame(collapsed, settled = true, sceneChanged = false).requestAcquire)
    }

    @Test
    fun regionRecoveryResetsCollapseStreak() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        val collapsed = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.2f))
        val recovered = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.9f))
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            e.onFrame(collapsed, settled = true, sceneChanged = false)
        }
        e.onFrame(recovered, settled = true, sceneChanged = false) // streak resets
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(e.onFrame(collapsed, settled = true, sceneChanged = false).requestAcquire)
        }
    }

    @Test
    fun perRegionHomographiesPassThroughOnlyWhileEmitting() {
        val e = engine()
        lockEngine(e)
        val refined = goodMeasurement().copy(
            perRegionH = mapOf(1 to doubleArrayOf(1.0, 0.0, 3.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        )
        val d = e.onFrame(refined, settled = false, sceneChanged = false)
        assertEquals(1, d.perRegionHCn.size)
        // Once LOST (h null), the per-region map must empty too.
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack, false, false) }
        val lost = e.onFrame(noTrack.copy(perRegionH = refined.perRegionH), false, false)
        assertNull(lost.hCn)
        assertTrue(lost.perRegionHCn.isEmpty())
    }

    @Test
    fun deadAnchorEscapesToIdleDespiteRematchSpikes() {
        val e = engine()
        lockEngine(e)
        // Replays the 2026-07-07 Moto G failure shape: the view no longer
        // matches the keyframe (sceneChanged pinned true) while spurious
        // rematches make inliers churn — spikes above MIN_INLIERS_KEEP kept
        // resetting the consecutive-bad hysteresis, pinning LOCKED for 27 s.
        val churn = intArrayOf(10, 15, 20, 200, 30, 12)
        var frames = 0
        var reachedIdle = false
        while (frames < 120) {
            val m = goodMeasurement(inliers = churn[frames % churn.size])
            val d = e.onFrame(m, settled = false, sceneChanged = true)
            frames++
            if (d.state == TrackState.IDLE) {
                reachedIdle = true
                assertNull("overlays must hide on dead anchor", d.hCn)
                break
            }
        }
        assertTrue("dead anchor never escaped to IDLE in $frames frames", reachedIdle)
    }

    @Test
    fun healthyPanNeverTripsDeadAnchorEscape() {
        val e = engine()
        lockEngine(e)
        // Panning across the same surface: sceneChanged is also pinned true
        // (the view left the keyframe), but tracking stays strong — the
        // escape must NOT fire.
        repeat(120) {
            val d = e.onFrame(goodMeasurement(inliers = 280), settled = false, sceneChanged = true)
            assertEquals(TrackState.LOCKED, d.state)
            assertNotNull(d.hCn)
        }
    }

    @Test
    fun homographyMathSanity() {
        // cnToAu conjugation: pure translation in CN scales by 1/s in AU.
        val hCn = doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, -4.0, 0.0, 0.0, 1.0)
        val hAu = Homography.cnToAu(hCn, auToCnScale = 0.5)
        assertEquals(20.0, hAu[2], 1e-9)
        assertEquals(-8.0, hAu[5], 1e-9)
        // scaleOf: a diag(2,2) mapping has scale 2.
        assertEquals(2f, Homography.scaleOf(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)), 1e-4f)
        // EMA converges toward the fresh value.
        val smoothed = Homography.IDENTITY.copyOf()
        val fresh = doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(20) { Homography.emaInPlace(smoothed, fresh, 0.6f) }
        assertEquals(10.0, smoothed[2], 0.01)
    }
}
