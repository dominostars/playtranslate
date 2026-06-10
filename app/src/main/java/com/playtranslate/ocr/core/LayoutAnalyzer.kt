package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation

/**
 * Shared, vendor-neutral layout logic for OCR.
 *
 * This object owns the **pure-geometry grouping kernel** — the carefully-tuned
 * predicates that decide whether two boxes belong to the same text block, plus
 * the one-pass clustering algorithm built on them. It is engine-agnostic: it
 * operates on `android.graphics.Rect` + injected `alignLefts` + orientation +
 * mode, with **no text content, no language, and no ML Kit / MNN / OpenCV
 * dependency**. Text-derived inputs (the hanging-punctuation align hint, the
 * source-script noise filter) are computed by the engine adapters and either
 * injected here as `alignLefts` or applied before/after these calls.
 *
 * This is what makes grouping shareable across all call sites:
 *  1. post-recognition layout for end-to-end engines (ML Kit lines → paragraphs),
 *  2. between detection and recognition (cluster detector boxes into bubbles —
 *     pure geometry, `alignLefts` all null, no text filter), and
 *  3. cross-frame region matching for overlay caching ([Classification]).
 *
 * The kernel was moved here verbatim from `OcrManager.Companion`; its behavior
 * is pinned by `OcrGroupingTest` (synthetic-Rect cases).
 */
object LayoutAnalyzer {

    /**
     * Structured outcome of [groupDecision] for debug logging. [reason] is
     * a short human-readable summary that names the check that fired
     * (when [Grouped]) or every check that failed with its numeric margin
     * (when [NotGrouped]) — so `adb logcat -s DetectionLog` shows exactly
     * which threshold is keeping rows apart.
     */
    sealed class GroupDecision {
        abstract val reason: String
        data class Grouped(override val reason: String) : GroupDecision()
        data class NotGrouped(override val reason: String) : GroupDecision()
    }

    /**
     * Which question is the caller asking? Two semantically distinct uses
     * of "do these rects belong together," each tuned for its own question.
     *
     * [SAME_PASS_LAYOUT] — clustering rects produced by a single OCR pass
     * into paragraphs. ML Kit per-line detection has already separated
     * these as distinct lines, so any pixel intersection is incidental
     * (ascender/descender slivers, glyph-box padding) and is NOT evidence
     * of grouping. Decisions rest on inline (same-line gap) and block
     * (next-line + alignment + height-match) checks alone, with the
     * strict block-axis thresholds (`blockGapMultiplier`=0.8,
     * `sizeRatioCap`=0.30) that keep typographically distinct elements
     * (headings vs body, captions vs body) from collapsing into one
     * paragraph.
     *
     * [CROSS_FRAME_SAME_REGION] — matching a fresh OCR rect against a rect
     * from a previous frame's overlay state, to decide if they represent
     * the same on-screen region. Stable regions may shift a few pixels or
     * be partially occluded between frames, so substantial rect overlap
     * is evidence of same-region identity even when heights diverge —
     * see [hasSubstantialOverlap]. Sliver-only overlaps fall through to
     * the layout checks, which run with looser thresholds
     * (`blockGapMultiplier`=0.9, `sizeRatioCap`=0.50) — body paragraphs
     * whose wraps differ in glyph-tight bbox height across cycles
     * (hiragana-dominant trailing line vs kanji-dominant body line,
     * digit-only short last line, etc.) shouldn't get split back apart
     * across cycles when they grouped fine within a frame. The 0.50
     * height cap still cleanly rejects heading-scale (≥1.5×) elements,
     * which is the real "different element" guard the gate is here for.
     */
    enum class GroupingMode { SAME_PASS_LAYOUT, CROSS_FRAME_SAME_REGION }

    /**
     * Minimum overlap-area / min(area_a, area_b) ratio for the intersect
     * short-circuit to fire in [GroupingMode.CROSS_FRAME_SAME_REGION].
     * A sliver overlap between two stacked-but-distinct lines (e.g. a 3-
     * pixel ascender bleed) sits well below this; a partially-occluded
     * re-OCR of the same region sits well above (typically near 1.0).
     *
     * TODO: tune against the OCR golden-set fixtures once we add inter-
     * frame partial-occlusion cases.
     */
    private const val CROSS_FRAME_OVERLAP_RATIO = 0.30

    /**
     * True iff [a] and [b] overlap by at least [CROSS_FRAME_OVERLAP_RATIO]
     * of the smaller rect's area. Used only by the
     * [GroupingMode.CROSS_FRAME_SAME_REGION] path of [wouldGroup] /
     * [groupDecision] — see [GroupingMode] kdoc for why same-pass callers
     * must NOT use this check.
     */
    private fun hasSubstantialOverlap(a: Rect, b: Rect): Boolean {
        if (!Rect.intersects(a, b)) return false
        val ix = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val iy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (ix <= 0 || iy <= 0) return false
        val overlap = ix.toLong() * iy.toLong()
        val areaA = a.width().toLong() * a.height().toLong()
        val areaB = b.width().toLong() * b.height().toLong()
        val minArea = minOf(areaA, areaB)
        if (minArea <= 0) return false
        return overlap.toDouble() / minArea >= CROSS_FRAME_OVERLAP_RATIO
    }

