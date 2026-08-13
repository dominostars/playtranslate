package com.playtranslate.ocr.meiki

import android.graphics.PointF
import android.graphics.Rect
import com.playtranslate.ocr.core.OcrBox
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Model-free slant estimator for Meiki det-box crops: per-column ink
 * y-centroids, Theil–Sen slope through them, angle in degrees.
 *
 * Meiki's models carry NO angle information anywhere (D-FINE heads are
 * axis-aligned by construction; the recognizer's char boxes span the full
 * canvas cross-axis), yet the recognizer reads a deskewed crop of slanted text
 * perfectly out to 30° while losing half the characters of a long line at 3°
 * of slant through the upright crop. This estimator is the missing angle
 * source: it measures the baseline tilt from the pixels the pipeline already
 * cropped — one O(pixels) pass, sign included, no rotation sweep, no second
 * model.
 *
 * Sign convention matches [com.playtranslate.ocr.core.OcrBox.angleDeg] /
 * [com.playtranslate.ocr.mangaocr.deskewAffine]: y-down, positive = clockwise
 * on screen (text descending to the right).
 *
 * Known blindness, accepted: the det box is a thin band through a slanted
 * line, and the band clips glyph tops/bottoms symmetrically, biasing the
 * estimate toward 0 (~35% under-read at 3–6°, no signal at all on long lines
 * past ~8°). [MeikiRecognizer]'s retry iterates once on the deskewed strip to
 * recover most of the bias; where the signal is gone entirely the estimate is
 * ~0 and the caller keeps today's behavior — the failure mode is a missed
 * recovery, never a corrupted read.
 */
internal object MeikiSkewEstimator {

    /** Minimum ink-bearing columns for a fit. Below this (single glyphs, icon
     *  fragments) the centroid trend is noise: the full-corpus census's junk
     *  flags were all ≤ 48 px wide. */
    private const val MIN_INK_COLUMNS = 24

    /** Ink threshold as a fraction of the crop's max |gray − median|: kills
     *  background speckle and mild gradients without an adaptive binarizer. */
    private const val SPECKLE_FRACTION = 0.15f

    /** A column participates only when its ink mass clears this fraction of
     *  the heaviest column — drops the empty gaps between glyphs. */
    private const val COLUMN_MASS_FRACTION = 0.05f

    /** Cap on fit columns (stride-subsampled above this): bounds the pairwise
     *  slope count at ~80k on the widest crops. */
    private const val MAX_FIT_COLUMNS = 400

