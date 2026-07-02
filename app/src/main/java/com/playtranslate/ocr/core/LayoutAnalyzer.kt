package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextDirection
import com.playtranslate.language.TextOrientation

/**
 * Shared, vendor-neutral layout logic for OCR.
 *
 * This object owns the **pure-geometry grouping kernel** — the carefully-tuned
 * predicates that decide whether two boxes belong to the same text block, plus
 * the one-pass clustering algorithm built on them, plus a group-aware evidence
 * layer (established line pitch, an ambiguous gap band with text-flow
 * corroboration, first-line indent — see [samePassBlockExtras]) applied only
 * in the same-pass grouping walk. It is engine-agnostic: it operates on
 * `android.graphics.Rect` + injected `alignLefts` / [TextFlowCue] flags +
 * orientation + mode, with **no raw text content, no language, and no ML Kit /
 * MNN / OpenCV dependency** in the decision kernel. Text-derived inputs (the
 * hanging-punctuation align hint, per-line text-flow cues, the source-script
 * noise filter) are computed by the engine adapters / [analyze] and either
 * injected here as flags or applied before/after these calls.
 *
 * This is what makes grouping shareable across all call sites:
 *  1. post-recognition layout for end-to-end engines (ML Kit lines → paragraphs),
 *  2. between detection and recognition (cluster detector boxes into bubbles —
 *     pure geometry, `alignLefts` all null, no text filter), and
 *  3. cross-frame region matching for overlay caching ([Classification]).
 *
 * The kernel was moved here verbatim from `OcrManager.Companion` and later
 * extended with the same-pass evidence layer (2026-07-01); its behavior is
 * pinned by `OcrGroupingTest` (synthetic-Rect cases).
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
     * (next-line + alignment + scale-class) checks alone. On top of this
     * pairwise predicate, the grouping walk ([groupBoxesOnePass]) layers
     * group-aware evidence — established line pitch, an ambiguous gap
     * band with text-flow corroboration, first-line indent — see
     * [samePassBlockExtras].
     *
     * [CROSS_FRAME_SAME_REGION] — matching a fresh OCR rect against a rect
     * from a previous frame's overlay state, to decide if they represent
     * the same on-screen region. Stable regions may shift a few pixels or
     * be partially occluded between frames, so substantial rect overlap
     * is evidence of same-region identity even when heights diverge —
     * see [hasSubstantialOverlap]. Sliver-only overlaps fall through to
     * the same layout checks as same-pass, but with the corroborated
     * scale cap ([SIZE_RATIO_CAP_CORROBORATED]) — absorbing cross-cycle
     * bbox variance is this mode's whole job. Bare same-pass pairs use
     * [SIZE_RATIO_CAP_BARE]; see the evidence-ladder kdoc there.
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
     * Block-axis gap multiplier for the block (next-line / next-column) check in
     * [wouldGroup] / [groupDecision]: two lines join a paragraph when the gap is under
     * [BLOCK_GAP_MULTIPLIER] × the reference line height (width for vertical text).
     *
     * Raised from a former same-pass 0.8 to 0.9 (the value cross-frame already used, so
     * both modes now share it). At 0.8 the threshold sat right at the leading of
     * generous-spaced body paragraphs — a gap of ≈0.84× the line height fragmented them
     * mid-paragraph — while a real paragraph break (≈1.9× the line height) stays well
     * clear, so distinct paragraphs still separate.
     *
     * This is the *confident-merge* ceiling, not the absolute one: the same-pass
     * grouping walk extends merging into the [BLOCK_GAP_MULTIPLIER]..[BAND_GAP_MULTIPLIER]
     * band when independent corroboration exists — see [samePassBlockExtras]. Cross-frame
     * callers get no band (they call the pairwise predicate directly).
     */
    private const val BLOCK_GAP_MULTIPLIER = 0.9f

    /**
     * Upper ceiling of the ambiguous block-gap band (× reference line height/width).
     * Gaps in [[BLOCK_GAP_MULTIPLIER], [BAND_GAP_MULTIPLIER]) merge only with
     * corroboration — an established-pitch match or a text-flow continuation cue —
     * never on gap+alignment alone (menus and stacked labels live in this range:
     * measured item spacing 0.31–0.8× *thickness* but ≥0.9× glyph-tight height is
     * common, while airy web/CJK leading at line-height 1.75–2.0 lands at 0.75–1.0×).
     * PP-StructureV3 precedent: soft 1.2× / hard 3× two-tier structure keyed on a
     * per-block line height; the value here is re-derived for glyph-tight ML Kit
     * boxes, not ported (the units differ).
     */
    private const val BAND_GAP_MULTIPLIER = 1.3f

    /**
     * Relative tolerance for matching a candidate's line pitch (center-to-center
     * block-axis distance from the group's last visual row) against the group's
     * established pitch, with [PITCH_MIN_TOLERANCE_PX] as the floor. Rendered text
     * has pixel-constant leading — the `blockGap_generousLeadingParagraph` fixture
     * measures pitch stable to ±2% while glyph-tight heights wobble ±20% — so this
     * can stay tight, absorbing only bbox-center jitter from glyph mix.
     */
    private const val PITCH_MATCH_TOLERANCE = 0.15f
    private const val PITCH_MIN_TOLERANCE_PX = 3

    /**
     * Accepted range (× reference line height) for a first-line indent between a
     * single-line group and a continuation candidate below it: JA 一字下げ prose
     * (web novels) indents the first line by exactly one full-width character
     * ≈ 1.0× line height, which fails the 0.5× start-edge tolerance and sits at
     * the exact boundary of the center check (a coin flip on bbox jitter).
     * Minimal port of Tesseract's first_indent/body_indent model — the full
     * tab-stop machinery needs ≥3-line homogeneous segments our 1–4-line blocks
     * don't have. LTR horizontal only.
     */
    private const val FIRST_LINE_INDENT_MIN = 0.7f
    private const val FIRST_LINE_INDENT_MAX = 1.3f

    /**
     * Master switch for the text-cue-seeded band merge (the `textContinues`
     * branch of the band zone in [samePassBlockExtras]). ON: the 2026-07-01
     * on-device A/B showed a previously validated fix regresses with it off,
     * which is the confirmed real-capture evidence the band was waiting for.
     * While OFF, the band's NotGrouped log reason appends "[seeding disabled]"
     * whenever the branch would have fired, so a regressed capture directly
     * implicates (or clears) this path. Pitch evidence is independent of this
     * switch: groups seeded by tight rows extend into the band on pitch alone.
     */
    internal const val BAND_TEXT_SEEDING_ENABLED = true

    /**
     * Scale-class caps on `(hi - lo) / lo` for the per-line size ratio (height
     * for horizontal text, width for vertical), graded by evidence strength —
     * the "evidence ladder" (2026-07-01, post-adversarial-review):
     *
     *  - [SIZE_RATIO_CAP_BARE] — the bare same-pass pairwise predicate
     *    ([wouldGroup] with no group-aware corroboration). The strict tier
     *    protects typographically distinct stacked elements whose case/glyph
     *    profiles compress a real ~1.4× scale difference into the measured
     *    0.30–0.50 band: Title Case item names above descriptions (both
     *    mixed-case, so the caps-heading veto is blind to them) and CJK
     *    headings at 1.3–1.5× (kanji boxes track font size honestly).
     *  - [SIZE_RATIO_CAP_CORROBORATED] — paths carrying independent evidence:
     *    cross-frame matching (absorbs ML Kit's cross-cycle glyph-tight bbox
     *    variance — kana/digit-heavy lines run 30–50% shorter than
     *    kanji/ascender lines of the same font) and the corroborated
     *    [samePassBlockExtras] paths (text-flow band merges; first-line
     *    indent, which keeps 0.50 because its target content — JA prose —
     *    carries exactly the kana variance the strict tier chokes on).
     *  - Waived entirely on an established-pitch match in
     *    [samePassBlockExtras] — the tier that carries the CONFIRMED
     *    trailing-wrap captures (ratios 0.51 and 0.55).
     *
     * The compared values are per-line (per-column for vertical) — see
     * [wouldGroup]'s `aLineCount` / `bLineCount` — so a multi-line group's
     * stacked extent doesn't trip the gate; only per-line scale does. The cap
     * is a scale-class backstop, NOT a heading detector: real all-caps titles
     * measure ratio 0.13–0.17 against their body (cap-height-only boxes) and
     * are caught by the caps-heading veto, not by any cap. History: briefly a
     * uniform 0.50 on 2026-07-01; re-graded the same day — every confirmed
     * capture was already carried by pitch/veto/0.50-catches, so the bare
     * relaxation traded unconfirmed benefit against unconfirmed heading-merge
     * regressions.
     */
    private const val SIZE_RATIO_CAP_BARE = 0.30
    private const val SIZE_RATIO_CAP_CORROBORATED = 0.50

    private fun sizeRatioCap(mode: GroupingMode): Double =
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION) SIZE_RATIO_CAP_CORROBORATED
        else SIZE_RATIO_CAP_BARE

    /**
     * Block-grouping size guard for **horizontal** text. When the earlier line
     * (strictly above) is less than one-third the later line's width, refuse to
     * group — catches speaker-name + dialogue, poem-stanza first lines, and short
     * headings above body text without affecting same-paragraph wraps. The
     * one-third threshold (loosened from one-half) means only clearly-short labels
     * trip it.
     *
     * **Vertical text is exempt** (returns null): a short column ahead of a long
     * one is almost always a sentence head flowing into the next column
     * (e.g. 今夜は → あちらの高原にて…), not a speaker label, and the guard split such
     * continuations apart. Vertical pairs defer entirely to the geometric
     * inline/block checks in [wouldGroup].
     *
     * Asymmetric (horizontal): long-above-short (a paragraph closing with a short
     * tail) is unaffected. Only fires when the rects are cleanly separated on the
     * reading axis; any overlap defers to the existing geometric checks.
     *
     * Returns the reason string when blocked, null otherwise. [wouldGroup]
     * discards the string; [groupDecision] surfaces it in the log so the two
     * predicates stay in numerical sync.
     */
    internal fun shortAboveLongBlock(
        a: Rect,
        b: Rect,
        orientation: TextOrientation,
    ): String? {
        return when (orientation) {
            // Vertical text is exempt: a short column ahead of a long one is
            // almost always a sentence head flowing into the next column
            // (e.g. 今夜は → あちらの高原にて…), not a speaker label, so the guard
            // mis-split continuations apart. Vertical pairs defer entirely to the
            // geometric inline/block checks.
            TextOrientation.VERTICAL -> null
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
        rtl: Boolean = false,
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
        if (dy < (refH * BLOCK_GAP_MULTIPLIER).toInt()) {
            val alignTolerance = (refH * 0.5f).toInt()
            // Start edge: left for LTR, right for RTL. Arabic lines are right-
            // aligned, so a short line's ragged LEFT edge must not break the
            // paragraph — compare the (consistent) right edge instead. The
            // aAlignLeft hanging-punctuation override is LTR-only; RTL uses the
            // raw right edge (a right-edge analog isn't injected yet).
            val aStart = if (rtl) a.right else (aAlignLeft ?: a.left)
            val bStart = if (rtl) b.right else (bAlignLeft ?: b.left)
            val startAligned = kotlin.math.abs(aStart - bStart) <= alignTolerance
            val centerAligned = kotlin.math.abs(a.centerX() - b.centerX()) <= alignTolerance
            if (startAligned || centerAligned) {
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
        if (dx < (refW * BLOCK_GAP_MULTIPLIER).toInt()) {
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
        rtl: Boolean = false,
    ): GroupDecision {
        val sizeBlock = shortAboveLongBlock(a, b, orientation)
        if (sizeBlock != null) return GroupDecision.NotGrouped(sizeBlock)
        return if (orientation == TextOrientation.VERTICAL)
            groupDecisionVertical(a, b, mode, aLineCount, bLineCount)
        else
            groupDecisionHorizontal(a, b, aAlignLeft, bAlignLeft, mode, aLineCount, bLineCount, rtl)
    }

    private fun groupDecisionHorizontal(
        a: Rect,
        b: Rect,
        aAlignLeft: Int?,
        bAlignLeft: Int?,
        mode: GroupingMode,
        aLineCount: Int = 1,
        bLineCount: Int = 1,
        rtl: Boolean = false,
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
        val vgapThreshold = (refH * BLOCK_GAP_MULTIPLIER).toInt()
        val heightCap = sizeRatioCap(mode)
        val alignTolerance = (refH * 0.5f).toInt()
        // Start edge: left for LTR, right for RTL (mirror wouldGroup, keep in sync).
        val aStart = if (rtl) a.right else (aAlignLeft ?: a.left)
        val bStart = if (rtl) b.right else (bAlignLeft ?: b.left)
        val rawStartDiff = kotlin.math.abs((if (rtl) a.right else a.left) - (if (rtl) b.right else b.left))
        val startDiff = kotlin.math.abs(aStart - bStart)
        val shifted = !rtl && (aStart != a.left || bStart != b.left)
        val edgeLabel = if (rtl) "rightΔ" else "leftΔ"
        val startStr = if (shifted) "$edgeLabel=$startDiff(adj,raw=$rawStartDiff)" else "$edgeLabel=$startDiff"
        val centerDiff = kotlin.math.abs(a.centerX() - b.centerX())
        val lo = minOf(aH, bH)
        val hi = maxOf(aH, bH)
        // Mirror wouldGroup: degenerate (lo<=0) treated as compatible
        // — without this the debug path would diverge for zero-height
        // line boxes and the log would explain a verdict the predicate
        // never made.
        val heightRatio = if (lo > 0) (hi - lo).toDouble() / lo else 0.0

        val vgapOk = dy < vgapThreshold
        val startAligned = startDiff <= alignTolerance
        val centerAligned = centerDiff <= alignTolerance
        val alignOk = startAligned || centerAligned
        val heightOk = lo <= 0 || heightRatio <= heightCap

        if (vgapOk && alignOk && heightOk) {
            val edgeName = if (rtl) "right" else "left"
            val which = when {
                startAligned && centerAligned -> "$edgeName+center"
                startAligned -> edgeName
                else -> "center"
            }
            val hRatioStr = if (lo > 0) "%.2f".format(heightRatio) else "n/a"
            return GroupDecision.Grouped(
                "block (dy=$dy<${vgapThreshold}px, align=$which $startStr centerΔ=$centerDiff tol=${alignTolerance}px, hRatio=$hRatioStr, refH=$refH$lnStr)"
            )
        }

        val fails = buildList {
            if (!vgapOk) add("vgap dy=$dy ≥ ${vgapThreshold}px")
            if (!alignOk) add("align: $startStr centerΔ=$centerDiff > tol=${alignTolerance}px")
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
        val hgapThreshold = (refW * BLOCK_GAP_MULTIPLIER).toInt()
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

    // ── Same-pass group-aware evidence: text-flow cues, pitch, gap band ──────

    /**
     * Per-line text-flow flags for the same-pass grouping walk, precomputed by
     * [analyze] (or a caller) and injected into [groupBoxesOnePass] exactly like
     * `alignLefts` — the decision kernel never sees raw text. Used ONLY as
     * corroboration inside the ambiguous gap band of [samePassBlockExtras],
     * never as a hard split/merge signal on their own: game/VN text is
     * hand-broken at clause boundaries, so document-tool assumptions
     * ("short line ⇒ deliberate paragraph end") do not hold here. The safe
     * direction — the one Tesseract itself uses by requiring text agreement
     * before declaring a break — is "the text looks mid-sentence, so keep
     * merging plausible."
     */
    data class TextFlowCue(
        /** Line ends with sentence-terminal punctuation (。！？.!? or a closer). */
        val endsTerminal: Boolean,
        /** Line ends with continuation punctuation (、，, ・ … em-dash, hyphen wrap). */
        val endsContinuation: Boolean,
        /** Line starts with a lowercase letter (Latin/Cyrillic continuation hint). */
        val startsLowercase: Boolean,
        /** Net count of opened-minus-closed quote/bracket pairs in this line. */
        val bracketDelta: Int,
        /** Line has at least one uppercase letter and none lowercase — an
         *  all-caps heading/label case profile (cased scripts only; always
         *  false for CJK). */
        val isAllCaps: Boolean = false,
        /** Line contains at least one lowercase letter (body-text profile). */
        val hasLowercase: Boolean = false,
    )

    private val TERMINAL_END_CHARS = setOf(
        '。', '．', '！', '？', '.', '!', '?',
        '」', '』', '）', '】', '〕', '》', '〉', '﹂', '﹄', '”', '’', ')',
        '؟', '۔',
    )

    // Deliberately excludes ー (U+30FC long-vowel mark: ですよー is utterance-final
    // in casual game dialogue) and bare absence-of-punctuation (menu items are
    // punct-less too — neutral by necessity). '…' IS included: within a band-gap
    // pair inside one region, trailing-off vs continuation both favor keeping the
    // utterance together.
    private val CONTINUATION_END_CHARS = setOf(
        '、', '，', ',', ';', '；', '・', '‥', '…', '—', '―', '-',
        '،', '؛',
    )

    private val OPENING_BRACKET_CHARS = setOf(
        '「', '『', '（', '【', '〔', '《', '〈', '﹁', '﹃', '(', '“', '‘',
    )
    private val CLOSING_BRACKET_CHARS = setOf(
        '」', '』', '）', '】', '〕', '》', '〉', '﹂', '﹄', ')', '”', '’',
    )

    /** Compute the [TextFlowCue] flags for one recognized line's text. */
    internal fun textFlowCue(text: String): TextFlowCue {
        val trimmed = text.trim()
        var delta = 0
        var hasUpper = false
        var hasLower = false
        for (c in trimmed) {
            if (c in OPENING_BRACKET_CHARS) delta++
            else if (c in CLOSING_BRACKET_CHARS) delta--
            if (c.isUpperCase()) hasUpper = true
            else if (c.isLowerCase()) hasLower = true
        }
        val last = trimmed.lastOrNull()
        val first = trimmed.firstOrNull()
        return TextFlowCue(
            endsTerminal = last != null && last in TERMINAL_END_CHARS,
            endsContinuation = last != null && last in CONTINUATION_END_CHARS,
            startsLowercase = first != null && first.isLowerCase(),
            bracketDelta = delta,
            isAllCaps = hasUpper && !hasLower,
            hasLowercase = hasLower,
        )
    }

    /** Established block-axis pitch of a group: [pitch] = median center-to-center
     *  distance between consecutive visual rows, [lastRowCenter] = block-axis
     *  center of the group's last row (the anchor a candidate's pitch is
     *  measured from). */
    private data class GroupPitch(val pitch: Int, val lastRowCenter: Int)

    /**
     * The group's established line pitch, or null when the group has no
     * reliable one (fewer than two visual rows, an out-of-flow member, or
     * irregular spacing). Rows come from [rowBands] so a line that OCR split
     * into inline fragments still counts as ONE row. Pitch is center-to-center,
     * not edge-gap: glyph-tight bboxes move their edges with ascender/kana
     * content, but a paragraph's row centers are strictly periodic (the
     * `blockGap_generousLeadingParagraph` capture: pitch 57.5/58.5/57.5 while
     * edge gaps run 24–27 over heights 31–37). Regularity is enforced with the
     * same tolerance used for matching, so a group whose own spacing disagrees
     * never lends pitch evidence.
     */
    private fun establishedPitch(memberBoxes: List<Rect>, orientation: TextOrientation): GroupPitch? {
        val rows = rowBands(memberBoxes, orientation)
        if (rows.size < 2) return null
        val vertical = orientation == TextOrientation.VERTICAL
        val centers = rows.map { idxs ->
            val r = unionRect(idxs.map { memberBoxes[it] })
            if (vertical) (r.left + r.right) / 2 else (r.top + r.bottom) / 2
        }
        val diffs = ArrayList<Int>(centers.size - 1)
        for (i in 1 until centers.size) {
            // Reading flow: top-to-bottom rows (horizontal), right-to-left
            // columns (vertical). A non-positive step means an out-of-flow
            // member landed in this group — no reliable pitch.
            val d = if (vertical) centers[i - 1] - centers[i] else centers[i] - centers[i - 1]
            if (d <= 0) return null
            diffs.add(d)
        }
        val sorted = diffs.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        val tol = pitchTolerance(median)
        if (sorted.first() < median - tol || sorted.last() > median + tol) return null
        return GroupPitch(median, centers.last())
    }

    private fun pitchTolerance(pitch: Int): Int =
        maxOf((pitch * PITCH_MATCH_TOLERANCE).toInt(), PITCH_MIN_TOLERANCE_PX)

    /**
     * Group-aware merge evidence layered on top of the pairwise [wouldGroup]
     * predicate by [groupBoxesOnePass] — same-pass layout only; cross-frame
     * callers never see this. Evaluated only after [wouldGroup] said no.
     * Reuses [wouldGroup]'s exact gap/alignment formulas (keep in sync).
     *
     * Merge paths, in order:
     *  1. **Pitch extension** (any gap below the band ceiling): the candidate
     *     sits at the group's established line pitch and passes alignment —
     *     continuation of a rhythm this group already proved. Waives the
     *     scale gate entirely (a digit/kana-tight trailing wrap at
     *     ratio ~0.51 is the confirmed false-split this exists to fix).
     *  2. **First-line indent** (base zone, horizontal LTR): single-line group
     *     whose line starts ≈1 em right of the candidate below — JA 一字下げ /
     *     Western first-line indent (minimal Tesseract first/body model).
     *  3. **Band + text corroboration** (gap in 0.9–1.3×): merge only when a
     *     [TextFlowCue] continuation signal corroborates (unclosed quote
     *     spanning the group, continuation punctuation, Latin lowercase
     *     continuation) and the scale gate passes. Menus and stacked labels —
     *     punct-less, pitch-less first pairs — find no corroboration and stay
     *     split, which keeps `splitMenuGroups`' rows≥4 gate fed.
     *
     * Allocation note: reason strings are built unconditionally; this runs in
     * the per-OCR-pass layout walk (tens of lines), NOT the per-overlay-frame
     * [Classification] hot path, so the [GroupDecision] cost is acceptable.
     */
    internal fun samePassBlockExtras(
        groupRect: Rect,
        candidate: Rect,
        orientation: TextOrientation,
        groupAlignLeft: Int? = null,
        candidateAlignLeft: Int? = null,
        rtl: Boolean = false,
        memberBoxes: List<Rect>,
        bracketBalance: Int = 0,
        lastCue: TextFlowCue? = null,
        candidateCue: TextFlowCue? = null,
        spacedScript: Boolean = true,
    ): GroupDecision {
        shortAboveLongBlock(groupRect, candidate, orientation)?.let {
            return GroupDecision.NotGrouped("ext: $it")
        }
        return if (orientation == TextOrientation.VERTICAL) {
            samePassBlockExtrasVertical(groupRect, candidate, memberBoxes, bracketBalance, lastCue)
        } else {
            samePassBlockExtrasHorizontal(
                groupRect, candidate, groupAlignLeft, candidateAlignLeft, rtl,
                memberBoxes, bracketBalance, lastCue, candidateCue, spacedScript,
            )
        }
    }

    private fun samePassBlockExtrasHorizontal(
        a: Rect,
        b: Rect,
        aAlignLeft: Int?,
        bAlignLeft: Int?,
        rtl: Boolean,
        memberBoxes: List<Rect>,
        bracketBalance: Int,
        lastCue: TextFlowCue?,
        candidateCue: TextFlowCue?,
        spacedScript: Boolean,
    ): GroupDecision {
        val aH = a.height()
        val bH = b.height()
        val refH = maxOf(aH, bH)
        if (refH <= 0) return GroupDecision.NotGrouped("ext: refH=0")
        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        val bandThreshold = (refH * BAND_GAP_MULTIPLIER).toInt()
        if (dy >= bandThreshold) {
            return GroupDecision.NotGrouped("ext: dy=$dy ≥ band ${bandThreshold}px")
        }

        val alignTolerance = (refH * 0.5f).toInt()
        val aStart = if (rtl) a.right else (aAlignLeft ?: a.left)
        val bStart = if (rtl) b.right else (bAlignLeft ?: b.left)
        val startDiff = kotlin.math.abs(aStart - bStart)
        val centerDiff = kotlin.math.abs(a.centerX() - b.centerX())
        val startAligned = startDiff <= alignTolerance
        val centerAligned = centerDiff <= alignTolerance
        // First-line indent: group is a single line that starts ≈1 em to the
        // RIGHT of the continuation candidate strictly below it. Directional —
        // a candidate indented under the group (hanging/list child) must NOT
        // qualify. LTR horizontal only.
        val indentDelta = aStart - bStart
        val firstLineIndent = !rtl && memberBoxes.size == 1 && a.bottom <= b.top &&
            indentDelta >= (refH * FIRST_LINE_INDENT_MIN).toInt() &&
            indentDelta <= (refH * FIRST_LINE_INDENT_MAX).toInt()
        if (!startAligned && !centerAligned && !firstLineIndent) {
            return GroupDecision.NotGrouped(
                "ext: align startΔ=$startDiff centerΔ=$centerDiff indentΔ=$indentDelta tol=${alignTolerance}px"
            )
        }

        val groupPitch = establishedPitch(memberBoxes, TextOrientation.HORIZONTAL)
        val candidatePitch = groupPitch?.let { b.centerY() - it.lastRowCenter }
        val pitchOk = groupPitch != null && candidatePitch != null && candidatePitch > 0 &&
            kotlin.math.abs(candidatePitch - groupPitch.pitch) <= pitchTolerance(groupPitch.pitch)
        val pitchStr = if (groupPitch != null) "$candidatePitch vs ${groupPitch.pitch}" else "n/a"

        val lo = minOf(aH, bH)
        val hi = maxOf(aH, bH)
        val scaleOk = lo <= 0 || (hi - lo).toDouble() / lo <= SIZE_RATIO_CAP_CORROBORATED

        val textContinues = bracketBalance > 0 ||
            lastCue?.endsContinuation == true ||
            (spacedScript && lastCue != null && !lastCue.endsTerminal && candidateCue?.startsLowercase == true)

        val vgapThreshold = (refH * BLOCK_GAP_MULTIPLIER).toInt()
        return if (dy < vgapThreshold) {
            when {
                pitchOk -> GroupDecision.Grouped(
                    "ext-pitch (dy=$dy, pitch $pitchStr ±${pitchTolerance(groupPitch!!.pitch)}px, scale waived)"
                )
                firstLineIndent && scaleOk -> GroupDecision.Grouped(
                    "ext-indent (dy=$dy, indentΔ=$indentDelta ≈ 1em of refH=$refH)"
                )
                else -> GroupDecision.NotGrouped(
                    "ext: base zone, pitch=$pitchStr, indent=$firstLineIndent, scaleOk=$scaleOk"
                )
            }
        } else {
            when {
                pitchOk -> GroupDecision.Grouped(
                    "ext-band-pitch (dy=$dy in [${vgapThreshold},${bandThreshold})px, pitch $pitchStr)"
                )
                BAND_TEXT_SEEDING_ENABLED && textContinues && scaleOk -> GroupDecision.Grouped(
                    "ext-band-cont (dy=$dy in [${vgapThreshold},${bandThreshold})px, brackets=$bracketBalance cont=${lastCue?.endsContinuation} lower=${candidateCue?.startsLowercase})"
                )
                else -> GroupDecision.NotGrouped(
                    "ext-band: dy=$dy, no corroboration (pitch=$pitchStr, cont=$textContinues" +
                        (if (textContinues && !BAND_TEXT_SEEDING_ENABLED) " [seeding disabled]" else "") +
                        ", scaleOk=$scaleOk)"
                )
            }
        }
    }

    private fun samePassBlockExtrasVertical(
        a: Rect,
        b: Rect,
        memberBoxes: List<Rect>,
        bracketBalance: Int,
        lastCue: TextFlowCue?,
    ): GroupDecision {
        val aW = a.width()
        val bW = b.width()
        val refW = maxOf(aW, bW)
        if (refW <= 0) return GroupDecision.NotGrouped("ext: refW=0")
        val dx = if (a.left <= b.right && b.right <= a.right) 0
                 else if (b.left <= a.right && a.right <= b.right) 0
                 else if (a.right <= b.left) b.left - a.right
                 else a.left - b.right
        val bandThreshold = (refW * BAND_GAP_MULTIPLIER).toInt()
        if (dx >= bandThreshold) {
            return GroupDecision.NotGrouped("ext: dx=$dx ≥ band ${bandThreshold}px")
        }

        val alignTolerance = (refW * 0.5f).toInt()
        val topDiff = kotlin.math.abs(a.top - b.top)
        val centerDiff = kotlin.math.abs(a.centerY() - b.centerY())
        if (topDiff > alignTolerance && centerDiff > alignTolerance) {
            return GroupDecision.NotGrouped(
                "ext: align topΔ=$topDiff centerΔ=$centerDiff > tol=${alignTolerance}px"
            )
        }

        val groupPitch = establishedPitch(memberBoxes, TextOrientation.VERTICAL)
        // Vertical flow is right-to-left: the next column's center sits LEFT
        // of the last row's center by one pitch.
        val candidatePitch = groupPitch?.let { it.lastRowCenter - ((b.left + b.right) / 2) }
        val pitchOk = groupPitch != null && candidatePitch != null && candidatePitch > 0 &&
            kotlin.math.abs(candidatePitch - groupPitch.pitch) <= pitchTolerance(groupPitch.pitch)
        val pitchStr = if (groupPitch != null) "$candidatePitch vs ${groupPitch.pitch}" else "n/a"

        val lo = minOf(aW, bW)
        val hi = maxOf(aW, bW)
        val scaleOk = lo <= 0 || (hi - lo).toDouble() / lo <= SIZE_RATIO_CAP_CORROBORATED

        // No lowercase rule (vertical text is CJK) and no first-line indent
        // (out of scope for vertical — see FIRST_LINE_INDENT_MIN kdoc).
        val textContinues = bracketBalance > 0 || lastCue?.endsContinuation == true

        val hgapThreshold = (refW * BLOCK_GAP_MULTIPLIER).toInt()
        return if (dx < hgapThreshold) {
            when {
                pitchOk -> GroupDecision.Grouped(
                    "ext-pitch (dx=$dx, pitch $pitchStr ±${pitchTolerance(groupPitch!!.pitch)}px, scale waived)"
                )
                else -> GroupDecision.NotGrouped(
                    "ext: base zone, pitch=$pitchStr, scaleOk=$scaleOk"
                )
            }
        } else {
            when {
                pitchOk -> GroupDecision.Grouped(
                    "ext-band-pitch (dx=$dx in [${hgapThreshold},${bandThreshold})px, pitch $pitchStr)"
                )
                BAND_TEXT_SEEDING_ENABLED && textContinues && scaleOk -> GroupDecision.Grouped(
                    "ext-band-cont (dx=$dx in [${hgapThreshold},${bandThreshold})px, brackets=$bracketBalance cont=${lastCue?.endsContinuation})"
                )
                else -> GroupDecision.NotGrouped(
                    "ext-band: dx=$dx, no corroboration (pitch=$pitchStr, cont=$textContinues" +
                        (if (textContinues && !BAND_TEXT_SEEDING_ENABLED) " [seeding disabled]" else "") +
                        ", scaleOk=$scaleOk)"
                )
            }
        }
    }

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
     * - [cues] : optional per-line [TextFlowCue] flags (see [textFlowCue]),
     *   used only as corroboration inside [samePassBlockExtras]' gap band.
     *   Pass `null` to run pure-geometry (bare-rect callers, tests) — the
     *   band then merges on pitch evidence alone.
     * - [spacedScript] : whether the source language separates words with
     *   whitespace; gates the Latin/Cyrillic lowercase-continuation cue.
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
        rtl: Boolean = false,
        cues: List<TextFlowCue>? = null,
        spacedScript: Boolean = true,
    ): List<List<Int>> {
        require(boxes.size == alignLefts.size) {
            "boxes and alignLefts must match length"
        }
        require(texts == null || texts.size == boxes.size) {
            "texts must match boxes length when provided"
        }
        require(cues == null || cues.size == boxes.size) {
            "cues must match boxes length when provided"
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
                // Caps-heading veto (text-informed, walk-level like the band
                // cues): an all-caps line directly above a line containing
                // lowercase is a title/label over body text ("ARCTIC GALE" /
                // "Your Casts also…", Hades boon cards, Thor 2026-07-01).
                // Glyph-tight boxes destroy the font-scale signal for exactly
                // this pair — an all-caps box is cap-height only, so a ~2×
                // title measures ~1.2× the mixed-case line below it (observed
                // hRatio 0.13–0.17, inside every height cap) — and the case
                // profile is the surviving evidence. Cased scripts +
                // horizontal only; caps-above-caps (shouted wraps) and
                // caps-above-digits are unaffected.
                val capsHeadingVeto = cues != null &&
                    orientation == TextOrientation.HORIZONTAL &&
                    groupRect.bottom <= lineBox.top &&
                    cues[candidateGroup.last()].isAllCaps &&
                    cues[idx].hasLowercase
                if (capsHeadingVeto) {
                    if (logDecisions) {
                        val prevSnippet =
                            (texts?.get(candidateGroup.last()) ?: "").take(24).replace('\n', ' ')
                        val candSnippet =
                            (texts?.get(idx) ?: "").take(24).replace('\n', ' ')
                        android.util.Log.d(
                            "DetectionLog",
                            "[group:$orientChar] SPLIT g$gi prev=${rectStr(groupRect)} \"$prevSnippet\" cand=${rectStr(lineBox)} \"$candSnippet\" :: caps-heading veto (all-caps above lowercase)"
                        )
                    }
                    continue
                }
                // wouldGroup is the canonical pairwise predicate — used
                // unconditionally so the debug-log toggle is purely
                // observational. When it says no, the group-aware evidence
                // layer gets a turn: pitch extension, first-line indent, and
                // the corroborated gap band (see samePassBlockExtras).
                // groupDecision is called only to produce a reason string
                // for the log; if it ever diverges from wouldGroup the log
                // wording becomes misleading but grouping behavior stays
                // consistent.
                val baseMerged = wouldGroup(
                    groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl = rtl
                )
                val extras = if (baseMerged) null else samePassBlockExtras(
                    groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl,
                    memberBoxes = candidateGroup.map { boxes[it] },
                    bracketBalance = cues?.let { cs -> candidateGroup.sumOf { cs[it].bracketDelta } } ?: 0,
                    lastCue = cues?.get(candidateGroup.last()),
                    candidateCue = cues?.get(idx),
                    spacedScript = spacedScript,
                )
                val groupMerged = baseMerged || extras is GroupDecision.Grouped
                if (logDecisions) {
                    val decision = groupDecision(
                        groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl = rtl
                    )
                    val prevSnippet =
                        (texts?.get(candidateGroup.last()) ?: "").take(24).replace('\n', ' ')
                    val candSnippet =
                        (texts?.get(idx) ?: "").take(24).replace('\n', ' ')
                    val verdict = if (groupMerged) "MERGE" else "SPLIT"
                    val extReason = extras?.let { " | ${it.reason}" } ?: ""
                    android.util.Log.d(
                        "DetectionLog",
                        "[group:$orientChar] $verdict g$gi prev=${rectStr(groupRect)} \"$prevSnippet\" cand=${rectStr(lineBox)} \"$candSnippet\" :: ${decision.reason}$extReason"
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
        "ru", "bg", "uk" -> c in 'Ѐ'..'ӿ'
        "th" -> c in '฀'..'๿'
        "hi", "mr", "ne" -> c in 'ऀ'..'ॿ'
        // Arabic (and any profiled language with no hardcoded case above) routes
        // through the profile's isScriptChar — for Arabic that covers Supplement,
        // Extended-A, and Presentation Forms (e.g. the ﷲ ligature) the recognizer
        // emits, which a base-block-only range would drop from the pipeline.
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
        val profile = SourceLanguageProfiles.forCode(sourceLang)
        val rtl = profile?.textDirection == TextDirection.RTL
        val spacedScript = profile?.wordsSeparatedByWhitespace != false
        val (vertical, horizontal) = regions.partition { it.orientation == TextOrientation.VERTICAL }
        val hGroups = groupRegions(
            horizontal.sortedBy { it.box.bounds.top }, TextOrientation.HORIZONTAL, logDecisions, rtl, spacedScript
        )
        val vGroups = groupRegions(
            vertical.sortedByDescending { it.box.bounds.right }, TextOrientation.VERTICAL, logDecisions, rtl, spacedScript
        )
        val rawGroups = (hGroups + vGroups).filter { group ->
            group.any { r -> r.text.any { isSourceLangChar(it, sourceLang) } }
        }
        if (rawGroups.isEmpty()) return emptyList()
        val split = if (screenshotWidthInRegionSpace > 0f) {
            splitMenuGroups(rawGroups, screenshotWidthInRegionSpace, logDecisions)
        } else {
            rawGroups.map { SplitGroup(it) }
        }
        // Join a group's lines with a space only for whitespace-delimited
        // languages; CJK/Thai (wordsSeparatedByWhitespace = false) get no
        // separator so the merged paragraph reads naturally AND the translator
        // receives clean source (`今日はいい天気` not `今日は いい天気`). Default to
        // a space when the profile is unknown — only languages we KNOW omit
        // inter-word spaces drop it, so every other language keeps prior behavior.
        val lineJoin = if (spacedScript) " " else ""
        return split.mapNotNull { buildLayoutGroup(it, lineJoin) }
    }

    /** Extract boxes + align-left hints + text-flow cues from sorted regions,
     *  run the kernel, and remap index-groups back to region lists. */
    private fun groupRegions(
        sorted: List<RecognizedRegion>,
        orientation: TextOrientation,
        logDecisions: Boolean,
        rtl: Boolean,
        spacedScript: Boolean,
    ): List<List<RecognizedRegion>> {
        if (sorted.isEmpty()) return emptyList()
        val boxes = sorted.map { it.box.bounds }
        val alignLefts: List<Int?> = if (orientation == TextOrientation.HORIZONTAL) {
            sorted.map { region -> region.lines.firstOrNull()?.let { effectiveAlignLeft(it) } ?: region.box.bounds.left }
        } else {
            List(sorted.size) { null }
        }
        val texts = if (logDecisions) sorted.map { it.text } else null
        val cues = sorted.map { textFlowCue(it.text) }
        val idxGroups =
            groupBoxesOnePass(boxes, alignLefts, orientation, logDecisions, texts, rtl, cues, spacedScript)
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
     *
     * Counting is by visual ROW, not raw region: regions that share a line — an
     * inline `label: value` pair like "Gust Area Damage:" + "4 (every 0.25 Sec.)"
     * — collapse into one row ([rowBands]). Otherwise a 3-row card body whose stat
     * line OCR'd as two boxes reads as a 4-item menu and gets shredded.
     */
    private fun splitMenuGroups(
        groups: List<List<RecognizedRegion>>,
        screenWidth: Float,
        logDecisions: Boolean = false,
    ): List<SplitGroup> = groups.flatMap { group ->
        val orientation = group.firstOrNull()?.orientation ?: TextOrientation.HORIZONTAL
        val rows = rowBands(group.map { it.box.bounds }, orientation)
        val rowRects = rows.map { idxs -> unionRect(idxs.map { group[it].box.bounds }) }
        if (rows.size >= 4 && isMenuLike(rowRects, screenWidth)) {
            val groupLeft = rowRects.minOf { it.left }
            val groupRight = rowRects.maxOf { it.right }
            if (logDecisions) {
                android.util.Log.d(
                    "DetectionLog",
                    "[menu-split] ${rows.size} rows w=${groupRight - groupLeft} " +
                        "\"${(group.firstOrNull()?.text ?: "").take(24).replace('\n', ' ')}\"",
                )
            }
            rows.map { idxs ->
                SplitGroup(idxs.map { group[it] }, parentLeft = groupLeft, parentRight = groupRight)
            }
        } else {
            listOf(SplitGroup(group))
        }
    }

    /** Union of [rects]. */
    private fun unionRect(rects: List<Rect>): Rect = Rect(
        rects.minOf { it.left }, rects.minOf { it.top },
        rects.maxOf { it.right }, rects.maxOf { it.bottom },
    )

    /**
     * Group box indices into visual rows along the reading-flow axis: horizontal
     * text stacks top-to-bottom (band on the Y axis), vertical text stacks
     * right-to-left into columns (band on the X axis). Boxes whose cross-axis spans
     * overlap by ≥ half the smaller extent share a row, so an inline pair on one
     * line collapses into a single row. Index lists, rows in reading order.
     */
    internal fun rowBands(boxes: List<Rect>, orientation: TextOrientation): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        val vertical = orientation == TextOrientation.VERTICAL
        val order = if (vertical) boxes.indices.sortedByDescending { boxes[it].right }
        else boxes.indices.sortedBy { boxes[it].top }
        val rows = mutableListOf<MutableList<Int>>()
        var bandLo = 0
        var bandHi = 0
        for (i in order) {
            val b = boxes[i]
            val lo = if (vertical) b.left else b.top
            val hi = if (vertical) b.right else b.bottom
            val join = if (rows.isEmpty()) false else {
                val overlap = minOf(bandHi, hi) - maxOf(bandLo, lo)
                val minExtent = minOf(bandHi - bandLo, hi - lo)
                minExtent > 0 && overlap >= 0.5f * minExtent
            }
            if (join) {
                rows.last() += i
                bandLo = minOf(bandLo, lo); bandHi = maxOf(bandHi, hi)
            } else {
                rows += mutableListOf(i)
                bandLo = lo; bandHi = hi
            }
        }
        return rows
    }

    /** Whether [rowRects] (one per visual row) look like a menu/list: narrower than
     *  ⅓ screen and left/right edges don't both cluster (a justified paragraph does). */
    internal fun isMenuLike(rowRects: List<Rect>, screenWidth: Float): Boolean {
        if (rowRects.isEmpty()) return false
        val groupWidth = rowRects.maxOf { it.right } - rowRects.minOf { it.left }
        if (groupWidth >= screenWidth / 3f) return false
        val avgRowHeight = rowRects.map { it.height() }.average().toFloat()
        val minLeft = rowRects.minOf { it.left }
        val maxRight = rowRects.maxOf { it.right }
        val clusterThreshold = rowRects.size - 1
        val nearMinLeft = rowRects.count { it.left - minLeft <= avgRowHeight }
        val nearMaxRight = rowRects.count { maxRight - it.right <= avgRowHeight }
        val leftClustered = nearMinLeft >= clusterThreshold
        val rightClustered = nearMaxRight >= clusterThreshold
        if (leftClustered && rightClustered) return false
        return true
    }

    /**
     * Indices of [boxes] in reading order: rows top-to-bottom, and within a row
     * left-to-right for horizontal text (top-to-bottom within a column for vertical).
     * Built on [rowBands], so a same-line inline pair is ordered by position, not by
     * OCR top-edge jitter that could otherwise flip a value ahead of its label.
     */
    internal fun readingOrderIndices(boxes: List<Rect>, orientation: TextOrientation): List<Int> {
        val vertical = orientation == TextOrientation.VERTICAL
        return rowBands(boxes, orientation).flatMap { idxs ->
            if (vertical) idxs.sortedBy { boxes[it].top } else idxs.sortedBy { boxes[it].left }
        }
    }

    private fun buildLayoutGroup(sg: SplitGroup, lineJoin: String): LayoutGroup? {
        val raw = sg.regions
        if (raw.isEmpty()) return null
        val verticalCount = raw.count { it.orientation == TextOrientation.VERTICAL }
        val orientation =
            if (verticalCount > raw.size / 2) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
        // Order regions in reading order before joining (rows top-to-bottom, within a
        // row left-to-right for horizontal), so a same-line inline pair like
        // "Gust Area Damage:" + "4 (every…)" joins by position — robust to OCR
        // top-edge jitter that could otherwise put the value ahead of its label.
        val regions = readingOrderIndices(raw.map { it.box.bounds }, orientation).map { raw[it] }
        val text = regions.joinToString(lineJoin) { it.text }.trim()
        if (text.isBlank()) return null
        val lines = regions.flatMap { it.lines }
        val rects = regions.map { it.box.bounds }
        val left = sg.parentLeft ?: rects.minOf { it.left }
        val right = sg.parentRight ?: rects.maxOf { it.right }
        val bounds = Rect(left, rects.minOf { it.top }, right, rects.maxOf { it.bottom })
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
