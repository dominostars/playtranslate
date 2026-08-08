package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * The cheap change gate in front of live-mode OCR (audit item A2), outside
 * half: a sparse luma comparison of the current raw frame against the clean
 * reference, sampled on a strided grid over the OCR crop with the rendered
 * overlay boxes excluded, photometrically normalized by [PhotometricFit]
 * (audit A3) so brightness pipelines — the composited screen dim,
 * auto-brightness, night light — don't fire it. A real localized change
 * decorrelates from the reference and survives the fit as residual.
 *
 * Soundness: new text cannot appear in uncovered space without changing
 * pixels there, and under-box changes are the pinhole detector's job — so
 * "outside quiet AND all pinholes KEEP" means OCR has nothing new to find,
 * down to the sample grid's resolution. Text finer than the stride can in
 * principle slip between samples; the caller keeps a periodic reconciliation
 * OCR as the net and instruments its hits as gate misses.
 *
 * Cost: two single-row getPixels reads per sampled row into reused buffers
 * (~150 rows ≈ ~1 MB copied at 1080p/stride 7) plus integer arithmetic. No
 * steady-state allocation beyond one small Result. The comparison is
 * anchored: `ref` is the clean reference from the last FULL cycle, not the
 * previous frame, so slow drifts accumulate against the anchor instead of
 * creeping under the threshold.
 */
object OutsideChangeGate {

    data class Result(
        val fired: Boolean,
        val changedSamples: Int,
        val totalSamples: Int,
        /** The photometric fit the residuals were measured against — slope
         *  in Q16 (65536 = 1.0) and offset in luma levels. A slope well off
         *  1.0 (or a large offset) with fired=false is the signature of a
         *  brightness ramp being correctly absorbed. */
        val fitSlopeQ16: Long,
        val fitOffset: Long,
        /** Grid-mode only (see [check] with an [OutsideBlockGrid]): some
         *  block is awaiting its stillness confirmation — the caller must
         *  force a follow-up cycle, because the change may already have
         *  settled into delivery silence. */
        val pendingSettle: Boolean = false,
        /** Grid-mode diagnostics for the skip/GO debug lines. */
        val movingBlocks: Int = 0,
        val volatileBlocks: Int = 0,
    ) {
        /** Human-readable fit for debug lines, e.g. "a=0.61 b=2". */
        fun fitLabel(): String = String.format(
            java.util.Locale.US, "a=%.2f b=%d", fitSlopeQ16 / 65536.0, fitOffset,
        )
    }

    /**
     * One exclusion region for [check]: [rect] is the rendered box rect,
     * caller-inflated past its anti-aliased edges. For a rotated chip
     * ([angleDeg] != 0) the true drawn footprint is [orientedW]×[orientedH]
     * (also caller-inflated) rotated about the rect center — only THAT
     * footprint is excluded. The AABB corners outside it stay sampled on
     * purpose: the pinhole detector's blend model skips pixels the overlay
     * never drew, so the outside gate is those corners' only watcher.
     */
    class Exclusion(
        val rect: Rect,
        private val angleDeg: Float = 0f,
        private val orientedW: Float = 0f,
        private val orientedH: Float = 0f,
    ) {
        private val cos: Float
        private val sin: Float

        init {
            val r = Math.toRadians(angleDeg.toDouble())
            cos = kotlin.math.cos(r).toFloat()
            sin = kotlin.math.sin(r).toFloat()
        }

        fun contains(x: Int, y: Int): Boolean {
            if (angleDeg == 0f || orientedW <= 0f || orientedH <= 0f) return rect.contains(x, y)
            // Un-rotate the sample about the chip center (== rect center: the
            // inflate is symmetric and rotation preserves the center) and test
            // the axis-aligned oriented rect. Shared frame math — see
            // [com.playtranslate.ocr.core.DeskewGeometry].
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val u = com.playtranslate.ocr.core.DeskewGeometry.toFrameU(x.toFloat(), y.toFloat(), cx, cy, cos, sin)
            val v = com.playtranslate.ocr.core.DeskewGeometry.toFrameV(x.toFloat(), y.toFloat(), cx, cy, cos, sin)
            return kotlin.math.abs(u) <= orientedW / 2f && kotlin.math.abs(v) <= orientedH / 2f
        }
    }

    /** Reused working buffers, owned by the caller (one set per mode). */
    class Buffers {
        internal var rawRow = IntArray(0)
        internal var refRow = IntArray(0)
        internal var pairs = LongArray(0)
        internal var blockIdx = IntArray(0)

