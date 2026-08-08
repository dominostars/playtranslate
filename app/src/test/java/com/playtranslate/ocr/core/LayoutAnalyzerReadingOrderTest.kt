package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the ONE emission-order policy in [LayoutAnalyzer.analyze]: the final
 * group list reads by POSITION on every frame — top-proximity bands, then
 * left-to-right within a band (right-to-left for RTL sources or bands holding
 * tategaki columns). The policy exists because the old order segregated by
 * kind: upright strategy output first, then vertical groups, then slant
 * clusters — an angled or tategaki group ABOVE an upright one emitted after it.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutAnalyzerReadingOrderTest {

    private fun region(
        text: String,
        box: OcrBox,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
    ) = RecognizedRegion(
        text = text,
        box = box,
        orientation = orientation,
        lines = listOf(RecognizedLine(text, box, orientation)),
        origin = RegionOrigin.LINE,
    )

    private fun upright(text: String, r: Rect, o: TextOrientation = TextOrientation.HORIZONTAL) =
        region(text, OcrBox.upright(r), o)

    @Test
    fun angledGroupAboveAnUprightOne_emitsFirst() {
        // The device-pass failure this stage answers: the angled 魅力 group sat
        // higher on the page but emitted after the upright group below it.
        val angled = region("SALE", OcrBox(Rect(100, 50, 293, 185), 200f, 40f, -20f))
        val lower = upright("Plain text", Rect(100, 400, 400, 440))
        val groups = LayoutAnalyzer.analyze(
            listOf(lower, angled), sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(listOf("SALE", "Plain text"), groups.map { it.text })
    }

    @Test
    fun tategakiColumns_bandWithNeighbors_andReadRightToLeft() {
        // Two columns side by side above a horizontal caption: the columns
        // band together (right first — columns read right-to-left) and the
        // caption follows by position instead of the old all-horizontal-first
        // order.
        val col1 = upright("いち", Rect(300, 100, 340, 400), TextOrientation.VERTICAL)
        val col2 = upright("にい", Rect(40, 100, 80, 400), TextOrientation.VERTICAL)
        val caption = upright("した", Rect(100, 500, 400, 540))
        val groups = LayoutAnalyzer.analyze(
            listOf(caption, col2, col1), sourceLang = "ja", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(listOf("いち", "にい", "した"), groups.map { it.text })
    }

    @Test
    fun sameBand_ltrSource_readsLeftToRight() {
        val left = upright("left", Rect(100, 100, 220, 140))
        val right = upright("right", Rect(400, 105, 520, 145))
        val groups = LayoutAnalyzer.analyze(
            listOf(right, left), sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(listOf("left", "right"), groups.map { it.text })
    }

    @Test
    fun sameBand_rtlSource_readsRightToLeft() {
        val left = upright("يسار", Rect(100, 100, 220, 140))
        val right = upright("يمين", Rect(400, 105, 520, 145))
        val groups = LayoutAnalyzer.analyze(
            listOf(left, right), sourceLang = "ar", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(listOf("يمين", "يسار"), groups.map { it.text })
    }

    @Test
    fun stackedRows_stayTopToBottom() {
        val top = upright("one", Rect(100, 100, 300, 140))
        val mid = upright("two", Rect(100, 500, 300, 540))
        val bot = upright("three", Rect(100, 900, 300, 940))
        val groups = LayoutAnalyzer.analyze(
            listOf(bot, top, mid), sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(listOf("one", "two", "three"), groups.map { it.text })
    }
}
