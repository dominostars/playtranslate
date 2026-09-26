package com.playtranslate.ui

import android.graphics.RectF
import android.text.TextDirectionHeuristics
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import kotlin.math.abs
import kotlin.math.floor

/** A frame, in whole px, a box's text takes at one text size (padding
 *  included). The child floors its float rect to whole px, so the producer
 *  rounds up and pass 4 compares against the floored rect. */
data class FitSize(val width: Int, val height: Int)

/** Measures a box's translation for pass 4. The view supplies the real one
 *  ([OverlayTextMeasurer]); tests inject metrics. */
internal fun interface MinTextMeasurer {
    /** The frame (screen axes, padding included) [box]'s translation takes at
     *  text size [px] when laid along [wrapExtent] px of its wrap axis (the
     *  frame's width for horizontal text; its height for [RenderMode.ROTATE]
     *  and [RenderMode.STACK_UPRIGHT]), or null when that is too short to lay
     *  it without breaking a word (or, for a single-column stack, in one
     *  column). Along a longer wrap the text never gets taller across it. */
    fun footprint(box: TextBox, mode: RenderMode, px: Int, wrapExtent: Int): FitSize?
}

/**
 * Pass 4's inputs. The resolver runs the pass only when one is supplied, i.e.
 * only when the user raised the minimum above the default.
 *
 * @property targetPx the minimum text size, px: what every box aims for.
 * @property floorPx the autosize floor, px. A box that can't reach the target
 *   takes the largest size above this that some free rect holds.
 * @property limits the caller's bound and avoid rects, crop coords.
 */
internal class MinTextGrowth(
    val targetPx: Int,
    val floorPx: Int,
    val measurer: MinTextMeasurer,
    val limits: GrowthLimits = GrowthLimits.NONE,
)

/**
 * Pass 4 of [OverlayLayout.resolveScreenRects]: grow each box whose text can't
 * reach the minimum size into free space, Widen vertical text's rules
 * ([OverlayLayout.growIntoGaps]) generalized from one axis to two:
 *  - growth only expands, so the box keeps covering its source;
 *  - growth adds no overlap with another box or an avoid rect: whatever
 *    already overlaps the box is cut to its parts outside the box (the
 *    straddler clamp), and the growth stays out of those;
 *  - boxes are processed in a fixed order and a grown box is a fixed obstacle
 *    for the ones after it. The order is INPUT order: live mode keeps its
 *    survivors first and appends newcomers, so survivors claim their growth
 *    before any newcomer can.
 * A box that can't reach the minimum anywhere takes the largest size its own
 * rect or one of its free rects holds (its text shrinks only as much as it
 * must); one that can't reach even the smallest keeps today's layout.
 * Measurement dominates the cost (layouts on the UI thread), so a box that
 * already fits costs one measurement and nothing else.
 */
internal object MinTextLayout {

    private const val EPS = 1e-3f

    /** Which way a box grows on one axis when it needs more room: keep the
     *  low edge (grow toward high first), keep the high edge, or split. */
    enum class Anchor { LOW, HIGH, CENTER }

    /** How a render mode lays its text out: which screen axis the lines wrap
     *  along, and which edge each axis keeps when the box grows. */
    class Axes(val wrapIsWidth: Boolean, val x: Anchor, val y: Anchor)

    /** One box's outcome: `grownFrom` is the pre-pass rect when it grew, and
     *  `floorPx` the size its rect is certified for (null when the pass left
     *  it alone or couldn't fit even the smallest size). */
    class Outcome(val grownFrom: RectF?, val floorPx: Int?)

