package com.playtranslate.ocr.core

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The shared deskew frame for angle-aware consumers: cluster slanted things by
 * angle, transform their geometry into an axis-aligned frame at the cluster
 * angle, run existing axis-aligned logic there, rotate results back. Used by
 * the grouping shell, LineAssembler banding, and overlay carving — one
 * clusterer, one rounding policy, so their geometry cannot drift apart.
 * No ML Kit / OpenCV imports, same contract as the rest of `ocr.core`.
 *
 * ## Rounding policy (a contract, not an implementation detail)
 * The grouping kernel is integer-Rect and claims byte-determinism, so rotation
 * floats must collapse through ONE rule: dimensions round once, independent of
 * position ([roundHalfUp] of the oriented dims); positions round once, from the
 * center. Always `floor(x + 0.5f)` — never `Math.round` (asymmetric at the
 * negative coordinates an anchor-centered frame produces) and never per-edge
 * rounding (which makes a rect's WIDTH flutter ±1 with sub-pixel position, and
 * width feeds every ratio in the kernel).
 *
 * ## Angles are copied, never computed (program invariant)
 * [AngleCluster.angleDeg] is a verbatim member angle (the longest member's) —
 * no averaging, no trig-derived angles. Trig here CONSUMES angles for placement
 * only. This is what keeps exact `angleDeg != 0f` a safe mode bit everywhere.
 */

/** A deskew frame: rotate by −[angleDeg] about ([anchorX], [anchorY]) to enter
 *  it, +[angleDeg] to leave. Sign convention as everywhere: clockwise-positive,
 *  y-down, `View.rotation` semantics. */
data class AngleFrame(
    val angleDeg: Float,
    val anchorX: Int,
    val anchorY: Int,
) {
    val cosT: Float
    val sinT: Float

    init {
        val r = Math.toRadians(angleDeg.toDouble())
        cosT = cos(r).toFloat()
        sinT = sin(r).toFloat()
    }
}

/** One angle cluster: [memberIndices] into the caller's input list (ascending),
 *  and the frame angle — the longest member's own [angleDeg], verbatim. */
data class AngleCluster(
    val memberIndices: List<Int>,
    val angleDeg: Float,
)

internal object DeskewGeometry {

    /** Length-aware residual bound: a member whose half-length excursion
     *  `(ow/2)·sin|θᵢ−θ̄|` exceeds this fraction of its own height splits out —
     *  comfortably below the kernel's 0.50 row-overlap requirement, so a
     *  deskewed member can never mis-band from residual slant alone. */
    private const val RESIDUAL_EXCURSION_FRAC = 0.35f

    /** Default clustering cap for the grouping shell. Floor: measured
     *  per-element angle jitter (probe data: ≤0.35° typical, ~2.1° worst
     *  clean). Ceiling: the length-aware split check is the real bound for
     *  long lines; the scalar only opens the run. Sweepable via the corpus
     *  variants. */
    const val DEFAULT_CLUSTER_CAP_DEG = 4f

    /** The one float→int rule. See the file kdoc's rounding contract. */
    fun roundHalfUp(x: Float): Int = floor(x + 0.5f).toInt()

    /** Rotate ([px],[py]) by +θ (clockwise, y-down) about ([cx],[cy]) with
     *  precomputed [cosT]/[sinT] — the FORWARD (frame → screen) direction. */
    fun rotateX(px: Float, py: Float, cx: Float, cy: Float, cosT: Float, sinT: Float): Float =
        cx + (px - cx) * cosT - (py - cy) * sinT

    fun rotateY(px: Float, py: Float, cx: Float, cy: Float, cosT: Float, sinT: Float): Float =
        cy + (px - cx) * sinT + (py - cy) * cosT

    /** Frame-relative coordinates of a screen point in a +θ frame about
     *  ([cx],[cy]): [toFrameU] is the along-baseline coordinate, [toFrameV]
     *  the cross-baseline one. The INVERSE of [rotateX]/[rotateY], expressed
     *  relatively — the shape every point hit-test wants. */
    fun toFrameU(px: Float, py: Float, cx: Float, cy: Float, cosT: Float, sinT: Float): Float =
        (px - cx) * cosT + (py - cy) * sinT

    fun toFrameV(px: Float, py: Float, cx: Float, cy: Float, cosT: Float, sinT: Float): Float =
        -(px - cx) * sinT + (py - cy) * cosT

    /**
     * A slanted box's rect in [frame]: the box CENTER inverse-rotated about the
     * anchor, with the oriented dims laid axis-aligned there. Never the AABB
     * rotated — that would re-inflate the very envelope deskewing removes.
     * Exact when the box's own angle equals the frame's; a residual δ within
     * the cluster bound leaves the true footprint slanted by δ in-frame — the
     * tight rect slightly under-covers, which [RESIDUAL_EXCURSION_FRAC] keeps
     * below banding thresholds.
     */
    fun deskew(box: OcrBox, frame: AngleFrame): Rect {
        val ax = frame.anchorX.toFloat()
        val ay = frame.anchorY.toFloat()
        val bx = box.bounds.exactCenterX()
        val by = box.bounds.exactCenterY()
        val cx = ax + toFrameU(bx, by, ax, ay, frame.cosT, frame.sinT)
        val cy = ay + toFrameV(bx, by, ax, ay, frame.cosT, frame.sinT)
        val w = roundHalfUp(box.orientedWidth).coerceAtLeast(1)
        val h = roundHalfUp(box.orientedHeight).coerceAtLeast(1)
        val l = roundHalfUp(cx - w / 2f)
        val t = roundHalfUp(cy - h / 2f)
        return Rect(l, t, l + w, t + h)
    }

    /**
     * The screen-space AABB of an in-frame rect rotated back by +θ̄: closed form
     * `W = ceil(w·|cos| + h·|sin|)` (and the transpose for H), centered on the
     * rotated center. The closed form + ceil + symmetric centering guarantee,
     * under integer rounding, the two properties every consumer of an angled
     * box needs: the AABB center equals the oriented rect's center to ±0.5px,
     * and the AABB contains the full drawn footprint.
     */
    fun screenAabbOf(inFrame: Rect, frame: AngleFrame): Rect {
        val ax = frame.anchorX.toFloat()
        val ay = frame.anchorY.toFloat()
        val fx = inFrame.exactCenterX()
        val fy = inFrame.exactCenterY()
        // Forward rotation: +θ.
        val cx = rotateX(fx, fy, ax, ay, frame.cosT, frame.sinT)
        val cy = rotateY(fx, fy, ax, ay, frame.cosT, frame.sinT)
        val ac = abs(frame.cosT)
        val asn = abs(frame.sinT)
        val w = inFrame.width() * ac + inFrame.height() * asn
        val h = inFrame.width() * asn + inFrame.height() * ac
        val wi = (floor(w).toInt() + if (w > floor(w)) 1 else 0).coerceAtLeast(1)
        val hi = (floor(h).toInt() + if (h > floor(h)) 1 else 0).coerceAtLeast(1)
        val l = roundHalfUp(cx - wi / 2f)
        val t = roundHalfUp(cy - hi / 2f)
        return Rect(l, t, l + wi, t + hi)
    }

    /**
     * Max corner displacement between two oriented footprints — the "did the
     * drawn chip move?" metric hysteresis sites share. Each footprint is its
     * oriented dims rotated by its angle about its own bounds center; an
     * upright side (angle 0 / dims 0 per the carriers' 0-when-upright
     * convention) uses its bounds rect verbatim. Corner correspondence is by
     * index in each footprint's own frame (TL,TR,BR,BL), so a pure rotation
     * about a shared center scores `2·r·sin(δ/2)` at the corner radius —
     * length-dependent, which is the point.
     */
    fun footprintCornerDelta(
        boundsA: Rect, angleA: Float, owA: Float, ohA: Float,
        boundsB: Rect, angleB: Float, owB: Float, ohB: Float,
    ): Float {
        var maxD = 0f
        for (k in 0 until 4) {
            val (axk, ayk) = footprintCorner(boundsA, angleA, owA, ohA, k)
            val (bxk, byk) = footprintCorner(boundsB, angleB, owB, ohB, k)
            val d = hypot(axk - bxk, ayk - byk)
            if (d > maxD) maxD = d
        }
        return maxD
    }

    private fun footprintCorner(bounds: Rect, angle: Float, ow: Float, oh: Float, k: Int): Pair<Float, Float> {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val w = if (angle != 0f && ow > 0f) ow else bounds.width().toFloat()
        val h = if (angle != 0f && oh > 0f) oh else bounds.height().toFloat()
        val ux = if (k == 0 || k == 3) -w / 2f else w / 2f
        val uy = if (k < 2) -h / 2f else h / 2f
        if (angle == 0f) return (cx + ux) to (cy + uy)
        val r = Math.toRadians(angle.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (cx + ux * c - uy * s) to (cy + ux * s + uy * c)
    }

    /**
     * THE angle clusterer (grouping shell, LineAssembler, carving — one
     * implementation, different tolerances). Deterministic spec:
     * 1. Sort candidate indices by (angle, input index).
     * 2. Greedy contiguous runs with SEED-anchored admission:
     *    `|θᵢ − θ_seed| ≤ capDeg` (the seed is the run's first member).
     * 3. Per cluster, θ̄ = the LONGEST member's angle (tie → lowest input
     *    index), fixed post-selection — a verbatim member angle (invariant:
     *    angles are copied, never computed), giving zero residual to the
     *    member with the largest excursion lever arm.
     * 4. Length-aware split check: any member with
     *    `(ow/2)·sin|θᵢ−θ̄| > 0.35·oh` splits into its own singleton cluster
     *    (its θ̄ = its own angle). The longest member can never fail (δ = 0),
     *    so one pass suffices and θ̄ never needs recomputing.
     * 5. Clusters ordered by their first member's input index.
     * Band assertion: every candidate must satisfy |θ| < 90 (the producer band
     * never reaches the ±90 wrap; wrap handling would be dead code).
     */
    fun clusterByAngle(
        angles: List<Float>,
        lengths: List<Float>,
        heights: List<Float>,
        capDeg: Float,
    ): List<AngleCluster> {
        require(angles.size == lengths.size && angles.size == heights.size)
        if (angles.isEmpty()) return emptyList()
        for (a in angles) require(abs(a) < 90f) { "angle $a outside the producer band" }

        val sorted = angles.indices.sortedWith(compareBy({ angles[it] }, { it }))
        val runs = mutableListOf<MutableList<Int>>()
        var seed = Float.NaN
        for (idx in sorted) {
            if (runs.isEmpty() || abs(angles[idx] - seed) > capDeg) {
                runs.add(mutableListOf(idx))
                seed = angles[idx]
            } else {
                runs.last().add(idx)
            }
        }

        val out = mutableListOf<AngleCluster>()
        for (run in runs) {
            val longest = run.minWithOrNull(
                compareByDescending<Int> { lengths[it] }.thenBy { it },
            )!!
            val thetaBar = angles[longest]
            val keep = mutableListOf<Int>()
            for (idx in run) {
                val residual = abs(angles[idx] - thetaBar)
                val excursion = lengths[idx] / 2f *
                    sin(Math.toRadians(residual.toDouble())).toFloat()
                if (idx != longest && excursion > RESIDUAL_EXCURSION_FRAC * heights[idx]) {
                    out.add(AngleCluster(listOf(idx), angles[idx]))
                } else {
                    keep.add(idx)
                }
            }
            if (keep.isNotEmpty()) out.add(AngleCluster(keep.sorted(), thetaBar))
        }
        return out.sortedBy { it.memberIndices.first() }
    }
}
