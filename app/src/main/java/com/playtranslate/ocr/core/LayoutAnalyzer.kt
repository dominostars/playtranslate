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
    internal const val BAND_GAP_MULTIPLIER = 1.3f

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
     * Tail-shape ceiling for the pitch waiver: an established-pitch match
     * waives the scale gate entirely ONLY when the candidate's inline extent
     * (width for horizontal text, column length for vertical) is under this
     * fraction of the group's union extent. The waiver's confirmed bug class
     * is trailing wraps, which are by nature much narrower than their
     * paragraph (both captured tails measure 0.44× their group's width) —
     * and glyph starvation, the reason short lines mis-measure their height,
     * only afflicts narrow lines in the first place: a near-full-width line
     * has enough characters to contain tall glyphs and measures normally.
     * A full-extent candidate at coincidental pitch still merges, but through
     * [SIZE_RATIO_CAP_CORROBORATED] — so no path bypasses the evidence
     * ladder without tail-shape evidence. (Option A from the 2026-05-12
     * height-gate analysis; closes the one cap-free merge path left after
     * the ladder re-grade.)
     */
    internal const val PITCH_TAIL_MAX_EXTENT_RATIO = 0.7f

    /**
     * Document-pitch prior (opt-in per pass — the file-import pipeline only;
     * see [analyze]'s documentPitchPrior). A single-row group can never
     * establish its own pitch, so the FIRST pair of every paragraph starved
     * in the gap band even on pages whose line rhythm is overwhelming: the
     * Thor japanese-test.pdf page carries body rows at 84–87px steps against
     * a page-dominant 86px, yet every opening pair logged "ext-band: no
     * corroboration (pitch=n/a, cont=false)" because bare CJK line endings
     * are deliberately not a continuation cue (the menu guard). The
     * page-dominant pitch stands in for the group's own at bootstrap, riding
     * the SAME merge rule (tolerance, tail/scale caps) as established pitch.
     *
     * [DOC_PITCH_MAX_SIZE_MULT] windows which row steps may vote: the band
     * ceiling admits edge gaps under [BAND_GAP_MULTIPLIER]× refH, i.e.
     * center-to-center steps up to roughly 2.3–2.4× a row's size — a step
     * beyond that could never be a line advance, so it is a paragraph or
     * section gap BY DEFINITION and must not shape the prior. That filter is
     * load-bearing: on the test page boundary steps (120–180px) OUTNUMBER
     * line steps (four at 84–87px), so a plain median would land on a
     * boundary. [DOC_PITCH_MIN_SAMPLES] in-window steps must agree within
     * [pitchTolerance] before a page is deemed to have a rhythm at all.
     */
    private const val DOC_PITCH_MIN_SAMPLES = 3
    private const val DOC_PITCH_MAX_SIZE_MULT = 2.4f

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
     *  - Waived on an established-pitch match in [samePassBlockExtras] when
     *    the candidate is tail-shaped ([PITCH_TAIL_MAX_EXTENT_RATIO]) — the
     *    tier that carries the CONFIRMED trailing-wrap captures (ratios 0.51
     *    and 0.55, both 0.44× their group's width). A pitch-matched
     *    full-extent candidate falls back to the corroborated tier.
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
     *
     * The BARE tier additionally accepts on the half-blended char-class
     * statistic under a raw ceiling — see [scaleOkWithBlend] /
     * [blendedSizeRatio] (2026-07-25): the raw statistic carries the line's
     * CONTENT (a descender row measures ~1.3× a same-font row without one),
     * and the blend discounts half of that predicted noise for mapped
     * scripts. Permissive only, same-pass walk only: the corroborated tier
     * and cross-frame keep the raw statistic alone.
     */
    /**
     * The bare cap is split by orientation because it guards two DIFFERENT
     * statistics (2026-07-24/25 corpus sweeps): horizontal compares line
     * heights, whose content noise tops out around 0.29 on every engine
     * (all-kana pixel-font prose reaches 0.286 even on Meiki), so 0.30 is
     * load-bearing and tightening to 0.15 shredded real paragraphs; vertical
     * compares column INK WIDTH, which is noisier still on short columns —
     * same-font 2-character manga columns (さて/よく) measure 0.30–0.37, and
     * 0.40 fixed all three such corpus wrong blocks at zero observed cost.
     * Caveat the 0.40 carries: the corpus's vertical should-split population
     * is thin, so the enemy (adjacent vertical columns at real size
     * differences, e.g. vertical menu headings) is priced from few samples —
     * accepted 2026-07-25; vertical-menu/manga seeds remain wanted.
     */
    private const val SIZE_RATIO_CAP_BARE_HORIZONTAL = 0.30
    private const val SIZE_RATIO_CAP_BARE_VERTICAL = 0.40
    internal const val SIZE_RATIO_CAP_CORROBORATED = 0.50

    private fun sizeRatioCap(mode: GroupingMode, vertical: Boolean): Double =
        if (mode == GroupingMode.CROSS_FRAME_SAME_REGION) SIZE_RATIO_CAP_CORROBORATED
        else if (vertical) SIZE_RATIO_CAP_BARE_VERTICAL
        else SIZE_RATIO_CAP_BARE_HORIZONTAL

    /** Same-line (inline) gap ceiling, in units of the reference line height.
     *  Shared by [wouldGroup]'s inline branch and [inlineContinuesLastLine]
     *  so the whole-box and last-line continuations stay on one ruler. */
    private const val INLINE_GAP_MULTIPLIER = 1.5f

    /**
     * Cross-frame inline-continuation probe for the shrink direction that
     * [wouldGroup]'s classification caller deliberately de-normalizes
     * (cached multi-line box, fresh single-line fragment): is [fresh] a
     * same-row continuation of [boxRect]'s LAST line?
     *
     * Against a multi-line cached rect, [wouldGroup]'s inline branch can
     * never accept a true last-line continuation — the fragment's height is
     * compared against the whole box's raw height (campfire trace
     * 2026-07-10: 125px two-line box vs 59px typewriter tail → ratio 1.12
     * over the cap, tail placed as its own box, sentence stranded split).
     * This probe re-tests the INLINE conditions only, on the last line's
     * band: fragment centerY inside the band, gap under
     * [INLINE_GAP_MULTIPLIER]× the per-line height (tighter than the
     * whole-box branch, whose refH is the full height), and per-line height
     * similarity within the cross-frame cap.
     *
     * Deliberately NOT a general shrink-direction re-normalization: a
     * fragment below the box always fails the band check here — the
     * next-row reveal shape has its own gated probe,
     * [blockContinuesBelow]. Unlike the inline
     * branch it mirrors, the probe is DIRECTIONAL: a continuation extends
     * the last line in reading direction only (rightward for LTR [rtl] =
     * false, leftward for RTL), strictly beside the union rect — the
     * non-forward side and x-overlap both refuse (see the forwardGap
     * comment below). Horizontal text only; the vertical twin (fragment
     * column beside a multi-column box) is unobserved and deferred.
     */
    fun inlineContinuesLastLine(
        boxRect: Rect,
        boxLineCount: Int,
        fresh: Rect,
        rtl: Boolean = false,
    ): Boolean {
        // Single-line boxes already get a correct inline comparison from
        // wouldGroup (raw height == line height); only multi-line boxes
        // need the band-scoped rescue.
        if (boxLineCount < 2) return false
        val lineH = boxRect.height() / boxLineCount
        if (lineH <= 0 || fresh.height() <= 0) return false
        val bandTop = boxRect.bottom - lineH
        val freshCy = (fresh.top + fresh.bottom) / 2
        if (freshCy < bandTop || freshCy > boxRect.bottom) return false
        // A continuation extends the last line in READING direction only —
        // a same-height fragment on the non-forward side is a neighbor
        // (label, list bullet, another column), never a continuation.
        // A negative gap also refuses x-overlap with the union, which is
        // NOT continuation evidence: the union rect says nothing about the
        // last line's true extent (ragged paragraph — long first line,
        // short last line — puts union width far right of the last-line
        // glyphs). The pinhole caller can't even produce an overlapping
        // fragment (the rendered box covers the union and the region is
        // bg-filled before OCR), but that is a caller invariant — the
        // geometry here must refuse on its own.
        val forwardGap =
            if (rtl) boxRect.left - fresh.right else fresh.left - boxRect.right
        if (forwardGap < 0) return false
        if (forwardGap >= (lineH * INLINE_GAP_MULTIPLIER).toInt()) return false
        val lo = minOf(lineH, fresh.height())
        val hi = maxOf(lineH, fresh.height())
        return (hi - lo).toDouble() / lo <= sizeRatioCap(GroupingMode.CROSS_FRAME_SAME_REGION, vertical = false)
    }

    /**
     * Cross-frame block-continuation probe for the OTHER shrink-direction
     * shape [wouldGroup]'s classification caller de-normalizes: is [fresh]
     * a single line revealed directly BELOW [boxRect] — the next wrapped
     * line of the paragraph the box covers?
     *
     * Line-by-line typewriter reveals produce exactly this geometry: the
     * box was placed on a partial read (rows 1–2 mid-reveal), the next row
     * then appears below it, and the whole-box branch compares the row's
     * height against the box's full stacked extent (グラウス trace
     * 2026-07-12 c14: 126px two-line box vs 55px third row → delta 1.29
     * over the cap, sentence stranded as a persistent 2+1 split; the box's
     * own under-box reveal read 2.0% pinholes — below the removal bar —
     * so no other signal could ever converge it). This probe re-tests the
     * BLOCK conditions on the box's per-line height instead: gap under
     * [BLOCK_GAP_MULTIPLIER]× the per-line reference, start-or-center
     * alignment at the per-line tolerance (half the slack the
     * de-normalized branch grants), and per-line height similarity within
     * the cross-frame cap. [shortAboveLongBlock] still vetoes a
     * clearly-short box above a wide fresh line.
     *
     * Deliberately NOT a general shrink-direction re-normalization, same
     * discipline as [inlineContinuesLastLine]: the fresh side must be a
     * single line (callers gate lineCount == 1) and strictly below the
     * box — downward is the reveal direction for horizontal text; a
     * fragment above is a heading or name plate, never a continuation.
     * The consciously-priced trade (2026-07-16): an unrelated label that
     * matches all three per-line conditions now stales the box, costing
     * one recoverable blank-and-regroup cycle in which the same-pass
     * grouper re-decides with full evidence — while the false-refuse this
     * closes was a permanently split sentence with no convergence path.
     * Horizontal text only; the vertical twin (next column beside a
     * multi-column box) is unobserved and deferred.
     */
    fun blockContinuesBelow(
        boxRect: Rect,
        boxLineCount: Int,
        fresh: Rect,
        rtl: Boolean = false,
    ): Boolean {
        // Single-line boxes already get a correct block comparison from
        // wouldGroup (raw height == line height); only multi-line boxes
        // need the per-line rescue.
        if (boxLineCount < 2) return false
        val lineH = boxRect.height() / boxLineCount
        if (lineH <= 0 || fresh.height() <= 0) return false
        // Strictly below only. Negative dy means overlap or fresh-above:
        // overlap is the inline probe's territory, and a line above a
        // paragraph is a heading/name-plate shape, not a reveal.
        val dy = fresh.top - boxRect.bottom
        if (dy < 0) return false
        if (shortAboveLongBlock(boxRect, fresh, TextOrientation.HORIZONTAL) != null) return false
        val refH = maxOf(lineH, fresh.height())
        if (dy >= (refH * BLOCK_GAP_MULTIPLIER).toInt()) return false
        // Same alignment contract as wouldGroup's block branch, at the
        // per-line tolerance.
        val alignTolerance = (refH * 0.5f).toInt()
        val aStart = if (rtl) boxRect.right else boxRect.left
        val bStart = if (rtl) fresh.right else fresh.left
        val startAligned = kotlin.math.abs(aStart - bStart) <= alignTolerance
        val centerAligned = kotlin.math.abs(boxRect.centerX() - fresh.centerX()) <= alignTolerance
        if (!startAligned && !centerAligned) return false
        val lo = minOf(lineH, fresh.height())
        val hi = maxOf(lineH, fresh.height())
        return (hi - lo).toDouble() / lo <= sizeRatioCap(GroupingMode.CROSS_FRAME_SAME_REGION, vertical = false)
    }

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
        /** [CharClassCoverage] predictions for the two lines (a = the group's
         *  last visual row, b = the candidate) — see [scaleOkWithBlend]. Null
         *  (bare-rect callers, cross-frame) keeps the raw statistic alone. */
        aCoverage: Double? = null,
        bCoverage: Double? = null,
    ): Boolean {
        if (shortAboveLongBlock(a, b, orientation) != null) return false
        if (orientation == TextOrientation.VERTICAL) {
            return wouldGroupVertical(a, b, mode, aLineCount, bLineCount, aCoverage, bCoverage)
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
            if (dx < (refH * INLINE_GAP_MULTIPLIER).toInt()) {
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
                if (scaleOkWithBlend(aH, bH, aCoverage, bCoverage, sizeRatioCap(mode, vertical = false))) return true
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
                if (scaleOkWithBlend(aH, bH, aCoverage, bCoverage, sizeRatioCap(mode, vertical = false))) return true
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
        aCoverage: Double? = null,
        bCoverage: Double? = null,
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
                if (scaleOkWithBlend(aW, bW, aCoverage, bCoverage, sizeRatioCap(mode, vertical = true))) return true
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
                if (scaleOkWithBlend(aW, bW, aCoverage, bCoverage, sizeRatioCap(mode, vertical = true))) return true
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
        aCoverage: Double? = null,
        bCoverage: Double? = null,
    ): GroupDecision {
        val sizeBlock = shortAboveLongBlock(a, b, orientation)
        if (sizeBlock != null) return GroupDecision.NotGrouped(sizeBlock)
        return if (orientation == TextOrientation.VERTICAL)
            groupDecisionVertical(a, b, mode, aLineCount, bLineCount, aCoverage, bCoverage)
        else
            groupDecisionHorizontal(
                a, b, aAlignLeft, bAlignLeft, mode, aLineCount, bLineCount, rtl,
                aCoverage, bCoverage,
            )
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
        aCoverage: Double? = null,
        bCoverage: Double? = null,
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
        val blendRatio = blendedSizeRatio(aH, bH, aCoverage, bCoverage)
        val inlineHeightOk = scaleOkWithBlend(aH, bH, aCoverage, bCoverage, sizeRatioCap(mode, vertical = false))
        if (sameLine && dx < inlineGapThreshold && inlineHeightOk) {
            return GroupDecision.Grouped("inline (dx=$dx < ${inlineGapThreshold}px, refH=$refH$lnStr)")
        }

        // 3. Block: vertical continuation (next line in same paragraph)
        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        val vgapThreshold = (refH * BLOCK_GAP_MULTIPLIER).toInt()
        val heightCap = sizeRatioCap(mode, vertical = false)
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
        val rawHeightOk = lo <= 0 || heightRatio <= heightCap
        val heightOk = scaleOkWithBlend(aH, bH, aCoverage, bCoverage, heightCap)
        // Name the evidence when the blended statistic carried a decision
        // the raw ratio would have blocked — the trace must show which
        // statistic decided.
        val normStr = if (!rawHeightOk && heightOk) " blend=${"%.2f".format(blendRatio)}" else ""

        if (vgapOk && alignOk && heightOk) {
            val edgeName = if (rtl) "right" else "left"
            val which = when {
                startAligned && centerAligned -> "$edgeName+center"
                startAligned -> edgeName
                else -> "center"
            }
            val hRatioStr = if (lo > 0) "%.2f".format(heightRatio) else "n/a"
            return GroupDecision.Grouped(
                "block (dy=$dy<${vgapThreshold}px, align=$which $startStr centerΔ=$centerDiff tol=${alignTolerance}px, hRatio=$hRatioStr$normStr, refH=$refH$lnStr)"
            )
        }

        val fails = buildList {
            if (!vgapOk) add("vgap dy=$dy ≥ ${vgapThreshold}px")
            if (!alignOk) add("align: $startStr centerΔ=$centerDiff > tol=${alignTolerance}px")
            if (!heightOk) add(
                "height: lo=$lo hi=$hi ratio=${"%.2f".format(heightRatio)}" +
                    (blendRatio?.let { " blend=${"%.2f".format(it)}" } ?: "") +
                    " > ${"%.2f".format(heightCap)}"
            )
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
        aCoverage: Double? = null,
        bCoverage: Double? = null,
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
        val blendRatio = blendedSizeRatio(aW, bW, aCoverage, bCoverage)
        val inlineWidthOk = scaleOkWithBlend(aW, bW, aCoverage, bCoverage, sizeRatioCap(mode, vertical = true))
        if (sameColumn && dy < inlineGapThreshold && inlineWidthOk) {
            return GroupDecision.Grouped("inline (dy=$dy < ${inlineGapThreshold}px, refW=$refW$lnStr)")
        }

        val dx = if (a.left <= b.right && b.right <= a.right) 0
                 else if (b.left <= a.right && a.right <= b.right) 0
                 else if (a.right <= b.left) b.left - a.right
                 else a.left - b.right
        val hgapThreshold = (refW * BLOCK_GAP_MULTIPLIER).toInt()
        val widthCap = sizeRatioCap(mode, vertical = true)
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
        val rawWidthOk = lo <= 0 || widthRatio <= widthCap
        val widthOk = scaleOkWithBlend(aW, bW, aCoverage, bCoverage, widthCap)
        // See groupDecisionHorizontal: name the statistic that decided.
        val normStr = if (!rawWidthOk && widthOk) " blend=${"%.2f".format(blendRatio)}" else ""

        if (hgapOk && alignOk && widthOk) {
            val which = when {
                topAligned && centerAligned -> "top+center"
                topAligned -> "top"
                else -> "center"
            }
            val wRatioStr = if (lo > 0) "%.2f".format(widthRatio) else "n/a"
            return GroupDecision.Grouped(
                "block (dx=$dx<${hgapThreshold}px, align=$which topΔ=$topDiff centerΔ=$centerDiff tol=${alignTolerance}px, wRatio=$wRatioStr$normStr, refW=$refW$lnStr)"
            )
        }

        val fails = buildList {
            if (!hgapOk) add("hgap dx=$dx ≥ ${hgapThreshold}px")
            if (!alignOk) add("align: topΔ=$topDiff centerΔ=$centerDiff > tol=${alignTolerance}px")
            if (!widthOk) add(
                "width: lo=$lo hi=$hi ratio=${"%.2f".format(widthRatio)}" +
                    (blendRatio?.let { " blend=${"%.2f".format(it)}" } ?: "") +
                    " > ${"%.2f".format(widthCap)}"
            )
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
        /** Line has at least one uppercase letter, no lowercase letter, and no
         *  letter from a CASELESS script — an all-caps heading/label case
         *  profile. The caseless-letter clause is what actually scopes this to
         *  cased scripts: `hasUpper && !hasLower` alone reads a CJK line with an
         *  embedded Latin acronym (楽しいHOLIDAY, HPを回復, GAME OVER) as
         *  all-caps, and the caps-heading veto would then split a CJK paragraph
         *  whenever the line below happened to carry a lowercase Latin fragment
         *  (ver., iPhone, px, a URL). Digits, symbols and punctuation are not
         *  letters, so "HP: 100" / "MENU ▶" still read as all-caps. */
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
    // utterance together. JA grammatical enders (particles を/が/て etc.) were
    // tried and REVERTED 2026-07-01: elliptical UI labels share those endings
    // (次へ, ひとりで, こんにちは) — re-add a member only with a field capture
    // of a cont=false band split on a line ending in it.
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
        var hasCaselessLetter = false
        for (c in trimmed) {
            if (c in OPENING_BRACKET_CHARS) delta++
            else if (c in CLOSING_BRACKET_CHARS) delta--
            if (c.isUpperCase()) hasUpper = true
            else if (c.isLowerCase()) hasLower = true
            // A letter that is neither upper nor lower belongs to a caseless
            // script (CJK, Hangul, Arabic, Thai, Devanagari…) — see isAllCaps.
            else if (c.isLetter()) hasCaselessLetter = true
        }
        val last = trimmed.lastOrNull()
        val first = trimmed.firstOrNull()
        return TextFlowCue(
            endsTerminal = last != null && last in TERMINAL_END_CHARS,
            endsContinuation = last != null && last in CONTINUATION_END_CHARS,
            startsLowercase = first != null && first.isLowerCase(),
            bracketDelta = delta,
            isAllCaps = hasUpper && !hasLower && !hasCaselessLetter,
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
     * Page-dominant line pitch: the best-supported cluster of consecutive
     * row-center steps that could plausibly BE line advances (see
     * [DOC_PITCH_MAX_SIZE_MULT]). Rows via [rowBands] over the whole pass'
     * boxes, so an inline label pair or a second column at the same Y folds
     * into one row instead of donating a bogus near-zero step. Ties between
     * equally-supported clusters go to the SMALLEST step (sorted iteration +
     * strict `>`), the conservative merge distance. Null when the page has
     * no rhythm worth borrowing. Pure — JVM-tested.
     */
    internal fun documentPitch(boxes: List<Rect>, orientation: TextOrientation): Int? {
        val rows = rowBands(boxes, orientation)
        if (rows.size < DOC_PITCH_MIN_SAMPLES + 1) return null
        val vertical = orientation == TextOrientation.VERTICAL
        val rects = rows.map { idxs -> unionRect(idxs.map { boxes[it] }) }
        val centers = rects.map { if (vertical) (it.left + it.right) / 2 else (it.top + it.bottom) / 2 }
        val sizes = rects.map { if (vertical) it.width() else it.height() }.sorted()
        val medianSize = sizes[sizes.size / 2]
        if (medianSize <= 0) return null
        val ceiling = (medianSize * DOC_PITCH_MAX_SIZE_MULT).toInt()
        val deltas = ArrayList<Int>(centers.size - 1)
        for (i in 1 until centers.size) {
            // Reading flow: top-to-bottom rows (horizontal), right-to-left
            // columns (vertical).
            val d = if (vertical) centers[i - 1] - centers[i] else centers[i] - centers[i - 1]
            if (d in medianSize..ceiling) deltas.add(d)
        }
        if (deltas.size < DOC_PITCH_MIN_SAMPLES) return null
        deltas.sort()
        var bestSeed = 0
        var bestCount = 0
        for (seed in deltas) {
            val count = deltas.count { kotlin.math.abs(it - seed) <= pitchTolerance(seed) }
            if (count > bestCount) {
                bestCount = count
                bestSeed = seed
            }
        }
        if (bestCount < DOC_PITCH_MIN_SAMPLES) return null
        val members = deltas.filter { kotlin.math.abs(it - bestSeed) <= pitchTolerance(bestSeed) }
        return members[members.size / 2]
    }

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
     *     scale gate for tail-shaped candidates (inline extent under
     *     [PITCH_TAIL_MAX_EXTENT_RATIO] of the group's — a digit/kana-tight
     *     trailing wrap at ratio ~0.51 is the confirmed false-split this
     *     exists to fix); a full-extent candidate at pitch merges through
     *     [SIZE_RATIO_CAP_CORROBORATED] instead.
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
        docPitch: Int? = null,
        recipe: GroupingRecipe = GroupingRecipe.Default,
    ): GroupDecision {
        shortAboveLongBlock(groupRect, candidate, orientation)?.let {
            return GroupDecision.NotGrouped("ext: $it")
        }
        return if (orientation == TextOrientation.VERTICAL) {
            samePassBlockExtrasVertical(
                groupRect, candidate, memberBoxes, bracketBalance, lastCue, docPitch, recipe,
            )
        } else {
            samePassBlockExtrasHorizontal(
                groupRect, candidate, groupAlignLeft, candidateAlignLeft, rtl,
                memberBoxes, bracketBalance, lastCue, candidateCue, spacedScript, docPitch, recipe,
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
        docPitch: Int?,
        recipe: GroupingRecipe,
    ): GroupDecision {
        val aH = a.height()
        val bH = b.height()
        val refH = maxOf(aH, bH)
        if (refH <= 0) return GroupDecision.NotGrouped("ext: refH=0")
        val dy = if (a.bottom <= b.top) b.top - a.bottom
                 else if (b.bottom <= a.top) a.top - b.bottom
                 else 0
        val bandThreshold = (refH * recipe.bandGapMultiplier).toInt()
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
        // Document-pitch prior: bootstrap-only stand-in for a pitch the group
        // cannot yet own (see DOC_PITCH_MIN_SAMPLES kdoc). Single-ROW groups
        // only — a multi-row group whose own spacing failed establishedPitch
        // had its chance and doesn't get to borrow the page's rhythm.
        val docStep = if (docPitch != null && groupPitch == null &&
            rowBands(memberBoxes, TextOrientation.HORIZONTAL).size == 1
        ) {
            val row = unionRect(memberBoxes)
            b.centerY() - (row.top + row.bottom) / 2
        } else null
        val docPitchOk = docPitch != null && docStep != null && docStep > 0 &&
            kotlin.math.abs(docStep - docPitch) <= pitchTolerance(docPitch)
        val pitchRef = groupPitch?.pitch ?: docPitch
        val pitchStr = when {
            groupPitch != null -> "$candidatePitch vs ${groupPitch.pitch}"
            docStep != null -> "doc $docStep vs $docPitch"
            else -> "n/a"
        }

        val lo = minOf(aH, bH)
        val hi = maxOf(aH, bH)
        val scaleOk = lo <= 0 || (hi - lo).toDouble() / lo <= recipe.sizeRatioCapCorroborated

        val textContinues = bracketBalance > 0 ||
            lastCue?.endsContinuation == true ||
            (spacedScript && lastCue != null && !lastCue.endsTerminal && candidateCue?.startsLowercase == true)

        // Tail shape: the full scale waiver is reserved for candidates that
        // look like a paragraph's dangling last line — much narrower than
        // the group's union width. A full-width candidate at coincidental
        // pitch still merges, but only through the corroborated cap: no
        // cap-free path without tail-shape evidence.
        val tailShaped = b.width() < a.width() * recipe.pitchTailMaxExtentRatio
        val pitchMerge = (pitchOk || docPitchOk) && (tailShaped || scaleOk)
        val pitchDetail =
            if (tailShaped) "tail w=${b.width()}<${recipe.pitchTailMaxExtentRatio}×${a.width()}, scale waived"
            else "scaleOk"

        val vgapThreshold = (refH * BLOCK_GAP_MULTIPLIER).toInt()
        return if (dy < vgapThreshold) {
            when {
                pitchMerge -> GroupDecision.Grouped(
                    "ext-pitch (dy=$dy, pitch $pitchStr ±${pitchTolerance(pitchRef!!)}px, $pitchDetail)"
                )
                firstLineIndent && scaleOk -> GroupDecision.Grouped(
                    "ext-indent (dy=$dy, indentΔ=$indentDelta ≈ 1em of refH=$refH)"
                )
                else -> GroupDecision.NotGrouped(
                    "ext: base zone, pitch=$pitchStr, tail=$tailShaped, indent=$firstLineIndent, scaleOk=$scaleOk"
                )
            }
        } else {
            when {
                pitchMerge -> GroupDecision.Grouped(
                    "ext-band-pitch (dy=$dy in [${vgapThreshold},${bandThreshold})px, pitch $pitchStr, $pitchDetail)"
                )
                recipe.bandTextSeeding && textContinues && scaleOk -> GroupDecision.Grouped(
                    "ext-band-cont (dy=$dy in [${vgapThreshold},${bandThreshold})px, brackets=$bracketBalance cont=${lastCue?.endsContinuation} lower=${candidateCue?.startsLowercase})"
                )
                else -> GroupDecision.NotGrouped(
                    "ext-band: dy=$dy, no corroboration (pitch=$pitchStr, tail=$tailShaped, cont=$textContinues" +
                        (if (textContinues && !recipe.bandTextSeeding) " [seeding disabled]" else "") +
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
        docPitch: Int?,
        recipe: GroupingRecipe,
    ): GroupDecision {
        val aW = a.width()
        val bW = b.width()
        val refW = maxOf(aW, bW)
        if (refW <= 0) return GroupDecision.NotGrouped("ext: refW=0")
        val dx = if (a.left <= b.right && b.right <= a.right) 0
                 else if (b.left <= a.right && a.right <= b.right) 0
                 else if (a.right <= b.left) b.left - a.right
                 else a.left - b.right
        val bandThreshold = (refW * recipe.bandGapMultiplier).toInt()
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
        // Document-pitch prior, bootstrap-only — see the horizontal path.
        val docStep = if (docPitch != null && groupPitch == null &&
            rowBands(memberBoxes, TextOrientation.VERTICAL).size == 1
        ) {
            val row = unionRect(memberBoxes)
            ((row.left + row.right) / 2) - ((b.left + b.right) / 2)
        } else null
        val docPitchOk = docPitch != null && docStep != null && docStep > 0 &&
            kotlin.math.abs(docStep - docPitch) <= pitchTolerance(docPitch)
        val pitchRef = groupPitch?.pitch ?: docPitch
        val pitchStr = when {
            groupPitch != null -> "$candidatePitch vs ${groupPitch.pitch}"
            docStep != null -> "doc $docStep vs $docPitch"
            else -> "n/a"
        }

        val lo = minOf(aW, bW)
        val hi = maxOf(aW, bW)
        val scaleOk = lo <= 0 || (hi - lo).toDouble() / lo <= recipe.sizeRatioCapCorroborated

        // No lowercase rule (vertical text is CJK) and no first-line indent
        // (out of scope for vertical — see FIRST_LINE_INDENT_MIN kdoc).
        val textContinues = bracketBalance > 0 || lastCue?.endsContinuation == true

        // Tail shape, vertical analog: a trailing COLUMN is a SHORTER one
        // (fewer characters down the column length) — see the horizontal
        // path for the rationale.
        val tailShaped = b.height() < a.height() * recipe.pitchTailMaxExtentRatio
        val pitchMerge = (pitchOk || docPitchOk) && (tailShaped || scaleOk)
        val pitchDetail =
            if (tailShaped) "tail h=${b.height()}<${recipe.pitchTailMaxExtentRatio}×${a.height()}, scale waived"
            else "scaleOk"

        val hgapThreshold = (refW * BLOCK_GAP_MULTIPLIER).toInt()
        return if (dx < hgapThreshold) {
            when {
                pitchMerge -> GroupDecision.Grouped(
                    "ext-pitch (dx=$dx, pitch $pitchStr ±${pitchTolerance(pitchRef!!)}px, $pitchDetail)"
                )
                else -> GroupDecision.NotGrouped(
                    "ext: base zone, pitch=$pitchStr, tail=$tailShaped, scaleOk=$scaleOk"
                )
            }
        } else {
            when {
                pitchMerge -> GroupDecision.Grouped(
                    "ext-band-pitch (dx=$dx in [${hgapThreshold},${bandThreshold})px, pitch $pitchStr, $pitchDetail)"
                )
                recipe.bandTextSeeding && textContinues && scaleOk -> GroupDecision.Grouped(
                    "ext-band-cont (dx=$dx in [${hgapThreshold},${bandThreshold})px, brackets=$bracketBalance cont=${lastCue?.endsContinuation})"
                )
                else -> GroupDecision.NotGrouped(
                    "ext-band: dx=$dx, no corroboration (pitch=$pitchStr, tail=$tailShaped, cont=$textContinues" +
                        (if (textContinues && !recipe.bandTextSeeding) " [seeding disabled]" else "") +
                        ", scaleOk=$scaleOk)"
                )
            }
        }
    }

    /**
     * Index of a line that sits BETWEEN [anchor] (the group's last visual row)
     * and [candidate] in reading flow, blocking the merge — or null when the
     * span between them is clear.
     *
     * **Why this is needed at all.** The block gap is an edge-to-edge INK
     * measurement, so it carries no information about how many rows were
     * skipped. When line boxes nearly touch, a two-row reach measures the same
     * as a one-row advance: the `text_sample` capture (2026-07-20 grouping run,
     * all 3 ML Kit reps) has rows at [359,516] / [516,636] / [658,816] — the
     * middle row fails the bare scale cap (ratio 0.31) and opens its own group,
     * then row 3 reaches back OVER it to row 1 at dy=142 < band 205 and merges.
     * The group's text came out as "This is a text sample testing,." with "for
     * translation" stranded in its own group, and row-1's bounds spanning the
     * stranded row so the two overlays drew on top of each other. That is a
     * text-CORRUPTING failure, not a mis-grouping: no downstream stage can
     * recover the dropped row or the reading order.
     *
     * **This closes a BAND of that failure, not the class** — see the known
     * miss below. Read that before relying on it.
     *
     * The multi-group walk's reach-back is deliberate and load-bearing (a
     * foreign-column line interleaving in sort order must not break a
     * paragraph — `vietnameseWikipediaCapture_sidebarInterleavesSixLineBody`),
     * but every pinned case reaches across a line that is x-DISJOINT from the
     * body. Nothing distinguished that from reaching across a line in the same
     * column. This does: an interposer only blocks when it is actually in the
     * way, i.e. all three of
     *  - its block-axis CENTER lies strictly inside the open span between the
     *    two (center, not containment, so a pixel of glyph overlap at either
     *    end doesn't silently disarm the guard);
     *  - its inline span overlaps the pair's combined inline span by at least
     *    half the smaller extent (the [rowBands] overlap convention) — a
     *    sidebar in another column overlaps by nothing and never blocks;
     *  - its per-line scale is compatible with EITHER endpoint at
     *    [sizeRatioCap], i.e. it is plausibly a member of the same block.
     *
     * That last clause is the **ruby/furigana exemption**. Ruby sits exactly
     * here — between consecutive body rows (to the right of its base column for
     * vertical), overlapping them inline — so a scale-blind guard would refuse
     * every body-row merge in Japanese text that carries ruby. Real ruby runs
     * about half the base size, which measures ≥1.0 against a glyph-tight base
     * box (the `scaleCap_furiganaScale_stillSplits` fixture is 1.67) — outside
     * the cap, so it never blocks.
     *
     * ## KNOWN MISS (Codex adversarial review, 2026-07-24)
     *
     * The exemption and the leak are the same window. An interposer is exempted
     * whenever its per-line size differs from BOTH endpoints by more than
     * [sizeRatioCap] — which at 0.50 means "shorter than ~⅔ of its neighbours".
     * A glyph-starved same-font row lands there: a Latin row carrying no
     * ascender or descender measures roughly half an x-height-plus-ascender row
     * of the SAME font. Such a row strands (the bare cap rejects it) and is then
     * exempted from blocking, so the rows above and below bridge over it — the
     * original corruption, unguarded. Measured boundary (JVM sweep, outer rows
     * 95px): blocks at interposer height ≥64 (ratio ≤0.48), leaks at ≤63 (ratio
     * ≥0.51); horizontal and vertical leak identically. Pinned by
     * `interposition_knownMiss_glyphStarvedRow_stillBridges`.
     *
     * This is not fixable by moving [sizeRatioCap]: the exemption is measured on
     * glyph-tight box extent, whose same-font content noise EXCEEDS the cap (the
     * `text_sample` 0.31 and `manga_cogen_02` 0.32 captures are the same
     * statistic misfiring one tier up, in the bare scale gate). Widening the cap
     * to cover glyph-starved rows starts blocking ruby; narrowing it leaks more.
     * The close is a scale measurement that does not ride box extent — the
     * char-tier statistic in [GlyphScale] — which also removes the stranding
     * that creates the bridge in the first place. Until then this guard is a
     * partial backstop and should be described as one.
     *
     * **Untracked effect:** the verdict depends on the interposer being detected
     * in THIS pass. A faint row that OCR intermittently drops will flip the
     * bridge between frames, which changes group rects that [Classification]
     * matches against. Not priced — needs a device run on the live surface.
     *
     * Members of the group and the candidate itself are excluded by geometry
     * alone: earlier members lie above the anchor, an inline neighbour's center
     * lies inside the anchor's own span, and the candidate's center lies past
     * the far end of the span. Degenerate (zero-extent) boxes fail the overlap
     * test. Same-pass walk only — cross-frame callers never see this.
     *
     * [sizeRatioCap] comes from the caller's [GroupingRecipe] rather than the
     * constant, so a harness sweep of `sizeRatioCapCorroborated` moves this
     * exemption too — the recipe's "every field is WIRED" contract covers the
     * guard, not just the evidence layer.
     */
    internal fun interposingLine(
        boxes: List<Rect>,
        anchor: Rect,
        candidate: Rect,
        orientation: TextOrientation,
        sizeRatioCap: Double = SIZE_RATIO_CAP_CORROBORATED,
    ): Int? {
        val vertical = orientation == TextOrientation.VERTICAL
        // The open span between the pair, in reading flow: below the anchor for
        // horizontal rows, LEFT of it for vertical columns (right-to-left).
        val lo = if (vertical) candidate.right else anchor.bottom
        val hi = if (vertical) anchor.left else candidate.top
        if (hi <= lo) return null            // touching, overlapping, or reversed
        val refLo = if (vertical) minOf(anchor.top, candidate.top)
                    else minOf(anchor.left, candidate.left)
        val refHi = if (vertical) maxOf(anchor.bottom, candidate.bottom)
                    else maxOf(anchor.right, candidate.right)
        val refExtent = refHi - refLo
        if (refExtent <= 0) return null
        val anchorSize = if (vertical) anchor.width() else anchor.height()
        val candSize = if (vertical) candidate.width() else candidate.height()
        for (j in boxes.indices) {
            val b = boxes[j]
            val center = if (vertical) (b.left + b.right) / 2 else (b.top + b.bottom) / 2
            if (center <= lo || center >= hi) continue
            val bLo = if (vertical) b.top else b.left
            val bHi = if (vertical) b.bottom else b.right
            val bExtent = bHi - bLo
            if (bExtent <= 0) continue
            val overlap = minOf(refHi, bHi) - maxOf(refLo, bLo)
            if (overlap < 0.5f * minOf(bExtent, refExtent)) continue
            val bSize = if (vertical) b.width() else b.height()
            if (bSize <= 0) continue
            if (scaleCompatible(bSize, anchorSize, sizeRatioCap) ||
                scaleCompatible(bSize, candSize, sizeRatioCap)
            ) return j
        }
        return null
    }

    /** `(hi - lo) / lo` within [cap] — the "could plausibly be the same block"
     *  test used by [interposingLine]. */
    private fun scaleCompatible(a: Int, b: Int, cap: Double): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if (lo <= 0) return false
        return (hi - lo).toDouble() / lo <= cap
    }

    /**
     * HALF-BLENDED size ratio: each extent averaged with its
     * [CharClassCoverage]-normalized value before the `(hi - lo) / lo`
     * compare, or null when either line has no prediction. Half strength is
     * deliberate — "trust the model halfway": full normalization lets the
     * prediction multiply an apparent ratio by up to 2.0× (an x-height-only
     * line against a full-span line), enough for its own error sources
     * (stylized fonts that reassign letter classes, pixel quantization on
     * short lines) to hide a real ~1.4× size difference under the cap; the
     * blend caps that leverage at 1.5×. Corpus (2026-07-25, 352 pairs):
     * legitimate rescues carry 4–10× margin and survive the halving — all
     * six Latin rescues hold — while the observed bad merges needed nearly
     * the full correction and die.
     *
     * Consumers accept when the RAW ratio clears the cap, OR when raw is
     * within [SIZE_RATIO_CAP_CORROBORATED] AND this blend clears the cap —
     * permissive-only (a wrong prediction can rescue a same-font pair, never
     * split one), and the raw ceiling means a gap beyond anything the
     * corroborated tier would accept is trusted as a real size difference no
     * matter what the letters say. The ceiling equals the corroborated cap,
     * so at 0.50-tier sites the blend is definitionally moot — it lives only
     * in the bare-tier gates. Deliberately absent from cross-frame paths (no
     * per-line text) and [interposingLine] (its cap qualifies a BLOCKER,
     * where "more forgiving" would mean "more blocking").
     */
    private fun blendedSizeRatio(aExt: Int, bExt: Int, aCov: Double?, bCov: Double?): Double? {
        if (aCov == null || bCov == null) return null
        val ba = (aExt + aExt / aCov) / 2.0
        val bb = (bExt + bExt / bCov) / 2.0
        val lo = minOf(ba, bb)
        if (lo <= 0.0) return null
        return (maxOf(ba, bb) - lo) / lo
    }

    /** The bare-tier scale predicate: raw within [cap], or raw within the
     *  [SIZE_RATIO_CAP_CORROBORATED] ceiling with the blend within [cap].
     *  Degenerate extents (lo <= 0) stay compatible, mirroring the historic
     *  gate behavior. */
    private fun scaleOkWithBlend(
        aExt: Int,
        bExt: Int,
        aCov: Double?,
        bCov: Double?,
        cap: Double,
    ): Boolean {
        val lo = minOf(aExt, bExt)
        if (lo <= 0) return true
        val raw = (maxOf(aExt, bExt) - lo).toDouble() / lo
        if (raw <= cap) return true
        if (raw > SIZE_RATIO_CAP_CORROBORATED) return false
        return (blendedSizeRatio(aExt, bExt, aCov, bCov) ?: Double.MAX_VALUE) <= cap
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
     * is always the right candidate" assumption. That reach-back is bounded
     * by [interposingLine]: it may pass a line in another column, never one
     * standing in the same column between the two.
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
     * - [documentPitchPrior] : opt-in page-rhythm bootstrap evidence for the
     *   corroborated gap band (see [documentPitch]) — the file-import
     *   pipeline's document bias. Off for game/live surfaces: menus are
     *   rhythmic too, and there the band's refusal is the wanted behavior.
     * - [recipe] : tunable values for the evidence layer (see
     *   [GroupingRecipe]); the default is byte-identical to production.
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
        documentPitchPrior: Boolean = false,
        recipe: GroupingRecipe = GroupingRecipe.Default,
        /** Per-line [CharClassCoverage] predictions for the scale gates —
         *  see [scaleOkWithBlend]. Null (bare-rect callers, tests) runs the raw
         *  statistic alone, like [texts]/[cues]. */
        coverages: List<Double?>? = null,
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
        require(coverages == null || coverages.size == boxes.size) {
            "coverages must match boxes length when provided"
        }
        if (boxes.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Int>>()
        val orientChar = orientation.name[0]
        val docPitch = if (documentPitchPrior) documentPitch(boxes, orientation) else null
        if (documentPitchPrior && logDecisions) {
            android.util.Log.d(
                "DetectionLog",
                "[group:$orientChar] doc-pitch=" + (docPitch?.let { "${it}px" } ?: "none")
            )
        }
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
                // The group side of the scale compare is groupRect, whose
                // cross extent is the LAST member line's (see the union
                // construction above) — so its coverage prediction is the
                // last member's, matching extent to text.
                val covPrev = coverages?.get(candidateGroup.last())
                val covCand = coverages?.get(idx)
                val baseMerged = wouldGroup(
                    groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl = rtl,
                    aCoverage = covPrev, bCoverage = covCand,
                )
                val extras = if (baseMerged) null else samePassBlockExtras(
                    groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl,
                    memberBoxes = candidateGroup.map { boxes[it] },
                    bracketBalance = cues?.let { cs -> candidateGroup.sumOf { cs[it].bracketDelta } } ?: 0,
                    lastCue = cues?.get(candidateGroup.last()),
                    candidateCue = cues?.get(idx),
                    spacedScript = spacedScript,
                    docPitch = docPitch,
                    recipe = recipe,
                )
                val wouldMerge = baseMerged || extras is GroupDecision.Grouped
                // A merge may not reach ACROSS a line that lies between the two
                // — the gap alone can't tell a one-row advance from a two-row
                // skip. Checked only on a prospective merge, so the scan costs
                // nothing on the overwhelmingly common refusal. See
                // [interposingLine].
                val blocker = if (wouldMerge) {
                    interposingLine(
                        boxes, groupRect, lineBox, orientation, recipe.sizeRatioCapCorroborated,
                    )
                } else null
                val groupMerged = wouldMerge && blocker == null
                if (logDecisions) {
                    val decision = groupDecision(
                        groupRect, lineBox, orientation, groupAlignLeft, candidateAlignLeft, rtl = rtl,
                        aCoverage = covPrev, bCoverage = covCand,
                    )
                    val prevSnippet =
                        (texts?.get(candidateGroup.last()) ?: "").take(24).replace('\n', ' ')
                    val candSnippet =
                        (texts?.get(idx) ?: "").take(24).replace('\n', ' ')
                    val verdict = if (groupMerged) "MERGE" else "SPLIT"
                    val extReason = extras?.let { " | ${it.reason}" } ?: ""
                    val blockReason = blocker?.let { j ->
                        val snippet = (texts?.get(j) ?: "").take(24).replace('\n', ' ')
                        " | INTERPOSED by ${rectStr(boxes[j])} \"$snippet\" (merge would reach across it)"
                    } ?: ""
                    android.util.Log.d(
                        "DetectionLog",
                        "[group:$orientChar] $verdict g$gi prev=${rectStr(groupRect)} \"$prevSnippet\" cand=${rectStr(lineBox)} \"$candSnippet\" :: ${decision.reason}$extReason$blockReason"
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
        documentPitchPrior: Boolean = false,
        /** The grouping algorithm to run — the decision middle between the
         *  shared shell's normalization (upstream) and reading-order join /
         *  bounds / orientation vote / alignment (downstream). Null picks the
         *  production strategy for the surface (see below). Research
         *  instruments (the grouping harness catalog) pass their own;
         *  production call sites never do. */
        strategy: GroupingStrategy? = null,
        /** Same-angle admission cap for the shell's slant clustering — the
         *  harness sweeps it; production always runs the default. */
        angleToleranceDeg: Float = DeskewGeometry.DEFAULT_CLUSTER_CAP_DEG,
    ): List<LayoutGroup> {
        if (regions.isEmpty()) return emptyList()
        val profile = SourceLanguageProfiles.forCode(sourceLang)
        val ctx = GroupingContext(
            sourceLang = sourceLang,
            screenshotWidthInRegionSpace = screenshotWidthInRegionSpace,
            rtl = profile?.textDirection == TextDirection.RTL,
            spacedScript = profile?.wordsSeparatedByWhitespace != false,
            logDecisions = logDecisions,
        )
        // [FlowGraphStrategy] is production on EVERY surface — screen capture,
        // live, camera, select-to-translate, and declared documents. On the
        // Thor pass results-1785050508550 (131 seeds) it halves shredding
        // against the default, 32 blocks to 16, and wins every image kind.
        //
        // Note for the import surface: the corpus has zero `# surface: import`
        // seeds, so documents are the one surface with no corpus evidence
        // either way, and [documentPitchPrior] no longer reaches the default
        // path — FlowGraph's per-chain modal pitch is its own answer to the
        // bootstrap hole that prior was built for. Import seeds are the way to
        // check that claim rather than assume it.
        val active = strategy ?: FlowGraphStrategy()
        // ONE grouping path for every horizontal angle, 0° included: measured
        // angles cluster uniformly ([DeskewGeometry.clusterByAngle]); each
        // slanted cluster runs the SAME strategy on synthetic upright copies
        // in its deskewed frame; the measured-upright mass runs the strategy
        // directly (its frame is the identity); and UNMEASURED boxes
        // ([OcrBox.angleUnmeasured] — the producer withheld the angle) join a
        // slanted cluster on positional evidence, confirmed only when the
        // strategy actually groups them with a measured member. The AABB
        // kernel never sees a slanted envelope, and a mixed-length slanted
        // sentence — long words measured, short words unmeasured — reunites
        // instead of splitting at a bucket boundary.
        val proposed = if (regions.none { it.box.isRotated }) {
            // Fast path, an OPTIMIZATION not a semantic fork: with no measured
            // slant there are no slanted clusters, so [groupWithAngles]
            // reduces to exactly this one strategy run over the regions in
            // input order. Kept because it is provably byte-identical for
            // every upright-only frame (the corpus fence's proof obligation).
            active.group(regions, ctx)
        } else {
            groupWithAngles(regions, active, ctx, sourceLang, angleToleranceDeg)
        }
        // Join a group's lines with a space only for whitespace-delimited
        // languages; CJK/Thai (wordsSeparatedByWhitespace = false) get no
        // separator so the merged paragraph reads naturally AND the translator
        // receives clean source (`今日はいい天気` not `今日は いい天気`). Default to
        // a space when the profile is unknown — only languages we KNOW omit
        // inter-word spaces drop it, so every other language keeps prior behavior.
        val lineJoin = if (ctx.spacedScript) " " else ""
        return orderByReading(proposed.mapNotNull { buildLayoutGroup(it, lineJoin) }, ctx.rtl)
    }

    /**
     * Global reading order over the final groups — the ONE emission-order
     * policy, applied to every frame on every surface. Replaces the old
     * emission order (strategy output, then vertical groups, then slant
     * clusters), which segregated the list by KIND: an upright group low on
     * the page emitted before an angled or tategaki group above it.
     *
     * Policy: horizontal bands built by top-proximity — a group joins the
     * current band when its top sits within half the shorter height of the
     * band's first member (local, so a tall sidebar can't swallow the page
     * into one band); bands read top-to-bottom; within a band, left-to-right
     * — right-to-left when the source is RTL or the band contains tategaki
     * columns (columns read right-to-left; an enumerated fact of the
     * language matrix). Slanted groups participate by their screen AABB.
     */
    private fun orderByReading(groups: List<LayoutGroup>, rtl: Boolean): List<LayoutGroup> {
        if (groups.size <= 1) return groups
        val byTop = groups.sortedBy { it.bounds.top }
        val bands = mutableListOf<MutableList<LayoutGroup>>()
        for (g in byTop) {
            val band = bands.lastOrNull()
            val ref = band?.first()
            if (ref != null &&
                g.bounds.top <= ref.bounds.top +
                minOf(g.bounds.height(), ref.bounds.height()) / 2
            ) {
                band.add(g)
            } else {
                bands.add(mutableListOf(g))
            }
        }
        return bands.flatMap { band ->
            val columnar = rtl || band.any { it.orientation == TextOrientation.VERTICAL }
            if (columnar) band.sortedByDescending { it.bounds.right }
            else band.sortedBy { it.bounds.left }
        }
    }

    /** Extract boxes + align-left hints + text-flow cues from sorted regions,
     *  run the kernel, and remap index-groups back to region lists. */
    /** [DefaultGroupingStrategy]'s per-orientation walk: extract boxes,
     *  align-left hints and text-flow cues from [sorted] regions, run
     *  [groupBoxesOnePass], and map index-groups back to region lists. */
    internal fun groupRegions(
        sorted: List<RecognizedRegion>,
        orientation: TextOrientation,
        ctx: GroupingContext,
        recipe: GroupingRecipe,
    ): List<List<RecognizedRegion>> {
        if (sorted.isEmpty()) return emptyList()
        val boxes = sorted.map { it.box.bounds }
        val alignLefts: List<Int?> = if (orientation == TextOrientation.HORIZONTAL) {
            sorted.map { region -> region.lines.firstOrNull()?.let { effectiveAlignLeft(it) } ?: region.box.bounds.left }
        } else {
            List(sorted.size) { null }
        }
        val texts = if (ctx.logDecisions) sorted.map { it.text } else null
        val cues = sorted.map { textFlowCue(it.text) }
        val coverages = sorted.map { CharClassCoverage.coverage(it.text, orientation) }
        val idxGroups = groupBoxesOnePass(
            boxes, alignLefts, orientation, ctx.logDecisions, texts, ctx.rtl, cues, ctx.spacedScript,
            recipe.documentPitchPrior, recipe, coverages,
        )
        return idxGroups.map { idxs -> idxs.map { sorted[it] } }
    }

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
     *
     * **Horizontal groups only.** [isMenuLike] carries a horizontal axis
     * convention throughout (see its kdoc), and a vertical group would be judged
     * on transposed axes: [rowBands] returns COLUMNS, whose `left`/`right` are the
     * cross-flow edges and whose `height` is the column LENGTH, not a row
     * thickness. In the 2026-07-20 corpus run that tolerance came out at 359–447px
     * against blocks only ~210–250px wide — larger than the thing it measures, so
     * the clustering test could not fail, and the split fired 0 times across 99
     * vertical groups. Where the arithmetic does flip (short columns spread wide),
     * it shreds vertical prose, and the `parentLeft`/`parentRight` pin is wrong for
     * vertical anyway: sibling columns share tops and bottoms, so every split
     * column would inherit the block's full x-extent and the overlays would stack.
     * A faithful transposition is possible (cross-flow tolerance = column
     * thickness, clustering on top/bottom, narrow gate against screen HEIGHT), but
     * genuine vertical-text menus — items that are each their own column — are
     * rare next to vertical prose, and abstaining is a no-op on the whole corpus
     * while a transposition would need the screenshot height plumbed and would
     * give up equal-length vertical menus regardless. Cost of abstaining: a
     * merged vertical menu stays one group (one concatenated translation), which
     * is what the pre-existing arithmetic produced for most shapes anyway. The
     * common JP-game menu — a vertical STACK of horizontal items — is a horizontal
     * group and is unaffected.
     */
    internal fun splitMenuGroups(
        groups: List<List<RecognizedRegion>>,
        screenWidth: Float,
        logDecisions: Boolean = false,
    ): List<ProposedGroup> = groups.flatMap { group ->
        val orientation = group.firstOrNull()?.orientation ?: TextOrientation.HORIZONTAL
        // Groups are orientation-homogeneous by construction (DefaultGroupingStrategy
        // partitions before grouping and concatenates after), so the first region's
        // orientation speaks for the group.
        if (orientation == TextOrientation.VERTICAL) return@flatMap listOf(ProposedGroup(group))
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
                ProposedGroup(idxs.map { group[it] }, parentLeft = groupLeft, parentRight = groupRight)
            }
        } else {
            listOf(ProposedGroup(group))
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
     *  ⅓ screen and left/right edges don't both cluster (a justified paragraph does).
     *
     *  **HORIZONTAL text only.** Every term here is horizontal by convention:
     *  `left`/`right` are where a row starts and ends along the text direction, and
     *  `height` is the row's cross-flow thickness, used as the edge-clustering
     *  tolerance. For vertical text those roles transpose, and [splitMenuGroups]
     *  refuses to call this for vertical groups rather than judge them on the wrong
     *  axes — see its kdoc. */
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

    /**
     * Group one angle cluster in its deskewed frame. Singleton clusters skip
     * the strategy entirely — v1's standalone emission, byte-identical.
     * Multi-member clusters run the strategy on synthetic upright copies
     * (`copy(box = upright(deskew(...)))` — the ONLY field the strategies
     * read geometrically) and swap the ORIGINAL instances back before
     * assembly: deskewed geometry must never escape, because the group's
     * lines pass verbatim to every downstream consumer. Identity map, never
     * data-class equality — byte-identical duplicate regions collide under
     * equality (see the note in the harness's LabelStackStrategy).
     */
    /**
     * The unified angle shell (reached only when some measured slant exists;
     * see the fast path in [analyze]):
     *  1. MEASURED regions — every angle, 0 included — cluster uniformly.
     *     No pre-filtering: the source-script decision is group-level
     *     everywhere (strategy-internal for every strategy run; the
     *     letterless-singleton check on the standalone emission below), so
     *     letterless lines live or die WITH their neighbors, slanted exactly
     *     as upright.
     *  2. Each SLANTED cluster (θ̄ ≠ 0) runs the strategy on synthetic
     *     upright copies in its deskewed frame, at the real screen width.
     *     UNMEASURED regions are admitted provisionally by position
     *     ([DeskewGeometry.admitUnmeasured]) and kept only when the strategy
     *     actually groups them with a measured member — over-admission costs
     *     nothing (the region falls back to the upright pool), so admission
     *     errs generous.
     *  3. The upright pool — measured-0 cluster members (including any
     *     light-slant members the clusterer absorbed within its designed
     *     tolerance) plus unclaimed unmeasured regions — runs the strategy
     *     directly, in ORIGINAL input order (order feeds the walks).
     * Emission: pool groups then cluster groups; [orderByReading] owns the
     * user-visible order downstream.
     */
    private fun groupWithAngles(
        regions: List<RecognizedRegion>,
        active: GroupingStrategy,
        ctx: GroupingContext,
        sourceLang: String,
        angleToleranceDeg: Float,
    ): List<ProposedGroup> {
        // No pre-filter on measured-slant regions: the source-script decision
        // is GROUP-level everywhere else (the strategies drop letterless
        // GROUPS, so a letterless line survives by grouping with letter-
        // bearing neighbors), and pre-dropping slanted letterless lines broke
        // that — garble that lived inside letter groups upright died the
        // moment stitching made it slanted. Letterless slanted singletons
        // fall back to the pool below and meet the same group-level filter
        // as any upright region; framed groups are strategy output and
        // already filtered.
        val unmeasured = regions.filter { it.box.angleUnmeasured }
        val measured = regions.filter { !it.box.angleUnmeasured }
        val clusters = DeskewGeometry.clusterByAngle(
            measured.map { it.box.angleDeg },
            measured.map { it.box.orientedWidth },
            measured.map { it.box.orientedHeight },
            angleToleranceDeg,
        )
        val claimed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        val unplaced = mutableListOf<RecognizedRegion>()
        val clusterGroups = mutableListOf<ProposedGroup>()
        for (cluster in clusters) {
            if (cluster.angleDeg == 0f) continue // the measured-upright mass runs in the pool
            val members = cluster.memberIndices.map { measured[it] }
            // Frame anchor: the MEASURED members' AABB-union center — a pure
            // function of the cluster, independent of admission outcomes, so
            // the frame (and everything derived in it) is deterministic.
            val union = Rect(members[0].box.bounds)
            for (m in members.drop(1)) union.union(m.box.bounds)
            val frame = AngleFrame(cluster.angleDeg, union.centerX(), union.centerY())
            val memberBoxes = members.map { it.box }
            val admitted = unmeasured.filter {
                it !in claimed && DeskewGeometry.admitUnmeasured(it.box, frame, memberBoxes)
            }
            if (members.size == 1 && admitted.isEmpty()) {
                if (members[0].text.any { isSourceLangChar(it, sourceLang) }) {
                    // Lone slanted region, nothing to try: the v1 standalone
                    // singleton, byte-compatible with the old bypass.
                    clusterGroups.add(ProposedGroup(members))
                } else {
                    // Letterless: the pool's group-level filter decides —
                    // grouped with letter neighbors it lives (as upright
                    // garble always has), isolated it drops (as v1 dropped
                    // non-source rotated singletons).
                    unplaced.add(members[0])
                }
                continue
            }
            val outcome = runFramed(members, admitted, frame, active, ctx)
            clusterGroups.addAll(outcome.groups)
            claimed.addAll(outcome.claimed)
            unplaced.addAll(outcome.unplaced)
        }
        val poolable = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        // Fallbacks resolve against the FINAL claimed state — a region can be
        // rejected by an early cluster and confirmed by a later one, and the
        // early fallback must not also route it through the pool (the
        // exactly-once invariant; outside-review finding, twin had it right).
        for (r in unplaced) if (r !in claimed) poolable.add(r)
        for (cluster in clusters) {
            if (cluster.angleDeg != 0f) continue
            for (i in cluster.memberIndices) poolable.add(measured[i])
        }
        for (u in unmeasured) if (u !in claimed) poolable.add(u)
        val pool = regions.filter { it in poolable }
        val poolGroups = if (pool.isEmpty()) emptyList() else active.group(pool, ctx)
        return poolGroups + clusterGroups
    }

    private class FramedOutcome(
        val groups: List<ProposedGroup>,
        /** Admitted unmeasured regions a confirmed group kept. */
        val claimed: List<RecognizedRegion>,
        /** Members returning to the upright pool: everyone from groups with
         *  no carried-slant evidence, plus strategy no-shows. The pool is the
         *  universal fallback — the cluster boundary must never strand or
         *  silently drop a region its neighbors would have kept alive. */
        val unplaced: List<RecognizedRegion>,
    )

    /**
     * One slanted cluster's framed strategy run over measured [members] plus
     * provisionally [admitted] unmeasured regions. The real screen width
     * passes through: deskew is an isometry, so an in-frame row extent is a
     * genuine pixel length and the width-keyed logic (menu split, FlowGraph's
     * list rows) stays dimensionally sound. Output triage:
     *  - a group with NO measured member is unconfirmed — its regions return
     *    to the upright pool (positional admission was provisional; only
     *    grouping WITH a measured member is slant evidence);
     *  - a lone measured region without pins keeps the v1 standalone-singleton
     *    emission (byte-compatible: the framed round-trip is the identity for
     *    a group that merged with nothing);
     *  - everything else emits framed, with FRAME-SPACE pins consumed by
     *    [buildLayoutGroup] before the back-rotation.
     */
    private fun runFramed(
        members: List<RecognizedRegion>,
        admitted: List<RecognizedRegion>,
        frame: AngleFrame,
        active: GroupingStrategy,
        ctx: GroupingContext,
    ): FramedOutcome {
        val all = members + admitted
        val synth = all.map { it.copy(box = OcrBox.upright(DeskewGeometry.deskew(it.box, frame))) }
        val backMap = java.util.IdentityHashMap<RecognizedRegion, RecognizedRegion>()
        for (i in synth.indices) backMap[synth[i]] = all[i]
        val framedGroups = active.group(synth, ctx)
        // Defensive: a strategy that returns instances it wasn't given (a
        // copy() somewhere) can't be swapped back, and deskewed coordinates
        // must not escape. Degrade to v1 singletons for the measured members,
        // loudly; admitted regions fall back to the pool unclaimed.
        if (framedGroups.any { g -> g.regions.any { it !in backMap } }) {
            android.util.Log.w(
                "LayoutAnalyzer",
                "angle-cluster fallback: ${active.javaClass.simpleName} returned unknown region instances",
            )
            return FramedOutcome(members.map { ProposedGroup(listOf(it)) }, emptyList(), emptyList())
        }
        val groups = mutableListOf<ProposedGroup>()
        val claimed = mutableListOf<RecognizedRegion>()
        val fallback = mutableListOf<RecognizedRegion>()
        val placed = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<RecognizedRegion, Boolean>(),
        )
        for (g in framedGroups) {
            val originals = g.regions.map { backMap.getValue(it) }
            placed.addAll(originals)
            // Confirmation = CARRIED-SLANT evidence: a group stays in the
            // frame only if some member actually carries an angle. Groups of
            // only absorbed measured-0s and provisional admits have no slant
            // evidence — every member falls back to the upright pool, where
            // its original neighbors are (never DISCARD: two fence
            // regressions came from members stranded on this boundary — the
            // tilted-photo mass loss, then the FF-VI garble fragmenting away
            // from the letter-bearing line-mates that kept it alive through
            // the source filter).
            val carried = originals.count { it.box.isRotated }
            when {
                carried == 0 -> fallback.addAll(originals)
                originals.size == 1 && g.parentLeft == null && g.parentRight == null ->
                    groups.add(ProposedGroup(originals))
                else -> {
                    groups.add(
                        ProposedGroup(originals, g.parentLeft, g.parentRight, frame = frame),
                    )
                    for (o in originals) if (o.box.angleUnmeasured) claimed.add(o)
                }
            }
        }
        // Strategy no-shows (its internal filter dropped a letterless group)
        // fall back the same way; the pool's own run decides their fate with
        // their neighbors present, exactly as the pre-partition pipeline did.
        fallback.addAll(all.filter { it !in placed && !it.box.angleUnmeasured })
        return FramedOutcome(groups, claimed, fallback)
    }

    private fun buildLayoutGroup(sg: ProposedGroup, lineJoin: String): LayoutGroup? {
        val raw = sg.regions
        if (raw.isEmpty()) return null
        val verticalCount = raw.count { it.orientation == TextOrientation.VERTICAL }
        val orientation =
            if (verticalCount > raw.size / 2) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
        // Order regions in reading order before joining (rows top-to-bottom, within a
        // row left-to-right for horizontal), so a same-line inline pair like
        // "Gust Area Damage:" + "4 (every…)" joins by position — robust to OCR
        // top-edge jitter that could otherwise put the value ahead of its label.
        // Every geometric decision reads ONE rect list: the DESKEWED rects for
        // a framed group — screen-space banding scrambles slanted stacks
        // (their AABBs overlap in screen-Y, so rowBands merges distinct lines
        // and left-sorts them) and screen-space alignment drifts by pitch·sinθ
        // per row — and the plain screen bounds otherwise.
        val frame = sg.frame
        val geomRects =
            if (frame != null) raw.map { DeskewGeometry.deskew(it.box, frame) }
            else raw.map { it.box.bounds }
        val regions = readingOrderIndices(geomRects, orientation).map { raw[it] }
        val text = regions.joinToString(lineJoin) { it.text }.trim()
        if (text.isBlank()) return null
        val lines = regions.flatMap { it.lines }
        if (frame != null) {
            // Framed group: oriented union in-frame, exact AABB back in screen
            // space — bounds.center == the oriented rect's center (±0.5px) and
            // bounds ⊇ the drawn footprint: the two properties the renderer,
            // pinhole fill, gate exclusion, and debug overlay all pin on.
            // Framed members are HORIZONTAL by the producer invariant. Pins
            // are frame-space u-values (see groupCluster) and clamp the
            // in-frame union's reading-axis extent BEFORE the back-rotation —
            // the pinned union is still an oriented rect, so the premise holds.
            val union = Rect(geomRects[0])
            for (r in geomRects.drop(1)) union.union(r)
            val pinned = Rect(
                sg.parentLeft ?: union.left, union.top,
                sg.parentRight ?: union.right, union.bottom,
            )
            val alignment = classifyGroupAlignment(
                lines.map { DeskewGeometry.deskew(it.box, frame) },
                lines.map { effectiveAlignLeftFramed(it, frame) },
            )
            return LayoutGroup(
                text, lines, DeskewGeometry.screenAabbOf(pinned, frame), orientation, alignment,
                angleDeg = frame.angleDeg,
                orientedWidth = pinned.width().toFloat(),
                orientedHeight = pinned.height().toFloat(),
            )
        }
        val left = sg.parentLeft ?: geomRects.minOf { it.left }
        val right = sg.parentRight ?: geomRects.maxOf { it.right }
        val bounds = Rect(left, geomRects.minOf { it.top }, right, geomRects.maxOf { it.bottom })
        val alignment =
            if (orientation == TextOrientation.VERTICAL) TextAlignment.LEFT else classifyGroupAlignment(lines)
        // A standalone rotated singleton carries its slant onto the group (a
        // single-member cluster — emitted frameless so this path stays
        // byte-identical to v1). Guarded on no parent pins: pinned bounds are
        // wider than the region's own AABB, which would break the renderer's
        // center-pin premise (group bounds == the oriented rect's exact AABB).
        val rot = if (sg.parentLeft == null && sg.parentRight == null) {
            raw.singleOrNull()?.box?.takeIf { it.isRotated }
        } else null
        return LayoutGroup(
            text, lines, bounds, orientation, alignment,
            angleDeg = rot?.angleDeg ?: 0f,
            orientedWidth = rot?.orientedWidth ?: 0f,
            orientedHeight = rot?.orientedHeight ?: 0f,
        )
    }

    /** [effectiveAlignLeft] for a deskewed frame: the same hanging-punct text
     *  test, geometry from the frame rect, and the char-precise branch skipped
     *  — chars are empty on rotated Paddle lines (PaddleRecognizer suppresses
     *  them until the oriented char-synthesis stage) and ML Kit char AABBs are
     *  screen-space; the height fallback matches the punct-width intent. */
    private fun effectiveAlignLeftFramed(line: RecognizedLine, frame: AngleFrame): Int {
        val rect = DeskewGeometry.deskew(line.box, frame)
        val firstIdx = line.text.indexOfFirst { !it.isWhitespace() }
        if (firstIdx < 0) return rect.left
        return if (line.text[firstIdx] in HANGING_PUNCT_LEFT) rect.left + rect.height() else rect.left
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
    internal fun classifyGroupAlignment(lines: List<RecognizedLine>): TextAlignment =
        classifyGroupAlignment(lines.map { it.box.bounds }, lines.map { effectiveAlignLeft(it) })

    /** Rects-taking core, shared by the screen-space wrapper above and the
     *  deskewed-frame path in [buildLayoutGroup] (which supplies frame rects +
     *  frame-computed align-lefts). */
    internal fun classifyGroupAlignment(boxes: List<Rect>, lefts: List<Int>): TextAlignment {
        if (boxes.size < 2) return TextAlignment.LEFT
        val refH = boxes.maxOf { it.height() }
        if (refH <= 0) return TextAlignment.LEFT
        val tol = (refH * 0.5f).toInt()
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
 *
 * [angleDeg] + [orientedWidth]/[orientedHeight] are non-zero for the two
 * angle-carrying shapes layout emits: a rotated singleton (angle + dims from
 * the region's own [OcrBox], verbatim) and an angle-cluster group (angle = the
 * cluster frame's θ̄ — itself a verbatim member angle — with dims from the
 * deskewed union and [bounds] its exact back-rotated AABB). Same coordinate
 * space as [bounds]. All three ride together: the oriented dims cannot be
 * re-derived from bounds+angle downstream (singular at 45°).
 */
data class LayoutGroup(
    val text: String,
    val lines: List<RecognizedLine>,
    val bounds: Rect,
    val orientation: TextOrientation,
    val alignment: TextAlignment,
    val angleDeg: Float = 0f,
    val orientedWidth: Float = 0f,
    val orientedHeight: Float = 0f,
)