    /**
     * Run the pass. Mutates [rects] in place (index-aligned with [boxes]) and
     * returns one [Outcome] per box. [bound] and [avoid] are in screen
     * coords, the avoid rects already padded.
     */
    fun grow(
        boxes: List<TextBox>,
        rects: List<RectF>,
        modes: List<RenderMode>,
        bound: RectF,
        avoid: List<RectF>,
        growth: MinTextGrowth,
    ): List<Outcome> {
        val out = MutableList(boxes.size) { Outcome(null, null) }
        for (i in boxes.indices) {
            val box = boxes[i]
            val mode = modes[i]
            if (box.isFurigana || box.angleDeg != 0f || mode == RenderMode.SOURCE_ANGLE) continue
            if (box.translatedText.isEmpty()) continue

            val r = RectF(rects[i])
            val fit = Fit(box, mode, r, growth.measurer)
            // Most boxes already fit: one measurement, nothing else.
            if (fit.inPlace(growth.targetPx)) {
                out[i] = Outcome(null, growth.targetPx)
                continue
            }
            val rooms = fit.order(emptyRectsContaining(r, obstaclesFor(i, rects, avoid, r), bound))

            var px = growth.targetPx
            var placed = fit.place(px, rooms)
            if (placed == null) {
                // No room for the target: the text shrinks only as much as the
                // room forces. Room enough for a size is monotone in the size,
                // so a binary search finds the largest one some room holds.
                var lo = growth.floorPx + 1
                var hi = growth.targetPx - 1
                var best = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (fit.holdsSomewhere(mid, rooms)) {
                        best = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                if (best < 0) continue  // not even the smallest: today's layout
                px = best
                // Never null: holdsSomewhere found a room holding this size,
                // and place lays the text across the same rooms the same way.
                placed = fit.place(px, rooms) ?: continue
            }
            if (placed != r) {
                rects[i].set(placed)
                out[i] = Outcome(r, px)
            } else {
                out[i] = Outcome(null, px)
            }
        }
        return out
    }

    /**
     * One box's search: its text laid across rooms at a size, measured once
     * per (size, wrap extent) however many rooms and checks ask. Rooms are
     * sized in whole px the way the child frames its rect ([capacity]), so
     * every shape found here is one [place] can put down, and every list of
     * rooms passed in is in [order].
     */
    private class Fit(
        private val box: TextBox,
        private val mode: RenderMode,
        private val r: RectF,
        private val measurer: MinTextMeasurer,
    ) {
        private val axes = axesFor(box, mode)
        private val wrapHave = if (axes.wrapIsWidth) r.width() else r.height()
        private val crossHave = if (axes.wrapIsWidth) r.height() else r.width()
        private val laid = HashMap<Long, FitSize?>()

        private fun wrapCap(m: RectF) = capacity(if (axes.wrapIsWidth) m.width() else m.height(), wrapHave)
        private fun crossCap(m: RectF) = capacity(if (axes.wrapIsWidth) m.height() else m.width(), crossHave)
        private fun crossOf(s: FitSize) = if (axes.wrapIsWidth) s.height else s.width

        private fun at(px: Int, wrap: Int): FitSize? {
            val key = (px.toLong() shl 32) or (wrap.toLong() and 0xffffffffL)
            if (laid.containsKey(key)) return laid[key]
            return measurer.footprint(box, mode, px, wrap).also { laid[key] = it }
        }

        /** [rooms] in the order feasibility is tried: the largest cross
         *  capacity first (the room that holds the text there allows its
         *  tightest wrap of all), the widest wrap among equals. */
        fun order(rooms: List<RectF>): List<RectF> =
            rooms.sortedWith(compareByDescending<RectF> { crossCap(it) }.thenByDescending { wrapCap(it) })

        /** Whether the text fits the box's own rect at [px]. */
        fun inPlace(px: Int): Boolean {
            val s = at(px, wrapHave.toInt()) ?: return false
            return crossOf(s) <= crossHave.toInt()
        }

        /** The frame at the box's own wrap, where some room holds it. */
        private fun keepingWrap(px: Int, rooms: List<RectF>): FitSize? {
            val s = at(px, wrapHave.toInt()) ?: return null
            return s.takeIf { rooms.any { m -> crossOf(s) <= crossCap(m) } }
        }

        /**
         * The first room in [order] that holds the text at [px] laid across
         * its whole wrap capacity (as short as the text gets there, so if
         * that doesn't fit, nothing in the room does), or null. Its tightest
         * wrap is the tightest any room allows: a larger cross capacity only
         * ever lets the wrap narrow. One measurement per distinct cross
         * capacity, since a narrower room with the same cross holds nothing
         * a wider one didn't.
         */
        private fun feasibleRoom(px: Int, rooms: List<RectF>): RectF? {
            var tried = Int.MIN_VALUE
            for (m in rooms) {
                val cc = crossCap(m)
                if (cc == tried) continue
                tried = cc
                val wc = wrapCap(m)
                // No wider than the box: its widest shape is the kept wrap's.
                if (wc <= wrapHave.toInt()) continue
                val s = at(px, wc) ?: continue
                if (crossOf(s) <= cc) return m
            }
            return null
        }

        /** Whether some room holds the text at [px]. */
        fun holdsSomewhere(px: Int, rooms: List<RectF>): Boolean =
            keepingWrap(px, rooms) != null || feasibleRoom(px, rooms) != null

        /**
         * The grown rect for [px], or null when no room holds the text.
         * Shape: keep the wrap and grow across it where a room allows (a
         * dialogue block grows taller at its line length); else the tightest
         * wrap the rooms hold (a menu row widens), narrowed by binary search
         * from the [feasibleRoom]'s whole wrap capacity. Then, among the
         * rooms that hold the shape, the one that moves the anchored edges
         * least.
         */
        fun place(px: Int, rooms: List<RectF>): RectF? {
            keepingWrap(px, rooms)?.let { s -> return placeShape(wrapHave.toInt(), crossOf(s), rooms) }
            val m = feasibleRoom(px, rooms) ?: return null
            val cc = crossCap(m)
            var lo = wrapHave.toInt() + 1
            var hi = wrapCap(m)
            var tight = at(px, hi) ?: return null
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val s = at(px, mid)
                if (s != null && crossOf(s) <= cc) {
                    hi = mid
                    tight = s
                } else {
                    lo = mid + 1
                }
            }
            return placeShape(hi, crossOf(tight), rooms)
        }

        /** [r] grown to a [wrap] x [cross] px shape inside the room that
         *  moves its anchored edges least. */
        private fun placeShape(wrap: Int, cross: Int, rooms: List<RectF>): RectF? {
            val w = need(if (axes.wrapIsWidth) wrap else cross, r.width())
            val h = need(if (axes.wrapIsWidth) cross else wrap, r.height())
            var best: RectF? = null
            var bestCost = Float.MAX_VALUE
            for (m in rooms) {
                if (w > m.width() + EPS || h > m.height() + EPS) continue
                val (left, right) = extend(r.left, r.right, w, m.left, m.right, axes.x)
                val (top, bottom) = extend(r.top, r.bottom, h, m.top, m.bottom, axes.y)
                val cost = anchorCost(r.left, r.right, left, right, axes.x) +
                    anchorCost(r.top, r.bottom, top, bottom, axes.y)
                if (cost < bestCost - EPS) {
                    bestCost = cost
                    best = RectF(left, top, right, bottom)
                }
            }
            return best
        }
    }

