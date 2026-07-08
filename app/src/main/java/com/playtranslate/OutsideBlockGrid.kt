package com.playtranslate

/**
 * Per-block temporal state for the outside change gate (audit A3, reshaped
 * by the 2026-07-08 speed-first product rule: no content class ever waits
 * longer than shipped cadence).
 *
 * The OCR crop is tiled into [BLOCK_PX] blocks. Each run (one gate pass over
 * one captured frame) feeds every block two signals:
 *
 *  - its sampled raw-luma SUM — compared against the previous run's sum,
 *    the block is MOVING when the average per-sample shift exceeds
 *    [MOVE_PER_SAMPLE]. This is frame-to-frame evidence, independent of the
 *    anchored reference.
 *  - its count of anchor-changed samples (post-photometric-fit residuals
 *    over threshold) — the block DIFFERS from the last full look.
 *
 * Decision per block:
 *  - DIFFERS ∧ still ∧ not volatile → FIRE now. No confirmation wait.
 *  - DIFFERS ∧ moving → hold (the frame is unreadable smear) and claim a
 *    floor-paced follow-up wake; fires on the first still look.
 *  - Persistently moving (EMA ≥ [VOLATILE_EMA]) → VOLATILE. The block can't
 *    produce useful per-look evidence, so it never fires — and the CALLER
 *    must treat any nonzero [Verdict.volatileBlocks] as "gate skipping
 *    disabled": screens containing endless animation run full-cadence OCR
 *    exactly like the shipped app, trading cycles for zero staleness.
 *
 * The caller must force floor-paced follow-up wakes while
 * [Verdict.pendingSettle] is true (a one-frame change reads as moving once
 * and may settle into delivery silence), and must [reset] whenever the
 * overlay layout changes (block membership shifts under the exclusion
 * rects) or the reference re-baselines.
 *
 * Pure integer state machine, allocation-free after sizing, JVM-tested.
 */
class OutsideBlockGrid {

    class Verdict(
        @JvmField var fired: Boolean = false,
        @JvmField var firedBlocks: Int = 0,
        /** Some moving block already differs from the anchor — recheck at
         *  the floor until it comes to rest (then it fires). */
        @JvmField var pendingSettle: Boolean = false,
        @JvmField var movingBlocks: Int = 0,
        /** Nonzero ⇒ the caller must not skip cycles on this screen. */
        @JvmField var volatileBlocks: Int = 0,
    )

    /** Reusable verdict for allocation-free runs (single-threaded owner). */
    val lastVerdict = Verdict()

    private var cols = 0
    private var rows = 0
    private var originX = 0
    private var originY = 0
    /** Per-block raw-luma sum from the previous run (Int.MIN_VALUE = none). */
    private var lastSum = IntArray(0)
    /** Per-block moving-EMA, 0..255. */
    private var ema = IntArray(0)
    /** True until the first run after a reset seeds [lastSum]. */
    private var seeding = true

    // Per-run scratch, indexed by block.
    private var runSum = IntArray(0)
    private var runCount = IntArray(0)
    private var runChanged = IntArray(0)

    /** (Re)size for a crop of [width]×[height] anchored at ([left], [top]).
     *  Any geometry change implies block membership changed → full reset. */
    fun configure(left: Int, top: Int, width: Int, height: Int) {
        val c = (width + BLOCK_PX - 1) / BLOCK_PX
        val r = (height + BLOCK_PX - 1) / BLOCK_PX
        if (c == cols && r == rows && left == originX && top == originY) return
        cols = c; rows = r; originX = left; originY = top
        val n = (c * r).coerceAtLeast(0)
        lastSum = IntArray(n)
        ema = IntArray(n)
        runSum = IntArray(n)
        runCount = IntArray(n)
        runChanged = IntArray(n)
        reset()
    }

    /** Forget all temporal state (overlay layout changed / re-baselined /
     *  mode reset). The next run only seeds sums and cannot fire. */
    fun reset() {
        java.util.Arrays.fill(lastSum, Int.MIN_VALUE)
        java.util.Arrays.fill(ema, 0)
        seeding = true
    }

