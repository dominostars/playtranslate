package com.playtranslate.ocr.core

import android.graphics.PointF
import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The single producer-side policy turning a detector's 4-point quad into an
 * oriented [OcrBox]. Both angle producers (ML Kit corner points, Paddle DBNet
 * quads) route through [boxFor] so the slant rules cannot drift apart. No ML
 * Kit / OpenCV imports by design — same contract as the rest of `ocr.core`.
 *
 * ## Sign convention
 * [Oriented.angleDeg] is `atan2(Δy, Δx)` over the reading axis in y-down image
 * coordinates: positive = visually clockwise = `View.rotation` semantics. The
 * renderer applies `child.rotation = box.angleDeg` with no negation.
 *
 * ## Producer invariant
 * [boxFor] snaps |angle| at or below the noise gate to a bit-identical
 * [OcrBox.upright]; [OcrBox.isRotated] is definitionally `angleDeg != 0f`.
 * Near-axis quads (all real-world upright text) therefore leave today's
 * geometry byte-identical, at any gate value.
 *
 * ## Probe
 * When [probeSink] is non-null, every quad-bearing [boxFor] call reports its
 * measured pre-policy angle and deciding rung — the population instrument for
 * gate/tolerance tuning. Null (the default) skips even the string build.
 */
internal object OrientedBoxGeometry {

    /** Reading-axis geometry of a quad: [longSide] along the reading direction,
     *  [shortSide] across it, [angleDeg] of the long axis in (-90, 90]. */
    internal data class Oriented(
        val longSide: Float,
        val shortSide: Float,
        val angleDeg: Float,
    )

    /** Aspect below which a quad has no meaningful reading axis: a 1–2 glyph
     *  detection is near-square and its min-area-rect angle is noise. */
    private const val MIN_AXIS_ASPECT = 1.2f

    /** Default slant ceiling — the ML Kit band: its recognizer reads slants
     *  internally and `detectOrientation` buckets 60–120° as VERTICAL (zeroed
     *  by [boxFor] rule 2), so 60° is where HORIZONTAL labels stop existing.
     *  Producers whose recognition geometry gives out earlier pass a lower
     *  ceiling ([boxFor]'s `maxSlantDeg`): Paddle claims nothing past 45°,
     *  where DBNet's corner ordering rolls and `warpCrop` treats elongated
     *  quads as columns, making reads sign-dependent — an angle-confident
     *  chip over such a read would be confidently wrong. */
    private const val MAX_SLANT_DEG = 60f

    /**
     * Pure geometry: reading axis of [quad], or null when the quad is
     * degenerate. Corner assignment uses the same sum/diff heuristic as
     * DBNet's `orderPoints` (tl = min(x+y), br = max(x+y), tr = min(y−x),
     * bl = max(y−x)), reimplemented here so `ocr.core` stays OpenCV-free.
     *
     * The reading axis is the LONGER edge pair, not a fixed corner pair: the
     * sum/diff assignment rolls its labels at exactly 45°, so a `p0→p1` rule
     * would jump by −90° there and report the cross-axis for steeper slants.
     * Keying on the longer edge is continuous through 45° and independent of
     * the caller's corner order (OpenCV's raw `RotatedRect.points()` order and
     * ML Kit's text-frame order land on the same answer). Cost: a 180°
     * ambiguity — upside-down text reads as its upright complement — which is
     * fine for footprint-faithful rendering.
     */
    internal fun fromQuad(quad: List<PointF>): Oriented? {
        if (quad.size != 4) return null
        val tl = quad.indices.minBy { quad[it].x + quad[it].y }
        val br = quad.indices.maxBy { quad[it].x + quad[it].y }
        val tr = quad.indices.minBy { quad[it].y - quad[it].x }
        val bl = quad.indices.maxBy { quad[it].y - quad[it].x }
        // A tie (one point winning two corners) means the heuristic has no
        // stable assignment — the exact-45° degenerate case. Refuse it.
        if (setOf(tl, tr, br, bl).size != 4) return null
        val p0 = quad[tl]; val p1 = quad[tr]; val p2 = quad[br]; val p3 = quad[bl]
        // Average each edge with its opposite (same stabilization idea as
        // warpCrop's max-of-opposite-edges) for a near-rectangle's true axes.
        val topX = (p1.x - p0.x + p2.x - p3.x) / 2f
        val topY = (p1.y - p0.y + p2.y - p3.y) / 2f
        val leftX = (p3.x - p0.x + p2.x - p1.x) / 2f
        val leftY = (p3.y - p0.y + p2.y - p1.y) / 2f
        val topLen = hypot(topX, topY)
        val leftLen = hypot(leftX, leftY)
        if (topLen <= 0f || leftLen <= 0f) return null
        val axisX: Float; val axisY: Float
        val longSide: Float; val shortSide: Float
        if (topLen >= leftLen) {
            axisX = topX; axisY = topY; longSide = topLen; shortSide = leftLen
        } else {
            axisX = leftX; axisY = leftY; longSide = leftLen; shortSide = topLen
        }
        var angle = Math.toDegrees(atan2(axisY.toDouble(), axisX.toDouble())).toFloat()
        if (angle > 90f) angle -= 180f
        if (angle <= -90f) angle += 180f
        return Oriented(longSide, shortSide, angle)
    }

