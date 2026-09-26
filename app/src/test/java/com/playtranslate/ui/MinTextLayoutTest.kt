package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Pass 4 of the overlay layout ([MinTextLayout], wired through
 * [OverlayLayout.resolveScreenRects]) under monospace metrics: a char is half
 * the text size wide, a line is the text size tall, words wrap greedily on
 * spaces, 3 px padding per side; a stack cell is the text size square. The
 * target is 20 px over a 6 px floor.
 */
@RunWith(RobolectricTestRunner::class)
class MinTextLayoutTest {

    private class Mono(private val cjkStack: Boolean = false) : MinTextMeasurer {
        /** Measurements asked for: the cost pass 4 must ration. */
        var calls = 0

        private fun words(text: String, px: Int) = text.split(' ').map { ceil(it.length * px / 2f).toInt() }

        override fun footprint(box: TextBox, mode: RenderMode, px: Int, wrapExtent: Int): FitSize? {
            calls++
            val text = box.translatedText
            if (mode == RenderMode.STACK_UPRIGHT) {
                val rows = (wrapExtent - 6) / px
                if (rows < 1) return null
                val cols = (text.length + rows - 1) / rows
                if (cols > 1 && !cjkStack) return null
                return FitSize(cols * px + 6, wrapExtent)
            }
            val words = words(text, px)
            val content = wrapExtent - 6
            if (content < words.max()) return null
            val space = ceil(px / 2f).toInt()
            var lines = 1
            var used = 0
            for (w in words) {
                val need = if (used == 0) w else used + space + w
                if (need <= content || used == 0) used = need else { lines++; used = w }
            }
            val cross = lines * px + 6
            return if (mode == RenderMode.ROTATE) FitSize(cross, wrapExtent) else FitSize(wrapExtent, cross)
        }

        /** Whether the text fits a [width] x [height] frame at [px], as pass 4
         *  judges it: laid along the frame's wrap axis. */
        fun fits(box: TextBox, mode: RenderMode, px: Int, width: Int, height: Int): Boolean {
            val wrapIsWidth = mode != RenderMode.ROTATE && mode != RenderMode.STACK_UPRIGHT
            val s = footprint(box, mode, px, if (wrapIsWidth) width else height) ?: return false
            return if (wrapIsWidth) s.height <= height else s.width <= width
        }
    }

    private fun growth(measurer: MinTextMeasurer = Mono(), limits: GrowthLimits = GrowthLimits.NONE) =
        MinTextGrowth(targetPx = 20, floorPx = 6, measurer = measurer, limits = limits)

    private fun box(
        text: String,
        alignment: TextAlignment = TextAlignment.LEFT,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        angleDeg: Float = 0f,
        isFurigana: Boolean = false,
    ) = TextBox(
        translatedText = text, bounds = Rect(0, 0, 1, 1),
        alignment = alignment, orientation = orientation,
        angleDeg = angleDeg, isFurigana = isFurigana,
    )

    /** Run [MinTextLayout.grow] on explicit rects; [avoid] already padded. */
    private fun grow(
        boxes: List<TextBox>,
        rects: List<RectF>,
        modes: List<RenderMode> = boxes.map { RenderMode.LEGACY_HORIZONTAL },
        bound: RectF = RectF(0f, 0f, 1000f, 1000f),
        avoid: List<RectF> = emptyList(),
        measurer: MinTextMeasurer = Mono(),
    ): List<MinTextLayout.Outcome> = MinTextLayout.grow(boxes, rects, modes, bound, avoid, growth(measurer))

    private fun assertRect(expected: RectF, actual: RectF) {
        val d = 1e-3f
        assertTrue(
            "expected $expected, got $actual",
            kotlin.math.abs(expected.left - actual.left) < d && kotlin.math.abs(expected.top - actual.top) < d &&
                kotlin.math.abs(expected.right - actual.right) < d && kotlin.math.abs(expected.bottom - actual.bottom) < d,
        )
    }

    // ── One box ──────────────────────────────────────────────────────────

    @Test
    fun alreadyFits_isUntouched_butCertified() {
        val r = RectF(100f, 100f, 300f, 200f)
        val out = grow(listOf(box("Hi")), listOf(r))
        assertRect(RectF(100f, 100f, 300f, 200f), r)
        assertNull(out[0].grownFrom)
        assertEquals(20, out[0].floorPx)
    }

    @Test
    fun leftToRightText_keepsItsStartEdge_andCentresVertically() {
        // "Hello" at 20 px: 50 content + 6 = 56 x 26, half a px over each.
        val r = RectF(100f, 100f, 140f, 120f)
        val out = grow(listOf(box("Hello")), listOf(r))
        assertRect(RectF(100f, 96.75f, 156.5f, 123.25f), r)
        assertRect(RectF(100f, 100f, 140f, 120f), out[0].grownFrom!!)
        assertEquals(20, out[0].floorPx)
    }

