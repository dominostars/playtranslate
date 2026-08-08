package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DeskewGeometry

/**
 * How a box's translation is rendered, decided once in [OverlayLayout.resolveScreenRects]
 * from the box geometry + target script + the grow pref, and consumed by
 * `TranslationOverlayView.rebuildChildren`. Splitting the render decision out of the view
 * keeps it pure and unit-testable, and lets the overlap passes pick a box's geometry from
 * its *footprint* (horizontal vs vertical vs grown) rather than its raw OCR orientation.
 *
 * - [LEGACY_HORIZONTAL] — a horizontal OCR box (today's autosized horizontal text).
 * - [HORIZONTAL_IN_PLACE] — a vertical box already wide enough for a legible horizontal line
 *   (e.g. a merged multi-column block); render horizontally in place, no rotation.
 * - [STACK_UPRIGHT] — upright tategaki via [VerticalTextView]: always for vertical-script
 *   (CJK) targets, and for short single-token translations in a stackable script.
 * - [GROW_HORIZONTAL] — a narrow vertical box grown in width over its source and rendered
 *   horizontally ([resolveScreenRects] pass 3). Only produced when the grow pref is on.
 * - [ROTATE] — the 90°-rotated fallback in the box's original narrow footprint. Reached when grow
 *   is off (or the script can't stack) and the box is too narrow for a horizontal line, and also
 *   when grow is on but the box is too wedged between neighbours to grow to a legible width.
 * - [SOURCE_ANGLE] — a genuinely slanted source box ([TextBox.angleDeg] != 0): the chip lays out
 *   at the oriented dims and rotates by the source angle about the bounds center. Unlike [ROTATE]
 *   (a *render* decision for narrow upright columns), this reflects the *source* geometry.
 *   Slanted boxes skip the carve passes — a carve would move the registered AABB but not the
 *   drawn chip — so residual overlap with neighbours is accepted (standalone diagonals are rare).
 */
enum class RenderMode {
    LEGACY_HORIZONTAL,
    HORIZONTAL_IN_PLACE,
    STACK_UPRIGHT,
    GROW_HORIZONTAL,
    ROTATE,
    SOURCE_ANGLE,
}

/** The drawn geometry of a [RenderMode.SOURCE_ANGLE] chip, screen px: padded
 *  oriented dims centered on ([centerX], [centerY]), rotated by [angleDeg].
 *  Same-angle carving moves the center/dims in the cluster's deskewed frame;
 *  the renderer consumes this payload verbatim (autosize wrap + skeleton dims
 *  + the rotated pin all follow it). */
data class OrientedChip(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleDeg: Float,
)

/** A box's resolved on-screen rect paired with its chosen [RenderMode]. Index-aligned with
 *  the input `boxes` list — the index alignment is load-bearing for pinhole change-detection
 *  (`PinholeOverlayMode` walks `getChildScreenRects()` positionally against its box list).
 *  [chip] is non-null ⟺ the mode is [RenderMode.SOURCE_ANGLE] (derived from the box's
 *  angle, never dual state); [rect] is then the chip's EXACT unclamped oriented AABB. */
data class ResolvedBox(val rect: RectF, val mode: RenderMode, val chip: OrientedChip? = null)

/**
 * Pure geometry for translation overlays: maps OCR-bitmap box bounds to on-screen rects,
 * picks each box's [RenderMode], and resolves overlaps between neighbouring boxes.
 *
 * Extracted from `TranslationOverlayView.rebuildChildren` so the geometry resolves through
 * one tested implementation. Pure and side-effect free — unit-testable without a live View
 * (callers inject [TextBox.minWidthPx]; this object never measures text).
 */
internal object OverlayLayout {

    /** Padding (dp) added around a non-furigana box for visual breathing room. */
    private const val BOX_PADDING_DP = 6f

