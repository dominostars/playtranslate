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
 * Settle is tracker-derived (median LK displacement): STILL_DISP frames
 * count toward the gate, MOVING_DISP frames reset it.
 */
class TrackerEngineTest {

    private companion object {
        const val STILL_DISP = 0.5
        const val MOVING_DISP = 5.0
        const val REAIM_DISP = TrackerConfig.MOTION_RESET_CN_PX + 2.0
    }

    private var nowMs = 1_000_000L
    private fun engine() = TrackerEngine(clock = { nowMs })

    private fun goodMeasurement(
        inliers: Int = 100,
        scale: Double = 1.0,
        disp: Double = STILL_DISP,
    ) = TrackMeasurement(
        hCn = doubleArrayOf(scale, 0.0, 2.0, 0.0, scale, -1.0, 0.0, 0.0, 1.0),
        inliers = inliers,
        medianDispPx = disp,
        trackedPoints = inliers,
    )

    /** Anchorless probe result: no homography, known displacement. */
    private fun probe(disp: Double) = TrackMeasurement(null, 0, disp, 0)

    /** No-signal frame (rematch/first frame): unknown displacement. */
    private val noTrack = TrackMeasurement(null, 0, -1.0, 0)

    /** Drive an engine from IDLE into LOCKED via a successful acquire. */
    private fun lockEngine(e: TrackerEngine) {
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES + 1) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) {
                acquired = true
                return@repeat
            }
        }
        assertTrue("engine never requested the initial acquire", acquired)
        assertEquals(TrackState.ACQUIRING, e.state)
        e.onAcquireFinished(locked = true, nowMs = nowMs)
        assertEquals(TrackState.LOCKED, e.state)
    }

    @Test
    fun idleAcquiresOnlyAfterSettling() {
        val e = engine()
        // Moving frames never open the gate.
        repeat(10) {
            assertFalse(e.onFrame(probe(MOVING_DISP)).requestAcquire)
        }
        // Unknown-displacement frames are neutral: no progress, no reset.
        repeat(5) {
            assertFalse(e.onFrame(noTrack).requestAcquire)
        }
        // Consecutive still frames open it.
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) acquired = true
        }
        assertTrue(acquired)
    }

    @Test
    fun movingFrameResetsSettleProgress() {
        val e = engine()
        repeat(TrackerConfig.SETTLE_FRAMES - 1) { e.onFrame(probe(STILL_DISP)) }
        e.onFrame(probe(MOVING_DISP)) // reset
        // Needs the full run of still frames again.
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES - 1) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) acquired = true
        }
        assertFalse(acquired)
        assertTrue(e.onFrame(probe(STILL_DISP)).requestAcquire)
    }

    @Test
    fun noTextBackoffEscalatesAndMotionResetsIt() {
        val e = engine()
        // First acquire fails (no text).
        lockAttemptFail(e)
        // Immediately settled again: cooldown alone has passed, but the
        // 1 s no-text backoff blocks a retry.
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            assertFalse(e.onFrame(probe(STILL_DISP)).requestAcquire)
        }
        // After the backoff, retry fires...
        nowMs += TrackerConfig.NO_TEXT_BACKOFF_BASE_MS
        assertTrue(e.onFrame(probe(STILL_DISP)).requestAcquire)
        e.onAcquireFinished(locked = false, nowMs = nowMs)
        // ...and the second failure doubles the wait.
        nowMs += TrackerConfig.NO_TEXT_BACKOFF_BASE_MS + 1
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            assertFalse(e.onFrame(probe(STILL_DISP)).requestAcquire)
        }
        // Deliberate re-aiming clears the textless verdict: only the
        // cooldown applies after real motion.
        e.onFrame(probe(REAIM_DISP))
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) acquired = true
        }
        assertTrue(acquired)
    }

    private fun lockAttemptFail(e: TrackerEngine) {
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES + 1) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) acquired = true
        }
        assertTrue(acquired)
        e.onAcquireFinished(locked = false, nowMs = nowMs)
        assertEquals(TrackState.IDLE, e.state)
    }

    @Test
    fun lockedEmitsSmoothedHomography() {
        val e = engine()
        lockEngine(e)
        val d = e.onFrame(goodMeasurement(disp = MOVING_DISP))
        assertEquals(TrackState.LOCKED, d.state)
        assertNotNull(d.hCn)
        assertEquals(100, d.inliers)
    }

    @Test
    fun briefDropoutKeepsLastHomography_hysteresis() {
        val e = engine()
        lockEngine(e)
        e.onFrame(goodMeasurement(disp = MOVING_DISP))
        repeat(TrackerConfig.FRAMES_TO_LOST - 1) {
            val d = e.onFrame(noTrack)
            assertEquals(TrackState.LOCKED, d.state)
            assertNotNull("dropout frame $it should keep last H", d.hCn)
        }
        val lost = e.onFrame(noTrack)
        assertEquals(TrackState.LOST, lost.state)
        assertNull(lost.hCn)
    }

    @Test
    fun lostRelocksOnStrongMeasurementAndSnaps() {
        val e = engine()
        lockEngine(e)
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack) }
        assertEquals(TrackState.LOST, e.state)
        val weak = e.onFrame(goodMeasurement(inliers = TrackerConfig.MIN_INLIERS_ACQUIRE - 1, disp = MOVING_DISP))
        assertEquals(TrackState.LOST, weak.state)
        val strong = e.onFrame(goodMeasurement(inliers = 80, disp = MOVING_DISP))
        assertEquals(TrackState.LOCKED, strong.state)
        assertNotNull(strong.hCn)
    }

    @Test
    fun lostDecaysToIdleAfterGrace() {
        val e = engine()
        lockEngine(e)
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack) }
        assertEquals(TrackState.LOST, e.state)
        repeat(TrackerConfig.LOST_TO_IDLE_FRAMES) { e.onFrame(noTrack) }
        assertEquals(TrackState.IDLE, e.state)
    }

    @Test
    fun stalenessRefreshFiresOnlyWhenSettled() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        // Moving: no refresh, ever.
        repeat(5) {
            assertFalse(e.onFrame(goodMeasurement(disp = MOVING_DISP)).requestAcquire)
        }
        // Settle, then the staleness refresh fires (still emitting H).
        var acquired = false
        var lastH: DoubleArray? = null
        repeat(TrackerConfig.SETTLE_FRAMES) {
            val d = e.onFrame(goodMeasurement(disp = STILL_DISP))
            if (d.requestAcquire) {
                acquired = true
                lastH = d.hCn
            }
        }
        assertTrue(acquired)
        assertNotNull(lastH)
        assertEquals(TrackState.ACQUIRING, e.state)
    }

    @Test
    fun scaleDriftTriggersReacquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        val drifted = goodMeasurement(scale = TrackerConfig.SCALE_DRIFT_REACQUIRE + 0.1, disp = STILL_DISP)
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            if (e.onFrame(drifted).requestAcquire) acquired = true
        }
        assertTrue(acquired)
    }

    @Test
    fun acquiringNeverStacksASecondAcquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES) {
            if (e.onFrame(goodMeasurement()).requestAcquire) acquired = true
        }
        assertTrue(acquired)
        assertEquals(TrackState.ACQUIRING, e.state)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        repeat(5) {
            assertFalse(e.onFrame(goodMeasurement()).requestAcquire)
        }
    }

    @Test
    fun regionSurvivalCollapseTriggersReacquire() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        val collapsed = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.2f))
        var fired = false
        var frames = 0
        while (frames < TrackerConfig.REGION_COLLAPSE_FRAMES + TrackerConfig.SETTLE_FRAMES) {
            frames++
            if (e.onFrame(collapsed).requestAcquire) {
                fired = true
                break
            }
        }
        assertTrue("collapse never fired in $frames frames", fired)
        assertTrue(frames >= TrackerConfig.REGION_COLLAPSE_FRAMES)
    }

    @Test
    fun regionRecoveryResetsCollapseStreak() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        val collapsed = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.2f))
        val recovered = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.9f))
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(e.onFrame(collapsed).requestAcquire)
        }
        e.onFrame(recovered) // streak resets
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(e.onFrame(collapsed).requestAcquire)
        }
    }

    @Test
    fun deadAnchorEscapesToIdleDespiteRematchSpikes() {
        val e = engine()
        lockEngine(e)
        // Moto G failure shape: spurious rematches churn the inliers with
        // spikes that defeat the consecutive-bad hysteresis.
        val churn = intArrayOf(10, 15, 20, 200, 30, 12)
        var frames = 0
        var reachedIdle = false
        while (frames < 120) {
            val m = goodMeasurement(inliers = churn[frames % churn.size], disp = MOVING_DISP)
            val d = e.onFrame(m)
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
        repeat(120) {
            val d = e.onFrame(goodMeasurement(inliers = 280, disp = MOVING_DISP))
            assertEquals(TrackState.LOCKED, d.state)
            assertNotNull(d.hCn)
        }
    }

    @Test
    fun engineNeverEntersAcquiringWhenSessionCannotLaunch() {
        val e = engine()
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            val d = e.onFrame(probe(STILL_DISP), canAcquire = false)
            assertFalse(d.requestAcquire)
        }
        assertEquals(TrackState.IDLE, e.state)
        lockEngine(e)
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            val d = e.onFrame(goodMeasurement(), canAcquire = false)
            assertFalse(d.requestAcquire)
        }
        assertEquals(TrackState.LOCKED, e.state)
    }

    @Test
    fun acquiringWatchdogRevertsToIdle() {
        val e = engine()
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES + 1) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) acquired = true
        }
        assertTrue(acquired)
        assertEquals(TrackState.ACQUIRING, e.state)
        nowMs += TrackerConfig.ACQUIRE_TIMEOUT_MS + 1
        val d = e.onFrame(noTrack)
        assertEquals(TrackState.IDLE, d.state)
        assertNull(d.hCn)
    }

    @Test
    fun perRegionHomographiesPassThroughOnlyWhileEmitting() {
        val e = engine()
        lockEngine(e)
        val refined = goodMeasurement(disp = MOVING_DISP).copy(
            perRegionH = mapOf(1 to doubleArrayOf(1.0, 0.0, 3.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        )
        val d = e.onFrame(refined)
        assertEquals(1, d.perRegionHCn.size)
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack) }
        val lost = e.onFrame(noTrack.copy(perRegionH = refined.perRegionH))
        assertNull(lost.hCn)
        assertTrue(lost.perRegionHCn.isEmpty())
    }

    @Test
    fun homographyMathSanity() {
        val hCn = doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, -4.0, 0.0, 0.0, 1.0)
        val hAu = Homography.cnToAu(hCn, auToCnScale = 0.5)
        assertEquals(20.0, hAu[2], 1e-9)
        assertEquals(-8.0, hAu[5], 1e-9)
        assertEquals(2f, Homography.scaleOf(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)), 1e-4f)
        val smoothed = Homography.IDENTITY.copyOf()
        val fresh = doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        repeat(20) { Homography.emaInPlace(smoothed, fresh, 0.6f) }
        assertEquals(10.0, smoothed[2], 0.01)
    }
}
