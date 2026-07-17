package com.playtranslate.camera.tracker

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A 3×3 planar homography as a row-major DoubleArray(9), normalized so
 * `h[8] == 1`. Plain arrays (no OpenCV types) so the math — and every policy
 * built on it — is JVM-unit-testable.
 */
object Homography {

    val IDENTITY: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun isValid(h: DoubleArray?): Boolean =
        h != null && h.size == 9 && h.all { it.isFinite() } && abs(h[8]) > 1e-12

    /** Normalize in place so h[8] == 1. Returns the same array. */
    fun normalize(h: DoubleArray): DoubleArray {
        val w = h[8]
        if (w != 1.0 && abs(w) > 1e-12) for (i in h.indices) h[i] /= w
        return h
    }

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (k in 0 until 3) s += a[r * 3 + k] * b[k * 3 + c]
            out[r * 3 + c] = s
        }
        return normalize(out)
    }

    /** Project point (x, y) through [h]; returns FloatArray(2). */
    fun project(h: DoubleArray, x: Double, y: Double): FloatArray {
        val w = h[6] * x + h[7] * y + h[8]
        return floatArrayOf(
            ((h[0] * x + h[1] * y + h[2]) / w).toFloat(),
            ((h[3] * x + h[4] * y + h[5]) / w).toFloat(),
        )
    }

    /**
     * Conjugate a CN-space homography into AU space: `H_au = S⁻¹ · H_cn · S`
     * where S is the uniform AU→CN downscale. Closed form (S = diag(s, s, 1)):
     * the translation terms scale by 1/s and the perspective terms by s.
     */
    fun cnToAu(hCn: DoubleArray, auToCnScale: Double): DoubleArray {
        val s = auToCnScale
        return normalize(
            doubleArrayOf(
                hCn[0], hCn[1], hCn[2] / s,
                hCn[3], hCn[4], hCn[5] / s,
                hCn[6] * s, hCn[7] * s, hCn[8],
            )
        )
    }

    /** In-place exponential smoothing: `smoothed = α·new + (1-α)·smoothed`,
     *  both inputs normalized first. Kills handheld jitter in the emitted
     *  transform without an EKF. */
    fun emaInPlace(smoothed: DoubleArray, fresh: DoubleArray, alpha: Float) {
        normalize(smoothed)
        normalize(fresh)
        val a = alpha.toDouble()
        for (i in smoothed.indices) smoothed[i] = a * fresh[i] + (1 - a) * smoothed[i]
        normalize(smoothed)
    }

    /** Max L1 deviation between where [a] and [b] place the corners of a
     *  [w]×[h] frame — "how far apart these transforms put content", the
     *  display deadband's distance metric. */
    fun maxCornerDeviation(a: DoubleArray, b: DoubleArray, w: Int, h: Int): Double {
        var worst = 0.0
        for (i in 0 until 4) {
            val x = if (i and 1 == 0) 0.0 else w.toDouble()
            val y = if (i and 2 == 0) 0.0 else h.toDouble()
            val pa = project(a, x, y)
            val pb = project(b, x, y)
            val d = (abs(pa[0] - pb[0]) + abs(pa[1] - pb[1])).toDouble()
            if (d > worst) worst = d
        }
        return worst
    }

    /** Slack on [pullWithinBudget]'s exit check (CN px, ~0.03 px on screen):
     *  a pull that lands at budget+ε must count as converged, or boundary
     *  chatter would burn the iteration cap and snap-to-live spuriously. */
    private const val PULL_SLACK_PX = 0.01

    /** Re-pulls before [pullWithinBudget] gives up and snaps: every
     *  adversarial case found by randomized search converged in 2. */
    private const val MAX_PULLS = 3

    /**
     * The display deadband: leave [display] untouched while it places
     * content within [budget] px of [live] over a [w]×[h] frame
     * ([maxCornerDeviation]), and when the gap exceeds the budget pull it
     * back to the budget boundary — a velocity-independent rubber band, not
     * a low-pass. Holding still the display freezes outright; sustained
     * motion is followed at a constant sub-budget trail.
     *
     * The lerp factor `1 - budget/dev` is exact only where projections are
     * linear in the matrix coefficients (affine). Perspective terms divide
     * by w, so a single pull can UNDERSHOOT — up to 1.6 px past a 1.2 px
     * budget at realistic magnitudes, unboundedly for wilder transforms
     * (found by randomized search; opposing h6/h7 signs at large deviation,
     * e.g. right after a rematch pop). So: re-measure and re-pull, and if
     * [MAX_PULLS] don't converge, snap to [live] — rendering the live fit
     * exactly is always legitimate, so the budget is a hard invariant.
     */
    fun pullWithinBudget(display: DoubleArray, live: DoubleArray, budget: Double, w: Int, h: Int) {
        repeat(MAX_PULLS) {
            val dev = maxCornerDeviation(display, live, w, h)
            if (dev <= budget + PULL_SLACK_PX) return
            emaInPlace(display, live, (1.0 - budget / dev).toFloat())
        }
        if (maxCornerDeviation(display, live, w, h) > budget + PULL_SLACK_PX) {
            System.arraycopy(live, 0, display, 0, 9)
        }
    }

    /** Effective uniform scale of the mapping: sqrt(|det| of the top-left 2×2)
     *  (of the normalized H). 1.0 = same apparent size as the anchor frame. */
    fun scaleOf(h: DoubleArray): Float {
        val det = h[0] * h[4] - h[1] * h[3]
        return sqrt(abs(det / (h[8] * h[8]))).toFloat()
    }

    /** Convert to an [android.graphics.Matrix] (which supports perspective). */
    fun toAndroidMatrix(h: DoubleArray, out: Matrix = Matrix()): Matrix {
        out.setValues(
            floatArrayOf(
                h[0].toFloat(), h[1].toFloat(), h[2].toFloat(),
                h[3].toFloat(), h[4].toFloat(), h[5].toFloat(),
                h[6].toFloat(), h[7].toFloat(), h[8].toFloat(),
            )
        )
        return out
    }
}