    /**
     * Estimated slant of [gray] (row-major, [width]×[height]) in degrees, or
     * 0 when there is no trustworthy signal (blank, sparse, or too narrow).
     * 0 is deliberately overloaded — "upright" and "can't tell" both return
     * it — because the caller's response to both is the same: change nothing.
     */
    fun estimate(gray: ByteArray, width: Int, height: Int): Float {
        val n = width * height
        if (width < 2 || height < 2 || gray.size < n) return 0f

        // Median via histogram; ink = |gray − median| (uniform backgrounds,
        // light or dark, subtract out without knowing text polarity).
        val hist = IntArray(256)
        for (i in 0 until n) hist[gray[i].toInt() and 0xff]++
        var acc = 0
        var median = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc * 2 >= n) { median = v; break }
        }
        var inkMax = 0
        val ink = IntArray(n)
        for (i in 0 until n) {
            val d = abs((gray[i].toInt() and 0xff) - median)
            ink[i] = d
            if (d > inkMax) inkMax = d
        }
        if (inkMax == 0) return 0f

        // Per-column mass and first moment over speckle-gated ink.
        val speckle = (SPECKLE_FRACTION * inkMax).toInt()
        val mass = LongArray(width)
        val moment = LongArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val v = ink[row + x]
                if (v < speckle) continue
                mass[x] += v.toLong()
                moment[x] += v.toLong() * y
            }
        }
        var maxMass = 0L
        for (x in 0 until width) if (mass[x] > maxMass) maxMass = mass[x]
        if (maxMass == 0L) return 0f
        val massFloor = (COLUMN_MASS_FRACTION * maxMass).toLong()
        val cols = ArrayList<Int>(width)
        for (x in 0 until width) if (mass[x] > massFloor) cols.add(x)
        if (cols.size < MIN_INK_COLUMNS) return 0f

        // Theil–Sen over (column, centroid): median pairwise slope. Robust to
        // the per-glyph oscillation and to local bumps (furigana, punctuation)
        // that would drag a least-squares fit.
        val step = if (cols.size <= MAX_FIT_COLUMNS) 1 else cols.size / MAX_FIT_COLUMNS
        val xs = ArrayList<Int>(MAX_FIT_COLUMNS + 1)
        val cys = ArrayList<Float>(MAX_FIT_COLUMNS + 1)
        var i = 0
        while (i < cols.size) {
            val x = cols[i]
            xs.add(x)
            cys.add(moment[x].toFloat() / mass[x])
            i += step
        }
        val minDx = max(8f, 0.05f * width)
        val slopes = ArrayList<Float>(xs.size * xs.size / 2)
        for (a in xs.indices) {
            for (b in a + 1 until xs.size) {
                val dx = (xs[b] - xs[a]).toFloat()
                if (dx < minDx) continue
                slopes.add((cys[b] - cys[a]) / dx)
            }
        }
        if (slopes.isEmpty()) return 0f
        slopes.sort()
        val m = slopes.size
        val slope = if (m % 2 == 1) slopes[m / 2] else (slopes[m / 2 - 1] + slopes[m / 2]) / 2f
        return Math.toDegrees(atan(slope.toDouble())).toFloat()
    }
}

/** Corners of the oriented rect (center [cx],[cy], true dims [ow]×[oh], slant
 *  [angleDeg]) in frame coords, tl→tr→br→bl — the synthesized quad that routes
 *  the estimator's measurement through [com.playtranslate.ocr.core.OrientedBoxGeometry.boxFor],
 *  making Meiki the third angle producer under the same policy ladder as
 *  ML Kit corner points and Paddle DBNet quads. */
internal fun rotatedRectQuad(cx: Float, cy: Float, ow: Float, oh: Float, angleDeg: Float): List<PointF> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val hw = ow / 2f
    val hh = oh / 2f
    fun corner(u: Float, v: Float) = PointF(cx + u * c - v * s, cy + u * s + v * c)
    return listOf(corner(-hw, -hh), corner(hw, -hh), corner(hw, hh), corner(-hw, hh))
}

/** Tight integer AABB of [quad] (floor mins, ceil maxes). */
internal fun quadAabb(quad: List<PointF>): Rect {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (p in quad) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    return Rect(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt(), ceil(maxY).toInt())
}

/**
 * Frame-coords AABB of a rect measured in [box]'s deskewed strip — the exact
 * inverse of [com.playtranslate.ocr.mangaocr.deskewAffine]'s mapping, so a
 * char box read off the warped strip lands back on the glyph's true (rotated)
 * frame position. Returns the upright AABB of the rotated corners, matching
 * how the other rotated producer (ML Kit) represents char boxes on slanted
 * lines: upright per-symbol AABBs under a rotated line box.
 */
internal fun stripRectToFrameAabb(box: OcrBox, rect: Rect): Rect {
    val rad = Math.toRadians(box.angleDeg.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val cx = box.bounds.exactCenterX()
    val cy = box.bounds.exactCenterY()
    val hw = box.orientedWidth / 2f
    val hh = box.orientedHeight / 2f
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (k in 0 until 4) {
        val du = (if (k == 1 || k == 2) rect.right else rect.left) - hw
        val dv = (if (k >= 2) rect.bottom else rect.top) - hh
        val x = cx + du * c - dv * s
        val y = cy + du * s + dv * c
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }
    return Rect(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt(), ceil(maxY).toInt())
}
