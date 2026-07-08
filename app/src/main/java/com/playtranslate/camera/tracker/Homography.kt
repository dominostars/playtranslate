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
