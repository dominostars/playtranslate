package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF
import com.playtranslate.OcrManager
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tap-to-lookup boxes ([TapLookupGeometry]): one per token, along the
 * line from the drag lens's own placement ([DragLookupController.placeTokensOnLine])
 * and across it from the line; which box a tap lands on; which top corner
 * the X takes. Runs under Robolectric for [Rect] and [RectF].
 */
@RunWith(RobolectricTestRunner::class)
class TapLookupGeometryTest {

    private val pad = 3f
    private val gap = 2f

    /** Uniform symbols for the non-space characters of [text], [w] px each
     *  from [left], on a horizontal line [top]..[bottom]. */
    private fun horizontal(text: String, left: Int = 100, w: Int = 10, top: Int = 50, bottom: Int = 70) =
        OcrManager.OcrLine(
            text = text,
            bounds = Rect(left, top, left + text.length * w, bottom),
            symbols = text.mapIndexedNotNull { i, ch ->
                if (ch == ' ') null
                else OcrManager.SymbolBox(ch.toString(), Rect(left + i * w, top, left + (i + 1) * w, bottom), i)
            },
        )

    @Test fun `an upright line's boxes span each word across the line, grown by the pad`() {
        val line = horizontal("hello world")
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("hello", "world"), pad, gap)

