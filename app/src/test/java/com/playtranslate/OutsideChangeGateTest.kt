package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [OutsideChangeGate.analyze] — the pure residual math the
 *  A2 gate's skip decision rests on. The bitmap-sampling shell is thin and
 *  exercised on-device. */
class OutsideChangeGateTest {

    private fun analyze(deltas: IntArray, threshold: Int = 25, minChanged: Int = 2) =
        OutsideChangeGate.analyze(deltas, deltas.size, threshold, minChanged)

    @Test
    fun `uniform brightness shift is normalized out`() {
        // Composited screen dim: every sample drops by the same amount.
        val r = analyze(IntArray(500) { -40 })
        assertFalse(r.fired)
        assertEquals(0, r.changedSamples)
        assertEquals(-40, r.meanDelta)
    }

    @Test
    fun `localized change fires through normalization`() {
        val deltas = IntArray(105) { if (it < 100) 0 else 80 }
        val r = analyze(deltas)
        assertTrue(r.fired)
        assertEquals(5, r.changedSamples)
    }

    @Test
    fun `dim ramp plus a real change fires only the change`() {
        // Global -60 dim; 6 samples additionally swing to +30 (a glyph swap
        // seen through the dim). Residuals: change ≈ +88, background ≈ -2.
        val deltas = IntArray(306) { if (it < 300) -60 else 30 }
        val r = analyze(deltas)
        assertTrue(r.fired)
        assertEquals(6, r.changedSamples)
    }

    @Test
    fun `sensor noise stays quiet`() {
        // Deterministic pseudo-noise in [-6, +6] — the measured mirror
        // noise envelope.
        val deltas = IntArray(400) { i -> ((i * 7919) % 13) - 6 }
        assertFalse(analyze(deltas).fired)
    }

    @Test
    fun `single outlier below the sample floor does not fire`() {
        val deltas = IntArray(200)
        deltas[7] = 200
        val r = analyze(deltas)
        assertFalse(r.fired)
        assertEquals(1, r.changedSamples)
    }

    @Test
    fun `empty sample set is quiet`() {
        val r = OutsideChangeGate.analyze(IntArray(0), 0, 25, 2)
        assertFalse(r.fired)
        assertEquals(0, r.totalSamples)
    }

    @Test
    fun `threshold is strict`() {
        // Residual exactly at the threshold must not count.
        val deltas = IntArray(100) { if (it < 98) 0 else 25 }
        // mean = 50/100 = 0 (integer); residual for the two = 25, not > 25.
        val r = analyze(deltas)
        assertEquals(0, r.changedSamples)
        assertFalse(r.fired)
    }
}