    /**
     * Full policy: the [OcrBox] for a detection with AABB [aabb], optional
     * detector [quad], and the engine's [orientation] label. An ordered ladder —
     * every rejected rung falls back to [OcrBox.upright], bit-identical to
     * today's construction:
     *
     * 1. no usable quad (Meiki, ML Kit null corner points);
     * 2. VERTICAL label — tategaki is NOT rotation ([OcrBox] invariant): ML Kit
     *    reports ~±90° for vertical lines, and writing that through would mark
     *    every tategaki line rotated, silently disabling MangaOcrRefiner;
     * 3. degenerate quad;
     * 4. near-square quad (no reading axis to trust);
     * 5. |angle| within [minSlantDeg] — the producer noise-gate snap that keeps
     *    the producer invariant (see class doc);
     * 6. |angle| past [maxSlantDeg] — the producer's supported band ceiling
     *    (defaults to the ML Kit band; see [MAX_SLANT_DEG]).
     */
    fun boxFor(
        aabb: Rect,
        quad: List<PointF>?,
        orientation: TextOrientation,
        maxSlantDeg: Float = MAX_SLANT_DEG,
        minSlantDeg: Float = OcrBox.ANGLE_NOISE_GATE_DEG,
    ): OcrBox {
        if (quad == null || quad.size != 4) return OcrBox.upright(aabb)
        val o = fromQuad(quad)
        val rung: String
        val result: OcrBox
        when {
            orientation == TextOrientation.VERTICAL -> { rung = "vertical"; result = OcrBox.upright(aabb) }
            // Degenerate quad / near-square box: there WAS angle evidence but
            // it can't be resolved (no axis, or the axis direction is
            // unreliable) — an UNMEASURED upright, not a measured one, so
            // grouping may place the region by position instead of holding it
            // apart from slanted neighbors by a meaningless angle distance.
            o == null -> {
                rung = "degenerate"
                result = OcrBox.upright(aabb).copy(angleUnmeasured = true)
            }
            o.longSide < o.shortSide * MIN_AXIS_ASPECT -> {
                rung = "near-square"
                result = OcrBox.upright(aabb).copy(angleUnmeasured = true)
            }
            abs(o.angleDeg) <= minSlantDeg -> { rung = "snap"; result = OcrBox.upright(aabb) }
            // The second gate term, from the corpus census (2026-08-07, 5891
            // known-upright measurements): angle noise scales INVERSELY with
            // line length — short lines read up to ~10° on straight text while
            // long lines sit under ~1.5° — so no flat angle separates noise
            // from slant. The physical quantity that does is the drawn
            // corner's displacement, (ow/2)·sin|θ|: a chip whose rotation
            // moves its corners less than this many px renders visually
            // straight anyway, and every observed short-line noise excursion
            // sits under it while real slants (consistent per-seed 3–5° on
            // 300px+ lines) clear it by 2× or more.
            o.longSide / 2f * kotlin.math.sin(
                Math.toRadians(abs(o.angleDeg).toDouble()),
            ).toFloat() <= OcrBox.ANGLE_MIN_EXCURSION_PX -> {
                // Sub-excursion: the measured tilt sweeps too few pixels at
                // this length to distinguish from noise — a "couldn't
                // measure", not a "measured straight". The unmeasured bit is
                // what lets a short word of a genuinely slanted sentence
                // rejoin its line by position (the two-term gate makes mixed
                // measured/unmeasured output the NORM for mixed-length
                // slanted text, so silently stamping 0 here would split such
                // sentences permanently).
                rung = "excursion"
                result = OcrBox.upright(aabb).copy(angleUnmeasured = true)
            }
            abs(o.angleDeg) > maxSlantDeg -> { rung = "band"; result = OcrBox.upright(aabb) }
            else -> { rung = "rotated"; result = OcrBox(aabb, o.longSide, o.shortSide, o.angleDeg) }
        }
        // Population probe: measured pre-policy angle + deciding rung, every
        // detection with a quad — the instrument that answers gate/tolerance
        // questions from play-session distributions instead of single screens.
        // The AABB origin is the cross-cycle join key for per-element jitter.
        probeSink?.let { sink ->
            sink(
                "meas=" + (o?.angleDeg ?: Float.NaN) +
                    " rung=" + rung +
                    " aspect=" + (if (o != null) o.longSide / o.shortSide else Float.NaN) +
                    " min=" + minSlantDeg + " max=" + maxSlantDeg +
                    " aabb=(" + aabb.left + "," + aabb.top + " " + aabb.width() + "x" + aabb.height() + ")",
            )
        }
        return result
    }

    /** Probe output, injected by the app layer (`ocr.core` has no Context/Prefs
     *  access by design — same injection pattern as `OcrModelManager.appContext`).
     *  Null = off = zero per-detection cost beyond this read. */
    @Volatile
    var probeSink: ((String) -> Unit)? = null
}