    /** A [RenderMode.GROW_HORIZONTAL] box that can't reach this fraction of its legible target
     *  width (`targetW = min(minWidthPx, ½·displayW)`) even by claiming all the room on both sides
     *  falls back to [RenderMode.ROTATE] rather than rendering a too-narrow horizontal line. An
     *  absolute legibility floor, not a fraction of the needed expansion — so an isolated box
     *  (full-screen room) can never spuriously rotate. */
    private const val ROTATE_FALLBACK_MIN_WIDTH_FRAC = 0.7f

    /** Map an OCR-bitmap rect to on-screen coordinates. */
    fun mapRect(
        r: Rect,
        cropOffsetX: Int, cropOffsetY: Int,
        scaleX: Float, scaleY: Float,
    ): RectF = RectF(
        (r.left + cropOffsetX) * scaleX,
        (r.top + cropOffsetY) * scaleY,
        (r.right + cropOffsetX) * scaleX,
        (r.bottom + cropOffsetY) * scaleY,
    )

    /**
     * Resolve every box's final on-screen rect + [RenderMode]: map OCR bounds → screen, pad
     * non-furigana boxes, resolve overlaps in two passes keyed by the box's source
     * **orientation** (not its render footprint) — horizontal-source boxes are stacked rows →
     * vertical-overlap shrink; vertical-source boxes are side-by-side columns → horizontal-
     * overlap shrink — then pick each box's render mode from its **post-shrink** rect and grow
     * GROW boxes into the freed width. Resolving by orientation is essential: a wide vertical
     * column rendered HORIZONTAL_IN_PLACE is still a column and must de-overlap with its sibling
     * columns, not get siloed away from them. Choosing the mode *after* the shrink matters too:
     * a column carved below its min width must reclassify (e.g. to GROW), not render too-narrow
     * horizontal text.
     *
     * @param targetIsVerticalScript the target is written vertically (CJK) — its vertical
     *   boxes always stack (tategaki) and keep the shrink geometry. Required (one production
     *   caller; a default would only risk a future caller silently getting CJK behaviour).
     * @param targetStackable the target script can be stacked as upright cells (CJK / Latin /
     *   Cyrillic / Greek — not Arabic / Thai / Indic). Gates [RenderMode.STACK_UPRIGHT] for
     *   non-CJK targets.
     * @param growEnabled the grow-narrow-boxes pref. When off, a narrow non-stackable box
     *   falls back to [RenderMode.ROTATE] instead of growing.
     *
     * Returns one [ResolvedBox] per input box, index-aligned with [boxes].
     */
    fun resolveScreenRects(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        displayW: Int, displayH: Int,
        density: Float,
        targetIsVerticalScript: Boolean,
        targetStackable: Boolean = false,
        growEnabled: Boolean = false,
    ): List<ResolvedBox> {
        val scaleX = displayW.toFloat() / screenshotW
        val scaleY = displayH.toFloat() / screenshotH
        val boxPadding = BOX_PADDING_DP * density
        val dW = displayW.toFloat()
        val dH = displayH.toFloat()

        // Map OCR bounds to screen coordinates; pad non-furigana boxes.
        val finalRects = boxes.map { box ->
            val r = mapRect(box.bounds, cropLeft, cropTop, scaleX, scaleY)
            if (box.isFurigana) {
                RectF(r.left, r.top, r.right, r.bottom)
            } else {
                RectF(
                    (r.left - boxPadding).coerceAtLeast(0f),
                    (r.top - boxPadding).coerceAtLeast(0f),
                    (r.right + boxPadding).coerceAtMost(dW),
                    (r.bottom + boxPadding).coerceAtMost(dH),
                )
            }
        }

        // Unpadded source rects: the carve floor for pass 1. A child that stops
        // covering its source text breaks the occlusion contract pinhole mode is
        // built on — the uncovered sliver re-OCRs as garbage, stales the box,
        // and the overlay churns (two live traces 2026-07-10: side-by-side boxes
        // whose padding bands overlapped by a few px were mid-split as if
        // stacked, cutting a whole text row out of one box's coverage).
        // Pass 2 shares the carve-below-source shape for vertical columns but is
        // left as-is deliberately: its carves feed the mode reclassification +
        // growIntoGaps machinery, and no uncovering instance has been observed
        // there — same-family watch item if vertical-text churn ever shows up.
        val sourceRects = boxes.map { mapRect(it.bounds, cropLeft, cropTop, scaleX, scaleY) }

        // SOURCE_ANGLE chips: padding lives in the DESKEWED frame — the drawn
        // chip is the oriented dims + padding, centered on the unpadded mapped
        // bounds center (the AABB-padded rect is not what's drawn). The
        // resolved rect becomes the chip's exact oriented AABB, deliberately
        // UNCLAMPED: clamping the AABB would move the registered rect without
        // moving the drawn chip.
        val chips = arrayOfNulls<OrientedChip>(boxes.size)
        for (i in boxes.indices) {
            val b = boxes[i]
            if (b.isFurigana || b.angleDeg == 0f) continue
            val src = sourceRects[i]
            chips[i] = OrientedChip(
                src.centerX(), src.centerY(),
                b.orientedWidth * scaleX + 2 * boxPadding,
                b.orientedHeight * scaleY + 2 * boxPadding,
                b.angleDeg,
            )
        }
        for (i in boxes.indices) chips[i]?.let { finalRects[i].set(chipAabb(it)) }

        // Pass 1 — horizontal-source boxes: resolve overlaps without ever
        // carving inside a box's unpadded source rect. Three geometries:
        //  - Stacked rows (sources y-disjoint): split at the y-midline, which
        //    lands in the inter-row padding band; the source clamp is a no-op
        //    safety there (long-standing behaviour, unchanged).
        //  - Side-by-side (sources x-disjoint, y-overlapping): the overlap is
        //    purely the two padding bands — split at the x-midline of the
        //    source gap so both boxes keep full coverage and stay disjoint.
        //  - Sources overlapping on both axes (upstream OCR jitter/dup): no
        //    clean split exists; clamp the y-split to the source edges and
        //    accept the residual rendered overlap (z-order resolves it) —
        //    covering source text outranks disjoint rendering.
        // Slanted boxes sit out both carve passes (mirroring the furigana
        // exclusion): a carve moves the registered AABB but the drawn chip —
        // oriented dims rotated about the bounds center — would not follow,
        // so the carve would be both wrong (uncovered source) and invisible.
        val hBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].angleDeg == 0f &&
                boxes[it].orientation != TextOrientation.VERTICAL
        }.sortedBy { finalRects[it].top }
        for (a in hBoxIndices.indices) {
            for (b in a + 1 until hBoxIndices.size) {
                val i = hBoxIndices[a]
                val j = hBoxIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                if (ri.bottom <= rj.top || ri.left >= rj.right || ri.right <= rj.left) continue
                val si = sourceRects[i]
                val sj = sourceRects[j]
                val yDisjoint = si.bottom <= sj.top || sj.bottom <= si.top
                val xDisjoint = si.right <= sj.left || sj.right <= si.left
                if (yDisjoint || !xDisjoint) {
                    // ri is the upper rect (sorted by top): its bottom may only
                    // retreat to its source bottom, rj's top to its source top.
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid.coerceAtLeast(si.bottom).coerceAtMost(ri.bottom)
                    rj.top = mid.coerceAtMost(sj.top).coerceAtLeast(rj.top)
                } else {
                    // Which box is left in SOURCE space decides the carve sides.
                    val iIsLeft = si.right <= sj.left
                    val lRect = if (iIsLeft) ri else rj
                    val rRect = if (iIsLeft) rj else ri
                    val lSrc = if (iIsLeft) si else sj
                    val rSrc = if (iIsLeft) sj else si
                    val mid = (lSrc.right + rSrc.left) / 2f
                    lRect.right = mid.coerceAtLeast(lSrc.right).coerceAtMost(lRect.right)
                    rRect.left = mid.coerceAtMost(rSrc.left).coerceAtLeast(rRect.left)
                }
            }
        }

        // Pass 1b — same-angle slant clusters: chips whose angles agree within
        // [CARVE_CLUSTER_TOL_DEG] carve against each other with pass 1's exact
        // geometry, run in the cluster's shared deskewed frame; carved members
        // adopt the cluster angle (θ̄ = the longest member's, verbatim) so the
        // in-frame geometry exactly describes the drawn chips. Cross-angle
        // chips still don't carve (documented limitation).
        carveSlantClusters(boxes, chips, finalRects, boxPadding)

        // Pass 2 — vertical-source boxes (side-by-side columns, ALL render modes:
        // HORIZONTAL_IN_PLACE / STACK / ROTATE / GROW): resolve horizontal overlaps between
        // adjacent columns in right-to-left reading order, so a narrow column and a wide one
        // still separate even though they render differently.
        val vBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && boxes[it].angleDeg == 0f &&
                boxes[it].orientation == TextOrientation.VERTICAL
        }.sortedByDescending { finalRects[it].right }
        for (a in vBoxIndices.indices) {
            for (b in a + 1 until vBoxIndices.size) {
                val ri = finalRects[vBoxIndices[a]]
                val rj = finalRects[vBoxIndices[b]]
                if (ri.left < rj.right && ri.top < rj.bottom && ri.bottom > rj.top) {
                    val mid = (ri.left + rj.right) / 2f
                    ri.left = mid
                    rj.right = mid
                }
            }
        }

        // Pick each box's render mode from its FINAL (post-shrink) rect: pass 2 may have carved
        // a vertical column below its measured min width, which must reclassify it (e.g. a
        // now-too-narrow HORIZONTAL_IN_PLACE column falls to GROW) rather than render it too
        // small. Safe here because passes 1–2 partition by orientation, not by mode.
        val modes = boxes.indices.map { i ->
            renderModeFor(boxes[i], finalRects[i], density, targetIsVerticalScript, targetStackable, growEnabled)
        }.toMutableList()

        // Pass 3 — GROW_HORIZONTAL: grow each box's width toward min-width into the free space
        // beside it. Pass 2 already separated the columns, so the growth clamps keep them
        // disjoint; growth only ever expands, so each box keeps covering its source.
        growIntoGaps(boxes, finalRects, modes, dW)

        return boxes.indices.map { ResolvedBox(finalRects[it], modes[it], chips[it]) }
    }

    /** Same-angle slant-cluster carve tolerance (degrees): must exceed the
     *  observed pairwise angle jitter of co-rendered chips (probe: typical
     *  ≤0.7°, worst clean ~2°) while staying under the grouping cluster cap. */
    private const val CARVE_CLUSTER_TOL_DEG = 3f

    /** Exact oriented AABB of a chip (closed form w·|cos| + h·|sin|). */
    private fun chipAabb(chip: OrientedChip): RectF {
        val rad = Math.toRadians(chip.angleDeg.toDouble())
        val ac = kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
        val asn = kotlin.math.abs(kotlin.math.sin(rad)).toFloat()
        val w = chip.width * ac + chip.height * asn
        val h = chip.width * asn + chip.height * ac
        return RectF(
            chip.centerX - w / 2f, chip.centerY - h / 2f,
            chip.centerX + w / 2f, chip.centerY + h / 2f,
        )
    }

    /** Carve overlapping same-angle chips against each other in their shared
     *  deskewed frame — pass 1's geometry verbatim on (u, v): stacked rows
     *  split at the midline clamped to the source floors; side-by-side chips
     *  split through the source gap; both-axes source overlap clamps and
     *  accepts residual. Source floors are each member's UNPADDED chip,
     *  inflated by the closed-form AABB growth of its residual δ from θ̄
     *  (δ ≤ the tolerance, so the inflation is small and conservative).
     *  Mutates [chips] (carved members adopt θ̄) and [finalRects] (exact
     *  AABBs follow the carved chips). */
    private fun carveSlantClusters(
        boxes: List<TextBox>,
        chips: Array<OrientedChip?>,
        finalRects: List<RectF>,
        boxPadding: Float,
    ) {
        val idxs = boxes.indices.filter { chips[it] != null }
        if (idxs.size < 2) return
        val clusters = DeskewGeometry.clusterByAngle(
            idxs.map { boxes[it].angleDeg },
            idxs.map { chips[it]!!.width },
            idxs.map { chips[it]!!.height },
            CARVE_CLUSTER_TOL_DEG,
        )
        for (cluster in clusters) {
            if (cluster.memberIndices.size < 2) continue
            val members = cluster.memberIndices.map { idxs[it] }
            val theta = cluster.angleDeg
            val rad = Math.toRadians(theta.toDouble())
            val c = kotlin.math.cos(rad).toFloat()
            val s = kotlin.math.sin(rad).toFloat()
            val ax = members.map { chips[it]!!.centerX }.average().toFloat()
            val ay = members.map { chips[it]!!.centerY }.average().toFloat()
            fun toU(x: Float, y: Float) = (x - ax) * c + (y - ay) * s
            fun toV(x: Float, y: Float) = -(x - ax) * s + (y - ay) * c

            fun inFrame(i: Int, w: Float, h: Float): RectF {
                val ch = chips[i]!!
                val u = toU(ch.centerX, ch.centerY)
                val v = toV(ch.centerX, ch.centerY)
                return RectF(u - w / 2f, v - h / 2f, u + w / 2f, v + h / 2f)
            }

            val rects = members.map { i -> inFrame(i, chips[i]!!.width, chips[i]!!.height) }
            val floors = members.map { i ->
                val ch = chips[i]!!
                val uw = (ch.width - 2 * boxPadding).coerceAtLeast(1f)
                val uh = (ch.height - 2 * boxPadding).coerceAtLeast(1f)
                val d = Math.toRadians(kotlin.math.abs(boxes[i].angleDeg - theta).toDouble())
                val dc = kotlin.math.cos(d).toFloat()
                val ds = kotlin.math.sin(d).toFloat()
                inFrame(i, uw * dc + uh * ds, uw * ds + uh * dc)
            }

            val order = members.indices.sortedBy { rects[it].top }
            for (a in order.indices) {
                for (b in a + 1 until order.size) {
                    val i = order[a]
                    val j = order[b]
                    val ri = rects[i]
                    val rj = rects[j]
                    if (ri.bottom <= rj.top || ri.left >= rj.right || ri.right <= rj.left) continue
                    val si = floors[i]
                    val sj = floors[j]
                    val yDisjoint = si.bottom <= sj.top || sj.bottom <= si.top
                    val xDisjoint = si.right <= sj.left || sj.right <= si.left
                    if (yDisjoint || !xDisjoint) {
                        val mid = (ri.bottom + rj.top) / 2f
                        ri.bottom = mid.coerceAtLeast(si.bottom).coerceAtMost(ri.bottom)
                        rj.top = mid.coerceAtMost(sj.top).coerceAtLeast(rj.top)
                    } else {
                        val iIsLeft = si.right <= sj.left
                        val lRect = if (iIsLeft) ri else rj
                        val rRect = if (iIsLeft) rj else ri
                        val lSrc = if (iIsLeft) si else sj
                        val rSrc = if (iIsLeft) sj else si
                        val mid = (lSrc.right + rSrc.left) / 2f
                        lRect.right = mid.coerceAtLeast(lSrc.right).coerceAtMost(lRect.right)
                        rRect.left = mid.coerceAtMost(rSrc.left).coerceAtLeast(rRect.left)
                    }
                }
            }

            for (k in members.indices) {
                val i = members[k]
                val r = rects[k]
                val ucx = r.centerX()
                val vcy = r.centerY()
                chips[i] = OrientedChip(
                    ax + ucx * c - vcy * s,
                    ay + ucx * s + vcy * c,
                    r.width(), r.height(), theta,
                )
                finalRects[i].set(chipAabb(chips[i]!!))
            }
        }
    }

    /** Render mode for a single padded box rect. Horizontal/furigana boxes and CJK targets
     *  keep today's behaviour; non-CJK vertical boxes route by width and translation. */
    private fun renderModeFor(
        box: TextBox,
        rect: RectF,
        density: Float,
        targetIsVerticalScript: Boolean,
        targetStackable: Boolean,
        growEnabled: Boolean,
    ): RenderMode {
        if (box.isFurigana) return RenderMode.LEGACY_HORIZONTAL
        // Slanted source box. Checked before the horizontal guard below —
        // producers label every slanted box HORIZONTAL (tategaki is never
        // slanted by the OcrBox contract), so a check after it is unreachable.
        if (box.angleDeg != 0f) return RenderMode.SOURCE_ANGLE
        if (box.orientation != TextOrientation.VERTICAL) {
            return RenderMode.LEGACY_HORIZONTAL
        }
        // Vertical OCR box.
        if (targetIsVerticalScript) return RenderMode.STACK_UPRIGHT  // CJK tategaki
        // Non-vertical target (Latin/etc.): width-aware routing.
        if (rect.width() >= box.minWidthPx) return RenderMode.HORIZONTAL_IN_PLACE
        if (stackViable(box.translatedText, rect, density, targetStackable)) return RenderMode.STACK_UPRIGHT
        if (growEnabled) return RenderMode.GROW_HORIZONTAL
        return RenderMode.ROTATE
    }

    /**
     * Whether [text] reads legibly as a single upright column in a [rect]-sized box — the
     * gate for stacking a non-CJK translation. Requires a stackable script, a single token
     * (no internal whitespace — multi-word stacks read poorly), and that
     * [VerticalTextLayout.compute] fits every grapheme in **one** column with cell size at or
     * above the legibility floor. Uses the same `pad` as the live [VerticalTextView] so
     * "fits" matches what actually renders.
     */
    fun stackViable(text: String, rect: RectF, density: Float, targetStackable: Boolean): Boolean {
        if (!targetStackable || text.isEmpty()) return false
        if (text.any { it.isWhitespace() }) return false
        val graphemes = VerticalTextLayout.splitGraphemes(text)
        if (graphemes.isEmpty()) return false
        val minCellPx = VerticalTextLayout.STACK_MIN_CELL_SP * density
        val layout = VerticalTextLayout.compute(
            graphemeCount = graphemes.size,
            width = rect.width(), height = rect.height(),
            pad = 3f * density,
            minPx = minCellPx,
            maxPx = 200f * density,
        )
        // One column, every grapheme placed (no truncation). compute() never returns a cell
        // below minPx, so cell ≥ legibility floor holds whenever it placed them all.
        return layout.cols == 1 && layout.rows >= graphemes.size
    }

    /**
     * Grow each [RenderMode.GROW_HORIZONTAL] box's width toward `min(minWidthPx, ½·displayW)`,
     * extending outward into the free space on either side and clamping at the nearest
     * non-furigana neighbour that vertically overlaps it. Pass 2 has already de-overlapped the
     * sibling columns, so the clamps keep backgrounds **disjoint**; growth only expands, so each
     * box keeps covering its source. Mutates [rects] in place; processed right-to-left so
     * contention is deterministic (an earlier box's grown rect is a fixed obstacle for later
     * ones).
     *
     * A box too wedged to reach [ROTATE_FALLBACK_MIN_WIDTH_FRAC] of its target width is flipped to
     * [RenderMode.ROTATE] in [modes] and left at its narrow footprint instead of being grown.
     */
    private fun growIntoGaps(
        boxes: List<TextBox>,
        rects: List<RectF>,
        modes: MutableList<RenderMode>,
        displayW: Float,
    ) {
        val growIdx = boxes.indices
            .filter { !boxes[it].isFurigana && modes[it] == RenderMode.GROW_HORIZONTAL }
            .sortedByDescending { rects[it].right }
        for (gi in growIdx) {
            val r = rects[gi]
            val targetW = minOf(boxes[gi].minWidthPx.toFloat(), 0.5f * displayW)
            val extra = targetW - r.width()
            if (extra <= 0f) continue

            // Nearest blocking edges among other non-furigana boxes that vertically overlap r.
            var leftLimit = 0f
            var rightLimit = displayW
            for (j in boxes.indices) {
                if (j == gi || boxes[j].isFurigana) continue
                val o = rects[j]
                if (o.bottom <= r.top || o.top >= r.bottom) continue  // no vertical overlap
                if (o.right <= r.left) {
                    leftLimit = maxOf(leftLimit, o.right)
                } else if (o.left >= r.right) {
                    rightLimit = minOf(rightLimit, o.left)
                } else {
                    // STRADDLER: the neighbour already overlaps r horizontally
                    // (a slanted AABB that sat out the carves, or an upright
                    // box the carve passes left overlapping). It bounds growth
                    // on BOTH sides — clamp both limits to r's own edges, so r
                    // grows nowhere and the wedge fallback below decides
                    // whether it renders rotated. Conservative for slanted
                    // neighbours (the AABB contains the drawn footprint), and
                    // closes the old hole where an upright straddler was
                    // counted in NEITHER limit and grown straight through.
                    leftLimit = maxOf(leftLimit, r.left)
                    rightLimit = minOf(rightLimit, r.right)
                }
            }
            val leftRoom = (r.left - leftLimit).coerceAtLeast(0f)
            val rightRoom = (rightLimit - r.right).coerceAtLeast(0f)

            // Wedged between neighbours: if even claiming all the room on both sides can't bring the
            // box to a legible fraction of its target width, rotate in place instead of rendering a
            // too-narrow horizontal line. Leaving the rect at its narrow post-pass-2 footprint lets
            // the ROTATE render path use it directly (TranslationOverlayView swaps dims + rotates 90°).
            val maxAchievableW = r.width() + leftRoom + rightRoom
            if (maxAchievableW < ROTATE_FALLBACK_MIN_WIDTH_FRAC * targetW) {
                modes[gi] = RenderMode.ROTATE
                continue
            }

            // Split the needed width symmetrically, then push any remainder to the side that
            // still has room.
            var addRight = minOf(extra / 2f, rightRoom)
            var addLeft = minOf(extra - addRight, leftRoom)
            addRight = minOf(extra - addLeft, rightRoom)
            r.left -= addLeft
            r.right += addRight
        }
    }

    /** Fuzzy comparison: same content, bounds within [tolerance] px. Absorbs OCR jitter so
     *  stable on-screen text doesn't trigger an overlay rebuild. */
    fun boxesMatchFuzzy(a: List<TextBox>, b: List<TextBox>, tolerance: Int = 20): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val ba = a[i]; val bb = b[i]
            if (ba.translatedText != bb.translatedText) return false
            if (ba.isFurigana != bb.isFurigana) return false
            if (ba.sourceText != bb.sourceText) return false
            if (ba.orientation != bb.orientation) return false
            if (ba.alignment != bb.alignment) return false
            // An upright↔slanted mode flip re-routes the render path, so it
            // always defeats the fuzzy fast path. Between two slanted reads,
            // motion is judged on the DRAWN footprint: corner displacement
            // within the bounds tolerance is the same jitter the AABB check
            // absorbs (angle jitter on a stable banner barely moves corners),
            // while a real rotation or dims change moves a long chip's
            // corners past it and rebuilds. Subsumes any separate angle or
            // oriented-dims comparison.
            if ((ba.angleDeg != 0f) != (bb.angleDeg != 0f)) return false
            if (ba.angleDeg != 0f && com.playtranslate.ocr.core.DeskewGeometry.footprintCornerDelta(
                    ba.bounds, ba.angleDeg, ba.orientedWidth, ba.orientedHeight,
                    bb.bounds, bb.angleDeg, bb.orientedWidth, bb.orientedHeight,
                ) > tolerance
            ) return false
            val ra = ba.bounds; val rb = bb.bounds
            if (Math.abs(ra.left - rb.left) > tolerance ||
                Math.abs(ra.top - rb.top) > tolerance ||
                Math.abs(ra.right - rb.right) > tolerance ||
                Math.abs(ra.bottom - rb.bottom) > tolerance) return false
        }
        return true
    }
}
