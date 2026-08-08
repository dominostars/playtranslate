package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [BlockTextAligner] — block-string → per-line splice with inherited
 * geometry. Synthetic fixtures only (strings + Rects); the correction examples are
 * the real ones from the manga-ocr findings report (いたい→いったい, 平く→早く,
 * 泊してから→一泊してから), NOT golden-image cases. Robolectric is mandatory for
 * real `Rect` arithmetic (see [CharBoxSynthesisTest]).
 */
@RunWith(RobolectricTestRunner::class)
class BlockTextAlignerTest {

    /** Vertical column at [left]: 40px wide, 40px per char, chars evenly tiled.
     *  (Real base engines emit real glyph boxes; even tiling just makes the
     *  inherited coordinates predictable in assertions.) */
    private fun column(text: String, left: Int = 0, withChars: Boolean = true): RecognizedLine {
        val box = Rect(left, 0, left + 40, (text.length * 40).coerceAtLeast(40))
        return RecognizedLine(
            text = text,
            box = OcrBox.upright(box),
            orientation = TextOrientation.VERTICAL,
            chars = if (withChars) synthesizeEvenCharBoxes(text, OcrBox.upright(box), vertical = true) else emptyList(),
        )
    }

    /** Horizontal line at [top]: 40px tall, 40px per char, chars evenly tiled. */
    private fun row(
        text: String,
        top: Int = 0,
        elements: List<ElementBox> = emptyList(),
    ): RecognizedLine {
        val box = Rect(0, top, (text.length * 40).coerceAtLeast(40), top + 40)
        return RecognizedLine(
            text = text,
            box = OcrBox.upright(box),
            orientation = TextOrientation.HORIZONTAL,
            elements = elements,
            chars = synthesizeEvenCharBoxes(text, OcrBox.upright(box), vertical = false),
        )
    }

    private fun aligned(result: BlockTextAligner.Result): BlockTextAligner.Result.Aligned {
        assertTrue("expected Aligned, got $result", result is BlockTextAligner.Result.Aligned)
        return result as BlockTextAligner.Result.Aligned
    }

    private fun rejected(result: BlockTextAligner.Result): BlockTextAligner.Result.Rejected {
        assertTrue("expected Rejected, got $result", result is BlockTextAligner.Result.Rejected)
        return result as BlockTextAligner.Result.Rejected
    }

    @Test
    fun `identical block text keeps every base line instance`() {
        val lines = listOf(column("いったい", left = 80), column("何をする", left = 0))
        val res = aligned(BlockTextAligner.align("いったい何をする", lines))
        assertEquals(2, res.lines.size)
        assertSame(lines[0], res.lines[0])
        assertSame(lines[1], res.lines[1])
        assertEquals(8, res.stats.matches)
        assertEquals(0, res.stats.insertions + res.stats.deletions + res.stats.substitutions)
    }

    @Test
    fun `inserted char adopts its line and leaves the neighbor untouched`() {
        // The report's real correction: the base engine read いったい as いたい.
        val itai = column("いたい", left = 80)
        val rest = column("何をする", left = 0)
        val res = aligned(BlockTextAligner.align("いったい何をする", listOf(itai, rest)))

        assertSame("unchanged line must keep its instance", rest, res.lines[1])
        val adopted = res.lines[0]
        assertEquals("いったい", adopted.text)
        assertSame("adopted line keeps the base line box", itai.box, adopted.box)
        assertEquals(listOf("い", "っ", "た", "い"), adopted.chars.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), adopted.chars.map { it.charOffset })