    @Test
    fun rightToLeftText_keepsItsRightEdge() {
        val r = RectF(100f, 100f, 140f, 120f)
        grow(listOf(box("مرحبا")), listOf(r))
        assertEquals(140f, r.right, 1e-3f)
        assertEquals(83.5f, r.left, 1e-3f)
    }

    @Test
    fun centredText_growsEvenly() {
        val r = RectF(100f, 100f, 140f, 120f)
        grow(listOf(box("Hello", alignment = TextAlignment.CENTER)), listOf(r))
        assertEquals(91.75f, r.left, 1e-3f)
        assertEquals(148.25f, r.right, 1e-3f)
    }

    @Test
    fun dialogueBlock_growsTallerAtItsLineLength() {
        // Six 50 px words: 3 per line fit the 194 px content width, so the
        // block keeps its width and takes a second line.
        val r = RectF(100f, 100f, 300f, 130f)
        grow(listOf(box("aaaaa bbbbb ccccc ddddd eeeee fffff")), listOf(r))
        assertEquals(100f, r.left, 1e-3f)
        assertEquals(300f, r.right, 1e-3f)
        assertEquals(46.5f, r.height(), 1e-3f)
        assertEquals(115f, r.centerY(), 1e-3f)
    }

    @Test
    fun boundEdge_pushesGrowthInward() {
        val r = RectF(100f, 100f, 140f, 120f)
        grow(listOf(box("Hello")), listOf(r), bound = RectF(0f, 0f, 150f, 1000f))
        assertEquals(150f, r.right, 1e-3f)
        assertEquals(93.5f, r.left, 1e-3f)
    }

    @Test
    fun avoidRect_isNeverClaimed() {
        val r = RectF(100f, 100f, 140f, 120f)
        grow(listOf(box("Hello")), listOf(r), avoid = listOf(RectF(140f, 50f, 400f, 150f)))
        assertEquals(140f, r.right, 1e-3f)
        assertEquals(83.5f, r.left, 1e-3f)
    }

    @Test
    fun avoidRectOverTheBox_isCutAroundIt_andWallsItIn() {
        // Cut to its parts outside the box, an avoid rect around the box
        // blocks every side, so the box keeps its rect at the largest size
        // that fits there (13: "Hello" is 33 + 6 px wide, the rect 40).
        val r = RectF(100f, 100f, 140f, 120f)
        val out = grow(listOf(box("Hello")), listOf(r), avoid = listOf(RectF(98f, 98f, 142f, 122f)))
        assertRect(RectF(100f, 100f, 140f, 120f), r)
        assertNull(out[0].grownFrom)
        assertEquals(13, out[0].floorPx)
    }

    @Test
    fun noRoomEvenAtTheSmallestSize_keepsTodaysLayout() {
        val r = RectF(100f, 100f, 110f, 105f)
        val walls = listOf(
            RectF(0f, 0f, 1000f, 100f), RectF(0f, 105f, 1000f, 1000f),
            RectF(0f, 100f, 100f, 105f), RectF(110f, 100f, 1000f, 105f),
        )
        val out = grow(listOf(box("Hello")), listOf(r), avoid = walls)
        assertRect(RectF(100f, 100f, 110f, 105f), r)
        assertNull(out[0].grownFrom)
        assertNull(out[0].floorPx)
    }

    // ── Cost ─────────────────────────────────────────────────────────────

    @Test
    fun aBoxThatAlreadyFits_costsOneMeasurement() {
        val mono = Mono()
        grow(
            listOf(box("Hi"), box("Hello there")),
            listOf(RectF(100f, 100f, 300f, 200f), RectF(100f, 300f, 400f, 400f)),
            measurer = mono,
        )
        assertEquals(2, mono.calls)
    }

    @Test
    fun longTextInATallNarrowRoom_takesTheTightestWrapThatFits() {
        // 40 two-letter words at 20 px (word 20, space 10). A 100 x 600
        // column holds two words a line: 56 px wide, 20 lines, 406 px tall.
        val mono = Mono()
        val text = List(40) { "ab" }.joinToString(" ")
        val r = RectF(80f, 300f, 120f, 320f)
        val walls = listOf(
            RectF(0f, 0f, 50f, 1000f), RectF(150f, 0f, 1000f, 1000f),
            RectF(50f, 0f, 150f, 20f), RectF(50f, 620f, 150f, 1000f),
        )
        val out = grow(listOf(box(text)), listOf(r), avoid = walls, measurer = mono)
        assertEquals("the target size, not a fallback", 20, out[0].floorPx)
        assertTrue("inside the column: $r", r.left >= 50f && r.right <= 150f && r.top >= 20f && r.bottom <= 620f)
        assertTrue(mono.fits(box(text), RenderMode.LEGACY_HORIZONTAL, 20, r.width().toInt(), r.height().toInt()))
        assertEquals("the tightest wrap that fits, not the whole column", 56.5f, r.width(), 1e-3f)
        assertTrue("measurements ${mono.calls}", mono.calls <= 2 + 8)
    }

