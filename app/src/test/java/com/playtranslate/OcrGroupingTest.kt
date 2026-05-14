package com.playtranslate

import android.graphics.Rect
import com.playtranslate.OcrManager
import com.playtranslate.OcrManager.Companion.groupBoxesOnePass
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [OcrManager.Companion.groupBoxesOnePass] — the index-level
 * grouping pass that powers [OcrManager.groupLinesOnePass]. Tests use plain
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
            OcrManager.wouldGroup(
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
            OcrManager.wouldGroup(
                tiny, normal, TextOrientation.VERTICAL,
                aLineCount = 4, bLineCount = 1,
            ),
        )
    }
}
