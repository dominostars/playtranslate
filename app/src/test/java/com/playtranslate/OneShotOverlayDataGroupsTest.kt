package com.playtranslate

import android.graphics.Rect
import com.playtranslate.ui.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A capture keeps the OCR groups it read on its overlay data
 * ([OneShotOverlayData.groups]), so the reading and word-box presentations
 * build from them without a second read: every fill carries them along, and
 * a fill whose every translation is blank keeps the data (no chips, groups
 * intact) instead of dropping it.
 */
@RunWith(RobolectricTestRunner::class)
class OneShotOverlayDataGroupsTest {

    private val rect = Rect(10, 10, 110, 40)
    private val group = OcrManager.OcrGroup(
        text = "hello",
        bounds = rect,
        lines = listOf(OcrManager.LineBox("hello", rect, groupIndex = 0, textStart = 0)),
    )

    private fun skeleton() = OneShotOverlayData(
        boxes = listOf(TextBox("", rect, drawBounds = rect)),
        cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
        cropWidth = 200, cropHeight = 160,
        text = listOf(rect),
        groups = listOf(group),
    )

    @Test fun `skeletons count as chips and carry the groups`() {
        val s = skeleton()
        assertTrue(s.hasChips)
        assertEquals(listOf(group), s.groups)
    }

    @Test fun `a fill carries the groups along`() {
        val filled = fillOneShotOverlayData(skeleton(), listOf("one"))!!
        assertEquals(listOf("one"), filled.boxes.map { it.translatedText })
        assertTrue(filled.hasChips)
        assertEquals(listOf(group), filled.groups)
    }

    @Test fun `every translation blank keeps the data without chips`() {
        val filled = fillOneShotOverlayData(skeleton(), listOf(" "))!!
        assertTrue(filled.boxes.isEmpty())
        assertFalse(filled.hasChips)
        assertEquals(listOf(group), filled.groups)
    }

    @Test fun `a fill that cannot be made is null`() {
        assertNull(fillOneShotOverlayData(null, listOf("one")))
        assertNull(fillOneShotOverlayData(skeleton(), listOf("one", "two")))
    }

    @Test fun `a partial fill carries the groups along`() {
        val partly = partlyFillOneShotOverlayData(skeleton(), listOf("one"))!!
        assertEquals(listOf(group), partly.groups)
    }

    @Test fun `data built without a read has no groups`() {
        val bare = OneShotOverlayData(emptyList(), 0, 0, 1, 1, 1, 1, emptyList())
        assertTrue(bare.groups.isEmpty())
        assertFalse(bare.hasChips)
    }
}
