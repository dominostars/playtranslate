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

    /** Settle until the engine OFFERS an acquire; fail if it never does. */
    private fun settleUntilOffer(e: TrackerEngine): Boolean {
        repeat(TrackerConfig.SETTLE_FRAMES + 2) {
            if (e.onFrame(probe(STILL_DISP)).requestAcquire) return true
        }
        return false
    }

    /** Drive an engine from IDLE into LOCKED via a successful acquire,
     *  using the full offer → begin → finish protocol. */
    private fun lockEngine(e: TrackerEngine) {
        assertTrue("engine never offered the initial acquire", settleUntilOffer(e))
        val id = e.beginAcquire(nowMs = nowMs)
        assertTrue(id != 0L)
        assertEquals(TrackState.ACQUIRING, e.state)
        e.finishAcquire(id, locked = true, nowMs = nowMs)
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
        val id2 = e.beginAcquire(nowMs = nowMs)
        e.finishAcquire(id2, locked = false, nowMs = nowMs)
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
        assertTrue(settleUntilOffer(e))
        val id = e.beginAcquire(nowMs = nowMs)
        assertTrue(id != 0L)
        e.finishAcquire(id, locked = false, nowMs = nowMs)
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
        // An offer does NOT transition; only the session's launch does.
        assertEquals(TrackState.LOCKED, e.state)
        assertTrue(e.beginAcquire(nowMs = nowMs) != 0L)
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
        assertTrue(e.beginAcquire(nowMs = nowMs) != 0L)
        assertEquals(TrackState.ACQUIRING, e.state)
        // While ACQUIRING: no further offers, and a second begin is refused.
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        repeat(5) {
            assertFalse(e.onFrame(goodMeasurement()).requestAcquire)
        }
        assertEquals(0L, e.beginAcquire(nowMs = nowMs))
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
    fun regionReplacementResetsCollapseStreaks() {
        val e = engine()
        lockEngine(e)
        nowMs += TrackerConfig.ACQUIRE_COOLDOWN_MS + 1
        val collapsed = goodMeasurement().copy(perRegionSurvival = mapOf(3 to 0.2f))
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(e.onFrame(collapsed).requestAcquire)
        }
        // Flavor change re-registers the regions: key 3 now names a DIFFERENT
        // region, so the old streak must not count it toward the trigger.
        e.onRegionsReplaced()
        repeat(TrackerConfig.REGION_COLLAPSE_FRAMES - 1) {
            assertFalse(e.onFrame(collapsed).requestAcquire)
        }
        // An uninterrupted streak still fires at the threshold.
        assertTrue(e.onFrame(collapsed).requestAcquire)
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
        assertTrue(settleUntilOffer(e))
        val id = e.beginAcquire(nowMs = nowMs)
        assertTrue(id != 0L)
        assertEquals(TrackState.ACQUIRING, e.state)
        nowMs += TrackerConfig.ACQUIRE_TIMEOUT_MS + 1
        val d = e.onFrame(noTrack)
        assertEquals(TrackState.IDLE, d.state)
        assertNull(d.hCn)
        // The watchdog also invalidated the id: the late completion is a
        // structural no-op instead of a resurrection.
        e.finishAcquire(id, locked = true, nowMs = nowMs)
        assertEquals(TrackState.IDLE, e.state)
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
    fun walkingAwayMidAcquireHidesOverlaysButAwaitsCompletion() {
        val e = engine()
        lockEngine(e)
        // Staleness refresh puts us in ACQUIRING with overlays still live.
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        var fired = false
        repeat(TrackerConfig.SETTLE_FRAMES) {
            if (e.onFrame(goodMeasurement()).requestAcquire) fired = true
        }
        assertTrue(fired)
        val id = e.beginAcquire(nowMs = nowMs)
        assertTrue(id != 0L)
        assertEquals(TrackState.ACQUIRING, e.state)
        assertNotNull(e.onFrame(goodMeasurement(disp = MOVING_DISP)).hCn)
        // User walks away while the (slow) OCR runs: after the hysteresis the
        // overlays must HIDE — but the state stays ACQUIRING so the pending
        // completion still lands (16 s stale-overlay linger observed before).
        repeat(TrackerConfig.FRAMES_TO_LOST) { e.onFrame(noTrack) }
        val d = e.onFrame(noTrack)
        assertNull(d.hCn)
        assertEquals(TrackState.ACQUIRING, d.state)
        // The late completion for the departed scene fails to lock → Idle.
        e.finishAcquire(id, locked = false, nowMs = nowMs)
        assertEquals(TrackState.IDLE, e.state)
    }

    @Test
    fun settleThresholdAdaptsDownOnSteadyDevices() {
        val e = engine()
        // Rock-steady rig: displacements far below the default threshold.
        repeat(TrackerConfig.SETTLE_ADAPT_WINDOW) { e.onFrame(probe(0.2)) }
        // p25(0.2) × 2.5 = 0.5, clamped up to the 0.8 minimum.
        assertEquals(TrackerConfig.SETTLE_THRESHOLD_MIN, e.settleThreshold(), 1e-9)
        // A 1.0 px wobble now counts as MOTION (default 1.5 would call it still).
        e.onFrame(probe(1.0))
        repeat(TrackerConfig.SETTLE_FRAMES - 1) { e.onFrame(probe(1.0)) }
        assertFalse(e.onFrame(probe(1.0)).requestAcquire)
    }

    @Test
    fun settleThresholdAdaptsUpOnShakyDevices() {
        val e = engine()
        // Noisy sensor/hand: resting displacement ~2.0 px — the default 1.5
        // threshold would NEVER settle (the original Moto G failure shape).
        repeat(TrackerConfig.SETTLE_ADAPT_WINDOW) { e.onFrame(probe(2.0)) }
        assertTrue(e.settleThreshold() >= 2.0)
        var acquired = false
        repeat(TrackerConfig.SETTLE_FRAMES + 1) {
            if (e.onFrame(probe(2.0)).requestAcquire) acquired = true
        }
        assertTrue("shaky-device settle never opened", acquired)
    }

    // ── Display stabilization: adaptive smoothing + deadband hold ─────────

    private fun measurementAt(tx: Double, ty: Double, disp: Double, inliers: Int = 100) =
        TrackMeasurement(
            hCn = doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty, 0.0, 0.0, 1.0),
            inliers = inliers,
            medianDispPx = disp,
            trackedPoints = inliers,
        )

    @Test
    fun displayFreezesUnderTremorWhileHoldingStill() {
        val e = engine()
        lockEngine(e)
        e.onFrame(measurementAt(2.0, -1.0, disp = STILL_DISP)) // seed
        var last: DoubleArray? = null
        // Sub-pixel tremor around a fixed pose: the raw fits jitter every
        // frame, the emitted transform must not move at all.
        repeat(30) { i ->
            val jitter = if (i % 2 == 0) 0.4 else -0.4
            val d = e.onFrame(measurementAt(2.0 + jitter, -1.0 - jitter, disp = 0.4))
            assertNotNull(d.hCn)
            last?.let { assertTrue("display moved under tremor at frame $i", it.contentEquals(d.hCn!!)) }
            last = d.hCn!!.copyOf()
        }
    }

    @Test
    fun displayFollowsSustainedDriftAtBoundedTrail() {
        val e = engine()
        lockEngine(e)
        e.onFrame(measurementAt(0.0, 0.0, disp = STILL_DISP)) // seed
        // Sustained 2 px/frame drift — exactly the shape the ADAPTIVE settle
        // threshold (which climbs to 4.0 on such input) would misread as
        // "still". The deadband is velocity-independent: the display must
        // follow at a bounded trail, never freeze into a growing offset.
        var lastTx = 0.0
        for (f in 1..40) {
            val tx = 2.0 * f
            val d = e.onFrame(measurementAt(tx, 0.0, disp = 2.0))
            assertNotNull(d.hCn)
            lastTx = d.hCn!![2]
            assertTrue("trail ${tx - lastTx} px at frame $f", tx - lastTx < 6.0)
        }
        assertTrue("display never followed the drift (tx=$lastTx)", lastTx > 60.0)
    }

    @Test
    fun adaptiveSmoothingSnappyInMotionHeavyAtRest() {
        // The same 10 px step, presented under pan-speed displacement and
        // under stillness: the moving engine must converge far faster.
        fun stepResponseAfter3Frames(disp: Double): Double {
            val e = engine()
            lockEngine(e)
            e.onFrame(measurementAt(0.0, 0.0, disp = disp)) // seed
            var tx = 0.0
            repeat(3) { tx = e.onFrame(measurementAt(10.0, 0.0, disp = disp)).hCn!![2] }
            return tx
        }
        assertTrue(stepResponseAfter3Frames(MOVING_DISP) > 7.0)
        assertTrue(stepResponseAfter3Frames(STILL_DISP) < 3.0)
    }

    @Test
    fun regionDisplayFrozenUnderTremorAndRetiresAfterDropout() {
        val e = engine()
        lockEngine(e)
        fun withRegion(jitter: Double) = goodMeasurement(disp = 0.4).copy(
            perRegionH = mapOf(7 to doubleArrayOf(1.0, 0.0, 3.0 + jitter, 0.0, 1.0, jitter, 0.0, 0.0, 1.0)),
        )
        e.onFrame(withRegion(0.0)) // seed global + region
        var held: DoubleArray? = null
        repeat(10) { i ->
            val d = e.onFrame(withRegion(if (i % 2 == 0) 0.4 else -0.4))
            val r = d.perRegionHCn[7]
            assertNotNull("region display missing at frame $i", r)
            held?.let { assertTrue("region display moved under tremor", it.contentEquals(r!!)) }
            held = r!!.copyOf()
        }
        // Fit drops out: the displayed transform holds verbatim through the
        // flicker window (no pop to the global H)...
        repeat(TrackerConfig.REGION_HOLD_FRAMES) { i ->
            val d = e.onFrame(goodMeasurement(disp = 0.4))
            val r = d.perRegionHCn[7]
            assertNotNull("hold window ended early at frame $i", r)
            assertTrue(held!!.contentEquals(r!!))
        }
        // ...then glides onto the global transform and drops off entirely.
        var gone = false
        repeat(20) {
            if (e.onFrame(goodMeasurement(disp = 0.4)).perRegionHCn.isEmpty()) gone = true
        }
        assertTrue("retired region never dropped off", gone)
    }

    @Test
    fun regionsReplacedDropsHeldRegionDisplays() {
        val e = engine()
        lockEngine(e)
        val withRegion = goodMeasurement(disp = 0.4).copy(
            perRegionH = mapOf(3 to doubleArrayOf(1.0, 0.0, 5.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
        )
        repeat(3) { e.onFrame(withRegion) }
        assertFalse(e.onFrame(withRegion).perRegionHCn.isEmpty())
        // Re-flavor: key 3 now names a DIFFERENT region — without the clear,
        // the old region's held transform would ride the flicker window onto
        // frames that have no fresh fit for it.
        e.onRegionsReplaced()
        assertTrue(e.onFrame(goodMeasurement(disp = 0.4)).perRegionHCn.isEmpty())
    }

    @Test
    fun freshAnchorSnapsDisplayToNewAnchorSpace() {
        val e = engine()
        lockEngine(e)
        repeat(3) { e.onFrame(measurementAt(2.0, -1.0, disp = STILL_DISP)) }
        // Staleness refresh → new anchor: the display must snap to the new
        // anchor space, not blend from the held transform.
        nowMs += TrackerConfig.ANCHOR_REFRESH_AGE_MS + 1
        var offered = false
        repeat(TrackerConfig.SETTLE_FRAMES + 1) {
            if (e.onFrame(measurementAt(2.0, -1.0, disp = STILL_DISP)).requestAcquire) offered = true
        }
        assertTrue(offered)
        val id = e.beginAcquire(nowMs = nowMs)
        assertTrue(id != 0L)
        e.finishAcquire(id, locked = true, nowMs = nowMs)
        val d = e.onFrame(measurementAt(50.0, 0.0, disp = STILL_DISP))
        assertEquals(50.0, d.hCn!![2], 1e-9)
    }

    @Test
    fun pullWithinBudgetBoundsPerspectiveTransforms() {
        val budget = TrackerConfig.HOLD_DEVIATION_CN_PX
        // Adversarial display/live pairs found by randomized search: opposing
        // perspective terms at large deviation (the post-rematch-pop shape).
        // A single coefficient-space lerp UNDERSHOOTS on these (1.61 px and
        // 53.5 px residuals against the 1.2 budget) because projection
        // divides by w — the bound must hold anyway.
        val adversarial = listOf(
            doubleArrayOf(1.03139058, -0.02161146, -16.16445665, -0.01924385, 0.98394557, 9.05589995, 9.943e-05, 9.786e-05, 1.0) to
                doubleArrayOf(0.99327152, -0.01326186, 17.29122582, 0.0464009, 1.0187865, -0.52714299, -9.524e-05, -9.464e-05, 1.0),
            doubleArrayOf(1.03561654, 0.0105073, -92.14275758, -0.02516343, 1.00696048, 148.94562958, 8.0562e-04, 9.9901e-04, 1.0) to
                doubleArrayOf(0.98736312, -0.00188266, -63.48333154, -0.02820324, 0.96203065, -63.93968025, -8.578e-04, -2.31e-04, 1.0),
        )
        for ((display, live) in adversarial) {
            assertTrue(Homography.maxCornerDeviation(display, live, 960, 540) > budget)
            Homography.pullWithinBudget(display, live, budget, 960, 540)
            val after = Homography.maxCornerDeviation(display, live, 960, 540)
            assertTrue("budget violated: $after px", after <= budget + 0.02)
        }
        // Within budget: untouched (the freeze that makes overlays rock-solid).
        val held = Homography.IDENTITY.copyOf()
        val near = doubleArrayOf(1.0, 0.0, 0.5, 0.0, 1.0, -0.4, 0.0, 0.0, 1.0)
        Homography.pullWithinBudget(held, near, budget, 960, 540)
        assertTrue(held.contentEquals(Homography.IDENTITY))
        // Beyond budget on a plain translation: pulled to the boundary, NOT
        // snapped onto the live fit — it's a rubber band, not a follower.
        val trailing = Homography.IDENTITY.copyOf()
        val far = doubleArrayOf(1.0, 0.0, 5.0, 0.0, 1.0, -3.0, 0.0, 0.0, 1.0)
        Homography.pullWithinBudget(trailing, far, budget, 960, 540)
        val dev = Homography.maxCornerDeviation(trailing, far, 960, 540)
        assertTrue("expected ~budget trail, got $dev", dev > budget - 0.2 && dev <= budget + 0.02)
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
