package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.LineAssembler
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [LineAssembler] — the POST-recognition word→line stitcher that
 * reassembles PaddleOCR DBNet's per-word recognitions into line regions.
 *
 * Two layers:
 *  - [LineAssembler.assembleLineIndices] — the pure rect kernel (band by vertical
 *    center against the line's running-mean height + a height-compatibility guard,
 *    then split on column gaps). Tests are script-agnostic synthetic [Rect]s.
 *  - [LineAssembler.assembleLines] — the post-recognition wrapper that stitches the
 *    recognized text and applies the collective-orientation guard.
 *
 * The headline test reproduces the actual 14 horizontal DBNet boxes from the
 * on-device DetectionLog ("это забавно…" capture). The mixed-scale tests pin the
 * `height_ths` / local-line-height guards ported from EasyOCR `group_text_box`
 * (the cases an adversarial review flagged: large boxes must not pull small rows
 * together, and different-scale text on one row must not merge).
 */
@RunWith(RobolectricTestRunner::class)
class LineAssemblyTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = Rect(left, top, right, bottom)

    private fun region(text: String, r: Rect, orientation: TextOrientation = TextOrientation.HORIZONTAL) =
        RecognizedRegion(
            text = text,
            box = OcrBox.upright(r),
            orientation = orientation,
            confidence = 0.9f,
            lines = listOf(RecognizedLine(text, OcrBox.upright(r), orientation)),
            origin = RegionOrigin.LINE,
        )

    /** Assert [groups] partitions exactly indices 0 until [n] with the given size multiset. */
    private fun assertPartition(groups: List<List<Int>>, n: Int, sizes: List<Int>) {
        assertEquals("group sizes", sizes.sorted(), groups.map { it.size }.sorted())
        assertEquals("covers every index exactly once", (0 until n).toList(), groups.flatten().sorted())
    }

    // ── kernel: shape / edge cases ───────────────────────────────────────────

    @Test
    fun empty_returnsEmpty() {
        assertEquals(emptyList<List<Int>>(), LineAssembler.assembleLineIndices(emptyList()))
    }

    @Test
    fun singleBox_oneLineOfOne() {
        assertEquals(listOf(listOf(0)), LineAssembler.assembleLineIndices(listOf(box(0, 0, 100, 50))))
    }

    // ── kernel: headline real-device capture ─────────────────────────────────

    @Test
    fun realDevice14Boxes_clusterInto5Lines() {
        val boxes = listOf(
            box(542, 113, 726, 202), box(743, 105, 1203, 219), box(1224, 133, 1356, 205),   // row 1
            box(349, 261, 741, 373), box(749, 272, 1552, 368),                               // row 2
            box(907, 437, 969, 505),                                                          // row 3 (lone "И")
            box(275, 559, 668, 676), box(672, 588, 986, 671), box(1002, 592, 1126, 655), box(1152, 580, 1623, 659), // row 4
            box(593, 741, 849, 827), box(876, 745, 939, 806), box(965, 744, 1079, 803), box(1095, 737, 1303, 815),  // row 5
        )
        val groups = LineAssembler.assembleLineIndices(boxes)
        assertPartition(groups, n = 14, sizes = listOf(3, 2, 1, 4, 4))
        assertEquals(listOf(5), groups.first { it.size == 1 })   // the lone "И"
    }

    // ── kernel: core behaviors ───────────────────────────────────────────────

    @Test
    fun ascenderDescender_sameLine() {
        // Same row, one ascender-tall box + one short box (heights within height_ths)
        // → one line. The thing the old 0.30 height-ratio gate wrongly split.
        assertEquals(
            listOf(listOf(0, 1)),
            LineAssembler.assembleLineIndices(listOf(box(0, 0, 100, 90), box(110, 20, 200, 80))),
        )
    }

    @Test
    fun loneNarrowBox_ownLine() {
        assertPartition(
            LineAssembler.assembleLineIndices(listOf(box(0, 0, 200, 80), box(80, 120, 140, 200), box(0, 240, 200, 320))),
            n = 3, sizes = listOf(1, 1, 1),
        )
    }

    @Test
    fun twoColumnsSameHeight_doNotMerge() {
        // Same centerY, gap ≫ GAP_THS·height → the gap split keeps two columns apart.
        assertPartition(
            LineAssembler.assembleLineIndices(listOf(box(0, 0, 200, 80), box(2000, 0, 2200, 80))),
            n = 2, sizes = listOf(1, 1),
        )
    }

    @Test
    fun staircaseWithinTol_runningMeanDoesNotChain() {
        // Centers 100/135/170/205 (tol 40): consecutive steps < tol, endpoints > tol
        // → two lines, NOT one chain. (Also the gently-staggered/slanted case.)
        assertPartition(
            LineAssembler.assembleLineIndices(
                listOf(box(0, 60, 100, 140), box(0, 95, 100, 175), box(0, 130, 100, 210), box(0, 165, 100, 245)),
            ),
            n = 4, sizes = listOf(2, 2),
        )
    }

    @Test
    fun paragraphGap_separateLines() {
        assertPartition(
            LineAssembler.assembleLineIndices(listOf(box(0, 0, 100, 80), box(0, 500, 100, 580))),
            n = 2, sizes = listOf(1, 1),
        )
    }

    // ── kernel: mixed font sizes (EasyOCR height_ths / local-height guards) ───

    @Test
    fun differentHeightsSameRow_doNotMerge() {
        // A big box and a small box share a centerY, but height_ths refuses to merge
        // text of very different sizes (a heading vs a tiny caption on the same row).
        assertPartition(
            LineAssembler.assembleLineIndices(listOf(box(0, 0, 200, 200), box(210, 80, 260, 120))),
            n = 2, sizes = listOf(1, 1),
        )
    }

    @Test
    fun smallRowsStaySeparateDespiteLargeBoxes() {
        // The adversarial-review case: large boxes would dominate a FRAME-WIDE median
        // (≈120 → tol 60), wrongly merging two small rows 45px apart. Using the line's
        // LOCAL height (40 → tol 20) keeps them separate. Boxes: two big (h=200), two
        // small rows (h=40) at centers 520 and 565.
        val groups = LineAssembler.assembleLineIndices(
            listOf(
                box(0, 0, 400, 200),        // big, cy 100
                box(0, 500, 100, 540),      // small row 1, cy 520
                box(0, 545, 100, 585),      // small row 2, cy 565
                box(0, 1000, 400, 1200),    // big, cy 1100
            ),
        )
        assertPartition(groups, n = 4, sizes = listOf(1, 1, 1, 1))
        // The two small rows (indices 1 and 2) must land in DIFFERENT groups.
        val g1 = groups.first { it.contains(1) }
        assertEquals(false, g1.contains(2))
    }

    // ── assembleLines: post-recognition text stitch + orientation guard ──────

    @Test
    fun assembleLines_concatenatesWordsLeftToRight() {
        // Post-recognition: word-regions on one row (given out of left-order) are
        // stitched into one line with text joined left-to-right by single spaces.
        val out = LineAssembler.assembleLines(
            listOf(
                region("то", box(542, 113, 726, 202)),
                region("забовно,", box(743, 105, 1203, 219)),
                region("HO", box(1224, 133, 1356, 205)),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("то забовно, HO", out[0].text)
        assertEquals(TextOrientation.HORIZONTAL, out[0].orientation)
    }

    @Test
    fun assembleLines_foldsVerticalArtifactAndRetags() {
        // A tall narrow "I" tagged VERTICAL by aspect, among horizontal words in a
        // horizontal-dominant capture → folded onto the row, re-tagged HORIZONTAL.
        val out = LineAssembler.assembleLines(
            listOf(
                region("am", box(60, 0, 160, 80), TextOrientation.HORIZONTAL),
                region("I", box(0, -10, 30, 90), TextOrientation.VERTICAL),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("I am", out[0].text)
        assertEquals(TextOrientation.HORIZONTAL, out[0].orientation)
    }

    @Test
    fun assembleLines_verticalDominant_passesAllThroughUntouched() {
        // Vertical-dominant capture (genuine vertical text — e.g. rare vertical
        // Korean/CJK columns): assembleLines touches nothing, so VERTICAL orientation
        // survives for LayoutAnalyzer's vertical path and no regions are merged.
        val input = listOf(
            region("一", box(0, 0, 60, 300), TextOrientation.VERTICAL),
            region("二", box(80, 0, 140, 300), TextOrientation.VERTICAL),
            region("foo", box(0, 320, 200, 380), TextOrientation.HORIZONTAL),
        )
        assertEquals(input, LineAssembler.assembleLines(input))
    }

    @Test
    fun assembleLines_standaloneVerticalRegion_keepsOrientation() {
        // Horizontal-DOMINANT capture (two horizontal words) plus one genuine
        // vertical column (tall → fails the height band, stays a singleton). The
        // column must NOT be re-tagged HORIZONTAL — its orientation survives for
        // LayoutAnalyzer's vertical path. (The adversarial-review case.)
        val column = region("vert", box(500, 0, 560, 400), TextOrientation.VERTICAL)
        val out = LineAssembler.assembleLines(
            listOf(
                region("two", box(0, 0, 200, 80), TextOrientation.HORIZONTAL),
                region("words", box(210, 0, 410, 80), TextOrientation.HORIZONTAL),
                column,
            ),
        )
        val survived = out.first { it.orientation == TextOrientation.VERTICAL }
        assertEquals(column.box.bounds, survived.box.bounds)
        assertEquals(column.text, survived.text)
    }

    @Test
    fun assembleLines_carriesMemberCharsWithRebasedOffsets() {
        // Two words on one row, each recognized with WORD-LOCAL char boxes (PaddleOCR's
        // per-word output). After assembly the merged line is "ab cd" and every charOffset
        // must be shifted to index the joined text — c→3, d→4 — with the join space
        // (index 2) carrying no symbol. Word separation is PaddleOCR's main path for
        // alphabetic scripts, so this is where the char-box feature has to survive.
        fun ch(text: String, offset: Int, l: Int, r: Int) =
            CharBox(text, OcrBox.upright(box(l, 0, r, 20)), offset)
        fun word(text: String, l: Int, r: Int, chars: List<CharBox>): RecognizedRegion {
            val b = OcrBox.upright(box(l, 0, r, 20))
            return RecognizedRegion(
                text = text, box = b, orientation = TextOrientation.HORIZONTAL, confidence = 0.9f,
                lines = listOf(RecognizedLine(text, b, TextOrientation.HORIZONTAL, chars = chars)),
                origin = RegionOrigin.LINE,
            )
        }
        val out = LineAssembler.assembleLines(
            listOf(
                word("ab", 0, 20, listOf(ch("a", 0, 0, 10), ch("b", 1, 10, 20))),
                word("cd", 30, 50, listOf(ch("c", 0, 30, 40), ch("d", 1, 40, 50))),
            ),
        )
        assertEquals(1, out.size)
        val line = out[0].lines.single()
        assertEquals("ab cd", line.text)
        assertEquals(listOf("a", "b", "c", "d"), line.chars.map { it.text })
        assertEquals(listOf(0, 1, 3, 4), line.chars.map { it.charOffset })
        // Absolute char geometry is preserved, not re-derived from the union box.
        assertEquals(30, line.chars[2].box.bounds.left)
        assertEquals(50, line.chars[3].box.bounds.right)
    }
}