    @Test
    fun aBoxThatCantReachTheTarget_takesTheLargestSize_withBoundedMeasurements() {
        // A 20 px row between walls: the target (a 26 px line) never fits;
        // the fallback's binary search over 7..19 keeps 14 (14 + 6 = 20).
        val mono = Mono()
        val r = RectF(100f, 120f, 140f, 140f)
        val walls = listOf(RectF(0f, 0f, 1000f, 120f), RectF(0f, 140f, 1000f, 1000f))
        val out = grow(listOf(box("Equipment")), listOf(r), avoid = walls, measurer = mono)
        assertEquals(14, out[0].floorPx)
        // One fit check, the target's search, four sizes probed in one room,
        // the kept size's search.
        assertTrue("measurements ${mono.calls}", mono.calls <= 1 + 12 + 4 + 12)
    }

    @Test
    fun paddingDominatedFallback_findsTheLargestSize() {
        // "ab" in a 15 px band: 8 fits (14 px; with the half px margin
        // 14.5 <= 15), 9 (15 px) doesn't.
        val r = RectF(100f, 100f, 110f, 110f)
        val walls = listOf(RectF(0f, 0f, 1000f, 97f), RectF(0f, 112f, 1000f, 1000f))
        val out = grow(listOf(box("ab")), listOf(r), avoid = walls)
        assertEquals(8, out[0].floorPx)
        assertRect(RectF(100f, 97.5f, 114.5f, 112f), r)
    }

    @Test
    fun aRoomExactlyALineTall_isOneTheLayoutCanPlaceNothingIn() {
        // A 24 px band: an 18 px line is 24 px, but a grown rect needs half a
        // px more (need()), so 18 can't be placed; rooms are sized the same
        // way, so the fallback keeps 17 instead of calling 18 a fit and
        // placing nothing.
        val r = RectF(100f, 100f, 140f, 120f)
        val walls = listOf(RectF(0f, 0f, 1000f, 98f), RectF(0f, 122f, 1000f, 1000f))
        val out = grow(listOf(box("Hello")), listOf(r), avoid = walls)
        assertEquals(17, out[0].floorPx)
        assertEquals(23.5f, r.height(), 1e-3f)
    }

    // ── Neighbours ───────────────────────────────────────────────────────

    @Test
    fun neighbourOnTheStartSide_pushesGrowthToTheOtherSide() {
        val a = RectF(100f, 100f, 140f, 120f)
        val b = RectF(140f, 90f, 300f, 130f)
        grow(listOf(box("Hello"), box("x")), listOf(a, b))
        assertEquals(140f, a.right, 1e-3f)
        assertEquals(83.5f, a.left, 1e-3f)
        assertRect(RectF(140f, 90f, 300f, 130f), b)
    }

    @Test
    fun stackedMenuRows_widenOnly_andShrinkToTheRowPitch() {
        // Row 0 and row 2 have room above / below; row 1 has none, so it
        // widens at the largest size whose line fits its 20 px pitch (14:
        // 14 + 6 = 20).
        val rows = listOf(
            RectF(100f, 100f, 140f, 120f),
            RectF(100f, 120f, 140f, 140f),
            RectF(100f, 140f, 140f, 160f),
        )
        val out = grow(List(3) { box("Equipment") }, rows)
        assertEquals(20, out[0].floorPx)
        assertEquals(14, out[1].floorPx)
        assertEquals(20, out[2].floorPx)
        assertEquals("row 1 keeps its pitch", 120f, rows[1].top, 1e-3f)
        assertEquals("row 1 keeps its pitch", 140f, rows[1].bottom, 1e-3f)
        assertEquals("row 1 widens from its start", 100f, rows[1].left, 1e-3f)
        assertEquals(69.5f, rows[1].width(), 1e-3f)
        assertEquals("row 0 grows up", 120f, rows[0].bottom, 1e-3f)
        assertEquals("row 2 grows down", 140f, rows[2].top, 1e-3f)
    }

