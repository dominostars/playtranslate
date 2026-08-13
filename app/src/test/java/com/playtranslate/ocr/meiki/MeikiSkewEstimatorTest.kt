package com.playtranslate.ocr.meiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * [MeikiSkewEstimator] on synthetic renders: square "glyphs" on a uniform
 * background along a baseline of known slant. Clean input, so the fit should
 * land within ~1° of truth; the production enemies (banner edges, low
 * contrast) are corpus-validated, not unit-tested.
 */
@RunWith(RobolectricTestRunner::class)
class MeikiSkewEstimatorTest {

    /** Row-major gray canvas (bg 40) with [glyph]-px squares at [pitch] along a
     *  baseline through the canvas centre at [angleDeg] (y-down, clockwise+). */
    private fun renderLine(
        width: Int,
        height: Int,
        angleDeg: Float,
        glyph: Int = 22,
        pitch: Int = 30,
    ): ByteArray {
        val img = ByteArray(width * height) { 40.toByte() }
        val slope = tan(Math.toRadians(angleDeg.toDouble())).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        var gx = 8
        while (gx + glyph < width - 8) {
            val gcx = gx + glyph / 2f
            val top = (cy + (gcx - cx) * slope - glyph / 2f).roundToInt()
            for (y in top until top + glyph) {
                if (y !in 0 until height) continue
                for (x in gx until gx + glyph) img[y * width + x] = 220.toByte()
            }
            gx += pitch
        }
        return img
    }

    @Test
    fun uprightLineEstimatesZero() {
        val est = MeikiSkewEstimator.estimate(renderLine(480, 60, 0f), 480, 60)
        assertTrue("expected ~0, got $est", abs(est) < 0.8f)
    }

    @Test
    fun positiveSlantRecoveredWithSign() {
        val est = MeikiSkewEstimator.estimate(renderLine(480, 120, 8f), 480, 120)
        assertTrue("expected ~+8, got $est", est > 6.8f && est < 9.2f)
    }

    @Test
    fun negativeSlantRecoveredWithSign() {
        val est = MeikiSkewEstimator.estimate(renderLine(480, 120, -8f), 480, 120)
        assertTrue("expected ~-8, got $est", est < -6.8f && est > -9.2f)
    }

    @Test
    fun lightSlantAboveNoiseGateResolves() {
        val est = MeikiSkewEstimator.estimate(renderLine(640, 100, 4f), 640, 100)
        assertTrue("expected ~+4, got $est", est > 3.0f && est < 5.0f)
    }

    @Test
    fun blankImageReturnsZero() {
        val img = ByteArray(480 * 60) { 40.toByte() }
        assertEquals(0f, MeikiSkewEstimator.estimate(img, 480, 60), 0f)
    }

    @Test
    fun sparseInkReturnsZero() {
        // One glyph = fewer ink columns than the fit floor: "can't tell", not a
        // measurement — the icon/decoration false-flag class from the census.
        val img = ByteArray(480 * 60) { 40.toByte() }
        for (y in 20 until 40) for (x in 200 until 216) img[y * 480 + x] = 220.toByte()
        assertEquals(0f, MeikiSkewEstimator.estimate(img, 480, 60), 0f)
    }

    @Test
    fun degenerateDimsReturnZero() {
        assertEquals(0f, MeikiSkewEstimator.estimate(ByteArray(10), 1, 10), 0f)
        assertEquals(0f, MeikiSkewEstimator.estimate(ByteArray(4), 4, 4), 0f) // undersized buffer
    }
}
