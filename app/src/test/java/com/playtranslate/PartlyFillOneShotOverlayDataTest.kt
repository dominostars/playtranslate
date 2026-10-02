package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [partlyFillOneShotOverlayData]: a capture's boxes while its translation is
 * still arriving (the first Translating and each box fill before Done). Every
 * box stays: a box whose text is not known yet, or is blank, is still a
 * skeleton, unlike [fillOneShotOverlayData], which drops the blank ones once
 * the translations are final; a fill on top of an earlier one keeps what the
 * earlier one gave. Robolectric for [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class PartlyFillOneShotOverlayDataTest {

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

    @Test fun `known texts fill their boxes and every other box stays a skeleton`() {
        val skeleton = skeleton()
        val filled = partlyFillOneShotOverlayData(skeleton, listOf("one", null, " "))!!
        assertEquals(listOf("one", "", ""), filled.boxes.map { it.translatedText })
        // Only the text changed: geometry, colors and the read text are the skeleton's.
        assertEquals(skeleton.boxes[0].copy(translatedText = "one"), filled.boxes[0])
        assertEquals(skeleton.boxes.drop(1), filled.boxes.drop(1))
        assertEquals(skeleton.copy(boxes = filled.boxes), filled)
    }

    @Test fun `a fill on top of a partly filled data keeps the earlier fills where it names nothing`() {
        // The first paint (a cache fill in box 0), then a delivery naming box 1 only.
        val firstPaint = partlyFillOneShotOverlayData(skeleton(), listOf("one", null, null))!!
        val filled = partlyFillOneShotOverlayData(firstPaint, listOf(null, "two", null))!!
        assertEquals(listOf("one", "two", ""), filled.boxes.map { it.translatedText })
    }

    @Test fun `nothing known is the skeleton itself`() {
        val skeleton = skeleton()
        assertSame(skeleton, partlyFillOneShotOverlayData(skeleton, listOf(null, null, null)))
        assertSame(skeleton, partlyFillOneShotOverlayData(skeleton, listOf("", " ", null)))
    }

    @Test fun `a size mismatch is the skeleton itself`() {
        val skeleton = skeleton()
        assertSame(skeleton, partlyFillOneShotOverlayData(skeleton, listOf("one", "two")))
    }

    @Test fun `no skeleton is none`() {
        assertNull(partlyFillOneShotOverlayData(null, listOf("one")))
    }
}
