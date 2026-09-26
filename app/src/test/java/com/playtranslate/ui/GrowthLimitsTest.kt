package com.playtranslate.ui

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [GrowthLimits.unboxedText]: the text of a look that no box covers. Robolectric for [Rect]. */
@RunWith(RobolectricTestRunner::class)
class GrowthLimitsTest {

    @Test
    fun textCentredInABoxsDrawnSource_isCovered_theRestIsNot() {
        val boxes = listOf(
            TextBox("a", Rect(10, 10, 50, 30)),
            // A base line whose furigana band is folded into the drawn rect.
            TextBox("b", Rect(10, 60, 50, 80), drawBounds = Rect(10, 50, 50, 80)),
        )
        val out = GrowthLimits.unboxedText(
            listOf(
                Rect(12, 11, 53, 31),   // the first box's text, read a few px off
                Rect(10, 50, 50, 58),   // the folded furigana band
                Rect(40, 20, 90, 40),   // straddles the first box, centre outside
                Rect(100, 90, 140, 120),  // unboxed
            ),
            boxes,
        )
        assertEquals(listOf(Rect(40, 20, 90, 40), Rect(100, 90, 140, 120)), out)
    }

    @Test
    fun noBoxes_leavesEveryRect() {
        val out = GrowthLimits.unboxedText(listOf(Rect(0, 0, 10, 10)), emptyList())
        assertEquals(listOf(Rect(0, 0, 10, 10)), out)
    }
}
