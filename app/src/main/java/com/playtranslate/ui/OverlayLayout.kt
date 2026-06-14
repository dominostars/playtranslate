package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextOrientation

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
 * - [ROTATE] — the 90°-rotated fallback, reached only when grow is off (or the script can't
 *   stack) and the box is too narrow for a horizontal line. Stays in the original footprint.
 */
enum class RenderMode {
    LEGACY_HORIZONTAL,
    HORIZONTAL_IN_PLACE,
    STACK_UPRIGHT,
    GROW_HORIZONTAL,
    ROTATE,
}

/** A box's resolved on-screen rect paired with its chosen [RenderMode]. Index-aligned with
 *  the input `boxes` list — the index alignment is load-bearing for pinhole change-detection
 *  (`PinholeOverlayMode` walks `getChildScreenRects()` positionally against its box list). */
data class ResolvedBox(val rect: RectF, val mode: RenderMode)

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

    /** True when [mode] occupies a horizontal footprint (resolves vertical overlaps with
     *  the other horizontal boxes). */
    private fun isHorizontalFootprint(mode: RenderMode) =
        mode == RenderMode.LEGACY_HORIZONTAL || mode == RenderMode.HORIZONTAL_IN_PLACE

    /** True when [mode] occupies the original (tall, narrow) vertical footprint (resolves
     *  horizontal overlaps in right-to-left reading order). */
    private fun isVerticalFootprint(mode: RenderMode) =
        mode == RenderMode.STACK_UPRIGHT || mode == RenderMode.ROTATE

    /**
     * Resolve every box's final on-screen rect + [RenderMode]: map OCR bounds → screen, pad
     * non-furigana boxes, pick a render mode, then resolve overlaps in three footprint-keyed
     * passes (horizontal shrink, vertical-footprint shrink, grow-into-gaps).
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

        // Pick a render mode per box from its padded rect.
        val modes = boxes.indices.map { i ->
            renderModeFor(boxes[i], finalRects[i], density, targetIsVerticalScript, targetStackable, growEnabled)
        }

        // Pass 1 — horizontal-footprint boxes (LEGACY_HORIZONTAL + HORIZONTAL_IN_PLACE):
        // resolve vertical overlaps by splitting at the midline.
        val hBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && isHorizontalFootprint(modes[it])
        }.sortedBy { finalRects[it].top }
        for (a in hBoxIndices.indices) {
            for (b in a + 1 until hBoxIndices.size) {
                val ri = finalRects[hBoxIndices[a]]
                val rj = finalRects[hBoxIndices[b]]
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        // Pass 2 — vertical-footprint boxes (STACK_UPRIGHT + ROTATE): resolve horizontal
        // overlaps (adjacent columns) in right-to-left reading order.
        val vBoxIndices = boxes.indices.filter {
            !boxes[it].isFurigana && isVerticalFootprint(modes[it])
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

        // Pass 3 — GROW_HORIZONTAL: grow each box's width over its source toward min-width,
        // clamped so the background never overlaps a neighbour's reserved bounds.
        growIntoGaps(boxes, finalRects, modes, dW)

        return boxes.indices.map { ResolvedBox(finalRects[it], modes[it]) }
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
        if (box.isFurigana || box.orientation != TextOrientation.VERTICAL) {
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
     * extending outward from its source-covering rect into the free space on each side and
     * clamping at the nearest neighbour (any non-furigana box, including horizontal ones) that
     * vertically overlaps it — so grown backgrounds stay **disjoint** and every box keeps
     * covering its source. Mutates [rects] in place; processed right-to-left so contention is
     * deterministic (an earlier box's grown rect is a fixed obstacle for later ones).
     */
    private fun growIntoGaps(
        boxes: List<TextBox>,
        rects: List<RectF>,
        modes: List<RenderMode>,
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
                if (o.right <= r.left) leftLimit = maxOf(leftLimit, o.right)
                else if (o.left >= r.right) rightLimit = minOf(rightLimit, o.left)
            }
            val leftRoom = (r.left - leftLimit).coerceAtLeast(0f)
            val rightRoom = (rightLimit - r.right).coerceAtLeast(0f)

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
            val ra = ba.bounds; val rb = bb.bounds
            if (Math.abs(ra.left - rb.left) > tolerance ||
                Math.abs(ra.top - rb.top) > tolerance ||
                Math.abs(ra.right - rb.right) > tolerance ||
                Math.abs(ra.bottom - rb.bottom) > tolerance) return false
        }
        return true
    }
}