        assertEquals(2, boxes.size)
        // "hello" is chars 0..4 (100..150), "world" chars 6..10 (160..210);
        // the space leaves room for the full pad on both sides.
        assertEquals(RectF(97f, 47f, 153f, 73f), boxes[0].rect)
        assertEquals(RectF(157f, 47f, 213f, 73f), boxes[1].rect)
        assertEquals(listOf(0, 1), boxes.map { it.tokenIndex })
        assertEquals(listOf(0, 6), boxes.map { it.charOffset })
        assertEquals(listOf("hello", "world"), boxes.map { it.surface })
    }

    @Test fun `words that touch keep the gap between their strokes`() {
        // Unspaced: 日本 (0..1) then 語 (2): 100..120 and 120..130.
        val line = horizontal("日本語")
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("日本", "語"), pad, gap)

        assertEquals(119f, boxes[0].rect.right)
        assertEquals(121f, boxes[1].rect.left)
        assertEquals("the outer edges keep the pad", 97f, boxes[0].rect.left)
        assertEquals(133f, boxes[1].rect.right)
    }

    @Test fun `a line laid out right to left keeps its gaps by screen order`() {
        // Token 0 on the right, token 1 on its left, touching.
        val line = OcrManager.OcrLine(
            text = "ab",
            bounds = Rect(100, 0, 140, 20),
            symbols = listOf(
                OcrManager.SymbolBox("a", Rect(120, 0, 140, 20), 0),
                OcrManager.SymbolBox("b", Rect(100, 0, 120, 20), 1),
            ),
        )
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("a", "b"), pad, gap)

        assertEquals(RectF(121f, -3f, 143f, 23f), boxes[0].rect)
        assertEquals(RectF(97f, -3f, 119f, 23f), boxes[1].rect)
    }

    @Test fun `without symbols the boxes divide the line by its characters`() {
        val line = OcrManager.OcrLine(text = "ab cd", bounds = Rect(0, 0, 50, 10))
        val boxes = TapLookupGeometry.wordBoxes(line, 2, listOf("ab", "cd"), pad, gap)

        assertEquals(RectF(-3f, -3f, 23f, 13f), boxes[0].rect)
        assertEquals(RectF(27f, -3f, 53f, 13f), boxes[1].rect)
        assertEquals(listOf(2, 2), boxes.map { it.lineIndex })
    }

    @Test fun `a vertical line's boxes span each word down the column, across its width`() {
        val line = OcrManager.OcrLine(
            text = "日本語",
            bounds = Rect(200, 100, 220, 160),
            symbols = (0 until 3).map { i ->
                OcrManager.SymbolBox("日本語"[i].toString(), Rect(200, 100 + i * 20, 220, 120 + i * 20), i)
            },
            orientation = TextOrientation.VERTICAL,
        )
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("日本", "語"), pad, gap)

        assertEquals(RectF(197f, 97f, 223f, 139f), boxes[0].rect)
        assertEquals(RectF(197f, 141f, 223f, 163f), boxes[1].rect)
    }

    @Test fun `a slanted line's boxes sit in its deskewed frame, turned by its angle about its centre`() {
        // A 30-degree line, centre (200, 100), 100 wide and 20 tall in its own
        // frame; no symbols, so each of the two 1-char tokens spans half.
        val line = OcrManager.OcrLine(
            text = "ab",
            bounds = Rect(150, 70, 250, 130),
            angleDeg = 30f,
            orientedWidth = 100f,
            orientedHeight = 20f,
        )
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("a", "b"), pad, gap)

        assertEquals(30f, boxes[0].angleDeg)
        assertEquals(200f, boxes[0].pivotX)
        assertEquals(100f, boxes[0].pivotY)
        assertEquals(RectF(147f, 87f, 199f, 113f), boxes[0].rect)
        assertEquals(RectF(201f, 87f, 253f, 113f), boxes[1].rect)

        // A point on the baseline 25 px along from the centre lands in "b".
        val rad = Math.toRadians(30.0)
        val x = (200 + 25 * Math.cos(rad)).toFloat()
        val y = (100 + 25 * Math.sin(rad)).toFloat()
        assertEquals(1, TapLookupGeometry.hitTest(boxes, x, y, slopPx = 0f))
        // The bounds of a turned box contain its turned corners.
        val b = boxes[1].screenBounds()
        assertTrue(b.width() > boxes[1].rect.width())
    }

    @Test fun `every box's centre resolves, through the drag's own finger match, to that box's token`() {
        val lines = listOf(
            horizontal("hello world"),
            OcrManager.OcrLine(text = "ab cd", bounds = Rect(0, 0, 50, 10)),
            OcrManager.OcrLine(
                text = "日本語",
                bounds = Rect(200, 100, 220, 160),
                orientation = TextOrientation.VERTICAL,
            ),
            OcrManager.OcrLine(
                text = "ab", bounds = Rect(150, 70, 250, 130),
                angleDeg = 30f, orientedWidth = 100f, orientedHeight = 20f,
            ),
        )
        val tokens = listOf(listOf("hello", "world"), listOf("ab", "cd"), listOf("日本", "語"), listOf("a", "b"))
        for ((i, line) in lines.withIndex()) {
            val placed = DragLookupController.placeTokensOnLine(line, tokens[i])
            for (box in TapLookupGeometry.wordBoxes(line, i, tokens[i], pad, gap)) {
                // The box's centre, back on screen.
                val c = box.rect
                val rad = Math.toRadians(box.angleDeg.toDouble())
                val dx = c.centerX() - box.pivotX
                val dy = c.centerY() - box.pivotY
                val x = if (box.angleDeg == 0f) c.centerX() else (box.pivotX + dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
                val y = if (box.angleDeg == 0f) c.centerY() else (box.pivotY + dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
                val match = DragLookupController.closestPlacedToken(
                    placed, DragLookupController.flowPosition(line, Math.round(x), Math.round(y)),
                )
                assertEquals("line $i, box ${box.surface}", box.tokenIndex, match?.tokenIndex)
            }
        }
    }

    @Test fun `a tap inside a box lands on it`() {
        val boxes = TapLookupGeometry.wordBoxes(horizontal("hello world"), 0, listOf("hello", "world"), pad, gap)
        assertEquals(0, TapLookupGeometry.hitTest(boxes, 120f, 60f, slopPx = 6f))
        assertEquals(1, TapLookupGeometry.hitTest(boxes, 190f, 60f, slopPx = 6f))
    }

    @Test fun `a tap just outside a box lands on it within the slop and on nothing beyond`() {
        val boxes = TapLookupGeometry.wordBoxes(horizontal("hello world"), 0, listOf("hello", "world"), pad, gap)
        // The boxes end at y = 73.
        assertEquals(0, TapLookupGeometry.hitTest(boxes, 120f, 78f, slopPx = 6f))
        assertNull(TapLookupGeometry.hitTest(boxes, 120f, 80f, slopPx = 6f))
        assertNull("far from every word", TapLookupGeometry.hitTest(boxes, 400f, 400f, slopPx = 6f))
    }

    @Test fun `a tap inside one box and within the slop of a nearer-centred one lands on the box it is in`() {
        // A long word, then a short one right after it.
        val line = horizontal("abcdefgh i")
        val boxes = TapLookupGeometry.wordBoxes(line, 0, listOf("abcdefgh", "i"), pad, gap)
        // x = 181 is inside "abcdefgh" (97..183) and 4 px from "i" (187..203).
        assertEquals(0, TapLookupGeometry.hitTest(boxes, 181f, 60f, slopPx = 6f))
        // Between them, the nearer edge wins.
        assertEquals(1, TapLookupGeometry.hitTest(boxes, 186f, 60f, slopPx = 6f))
    }

    // ── the buffer: a tap that misses every box by a little picks the
    //    closest word, and overlapping reaches resolve by distance. ──────

    @Test fun `a tap in the gap between two lines picks the nearer line's word`() {
        val upper = horizontal("one", top = 100, bottom = 120)
        val lower = horizontal("two", top = 160, bottom = 180)
        val boxes = TapLookupGeometry.wordBoxes(upper, 0, listOf("one"), pad, gap) +
            TapLookupGeometry.wordBoxes(lower, 1, listOf("two"), pad, gap)
        // The boxes span 97..123 and 157..183: y = 130 is 7 below the upper
        // and 27 above the lower; y = 150 the other way round.
        assertEquals(0, TapLookupGeometry.hitTest(boxes, 110f, 130f, slopPx = 24f))
        assertEquals(1, TapLookupGeometry.hitTest(boxes, 110f, 150f, slopPx = 24f))
        assertNull("beyond the buffer of both", TapLookupGeometry.hitTest(boxes, 110f, 230f, slopPx = 24f))
    }

    @Test fun `a tap inside two boxes that overlap goes to the one whose centre is nearer`() {
        val a = TapLookupGeometry.WordBox(0, 0, 0, "a", RectF(100f, 100f, 200f, 140f))
        val b = TapLookupGeometry.WordBox(1, 0, 0, "b", RectF(180f, 110f, 280f, 150f))
        // (185, 125) is inside both: about 35 from a's centre (150, 120) and
        // 45 from b's (230, 130).
        assertEquals(0, TapLookupGeometry.hitTest(listOf(a, b), 185f, 125f, slopPx = 24f))
        assertEquals("the order of the boxes does not decide", 1, TapLookupGeometry.hitTest(listOf(b, a), 185f, 125f, slopPx = 24f))
    }

    @Test fun `a box a tap is in beats a neighbour whose buffer reaches it`() {
        val small = TapLookupGeometry.WordBox(0, 0, 0, "s", RectF(100f, 100f, 110f, 110f))
        val large = TapLookupGeometry.WordBox(1, 0, 0, "l", RectF(112f, 60f, 400f, 150f))
        // (108, 105) is inside the small box and 4 from the large one, whose
        // buffer reaches it.
        assertEquals(0, TapLookupGeometry.hitTest(listOf(small, large), 108f, 105f, slopPx = 24f))
        // (111, 105) is 1 from the small box and 1 from the large one: the
        // nearer centre (the small box's) takes it.
        assertEquals(0, TapLookupGeometry.hitTest(listOf(small, large), 111f, 105f, slopPx = 24f))
    }

    // ── the controller ring's step along a row ─────────────────────────────

    private val right = SheetNavGeometry.Dir.RIGHT
    private val left = SheetNavGeometry.Dir.LEFT

    @Test fun `along a row the ring passes a nearer word on the next row for the next word on its own`() {
        val bounds = listOf(
            RectF(97f, 297f, 143f, 333f),   // aa
            RectF(597f, 297f, 643f, 333f),  // bb, far along the same row
            RectF(157f, 337f, 203f, 373f),  // cc, just below aa
        )
        assertEquals(1, TapLookupGeometry.nextInRow(bounds, 0, right))
        assertEquals(0, TapLookupGeometry.nextInRow(bounds, 1, left))
        assertNull("the row ends", TapLookupGeometry.nextInRow(bounds, 1, right))
        assertNull("nothing left of the first word on its row", TapLookupGeometry.nextInRow(bounds, 0, left))
    }

    @Test fun `the next line's padded box grazing this row is not in it, a word beside it in another column is`() {
        val bounds = listOf(
            RectF(100f, 100f, 150f, 140f),  // this word
            RectF(160f, 132f, 200f, 172f),  // the next line, overlapping by 8 of 40
            RectF(400f, 104f, 450f, 144f),  // another column, 4 lower, overlapping by 36
        )
        assertEquals(2, TapLookupGeometry.nextInRow(bounds, 0, right))
    }

    @Test fun `on a vertical column the ring steps down and up along it`() {
        val bounds = listOf(
            RectF(200f, 100f, 230f, 140f),  // the column's first word
            RectF(200f, 145f, 230f, 185f),  // its second
            RectF(160f, 110f, 190f, 150f),  // the next column, to the left
        )
        assertEquals(1, TapLookupGeometry.nextInRow(bounds, 0, SheetNavGeometry.Dir.DOWN))
        assertEquals(0, TapLookupGeometry.nextInRow(bounds, 1, SheetNavGeometry.Dir.UP))
        assertNull(TapLookupGeometry.nextInRow(bounds, 1, SheetNavGeometry.Dir.DOWN))
    }
}