    @Test
    fun inputOrderDecidesContention() {
        // A (LTR) wants the gap on its right, B (RTL) the same gap on its
        // left; A comes first and takes its share, B pushes the rest out.
        val a = RectF(100f, 100f, 140f, 120f)
        val b = RectF(170f, 100f, 210f, 120f)
        grow(listOf(box("Hello"), box("مرحبا")), listOf(a, b))
        assertEquals(156.5f, a.right, 1e-3f)
        assertEquals(156.5f, b.left, 1e-3f)
        assertEquals(213f, b.right, 1e-3f)
    }

    @Test
    fun preexistingOverlap_isNeverWidened() {
        val a = RectF(100f, 100f, 140f, 120f)
        val b = RectF(130f, 110f, 200f, 150f)
        grow(listOf(box("Hello"), box("x")), listOf(a, b))
        val overlap = RectF()
        assertTrue(overlap.setIntersect(a, b))
        assertRect(RectF(130f, 110f, 140f, 120f), overlap)
        assertEquals(56.5f, a.width(), 1e-3f)
    }

    // ── Render modes ─────────────────────────────────────────────────────

    @Test
    fun rotatedColumn_widensAcrossItsLines_keepingItsLength() {
        val r = RectF(100f, 100f, 120f, 300f)
        grow(
            listOf(box("Hello world", orientation = TextOrientation.VERTICAL)), listOf(r),
            modes = listOf(RenderMode.ROTATE),
        )
        assertEquals(200f, r.height(), 1e-3f)
        assertEquals(26.5f, r.width(), 1e-3f)
        assertEquals(110f, r.centerX(), 1e-3f)
    }

    @Test
    fun latinStack_growsTallerFromItsTop() {
        // Five cells of 20 px in one column: 100 + 6 tall.
        val r = RectF(100f, 100f, 130f, 160f)
        grow(
            listOf(box("ABCDE", orientation = TextOrientation.VERTICAL)), listOf(r),
            modes = listOf(RenderMode.STACK_UPRIGHT),
        )
        assertEquals(100f, r.top, 1e-3f)
        assertEquals(206.5f, r.bottom, 1e-3f)
        assertEquals(30f, r.width(), 1e-3f)
    }

    @Test
    fun cjkStack_addsColumnsLeftward_keepingItsHeight() {
        // 60 px holds two 20 px rows: three columns, 66 px wide.
        val r = RectF(100f, 100f, 130f, 160f)
        grow(
            listOf(box("ABCDE", orientation = TextOrientation.VERTICAL)), listOf(r),
            modes = listOf(RenderMode.STACK_UPRIGHT),
            measurer = Mono(cjkStack = true),
        )
        assertEquals(130f, r.right, 1e-3f)
        assertEquals(66.5f, r.width(), 1e-3f)
        assertEquals(60f, r.height(), 1e-3f)
    }

    @Test
    fun slantedFuriganaAndPlaceholders_areLeftAlone_butStillBlock() {
        val slanted = RectF(141f, 90f, 300f, 130f)
        val boxes = listOf(
            box("Hello"),
            box("Hello", angleDeg = 10f),
            box("よみ", isFurigana = true),
            box(""),
        )
        val rects = listOf(
            RectF(100f, 100f, 140f, 120f),
            slanted,
            RectF(400f, 100f, 410f, 105f),
            RectF(500f, 100f, 505f, 105f),
        )
        val modes = listOf(
            RenderMode.LEGACY_HORIZONTAL, RenderMode.SOURCE_ANGLE,
            RenderMode.LEGACY_HORIZONTAL, RenderMode.LEGACY_HORIZONTAL,
        )
        val out = grow(boxes, rects, modes)
        assertRect(RectF(141f, 90f, 300f, 130f), rects[1])
        assertRect(RectF(400f, 100f, 410f, 105f), rects[2])
        assertRect(RectF(500f, 100f, 505f, 105f), rects[3])
        for (k in 1..3) {
            assertNull(out[k].grownFrom)
            assertNull(out[k].floorPx)
        }
        assertTrue("the slanted chip bounds the first box's growth", rects[0].right <= 141f + 1e-3f)
    }

    // ── Free rects ───────────────────────────────────────────────────────

