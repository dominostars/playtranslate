package com.playtranslate.ui

import android.graphics.RectF
import com.playtranslate.OcrManager
import com.playtranslate.language.TextOrientation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry of a capture's Definitions presentation ([WordLookupSession],
 * [WordBoxesView]): the box drawn
 * around each word, which box a tap lands on, and where the controller's
 * cursor steps along a row. Boxes are built on
 * [DragLookupController.placeTokensOnLine], the placement the drag lens
 * matches a finger against, so a box covers exactly the extent its token is
 * looked up by. Pure: no views, no context.
 */
internal object TapLookupGeometry {

    /**
     * One word's box. [rect] is in the box's own frame: screen pixels for an
     * upright line, the line's deskewed frame for a slanted one, which
     * [angleDeg] turns about ([pivotX], [pivotY]) clockwise, the line's own
     * angle with [android.view.View.rotation]'s sense. [lineIndex] and
     * [tokenIndex] address the token the box was drawn for in the caller's
     * lines and their tokenizations; [charOffset] is its offset in the line
     * text and [surface] its text there.
     */
    data class WordBox(
        val lineIndex: Int,
        val tokenIndex: Int,
        val charOffset: Int,
        val surface: String,
        val rect: RectF,
        val angleDeg: Float = 0f,
        val pivotX: Float = 0f,
        val pivotY: Float = 0f,
    ) {
        /** The box's axis-aligned bounds on screen: [rect] itself on an
         *  upright line, the bounds of its four corners turned by
         *  [angleDeg] on a slanted one. */
        fun screenBounds(): RectF {
            if (angleDeg == 0f) return RectF(rect)
            val rad = Math.toRadians(angleDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val xs = FloatArray(4)
            val ys = FloatArray(4)
            val corners = arrayOf(
                rect.left to rect.top, rect.right to rect.top,
                rect.right to rect.bottom, rect.left to rect.bottom,
            )
            for ((i, corner) in corners.withIndex()) {
                val dx = corner.first - pivotX
                val dy = corner.second - pivotY
                xs[i] = pivotX + dx * c - dy * s
                ys[i] = pivotY + dx * s + dy * c
            }
            return RectF(xs.min(), ys.min(), xs.max(), ys.max())
        }

        /** The screen point ([x], [y]) in this box's frame: unchanged on an
         *  upright line, turned back by [angleDeg] about the pivot on a
         *  slanted one (the frame [DragLookupController.flowU] measures). */
        fun toBoxFrame(x: Float, y: Float): Pair<Float, Float> {
            if (angleDeg == 0f) return x to y
            val rad = Math.toRadians(angleDeg.toDouble())
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            val dx = x - pivotX
            val dy = y - pivotY
            return (pivotX + dx * c + dy * s) to (pivotY - dx * s + dy * c)
        }
    }

    /**
     * The boxes for one OCR [line] (index [lineIndex] in the caller's
     * lines), one per token of [surfaces] (the line's tokenization, in line
     * order) that [DragLookupController.placeTokensOnLine] places. Across the
     * line a box spans the token's extent; along the other axis, the line's
     * own extent; both grow by [padPx]. Along the line, a box never grows
     * past the midpoint to its neighbour less half of [gapPx], so words that
     * touch (unspaced scripts) keep [gapPx] between their strokes.
     * Neighbours are taken in screen order, not token order, so a line laid
     * out right to left keeps its gaps.
     */
    fun wordBoxes(
        line: OcrManager.OcrLine,
        lineIndex: Int,
        surfaces: List<String>,
        padPx: Float,
        gapPx: Float,
    ): List<WordBox> {
        val placed = DragLookupController.placeTokensOnLine(line, surfaces)
        if (placed.isEmpty()) return emptyList()
        val order = placed.indices.sortedBy { placed[it].start }
        val lo = FloatArray(placed.size)
        val hi = FloatArray(placed.size)
        for ((rank, i) in order.withIndex()) {
            val p = placed[i]
            var from = p.start - padPx
            var to = p.end + padPx
            order.getOrNull(rank - 1)?.let { prev ->
                from = max(from, (placed[prev].end + p.start) / 2f + gapPx / 2f)
            }
            order.getOrNull(rank + 1)?.let { next ->
                to = minOf(to, (p.end + placed[next].start) / 2f - gapPx / 2f)
            }
            if (to <= from) {
                // A token narrower than the gap it must keep: a sliver at its
                // centre, still drawable and hittable.
                val mid = (p.start + p.end) / 2f
                from = mid - 0.5f
                to = mid + 0.5f
            }
            lo[i] = from
            hi[i] = to
        }
        val bounds = line.bounds
        val vertical = line.orientation == TextOrientation.VERTICAL
        return placed.mapIndexed { i, p ->
            when {
                line.angleDeg != 0f -> {
                    val cx = bounds.exactCenterX()
                    val cy = bounds.exactCenterY()
                    val halfH = line.orientedHeight / 2f + padPx
                    WordBox(
                        lineIndex, p.tokenIndex, p.charOffset, p.surface,
                        RectF(cx + lo[i], cy - halfH, cx + hi[i], cy + halfH),
                        angleDeg = line.angleDeg, pivotX = cx, pivotY = cy,
                    )
                }
                vertical -> WordBox(
                    lineIndex, p.tokenIndex, p.charOffset, p.surface,
                    RectF(bounds.left - padPx, lo[i], bounds.right + padPx, hi[i]),
                )
                else -> WordBox(
                    lineIndex, p.tokenIndex, p.charOffset, p.surface,
                    RectF(lo[i], bounds.top - padPx, hi[i], bounds.bottom + padPx),
                )
            }
        }
    }

    /**
     * The index of the box a tap at ([x], [y]) lands on, or null when it
     * lands on none. A box takes the tap when the point is inside it, or
     * within [slopPx] of it (the buffer around every word, where a tap that
     * just misses still picks a word): the closest box wins, a point inside
     * a box being at distance 0, so a box the tap is in beats a neighbour
     * whose buffer also reaches it; among boxes at the same distance (two
     * that overlap and both contain it), the one whose centre is nearer.
     * Slanted boxes are measured in their own frame.
     */
    fun hitTest(boxes: List<WordBox>, x: Float, y: Float, slopPx: Float): Int? {
        var best = -1
        var bestEdge = Float.MAX_VALUE
        var bestCentre = Float.MAX_VALUE
        for ((i, box) in boxes.withIndex()) {
            val (px, py) = box.toBoxFrame(x, y)
            val r = box.rect
            val dx = max(max(r.left - px, px - r.right), 0f)
            val dy = max(max(r.top - py, py - r.bottom), 0f)
            val edge = hypot(dx, dy)
            if (edge > slopPx) continue
            val centre = hypot(px - r.centerX(), py - r.centerY())
            if (edge < bestEdge || (edge == bestEdge && centre < bestCentre)) {
                best = i
                bestEdge = edge
                bestCentre = centre
            }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * The controller cursor's step along a row: from the box at [from] toward
     * [dir] (left or right on a horizontal line, up or down on a vertical
     * column), the nearest box of the same row whose centre lies beyond
     * [from]'s that way, by the distance along the row; ties go to the one
     * nearer across it. Null at the row's end, so the cursor stays put rather
     * than leave the row. Two boxes are in the same row when their extents
     * across it overlap by at least half the shorter one: a word on the same
     * line, or beside it in another column, is; the next line, whose padded
     * box may graze this one's, is not. [bounds] are the boxes' screen
     * bounds, index-parallel to the boxes.
     */
    fun nextInRow(bounds: List<RectF>, from: Int, dir: SheetNavGeometry.Dir): Int? {
        val f = bounds.getOrNull(from) ?: return null
        val horizontal = dir == SheetNavGeometry.Dir.LEFT || dir == SheetNavGeometry.Dir.RIGHT
        var best = -1
        var bestAlong = Float.MAX_VALUE
        var bestAcross = Float.MAX_VALUE
        for ((i, c) in bounds.withIndex()) {
            if (i == from) continue
            val along = when (dir) {
                SheetNavGeometry.Dir.RIGHT -> c.centerX() - f.centerX()
                SheetNavGeometry.Dir.LEFT -> f.centerX() - c.centerX()
                SheetNavGeometry.Dir.DOWN -> c.centerY() - f.centerY()
                SheetNavGeometry.Dir.UP -> f.centerY() - c.centerY()
            }
            if (along <= 0f) continue
            val overlap = if (horizontal) {
                min(f.bottom, c.bottom) - max(f.top, c.top)
            } else {
                min(f.right, c.right) - max(f.left, c.left)
            }
            val shorter = if (horizontal) min(f.height(), c.height()) else min(f.width(), c.width())
            if (overlap < shorter / 2f) continue
            val across = if (horizontal) abs(c.centerY() - f.centerY()) else abs(c.centerX() - f.centerX())
            if (along < bestAlong || (along == bestAlong && across < bestAcross)) {
                best = i
                bestAlong = along
                bestAcross = across
            }
        }
        return best.takeIf { it >= 0 }
    }
}