        // Matched chars inherit the base glyph boxes...
        val baseRects = itai.chars.map { it.box.bounds }
        assertEquals(baseRects[0], adopted.chars[0].box.bounds)
        assertEquals(baseRects[1], adopted.chars[2].box.bounds)
        assertEquals(baseRects[2], adopted.chars[3].box.bounds)
        // ...and the inserted っ has no room between its adjacent anchors, so it
        // copies the nearest anchor's box (never a zero-area cell).
        assertEquals(baseRects[0], adopted.chars[1].box.bounds)
    }

    @Test
    fun `substituted char inherits the exact base glyph box`() {
        // 平く→早く: a substitution is the same glyph position read differently,
        // so the base box IS the position.
        val line = row(
            "平く見つけよう",
            elements = listOf(ElementBox("平く", OcrBox.upright(Rect(0, 0, 80, 40)))),
        )
        val res = aligned(BlockTextAligner.align("早く見つけよう", listOf(line)))
        val adopted = res.lines[0]
        assertEquals("早く見つけよう", adopted.text)
        for (k in adopted.chars.indices) {
            assertEquals("char $k keeps its base box", line.chars[k].box.bounds, adopted.chars[k].box.bounds)
        }
        assertTrue("stale element tier must be dropped", adopted.elements.isEmpty())
        assertEquals(1, res.stats.substitutions)
    }

    @Test
    fun `boundary insertion attaches to the following line`() {
        // The report's 泊してから→一泊してから: the base engine dropped a
        // column-INITIAL glyph. The inserted 一 sits between an anchor in column 1
        // and an anchor in column 2 — it must join column 2, not tail column 1.
        val res = aligned(
            BlockTextAligner.align(
                "早く一泊してから",
                listOf(column("平く", left = 80), column("泊してから", left = 0)),
            )
        )
        assertEquals("早く", res.lines[0].text)
        assertEquals("一泊してから", res.lines[1].text)
    }

    @Test
    fun `leading insertions attach to the first line`() {
        val res = aligned(
            BlockTextAligner.align("いったい何をする", listOf(column("たい", left = 80), column("何をする")))
        )
        assertEquals("いったい", res.lines[0].text)
        assertEquals("何をする", res.lines[1].text)
        // No room above the first anchor → nearest anchor's box; still inside the line.
        val lineArea = Rect(80, 0, 120, 80)
        res.lines[0].chars.forEach {
            assertTrue("char box ${it.box.bounds} inside $lineArea", lineArea.contains(it.box.bounds))
        }
    }

    @Test
    fun `inserted char with room between anchors gets the gap cell`() {
        // Sparse base char tier with a real pixel gap between the two anchors: the
        // inserted char must tile that gap, cross-axis from the line box.
        val box = Rect(0, 0, 120, 40)
        val line = RecognizedLine(
            text = "あい",
            box = OcrBox.upright(box),
            orientation = TextOrientation.HORIZONTAL,
            chars = listOf(
                CharBox("あ", OcrBox.upright(Rect(0, 0, 40, 40)), 0),
                CharBox("い", OcrBox.upright(Rect(80, 0, 120, 40)), 1),
            ),
        )
        val adopted = aligned(BlockTextAligner.align("あるい", listOf(line))).lines[0]
        assertEquals("あるい", adopted.text)
        assertEquals(Rect(0, 0, 40, 40), adopted.chars[0].box.bounds)
        assertEquals(Rect(40, 0, 80, 40), adopted.chars[1].box.bounds)
        assertEquals(Rect(80, 0, 120, 40), adopted.chars[2].box.bounds)
    }

    @Test
    fun `line the block reading skipped keeps its base instance`() {
        // Printed rubi: the base engine emits it as its own tiny line; the block
        // specialist reads through it. All its base chars become deletions and no
        // block chars land on it — the base line stands.
        val rubi = column("ルビ", left = 44)
        val body = column("本文です", left = 0)
        val res = aligned(BlockTextAligner.align("本文です", listOf(rubi, body)))
        assertSame(rubi, res.lines[0])
        assertSame(body, res.lines[1])
        assertEquals(2, res.stats.deletions)
    }

    @Test
    fun `adopted line without a base char tier falls back to even spread`() {
        val line = column("いたい", withChars = false)
        val res = aligned(BlockTextAligner.align("いったい", listOf(line)))
        val adopted = res.lines[0]
        assertEquals(4, adopted.chars.size)
        assertEquals(line.box.bounds.top, adopted.chars.first().box.bounds.top)
        assertEquals(line.box.bounds.bottom, adopted.chars.last().box.bounds.bottom)
        assertEquals(listOf(0, 1, 2, 3), adopted.chars.map { it.charOffset })
    }

    @Test
    fun `same-length garbage is rejected on match rate`() {
        // An all-substitution alignment has zero gaps — only the match-rate guard
        // separates it from a same-length hallucination.
        val res = rejected(BlockTextAligner.align("ありがとう", listOf(column("こんにちは"))))
        assertTrue(res.reason, "match rate" in res.reason)
    }

    @Test
    fun `runaway long decode is rejected on gap rate`() {
        val res = rejected(BlockTextAligner.align("あ".repeat(30), listOf(column("こんにちは"))))
        assertTrue(res.reason, "gap rate" in res.reason)
    }

    @Test
    fun `blank block text and empty base are rejected`() {
        rejected(BlockTextAligner.align("", listOf(column("あ"))))
        rejected(BlockTextAligner.align("   ", listOf(column("あ"))))
        rejected(BlockTextAligner.align("あ", listOf(column(""))))
        rejected(BlockTextAligner.align("あ", emptyList()))
    }

    @Test
    fun `oversized input is rejected not aligned`() {
        val res = rejected(BlockTextAligner.align("あ".repeat(401), listOf(column("あ".repeat(10)))))
        assertTrue(res.reason, "too long" in res.reason)
    }

    @Test
    fun `rotated base interpolates the gap along the baseline`() {
        // A −20° base line (300×48 strip, AABB centered 200,150) with anchors at
        // u = −100 and +100 riding the baseline. The inserted middle char must
        // land BETWEEN them ON the baseline — its cell centered near the AABB
        // center and oh-tall — not tiled across the inflated AABB's x-span and
        // full height, which is what upright interpolation would do.
        val cos = 0.93969
        val sin = -0.34202
        fun baselineCell(u: Double): Rect {
            val cx = 200 + u * cos
            val cy = 150 + u * sin
            return Rect((cx - 20).toInt(), (cy - 24).toInt(), (cx + 20).toInt(), (cy + 24).toInt())
        }
        val base = RecognizedLine(
            text = "あい",
            box = OcrBox(Rect(51, 76, 349, 224), 300f, 48f, -20f),
            orientation = TextOrientation.HORIZONTAL,
            chars = listOf(
                CharBox("あ", OcrBox.upright(baselineCell(-100.0)), 0),
                CharBox("い", OcrBox.upright(baselineCell(100.0)), 1),
            ),
        )
        val adopted = aligned(BlockTextAligner.align("あるい", listOf(base))).lines[0]
        assertEquals("あるい", adopted.text)
        // Anchored cells keep their engine boxes verbatim.
        assertEquals(baselineCell(-100.0), adopted.chars[0].box.bounds)
        assertEquals(baselineCell(100.0), adopted.chars[2].box.bounds)
        // The filled cell rides the baseline midpoint, at baseline height.
        val mid = adopted.chars[1].box.bounds
        assertTrue("center x near 200, got ${mid.centerX()}", Math.abs(mid.centerX() - 200) <= 3)
        assertTrue("center y near 150, got ${mid.centerY()}", Math.abs(mid.centerY() - 150) <= 3)
        assertTrue("cell height ≈ oh (48), got ${mid.height()}", mid.height() in 40..56)
        assertTrue("never the AABB's 148px span", mid.height() < 100)
    }
}