    @Test
    fun emptyRects_areTheMaximalOnes_andEachContainsTheBox() {
        val r = RectF(100f, 100f, 140f, 120f)
        val bound = RectF(0f, 0f, 1000f, 1000f)
        // A band above, a block to the right over the box's rows, a block
        // below over the box's own columns: one room, walled on three sides.
        val walled = listOf(
            RectF(0f, 0f, 1000f, 50f),
            RectF(200f, 60f, 300f, 200f),
            RectF(50f, 150f, 120f, 170f),
        )
        assertEquals(listOf(RectF(0f, 50f, 200f, 150f)), MinTextLayout.emptyRectsContaining(r, walled, bound))
        // A block below and to the right: the band above it, and the column
        // beside it, each maximal, neither inside the other.
        val corner = listOf(RectF(200f, 130f, 300f, 200f))
        assertEquals(
            setOf(RectF(0f, 0f, 1000f, 130f), RectF(0f, 0f, 200f, 1000f)),
            MinTextLayout.emptyRectsContaining(r, corner, bound).toSet(),
        )
        // A box already past the bound keeps what it has.
        val past = RectF(-20f, 100f, 40f, 120f)
        assertEquals(listOf(RectF(-20f, 0f, 1000f, 1000f)), MinTextLayout.emptyRectsContaining(past, emptyList(), bound))
    }

    @Test
    fun emptyRects_manyUnalignedEdges_collapseToOnePerSidePair() {
        // A box in the gap between two columns: 30 rows above it on the left,
        // 30 below it on the right, none over its columns. 31 x 31 bands, but
        // only four side pairs (each side is cut once the first row on that
        // side enters the band), so four maximal rooms.
        val r = RectF(450f, 1500f, 550f, 1520f)
        val walls = ArrayList<RectF>()
        for (k in 1..30) {
            walls += RectF(0f, 1500f - 40f * k - 20f, 400f, 1500f - 40f * k)
            walls += RectF(600f, 1520f + 40f * k, 1000f, 1520f + 40f * k + 20f)
        }
        val rooms = MinTextLayout.emptyRectsContaining(r, walls, RectF(0f, 0f, 1000f, 3000f))
        assertEquals(
            setOf(
                RectF(0f, 1460f, 1000f, 1560f),
                RectF(0f, 1460f, 600f, 3000f),
                RectF(400f, 0f, 1000f, 1560f),
                RectF(400f, 0f, 600f, 3000f),
            ),
            rooms.toSet(),
        )
        assertEquals(4, rooms.size)
    }

    @Test
    fun manyShortWideRooms_neverHideATallNarrowOne() {
        // Eight wall pairs sit within 4 px above the box, each pair closer in
        // than the one below it, and a floor sits right under it: eight bands
        // 20.5 to 24 px tall (none holds a 26 px line) of decreasing width,
        // plus the corridor between the innermost walls, 80 px wide and 520
        // tall. Twelve two-letter words at 20 px fit only the corridor, and
        // its tightest wrap there is one a line: 26 x 246, grown upward from
        // the floor.
        val r = RectF(500f, 500f, 520f, 520f)
        val walls = ArrayList<RectF>()
        for (k in 1..8) {
            val bottom = 500f - 0.5f * k
            val d = 30f + 10f * (9 - k)
            walls += RectF(510f - d - 10f, bottom - 10f, 510f - d, bottom)
            walls += RectF(510f + d, bottom - 10f, 510f + d + 10f, bottom)
        }
        walls += RectF(0f, 520f, 1000f, 1000f)
        val rooms = MinTextLayout.emptyRectsContaining(r, walls, RectF(0f, 0f, 1000f, 1000f))
        assertEquals(9, rooms.size)
        assertTrue(rooms.any { it == RectF(470f, 0f, 550f, 520f) })
        val out = grow(listOf(box(List(12) { "ab" }.joinToString(" "))), listOf(r), avoid = walls)
        assertEquals(20, out[0].floorPx)
        assertTrue("in the corridor: $r", r.left >= 470f && r.right <= 550f && r.bottom <= 520f + 1e-3f)
        assertEquals(26.5f, r.width(), 1e-3f)
        assertEquals(246.5f, r.height(), 1e-3f)
    }

    // ── Through the resolver ─────────────────────────────────────────────

    private fun resolve(
        boxes: List<TextBox>,
        minText: MinTextGrowth?,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        density: Float = 0f,
    ) = OverlayLayout.resolveScreenRects(
        boxes, cropLeft, cropTop, 1000, 1000, 1000, 1000, density,
        targetIsVerticalScript = false, targetStackable = false, growEnabled = true,
        minText = minText,
    )

    @Test
    fun resolver_withoutMinText_reportsNoGrowth() {
        val boxes = listOf(TextBox("Hello", Rect(100, 100, 140, 120)))
        val resolved = resolve(boxes, null)
        assertNull(resolved[0].grownFrom)
        assertNull(resolved[0].floorPx)
        assertRect(RectF(100f, 100f, 140f, 120f), resolved[0].rect)
    }

