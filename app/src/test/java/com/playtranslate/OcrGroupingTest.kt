package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ocr.core.LayoutAnalyzer
import com.playtranslate.ocr.core.LayoutAnalyzer.groupBoxesOnePass
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [LayoutAnalyzer.groupBoxesOnePass] — the index-level
 * grouping pass that powers `OcrManager.groupLinesOnePass`. Tests use plain
 * [Rect]s so they don't need to fabricate ML Kit [Text.Line] objects; the
 * companion-level function is the same algorithm the production wrapper
 * runs after extracting boxes/align-lefts from Text.Lines.
 *
 * The "user case" test mirrors the coordinates from the Vietnamese Wikipedia
 * capture that motivated the multi-group walk fix (a right-column sidebar
 * entry interleaving in top-Y sort order between two body lines of the
 * same paragraph) — the body lines must still cluster into one group, with
 * the sidebar landing in its own group.
 */
@RunWith(RobolectricTestRunner::class)
class OcrGroupingTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        Rect(left, top, right, bottom)

    private fun group(
        boxes: List<Rect>,
        alignLefts: List<Int?>? = null,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ): List<List<Int>> {
        val lefts = alignLefts ?: boxes.map { it.left }
        return groupBoxesOnePass(boxes, lefts, orientation)
    }

    // ── shape / edge cases ───────────────────────────────────────────────

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList<List<Int>>(), group(emptyList()))
    }

    @Test
    fun singleLine_returnsSingleGroup() {
        val groups = group(listOf(box(0, 0, 100, 50)))
        assertEquals(listOf(listOf(0)), groups)
    }

    @Test
    fun mismatchedAlignLefts_throws() {
        try {
            groupBoxesOnePass(
                boxes = listOf(box(0, 0, 100, 50), box(0, 80, 100, 130)),
                alignLefts = listOf(0),
                orientation = TextOrientation.HORIZONTAL,
            )
            error("expected require() to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("alignLefts"))
        }
    }

    // ── single-paragraph baselines ───────────────────────────────────────

    @Test
    fun twoBodyLines_sameParagraph_merge() {
        // Tight vertical gap (dy=30, refH=50, threshold=40), aligned lefts,
        // matching heights → one group.
        val groups = group(listOf(
            box(100, 0, 1000, 50),
            box(100, 80, 1000, 130),
        ))
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    @Test
    fun twoBodyLines_paragraphGap_split() {
        // dy=150 vastly exceeds 0.8 * refH=40 → separate paragraphs.
        val groups = group(listOf(
            box(100, 0, 1000, 50),
            box(100, 200, 1000, 250),
        ))
        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    @Test
    fun threeBodyLines_allMerge() {
        val groups = group(listOf(
            box(100, 0, 1000, 50),
            box(100, 80, 1000, 130),
            box(100, 160, 1000, 210),
        ))
        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun rtlArabic_raggedLeftSharedRight_mergesWhenRtl() {
        // Arabic over-fragmentation case (from a real capture): paragraph lines
        // share a RIGHT edge but have ragged LEFT edges (a short line starts far to
        // the right). With rtl=true the grouper aligns on the right edge so the
        // short line stays in the paragraph; with rtl=false (LTR, left-aligned) it
        // splits off — the bug this fixes. vgap is constant, isolating alignment.
        val boxes = listOf(
            box(160, 0, 1760, 36),     // full-width line
            box(1000, 40, 1762, 76),   // short line right below — right-aligned, ragged left
        )
        val lefts = boxes.map { it.left }
        assertEquals(
            "RTL aligns on the right edge → one paragraph",
            listOf(listOf(0, 1)),
            groupBoxesOnePass(boxes, lefts, TextOrientation.HORIZONTAL, rtl = true),
        )
        assertEquals(
            "LTR aligns on the left edge → short line fragments off",
            listOf(listOf(0), listOf(1)),
            groupBoxesOnePass(boxes, lefts, TextOrientation.HORIZONTAL, rtl = false),
        )
    }

    @Test
    fun farApartLines_eachOwnGroup() {
        // Three lines, each separated by huge vertical gaps. The multi-group
        // walk must not chain anything just because earlier groups exist.
        val groups = group(listOf(
            box(100, 0, 200, 50),
            box(100, 1000, 200, 1050),
            box(100, 2000, 200, 2050),
        ))
        assertEquals(3, groups.size)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    // ── multi-column / interleaving (the bug we fixed) ───────────────────

    @Test
    fun twoColumnsSideBySide_stayInOwnGroups() {
        // Body column at left, sidebar column at right. The sidebar lines
        // are interleaved in top-Y sort order with body lines, but each
        // column's lines must cluster together.
        val groups = group(listOf(
            box(100, 0, 1000, 50),       // body 1
            box(100, 80, 1000, 130),     // body 2 (merges into body group)
            box(2000, 80, 2500, 130),    // sidebar 1
            box(2000, 160, 2500, 210),   // sidebar 2 (merges into sidebar group)
        ))
        val bodyGroup = groups.first { 0 in it }
        val sidebarGroup = groups.first { 2 in it }
        assertEquals(listOf(0, 1), bodyGroup)
        assertEquals(listOf(2, 3), sidebarGroup)
        assertEquals(2, groups.size)
    }

    @Test
    fun vietnameseWikipediaCapture_sidebarInterleavesSixLineBody() {
        // Exact rects from the 2026-05-12 capture (DetectionLog, 12:03:35).
        // A right-column sidebar entry's top-Y (158) falls between body 2
        // (top=110) and body 3 (top=186) in sort order. Pre-fix the body
        // paragraph split 2 + 4 because the algorithm only compared each
        // line to the last group's last line. Post-fix all six body lines
        // must stay in one group and the sidebar is alone in another.
        val body1 = box(170, 28, 1283, 79)
        val body2 = box(178, 110, 1183, 165)
        val sidebar = box(1745, 158, 2151, 204)
        val body3 = box(168, 186, 1268, 248)
        val body4 = box(196, 279, 1205, 331)
        val body5 = box(173, 360, 1295, 414)
        val body6 = box(172, 444, 819, 495)

        val groups = group(listOf(body1, body2, sidebar, body3, body4, body5, body6))

        // Two groups total: 6-line body + 1-line sidebar.
        assertEquals(2, groups.size)
        val bodyGroup = groups.first { 0 in it }
        val sidebarGroup = groups.first { 2 in it }
        assertEquals(listOf(0, 1, 3, 4, 5, 6), bodyGroup)
        assertEquals(listOf(2), sidebarGroup)
    }

    @Test
    fun sidebarInterleavesBetweenTwoParagraphs_doesNotFalselyJoin() {
        // Body paragraph 1 → sidebar → body paragraph 2 with a true gap
        // between paragraphs (dy much larger than threshold). The fix
        // must NOT merge paragraph 2's first line back into paragraph 1
        // when reaching across the sidebar.
        val groups = group(listOf(
            box(100, 0, 1000, 50),       // para1 line a
            box(100, 80, 1000, 130),     // para1 line b
            box(2000, 100, 2500, 150),   // sidebar
            box(100, 500, 1000, 550),    // para2 line a (huge gap from para1)
            box(100, 580, 1000, 630),    // para2 line b
        ))
        // Body para1 = [0,1]; sidebar = [2]; body para2 = [3,4].
        assertEquals(3, groups.size)
        val para1 = groups.first { 0 in it }
        val sidebar = groups.first { 2 in it }
        val para2 = groups.first { 3 in it }
        assertEquals(listOf(0, 1), para1)
        assertEquals(listOf(2), sidebar)
        assertEquals(listOf(3, 4), para2)
    }

    // ── orientation ──────────────────────────────────────────────────────

    @Test
    fun verticalOrientation_columnLinesMerge() {
        // Two vertical "lines" (CJK columns) in the same column, sorted
        // right-to-left in production. Here we pass one column, so it
        // should merge into a single group.
        val groups = group(
            boxes = listOf(
                box(800, 100, 850, 500),
                box(800, 520, 850, 900),
            ),
            alignLefts = listOf(null, null),
            orientation = TextOrientation.VERTICAL,
        )
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    // ── tie-breaking on multi-group walk ─────────────────────────────────

    @Test
    fun candidateMatchingMultipleGroups_joinsMostRecent() {
        // Two body groups stacked, then a sidebar (different column), then
        // a candidate that geometrically could merge with EITHER body group
        // by vertical gap alone. The walk visits most-recent-first and
        // breaks on first match, so it must land in the second body group.
        //
        // Lines below all use box height 50 (refH=50). vgapThreshold = 40.
        //
        //   y=  0..50    para A
        //   y= 80..130   para A continuation
        //   y=160..210   ← gap of 30 ⇒ would merge with A (last bottom=130, dy=30)
        //
        // To get a TRUE "could merge with either," widen A's last line so its
        // bottom sits high enough that the candidate matches A as well. We
        // use sequence: A1, A2 (merges), then a SHORT line dropped into
        // a different column (sidebar) at sort-order position 2, then a
        // candidate that geometrically matches A2 — but A2 is no longer the
        // last group's last line, sidebar is. Most-recent first means we
        // reach A2 by walking back, MERGE there, and stop.
        //
        // We can't easily construct a "could match A *and* a separate B"
        // case without breaking the geometry, so the simpler assertion is
        // that walking back across an unrelated intermediate group still
        // reaches A — which the Vietnamese Wikipedia case already covers.
        // This test is a focused re-statement using minimal geometry.
        val groups = group(listOf(
            box(100, 0, 1000, 50),       // A1
            box(100, 80, 1000, 130),     // A2 (merge with A1)
            box(2000, 100, 2500, 150),   // sidebar (breaks the chain)
            box(100, 160, 1000, 210),    // candidate: should rejoin A group
        ))
        val aGroup = groups.first { 0 in it }
        assertEquals(listOf(0, 1, 3), aGroup)
        assertEquals(2, groups.size)
    }

    // ── log toggle doesn't affect grouping output ────────────────────────

    @Test
    fun loggingToggle_doesNotChangeGrouping() {
        val boxes = listOf(
            box(170, 28, 1283, 79),
            box(178, 110, 1183, 165),
            box(1745, 158, 2151, 204),
            box(168, 186, 1268, 248),
        )
        val lefts = boxes.map { it.left }
        val withoutLog = groupBoxesOnePass(boxes, lefts, TextOrientation.HORIZONTAL, logDecisions = false)
        val withLog = groupBoxesOnePass(
            boxes, lefts, TextOrientation.HORIZONTAL,
            logDecisions = true,
            texts = listOf("body 1", "body 2", "sidebar", "body 3"),
        )
        assertEquals(withoutLog, withLog)
    }

    // ── wouldGroup: per-line normalization invariants ────────────────────

    @Test
    fun wouldGroup_horizontal_positiveRectSmallerThanLineCount_keepsSizeGuard() {
        // Degenerate-looking input: positive height but smaller than the
        // reported lineCount. Integer division would collapse per-line
        // height to 0 and the `lo <= 0 → compatible` short-circuit in the
        // block check would silently bypass the size-ratio guard. The
        // coerce-to-1 invariant keeps the guard active so a tiny rect
        // doesn't group with a much larger aligned neighbor.
        val tiny = Rect(0, 0, 200, 3)        // h=3
        val normal = Rect(0, 10, 200, 60)    // h=50, dy=7 from tiny.bottom
        assertFalse(
            "positive multi-line rect of height 3 must not group with a 50px neighbor",
            LayoutAnalyzer.wouldGroup(
                tiny, normal, TextOrientation.HORIZONTAL,
                aLineCount = 4, bLineCount = 1,
            ),
        )
    }

    @Test
    fun wouldGroup_vertical_positiveRectSmallerThanLineCount_keepsSizeGuard() {
        // Mirror of the horizontal case for vertical text (column-count
        // normalization on the width axis).
        val tiny = Rect(0, 0, 3, 200)        // w=3
        val normal = Rect(10, 0, 60, 200)    // w=50, dx=7 from tiny.right
        assertFalse(
            "positive multi-column rect of width 3 must not group with a 50px neighbor",
            LayoutAnalyzer.wouldGroup(
                tiny, normal, TextOrientation.VERTICAL,
                aLineCount = 4, bLineCount = 1,
            ),
        )
    }

    // ── shortAboveLongBlock: the 1/3 width/height guard ──────────────────

    @Test
    fun shortAboveLong_horizontal_speakerName_splits() {
        // Classic VN/RPG layout: short speaker name (or label) above a
        // wider dialogue line. The rule must split them so the name keeps
        // its own overlay/translation context.
        val name = box(100, 0, 200, 50)        // w=100
        val dialogue = box(100, 80, 1000, 130) // w=900 → 100*3 < 900 fires
        val groups = group(listOf(name, dialogue))
        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    @Test
    fun shortAboveLong_horizontal_nameAboveTwoLineParagraph() {
        // Three lines: short name, then a 2-line paragraph. The rule must
        // isolate the name, but the paragraph's own two lines must still
        // cluster together (the rule is asymmetric and only fires when the
        // *earlier* line is the short one).
        val groups = group(listOf(
            box(100, 0, 200, 50),         // name, w=100
            box(100, 80, 1000, 130),      // dialogue line 1, w=900
            box(100, 160, 1000, 210),     // dialogue line 2, w=900
        ))
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups)
    }

    @Test
    fun shortAboveLong_horizontal_longAboveShort_merges() {
        // Inverse direction: long paragraph closing with a short tail line.
        // Rule must NOT fire — existing geometry merges them as a trailing
        // wrap. Asymmetric rule by design.
        val groups = group(listOf(
            box(100, 0, 1000, 50),        // body line, w=900
            box(100, 80, 300, 130),       // short tail, w=200, ratio 200/900 below 1/3
        ))
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    @Test
    fun shortAboveLong_horizontal_exactlyOneThirdWidth_merges() {
        // Strict less-than threshold: at exactly 1/3× ratio, the rule does
        // NOT fire. Existing geometry decides — here heights and alignment
        // match, so they merge.
        val groups = group(listOf(
            box(100, 0, 200, 50),         // earlier, w=100
            box(100, 80, 400, 130),       // later,   w=300 → 100*3 = 300, NOT < 300
        ))
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    @Test
    fun shortAboveLong_vertical_adjacentShortColumn_merges() {
        // Vertical text reads right-to-left. A short rightward column that
        // continues into a much longer leftward column (e.g. 今夜は →
        // あちらの高原にて…) now MERGES: vertical text is exempt from the
        // short-above-long size guard, so the geometric block check (adjacent
        // columns, top-aligned) clusters them into one paragraph.
        val rightColumn = box(190, 0, 233, 132)   // h=132, the sentence head
        val leftColumn = box(137, 4, 178, 428)    // h=424, gap 12px, topΔ 4
        val groups = group(
            listOf(rightColumn, leftColumn),
            orientation = TextOrientation.VERTICAL,
        )
        assertEquals(listOf(listOf(0, 1)), groups)
    }

    @Test
    fun shortAboveLongBlock_overlappingRects_returnsNull() {
        // Rule only fires when rects are cleanly separated on the reading
        // axis. Any overlap defers to the existing geometry (inline path /
        // block path) so we don't double-judge.
        val a = Rect(0, 0, 50, 30)
        val b = Rect(0, 25, 200, 60)   // vertical overlap 25..30
        assertNull(LayoutAnalyzer.shortAboveLongBlock(a, b, TextOrientation.HORIZONTAL))
    }

    @Test
    fun shortAboveLongBlock_longAboveShort_returnsNull() {
        // Asymmetric: earlier=long, later=short → rule must not fire.
        val long = Rect(0, 0, 200, 30)
        val short = Rect(0, 50, 50, 80)
        assertNull(LayoutAnalyzer.shortAboveLongBlock(long, short, TextOrientation.HORIZONTAL))
    }

    @Test
    fun shortAboveLongBlock_horizontal_argOrderIndependent() {
        // Caller may pass rects in either order. The rule determines
        // earlier/later from spatial position, not argument position.
        val above = Rect(100, 0, 200, 50)         // w=100
        val below = Rect(100, 80, 1000, 130)      // w=900
        assertTrue(LayoutAnalyzer.shortAboveLongBlock(above, below, TextOrientation.HORIZONTAL) != null)
        assertTrue(LayoutAnalyzer.shortAboveLongBlock(below, above, TextOrientation.HORIZONTAL) != null)
    }

    @Test
    fun shortAboveLongBlock_vertical_returnsNull() {
        // Vertical text is exempt from the size guard: a short rightward column
        // never blocks grouping with a long leftward one on size alone (the
        // height ratio would have fired before this change).
        val rightColumn = Rect(200, 0, 250, 50)   // h=50
        val leftColumn = Rect(140, 0, 190, 500)   // h=500 → 50*3 < 500
        assertNull(LayoutAnalyzer.shortAboveLongBlock(rightColumn, leftColumn, TextOrientation.VERTICAL))
        assertNull(LayoutAnalyzer.shortAboveLongBlock(leftColumn, rightColumn, TextOrientation.VERTICAL))
    }

    @Test
    fun shortAboveLong_horizontal_centeredShortLongLong_splitsThenMerges() {
        // Centered short-title-style line + two centered long body lines.
        // The rule isolates the short top, but the two longer lines must
        // still cluster together via the existing centerAligned block
        // path — the asymmetry doesn't cascade. Once the short top splits
        // off, subsequent long-equals-long comparisons fall to existing
        // geometry, which merges them as a normal centered paragraph.
        val groups = group(listOf(
            box(400, 0, 500, 50),      // centerX=450, w=100 (short top)
            box(50, 80, 850, 130),     // centerX=450, w=800 (long line 1)
            box(50, 160, 850, 210),    // centerX=450, w=800 (long line 2)
        ))
        assertEquals(listOf(listOf(0), listOf(1, 2)), groups)
    }

    @Test
    fun shortAboveLong_horizontal_centeredWrap_splits_pinnedBehavior() {
        // Behavior pin: a centered first line < 1/3× the centered second
        // line's width is split, even though the existing block path would
        // otherwise merge via centerAligned. The size-block runs before
        // alignment checks.
        //
        // Natural center-aligned wrapping (engine fills first line, breaks
        // when full) overwhelmingly produces long-above-short, NOT
        // short-above-long; the inverse direction requires an authored
        // break or punctuation-forced early termination, both of which
        // signal intent rather than continuation. Splitting matches that
        // intent for VN/RPG layouts. If a future use case (cutscene-style
        // subtitling, manga bubbles) requires keeping these centered
        // short-above-long wraps grouped, refine the rule to consult
        // alignment + gap evidence and update this test.
        val groups = group(listOf(
            box(400, 0, 500, 50),     // centerX=450, w=100
            box(50, 80, 850, 130),    // centerX=450, w=800 → 100*3 < 800 fires
        ))
        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    // ── blockGap: generous-leading paragraphs ───────────────────────────

    @Test
    fun blockGap_generousLeadingParagraph_staysOneGroup() {
        // Uniform body paragraph: line pitch ~58px, line heights 31-37px, so inter-line
        // gaps run 24-27px (≈0.84× height) — right at the old 0.8× cliff that fragmented
        // it mid-paragraph. At the 0.9× multiplier they merge; the next paragraph (60px
        // gap, ~1.9× height) still splits.
        val groups = group(listOf(
            box(165, 164, 1293, 200),   // line 1, h=36
            box(166, 224, 1369, 255),   // line 2, gap 24
            box(165, 282, 1365, 314),   // line 3, gap 27 (0.84×32)
            box(166, 340, 1266, 371),   // line 4, gap 26
            box(167, 431, 1676, 463),   // next paragraph, gap 60 (1.9×)
        ))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4)), groups)
    }

    // ── splitMenuGroups: row-based counting (vs raw regions) ─────────────

    @Test
    fun rowBands_inlinePairCollapsesToOneRow() {
        // Arctic Gale card body: title / body line / (label + inline value).
        // 4 OCR boxes but 3 visual rows — the inline "4 (every…)" joins the
        // "Gust Area Damage:" row instead of counting as a 4th menu item.
        val boxes = listOf(
            Rect(557, 505, 752, 532),    // ARCTIC GALE         (row 0)
            Rect(557, 540, 1090, 563),   // Your Casts also…    (row 1)
            Rect(558, 573, 761, 599),    // Gust Area Damage:   (row 2)
            Rect(956, 574, 1142, 596),   // 4 (every 0.25 Sec.) inline on row 2
        )
        val rows = LayoutAnalyzer.rowBands(boxes, TextOrientation.HORIZONTAL)
        assertEquals(3, rows.size)
        assertEquals(listOf(2, 3), rows[2])   // inline pair shares row 2
    }

    @Test
    fun rowBands_distinctLines_eachOwnRow() {
        val boxes = listOf(
            Rect(0, 0, 100, 20),
            Rect(0, 30, 100, 50),
            Rect(0, 60, 100, 80),
        )
        assertEquals(3, LayoutAnalyzer.rowBands(boxes, TextOrientation.HORIZONTAL).size)
    }

    @Test
    fun rowBands_vertical_bandsByColumn() {
        // Vertical text bands on the X axis (columns). Boxes 0 and 2 x-overlap →
        // same column; box 1 is a separate column.
        val boxes = listOf(
            Rect(200, 0, 250, 400),   // right column
            Rect(100, 0, 150, 400),   // left column
            Rect(210, 0, 240, 200),   // x-overlaps box 0 → joins its column
        )
        assertEquals(2, LayoutAnalyzer.rowBands(boxes, TextOrientation.VERTICAL).size)
    }

    @Test
    fun isMenuLike_justifiedParagraph_false() {
        // Both edges clustered → wrapped/justified block, not a menu.
        val rows = listOf(
            Rect(100, 0, 400, 20), Rect(100, 30, 400, 50),
            Rect(100, 60, 400, 80), Rect(100, 90, 400, 110),
        )
        assertFalse(LayoutAnalyzer.isMenuLike(rows, screenWidth = 1500f))
    }

    @Test
    fun isMenuLike_narrowLeftClusteredRightRagged_true() {
        val rows = listOf(
            Rect(100, 0, 250, 20), Rect(100, 30, 400, 50),
            Rect(100, 60, 300, 80), Rect(100, 90, 200, 110),
        )
        assertTrue(LayoutAnalyzer.isMenuLike(rows, screenWidth = 1500f))
    }

    @Test
    fun isMenuLike_wideGroup_false() {
        // A group spanning ≥ ⅓ of the screen is never treated as a menu.
        val rows = listOf(
            Rect(100, 0, 700, 20), Rect(100, 30, 700, 50),
            Rect(100, 60, 700, 80), Rect(100, 90, 700, 110),
        )
        assertFalse(LayoutAnalyzer.isMenuLike(rows, screenWidth = 1500f))
    }

    // ── readingOrderIndices: inline order robust to top jitter ───────────

    @Test
    fun readingOrderIndices_inlinePair_leftOrderDespiteTopJitter() {
        // Same-line label + inline value, but OCR gave the right-hand value a
        // slightly SMALLER top than the label. A pure top-sort would emit the
        // value first; reading order must keep the label first (left within row).
        val boxes = listOf(
            Rect(558, 574, 761, 599),    // "Gust Area Damage:"   label (top 574)
            Rect(956, 573, 1142, 596),   // "4 (every 0.25 Sec.)" value (top 573, jittered up)
        )
        assertEquals(listOf(0, 1), LayoutAnalyzer.readingOrderIndices(boxes, TextOrientation.HORIZONTAL))
    }

    @Test
    fun readingOrderIndices_multiRow_rowsTopToBottom_leftWithinRow() {
        val boxes = listOf(
            Rect(100, 0, 400, 20),       // row 0
            Rect(300, 30, 450, 52),      // row 1, value (right), top 30
            Rect(100, 31, 280, 50),      // row 1, label (left), top 31 (jittered down)
        )
        assertEquals(listOf(0, 2, 1), LayoutAnalyzer.readingOrderIndices(boxes, TextOrientation.HORIZONTAL))
    }
}
