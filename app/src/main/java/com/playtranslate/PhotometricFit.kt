package com.playtranslate

/**
 * Shared photometric normalization for the change detectors (audit A3): a
 * per-region least-squares affine fit `observed ≈ a·expected + b`, with
 * change measured as residual from the fit instead of raw delta.
 *
 * Why affine, not mean-subtraction: the composited screen dim (and most
 * brightness pipelines — auto-brightness, night light) transform pixels
 * approximately multiplicatively, so the delta it induces is proportional
 * to the pixel's own brightness. On high-contrast content a ×0.6 dim
 * leaves ±60-level deltas AFTER subtracting the mean — indistinguishable
 * from a text change under a pure offset model, which is exactly the flap
 * recorded on Thor (2026-07-08, c35/c56: all boxes 85–95% "changed",
 * maxDelta 255, in lockstep with dim-ramp delivery bursts). Under the
 * affine fit the dim collapses into (a≈0.6, b≈0) and residuals return to
 * the noise floor, while a real content change decorrelates observed from
 * expected and no line fits it.
 *
 * Degenerate regions (near-constant expected values, e.g. a solid-color
 * reference) make the slope unidentifiable; the fit falls back to the
 * offset-only model (a=1, b=mean delta) there, which is exact for whatever
 * global shift a constant region can express.
 *
 * Pure integer/long math, allocation-free, JVM-tested. The centered normal
 * equations keep every intermediate under 2^63 for any realistic sample
 * count (safe to n ≈ 10^8 of 8-bit values).
 */
object PhotometricFit {

    /** Fixed-point shift for [Fit.slopeQ16] (a = slopeQ16 / 2^16). Public
     *  only because the inline [fit] body needs it at call sites. */
    const val Q = 16

    /** Minimum Var(expected), in squared 8-bit levels, for the slope to be
     *  identifiable (σ ≥ 5 levels). Below it the offset-only fallback is
     *  both stabler and exact for anything a near-constant region can show. */
    const val MIN_EXPECTED_VARIANCE = 25L

    /** Plausible-photometry bounds for the fit (see [finish]). Measured dim
     *  ramps sit near a≈0.6; 0.35 leaves room for deeper dim stages while
     *  still refusing content-scale tilts. */
    const val MIN_SLOPE = 0.35
    const val MAX_SLOPE = 1.6
    const val MAX_OFFSET = 96L

    class Fit(
        @JvmField var slopeQ16: Long = 1L shl Q,
        @JvmField var offset: Long = 0L,
    )

    /**
     * Fit `observed ≈ a·expected + b` over the [n] sample pairs produced by
     * [pairs] (invoked with indices 0 until n; values via [pack]). Writes
     * into [out] to stay allocation-free.
     */
    inline fun fit(n: Int, out: Fit, pairs: (Int) -> Long) {
        var sx = 0L; var sy = 0L; var sxx = 0L; var sxy = 0L
        for (i in 0 until n) {
            val p = pairs(i)
            val x = p ushr 32
            val y = p and 0xFFFFFFFFL
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        finish(n, sx, sy, sxx, sxy, out)
    }

    /** Solve the normal equations from the accumulated sums.
     *
     *  Centered form: dividing the cross-terms by n BEFORE the Q16 shift
     *  keeps the shifted numerator ≈ n·Cov·2^16 instead of n²·Cov·2^16 —
     *  the uncentered form overflows Long around n ≈ 90k samples, which a
     *  QHD outside-crop or a full-screen text box's pinhole region really
     *  reaches. Centered, the math is safe far beyond any physical display
     *  (raw sums stay < 2^54 for 8-bit values up to n ≈ 10^8). */
    fun finish(n: Int, sx: Long, sy: Long, sxx: Long, sxy: Long, out: Fit) {
        val nL = n.toLong()
        if (n == 0) {
            out.slopeQ16 = 1L shl Q
            out.offset = 0L
            return
        }
        val covN = sxy - sx * sy / nL   // ≈ n · Cov(expected, observed)
        val varN = sxx - sx * sx / nL   // ≈ n · Var(expected)
        if (varN < MIN_EXPECTED_VARIANCE * nL) {
            out.slopeQ16 = 1L shl Q
            out.offset = ((sy - sx) / nL).coerceIn(-MAX_OFFSET, MAX_OFFSET)
            return
        }
        // Clamp to the photometrically-plausible range. The fit exists to
        // absorb brightness pipelines (measured dim slope ≈ 0.6; deep dim
        // stages a bit lower; night light shifts offsets) — NOT to absorb
        // content. A content change tilts the unconstrained least-squares
        // line toward the changed samples and shaves their residuals under
        // the threshold (field regression 2026-07-08: under-box text swaps
        // read all-KEEP). Bounding (a, b) caps how much change the fit can
        // ever soak while leaving every observed brightness event inside
        // the box.
        val slopeQ = ((covN shl Q) / varN)
            .coerceIn((MIN_SLOPE * (1 shl Q)).toLong(), (MAX_SLOPE * (1 shl Q)).toLong())
        out.slopeQ16 = slopeQ
        out.offset = ((sy - ((slopeQ * sx) shr Q)) / nL)
            .coerceIn(-MAX_OFFSET, MAX_OFFSET)
    }

    /** Residual of one (expected, observed) pair under [fit]. */
    fun residual(fit: Fit, expected: Int, observed: Int): Int =
        (observed - (((fit.slopeQ16 * expected) shr Q) + fit.offset)).toInt()

    /** Pack an (expected, observed) sample pair for [fit]'s accessor. */
    fun pack(expected: Int, observed: Int): Long =
        (expected.toLong() shl 32) or observed.toLong()
}
