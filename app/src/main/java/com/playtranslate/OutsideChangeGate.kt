package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * The cheap change gate in front of live-mode OCR (audit item A2), outside
 * half: a sparse, brightness-normalized luma diff of the current raw frame
 * against the clean reference, sampled on a strided grid over the OCR crop
 * with the rendered overlay boxes excluded.
 *
 * Soundness: new text cannot appear in uncovered space without changing
 * pixels there, and under-box changes are the pinhole detector's job — so
 * "outside quiet AND all pinholes KEEP" means OCR has nothing new to find,
 * down to the sample grid's resolution. Text finer than the stride can in
 * principle slip between samples; the caller keeps a periodic reconciliation
 * OCR as the net and instruments its hits as gate misses.
 *
 * Normalization: the mean signed luma delta over all samples is subtracted
 * before thresholding, so global brightness shifts (the composited screen
 * dim, auto-brightness) don't fire the gate — a localized real change stands
 * out as residual above the global shift.
 *
 * Cost: two single-row getPixels reads per sampled row into reused buffers
 * (~150 rows ≈ ~1 MB copied at 1080p/stride 7) plus integer arithmetic. No
 * steady-state allocation. The comparison is anchored: `ref` is the clean
 * reference from the last FULL cycle, not the previous frame, so slow drifts
 * accumulate against the anchor instead of creeping under the threshold.
 */
object OutsideChangeGate {

    data class Result(
        val fired: Boolean,
        val changedSamples: Int,
        val totalSamples: Int,
        /** Mean signed luma delta — the global-shift term the residuals are
         *  measured against. Large magnitude with fired=false is the
         *  signature of a brightness ramp being correctly ignored. */
        val meanDelta: Int,
    )

    /** Reused working buffers, owned by the caller (one set per mode). */
    class Buffers {
        internal var rawRow = IntArray(0)
        internal var refRow = IntArray(0)
        internal var deltas = IntArray(0)

        internal fun ensure(rowWidth: Int, maxSamples: Int) {
            if (rawRow.size < rowWidth) {
                rawRow = IntArray(rowWidth)
                refRow = IntArray(rowWidth)
            }
            if (deltas.size < maxSamples) deltas = IntArray(maxSamples)
        }
    }

    /**
     * Sample the strided grid over [bounds] (typically the OCR crop),
     * skipping samples inside [exclude] (the rendered box rects, inflated by
     * the caller past their anti-aliased edges), and report whether the
     * normalized residuals say something outside the overlays changed.
     * [raw] and [ref] must share dimensions.
     */
    fun check(
        raw: Bitmap,
        ref: Bitmap,
        bounds: Rect,
        exclude: List<Rect>,
        buffers: Buffers,
        stridePx: Int = PinholeCalibration.OUTSIDE_STRIDE_PX,
        lumaThreshold: Int = PinholeCalibration.OUTSIDE_LUMA_THRESHOLD,
        minChangedSamples: Int = PinholeCalibration.OUTSIDE_MIN_CHANGED_SAMPLES,
    ): Result {
        val left = bounds.left.coerceIn(0, raw.width)
        val right = bounds.right.coerceIn(left, raw.width)
        val top = bounds.top.coerceIn(0, raw.height)
        val bottom = bounds.bottom.coerceIn(top, raw.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return Result(false, 0, 0, 0)

        val maxSamples = (height / stridePx + 1) * (width / stridePx + 1)
        buffers.ensure(width, maxSamples)

        var n = 0
        var y = top
        while (y < bottom) {
            raw.getPixels(buffers.rawRow, 0, width, left, y, width, 1)
            ref.getPixels(buffers.refRow, 0, width, left, y, width, 1)
            var x = 0
            while (x < width) {
                if (!excluded(left + x, y, exclude)) {
                    buffers.deltas[n++] =
                        luma(buffers.rawRow[x]) - luma(buffers.refRow[x])
                }
                x += stridePx
            }
            y += stridePx
        }
        return analyze(buffers.deltas, n, lumaThreshold, minChangedSamples)
    }

    /** Pure residual analysis over the first [n] signed luma deltas:
     *  subtract the mean (global-shift normalization), count |residual|
     *  strictly above [lumaThreshold]. JVM-tested. */
    fun analyze(
        deltas: IntArray,
        n: Int,
        lumaThreshold: Int,
        minChangedSamples: Int,
    ): Result {
        if (n == 0) return Result(false, 0, 0, 0)
        var sum = 0L
        for (i in 0 until n) sum += deltas[i]
        val mean = (sum / n).toInt()
        var changed = 0
        for (i in 0 until n) {
            val residual = deltas[i] - mean
            if (residual > lumaThreshold || residual < -lumaThreshold) changed++
        }
        return Result(changed >= minChangedSamples, changed, n, mean)
    }

    /** Integer Rec.601-ish luma from an ARGB pixel. */
    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) ushr 8
    }

    private fun excluded(x: Int, y: Int, rects: List<Rect>): Boolean {
        for (i in rects.indices) {
            if (rects[i].contains(x, y)) return true
        }
        return false
    }
}