    @Test
    fun resolver_boundsAndAvoid_areMappedFromCropCoords() {
        // Crop at (50, 50): crop-space bounds (0, 0, 100, 100) are screen
        // (50, 50, 150, 150); an avoid rect right of the box, in crop space.
        val boxes = listOf(TextBox("Helloooo", Rect(40, 10, 80, 30)))
        val limits = GrowthLimits(
            bounds = Rect(0, 0, 100, 100),
            avoid = listOf(Rect(90, 0, 100, 100)),
        )
        val resolved = resolve(boxes, growth(limits = limits), cropLeft = 50, cropTop = 50)
        val r = resolved[0].rect
        assertTrue("stays inside the region: $r", r.left >= 50f - 1e-3f && r.right <= 150f + 1e-3f)
        assertTrue("stays clear of the avoid rect: $r", r.right <= 140f + 1e-3f)
        assertEquals(86.5f, r.width(), 1e-3f)
    }

    @Test
    fun resolver_padsAvoidRectsAtTheViewsDensity() {
        // "Equipment" widens right toward the text at x 190 until that
        // text's padding: 6dp, 12 px at density 2 and 18 at 3, the same the
        // box itself is padded by.
        for (density in listOf(2f, 3f)) {
            val boxes = listOf(TextBox("Equipment", Rect(100, 100, 140, 120)))
            val limits = GrowthLimits(avoid = listOf(Rect(190, 100, 230, 120)))
            val r = resolve(boxes, growth(limits = limits), density = density)[0].rect
            assertEquals("density $density", 190f - 6f * density, r.right, 1e-3f)
        }
    }

    @Test
    fun resolver_neverChangesModesOrChips() {
        val boxes = listOf(
            TextBox("Hello there", Rect(100, 100, 140, 120)),
            TextBox("Vertical text", Rect(300, 100, 320, 300), orientation = TextOrientation.VERTICAL, minWidthPx = 500),
            TextBox("Slanted", Rect(500, 100, 600, 150), angleDeg = 12f, orientedWidth = 100f, orientedHeight = 30f),
        )
        val before = resolve(boxes, null, density = 1f)
        val after = resolve(boxes, growth(), density = 1f)
        assertEquals(before.map { it.mode }, after.map { it.mode })
        assertEquals(before.map { it.chip }, after.map { it.chip })
    }

    // ── Invariants over random layouts ───────────────────────────────────

    @Test
    fun randomLayouts_holdTheGrowthInvariants() {
        val rnd = Random(20260924)
        val words = listOf("a", "ok", "menu", "Items", "Equipment", "save", "the quick", "brown fox jumps")
        repeat(400) { case ->
            val n = 1 + rnd.nextInt(7)
            val boxes = List(n) {
                val l = rnd.nextInt(0, 900)
                val t = rnd.nextInt(0, 900)
                val w = rnd.nextInt(8, 120)
                val h = rnd.nextInt(6, 60)
                TextBox(
                    translatedText = List(1 + rnd.nextInt(3)) { words.random(rnd) }.joinToString(" "),
                    bounds = Rect(l, t, l + w, t + h),
                    alignment = if (rnd.nextBoolean()) TextAlignment.LEFT else TextAlignment.CENTER,
                )
            }
            val avoid = List(rnd.nextInt(6)) {
                val l = rnd.nextInt(0, 950)
                val t = rnd.nextInt(0, 950)
                Rect(l, t, l + rnd.nextInt(5, 80), t + rnd.nextInt(5, 80))
            }
            val bounds = if (rnd.nextBoolean()) Rect(50, 50, 950, 950) else null
            val mono = Mono()
            val limits = GrowthLimits(bounds, avoid)
            val base = resolve(boxes, null, density = 1f)
            val grown = resolve(boxes, growth(mono, limits), density = 1f)
            assertEquals(base.size, grown.size)
            for (i in boxes.indices) {
                val b = base[i].rect
                val g = grown[i].rect
                assertTrue("case $case box $i: $g must contain $b", g.contains(b))
                val bound = RectF(bounds?.let { RectF(it) } ?: RectF(0f, 0f, 1000f, 1000f))
                bound.union(b)
                bound.inset(-1e-3f, -1e-3f)
                assertTrue("case $case box $i: $g outside $bound", bound.contains(g))
                for (a in avoid) {
                    // Padded by the box padding (6dp at density 1).
                    val af = RectF(a).apply { inset(-6f, -6f) }
                    assertEquals(
                        "case $case box $i: growth claimed avoid rect $a",
                        overlapArea(b, af), overlapArea(g, af), 1e-2f,
                    )
                }
                grown[i].floorPx?.let { px ->
                    assertTrue(
                        "case $case box $i: certified $px px doesn't fit $g",
                        mono.fits(boxes[i], grown[i].mode, px, g.width().toInt(), g.height().toInt()),
                    )
                }
                for (j in i + 1 until boxes.size) {
                    assertEquals(
                        "case $case: boxes $i and $j overlap more than before",
                        overlapArea(base[i].rect, base[j].rect), overlapArea(g, grown[j].rect), 1e-2f,
                    )
                }
            }
        }
    }

