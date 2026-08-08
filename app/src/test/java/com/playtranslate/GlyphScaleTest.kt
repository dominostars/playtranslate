package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.CharBox
import com.playtranslate.ocr.core.GlyphScale
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.synthesizeEvenCharBoxes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [GlyphScale] — the char-tier scale instrument. It is not wired
 * into grouping, so these pin the MEASUREMENT's properties, which is exactly
 * what has to hold before any rule is built on it:
 *  - a synthesized char tier is rejected rather than restated (the Paddle /
 *    manga-ocr path, where every cell inherits the line box's cross extent);
 *  - same-font lines with different glyph content agree, where the line box
 *    does not — the property the whole approach rests on;
 *  - a genuinely larger line still disagrees, at every quantile.
 *
 * Fixtures model Latin metrics in a 100-unit em: x-height glyphs 50, ascenders
 * 75, descender-bearing glyphs 70 sitting below the baseline. The line box is
 * the union, so it swings 50→95 on content alone at one font size — the noise
 * the instrument is meant to see past.
 */
@RunWith(RobolectricTestRunner::class)
class GlyphScaleTest {

    /** A horizontal line whose char boxes have the given heights, all sharing a
     *  baseline at y=95 unless the glyph descends. */
    private fun line(heights: List<Int>, scale: Double = 1.0): RecognizedLine {
        var x = 0
        val chars = heights.map { h ->
            val hh = (h * scale).toInt()
            val top = (95 * scale).toInt() - hh
            val r = Rect(x, top, x + 40, (95 * scale).toInt())
            x += 45
            CharBox(text = "x", box = OcrBox.upright(r), charOffset = 0)
        }
        val union = Rect(
            chars.minOf { it.box.bounds.left }, chars.minOf { it.box.bounds.top },
            chars.maxOf { it.box.bounds.right }, chars.maxOf { it.box.bounds.bottom },
        )
        return RecognizedLine(
            text = "x".repeat(heights.size), box = OcrBox.upright(union),
            orientation = TextOrientation.HORIZONTAL, chars = chars,
        )
    }

    // x-height only ("acorn me"), and the same font with ascenders + a descender
    private val xHeightOnly = line(listOf(50, 50, 50, 50, 50, 50))
    private val mixedGlyphs = line(listOf(50, 75, 50, 95, 50, 75))

    @Test
    fun lineBox_swingsOnContentAlone_atOneFontSize() {
        // The premise: these two lines are the SAME font size, and the gate's
        // current statistic says otherwise by a wide margin.
        val delta = GlyphScale.lineBoxDelta(xHeightOnly, mixedGlyphs)!!
        assertTrue("line-box delta should exceed the bare cap, was $delta", delta > 0.30)
    }

    @Test
    fun uniformGlyphHeights_stillCountAsMeasured() {
        // Trap found during bring-up: every glyph on this line is the same
        // height, so each char box equals the line box's height and a purely
        // cross-axis "is it thinner than the line" test rejects a perfectly real
        // tier — silencing the instrument on exactly the all-x-height content it
        // exists to measure. Inter-glyph gaps are what carry it.
        assertTrue(GlyphScale.hasMeasuredCharTier(xHeightOnly))
        assertNotNull(GlyphScale.quantiles(xHeightOnly))
    }

    @Test
    fun charTier_sameFontDifferentContent_agrees() {
        val delta = GlyphScale.scaleDelta(xHeightOnly, mixedGlyphs)
        assertNotNull(delta)
        assertEquals("same font must read as same scale", 0.0, delta!!, 0.001)
    }

    @Test
    fun charTier_largerLine_disagreesAtEveryQuantile() {
        // Same glyph mix, 1.45× the size — a Title Case heading over body.
        val heading = line(listOf(50, 75, 50, 95, 50, 75), scale = 1.45)
        val delta = GlyphScale.scaleDelta(mixedGlyphs, heading)!!
        assertTrue("a 1.45x heading must stay out of the corroborated cap, was $delta", delta > 0.30)
    }

    @Test
    fun charTier_rubyScale_disagrees() {
        val ruby = line(listOf(50, 50, 50, 50), scale = 0.5)
        val delta = GlyphScale.scaleDelta(mixedGlyphs, ruby)!!
        assertTrue("ruby must stay clearly out of scale, was $delta", delta > 0.50)
    }

    @Test
    fun synthesizedCharTier_isRejected() {
        // The Paddle / manga-ocr shape: cells sliced from the line box, so every
        // cell reports the line's own height. Restating that as a "char-tier"
        // measurement would be a confident-looking tautology.
        val bounds = Rect(0, 0, 400, 95)
        val synthetic = RecognizedLine(
            text = "abcdefgh", box = OcrBox.upright(bounds),
            orientation = TextOrientation.HORIZONTAL,
            chars = synthesizeEvenCharBoxes("abcdefgh", OcrBox.upright(bounds), vertical = false),
        )
        assertFalse(GlyphScale.hasMeasuredCharTier(synthetic))
        assertNull(GlyphScale.quantiles(synthetic))
        assertNull("null, never a fabricated agreement", GlyphScale.scaleDelta(synthetic, mixedGlyphs))
    }

    @Test
    fun charlessLine_isRejected() {
        val bare = RecognizedLine(
            text = "no chars", box = OcrBox.upright(Rect(0, 0, 400, 95)),
            orientation = TextOrientation.HORIZONTAL,
        )
        assertFalse(GlyphScale.hasMeasuredCharTier(bare))
        assertNull(GlyphScale.scaleDelta(bare, mixedGlyphs))
    }

    @Test
    fun vertical_measuresColumnThickness() {
        // Vertical CJK: the compared axis is width. A kana-narrow column and a
        // kanji column of the same font size agree on the char tier.
        fun col(widths: List<Int>): RecognizedLine {
            var y = 0
            val chars = widths.map { w ->
                val r = Rect(100 - w / 2, y, 100 + w / 2, y + 90)
                y += 95
                CharBox(text = "字", box = OcrBox.upright(r), charOffset = 0)
            }
            val union = Rect(
                chars.minOf { it.box.bounds.left }, chars.minOf { it.box.bounds.top },
                chars.maxOf { it.box.bounds.right }, chars.maxOf { it.box.bounds.bottom },
            )
            return RecognizedLine(
                text = "字".repeat(widths.size), box = OcrBox.upright(union),
                orientation = TextOrientation.VERTICAL, chars = chars,
            )
        }
        val kana = col(listOf(60, 60, 60, 60))
        val kanji = col(listOf(60, 90, 60, 90))
        assertTrue(GlyphScale.lineBoxDelta(kana, kanji)!! > 0.30)
        assertEquals(0.0, GlyphScale.scaleDelta(kana, kanji)!!, 0.001)
    }
}
