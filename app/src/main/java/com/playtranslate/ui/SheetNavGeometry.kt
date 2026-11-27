package com.playtranslate.ui

import kotlin.math.abs

/**
 * Pure spatial-navigation math for the capture sheet's controller cursor —
 * Android-free (own rect type, mirroring [CaptureResultGeometry]'s discipline)
 * so it's JVM-testable. Candidates are screen-space rects of the navigable
 * items (the sections' header buttons, the Ask section's controls, the
 * Words rows, source words); the caller owns
 * collecting them and mapping the returned index back to an item.
 */
object SheetNavGeometry {
    enum class Dir { UP, DOWN, LEFT, RIGHT }

    data class NavRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** Perpendicular-distance weight for candidates whose extent does NOT
     *  overlap the source on the cross axis — the "beam" preference that keeps
     *  a column walk in its column instead of drifting to a nearer-but-offset
     *  neighbor. Overlapping candidates pay only [ALIGNED_TIEBREAK] per
     *  perpendicular px, which orders same-row candidates by alignment without
     *  letting alignment beat axis distance. */
    const val OFF_AXIS_WEIGHT = 2f
    const val ALIGNED_TIEBREAK = 0.1f

    /** Index in [candidates] of the best move from [from] toward [dir], or null
     *  when nothing lies that way. A candidate qualifies only when its center is
     *  strictly beyond [from]'s center along the axis (so [from] itself, passed
     *  among the candidates, can never be chosen). Lowest score wins; exact ties
     *  break toward the smaller index (document order). */
    fun nextInDirection(from: NavRect, candidates: List<NavRect>, dir: Dir): Int? {
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in candidates.indices) {
            val c = candidates[i]
            val axis = when (dir) {
                Dir.UP -> from.centerY - c.centerY
                Dir.DOWN -> c.centerY - from.centerY
                Dir.LEFT -> from.centerX - c.centerX
                Dir.RIGHT -> c.centerX - from.centerX
            }
            if (axis <= 0) continue
            val overlaps = when (dir) {
                Dir.UP, Dir.DOWN -> c.right > from.left && c.left < from.right
                Dir.LEFT, Dir.RIGHT -> c.bottom > from.top && c.top < from.bottom
            }
            val perp = when (dir) {
                Dir.UP, Dir.DOWN -> abs(c.centerX - from.centerX)
                Dir.LEFT, Dir.RIGHT -> abs(c.centerY - from.centerY)
            }
            val score = axis + perp * (if (overlaps) ALIGNED_TIEBREAK else OFF_AXIS_WEIGHT)
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * How far to scroll (as `scrollBy`: positive moves the content up) so a
     * cursor's item, from [itemTop] to [itemBottom] on screen, shows in the
     * viewport from [viewTop] to [viewBottom], kept [pad] in from both edges:
     * 0 when it shows already, else the least scroll that shows it. An item
     * taller than that room shows its top: a scroll never takes the item's
     * top out of view to bring its end in, since the top is what says what it
     * is (a definitions row's word, which the ring outlines).
     */
    fun revealScrollDelta(itemTop: Int, itemBottom: Int, viewTop: Int, viewBottom: Int, pad: Int = 0): Int {
        val top = viewTop + pad
        val bottom = viewBottom - pad
        return when {
            itemTop < top -> itemTop - top
            itemBottom > bottom -> minOf(itemBottom - bottom, itemTop - top)
            else -> 0
        }
    }

    /** Selection for the first-ever controller press: topmost, then leftmost. */
    fun firstItem(candidates: List<NavRect>): Int? {
        var best = -1
        for (i in candidates.indices) {
            if (best < 0) {
                best = i
                continue
            }
            val b = candidates[best]
            val c = candidates[i]
            if (c.top < b.top || (c.top == b.top && c.left < b.left)) best = i
        }
        return best.takeIf { it >= 0 }
    }
}
