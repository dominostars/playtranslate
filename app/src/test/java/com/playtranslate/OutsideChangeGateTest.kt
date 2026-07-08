package com.playtranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OutsideChangeGate.analyze] — the photometrically-normalized
 * residual math (via [PhotometricFit]) that the A2 gate's skip decision and
 * the A3 pinhole rework both rest on. The bitmap-sampling shells are thin
 * and exercised on-device.
 *
 * The multiplicative cases mirror the flap recorded on Thor 2026-07-08
 * (c35/c56): a composited dim ramp on high-contrast content, which the old
 * absolute/mean-offset models misread as 85–95% change.
 */
class OutsideChangeGateTest {

    private fun pairs(vararg xy: Pair<Int, Int>): LongArray =
        LongArray(xy.size) { PhotometricFit.pack(xy[it].first, xy[it].second) }

    private fun analyze(p: LongArray, threshold: Int = 25, minChanged: Int = 2) =
        OutsideChangeGate.analyze(p, p.size, threshold, minChanged)

    /** High-contrast reference: alternating dark panel / bright glyph. */
    private fun contrasty(n: Int, transform: (Int) -> Int): LongArray =
        LongArray(n) {
            val x = if (it % 2 == 0) 16 else 235
            PhotometricFit.pack(x, transform(x).coerceIn(0, 255))
        }

    @Test
    fun `additive brightness shift is absorbed`() {
        val r = analyze(contrasty(500) { it - 40 })
        assertFalse(r.fired)
        assertEquals(0, r.changedSamples)
    }

    @Test
    fun `multiplicative dim on high-contrast content is absorbed`() {
        // The recorded-flap regression case: ×0.6 dim over dark panels and
        // bright glyphs. Mean-subtraction leaves ±44-level residuals here;
        // the affine fit leaves ~0.
        val r = analyze(contrasty(600) { (it * 6) / 10 })
        assertFalse(r.fired)
        assertEquals(0, r.changedSamples)
        assertTrue("slope should track the dim, got ${r.fitSlopeQ16}",
            r.fitSlopeQ16 in (0.55 * 65536).toLong()..(0.65 * 65536).toLong())
    }

    @Test
    fun `real change fires through a simultaneous dim`() {
        // 300 dimmed-but-unchanged samples + 6 decorrelated ones (a glyph
        // swap seen through the dim).
        val base = contrasty(300) { (it * 6) / 10 }
        val outliers = pairs(
            16 to 200, 235 to 20, 16 to 190,
            235 to 30, 16 to 210, 235 to 10,
        )
        val all = base + outliers
        val r = analyze(all)
        assertTrue(r.fired)
        assertEquals(6, r.changedSamples)
    }

    @Test
    fun `localized change fires with no photometric shift`() {
        val base = contrasty(100) { it }
        val outliers = pairs(16 to 120, 235 to 100, 16 to 130)
        val r = analyze(base + outliers)
        assertTrue(r.fired)
        assertEquals(3, r.changedSamples)
    }

    @Test
    fun `sensor noise stays quiet`() {
        // Deterministic pseudo-noise in [-6, +6] on top of identity — the
        // measured mirror noise envelope.
        val p = LongArray(400) {
            val x = if (it % 2 == 0) 16 else 235
            PhotometricFit.pack(x, x + ((it * 7919) % 13) - 6)
        }
        assertFalse(analyze(p).fired)
    }

    @Test
    fun `single outlier below the sample floor does not fire`() {
        val base = contrasty(200) { it }.copyOf()
        base[7] = PhotometricFit.pack(16, 220)
        val r = analyze(base)
        assertFalse(r.fired)
        assertEquals(1, r.changedSamples)
    }

    @Test
    fun `constant reference falls back to offset model`() {
        // Slope unidentifiable on a flat reference; a uniform shift must
        // still be absorbed by the offset-only fallback...
        val flatShift = LongArray(300) { PhotometricFit.pack(128, 158) }
        assertFalse(analyze(flatShift).fired)
        // ...while genuine outliers against the flat field still fire.
        val withChange = flatShift.copyOf()
        withChange[10] = PhotometricFit.pack(128, 20)
        withChange[20] = PhotometricFit.pack(128, 255)
        val r = analyze(withChange)
        assertTrue(r.fired)
        assertEquals(2, r.changedSamples)
    }

    @Test
    fun `empty sample set is quiet`() {
        val r = OutsideChangeGate.analyze(LongArray(0), 0, 25, 2)
        assertFalse(r.fired)
        assertEquals(0, r.totalSamples)
    }
}
