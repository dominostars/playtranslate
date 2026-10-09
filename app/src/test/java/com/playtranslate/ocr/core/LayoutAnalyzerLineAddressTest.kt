package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The layout's group text is its lines joined, and every line comes out
 *  addressed into it (the overlay's furigana lands by that address). */
@RunWith(RobolectricTestRunner::class)
class LayoutAnalyzerLineAddressTest {

    private fun region(text: String, r: Rect): RecognizedRegion {
        val box = OcrBox.upright(r)
        return RecognizedRegion(
            text = text, box = box, orientation = TextOrientation.HORIZONTAL,
            lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL)),
            origin = RegionOrigin.LINE,
        )
    }

    @Test
    fun `a wrapped paragraph's lines are addressed into the group text`() {
        val groups = LayoutAnalyzer.analyze(
            listOf(
                region("それで今日の手伝い終わ", Rect(100, 100, 540, 140)),
                region("りなねだって。", Rect(100, 150, 380, 190)),
            ),
            sourceLang = "ja", screenshotWidthInRegionSpace = 0f,
        )
        val g = groups.single()
        assertEquals("それで今日の手伝い終わりなねだって。", g.text)
        assertEquals(listOf(0, 11), g.lines.map { it.textStart })
    }

    @Test
    fun `a spaced script's addresses count the joining space`() {
        val groups = LayoutAnalyzer.analyze(
            listOf(
                region("Hello there", Rect(100, 100, 540, 140)),
                region("world", Rect(100, 150, 300, 190)),
            ),
            sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        val g = groups.single()
        assertEquals("Hello there world", g.text)
        assertEquals(listOf(0, 12), g.lines.map { it.textStart })
    }
}
