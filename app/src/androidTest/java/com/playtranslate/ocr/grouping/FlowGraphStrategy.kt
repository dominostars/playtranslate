package com.playtranslate.ocr.grouping

import android.graphics.Rect
import android.util.Log
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharClassCoverage
import com.playtranslate.ocr.core.GroupingContext
import com.playtranslate.ocr.core.GroupingStrategy
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.ProposedGroup
import com.playtranslate.ocr.core.RecognizedRegion

/**
 * Research variant: **FLOWGRAPH** — a clean-room line→block grouper that shares
 * no decision code with [com.playtranslate.ocr.core.DefaultGroupingStrategy].
 * Only the shell contract is shared (normalized regions in, proposed groups
 * out), so a column delta against `docpitch-off` is attributable to this file.
 *
 * ## Why a different architecture
 *
 * Measured on the corpus (host-side twin `flowgraph.py`, 2026-07-25): labelling
 * every adjacent line pair from the expectations and sweeping every geometric
 * feature, the best single pairwise statistic is centre-to-centre pitch over
 * line thickness at AUC 0.925 — its best threshold still errs on 13.7% of
 * pairs, because the SAME p90 (1.91 em) sits ABOVE the DIFF p10 (1.57 em). The
 * confusion band is structural, not a mis-set constant, so no retune of a
 * pairwise gate reaches the corpus. The accuracy has to come from somewhere
 * else, and this strategy takes it from two places:
 *
 * **1. Adjacency instead of sequence.** Production walks a globally top-sorted
 * list, comparing each line against the groups built so far. FLOWGRAPH builds a
 * MUTUAL nearest-neighbour successor graph in flow coordinates, which yields
 * one linear chain per column. Text from another panel cannot sit between two
 * lines of a paragraph, because it is not that paragraph's nearest neighbour —
 * a structural fix for the multi-panel game screens the corpus is full of.
 *
 * **2. Rhythm as the primary evidence, per chain.** Within a chain the modal
 * pitch (largest mutually-close cluster of the chain's OWN pitches) is the
 * reference. A pair sitting on that rhythm merges even when its absolute gap is
 * generous; a pair off it splits even when its gap is small. Absolute pitch
 * thresholds apply only where there is no rhythm to appeal to — a first pair,
 * a two-row block. This is what reaches generously-led paragraphs and headings
 * that happen to sit at the body's own pitch (nhk-news-easy's title is 56px
 * against a 56.5px body pitch — no gap rule can see it).
 *
 * Everything else is deliberately subordinate:
 *  - Scale and case may cut a pair the pitch would keep, but ONLY off-rhythm:
 *    font size and line pitch are physically coupled, so a real size step
 *    perturbs the rhythm too, and an apparent step at exactly the body pitch is
 *    box noise. (Worth +4 cells on its own.)
 *  - A short block-OPENER is a heading: measured 33 of 33 labelled edges are
 *    boundaries at >= 6 em shorter than the block below, at zero cost. The
 *    "opener" restriction is what makes it safe — interior lines are ragged for
 *    many reasons, openers are not.
 *  - Alignment, measured against the block's MEDIAN edge rather than its
 *    accumulated spread, arbitrates only above [pitchTight].
 *  - A continuation cue vetoes every arbitrated cut.
 *
 * ## Text evidence, and its measured base rates
 *
 * Continuation cues (trailing comma / connective particle / unclosed bracket,
 * or a lower line opening lowercase or with a closing bracket) are the
 * strongest merge evidence in the corpus: **P(boundary | cue) = 0.02** (1 of
 * 53) inside the ambiguous pitch band against a 0.45 base rate, and ablating
 * them costs 6 cells. Note this includes the JA particle set reverted on
 * 2026-07-01 — that reversal was argued from the base rate of elliptical UI
 * labels, and the corpus does not bear it out at this position (as a merge
 * PROTECTION inside the band, not as a band corroborator).
 *
 * Terminal punctuation is NOT used, and deliberately: P(boundary | the upper
 * line ends in 。/./!/?) = 0.34, BELOW the base rate. Game dialogue ends lines
 * mid-block with ! and 。 constantly.
 *
 * ## Enemy population and bounded failure
 *
 * The list rule ([looksLikeList]) is narrow by construction because the corpus
 * killed the general form. Over 116 labelled segments, interior shortfall (how
 * far an interior row ends short of the block's longest — the wrap-wall /
 * word-fit test) spans 0.65–23.5 em for blocks that must stay merged and
 * 0.0–27.7 em for blocks that must split: FULLY overlapping. Hand-broken game
 * dialogue is exactly as ragged as a menu, so shape cannot carry it. What is
 * left is three narrow paths, each measured:
 *  - a cue-free stack narrower than [listNarrow] of the screen with at least
 *    one em of leading;
 *  - a cue-free stack of capitalised, sentence-free rows ([listTitleCase]) —
 *    the FF-family command columns;
 *  - a cue-free stack of >= [listRaggedRows] rows under [listRaggedNarrow] of
 *    the screen holding neither margin.
 * Vertical text abstains outright, for the production split's reasons plus one
 * of its own: manga bubbles (え? / ここじゃ / ないの?) are exactly the shape the
 * rule would misread.
 *
 * Residual failures on the corpus, in families: uniform-pitch item stacks that
 * are geometrically identical to prose (centred equal-width JA title menus);
 * stylized fonts whose case OCR is noise (a RU pixel font reads
 * `кОмБиНироВАтЬ`); OCR line fragmentation; and gaps above every ceiling that
 * the corpus still wants merged.
 *
 * ## Scores (host-side prediction, judge unmodified)
 *
 * `results-1785001951858` (126 evaluable cells), production engine column:
 *
 *     production          66  stanzas 707   menu  9/29  comic  8/15  choice 2/6
 *     flowgraph           89  stanzas 779   menu 17/29  comic  8/15  choice 2/6
 *     flowgraph-census    94  stanzas 802   menu 19/29  comic  8/15  choice 6/6
 *     flowgraph-census2   96  stanzas 805   menu 20/29  comic 10/15  choice 6/6
 *
 * It also beats production on all four engine columns including the three it
 * was never tuned on, and a 2-fold re-tune of the sensitive parameters on
 * either half of the corpus finds no better values than these.
 *
 * ## Parity with the twin — CHECKED, 2026-07-25, run results-1784997215389
 *
 * Compared at the PARTITION level (replay the device's own recognition output
 * through the twin, align regions by box+text, compare blocks), not at the
 * verdict level: **111 of 129 cells byte-identical**, 18 differing, and 4
 * verdict disagreements — the same four an independent verdict-level check
 * found on a different run. Causes, all twin-side or benign:
 *  - the twin's source-script filter was an approximation of
 *    `LayoutAnalyzer.isSourceLangChar` (fixed in `flowgraph.py`); a looser
 *    rule keeps lines the shell drops, which shifts group membership;
 *  - hades2_boons_of_demeter's partition is IDENTICAL — its disagreement is
 *    one line the twin dropped and the device kept, so the regression there is
 *    real, not a port bug;
 *  - the remaining two are knife-edge scale decisions (a logged ratio of
 *    exactly 1.30 against a 1.30 cap) where the two coverage implementations
 *    round apart, and they fall in OPPOSITE directions — one costs a cell, one
 *    wins one.
 * No port bug was found that would improve this class if "fixed". Treat the
 * twin as a design instrument that agrees in aggregate (84/114 either way on
 * that run), not as an oracle for individual cells.
 *
 * ## Fixed after the first device run: source-script filter position
 *
 * The filter originally ran over the EXPLODED output, so a numeric or
 * icon-only row inside an otherwise source-language menu was dropped where
 * `DefaultGroupingStrategy` — filtering `rawGroups` before `splitMenuGroups` —
 * keeps it. Measured on results-1785001951858: **11 lines across 8 cells** the
 * shipped column silently dropped, all HUD values and Latin fragments inside
 * menus (`107/113`, `18:28`, `38/39`, `P1`, `12-QUEEN`). It flattered the
 * score — moving the filter to the block, where production applies it, costs
 * about 4 stanzas and no cells. A strategy that sheds rows the other columns
 * emit cannot be compared to them on the same seeds, which is the reason to
 * care; the scoreboard effect is second order.
 *
 * ## RTL
 *
 * Horizontal text is judged in START/END terms, not left/right: [Row.inStart]
 * is where a line begins (left for LTR, right for RTL) and [Row.inEnd] the
 * margin wrapped text fills toward, both derived from `ctx.rtl`. Alignment,
 * the hanging-punctuation anchor, edge clustering and [wrappedTail] all read
 * those, so RTL is the same code with one axis flipped rather than a parallel
 * path. Vertical partitions are never mirrored — a column reads top-to-bottom
 * whatever the script's horizontal direction, and vertical partitions are CJK
 * by construction.
 *
 * Cue sets are NOT mirrored, deliberately: the recognizers emit Arabic in
 * logical order, so "the first character" is the first READ character and
 * bracket/­comma tests work unchanged. They gained the Arabic marks the sets
 * were missing (`،` `؛` continuation, `؟` `۔` terminal, `«»﴾﴿` brackets).
 *
 * Verification status is honest: the refactor is **byte-identical on all 126
 * LTR cells** (every column, every tag slice), so it cannot regress what is
 * measured. The mirrored paths themselves are NOT yet exercised by a device
 * run — the corpus's one Arabic seed has no installed OCR column. Replaying
 * that seed's expectation boxes through the twin does exercise them, and it
 * surfaced a defect that was never an RTL problem at all: see
 * [headingNoCascade]. Treat RTL as implemented-and-unmeasured until an Arabic
 * column runs.
 *
 * ## Measured and REJECTED — do not re-derive
 *
 * From the same review, each implemented behind a flag, measured, and dropped:
 *  - **A sentence-closing block OPENER as a boundary** (the inverse conditional
 *    to [headingNotSentence]): **-13 cells**. Game dialogue routinely opens a
 *    block with a line that ends in 。 and keeps going.
 *  - **Requiring the short-opener heading rule to be off-rhythm too**: **-6
 *    cells**. That rule's whole value is cutting headings that sit ON the
 *    body's rhythm — nhk-news-easy's title is 56px against a 56.5px body
 *    pitch — so the extra condition removes the mechanism, not its noise.
 *  - **Chain-wide list membership** (a cue-free segment inherits list-ness from
 *    a sibling segment of the same chain, to rescue menus the rhythm cut in
 *    two): +1 cell alone, but it CONFLICTS with the punctuation census (+3
 *    alone, +1 together) and is subsumed by it, since the census's two-row
 *    floor already judges the fragments directly.
 *  - Estimating chain pitch as the best-supported cluster rather than from the
 *    first pair was also recommended: [modalPitch] already does exactly that.
 *
 * ## Contract
 *
 * Every constructor knob is WIRED (the [com.playtranslate.ocr.core.GroupingRecipe]
 * rule: a field that silently does nothing makes sweep results lies), and each
 * is an ablation measured host-side. Determinism: no clock, no randomness,
 * integer geometry — the harness's byte-determinism claim survives.
 *
 * The host-side twin is `ocr-grouping/flowgraph.py` (run it with
 * `flowgraph_bench.py`); the two must agree or the numbers above stop
 * describing this code.
 */
