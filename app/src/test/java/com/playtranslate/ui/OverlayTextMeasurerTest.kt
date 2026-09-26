package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.ceil

/**
 * [OverlayTextMeasurer]'s plumbing on a template child: the frame it reports
 * is the template's own measurement at the wrap extent, axes swap for a
 * rotated child, a wrap shorter than the widest unbreakable run is no fit,
 * stacks pack by [VerticalTextLayout]'s arithmetic, and each (text, size,
 * wrap) is measured once, within a bounded LRU memo. Robolectric's legacy text metrics never wrap, so
 * line breaking itself is not pinned here (the layout is pinned with
 * injected metrics in [MinTextLayoutTest]).
 */
@RunWith(RobolectricTestRunner::class)
class OverlayTextMeasurerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    /** A template like the overlay's translation children, counting its measurements. */
    private class Template(ctx: Context) : TextView(ctx) {
        var measures = 0
        init {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(3, 3, 3, 3)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            measures++
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun measurer(template: TextView = Template(ctx), multiColumn: Boolean = false) =
        OverlayTextMeasurer(template, stackPadPx = 3f, multiColumnStacks = multiColumn)

    private fun box(text: String) = TextBox(text, Rect(0, 0, 1, 1))

    @Test
    fun horizontalFrame_isTheWrapExtent_byTheTemplatesHeight() {
        val template = Template(ctx)
        val m = measurer(template)
        val s = m.footprint(box("Hello there"), RenderMode.LEGACY_HORIZONTAL, 20, 200)
        assertNotNull(s)
        assertEquals(200, s!!.width)
        template.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
        template.text = "Hello there"
        template.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals(template.measuredHeight, s.height)
    }

    @Test
    fun rotatedFrame_swapsTheAxes() {
        val m = measurer()
        val h = m.footprint(box("Hello there"), RenderMode.LEGACY_HORIZONTAL, 20, 200)!!
        val r = m.footprint(box("Hello there"), RenderMode.ROTATE, 20, 200)!!
        assertEquals(FitSize(h.height, h.width), r)
    }

    @Test
    fun aWrapShorterThanTheWidestRun_isNoFit() {
        val template = Template(ctx)
        val m = measurer(template)
        template.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
        val run = ceil(template.paint.measureText("Supercalifragilistic")).toInt() + 1
        val text = box("A Supercalifragilistic word")
        assertNull(m.footprint(text, RenderMode.LEGACY_HORIZONTAL, 20, 6 + run - 1))
        assertNotNull(m.footprint(text, RenderMode.LEGACY_HORIZONTAL, 20, 6 + run))
    }

    @Test
    fun emptyText_andNoExtent_areNoFit() {
        val m = measurer()
        assertNull(m.footprint(box(""), RenderMode.LEGACY_HORIZONTAL, 20, 200))
        assertNull(m.footprint(box("Hi"), RenderMode.LEGACY_HORIZONTAL, 20, 0))
        assertNull(m.footprint(box("Hi"), RenderMode.LEGACY_HORIZONTAL, 0, 200))
    }

    @Test
    fun stacks_packByTheVerticalLayoutsArithmetic() {
        // Five cells at 20 px: a row is 21 px, a column 23 px, 3 px of
        // padding a side. 106 px holds one row short of five; 111 holds five.
        val latin = measurer(multiColumn = false)
        val cjk = measurer(multiColumn = true)
        val five = box("ABCDE")
        assertNull("a second column, single-column script", latin.footprint(five, RenderMode.STACK_UPRIGHT, 20, 106))
        assertEquals(FitSize(30, 111), latin.footprint(five, RenderMode.STACK_UPRIGHT, 20, 111))
        // Multi-column: 106 px holds four rows, so two columns.
        assertEquals(FitSize(53, 106), cjk.footprint(five, RenderMode.STACK_UPRIGHT, 20, 106))
        assertNull("no row at all", cjk.footprint(five, RenderMode.STACK_UPRIGHT, 20, 20))
    }

    @Test
    fun eachTextSizeAndWrap_isMeasuredOnce() {
        val template = Template(ctx)
        val m = measurer(template)
        val b = box("Hello there")
        repeat(3) { m.footprint(b, RenderMode.LEGACY_HORIZONTAL, 20, 200) }
        repeat(3) { m.footprint(b, RenderMode.ROTATE, 20, 200) }
        assertEquals(1, template.measures)
        m.footprint(b, RenderMode.LEGACY_HORIZONTAL, 20, 201)
        m.footprint(b, RenderMode.LEGACY_HORIZONTAL, 21, 200)
        m.footprint(box("Hello here"), RenderMode.LEGACY_HORIZONTAL, 20, 200)
        assertEquals(4, template.measures)
    }

    @Test
    fun theMemoIsBounded_leastRecentlyUsedGoesFirst() {
        val template = Template(ctx)
        // Four entries: each text takes two (its run width and its frame).
        val m = OverlayTextMeasurer(template, stackPadPx = 3f, multiColumnStacks = false, maxEntries = 4)
        val texts = listOf("One", "Two", "Three", "Four", "Five").map(::box)
        texts.forEach { m.footprint(it, RenderMode.LEGACY_HORIZONTAL, 20, 200) }
        assertEquals(5, template.measures)
        // The most recent is still held.
        m.footprint(texts.last(), RenderMode.LEGACY_HORIZONTAL, 20, 200)
        assertEquals(5, template.measures)
        // The first was evicted, so it is measured again.
        m.footprint(texts.first(), RenderMode.LEGACY_HORIZONTAL, 20, 200)
        assertEquals(6, template.measures)
    }
}
