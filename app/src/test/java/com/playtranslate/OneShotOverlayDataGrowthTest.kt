package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [OneShotOverlayData.growthLimits]: a box dropped for a blank translation
 *  leaves its source text on screen, so growth must keep clear of it.
 *  Robolectric for [Rect]. */
@RunWith(RobolectricTestRunner::class)
class OneShotOverlayDataGrowthTest {

    private val drawn = listOf(
        Rect(10, 10, 110, 40),
        Rect(10, 60, 110, 90),
        Rect(10, 110, 110, 140),
    )

    /** One skeleton box per group, index-aligned, as buildOneShotOverlayData makes them. */
    private fun skeleton() = OneShotOverlayData(
        boxes = drawn.map { TextBox("", Rect(it), drawBounds = Rect(it)) },
        cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
        cropWidth = 200, cropHeight = 160,
        text = drawn.map { Rect(it) },
    )

    @Test
    fun skeleton_coversEveryGroup_avoidIsEmpty() {
        val limits = skeleton().growthLimits
        assertTrue(limits.avoid.isEmpty())
        assertEquals(Rect(0, 0, 200, 160), limits.bounds)
    }

    @Test
    fun blankMiddleTranslation_avoidIsTheDroppedGroupsText() {
        val filled = fillOneShotOverlayData(skeleton(), listOf("one", " ", "three"))!!
        assertEquals(2, filled.boxes.size)
        val limits = filled.growthLimits
        assertEquals(listOf(Rect(10, 60, 110, 90)), limits.avoid)
        assertEquals(Rect(0, 0, 200, 160), limits.bounds)
    }

    @Test
    fun everyTranslationBlank_isNull() {
        assertNull(fillOneShotOverlayData(skeleton(), listOf("", " ", "")))
    }
}