    /** The largest whole-px extent a frame can take in a room [extent] px long
     *  on an axis where the box has [have] px: the floored size the box
     *  already has, or a grown one that keeps [need]'s half px inside the room. */
    private fun capacity(extent: Float, have: Float): Int =
        maxOf(have.toInt(), floor(extent - 0.5f + EPS).toInt())

    /** The extent an axis needs for a whole-px frame [px] when it has [have]:
     *  unchanged if the child's floored size already holds it, else [px] plus
     *  half a px, so float error can't floor it one short. */
    private fun need(px: Int, have: Float): Float =
        if (px <= have.toInt()) have else maxOf(px + 0.5f, have)

    /**
     * Everything box [i] must not grow into, relative to its rect [r]: the
     * other boxes' current rects (furigana included, so growth can't cover a
     * reading either) and the avoid rects. Obstacles already overlapping [r]
     * are cut to their parts outside it.
     */
    private fun obstaclesFor(i: Int, rects: List<RectF>, avoid: List<RectF>, r: RectF): List<RectF> {
        val out = ArrayList<RectF>()
        fun add(o: RectF) {
            if (o.width() <= 0f || o.height() <= 0f) return
            if (overlaps(o, r)) splitAround(o, r, out) else out += o
        }
        for (j in rects.indices) if (j != i) add(rects[j])
        for (a in avoid) add(a)
        return out
    }

    private fun overlaps(a: RectF, b: RectF) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    /** [o] minus [r], as up to four rects: full-height side slabs, then the
     *  parts above and below [r]'s own columns. */
    private fun splitAround(o: RectF, r: RectF, out: MutableList<RectF>) {
        if (o.left < r.left) out += RectF(o.left, o.top, r.left, o.bottom)
        if (o.right > r.right) out += RectF(r.right, o.top, o.right, o.bottom)
        val l = maxOf(o.left, r.left)
        val rt = minOf(o.right, r.right)
        if (l < rt) {
            if (o.top < r.top) out += RectF(l, o.top, rt, r.top)
            if (o.bottom > r.bottom) out += RectF(l, r.bottom, rt, o.bottom)
        }
    }

