package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A capture's boxes filled a second time ([refillOneShotOverlayData]), when
 * its groups are translated again by a service the user picked for the
 * result on show.
 *
 * The first fill drops the boxes whose translation came back blank, so the
 * boxes left can no longer be matched to the groups by position. The
 * capture's own boxes, one a group, are kept through every fill
 * ([OneShotOverlayData.skeleton]) and the refill is a fill of those: every
 * group takes its new translation, one that lost its box to a blank has it
 * again when the new translation is not, and one that is blank now loses
 * it. A partial fill keeps them too. With no data, or a count of texts that
 * is not the groups', there is nothing to refill.
 */
@RunWith(RobolectricTestRunner::class)
class RefillOneShotOverlayDataTest {

    private val rects = listOf(Rect(0, 0, 100, 30), Rect(0, 40, 100, 70), Rect(0, 80, 100, 110))

    private fun skeleton() = OneShotOverlayData(
        boxes = rects.map { TextBox("", it, drawBounds = it) },
        cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
        cropWidth = 200, cropHeight = 160,
        text = rects,
    )

    private fun texts(data: OneShotOverlayData) = data.boxes.map { it.translatedText to it.bounds }

    @Test fun `the capture's boxes are its skeleton until a fill, and stay it after`() {
        val built = skeleton()
        assertEquals(built.boxes, built.skeleton)

        val filled = fillOneShotOverlayData(built, listOf("one", "two", "three"))!!

        assertEquals("the fill wrote the boxes", listOf("one", "two", "three"), filled.boxes.map { it.translatedText })
        assertEquals("and left the skeleton as built", built.boxes, filled.skeleton)
        assertTrue(filled.skeleton.all { it.translatedText.isEmpty() })
    }

    @Test fun `every group takes its new translation, in its own box`() {
        val filled = fillOneShotOverlayData(skeleton(), listOf("one", "two", "three"))!!

        val refilled = refillOneShotOverlayData(filled, listOf("uno", "dos", "tres"))!!

        assertEquals(listOf("uno" to rects[0], "dos" to rects[1], "tres" to rects[2]), texts(refilled))
    }

    @Test fun `a group that lost its box to a blank has it again, and the others are not shifted onto it`() {
        // The second group came back blank the first time: two boxes left,
        // which by position alone would read as the first two groups.
        val filled = fillOneShotOverlayData(skeleton(), listOf("one", " ", "three"))!!
        assertEquals(listOf("one" to rects[0], "three" to rects[2]), texts(filled))

        val refilled = refillOneShotOverlayData(filled, listOf("uno", "dos", "tres"))!!

        assertEquals(listOf("uno" to rects[0], "dos" to rects[1], "tres" to rects[2]), texts(refilled))
    }

    @Test fun `a group that is blank now loses its box`() {
        val filled = fillOneShotOverlayData(skeleton(), listOf("one", "two", "three"))!!

        val refilled = refillOneShotOverlayData(filled, listOf("uno", "", "tres"))!!

        assertEquals(listOf("uno" to rects[0], "tres" to rects[2]), texts(refilled))
        // And a third translation still has all three to fill.
        assertEquals(3, refillOneShotOverlayData(refilled, listOf("a", "b", "c"))!!.boxes.size)
    }

    @Test fun `a partial fill keeps the skeleton too`() {
        val partly = partlyFillOneShotOverlayData(skeleton(), listOf("one", null, null))!!
        val done = fillOneShotOverlayData(partly, listOf("one", "", "three"))!!

        val refilled = refillOneShotOverlayData(done, listOf("uno", "dos", "tres"))!!

        assertEquals(listOf("uno" to rects[0], "dos" to rects[1], "tres" to rects[2]), texts(refilled))
    }

    @Test fun `with no data or a count that is not the groups' there is nothing to refill`() {
        val filled = fillOneShotOverlayData(skeleton(), listOf("one", "two", "three"))!!

        assertNull(refillOneShotOverlayData(null, listOf("uno")))
        assertNull("one text for three groups", refillOneShotOverlayData(filled, listOf("uno")))
        assertNull(refillOneShotOverlayData(filled, listOf("a", "b", "c", "d")))
    }
}