class FlowGraphStrategy(
    // ── stage 2: rows ────────────────────────────────────────────────────────
    /** Same-row merge: boxes closer than this many em along the text direction
     *  become one row. Tight on purpose — the corpus wants same-row `label:` /
     *  `value` pairs kept APART far more often than together, and 0.6 em still
     *  rejoins the genuine OCR fragments of one line (`МНОЖ.` + `x0.1` +
     *  `за каждую…` sit at ~0.5 em). 1.2 em costs a cell on both runs. */
    private val inlineGapEm: Float = 0.60f,
    /** Cross-axis overlap fraction for two boxes to share a visual row. */
    private val rowOverlap: Float = 0.50f,
    /** Thickness ratio beyond which two boxes cannot share a row. Without it a
     *  rotated "Main Menu" tab spanning a whole column bands with every line it
     *  crosses and swallows the lot. */
    private val rowThicknessCap: Float = 2.0f,
    // ── stage 3: graph ───────────────────────────────────────────────────────
    /** Inline overlap a row needs to be another row's flow neighbour. */
    private val corridorOverlap: Float = 0.25f,
    /**
     * em ratio beyond which two rows cannot link at all — the ruby/furigana
     * gate. It governs TWO sequential tests and both matter: link eligibility
     * (a half-size annotation must not be chosen as a body line's nearest
     * neighbour) and [corridorBlocked] (having been rejected as a neighbour it
     * must also not FENCE that body line off from its real one). Lowering only
     * one of them changes nothing, which is how the pair was found.
     *
     * 1.80 is the shipped value. 1.52 measures better (+2 cells, comic 8/15 ->
     * 10/15) and is the thinnest constant in the whole strategy: the corpus's
     * ruby columns sit at 1.54 and 1.77 while legitimate on-rhythm pairs reach
     * 1.52, so it lives in a 0.02-wide measured gap. 1.62–1.75 is the safe
     * plateau and costs exactly one cell (rEHbXlAk, whose ruby is the 1.54).
     */
    private val linkScaleCap: Float = 1.80f,
    /** em: no link reaches further than this. */
    private val linkMaxPitch: Float = 3.20f,
    /** Corridor-interposition sensitivity. Measures ZERO on the corpus —
     *  mutual-NN linking already prevents reaching over a line — and is kept
     *  only as the structural guard for that case. */
    private val corridorBlock: Float = 0.35f,
    // ── stage 4: segmentation ────────────────────────────────────────────────
    /** How far above the chain's modal pitch a pair may sit and still continue it. */
    private val rhythmTol: Float = 0.18f,
    /** Clustering tolerance when locating the modal pitch. */
    private val modalTol: Float = 0.12f,
    /**
     * Whether a VERTICAL chain's modal pitch counts as evidence. The rhythm
     * mechanism assumes a chain is one column of one block; in vertical manga
     * that assumption fails — speech balloons are packed side by side and
     * overlap on the inline axis, so one chain routinely spans several of them
     * and its modal pitch is the neighbouring balloon's rhythm. Measured on
     * rEHbXlAk: a chain of six columns across two balloons plus a furigana
     * column, modal pitch 32.75 from balloon B, cutting a legitimate 46-pitch
     * pair inside balloon A.
     *
     * Turning it off makes vertical pairs unconditionally "on rhythm" when the
     * chain has one, which withdraws BOTH the rhythm cut and the scale cut it
     * guards, leaving the absolute pitch rules in charge. Anything from a 0.45
     * tolerance upward scores identically, so the honest form is abstention
     * rather than another tuned number. Horizontal text is untouched: there the
     * rhythm cut is worth 6 cells.
     */
    private val rhythmVertical: Boolean = true,
    /** em: cut when the chain has no rhythm to appeal to. */
    private val pitchSolo: Float = 2.05f,
    /** em: below this, weak evidence (alignment) may not cut. */
    private val pitchTight: Float = 1.55f,
    /** em: cut regardless of rhythm. */
    private val pitchCeiling: Float = 2.20f,
    /** em ratio (raw AND coverage-normalized) that counts as a size step. */
    private val scaleCut: Float = 1.30f,
    /** em: alignment agreement tolerance against the block's median edge. */
    private val alignTol: Float = 0.55f,
    /** All-caps row above a lowercase-bearing row cuts. Worth 4 stanzas, 0
     *  cells — the heading rule absorbed most of its old work. */
    private val capsCut: Boolean = true,
    /** A continuation cue vetoes the arbitrated cuts. */
    private val contProtect: Boolean = true,
    /** em: how far a continuation cue may stretch a no-rhythm pair. */
    private val contSolo: Float = 2.05f,
    /** em: a block-opener this much shorter than the block below is a heading. */
    private val headingShort: Float = 6.0f,
    /** Rows below the opener the block's width is measured over. */
    private val headingWindow: Int = 4,
    /** A row that closes a sentence is prose, not a title. */
    private val headingNotSentence: Boolean = true,
    /**
     * A row that became a block-opener only BECAUSE the previous edge was a
     * heading cut is not itself a heading. Without this the rule cascades: in a
     * ragged block whose lines grow, every row in turn is "short against the
     * block below" and the block shreds row by row. It is what takes the
     * corpus's one Arabic seed from SPLIT to PASS (6 groups down to the
     * expected 2).
     *
     * Cost, measured per column and NOT uniform: exactly neutral with
     * [listPunctCensus] on (94/802 and 96/805 either way), but it costs the
     * bare column one cell — `dkcjkqab5nn11`, a choice list the cascade was
     * shredding BY ACCIDENT. The census splits that seed properly, so where
     * the census runs the cascade was never earning anything.
     * Enemy case, enumerated and absent from the corpus: a genuine
     * title / subtitle / body stack, where the second cut is wanted.
     */
    private val headingNoCascade: Boolean = true,
    /** A trailing-colon row opens a labelled unit. SPECIMEN-THIN: rests on 3
     *  edges from one seed (hades2_boons_of_demeter). Worth 1 cell. */
    private val labelColon: Boolean = true,
    // ── stage 5: list ────────────────────────────────────────────────────────
    private val listEnabled: Boolean = true,
    private val listMinRows: Int = 2,
    /** Fraction of screen width a menu column may span (spacious path). */
    private val listNarrow: Float = 0.17f,
    /** em: rows closer than this overlap, so they are not a spaced stack. */
    private val listPitch: Float = 1.00f,
    /** Capitalised, sentence-free rows read as a command column. */
    private val listTitleCase: Boolean = true,
    /** Fraction of screen width the title-case path allows. */
    private val listTitledNarrow: Float = 0.28f,
    /**
     * Block-level punctuation census as a fourth list path (2026-07-25 review
     * recommendation, measured and adopted): a cue-free stack in which almost
     * no row CLOSES a sentence is a set of labels, because running text
     * punctuates somewhere within a few rows and a label column never does.
     *
     * This does not contradict the class kdoc's rejection of terminal
     * punctuation — that is a different statistic at a different position. The
     * rejected one is PAIRWISE (P(boundary | the upper line ends a sentence) =
     * 0.34, below the 0.45 base rate); this is a CENSUS over a whole stack, and
     * a per-pair cue carrying no information says nothing about the census.
     *
     * Its value is that it is script-agnostic: [listTitleCase] is Latin
     * capitalisation evidence and structurally cannot fire for JA, ZH or RU
     * menus. This is what finally reaches `最初から / つづきから / ヘルプとオプション`.
     *
     * Measured at this position on results-1785001951858: 89 -> 94 cells,
     * 779 -> 802 stanzas, menu-tagged 17/29 -> 19/29, and choice 3/6 -> 6/6 —
     * punctuated choice menus, a population the same census over production's
     * groups documents as unreachable. Exposure, and it is real: punctuation-free
     * PROSE. Two blocks shred (a ZH rules list and a JA description). Sweeping
     * the width gate and a longest-row cap does not separate them from the
     * menus it wins — that trade is the shape of the mechanism, not a mis-set
     * constant.
     */
    private val listPunctCensus: Boolean = false,
    /** Rows a stack needs before its punctuation profile is trusted. TWO here,
     *  where the same census over production's greedy groups needs four: these
     *  stacks are already rhythm-cut chain segments, so a two-row fragment is
     *  a coherent piece of one column rather than an arbitrary pair. Measured:
     *  2 -> 89 cells, 3 -> 88, 4 -> 86. */
    private val listPunctRows: Int = 2,
    /** Fraction of rows that may close a sentence before the stack reads as
     *  running text. 0.0/0.1/0.2 score identically; 0.2 is the most tolerant
     *  of a stray OCR period. Above 0.34 it collapses (-5 cells). */
    private val listPunctEnds: Float = 0.20f,
    /** Fraction of screen width the census path allows. The knee is 0.50;
     *  wider is neutral, 0.42 costs 2 cells. */
    private val listPunctNarrow: Float = 0.50f,
    /** Rows needed before the ragged-edge path applies. */
    private val listRaggedRows: Int = 5,
    /** Fraction of screen width the ragged path allows. */
    private val listRaggedNarrow: Float = 0.34f,
    /** em: edge-clustering tolerance for the ragged path. */
    private val listEdgeTol: Float = 1.00f,
    override val name: String = "flowgraph",
) : GroupingStrategy {

    override fun group(
        regions: List<RecognizedRegion>,
        ctx: GroupingContext,
    ): List<ProposedGroup> {
        if (regions.isEmpty()) return emptyList()
        val out = ArrayList<ProposedGroup>()
        for (vertical in listOf(false, true)) {
            val part = regions.filter { (it.orientation == TextOrientation.VERTICAL) == vertical }
            if (part.isEmpty()) continue
            val rows = buildRows(part, vertical, ctx.rtl)
            for (chain in chainsOf(rows)) {
                for (seg in segment(chain, rows, ctx)) {
                    emit(seg, rows, ctx, out)
                }
            }
        }
        return out
    }

    private fun emit(
        seg: List<Int>,
        rows: List<Row>,
        ctx: GroupingContext,
        out: MutableList<ProposedGroup>,
    ) {
        val segRows = seg.map { rows[it] }
        // Source-script filter, at the point the production shell applies it:
        // on the BLOCK, before any list split. Per-exploded-row instead would
        // drop a numeric or icon-only row out of an otherwise source-language
        // menu ("Items / 3 / Equipment" loses the 3) where
        // DefaultGroupingStrategy — which filters rawGroups before
        // splitMenuGroups — keeps it. A strategy that sheds rows the others
        // emit cannot be compared to them on the same seeds.
        if (segRows.none { row ->
                row.members.any { r ->
                    r.text.any { LayoutAnalyzer.isSourceLangChar(it, ctx.sourceLang) }
                }
            }
        ) {
            return
        }
        if (looksLikeList(segRows, ctx)) {
            // Pin each exploded row to the stack's own edges, like the
            // production menu split, so the overlays align down the column.
            val left = segRows.minOf { it.box.left }
            val right = segRows.maxOf { it.box.right }
            for (r in segRows) {
                if (ctx.logDecisions) {
                    Log.d(TAG, "[flowgraph] list-row \"${snippet(r.text)}\"")
                }
                out += ProposedGroup(r.members, parentLeft = left, parentRight = right)
            }
        } else {
            out += ProposedGroup(segRows.flatMap { it.members })
        }
    }

    // ── stage 1: scale ───────────────────────────────────────────────────────

    /**
     * Font size estimate: the glyph-tight cross extent divided by the coverage
     * the text can actually reach. The raw box tracks CONTENT as much as size
     * (an all-caps line is cap-height only; a kana-only line is short), which
     * is the whole reason [CharClassCoverage] exists. Unmapped scripts return
     * null and fall back to a script-class default rather than a wrong number.
     */
    private fun emOf(region: RecognizedRegion, vertical: Boolean): Double {
        val b = region.box.bounds
        val cross = (if (vertical) b.width() else b.height()).toDouble()
        val orientation = if (vertical) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL
        val cov = CharClassCoverage.coverage(region.text, orientation)
        if (cov != null && cov > 0.05) return maxOf(1.0, cross / cov)
        val cjk = region.text.any { isCjk(it) }
        return maxOf(1.0, cross / (if (cjk) 0.88 else 0.80))
    }

    // ── stage 2: rows ────────────────────────────────────────────────────────

    /**
     * One row = one visual line of the block: boxes whose cross-axis spans
     * overlap and whose inline gap is under about one em. Coordinates are FLOW
     * coordinates — for vertical text the cross axis is negated x, so columns
     * advance right-to-left in increasing order and every later stage is
     * orientation-agnostic.
     */
    private inner class Row(
        val members: List<RecognizedRegion>,
        val vertical: Boolean,
        val rtl: Boolean,
    ) {
        val box: Rect = Rect(
            members.minOf { it.box.bounds.left }, members.minOf { it.box.bounds.top },
            members.maxOf { it.box.bounds.right }, members.maxOf { it.box.bounds.bottom },
        )
        val text: String = members.joinToString("") { it.text }
        val em: Double = members.maxOf { emOf(it, vertical) }
        val raw: Int = members.maxOf { if (vertical) it.box.bounds.width() else it.box.bounds.height() }
        val crossLo: Int = if (vertical) -box.right else box.top
        val crossHi: Int = if (vertical) -box.left else box.bottom
        val inLo: Int = if (vertical) box.top else box.left
        val inHi: Int = if (vertical) box.bottom else box.right
        val thickness: Int get() = crossHi - crossLo
        val extent: Int get() = inHi - inLo
        val center: Double get() = (crossLo + crossHi) / 2.0
        val inCenter: Double get() = (inLo + inHi) / 2.0

        /**
         * The inline axis runs high-to-low in reading order. RTL HORIZONTAL text
         * only: a vertical column is read top-to-bottom whatever the script's
         * horizontal direction, and vertical partitions are CJK by construction.
         */
        val mirrored: Boolean = rtl && !vertical

        /** Where a line BEGINS: left for LTR, right for RTL, top for vertical.
         *  Every alignment and margin test is expressed in these terms rather
         *  than in left/right, so RTL is the same code with the axis flipped. */
        val inStart: Int get() = if (mirrored) inHi else inLo

        /** Where a line ENDS — the margin wrapped text fills toward. */
        val inEnd: Int get() = if (mirrored) inLo else inHi

        /** [inStart] with a leading hanging glyph skipped, so a bulleted or
         *  quoted line aligns to where its body text starts. The shift is
         *  INWARD along the reading direction, which is leftward for RTL. */
        val effStart: Int = run {
            val first = members.minByOrNull {
                val b = it.box.bounds
                when {
                    vertical -> b.top
                    mirrored -> -b.right
                    else -> b.left
                }
            }
            val t = first?.text?.trimStart().orEmpty()
            if (t.isNotEmpty() && t[0] in HANGING) {
                val advance = extent / maxOf(1, compact(first!!.text).length)
                if (mirrored) inStart - advance else inStart + advance
            } else {
                inStart
            }
        }
    }

    private fun buildRows(part: List<RecognizedRegion>, vertical: Boolean, rtl: Boolean): List<Row> {
        fun crossOf(b: Rect) = if (vertical) -b.right to -b.left else b.top to b.bottom
        fun inlineOf(b: Rect) = if (vertical) b.top to b.bottom else b.left to b.right

        class Band(val members: MutableList<RecognizedRegion>, var lo: Int, var hi: Int)

        val ordered = part.sortedBy { crossOf(it.box.bounds).first }
        val bands = ArrayList<Band>()
        for (r in ordered) {
            val (lo, hi) = crossOf(r.box.bounds)
            val band = bands.firstOrNull { b ->
                val ov = minOf(b.hi, hi) - maxOf(b.lo, lo)
                val thick = maxOf(b.hi - b.lo, hi - lo).toDouble() /
                    maxOf(1, minOf(b.hi - b.lo, hi - lo))
                ov > 0 && thick <= rowThicknessCap && ov >= rowOverlap * minOf(b.hi - b.lo, hi - lo)
            }
            if (band == null) {
                bands += Band(mutableListOf(r), lo, hi)
            } else {
                band.members += r
                band.lo = minOf(band.lo, lo)
                band.hi = maxOf(band.hi, hi)
            }
        }

        val rows = ArrayList<Row>()
        for (band in bands) {
            val inOrder = band.members.sortedBy { inlineOf(it.box.bounds).first }
            var cur = mutableListOf(inOrder.first())
            for (r in inOrder.drop(1)) {
                val prevHi = cur.maxOf { inlineOf(it.box.bounds).second }
                val gap = inlineOf(r.box.bounds).first - prevHi
                val em = (cur + r).maxOf { emOf(it, vertical) }
                if (gap <= inlineGapEm * em) {
                    cur += r
                } else {
                    rows += Row(cur, vertical, rtl); cur = mutableListOf(r)
                }
            }
            rows += Row(cur, vertical, rtl)
        }
        return rows.sortedBy { it.crossLo }
    }

    // ── stage 3: graph ───────────────────────────────────────────────────────

    private fun overlapFrac(a: Row, b: Row): Double {
        val ov = minOf(a.inHi, b.inHi) - maxOf(a.inLo, b.inLo)
        val m = minOf(a.extent, b.extent)
        return if (m > 0) ov.toDouble() / m else 0.0
    }

    /**
     * A row between [a] and [b] INSIDE their shared inline corridor. The
     * corridor restriction is what lets a merge ignore another panel's text;
     * the scale test is what lets it ignore ruby, which sits between body lines
     * by design.
     */
    private fun corridorBlocked(a: Row, b: Row, rows: List<Row>): Boolean {
        val lo = a.crossHi
        val hi = b.crossLo
        if (hi <= lo) return false
        val cLo = maxOf(a.inLo, b.inLo)
        val cHi = minOf(a.inHi, b.inHi)
        for (r in rows) {
            if (r === a || r === b) continue
            if (r.center <= lo || r.center >= hi) continue
            if (ratio(r.em, a.em) > linkScaleCap && ratio(r.em, b.em) > linkScaleCap) continue
            val ov = minOf(cHi, r.inHi) - maxOf(cLo, r.inLo)
            if (ov > corridorBlock * minOf(cHi - cLo, r.extent)) return true
        }
        return false
    }

    /** Mutual nearest-neighbour successor links: each row's nearest compatible
     *  neighbour downstream, kept only when the choice is reciprocated. */
    private fun chainsOf(rows: List<Row>): List<List<Int>> {
        val n = rows.size
        val succ = IntArray(n) { -1 }
        for (i in 0 until n) {
            val a = rows[i]
            var best = -1
            var bestD = Int.MAX_VALUE
            for (j in 0 until n) {
                if (j == i) continue
                val b = rows[j]
                if (b.center <= a.center) continue
                if (b.crossLo < a.crossHi - 0.35 * minOf(a.thickness, b.thickness)) continue
                if (overlapFrac(a, b) < corridorOverlap) continue
                if (ratio(a.em, b.em) > linkScaleCap) continue
                if (b.center - a.center > linkMaxPitch * maxOf(a.em, b.em)) continue
                if (corridorBlocked(a, b, rows)) continue
                val d = b.crossLo - a.crossHi
                if (d < bestD) { best = j; bestD = d }
            }
            succ[i] = best
        }
        val pred = IntArray(n) { -1 }
        for (j in 0 until n) {
            var best = -1
            var bestD = Int.MAX_VALUE
            for (i in 0 until n) {
                if (succ[i] != j) continue
                val d = rows[j].crossLo - rows[i].crossHi
                if (d < bestD) { best = i; bestD = d }
            }
            pred[j] = best
        }
        val next = HashMap<Int, Int>()
        for (i in 0 until n) {
            val j = succ[i]
            if (j >= 0 && pred[j] == i) next[i] = j
        }
        val hasPred = next.values.toHashSet()
        val chains = ArrayList<List<Int>>()
        for (i in 0 until n) {
            if (i in hasPred) continue
            val c = arrayListOf(i)
            var cur = i
            while (next.containsKey(cur)) { cur = next.getValue(cur); c += cur }
            chains += c
        }
        return chains
    }

    // ── stage 4: segmentation ────────────────────────────────────────────────

    /** Largest mutually-close cluster of a chain's pitches, then its median.
     *  Null below two members — one pitch is a measurement, two are a rhythm. */
    private fun modalPitch(pitches: List<Double>): Double? {
        if (pitches.size < 2) return null
        var best: List<Double>? = null
        for (p in pitches) {
            val members = pitches.filter { kotlin.math.abs(it - p) <= modalTol * maxOf(it, p) }
            if (members.size < 2) continue
            val b = best
            if (b == null || members.size > b.size ||
                (members.size == b.size && members.sum() < b.sum())
            ) {
                best = members
            }
        }
        return best?.let { median(it) }
    }

    /** Permissive: a size step BOTH the raw box and the coverage-normalized
     *  estimate agree on. Either alone is content noise. */
    private fun scaleChange(a: Row, b: Row): Boolean =
        ratio(a.em, b.em) > scaleCut && ratio(a.raw.toDouble(), b.raw.toDouble()) > scaleCut

    /** Does the candidate sit on one of the block's axes? Measured against the
     *  block's MEDIAN edge, not its accumulated spread — otherwise a long block
     *  slowly disqualifies its own later lines as the spread grows. Expressed
     *  in start/end rather than left/right so an RTL block is judged on the
     *  margin it actually aligns to. */
    private fun alignOk(seg: List<Row>, cand: Row): Boolean {
        val em = maxOf(seg.maxOf { it.em }, cand.em)
        val tol = alignTol * em
        return kotlin.math.abs(cand.effStart - median(seg.map { it.effStart.toDouble() })) <= tol ||
            kotlin.math.abs(cand.inEnd - median(seg.map { it.inEnd.toDouble() })) <= tol ||
            kotlin.math.abs(cand.inCenter - median(seg.map { it.inCenter })) <= tol
    }

    private fun segment(chain: List<Int>, rows: List<Row>, ctx: GroupingContext): List<List<Int>> {
        if (chain.size == 1) return listOf(chain)
        val pitches = (0 until chain.size - 1).map { rows[chain[it + 1]].center - rows[chain[it]].center }
        val p = modalPitch(pitches)
        val segs = ArrayList<List<Int>>()
        var cur = mutableListOf(chain[0])
        var lastWasHeading = false
        for (k in 0 until chain.size - 1) {
            val a = rows[chain[k]]
            val b = rows[chain[k + 1]]
            val em = maxOf(a.em, b.em)
            val pitch = pitches[k]
            val r = pitch / em
            val cont = contProtect &&
                (continues(a.text, ctx.sourceLang) || startsContinuation(b.text, ctx.sourceLang))
            val onRhythm =
                if (a.vertical && !rhythmVertical) p != null
                else p != null && pitch <= p * (1 + rhythmTol)
            var why: String? = null
            // Hard evidence.
            if (pitch > pitchCeiling * em && !(cont && r <= contSolo)) {
                why = "ceiling ${fmt(r)}em"
            } else if (p != null && !onRhythm) {
                why = "rhythm ${fmt(pitch)} vs P=${fmt(p)}"
            } else if (p == null && r > pitchSolo && !(cont && r <= contSolo)) {
                why = "solo ${fmt(r)}em"
            }
            // A heading may sit at the body's pitch by coincidence, so scale and
            // case cut through it — but only OFF-rhythm: size and pitch are
            // coupled, so an apparent size step at the body pitch is box noise.
            if (why == null && !onRhythm && scaleChange(a, b)) {
                why = "scale ${fmt(ratio(a.em, b.em))}"
            }
            if (why == null && capsCut && !a.vertical && isAllCaps(a.text) && hasLower(b.text)) {
                why = "caps-heading"
            }
            if (why == null && labelColon && !cont &&
                endsColon(b.text) && !endsColon(a.text)
            ) {
                why = "label-row"
            }
            // Heading position: the row OPENS a block and is far shorter than
            // the block under it. Wrapped text is short only on its LAST line.
            if (why == null && cur.size == 1 && !cont &&
                !(headingNoCascade && lastWasHeading) &&
                !(headingNotSentence && endsSentence(a.text))
            ) {
                val below = chain.subList(k + 1, minOf(chain.size, k + 1 + headingWindow)).map { rows[it] }
                val widest = below.maxOf { it.extent }
                if (widest - a.extent > headingShort * em) {
                    why = "heading (short opener, ${fmt((widest - a.extent) / em)}em)"
                }
            }
            // Arbitrated evidence: only where the pitch is not decisive, and
            // never against a continuation cue.
            if (why == null && !cont && r > pitchTight && !alignOk(cur.map { rows[it] }, b)) {
                why = "align (r=${fmt(r)})"
            }
            if (why == null) {
                cur += chain[k + 1]
            } else {
                if (ctx.logDecisions) {
                    Log.d(
                        TAG,
                        "[flowgraph] SPLIT \"${snippet(a.text)}\" | \"${snippet(b.text)}\" :: $why",
                    )
                }
                lastWasHeading = why.startsWith("heading")
                segs += cur
                cur = mutableListOf(chain[k + 1])
            }
        }
        segs += cur
        return segs
    }

    // ── stage 5: list ────────────────────────────────────────────────────────

    /**
     * A stack of independent items rather than one wrapped block. See the class
     * kdoc for why the obvious formulation (interior rows short of the wrap
     * wall) is dead on this corpus. All three surviving paths require the stack
     * to be free of continuation cues — every row a complete thought.
     */
    private fun looksLikeList(seg: List<Row>, ctx: GroupingContext): Boolean {
        if (!listEnabled || seg.size < listMinRows) return false
        if (seg.any { it.vertical }) return false
        val screenWidth = ctx.screenshotWidthInRegionSpace
        if (screenWidth <= 0f) return false
        for (k in 0 until seg.size - 1) {
            if (continues(seg[k].text, ctx.sourceLang) ||
                startsContinuation(seg[k + 1].text, ctx.sourceLang)
            ) {
                return false
            }
        }
        val em = seg.maxOf { it.em }
        val width = seg.maxOf { it.inHi } - seg.minOf { it.inLo }
        val pitch = (1 until seg.size).sumOf { seg[it].center - seg[it - 1].center } /
            (seg.size - 1) / em
        if (width <= listNarrow * screenWidth && pitch > listPitch) return true
        // A column of named commands ("Ability / Switch / Card / Config"): every
        // row opens with a capital and none closes a sentence. Cased scripts only.
        if (listTitleCase && !unspacedScript(ctx.sourceLang) &&
            width <= listTitledNarrow * screenWidth &&
            seg.all { opensCapital(it.text) } && seg.none { endsSentence(it.text) }
        ) {
            return true
        }
        // Block punctuation census — script-agnostic, so it reaches the CJK and
        // Cyrillic menus the capitalisation path structurally cannot.
        if (listPunctCensus && seg.size >= listPunctRows &&
            width <= listPunctNarrow * screenWidth
        ) {
            val ends = seg.count { endsSentence(it.text) }
            if (ends <= listPunctEnds * seg.size) return true
        }
        // A wider column may still be a list if its edges are RAGGED: wrapped
        // text holds one margin and fills to the other, a stack of items
        // holds neither.
        if (seg.size < listRaggedRows || width > listRaggedNarrow * screenWidth) return false
        val tol = listEdgeTol * em
        val startClustered = (seg.maxOf { it.effStart } - seg.minOf { it.effStart }) <= tol
        val endClustered = (seg.maxOf { it.inEnd } - seg.minOf { it.inEnd }) <= tol
        if (startClustered && endClustered) return false
        if (startClustered && wrappedTail(seg, tol)) return false
        return true
    }

    /** Wrapped text: every row but the last reaches the block's far margin —
     *  the END edge, which is the right margin for LTR and the LEFT one for
     *  RTL. Taking it as `max(right)` unconditionally would make the test
     *  vacuous for Arabic and let the ragged path shred RTL prose. */
    private fun wrappedTail(seg: List<Row>, tol: Double): Boolean {
        val mirrored = seg.first().mirrored
        val wall = if (mirrored) seg.minOf { it.inEnd } else seg.maxOf { it.inEnd }
        return seg.dropLast(1).all { kotlin.math.abs(wall - it.inEnd) <= tol }
    }

    // ── text cues ────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "DetectionLog"

        // Cue sets operate on LOGICAL order — the recognizers emit Arabic in
        // logical order, so "first character" means the first READ character
        // whatever the visual direction. Only geometry needs mirroring, which
        // is why no bracket set is flipped here.
        val HANGING = "「『（【〔《〈([{・·“‘\"'-—–*•,«»﴾".toSet()
        val CONT_END = "、，,・:：;；ー-—/／「『（【〔《〈([{،؛".toSet()
        val JA_PARTICLE = "はがをにへとでもやのかねよな".toSet()
        val OPEN_BRACKET = "「『（【〔《〈([{«﴾".toSet()
        val CLOSE_BRACKET = "」』）】〕》〉)]}»﴿".toSet()
        val TERMINAL = "。．.!！?？…‥؟۔".toSet()
        val UNSPACED = setOf("ja", "zh", "zh-TW", "th")

        fun unspacedScript(lang: String) = lang in UNSPACED

        fun isCjk(c: Char) = c in '぀'..'ヿ' || c in '一'..'鿿' || c in '㐀'..'䶿' || c in '가'..'힯'

        fun compact(t: String) = t.filterNot { it.isWhitespace() }

        fun ratio(a: Double, b: Double) = maxOf(a, b) / maxOf(1.0, minOf(a, b))

        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }

        fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

        fun snippet(t: String) = t.take(24).replace('\n', ' ')

        fun hasLower(t: String) = t.any { it.isLowerCase() }

        fun isAllCaps(t: String): Boolean {
            val letters = t.filter { it.isLetter() }
            return letters.length >= 2 && letters.all { it.isUpperCase() }
        }

        fun endsColon(t: String) = t.trim().let { it.endsWith(":") || it.endsWith("：") }

        /** Closes a sentence, with any trailing quote or bracket peeled off. */
        fun endsSentence(text: String): Boolean {
            var t = text.trim()
            while (t.isNotEmpty() && t.last() in CLOSE_BRACKET) t = t.dropLast(1)
            return t.isNotEmpty() && t.last() in TERMINAL
        }

        fun opensCapital(text: String): Boolean {
            for (c in text.trimStart()) {
                if (c.isLetter()) return c.isUpperCase()
                if (c.isDigit()) return false
            }
            return false
        }

        /**
         * The upper line reads as mid-thought. Measured lift on the corpus:
         * P(boundary | cue) = 0.02 inside the ambiguous pitch band against a
         * 0.45 base rate. Terminal punctuation is deliberately absent — it
         * measures BELOW the base rate as a boundary signal.
         */
        fun continues(text: String, lang: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            val last = t.last()
            if (last in CONT_END) return true
            if (lang == "ja" && last in JA_PARTICLE) return true
            return t.count { it in OPEN_BRACKET } > t.count { it in CLOSE_BRACKET }
        }

        /** The lower line opens mid-sentence. */
        fun startsContinuation(text: String, lang: String): Boolean {
            val t = text.trimStart()
            if (t.isEmpty()) return false
            if (t[0] in CLOSE_BRACKET) return true
            return !unspacedScript(lang) && t[0].isLowerCase()
        }
    }
}
