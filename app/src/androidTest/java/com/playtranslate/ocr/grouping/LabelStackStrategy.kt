package com.playtranslate.ocr.grouping

import android.graphics.Rect
import android.util.Log
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DefaultGroupingStrategy
import com.playtranslate.ocr.core.GroupingContext
import com.playtranslate.ocr.core.GroupingRecipe
import com.playtranslate.ocr.core.GroupingStrategy
import com.playtranslate.ocr.core.ProposedGroup
import com.playtranslate.ocr.core.RecognizedRegion

/**
 * Research variant: **label-stack detection by punctuation profile**, replacing
 * the production menu split's edge-clustering shape test.
 *
 * ## Why (2026-07-25 corpus measurement, run results-1784968007894)
 *
 * `LayoutAnalyzer.isMenuLike` decides "menu" from the shape of a group's row
 * ends: a group is a paragraph when `rows-1` of its rows end within one
 * line-height of the longest. Measured against the corpus's labeled ≥4-row row
 * sets (23 prose blocks that must stay merged, 23 menu stacks that must split),
 * that test scores **18/23 menus split, 5/23 prose shredded**, and it fails in
 * both directions for one reason: shape does not carry the answer.
 *  - Ragged-right and centered prose (game dialogue, ad copy, VN text, and even
 *    justified CJK whose kinsoku variance exceeds one line height) reads as
 *    "menu" and gets shredded into lines.
 *  - Equal-width short-word menus (`Item / Magic / Equip / Status`, the FF
 *    family, the BoF3 camp menu, tacticsogre, JA title screens) cluster on both
 *    edges, read as "paragraph", and stay glued into one translation.
 *
 * Every geometric feature was swept for a separator (row-gap over thickness,
 * pitch over row height, width dispersion, wall residual, and Tesseract-style
 * would-the-next-unit-have-fit). None separates: the BoF3 camp menu has
 * *tighter* relative leading than the median prose block, and its item widths
 * pile up at a wall exactly like wrapped text. The separating signal is
 * textual, and it only works over a whole block:
 *
 * **Running text punctuates somewhere within four rows; a set of labels does
 * not.** Sentence terminators land at arbitrary positions INSIDE a wrapped
 * paragraph's rows, and its rows end mid-clause; menu rows are each a complete
 * label that neither ends a sentence nor continues one.
 *
 * Same labeled sets, this strategy's rule: **22/23 menus split, 1/23 prose
 * shredded** — and it reaches every equal-width menu that geometry cannot.
 *
 * This is Tesseract's direction of travel for text evidence (require textual
 * agreement before acting) and a different statistical position from the JA
 * particle cue reverted on 2026-07-01: that was one pair's final character
 * against a base rate of elliptical UI labels, this is a punctuation census
 * over ≥4 rows with a required majority.
 *
 * ## Enemy population and bounded failure
 *
 * - **Punctuation-free prose** is the exposure, and it is measured: 6 of 50
 *   prose blocks in the corpus carry no punctuation at all (a ZH rules list,
 *   three 3-row RU card descriptions, one 3-row EN game line, text_sample).
 *   With [minStackRows] = 4 and the [maxBlockWidthFraction] gate only the ZH
 *   rules list is actually exposed. Retro games that render dialogue without
 *   periods are where this lives, and the corpus barely covers them — the
 *   seeds most likely to falsify this strategy have not been collected yet.
 * - **Punctuated menus** stay merged (VN choice stacks whose options are full
 *   sentences; a description line chained onto a menu by pitch). One corpus
 *   seed, ZH, does this today.
 * - **OCR noise** manufactures punctuation: blanked menu slots read as `?????`,
 *   icon glyphs as `!` or `?`. Handled by construction — runs of identical
 *   punctuation collapse, and a terminator counts only when a word character
 *   precedes it. Without both hardenings FF12, FF15 and World of Final Fantasy
 *   are falsely protected as prose. A *missed* terminator flips a paragraph to
 *   "labels", which is the residual this cannot defend against.
 * - **A run spanning a paragraph and an adjacent menu at the same pitch** takes
 *   the paragraph's punctuation and declines to split — failing toward today's
 *   behavior for the menu half.
 *
 * ## Second change in the same idea: detect stacks capture-wide, not per group
 *
 * The production split asks its question about the group the greedy walk
 * happened to build, and that is where both remaining menu failure modes come
 * from. FF9's menu is seven rows but the walk builds a six-row group whose
 * widths cluster (false keep); Crisis Core's left menu is eight rows but the
 * walk builds two-row groups that fall under the `rows >= 4` floor and no
 * post-pass can ever repair them (starvation). Lowering the floor is not the
 * answer: at 3 rows the corpus's three RU card descriptions get shredded.
 *
 * So the stack is detected over the whole orientation partition — rows sharing
 * an alignment axis at constant pitch — and any group holding two rows of one
 * detected stack is split along its rows regardless of the group's own shape.
 * [usePunctuationBackstop] additionally applies the punctuation profile to a
 * group's own rows, so a group that no capture-wide run covered still gets the
 * new test instead of the old one.
 *
 * ## Contract
 *
 * Every knob is WIRED (the [GroupingRecipe] rule: a field that silently does
 * nothing makes sweep results lies), and the four here are exactly the
 * ablations measured host-side, so a catalog entry can reproduce any of them.
 * Determinism: no clock, no randomness, integer geometry — the harness's
 * byte-determinism claim for Meiki/Paddle survives.
 *
 * Grouping itself is delegated to [DefaultGroupingStrategy] with the width
 * zeroed, which is its documented "skip the menu split" path — so the pairwise
 * kernel, evidence layer, reading-order sort and source-script filter are
 * production's, and a column delta is attributable to this file alone.
 *
 * VERTICAL groups are passed through untouched, like the production split, and
 * for its reasons (`parentLeft`/`parentRight` are wrong for sibling columns
 * that share tops and bottoms, and vertical menus are rare next to vertical
 * prose). [includeVertical] exists to measure that abstention, not to defend
 * it; the corpus has no vertical menu seeds either way.
 *
 * The host-side twin of [punctuationVerdict] is `punct.py` from the same
 * session; the two must agree character-for-character or the measurement above
 * stops describing this code.
 */
