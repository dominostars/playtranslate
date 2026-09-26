package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.view.View.MeasureSpec
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TranslationOverlayView]'s side of the minimum text size, at xhdpi so the
 * sp and px autosize ladders differ (1sp = 2px). Robolectric's legacy text
 * metrics are fake (a line never wraps), so these pin structure (which
 * ladder, which caps, what the footprint reports, when the view rebuilds),
 * not glyph widths; pass 4 itself is pinned with injected metrics in
 * [MinTextLayoutTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class TranslationOverlayMinTextTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private fun overlay(minTextSp: Int): TranslationOverlayView {
        ctx.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        val v = TranslationOverlayView(ctx, renderConfig = OverlayRenderConfig(minTextSp = minTextSp))
        layOut(v)
        return v
    }

    private fun layOut(v: TranslationOverlayView) {
        v.measure(
            MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, 1080, 1920)
    }

    /** Far too small for its text at any size above the floor. The padded
     *  rect is 12 px (6dp) wider on every side: (488, 488, 516, 516). */
    private val tinyBox = TextBox(translatedText = "Hello there friend", bounds = Rect(500, 500, 504, 504))
    private val paddedTiny = Rect(488, 488, 516, 516)

    private fun onlyChild(v: TranslationOverlayView) = v.getChildAt(0) as TextView

    @Test
    fun defaultMinimum_keepsTheSpLadder_andNothingGrows() {
        val v = overlay(6)
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920)
        layOut(v)
        val child = onlyChild(v)
        assertEquals("1sp steps", 2, child.autoSizeStepGranularity)
        assertEquals("6sp floor", 12, child.autoSizeMinTextSize)
        assertEquals(paddedTiny.width(), child.width)
        assertEquals(paddedTiny.height(), child.height)
    }

    @Test
    fun raisedMinimum_grownBox_getsThePxLadder() {
        val v = overlay(20)
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920)
        layOut(v)
        val child = onlyChild(v)
        assertEquals("1px steps", 1, child.autoSizeStepGranularity)
        assertEquals("the floor stays 6sp", 12, child.autoSizeMinTextSize)
        assertTrue("20sp (40px) reachable, max ${child.autoSizeMaxTextSize}", child.autoSizeMaxTextSize >= 40)
        assertTrue("grew", child.width * child.height > paddedTiny.width() * paddedTiny.height())
    }

    @Test
    fun raisedMinimum_boxThatAlreadyFits_isCertifiedInPlace() {
        val v = overlay(20)
        v.setBoxes(listOf(TextBox(translatedText = "Hi", bounds = Rect(100, 100, 900, 500))), 0, 0, 1080, 1920)
        layOut(v)
        val child = onlyChild(v)
        assertEquals(1, child.autoSizeStepGranularity)
        assertTrue(child.autoSizeMaxTextSize >= 40)
    }

    private fun frame(child: TextView) = Rect(child.left, child.top, child.right, child.bottom)

    /** A 2 x 2 rect inside [grown], 5 px out from [core]: part of the growth,
     *  clear of the core even after a 2 px jitter. */
    private fun inGrowth(grown: Rect, core: Rect): Rect = when {
        grown.right >= core.right + 8 -> Rect(core.right + 5, core.centerY() - 1, core.right + 7, core.centerY() + 1)
        grown.left <= core.left - 8 -> Rect(core.left - 7, core.centerY() - 1, core.left - 5, core.centerY() + 1)
        grown.bottom >= core.bottom + 8 -> Rect(core.centerX() - 1, core.bottom + 5, core.centerX() + 1, core.bottom + 7)
        else -> Rect(core.centerX() - 1, core.top - 7, core.centerX() + 1, core.top - 5)
    }

    private fun grownOverlay(): Pair<TranslationOverlayView, TextView> {
        val v = overlay(20)
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920)
        layOut(v)
        val grown = onlyChild(v)
        assertTrue("grew", grown.width * grown.height > paddedTiny.width() * paddedTiny.height())
        return v to grown
    }

    @Test
    fun sameLimits_keepTheLayout_throughEitherFastPath() {
        // Identical boxes, and boxes a px off (fuzzy-matched): the limits the
        // layout was drawn with never rebuild it.
        for (jitter in listOf(0, 1)) {
            val (v, grown) = grownOverlay()
            fun box(dx: Int) = Rect(500 + dx, 500, 504 + dx, 504).let { tinyBox.copy(bounds = it, drawBounds = Rect(it)) }
            v.setBoxes(listOf(box(jitter)), 0, 0, 1080, 1920)
            assertSame("jitter $jitter", grown, onlyChild(v))
        }
    }

    @Test
    fun newLimits_rebuildTheLayout_throughEitherFastPath() {
        // The clean-stream tier's case: a line typing out under a neighbour's
        // growth changes no box, only the limits, and the growth must retreat.
        for (jitter in listOf(0, 1)) {
            val (v, grown) = grownOverlay()
            fun box(dx: Int) = Rect(500 + dx, 500, 504 + dx, 504).let { tinyBox.copy(bounds = it, drawBounds = Rect(it)) }
            val typing = inGrowth(frame(grown), paddedTiny)
            v.setBoxes(listOf(box(jitter)), 0, 0, 1080, 1920, growthLimits = GrowthLimits(avoid = listOf(typing)))
            layOut(v)
            val rebuilt = onlyChild(v)
            assertTrue("jitter $jitter: rebuilt", rebuilt !== grown)
            assertFalse("clear of $typing: ${frame(rebuilt)}", Rect.intersects(frame(rebuilt), typing))
        }
    }

    @Test
    fun atTheDefault_limitsAreIgnored() {
        val v = overlay(6)
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920)
        layOut(v)
        val child = onlyChild(v)
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920, growthLimits = GrowthLimits(avoid = listOf(Rect(0, 0, 1080, 1920))))
        assertSame(child, onlyChild(v))
    }

    @Test
    fun aBoundTheGrowthNowLeaves_rebuildsInsideIt() {
        // The region shrinks to the source rect: no room left to grow, so the
        // box goes back to its padded rect.
        val (v, grown) = grownOverlay()
        v.setBoxes(listOf(tinyBox), 0, 0, 1080, 1920, growthLimits = GrowthLimits(bounds = Rect(500, 500, 504, 504)))
        layOut(v)
        val boxedIn = onlyChild(v)
        assertTrue(boxedIn !== grown)
        assertEquals(paddedTiny.width(), boxedIn.width)
        assertEquals(paddedTiny.height(), boxedIn.height)
    }
}