    @Test
    fun randomFallbacks_takeTheLargestSizeSomeRoomHolds() {
        // The oracle: a size fits when the box's own rect holds it, or some
        // maximal free rect does with the text laid across its whole width
        // (as short as it gets there), under the layout's whole-px rounding.
        // Walls hug the box (never cover it) so most cases can't reach the
        // target.
        val rnd = Random(20260925)
        val words = listOf("a", "ok", "menu", "Items", "Equipment", "save", "the quick", "brown fox jumps")
        fun cap(extent: Float, have: Float) = maxOf(have.toInt(), kotlin.math.floor(extent - 0.5f + 1e-3f).toInt())
        val mode = RenderMode.LEGACY_HORIZONTAL
        var fellBack = 0
        var checked = 0
        repeat(400) { case ->
            val l = rnd.nextInt(300, 600).toFloat()
            val t = rnd.nextInt(300, 600).toFloat()
            val r0 = RectF(l, t, l + rnd.nextInt(10, 90), t + rnd.nextInt(8, 40))
            fun gap() = rnd.nextInt(0, 25).toFloat()
            fun reach() = rnd.nextInt(0, 200).toFloat()
            val walls = ArrayList<RectF>()
            if (rnd.nextFloat() < 0.8f) walls += RectF(r0.left - reach(), 0f, r0.right + reach(), r0.top - gap())
            if (rnd.nextFloat() < 0.8f) walls += RectF(r0.left - reach(), r0.bottom + gap(), r0.right + reach(), 1000f)
            if (rnd.nextFloat() < 0.5f) walls += RectF(0f, r0.top - reach(), r0.left - gap(), r0.bottom + reach())
            if (rnd.nextFloat() < 0.5f) walls += RectF(r0.right + gap(), r0.top - reach(), 1000f, r0.bottom + reach())
            assertTrue(walls.none { RectF.intersects(it, r0) })
            val rooms = MinTextLayout.emptyRectsContaining(r0, walls, RectF(0f, 0f, 1000f, 1000f))
            val b = box(List(1 + rnd.nextInt(3)) { words.random(rnd) }.joinToString(" "))
            val mono = Mono()
            fun holds(px: Int) = mono.fits(b, mode, px, r0.width().toInt(), r0.height().toInt()) ||
                rooms.any { m ->
                    val shape = mono.footprint(b, mode, px, cap(m.width(), r0.width()))
                    shape != null && shape.height <= cap(m.height(), r0.height())
                }
            val expected = (7..20).filter { holds(it) }.maxOrNull()
            val r = RectF(r0)
            val out = grow(listOf(b), listOf(r), avoid = walls, measurer = Mono())
            assertEquals("case $case: \"${b.translatedText}\" in $r0", expected, out[0].floorPx)
            out[0].floorPx?.let { px ->
                assertTrue("case $case: $px px doesn't fit $r", mono.fits(b, mode, px, r.width().toInt(), r.height().toInt()))
                assertTrue("case $case: $r over a wall", walls.none { RectF.intersects(it, r) })
            }
            checked++
            if (expected != null && expected < 20) fellBack++
        }
        assertTrue("fallbacks exercised: $fellBack of $checked", fellBack >= 20)
    }

    // ── Incremental sweep vs the per-band scan ───────────────────────────

    /** The per-band full scan [MinTextLayout.emptyRectsContaining] replaced:
     *  every band rescans every obstacle for its sides and its block. */
    private fun referenceEmptyRects(r: RectF, obstacles: List<RectF>, bound: RectF): List<RectF> {
        val b = RectF(
            minOf(bound.left, r.left), minOf(bound.top, r.top),
            maxOf(bound.right, r.right), maxOf(bound.bottom, r.bottom),
        )
        val tops = (obstacles.map { it.bottom }.filter { it <= r.top && it > b.top } + b.top)
            .distinct().sortedDescending()
        val bottoms = (obstacles.map { it.top }.filter { it >= r.bottom && it < b.bottom } + b.bottom)
            .distinct().sorted()
        fun blocked(top: Float, bottom: Float): Boolean =
            obstacles.any { o ->
                o.top < bottom && o.bottom > top && o.left < r.right && o.right > r.left
            }
        val bySides = LinkedHashMap<Pair<Float, Float>, RectF>()
        for (t in listOf(r.top) + tops) {
            if (t > r.top) continue
            if (blocked(t, r.bottom)) break
            for (bt in listOf(r.bottom) + bottoms) {
                if (bt < r.bottom) continue
                if (blocked(t, bt)) break
                var left = b.left
                var right = b.right
                for (o in obstacles) {
                    if (o.top >= bt || o.bottom <= t) continue
                    if (o.right <= r.left) left = maxOf(left, o.right)
                    else if (o.left >= r.right) right = minOf(right, o.left)
                }
                val room = bySides.getOrPut(left to right) { RectF(left, t, right, bt) }
                room.top = minOf(room.top, t)
                room.bottom = maxOf(room.bottom, bt)
            }
        }
        return bySides.values.toList()
    }