        internal fun ensure(rowWidth: Int, maxSamples: Int) {
            if (rawRow.size < rowWidth) {
                rawRow = IntArray(rowWidth)
                refRow = IntArray(rowWidth)
            }
            if (pairs.size < maxSamples) {
                pairs = LongArray(maxSamples)
                blockIdx = IntArray(maxSamples)
            }
        }
    }

    /**
     * Sample the strided grid over [bounds] (typically the OCR crop),
     * skipping samples inside [exclude] (the rendered box footprints — see
     * [Exclusion]; inflated by the caller past their anti-aliased edges —
     * plus the floating icon's window rect, whose burn-in animations are
     * self-chrome motion, not screen change), and report whether the
     * fit-normalized residuals say something outside the overlays changed.
     * [raw] and [ref] must share dimensions.
     */
    fun check(
        raw: Bitmap,
        ref: Bitmap,
        bounds: Rect,
        exclude: List<Exclusion>,
        buffers: Buffers,
        grid: OutsideBlockGrid? = null,
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
        if (width <= 0 || height <= 0) return Result(false, 0, 0, 1L shl PhotometricFit.Q, 0)

        val maxSamples = (height / stridePx + 1) * (width / stridePx + 1)
        buffers.ensure(width, maxSamples)
        grid?.configure(left, top, width, height)

        var n = 0
        var y = top
        while (y < bottom) {
            raw.getPixels(buffers.rawRow, 0, width, left, y, width, 1)
            ref.getPixels(buffers.refRow, 0, width, left, y, width, 1)
            var x = 0
            while (x < width) {
                if (!excluded(left + x, y, exclude)) {
                    buffers.pairs[n] = PhotometricFit.pack(
                        expected = luma(buffers.refRow[x]),
                        observed = luma(buffers.rawRow[x]),
                    )
                    if (grid != null) {
                        buffers.blockIdx[n] = grid.blockIndex(left + x, y)
                    }
                    n++
                }
                x += stridePx
            }
            y += stridePx
        }

        val flat = analyze(buffers.pairs, n, lumaThreshold, minChangedSamples)
        if (grid == null) return flat

        // Grid mode: replay the residuals into the per-block temporal state;
        // the settle/volatility verdict supersedes the flat sample count as
        // the firing decision (audit A3 — animation is excluded, transitions
        // wait for stillness), while the flat fields stay for diagnostics.
        val fit = PhotometricFit.Fit(flat.fitSlopeQ16, flat.fitOffset)
        grid.beginRun()
        for (i in 0 until n) {
            val p = buffers.pairs[i]
            val expected = (p ushr 32).toInt()
            val observed = (p and 0xFFFFFFFFL).toInt()
            val r = PhotometricFit.residual(fit, expected, observed)
            grid.accumulate(
                buffers.blockIdx[i],
                observed,
                r > lumaThreshold || r < -lumaThreshold,
            )
        }
        val verdict = grid.lastVerdict
        grid.finishRun(verdict)
        return flat.copy(
            fired = verdict.fired,
            pendingSettle = verdict.pendingSettle,
            movingBlocks = verdict.movingBlocks,
            volatileBlocks = verdict.volatileBlocks,
        )
    }

    /** Pure residual analysis over the first [n] packed (ref, raw) luma
     *  pairs: fit observed ≈ a·expected + b, count |residual| strictly
     *  above [lumaThreshold]. JVM-tested. */
    fun analyze(
        pairs: LongArray,
        n: Int,
        lumaThreshold: Int,
        minChangedSamples: Int,
    ): Result {
        val fit = PhotometricFit.Fit()
        if (n == 0) return Result(false, 0, 0, fit.slopeQ16, 0)
        PhotometricFit.fit(n, fit) { pairs[it] }
        var changed = 0
        for (i in 0 until n) {
            val p = pairs[i]
            val expected = (p ushr 32).toInt()
            val observed = (p and 0xFFFFFFFFL).toInt()
            val r = PhotometricFit.residual(fit, expected, observed)
            if (r > lumaThreshold || r < -lumaThreshold) changed++
        }
        return Result(changed >= minChangedSamples, changed, n, fit.slopeQ16, fit.offset)
    }

    /** Integer Rec.601-ish luma from an ARGB pixel. */
    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 150 + b * 29) ushr 8
    }

    private fun excluded(x: Int, y: Int, regions: List<Exclusion>): Boolean {
        for (i in regions.indices) {
            if (regions[i].contains(x, y)) return true
        }
        return false
    }
}
