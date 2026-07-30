package com.playtranslate.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the shutter's pre-tap frame selection: the sharpest fresh frame
 *  received before the finger's impact wins; the newest frame needs a clear
 *  sharpness margin to beat it; and every no-anchor/no-candidate path falls
 *  back to the newest frame (the pre-ring behavior). */
class FreezeSelectorTest {

    private fun c(key: Int, tMs: Long, score: Double) =
        FreezeSelector.Candidate(key, tMs, score)

    // Timeline: now = 1000, tap impact at 800, entries every 100 ms.
    private val now = 1000L
    private val down = 800L

    @Test
    fun emptyRingSelectsNothing() {
        assertNull(FreezeSelector.select(emptyList(), down, now))
    }

    @Test
    fun noTouchAnchorSelectsNewest() {
        val newest = c(2, 1000, 1.0) // blurriest of the three
        val picked = FreezeSelector.select(
            listOf(c(0, 700, 9.0), c(1, 800, 5.0), newest),
            tapDownUptimeMs = null, nowMs = now,
        )
        assertEquals(newest, picked)
    }

    @Test
    fun sharpestPreTapFrameWins() {
        val sharpPre = c(1, 700, 8.0)
        val picked = FreezeSelector.select(
            listOf(c(0, 600, 6.0), sharpPre, c(2, 900, 9.0), c(3, 1000, 7.0)),
            down, now,
        )
        // 900 is post-impact and not newest: never eligible. The newest
        // (1000, score 7.0) fails the 1.25× margin against 8.0.
        assertEquals(sharpPre, picked)
    }

    @Test
    fun newestNeedsTheMarginToBeatPreTap() {
        val pre = c(0, 750, 10.0)
        val newestJustSharper = c(1, 1000, 12.0) // 1.2× — inside the margin
        assertEquals(
            pre,
            FreezeSelector.select(listOf(pre, newestJustSharper), down, now),
        )
        val newestClearlySharper = c(1, 1000, 13.0) // 1.3× — settle-then-tap
        assertEquals(
            newestClearlySharper,
            FreezeSelector.select(listOf(pre, newestClearlySharper), down, now),
        )
    }

    @Test
    fun staleEntriesNeverServeATap() {
        // Analyzer stalled (backgrounding): the only pre-tap frames predate
        // the freshness window and must not resurrect an old scene.
        val newest = c(1, 1000, 1.0)
        val picked = FreezeSelector.select(
            listOf(c(0, now - FreezeSelector.STALE_MS - 1, 99.0), newest),
            down, now,
        )
        assertEquals(newest, picked)
    }

    @Test
    fun allPostImpactFallsBackToNewest() {
        // Slow press: the impact predates everything the ring still holds.
        val newest = c(1, 1000, 5.0)
        val picked = FreezeSelector.select(
            listOf(c(0, 900, 9.0), newest),
            tapDownUptimeMs = 850L, nowMs = now,
        )
        assertEquals(newest, picked)
    }

    @Test
    fun pushThrottleNeverJamsOnTheNeverPushedSentinel() {
        // Regression (Moto G 2026-07-30): Long.MIN_VALUE as the "never
        // pushed" sentinel made nowMs - lastPushMs wrap negative, reading
        // as "pushed too recently" forever — every freeze saw exactly ONE
        // candidate (the force-pushed post-tap frame), silently reverting
        // the feature to the old blurry behavior.
        assertTrue(FreezeFrameRing.pushDue(nowMs = 5L, lastPushMs = 0L, force = false))
        assertTrue(FreezeFrameRing.pushDue(nowMs = 100L, lastPushMs = 50L, force = false))
        assertFalse(
            "inside the interval must throttle",
            FreezeFrameRing.pushDue(nowMs = 99L, lastPushMs = 50L, force = false),
        )
        assertTrue(
            "force bypasses the throttle",
            FreezeFrameRing.pushDue(nowMs = 51L, lastPushMs = 50L, force = true),
        )
    }

    @Test
    fun sharpnessRanksEdgesAboveFlatAndBlurred() {
        val w = 64
        val h = 64
        val flat = ByteArray(w * h) { 128.toByte() }
        // Hard vertical stripes (period 8) vs the same pattern linearly
        // ramped (a crude blur): the metric only needs to ORDER same-scene
        // variants correctly.
        val sharp = ByteArray(w * h)
        val blurred = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                sharp[y * w + x] = if ((x / 8) % 2 == 0) 0 else 255.toByte()
                val phase = x % 16
                val ramp = if (phase < 8) phase * 32 else (15 - phase) * 32
                blurred[y * w + x] = ramp.coerceIn(0, 255).toByte()
            }
        }
        val sFlat = FreezeSelector.sharpness(flat, w, h)
        val sSharp = FreezeSelector.sharpness(sharp, w, h)
        val sBlurred = FreezeSelector.sharpness(blurred, w, h)
        assertEquals(0.0, sFlat, 0.0)
        assertTrue("sharp ($sSharp) must outrank blurred ($sBlurred)", sSharp > sBlurred)
        assertTrue("blurred ($sBlurred) must outrank flat", sBlurred > 0.0)
    }
}