    /**
     * Block-axis gap multiplier used by the block check in [wouldGroup] /
     * [groupDecision]. Cross-frame is looser (0.9× vs 0.8×) because it's
     * asking "is this fresh OCR in the same on-screen region as a prior-
     * frame overlay?" — body paragraphs with generous leading and short
     * trailing lines sit right at the 0.8× cliff and shouldn't get split
     * back apart across cycles when they grouped fine within a frame.
     */
    private fun blockGapMultiplier(mode: GroupingMode): Float =
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION) 0.9f else 0.8f

    /**
     * Cap on `(hi - lo) / lo` for the inline-axis size ratio (height for
     * horizontal text, width for vertical). The compared values are
     * per-line (per-column for vertical) — see [wouldGroup]'s
     * `aLineCount` / `bLineCount` — so a multi-line group's stacked
     * extent doesn't trip this gate; only an actual per-line scale
     * difference does.
     *
     * Cross-frame uses 0.50 vs 0.30 same-pass to absorb ML Kit's
     * glyph-tight bbox variance — hiragana-dominant body wraps can be
     * 30–40% shorter than kanji-dominant lines of the same paragraph.
     * The 0.50 ceiling still cleanly rejects heading-scale (≥1.5×)
     * elements, which is the real "different element" guard the gate
     * is here for.
     */
    private fun sizeRatioCap(mode: GroupingMode): Double =
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION) 0.50 else 0.30

    /**
     * Block-grouping size guard. When the earlier line (in reading order)
     * is strictly less than one-third the later line's extent, refuse to
     * group. Catches speaker-name + dialogue, poem-stanza first lines,
     * and short headings above body text without affecting same-paragraph
     * wraps. The threshold was loosened from one-half to one-third so
     * subtle short-above-long pairings (e.g. a ~40% intro line above a
     * long body) cluster instead of splitting — only clearly-short
     * labels still trip the guard.
     *
     * Asymmetric: long-above-short (a paragraph closing with a short tail)
     * is unaffected. Only fires when the rects are cleanly separated on
     * the reading axis; any overlap defers to the existing geometric
     * checks (inline/block paths in [wouldGroup]).
     *
     * Reading order per orientation:
     * - [TextOrientation.HORIZONTAL] — earlier = strictly above, axis = width.
     * - [TextOrientation.VERTICAL]   — earlier = strictly to the right of,
     *   axis = height (vertical text reads right-to-left, top-to-bottom).
     *
     * Returns the reason string when blocked, null otherwise. [wouldGroup]
     * discards the string; [groupDecision] surfaces it in the log so the
     * two predicates stay in numerical sync.
     */
    internal fun shortAboveLongBlock(
        a: Rect,
        b: Rect,
        orientation: TextOrientation,
    ): String? {
        return when (orientation) {
            TextOrientation.VERTICAL -> {
                val (earlier, later) = when {
                    a.left >= b.right -> a to b
                    b.left >= a.right -> b to a
                    else -> return null
                }
                val eh = earlier.height()
                val lh = later.height()
                if (eh > 0 && eh * 3 < lh)
                    "size-block (vertical: earlier h=$eh < ⅓× later h=$lh)"
                else null
            }
            else -> {
                val (earlier, later) = when {
                    a.bottom <= b.top -> a to b
                    b.bottom <= a.top -> b to a
                    else -> return null
                }
                val ew = earlier.width()
                val lw = later.width()
                if (ew > 0 && ew * 3 < lw)
                    "size-block (horizontal: earlier w=$ew < ⅓× later w=$lw)"
                else null
            }
        }
    }

    /**
     * Would two rects be grouped as the same text block?
     * Up to three checks: intersection (cross-frame only), inline (same
     * line/column), block (next line/column in paragraph with alignment).
     *
     * The [mode] selects how the intersection signal is interpreted — see
     * [GroupingMode] for the full semantic split. Briefly: same-pass
     * callers (paragraph clustering) ignore intersection because ML Kit
     * already separated the lines; cross-frame callers (region identity)
     * use intersection — but only when overlap area is substantial — as
     * evidence the two rects track the same on-screen region.
     *
     * When [orientation] is [TextOrientation.VERTICAL], all axis logic is
     * swapped: "inline" checks for vertical continuation in the same column,
     * and "block" checks for horizontal continuation to the next column
     * (right-to-left).
     *
     * [aAlignLeft] / [bAlignLeft] override only the leftAligned sub-check
     * (block path, horizontal orientation). Callers pass these to
     * compensate for hanging-punctuation outdent — see effectiveAlignLeft.
     * When null (default), the rect's own [Rect.left] is used, preserving
     * legacy behavior for all bare-rect callers (e.g. [Classification]).
     *
     * [aLineCount] / [bLineCount] tell the predicate how many text lines
     * each rect spans across the wrap axis (line count for horizontal,
     * column count for vertical). When passed, the reference dimension
     * (refH/refW) is computed as the rect's per-line height (or per-column
     * width) instead of its raw extent — so a 2-line group's height is
     * normalized to a single-line equivalent before gap/align/ratio
     * thresholds apply. Default 1 preserves legacy behavior for callers
     * comparing single-line ML Kit lines or [groupBoxesOnePass]'s
     * last-line-only `groupRect`. Cross-frame callers in [Classification]
     * pass the cached/fresh group's line counts so a 1-line cached box
     * and a 2-line fresh OCR group don't fail the size-ratio cap on
     * stacked-line height alone.
     *
     * Hot path: called from [Classification] for every live-overlay /
     * pinhole-detection pair, so the boolean version intentionally
     * skips the reason-string allocation that [groupDecision] does. The
     * two implementations must stay in numerical sync — any threshold
     * change here goes into [groupDecisionHorizontal]/[groupDecisionVertical]
     * too.
     */
    fun wouldGroup(
        a: Rect,
        b: Rect,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        aAlignLeft: Int? = null,
        bAlignLeft: Int? = null,
        mode: GroupingMode = GroupingMode.SAME_PASS_LAYOUT,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
    ): Boolean {
        if (shortAboveLongBlock(a, b, orientation) != null) return false
        if (orientation == TextOrientation.VERTICAL) {
            return wouldGroupVertical(a, b, mode, aLineCount, bLineCount)
        }
        val aLn = aLineCount.coerceAtLeast(1)
        val bLn = bLineCount.coerceAtLeast(1)
        // Coerce normalized heights to at least 1 when the input rect is
        // positive — integer division can otherwise collapse a positive
        // multi-line rect's per-line height to 0, which would trip the
        // `lo <= 0 → compatible` branch below and silently bypass the
        // size-ratio guard.
        val aH = if (a.height() <= 0) 0 else maxOf(a.height() / aLn, 1)
        val bH = if (b.height() <= 0) 0 else maxOf(b.height() / bLn, 1)
        val refH = maxOf(aH, bH)
        if (refH <= 0) return false
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION && hasSubstantialOverlap(a, b)) return true

        val aCenterY = (a.top + a.bottom) / 2
        val bCenterY = (b.top + b.bottom) / 2
        if (bCenterY in a.top..a.bottom || aCenterY in b.top..b.bottom) {
            val dx = if (a.right <= b.left) b.left - a.right
                     else if (b.right <= a.left) a.left - b.right
                     else 0
            if (dx < (refH * 1.5f).toInt()) {
                // Heights must be similar — inline is for same-line
                // text continuation, not for a small fresh fragment
                // whose centerY happens to fall inside a tall
                // multi-line cached box's full y range. Without this,
                // any tiny OCR fragment adjacent to a multi-line
                // overlay inline-matches it on dx alone and stales
                // the legitimate cached translation. Uses the same
                // size-ratio cap the block check below already
                // applies, so a true same-line continuation (same
                // font, same height) still matches.
                val lo = minOf(aH, bH)
                val hi = maxOf(aH, bH)
                if (lo <= 0 || (hi - lo).toDouble() / lo <= sizeRatioCap(mode)) return true
            }
        }

        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        if (dy < (refH * blockGapMultiplier(mode)).toInt()) {
            val alignTolerance = (refH * 0.5f).toInt()
            val aLeft = aAlignLeft ?: a.left
            val bLeft = bAlignLeft ?: b.left
            val leftAligned = kotlin.math.abs(aLeft - bLeft) <= alignTolerance
            val centerAligned = kotlin.math.abs(a.centerX() - b.centerX()) <= alignTolerance
            if (leftAligned || centerAligned) {
                val lo = minOf(aH, bH)
                val hi = maxOf(aH, bH)
                if (lo <= 0 || (hi - lo).toDouble() / lo <= sizeRatioCap(mode)) return true
            }
        }
        return false
    }

    private fun wouldGroupVertical(
        a: Rect,
        b: Rect,
        mode: GroupingMode,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
    ): Boolean {
        val aLn = aLineCount.coerceAtLeast(1)
        val bLn = bLineCount.coerceAtLeast(1)
        // See [wouldGroup] horizontal path — same coerce-to-1 invariant
        // so a positive multi-column rect can't normalize to width 0
        // and bypass the size-ratio guard.
        val aW = if (a.width() <= 0) 0 else maxOf(a.width() / aLn, 1)
        val bW = if (b.width() <= 0) 0 else maxOf(b.width() / bLn, 1)
        val refW = maxOf(aW, bW)
        if (refW <= 0) return false
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION && hasSubstantialOverlap(a, b)) return true

        val aCenterX = (a.left + a.right) / 2
        val bCenterX = (b.left + b.right) / 2
        if (bCenterX in a.left..a.right || aCenterX in b.left..b.right) {
            val dy = if (a.bottom <= b.top) b.top - a.bottom
                     else if (b.bottom <= a.top) a.top - b.bottom
                     else 0
            if (dy < (refW * 1.5f).toInt()) {
                // Widths must be similar (vertical's height-ratio analogue) —
                // see horizontal wouldGroup for rationale. Without this, a
                // narrow fresh column fragment whose centerX falls inside a
                // wide multi-column cached box's x range inline-matches on
                // dy alone and stales the cached translation.
                val lo = minOf(aW, bW)
                val hi = maxOf(aW, bW)
                if (lo <= 0 || (hi - lo).toDouble() / lo <= sizeRatioCap(mode)) return true
            }
        }

        val dx = if (a.left <= b.right && b.right <= a.right) 0
                 else if (b.left <= a.right && a.right <= b.right) 0
                 else if (a.right <= b.left) b.left - a.right
                 else a.left - b.right
        if (dx < (refW * blockGapMultiplier(mode)).toInt()) {
            val alignTolerance = (refW * 0.5f).toInt()
            val topAligned = kotlin.math.abs(a.top - b.top) <= alignTolerance
            val centerAligned = kotlin.math.abs(a.centerY() - b.centerY()) <= alignTolerance
            if (topAligned || centerAligned) {
                val lo = minOf(aW, bW)
                val hi = maxOf(aW, bW)
                if (lo <= 0 || (hi - lo).toDouble() / lo <= sizeRatioCap(mode)) return true
            }
        }
        return false
    }

    /** Explainer twin of [wouldGroup]: same predicate, but allocates a
     *  [GroupDecision] with a human-readable reason. Used only by
     *  [groupBoxesOnePass] when the debug-log toggle is on, so the
     *  reason-string cost stays out of hot paths.
     *
     *  [aAlignLeft] / [bAlignLeft] mirror [wouldGroup]'s overrides for
     *  hanging-punctuation compensation. [mode] selects the intersection
     *  semantics — see [GroupingMode]. [aLineCount] / [bLineCount] mirror
     *  [wouldGroup]'s per-line normalization — default 1 keeps legacy
     *  bare-rect behavior. */
    fun groupDecision(
        a: Rect,
        b: Rect,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        aAlignLeft: Int? = null,
        bAlignLeft: Int? = null,
        mode: GroupingMode = GroupingMode.SAME_PASS_LAYOUT,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
    ): GroupDecision {
        val sizeBlock = shortAboveLongBlock(a, b, orientation)
        if (sizeBlock != null) return GroupDecision.NotGrouped(sizeBlock)
        return if (orientation == TextOrientation.VERTICAL)
            groupDecisionVertical(a, b, mode, aLineCount, bLineCount)
        else
            groupDecisionHorizontal(a, b, aAlignLeft, bAlignLeft, mode, aLineCount, bLineCount)
    }

    private fun groupDecisionHorizontal(
        a: Rect,
        b: Rect,
        aAlignLeft: Int?,
        bAlignLeft: Int?,
        mode: GroupingMode,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
    ): GroupDecision {
        val aLn = aLineCount.coerceAtLeast(1)
        val bLn = bLineCount.coerceAtLeast(1)
        // Mirror wouldGroup's coerce-to-1 invariant so the debug log
        // path agrees on size-guard behavior for positive rects whose
        // integer-divided per-line dim would otherwise be 0.
        val aH = if (a.height() <= 0) 0 else maxOf(a.height() / aLn, 1)
        val bH = if (b.height() <= 0) 0 else maxOf(b.height() / bLn, 1)
        val refH = maxOf(aH, bH)
        if (refH <= 0) return GroupDecision.NotGrouped("refH=0 (degenerate rect)")

        // 1. Intersection: rects substantially overlap. Cross-frame only
        //    — same-pass rects from ML Kit are known-distinct, so sliver
        //    overlaps there are noise, not evidence. See [GroupingMode].
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION && hasSubstantialOverlap(a, b)) {
            return GroupDecision.Grouped("intersect (cross-frame, substantial overlap)")
        }

        // 2. Inline: horizontal continuation on the same line
        val aCenterY = (a.top + a.bottom) / 2
        val bCenterY = (b.top + b.bottom) / 2
        val sameLine = bCenterY in a.top..a.bottom || aCenterY in b.top..b.bottom
        val dx = if (a.right <= b.left) b.left - a.right
                 else if (b.right <= a.left) a.left - b.right
                 else 0
        val inlineGapThreshold = (refH * 1.5f).toInt()
        val lnStr = if (aLn > 1 || bLn > 1) " ln=$aLn/$bLn" else ""
        val inlineLo = minOf(aH, bH)
        val inlineHi = maxOf(aH, bH)
        val inlineHeightOk = inlineLo <= 0 || (inlineHi - inlineLo).toDouble() / inlineLo <= sizeRatioCap(mode)
        if (sameLine && dx < inlineGapThreshold && inlineHeightOk) {
            return GroupDecision.Grouped("inline (dx=$dx < ${inlineGapThreshold}px, refH=$refH$lnStr)")
        }

        // 3. Block: vertical continuation (next line in same paragraph)
        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        val vgapThreshold = (refH * blockGapMultiplier(mode)).toInt()
        val heightCap = sizeRatioCap(mode)
        val alignTolerance = (refH * 0.5f).toInt()
        val aLeft = aAlignLeft ?: a.left
        val bLeft = bAlignLeft ?: b.left
        val rawLeftDiff = kotlin.math.abs(a.left - b.left)
        val leftDiff = kotlin.math.abs(aLeft - bLeft)
        val shifted = aLeft != a.left || bLeft != b.left
        val leftStr = if (shifted) "leftΔ=$leftDiff(adj,raw=$rawLeftDiff)" else "leftΔ=$leftDiff"
        val centerDiff = kotlin.math.abs(a.centerX() - b.centerX())
        val lo = minOf(aH, bH)
        val hi = maxOf(aH, bH)
        // Mirror wouldGroup: degenerate (lo<=0) treated as compatible
        // — without this the debug path would diverge for zero-height
        // line boxes and the log would explain a verdict the predicate
        // never made.
        val heightRatio = if (lo > 0) (hi - lo).toDouble() / lo else 0.0

        val vgapOk = dy < vgapThreshold
        val leftAligned = leftDiff <= alignTolerance
        val centerAligned = centerDiff <= alignTolerance
        val alignOk = leftAligned || centerAligned
        val heightOk = lo <= 0 || heightRatio <= heightCap

        if (vgapOk && alignOk && heightOk) {
            val which = when {
                leftAligned && centerAligned -> "left+center"
                leftAligned -> "left"
                else -> "center"
            }
            val hRatioStr = if (lo > 0) "%.2f".format(heightRatio) else "n/a"
            return GroupDecision.Grouped(
                "block (dy=$dy<${vgapThreshold}px, align=$which $leftStr centerΔ=$centerDiff tol=${alignTolerance}px, hRatio=$hRatioStr, refH=$refH$lnStr)"
            )
        }

        val fails = buildList {
            if (!vgapOk) add("vgap dy=$dy ≥ ${vgapThreshold}px")
            if (!alignOk) add("align: $leftStr centerΔ=$centerDiff > tol=${alignTolerance}px")
            if (!heightOk) add("height: lo=$lo hi=$hi ratio=${"%.2f".format(heightRatio)} > ${"%.2f".format(heightCap)}")
            if (sameLine && dx >= inlineGapThreshold) add("inline gap dx=$dx ≥ ${inlineGapThreshold}px")
        }
        return GroupDecision.NotGrouped(
            "block " + fails.joinToString("; ").ifEmpty { "no sub-check matched" } + " (refH=$refH$lnStr)"
        )
    }

    /**
     * Vertical-text variant of [groupDecisionHorizontal]. Axes are swapped:
     * - "Inline" = vertical continuation in the same column (same X-band)
     * - "Block"  = horizontal continuation to the next column (top-aligned
     *   or center-Y-aligned, right-to-left flow)
     * - Reference dimension is width (column thickness) not height.
     */
    private fun groupDecisionVertical(
        a: Rect,
        b: Rect,
        mode: GroupingMode,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
    ): GroupDecision {
        val aLn = aLineCount.coerceAtLeast(1)
        val bLn = bLineCount.coerceAtLeast(1)
        // Mirror wouldGroupVertical's coerce-to-1 invariant — see
        // groupDecisionHorizontal for rationale.
        val aW = if (a.width() <= 0) 0 else maxOf(a.width() / aLn, 1)
        val bW = if (b.width() <= 0) 0 else maxOf(b.width() / bLn, 1)
        val refW = maxOf(aW, bW)
        if (refW <= 0) return GroupDecision.NotGrouped("refW=0 (degenerate rect)")

        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION && hasSubstantialOverlap(a, b)) {
            return GroupDecision.Grouped("intersect (cross-frame, substantial overlap)")
        }

        val aCenterX = (a.left + a.right) / 2
        val bCenterX = (b.left + b.right) / 2
        val sameColumn = bCenterX in a.left..a.right || aCenterX in b.left..b.right
        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        val inlineGapThreshold = (refW * 1.5f).toInt()
        val lnStr = if (aLn > 1 || bLn > 1) " ln=$aLn/$bLn" else ""
        val inlineLo = minOf(aW, bW)
        val inlineHi = maxOf(aW, bW)
        val inlineWidthOk = inlineLo <= 0 || (inlineHi - inlineLo).toDouble() / inlineLo <= sizeRatioCap(mode)
        if (sameColumn && dy < inlineGapThreshold && inlineWidthOk) {
            return GroupDecision.Grouped("inline (dy=$dy < ${inlineGapThreshold}px, refW=$refW$lnStr)")
        }

        val dx = if (a.left <= b.right && b.right <= a.right) 0
                 else if (b.left <= a.right && a.right <= b.right) 0
                 else if (a.right <= b.left) b.left - a.right
                 else a.left - b.right
        val hgapThreshold = (refW * blockGapMultiplier(mode)).toInt()
        val widthCap = sizeRatioCap(mode)
        val alignTolerance = (refW * 0.5f).toInt()
        val topDiff = kotlin.math.abs(a.top - b.top)
        val centerDiff = kotlin.math.abs(a.centerY() - b.centerY())
        val lo = minOf(aW, bW)
        val hi = maxOf(aW, bW)
        // Mirror wouldGroupVertical's degenerate-rect handling (see
        // groupDecisionHorizontal for the rationale).
        val widthRatio = if (lo > 0) (hi - lo).toDouble() / lo else 0.0

        val hgapOk = dx < hgapThreshold
        val topAligned = topDiff <= alignTolerance
        val centerAligned = centerDiff <= alignTolerance
        val alignOk = topAligned || centerAligned
        val widthOk = lo <= 0 || widthRatio <= widthCap

        if (hgapOk && alignOk && widthOk) {
            val which = when {
                topAligned && centerAligned -> "top+center"
                topAligned -> "top"
                else -> "center"
            }
            val wRatioStr = if (lo > 0) "%.2f".format(widthRatio) else "n/a"
            return GroupDecision.Grouped(
                "block (dx=$dx<${hgapThreshold}px, align=$which topΔ=$topDiff centerΔ=$centerDiff tol=${alignTolerance}px, wRatio=$wRatioStr, refW=$refW$lnStr)"
            )
        }

        val fails = buildList {
            if (!hgapOk) add("hgap dx=$dx ≥ ${hgapThreshold}px")
            if (!alignOk) add("align: topΔ=$topDiff centerΔ=$centerDiff > tol=${alignTolerance}px")
            if (!widthOk) add("width: lo=$lo hi=$hi ratio=${"%.2f".format(widthRatio)} > ${"%.2f".format(widthCap)}")
            if (sameColumn && dy >= inlineGapThreshold) add("inline gap dy=$dy ≥ ${inlineGapThreshold}px")
        }
        return GroupDecision.NotGrouped(
            "block " + fails.joinToString("; ").ifEmpty { "no sub-check matched" } + " (refW=$refW$lnStr)"
        )
    }

    internal fun rectStr(r: Rect): String =
        "[L=${r.left} T=${r.top} R=${r.right} B=${r.bottom}]"

    /**
     * Index-level grouping pass. Pure function over rectangles + per-line
     * effective align-lefts, factored out of `groupLinesOnePass` so unit
     * tests can drive the algorithm without fabricating ML Kit objects.
     *
     * Walks groups most-recent-first and joins the candidate into the
     * first group that passes [wouldGroup]. Checking every existing group
     * (not just the latest) reconnects body lines when a foreign-column
     * line (e.g. right-column sidebar entry) interleaves between two
     * body lines in top-Y sort order and breaks the simple "last group
     * is always the right candidate" assumption.
     *
     * - [boxes] : line bounding boxes, in sort order (top-to-bottom for
     *   horizontal, right-to-left for vertical).
     * - [alignLefts] : per-line effective left edge, with hanging-
     *   punctuation outdent compensated (see effectiveAlignLeft).
     *   Pass `null` per entry to skip compensation; must be the same
     *   length as [boxes].
     * - [texts] : optional per-line text, only used to populate the
     *   debug-log snippets. Pass `null` when logging is off.
     *
     * Returns a list of groups, each group being the indices into
     * [boxes] that ended up together, in encounter order.
     */
    internal fun groupBoxesOnePass(
        boxes: List<Rect>,
        alignLefts: List<Int?>,
        orientation: TextOrientation,
        logDecisions: Boolean = false,
        texts: List<String>? = null,
    ): List<List<Int>> {
        require(boxes.size == alignLefts.size) {
            "boxes and alignLefts must match length"
        }
        require(texts == null || texts.size == boxes.size) {
            "texts must match boxes length when provided"
        }
        if (boxes.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Int>>()
        val orientChar = orientation.name[0]
        for (idx in boxes.indices) {
            val lineBox = boxes[idx]
            if (groups.isEmpty()) {
                if (logDecisions) {
                    val snippet = (texts?.get(idx) ?: "").take(24).replace('\n', ' ')
                    android.util.Log.d(
                        "DetectionLog",
                        "[group:$orientChar] FIRST cand=${rectStr(lineBox)} \"$snippet\""
                    )
                }
                groups += mutableListOf(idx)
                continue
            }

            val candidateAlignLeft =
                if (orientation == TextOrientation.VERTICAL) null else alignLefts[idx]
            var merged = false
            for (gi in groups.indices.reversed()) {
                val candidateGroup = groups[gi]
                val prevBox = boxes[candidateGroup.last()]
                // Use the *union* of all prior line edges across the
                // wrap axis (left+right for horizontal, top+bottom for
                // vertical) so the group's center stays on the paragraph
                // axis as line widths vary. Mixing a union edge with the
                // last line's opposite edge pulled groupRect.centerX/Y
                // off the real axis and broke center-aligned wrapped text.
                val groupRect: Rect
                val groupAlignLeft: Int?
                if (orientation == TextOrientation.VERTICAL) {
                    val groupTop = candidateGroup.minOf { boxes[it].top }
                    val groupBottom = candidateGroup.maxOf { boxes[it].bottom }
                    groupRect = Rect(prevBox.left, groupTop, prevBox.right, groupBottom)
                    groupAlignLeft = null
                } else {
                    val groupLeft = candidateGroup.minOf { boxes[it].left }
                    val groupRight = candidateGroup.maxOf { boxes[it].right }
                    groupRect = Rect(groupLeft, prevBox.top, groupRight, prevBox.bottom)
                    // Per-line effective lefts compensate for hanging
                    // punctuation outdent (e.g. 「, ·). Used only by the
                    // leftAligned sub-check; centerX still uses
                    // groupRect's actual edges so center-aligned wrapped
                    // text is unaffected.
                    groupAlignLeft = candidateGroup.mapNotNull { alignLefts[it] }.minOrNull()
                }
                // wouldGroup is the canonical predicate — used
                // unconditionally so the debug-log toggle is purely
                // observational. groupDecision is called only to
                // produce a reason string for the log; if it ever
                // diverges from wouldGroup the log wording becomes
                // misleading but grouping behavior stays consistent.
                val groupMerged = wouldGroup(
                    groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft
                )
                if (logDecisions) {
                    val decision = groupDecision(
                        groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft
                    )
                    val prevSnippet =
                        (texts?.get(candidateGroup.last()) ?: "").take(24).replace('\n', ' ')
                    val candSnippet =
                        (texts?.get(idx) ?: "").take(24).replace('\n', ' ')
                    val verdict = if (groupMerged) "MERGE" else "SPLIT"
                    android.util.Log.d(
                        "DetectionLog",
                        "[group:$orientChar] $verdict g$gi prev=${rectStr(groupRect)} \"$prevSnippet\" cand=${rectStr(lineBox)} \"$candSnippet\" :: ${decision.reason}"
                    )
                }
                if (groupMerged) {
                    candidateGroup += idx
                    merged = true
                    break
                }
            }
            if (!merged) {
                groups += mutableListOf(idx)
            }
        }
        return groups
    }

    // ── Source-script filtering (shared; was OcrManager.isSourceLangChar) ─────

    /**
     * Returns true if [c] belongs to a script native to [sourceLang]. Used to
     * drop OCR groups containing no source-language characters (romanizations,
     * symbols, target-language UI labels).
     */
    fun isSourceLangChar(c: Char, sourceLang: String): Boolean = when (sourceLang) {
        "ja" -> c in '぀'..'ゟ' || c in '゠'..'ヿ' ||
            c in '一'..'鿿' || c in '㐀'..'䶿' || c in '･'..'ﾟ'
        "zh", "zh-TW" -> c in '一'..'鿿' || c in '㐀'..'䶿'
        "ko" -> c in '가'..'힯' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
        "ar" -> c in '؀'..'ۿ'
        "ru", "bg", "uk" -> c in 'Ѐ'..'ӿ'
        "th" -> c in '฀'..'๿'
        "hi", "mr", "ne" -> c in 'ऀ'..'ॿ'
        else -> {
            val profile = SourceLanguageProfiles.forCode(sourceLang)
            if (profile != null) profile.isScriptChar(c) else c.code > 0x007F
        }
    }

    // ── Post-recognition layout: lines → paragraphs (call site #1) ───────────

    /**
     * The agnostic post-recognition layout stage. Takes per-line
     * [RecognizedRegion]s (origin = LINE) in one coordinate space and produces
     * grouped paragraphs ([LayoutGroup]) in that SAME space — the caller
     * (OcrPipeline) normalizes to original-bitmap coords afterward.
     *
     * Faithful reproduction of the former OcrManager grouping path: partition by
     * orientation → reading-order sort (horizontal top-to-bottom, vertical
     * right-to-left) → [groupBoxesOnePass] → source-script filter → menu split →
     * orientation vote + alignment classification.
     *
     * [screenshotWidthInRegionSpace] is the full screenshot width expressed in
     * the regions' coordinate space (the caller scales it to match). 0 = unknown
     * (skip the menu split).
     */
    fun analyze(
        regions: List<RecognizedRegion>,
        sourceLang: String,
        screenshotWidthInRegionSpace: Float,
        logDecisions: Boolean = false,
    ): List<LayoutGroup> {
        if (regions.isEmpty()) return emptyList()
        val (vertical, horizontal) = regions.partition { it.orientation == TextOrientation.VERTICAL }
        val hGroups = groupRegions(
            horizontal.sortedBy { it.box.bounds.top }, TextOrientation.HORIZONTAL, logDecisions
        )
        val vGroups = groupRegions(
            vertical.sortedByDescending { it.box.bounds.right }, TextOrientation.VERTICAL, logDecisions
        )
        val rawGroups = (hGroups + vGroups).filter { group ->
            group.any { r -> r.text.any { isSourceLangChar(it, sourceLang) } }
        }
        if (rawGroups.isEmpty()) return emptyList()
        val split = if (screenshotWidthInRegionSpace > 0f) {
            splitMenuGroups(rawGroups, screenshotWidthInRegionSpace)
        } else {
            rawGroups.map { SplitGroup(it) }
        }
        // Join a group's lines with a space only for whitespace-delimited
        // languages; CJK/Thai (wordsSeparatedByWhitespace = false) get no
        // separator so the merged paragraph reads naturally AND the translator
        // receives clean source (`今日はいい天気` not `今日は いい天気`). Default to
        // a space when the profile is unknown — only languages we KNOW omit
        // inter-word spaces drop it, so every other language keeps prior behavior.
        val lineJoin =
            if (SourceLanguageProfiles.forCode(sourceLang)?.wordsSeparatedByWhitespace == false) "" else " "
        return split.mapNotNull { buildLayoutGroup(it, lineJoin) }
    }

    /** Extract boxes + align-left hints from sorted regions, run the kernel,
     *  and remap index-groups back to region lists. */
    private fun groupRegions(
        sorted: List<RecognizedRegion>,
        orientation: TextOrientation,
        logDecisions: Boolean,
    ): List<List<RecognizedRegion>> {
        if (sorted.isEmpty()) return emptyList()
        val boxes = sorted.map { it.box.bounds }
        val alignLefts: List<Int?> = if (orientation == TextOrientation.HORIZONTAL) {
            sorted.map { region -> region.lines.firstOrNull()?.let { effectiveAlignLeft(it) } ?: region.box.bounds.left }
        } else {
            List(sorted.size) { null }
        }
        val texts = if (logDecisions) sorted.map { it.text } else null
        val idxGroups = groupBoxesOnePass(boxes, alignLefts, orientation, logDecisions, texts)
        return idxGroups.map { idxs -> idxs.map { sorted[it] } }
    }

    private data class SplitGroup(
        val regions: List<RecognizedRegion>,
        val parentLeft: Int? = null,
        val parentRight: Int? = null,
    )

    /**
     * Split menu/list-like groups into individual rows (each its own group),
     * inheriting the parent's left/right so overlays align. Menu-like = 4+ rows,
     * narrow (< 1/3 screen), and edges don't cluster on BOTH sides the way
     * wrapped paragraph text does.
     */
    private fun splitMenuGroups(
        groups: List<List<RecognizedRegion>>,
        screenWidth: Float,
    ): List<SplitGroup> = groups.flatMap { group ->
        if (group.size >= 4 && isMenuLike(group, screenWidth)) {
            val boxes = group.map { it.box.bounds }
            val groupLeft = boxes.minOf { it.left }
            val groupRight = boxes.maxOf { it.right }
            group.map { SplitGroup(listOf(it), parentLeft = groupLeft, parentRight = groupRight) }
        } else {
            listOf(SplitGroup(group))
        }
    }

    private fun isMenuLike(group: List<RecognizedRegion>, screenWidth: Float): Boolean {
        val boxes = group.map { it.box.bounds }
        if (boxes.isEmpty()) return false
        val groupWidth = boxes.maxOf { it.right } - boxes.minOf { it.left }
        if (groupWidth >= screenWidth / 3f) return false
        val avgLineHeight = boxes.map { it.height() }.average().toFloat()
        val minLeft = boxes.minOf { it.left }
        val maxRight = boxes.maxOf { it.right }
        val clusterThreshold = boxes.size - 1
        val nearMinLeft = boxes.count { it.left - minLeft <= avgLineHeight }
        val nearMaxRight = boxes.count { maxRight - it.right <= avgLineHeight }
        val leftClustered = nearMinLeft >= clusterThreshold
        val rightClustered = nearMaxRight >= clusterThreshold
        if (leftClustered && rightClustered) return false
        return true
    }

    private fun buildLayoutGroup(sg: SplitGroup, lineJoin: String): LayoutGroup? {
        val regions = sg.regions
        if (regions.isEmpty()) return null
        val text = regions.joinToString(lineJoin) { it.text }.trim()
        if (text.isBlank()) return null
        val lines = regions.flatMap { it.lines }
        val rects = regions.map { it.box.bounds }
        val left = sg.parentLeft ?: rects.minOf { it.left }
        val right = sg.parentRight ?: rects.maxOf { it.right }
        val bounds = Rect(left, rects.minOf { it.top }, right, rects.maxOf { it.bottom })
        val verticalCount = regions.count { it.orientation == TextOrientation.VERTICAL }
        val orientation =
            if (verticalCount > regions.size / 2) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
        val alignment =
            if (orientation == TextOrientation.VERTICAL) TextAlignment.LEFT else classifyGroupAlignment(lines)
        return LayoutGroup(text, lines, bounds, orientation, alignment)
    }

    /**
     * Opening punctuation that visually hangs to the LEFT of body text (brackets,
     * quotes, middle dots), plus glyphs OCR commonly misreads for them. When such a
     * glyph is a line's first character its box left-edge is an outdented anchor;
     * [effectiveAlignLeft] shifts the alignment reference right past it so a body
     * line beneath `「こんにちは` aligns to where `こ` starts. Moved here (vendor-
     * neutral) from the former ML-Kit-only `OcrManager` so EVERY engine's lines get
     * the compensation, not just ML Kit's.
     */
    private val HANGING_PUNCT_LEFT = setOf(
        '「', '『', '（', '【', '〔', '《', '〈',
        '(', '[', '{',
        '・', '·',
        '“', '‘', '"', '\'',
        ',',
    )

    /**
     * Effective left edge of [line] for paragraph-alignment checks (grouping +
     * [classifyGroupAlignment]). If the line begins with a [HANGING_PUNCT_LEFT]
     * glyph, the anchor is shifted right past it — to the right edge of that
     * punctuation's own char box, matched by offset. The char tier may be sparse (a
     * missing symbol is allowed), so we must NOT take `chars.first()` blindly: if the
     * punctuation glyph has no box that would be the first *body* glyph, whose right
     * edge over-shoots past the body. When the punctuation box is absent we fall back
     * to a line-height approximation (box.left ≈ the punctuation's left edge on a
     * hanging-punct line) — also the path for char-less engines (PaddleOCR / manga-ocr).
     * Otherwise the raw box left. Computed on demand here (not precomputed per-engine)
     * so it is identical for all engines and the model carries no precompute/consume
     * split. Assumes [line] is already text-normalized (leading pipes/decoration
     * stripped by [RecognizedTextNormalizer]).
     */
    internal fun effectiveAlignLeft(line: RecognizedLine): Int {
        val box = line.box.bounds
        val firstIdx = line.text.indexOfFirst { !it.isWhitespace() }
        if (firstIdx < 0) return box.left
        if (line.text[firstIdx] !in HANGING_PUNCT_LEFT) return box.left
        val punct = line.chars.firstOrNull { it.charOffset == firstIdx }
        return punct?.box?.bounds?.right ?: (box.left + box.height())
    }

    /**
     * Classify a horizontal group's alignment (LEFT/CENTER) from each line's
     * [effectiveAlignLeft] (hanging-punct-compensated) vs its center. Left wins on
     * ties — same-width left-aligned lines satisfy both checks and we never falsely
     * center actually-left text.
     */
    internal fun classifyGroupAlignment(lines: List<RecognizedLine>): TextAlignment {
        if (lines.size < 2) return TextAlignment.LEFT
        val boxes = lines.map { it.box.bounds }
        val refH = boxes.maxOf { it.height() }
        if (refH <= 0) return TextAlignment.LEFT
        val tol = (refH * 0.5f).toInt()
        val lefts = lines.map { effectiveAlignLeft(it) }
        val leftSpread = lefts.max() - lefts.min()
        val centerXs = boxes.map { it.centerX() }
        val centerSpread = centerXs.max() - centerXs.min()
        if (leftSpread <= tol) return TextAlignment.LEFT
        if (centerSpread <= tol) return TextAlignment.CENTER
        return TextAlignment.LEFT
    }
}

/**
 * One grouped paragraph from [LayoutAnalyzer.analyze]: its combined [text], the
 * [lines] it contains, an axis-aligned [bounds] in the analyze input coordinate
 * space, and the voted [orientation] + classified [alignment]. The pipeline
 * flattens these into the final OcrResult, normalizing coords to original.
 */
data class LayoutGroup(
    val text: String,
    val lines: List<RecognizedLine>,
    val bounds: Rect,
    val orientation: TextOrientation,
    val alignment: TextAlignment,
)