    @Test
    fun emptyRects_matchTheReferenceSweep_onRandomObstacles() {
        val rnd = Random(20260926)
        val bound = RectF(0f, 0f, 1000f, 1000f)
        var rooms = 0
        repeat(500) { case ->
            // Half the cases on a coarse grid, so edges tie often.
            val grid = rnd.nextBoolean()
            fun coord(from: Int, until: Int): Float =
                if (grid) (rnd.nextInt(from, until) / 50 * 50).toFloat() else rnd.nextInt(from, until).toFloat()
            val outside = rnd.nextInt(10) == 0
            val l = if (outside) coord(-100, 1000) else coord(0, 900)
            val t = if (outside) coord(-100, 1000) else coord(0, 900)
            val r = RectF(l, t, l + maxOf(10f, coord(10, 150)), t + maxOf(10f, coord(10, 100)))
            val obstacles = ArrayList<RectF>()
            repeat(rnd.nextInt(26)) {
                val ol = coord(-50, 1000)
                val ot = coord(-50, 1000)
                val o = RectF(ol, ot, ol + maxOf(5f, coord(5, 300)), ot + maxOf(5f, coord(5, 300)))
                if (!RectF.intersects(o, r)) obstacles += o
            }
            val got = MinTextLayout.emptyRectsContaining(r, obstacles, bound)
            val want = referenceEmptyRects(r, obstacles, bound)
            assertEquals("case $case: $r among $obstacles", want.toSet(), got.toSet())
            assertEquals("case $case: first-seen order", want, got)
            for (m in got) {
                assertTrue("case $case: $m must contain $r", m.contains(r))
                assertTrue("case $case: $m over an obstacle", obstacles.none { RectF.intersects(it, m) })
            }
            rooms += got.size
        }
        assertTrue("rooms exercised: $rooms", rooms > 500)
    }

    // ── Cost on dense pages ──────────────────────────────────────────────

    @Test
    fun denseLayouts_keepMeasurementsLinearInTheBoxes() {
        val texts = listOf("Attack", "Items", "Equipment", "Save game", "the quick brown", "Run away now")
        val grid = List(60) { k ->
            val c = k % 5
            val row = k / 5
            val l = 100 + c * 360
            val t = 60 + row * 80
            TextBox(texts[k % texts.size], Rect(l, t, l + 120, t + 24))
        }
        val columns = List(20) { row ->
            val t = 20 + row * 50
            listOf(
                TextBox(texts[row % texts.size], Rect(100, t, 700, t + 24)),
                TextBox(texts[(row + 3) % texts.size], Rect(1200, t, 1800, t + 24)),
            )
        }.flatten() + TextBox("Run away now", Rect(920, 500, 1000, 520))
        val rnd = Random(20260926)
        val scattered = List(60) {
            val l = rnd.nextInt(0, 1801)
            val t = rnd.nextInt(0, 1041)
            TextBox(texts.random(rnd), Rect(l, t, l + rnd.nextInt(40, 161), t + rnd.nextInt(16, 41)))
        }
        for ((name, boxes) in listOf("grid" to grid, "columns" to columns, "random" to scattered)) {
            val mono = Mono()
            val resolved = OverlayLayout.resolveScreenRects(
                boxes, 0, 0, 1920, 1080, 1920, 1080, 2f,
                targetIsVerticalScript = false, targetStackable = false, growEnabled = true,
                minText = MinTextGrowth(40, 12, mono, GrowthLimits.NONE),
            )
            val grew = resolved.count { it.grownFrom != null }
            val certified = resolved.count { it.floorPx != null }
            println("COST $name: calls=${mono.calls} boxes=${boxes.size} grew=$grew certified=$certified")
            assertTrue("$name: ${mono.calls} measurements for ${boxes.size} boxes", mono.calls <= 30 * boxes.size)
        }
    }

    private fun overlapArea(a: RectF, b: RectF): Float {
        val r = RectF()
        return if (r.setIntersect(a, b)) r.width() * r.height() else 0f
    }
}
