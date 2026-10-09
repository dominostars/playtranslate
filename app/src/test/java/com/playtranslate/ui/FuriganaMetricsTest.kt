package com.playtranslate.ui

import android.graphics.Typeface
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [FuriganaMetrics]: the renderer and the placement estimates measure a
 *  reading the same way. Robolectric measures glyphs as zero width, so the
 *  width assertions pin the outline term and the paint restore. */
@RunWith(RobolectricTestRunner::class)
class FuriganaMetricsTest {

    @Test
    fun `the measuring paint carries the drawn face and outline`() {
        val paint = FuriganaMetrics.measuringPaint(density = 2f)
        assertEquals("the face the renderer draws with", Typeface.DEFAULT_BOLD, paint.typeface)
        assertEquals(6f, paint.strokeWidth, 0f)
    }

    @Test
    fun `rendered width includes the outline and restores the paint`() {
        val paint = FuriganaMetrics.measuringPaint(density = 2f)
        val width = FuriganaMetrics.renderedWidth("きょう", bandExtentPx = 40f, paint = paint)
        assertTrue("outline is part of the drawn width", width >= 6f)
        assertEquals("size restored after measuring", 100f, paint.textSize, 0f)
    }

    @Test
    fun `text size is the band ratio with a floor`() {
        assertEquals(28f, FuriganaMetrics.textSizePx(40f), 0f)
        assertEquals(FuriganaMetrics.MIN_TEXT_PX, FuriganaMetrics.textSizePx(2f), 0f)
    }

    @Test
    fun `the outline is exactly the paint's stroke`() {
        // Robolectric's default paint stroke is not zero, so pin the
        // difference between an outlined paint and a plain one.
        val outlined = FuriganaMetrics.measuringPaint(density = 2f)
        val plain = TextPaint()
        val diff = FuriganaMetrics.renderedWidth("よ", 40f, outlined) - FuriganaMetrics.renderedWidth("よ", 40f, plain)
        assertEquals(outlined.strokeWidth - plain.strokeWidth, diff, 0.01f)
    }
}
