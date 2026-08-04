package com.playtranslate.ocr.core

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the rotated-region standalone bypass in [LayoutAnalyzer.analyze]: a
 * rotated region never enters the grouping strategy — it becomes its own
 * single-region [LayoutGroup] carrying the slant — and its presence must not
 * change how the upright regions group. Also pins the replicated source-script
 * filter (the strategies apply it internally; the bypass must too).
 */
@RunWith(RobolectricTestRunner::class)
class LayoutAnalyzerRotatedBypassTest {

    private fun region(text: String, box: OcrBox): RecognizedRegion = RecognizedRegion(
        text = text,
        box = box,
        orientation = TextOrientation.HORIZONTAL,
        lines = listOf(RecognizedLine(text, box, TextOrientation.HORIZONTAL)),
        origin = RegionOrigin.LINE,
    )

    private fun upright(text: String, r: Rect) = region(text, OcrBox.upright(r))

    private fun rotated(text: String, r: Rect, angle: Float, w: Float, h: Float) =
        region(text, OcrBox(r, w, h, angle))

    @Test
    fun rotatedRegion_becomesStandaloneGroup_carryingSlant() {
        val para1 = upright("First line", Rect(0, 0, 300, 40))
        val para2 = upright("second line", Rect(0, 48, 300, 88))
        val banner = rotated("SALE", Rect(400, 0, 593, 135), angle = 30f, w = 200f, h = 40f)

        val withBanner = LayoutAnalyzer.analyze(
            listOf(para1, banner, para2), sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        val bannerGroup = withBanner.single { it.text == "SALE" }
        assertEquals(30f, bannerGroup.angleDeg, 0f)
        assertEquals(200f, bannerGroup.orientedWidth, 0f)
        assertEquals(40f, bannerGroup.orientedHeight, 0f)
        assertEquals(Rect(400, 0, 593, 135), bannerGroup.bounds)
        assertEquals(1, bannerGroup.lines.size)

        // The upright pair groups exactly as it would without the banner.
        val without = LayoutAnalyzer.analyze(
            listOf(para1, para2), sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(
            without.map { it.text to it.bounds },
            withBanner.filter { it.text != "SALE" }.map { it.text to it.bounds },
        )
    }

    @Test
    fun rotatedRegion_withoutSourceScript_isDropped() {
        val groups = LayoutAnalyzer.analyze(
            listOf(
                upright("Real text", Rect(0, 0, 300, 40)),
                rotated("###", Rect(400, 0, 560, 120), angle = 25f, w = 160f, h = 40f),
            ),
            sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertTrue(groups.none { it.text.contains("#") })
    }

    @Test
    fun allRotated_oneGroupEach_strategyNeverRuns() {
        val groups = LayoutAnalyzer.analyze(
            listOf(
                rotated("One", Rect(0, 0, 200, 100), 20f, 190f, 40f),
                rotated("Two", Rect(0, 110, 200, 210), -20f, 190f, 40f),
            ),
            sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(2, groups.size)
        assertEquals(setOf(20f, -20f), groups.map { it.angleDeg }.toSet())
    }

    @Test
    fun uprightGroups_carryZeroAngle() {
        val groups = LayoutAnalyzer.analyze(
            listOf(upright("Plain", Rect(0, 0, 300, 40))),
            sourceLang = "en", screenshotWidthInRegionSpace = 0f,
        )
        assertEquals(0f, groups.single().angleDeg, 0f)
        assertEquals(0f, groups.single().orientedWidth, 0f)
    }
}