    /** Block index for a sample at absolute ([x], [y]), or -1 if outside. */
    fun blockIndex(x: Int, y: Int): Int {
        val bx = (x - originX) / BLOCK_PX
        val by = (y - originY) / BLOCK_PX
        if (bx < 0 || by < 0 || bx >= cols || by >= rows) return -1
        return by * cols + bx
    }

    fun beginRun() {
        java.util.Arrays.fill(runSum, 0)
        java.util.Arrays.fill(runCount, 0)
        java.util.Arrays.fill(runChanged, 0)
    }

    /** Feed one sample: its block, raw luma, and whether its post-fit
     *  residual crossed the change threshold. */
    fun accumulate(block: Int, rawLuma: Int, anchorChanged: Boolean) {
        if (block < 0) return
        runSum[block] += rawLuma
        runCount[block]++
        if (anchorChanged) runChanged[block]++
    }

    /** Close the run: update EMAs, advance settle state, emit the verdict
     *  into [out] (allocation-free). */
    fun finishRun(out: Verdict) {
        out.fired = false
        out.firedBlocks = 0
        out.pendingSettle = false
        out.movingBlocks = 0
        out.volatileBlocks = 0
        val wasSeeding = seeding
        seeding = false
        for (b in 0 until cols * rows) {
            val count = runCount[b]
            if (count == 0) {
                // Fully excluded (under a box) this run — no evidence either
                // way; hold state.
                continue
            }
            val moving = if (wasSeeding || lastSum[b] == Int.MIN_VALUE) {
                false // no previous sum — cannot assess motion this run
            } else {
                val shift = runSum[b] - lastSum[b]
                val bound = MOVE_PER_SAMPLE * count
                shift > bound || shift < -bound
            }
            lastSum[b] = runSum[b]

            // Moving-EMA: ema += (target − ema) / 8.
            val target = if (moving) 255 else 0
            ema[b] += (target - ema[b]) shr 3
            val isVolatile = ema[b] >= VOLATILE_EMA
            if (moving) out.movingBlocks++
            if (isVolatile) out.volatileBlocks++

            val differs = runChanged[b] >= MIN_CHANGED_PER_BLOCK
            when {
                wasSeeding -> Unit // first look only seeds the sums
                isVolatile -> {
                    // Persistent animation. The block can't fire usefully
                    // (its pixels differ every frame by nature) — the CALLER
                    // must treat a nonzero volatileBlocks count as "gate
                    // skipping off": text inside endless animation is
                    // invisible to per-look pixel evidence, and the product
                    // rule (2026-07-08) is that no content class ever waits
                    // longer than shipped cadence — volatile screens run
                    // full-cadence OCR instead of accepting staleness.
                }
                moving -> {
                    // Mid-transition: the frame is unreadable smear; firing
                    // now would OCR garbage. Fire on the first still look.
                    // A moving block that already differs claims a follow-up
                    // wake — a single-frame change reads as moving exactly
                    // once and may settle into delivery silence.
                    if (differs) out.pendingSettle = true
                }
                differs -> {
                    // Still + differs from the last full look → fire NOW.
                    // (The former K=2 confirmation wait added 250ms–1s to
                    // every new-text event as insurance against an
                    // unmeasured garbage-OCR class — removed per the
                    // speed-first product rule.)
                    out.fired = true
                    out.firedBlocks++
                }
                else -> Unit
            }
        }
    }

    companion object {
        /** Block edge in bitmap px (audit A3's coarse-grid size). */
        const val BLOCK_PX = 32

        /** Average per-sample luma shift between consecutive runs above
         *  which a block counts as moving. Sits above the ~±6 mirror noise
         *  but far below glyph-scale change. */
        const val MOVE_PER_SAMPLE = 5

        /** Moving-EMA level (0..255) at which a block is volatile. 96 ≈
         *  moving on ~40% of recent runs. Decay from saturation back below
         *  the bar takes ~8 quiet runs (ema × (7/8)ⁿ). */
        const val VOLATILE_EMA = 96

        /** Anchor-changed samples within one block for it to count as
         *  differing (mirrors OUTSIDE_MIN_CHANGED_SAMPLES at block scale). */
        const val MIN_CHANGED_PER_BLOCK = 2
    }
}