    /**
     * The maximal obstacle-free rects inside [bound] (widened to contain [r],
     * so a box already past the region's edge is never cut) that contain [r].
     * Every such rect has its top at the bound or an obstacle's bottom and its
     * bottom at the bound or an obstacle's top; within that band the
     * obstacles beside [r] fix its left and right, and an obstacle over [r]'s
     * own columns makes the band impossible. Tops sweep upward and bottoms
     * downward, each stopping at the first impossible band (a taller band
     * only adds obstacles). Complete once [obstacles] are disjoint from [r].
     *
     * Each band's sides are maintained incrementally as the band grows, so a
     * box costs its edges times its obstacles, not their product.
     *
     * The sweep also yields bands nested in taller ones. A taller band has
     * equal or tighter sides, so one rect can only contain another with the
     * same sides; the maximal rects are one per side pair, spanning the
     * union of its bands (a band too, since both hold [r]).
     */
    fun emptyRectsContaining(r: RectF, obstacles: List<RectF>, bound: RectF): List<RectF> {
        val b = RectF(
            minOf(bound.left, r.left), minOf(bound.top, r.top),
            maxOf(bound.right, r.right), maxOf(bound.bottom, r.bottom),
        )
        val tops = (obstacles.map { it.bottom }.filter { it <= r.top && it > b.top } + b.top)
            .distinct().sortedDescending()
        val bottoms = (obstacles.map { it.top }.filter { it >= r.bottom && it < b.bottom } + b.bottom)
            .distinct().sorted()
        // For a fixed top the band's obstacles only grow as its bottom moves
        // down: admit them in top order, keeping the sides and the block.
        val byTop = obstacles.sortedBy { it.top }
        // A band reaching down to r's own top/bottom always exists: start
        // each sweep from r's edges.
        val bySides = LinkedHashMap<Pair<Float, Float>, RectF>()
        for (t in listOf(r.top) + tops) {
            if (t > r.top) continue
            var next = 0
            var left = b.left
            var right = b.right
            var blocked = false
            fun admitAbove(bt: Float) {
                while (next < byTop.size && byTop[next].top < bt) {
                    val o = byTop[next++]
                    if (o.bottom <= t) continue
                    if (o.left < r.right && o.right > r.left) blocked = true
                    if (o.right <= r.left) left = maxOf(left, o.right)
                    else if (o.left >= r.right) right = minOf(right, o.left)
                }
            }
            admitAbove(r.bottom)
            if (blocked) break
            for (bt in listOf(r.bottom) + bottoms) {
                if (bt < r.bottom) continue
                admitAbove(bt)
                if (blocked) break
                val room = bySides.getOrPut(left to right) { RectF(left, t, right, bt) }
                room.top = minOf(room.top, t)
                room.bottom = maxOf(room.bottom, bt)
            }
        }
        return bySides.values.toList()
    }

    fun axesFor(box: TextBox, mode: RenderMode): Axes = when (mode) {
        // The child is laid out tall-side-as-width and turned 90° clockwise:
        // its lines run down the screen from the top and are centred across it.
        RenderMode.ROTATE -> Axes(wrapIsWidth = false, x = Anchor.CENTER, y = Anchor.LOW)
        // VerticalTextView packs from the top-right: rows down, columns leftward.
        RenderMode.STACK_UPRIGHT -> Axes(wrapIsWidth = false, x = Anchor.HIGH, y = Anchor.LOW)
        else -> {
            // Horizontal text, centred vertically (CENTER / CENTER_VERTICAL
            // gravity). Horizontally the text starts at its paragraph's start
            // edge unless the child centres it (GROW, CENTER alignment) or the
            // source is a vertical column (its growth splits, as in Widen).
            val x = when {
                mode == RenderMode.GROW_HORIZONTAL ||
                    box.alignment == TextAlignment.CENTER ||
                    box.orientation == TextOrientation.VERTICAL -> Anchor.CENTER
                TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(
                    box.translatedText, 0, box.translatedText.length,
                ) -> Anchor.HIGH
                else -> Anchor.LOW
            }
            Axes(wrapIsWidth = true, x = x, y = Anchor.CENTER)
        }
    }

    /** Extend [[lo], [hi]] to [length] inside [[min], [max]], growing from the
     *  [anchor] side first and pushing whatever that side can't take to the
     *  other (growIntoGaps' split, for CENTER). */
    fun extend(lo: Float, hi: Float, length: Float, min: Float, max: Float, anchor: Anchor): Pair<Float, Float> {
        val extra = (length - (hi - lo)).coerceAtLeast(0f)
        if (extra == 0f) return lo to hi
        val roomLow = (lo - min).coerceAtLeast(0f)
        val roomHigh = (max - hi).coerceAtLeast(0f)
        val addLow: Float
        val addHigh: Float
        when (anchor) {
            Anchor.LOW -> {
                addHigh = minOf(extra, roomHigh)
                addLow = minOf(extra - addHigh, roomLow)
            }
            Anchor.HIGH -> {
                addLow = minOf(extra, roomLow)
                addHigh = minOf(extra - addLow, roomHigh)
            }
            Anchor.CENTER -> {
                val half = minOf(extra / 2f, roomHigh)
                addLow = minOf(extra - half, roomLow)
                addHigh = minOf(extra - addLow, roomHigh)
            }
        }
        return (lo - addLow) to (hi + addHigh)
    }

    /** How far the anchored edge (or the centre) moved. */
    private fun anchorCost(lo: Float, hi: Float, newLo: Float, newHi: Float, anchor: Anchor): Float =
        when (anchor) {
            Anchor.LOW -> lo - newLo
            Anchor.HIGH -> newHi - hi
            Anchor.CENTER -> abs((newLo + newHi) - (lo + hi)) / 2f
        }
}