class LabelStackStrategy(
    private val recipe: GroupingRecipe = GroupingRecipe.Default,
    /** Rows a run (or a group, for the backstop) needs before its punctuation
     *  profile is trusted. 4 is the measured floor: at 3 the corpus's RU card
     *  descriptions carry no punctuation and get shredded. */
    private val minStackRows: Int = 4,
    /** Cross-flow extent ceiling, as a fraction of the screenshot width. A weak
     *  backstop rather than the load-bearing gate it is in `isMenuLike`
     *  (measured: removing it costs 1 prose block, tightening it to ⅓ costs 1
     *  menu). */
    private val maxBlockWidthFraction: Float = 0.5f,
    /** How many rows may end a sentence before the block reads as running text,
     *  as a fraction of row count. At 0.2 a 4-row block is prose the moment ONE
     *  row ends a sentence. Measured: 0.0 costs 2 menus, 0.4 costs 2 prose. */
    private val maxSentenceEndFraction: Float = 0.2f,
    /** Capture-wide run detection (the fix for equal-width false keeps and for
     *  2-3 row starvation). Off = punctuation backstop only. */
    private val useCaptureStacks: Boolean = true,
    /** Apply the punctuation profile to a group's own rows when no detected
     *  stack covers it — the direct replacement for `isMenuLike`. */
    private val usePunctuationBackstop: Boolean = true,
    /** Judge vertical partitions too. See the kdoc: off mirrors production. */
    private val includeVertical: Boolean = false,
    override val name: String = "labelstack",
) : GroupingStrategy {

    override fun group(
        regions: List<RecognizedRegion>,
        ctx: GroupingContext,
    ): List<ProposedGroup> {
        // Production grouping, minus its menu split: width 0 is
        // DefaultGroupingStrategy's documented skip path.
        val raw = DefaultGroupingStrategy(recipe)
            .group(regions, ctx.copy(screenshotWidthInRegionSpace = 0f))
        val screenWidth = ctx.screenshotWidthInRegionSpace
        if (raw.isEmpty() || screenWidth <= 0f) return raw

        // Stack membership is per REGION so a group's rows can look it up
        // whatever subset of the capture the walk put in that group. Keyed by
        // IDENTITY, not structural equality: DefaultGroupingStrategy hands back
        // the very instances passed in, and two byte-identical regions from an
        // engine that emitted one line twice must stay two stack members.
        val stackOf: MutableMap<RecognizedRegion, Int> = java.util.IdentityHashMap()
        if (useCaptureStacks) {
            // Detect over the regions that SURVIVED the source-script filter,
            // not over the input: production's split sits after that filter too,
            // and a dropped garbage line (romanization, target-language UI
            // label) carrying a period would otherwise turn a whole menu column
            // PROSE and silently lose the stack. The cost is the mirror case — a
            // dropped line leaves a pitch hole that can break one run into two
            // sub-floor runs — which degrades to today's behavior instead of
            // inverting the verdict.
            val kept = raw.flatMap { it.regions }
            detectStacks(kept, TextOrientation.HORIZONTAL, screenWidth, ctx, stackOf)
            if (includeVertical) {
                detectStacks(kept, TextOrientation.VERTICAL, screenWidth, ctx, stackOf)
            }
        }

        return raw.flatMap { proposed ->
            splitIfLabelStack(proposed, screenWidth, stackOf, ctx)
        }
    }

    // ── Capture-wide stack detection ─────────────────────────────────────────

    /**
     * Bucket the partition's regions into alignment COLUMNS, chain each column
     * into maximal constant-pitch runs, and keep the runs whose punctuation
     * profile reads as labels. Writes stack ids into [stackOf].
     *
     * Columns first, and not for tidiness: chaining rows in global flow order
     * breaks on any interleaved column. FF9's menu sits at x≈1125 while party
     * names and HP values sit at x≈300 with tops that interleave in Y, so a
     * flow-order chain fails the axis test at every step and finds no stack at
     * all. Same shape on every FF party-menu screen and every stat panel.
     *
     * Detection works on REGIONS, not on [bandRows] rows, for a second reason:
     * banding merges a menu item with whatever shares its Y from another column
     * (`HP` / `Sphere Grid` / `Tidus` on the FF10 screen), which would both
     * pollute the punctuation profile and hand a stack id to regions that are
     * not stack members. Banding is still what the SPLIT uses, so an inline
     * `label: value` pair stays in one output group.
     *
     * A wrapped paragraph forms a run too — that is the point. The run detector
     * only finds something big enough to ask the question about; the
     * punctuation profile answers it.
     */
    private fun detectStacks(
        regions: List<RecognizedRegion>,
        orientation: TextOrientation,
        screenWidth: Float,
        ctx: GroupingContext,
        stackOf: MutableMap<RecognizedRegion, Int>,
    ) {
        val vertical = orientation == TextOrientation.VERTICAL
        val partition = regions.filter { (it.orientation == TextOrientation.VERTICAL) == vertical }
        if (partition.size < minStackRows) return
        val sorted =
            if (vertical) partition.sortedByDescending { it.box.bounds.right }
            else partition.sortedBy { it.box.bounds.top }

        var nextStackId = stackOf.values.maxOrNull()?.plus(1) ?: 0
        for (column in axisColumns(sorted, vertical)) {
            if (column.size < minStackRows) continue
            val rects = column.map { sorted[it].box.bounds }
            var start = 0
            while (start <= column.size - minStackRows) {
                // Grow a run from `start` while the pitch holds. Axis already
                // holds for the whole column by construction.
                var end = start
                var pitch = -1
                while (end + 1 < column.size) {
                    val step = flowStep(rects[end], rects[end + 1], vertical)
                    if (step <= 0) break
                    if (pitch < 0) {
                        pitch = step
                    } else if (kotlin.math.abs(step - pitch) > pitchTolerance(pitch)) {
                        break
                    }
                    end++
                }
                val length = end - start + 1
                if (length >= minStackRows) {
                    val members = (start..end).map { column[it] }
                    val extent = crossExtent(members.map { sorted[it].box.bounds }, vertical)
                    val texts = members.map { sorted[it].text }
                    val verdict = punctuationVerdict(texts)
                    val narrow = extent <= maxBlockWidthFraction * screenWidth
                    if (ctx.logDecisions) {
                        Log.d(
                            "DetectionLog",
                            "[stack] $length rows extent=$extent pitch=$pitch " +
                                "\"${texts.first().take(24).replace('\n', ' ')}\" :: " +
                                "${verdict.label} (ends=${verdict.ends} cont=${verdict.continuations}) " +
                                "narrow=$narrow",
                        )
                    }
                    if (verdict.isLabels && narrow) {
                        val id = nextStackId++
                        for (i in members) stackOf[sorted[i]] = id
                    }
                }
                // A run that reached the floor consumed its members either way:
                // restarting inside a PROSE run would re-ask the question of a
                // suffix that may have left the paragraph's only period behind
                // in row 1, and answer "labels". Skipping past is the safe
                // direction. A run too short to judge advances by one so an
                // off-rhythm leading row (a heading above a menu) cannot hide
                // the stack behind it.
                start = if (length >= minStackRows) end + 1 else start + 1
            }
        }
    }

    /**
     * Partition regions into alignment columns: a region joins the first column
     * whose MEDIAN start edge or MEDIAN center it agrees with (see
     * [axisAgrees]), else opens a new one. Median rather than last-member so a
     * single out-dented member (speaker prefix, leading bracket, a bullet)
     * cannot drag the column's axis and strand everything after it. Columns and
     * their members stay in flow order, so the result is deterministic.
     */
    private fun axisColumns(sorted: List<RecognizedRegion>, vertical: Boolean): List<List<Int>> {
        val columns = mutableListOf<MutableList<Int>>()
        for (i in sorted.indices) {
            val box = sorted[i].box.bounds
            val hit = columns.firstOrNull { col ->
                axisAgrees(axisRepresentative(col, sorted, vertical), box, vertical)
            }
            if (hit != null) hit += i else columns += mutableListOf(i)
        }
        return columns
    }

    /** A synthetic rect carrying the column's median start edge, median center
     *  and median extent — the axis a candidate is compared against. */
    private fun axisRepresentative(
        column: List<Int>,
        sorted: List<RecognizedRegion>,
        vertical: Boolean,
    ): Rect {
        val boxes = column.map { sorted[it].box.bounds }
        return if (vertical) {
            val top = median(boxes.map { it.top })
            val extent = median(boxes.map { it.width() })
            val centerY = median(boxes.map { it.centerY() })
            Rect(0, top, extent, top + 2 * (centerY - top))
        } else {
            val left = median(boxes.map { it.left })
            val extent = median(boxes.map { it.height() })
            val centerX = median(boxes.map { it.centerX() })
            Rect(left, 0, left + 2 * (centerX - left), extent)
        }
    }

    private fun median(values: List<Int>): Int {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    // ── Per-group decision ──────────────────────────────────────────────────

    private fun splitIfLabelStack(
        proposed: ProposedGroup,
        screenWidth: Float,
        stackOf: Map<RecognizedRegion, Int>,
        ctx: GroupingContext,
    ): List<ProposedGroup> {
        val group = proposed.regions
        if (group.isEmpty()) return listOf(proposed)
        val vertical = group.first().orientation == TextOrientation.VERTICAL
        if (vertical && !includeVertical) return listOf(proposed)

        val rows = bandRows(group.map { it.box.bounds }, vertical)
        if (rows.size < 2) return listOf(proposed)
        val rects = rows.map { idxs -> unionRect(idxs.map { group[it].box.bounds }) }

        // (1) two rows of one capture-wide stack in this group ⇒ split, whatever
        // the group's own shape says. This is what reaches the 2-3 row groups
        // the `rows >= 4` floor can never see.
        //
        // PARTICIPATING stacks only, and the split is restricted to their rows:
        // the trigger is a property of two rows, so it cannot justify acting on
        // the whole group. If the walk merged a paragraph with two menu rows,
        // splitting every row would shred the paragraph as collateral — and the
        // corpus could not see it, because expectations are partial and
        // unanchored content constrains nothing in either direction. That is a
        // benchmark-poisoning shape, not just a wrong answer. Rows outside a
        // participating stack stay together as contiguous spans.
        // (The backstop path below is different and DOES split every row: there
        // the verdict is a property of the whole group.)
        val ids = rows.map { idxs ->
            idxs.firstNotNullOfOrNull { stackOf[group[it]] } ?: -1
        }
        val participating = ids.filter { it >= 0 }
            .groupingBy { it }.eachCount()
            .filterValues { it >= 2 }.keys
        val sharedStack = participating.isNotEmpty()

        // (2) backstop: the group's own rows, judged by punctuation instead of
        // by edge clustering.
        val ownVerdict = if (!sharedStack && usePunctuationBackstop && rows.size >= minStackRows) {
            val extent = crossExtent(rects, vertical)
            if (extent <= maxBlockWidthFraction * screenWidth) {
                punctuationVerdict(rows.map { rowText(it, group, vertical) })
            } else null
        } else null

        if (!sharedStack && ownVerdict?.isLabels != true) return listOf(proposed)

        // Overlay pins come from the rows actually being split, not from the
        // whole group: a mixed group's union spans content the stack's column
        // does not, and pinning a menu row to it would stretch its overlay.
        val pinned: List<Int> =
            if (sharedStack) rows.indices.filter { ids[it] in participating } else rows.indices.toList()
        val left = pinned.minOf { rects[it].left }
        val right = pinned.maxOf { rects[it].right }

        val out = ArrayList<ProposedGroup>(rows.size)
        if (sharedStack) {
            val span = ArrayList<Int>()
            fun flushSpan() {
                if (span.isEmpty()) return
                // Preserved as-is: no parent pins, since this run is not part of
                // the stack's column and its own bounds are the right ones.
                out += ProposedGroup(span.flatMap { rows[it] }.map { group[it] })
                span.clear()
            }
            for (i in rows.indices) {
                if (ids[i] in participating) {
                    flushSpan()
                    out += ProposedGroup(rows[i].map { group[it] }, parentLeft = left, parentRight = right)
                } else {
                    span += i
                }
            }
            flushSpan()
        } else {
            // Whole-group verdict ⇒ whole-group split, production's semantics.
            rows.mapTo(out) { idxs ->
                ProposedGroup(idxs.map { group[it] }, parentLeft = left, parentRight = right)
            }
        }
        if (ctx.logDecisions) {
            val split = if (sharedStack) pinned.size else rows.size
            Log.d(
                "DetectionLog",
                "[stack-split] $split of ${rows.size} rows w=${right - left} " +
                    "\"${(group.firstOrNull()?.text ?: "").take(24).replace('\n', ' ')}\" :: " +
                    if (sharedStack) "capture stack (${out.size - split} span(s) preserved)"
                    else "own punctuation profile",
            )
        }
        return out
    }

    // ── The punctuation profile ──────────────────────────────────────────────

    /** Verdict plus the two counts, so a log line explains itself. */
    class Verdict(val isLabels: Boolean, val ends: Int, val continuations: Int) {
        val label: String get() = if (isLabels) "LABELS" else "PROSE"
    }

    /**
     * Does this set of row texts read as running text or as a set of labels?
     *
     * PROSE on either witness:
     *  - any row CONTINUES a sentence: a terminator strictly inside the row
     *    (so the row is a slice of running text), or a row-final comma, or an
     *    unclosed bracket;
     *  - more than [maxSentenceEndFraction] of rows END a sentence.
     *
     * A terminator only counts when a word character precedes it, and runs of
     * identical punctuation collapse first: both guards exist because OCR
     * writes `?????` for blanked menu slots and `!`/`?` for icon glyphs, and
     * without them three FF menus read as prose.
     */
    fun punctuationVerdict(rowTexts: List<String>): Verdict {
        var ends = 0
        var continuations = 0
        for (raw in rowTexts) {
            val t = normalizeForProfile(raw)
            if (t.isEmpty()) continue
            val last = t[t.length - 1]
            if (last in SENTENCE_CLOSERS ||
                (last in HARD_TERMINATORS && t.length > 1 && t[t.length - 2].isWordish())
            ) {
                ends++
            }
            val interiorTerminator = (0 until t.length - 1).any { i ->
                t[i] in HARD_TERMINATORS && i > 0 && t[i - 1].isWordish()
            }
            val unclosed = t.count { it in OPENING_BRACKETS } - t.count { it in CLOSING_BRACKETS }
            if (interiorTerminator || last in COMMAS || unclosed > 0) continuations++
        }
        val isLabels = continuations == 0 && ends <= maxSentenceEndFraction * rowTexts.size
        return Verdict(isLabels, ends, continuations)
    }

    /** Strip all whitespace, then collapse runs of the same punctuation mark so
     *  a blanked-out menu slot (`?????`) cannot fake sentence structure. */
    private fun normalizeForProfile(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            if (c.isWhitespace()) continue
            if (out.isNotEmpty() && out[out.length - 1] == c && !c.isWordish()) continue
            out.append(c)
        }
        return out.toString()
    }

    // ── Geometry helpers (mirrors of the production shapes) ──────────────────

    /**
     * Mirror of `LayoutAnalyzer.rowBands`: boxes whose cross-flow spans overlap
     * by at least half the smaller extent share a row, so an inline
     * `label: value` pair collapses into one row. Reimplemented here rather
     * than called so this file stays self-contained in androidTest; it must
     * stay faithful or a column delta stops being attributable to the rule.
     */
    private fun bandRows(boxes: List<Rect>, vertical: Boolean): List<List<Int>> {
        if (boxes.isEmpty()) return emptyList()
        val order =
            if (vertical) boxes.indices.sortedByDescending { boxes[it].right }
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
                bandLo = minOf(bandLo, lo)
                bandHi = maxOf(bandHi, hi)
            } else {
                rows += mutableListOf(i)
                bandLo = lo
                bandHi = hi
            }
        }
        return rows
    }

    /** Row text in reading order, so an inline pair reads by position rather
     *  than by OCR emission order. */
    private fun rowText(
        rowIdx: List<Int>,
        regions: List<RecognizedRegion>,
        vertical: Boolean,
    ): String {
        val ordered =
            if (vertical) rowIdx.sortedBy { regions[it].box.bounds.top }
            else rowIdx.sortedBy { regions[it].box.bounds.left }
        return ordered.joinToString(" ") { regions[it].text.trim() }.trim()
    }

    /**
     * Do two rows sit on a shared alignment axis? Start edge OR center, with
     * the center allowed more slack than the start: a start edge is pinned to
     * the margin by the layout engine, while a centered row is pinned only to
     * the nearest half cell when the renderer centers on a character grid
     * (retro/pixel fonts).
     */
    private fun axisAgrees(a: Rect, b: Rect, vertical: Boolean): Boolean {
        val ref = if (vertical) maxOf(a.width(), b.width()) else maxOf(a.height(), b.height())
        if (ref <= 0) return false
        val startDelta =
            if (vertical) kotlin.math.abs(a.top - b.top) else kotlin.math.abs(a.left - b.left)
        val centerDelta =
            if (vertical) kotlin.math.abs(a.centerY() - b.centerY())
            else kotlin.math.abs(a.centerX() - b.centerX())
        return startDelta <= (ref * 0.5f).toInt() || centerDelta <= (ref * 0.75f).toInt()
    }

    /** Center-to-center distance along the flow axis, positive downstream. */
    private fun flowStep(a: Rect, b: Rect, vertical: Boolean): Int =
        if (vertical) a.centerX() - b.centerX() else b.centerY() - a.centerY()

    /** Extent across the flow axis: block width for horizontal text, block
     *  height for vertical. */
    private fun crossExtent(rects: List<Rect>, vertical: Boolean): Int =
        if (vertical) rects.maxOf { it.bottom } - rects.minOf { it.top }
        else rects.maxOf { it.right } - rects.minOf { it.left }

    private fun unionRect(rects: List<Rect>): Rect = Rect(
        rects.minOf { it.left }, rects.minOf { it.top },
        rects.maxOf { it.right }, rects.maxOf { it.bottom },
    )

    /** Same shape as the production pitch tolerance: 15%, floored at 3px so a
     *  tiny pitch cannot demand sub-pixel agreement. */
    private fun pitchTolerance(pitch: Int): Int = maxOf(3, (pitch * 0.15f).toInt())

    private companion object {
        // These four sets are DELIBERATELY the host twin's (punct.py), not
        // LayoutAnalyzer's larger TERMINAL_END_CHARS / bracket sets: the 22-of-23
        // and 1-of-23 numbers in the class kdoc were measured with exactly these
        // members, and a superset here would make the measurement stop
        // describing this code. LayoutAnalyzer additionally carries 《〉〈》﹁﹃﹂﹄
        // and '’ — inert on every corpus seed, and a candidate widening only
        // once a seed needs one.
        val HARD_TERMINATORS = setOf('。', '．', '.', '!', '?', '！', '？', '…', '‥')
        val COMMAS = setOf(',', '、', '，', '،', '؛')
        val OPENING_BRACKETS = setOf('「', '『', '（', '(', '【', '[', '{', '“')
        val CLOSING_BRACKETS = setOf('」', '』', '）', ')', '】', ']', '}', '”')

        /** Closers that END a sentence. '”' is in [CLOSING_BRACKETS] for balance
         *  accounting but NOT here: a closing quote mid-dialogue ends the quote,
         *  not the sentence, and counting it would read quoted prose as labels. */
        val SENTENCE_CLOSERS = setOf('」', '』', '】', '）', ')', ']', '}')

        /** Letters and digits from any script — what must precede a mark for it
         *  to be sentence punctuation rather than decoration. (The twin admits
         *  Unicode categories "L" and "N"; this admits letters plus Nd,
         *  differing only on Nl and No numerals, which no corpus seed
         *  contains.) */
        fun Char.isWordish(): Boolean = isLetterOrDigit()
    }
}
