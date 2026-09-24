package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HeaderFit], the pure rule behind the results headers' ⋯
 * overflow. No Android. Pins: everything inline while it fits; strict order
 * (what stays inline is a prefix, the tail folds first); ⋯ never takes a
 * lone action it costs as much as; the name keeps its width until nothing
 * fits beside it; wider never folds more.
 */
class HeaderFitTest {

    private val icon = 36
    private val gap = 16
    private val more = 36
    private val eye = 36

    /** The source header's shape: four icon actions, then the pinned eye. */
    private fun source(width: Int?, label: Int = 100) =
        HeaderFit.plan(width, label, gap, List(4) { icon }, listOf(eye), more)

    /** The target header's shape: the worded pill, Anki, Text size, eye. */
    private fun target(width: Int?, label: Int = 60) =
        HeaderFit.plan(width, label, gap, listOf(100, icon, icon), listOf(eye), more)

    // Source action side, gap to the label included:
    //   all four + eye:        5 * 36 + 5 * 16 = 260
    //   three + more + eye:    5 * 36 + 5 * 16 = 260
    //   two + more + eye:      4 * 36 + 4 * 16 = 208
    //   more + eye:            2 * 36 + 2 * 16 = 104

    @Test fun `everything inline at the exact boundary`() {
        assertEquals(HeaderFit.Plan(4, showMore = false, labelWidth = 100), source(360))
    }

    @Test fun `one pixel short folds two equal icons, never one`() {
        // Three + ⋯ costs exactly what all four do, so it can't fit either.
        assertEquals(HeaderFit.Plan(2, showMore = true, labelWidth = 100), source(359))
    }

    @Test fun `with equal icons more always takes at least two`() {
        for (w in 0..500) {
            val p = source(w)
            assertEquals("showMore iff something folded at $w", p.inlineCount < 4, p.showMore)
            if (p.showMore) assertTrue("at $w only ${4 - p.inlineCount} folded", 4 - p.inlineCount >= 2)
        }
    }

    @Test fun `the target folds from the tail and keeps the pill while it fits`() {
        // pill + anki + size + eye = 100 + 36 + 36 + 36 + 4 * 16 = 272
        assertEquals(HeaderFit.Plan(3, showMore = false, labelWidth = 60), target(332))
        // pill + anki + more + eye also costs 272; pill + more + eye = 220.
        assertEquals(HeaderFit.Plan(1, showMore = true, labelWidth = 60), target(331))
        assertEquals(HeaderFit.Plan(1, showMore = true, labelWidth = 60), target(280))
    }

    @Test fun `strict order - a pill that doesn't fit takes the narrower icons behind it`() {
        // Anki + Text size + more + eye (4 * 36 + 4 * 16 = 208, so 268 beside
        // the label) would fit at 279, but inline is always a prefix: the
        // pill folds, and so does everything after it.
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 60), target(279))
    }

    @Test fun `the label keeps its width while more and the eye fit beside it`() {
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 100), source(204))
    }

    @Test fun `the label is cut only when nothing fits beside it`() {
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 99), source(203))
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 46), source(150))
        for (w in 0..500) {
            val p = source(w)
            if (p.labelWidth < 100) assertEquals("label cut at $w with actions inline", 0, p.inlineCount)
        }
    }

    @Test fun `a lone icon never gets more, however narrow`() {
        for (w in -10..300) {
            val p = HeaderFit.plan(w, 80, gap, listOf(icon), listOf(eye), more)
            assertFalse("at $w", p.showMore)
            assertEquals(1, p.inlineCount)
        }
    }

    @Test fun `a lone action wider than more folds when it can't fit`() {
        // pill + eye = 100 + 36 + 2 * 16 = 168 beside a 60 label needs 228.
        val p = HeaderFit.plan(227, 60, gap, listOf(100), listOf(eye), more)
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 60), p)
    }

    @Test fun `pinned only never shows more`() {
        for (w in 0..200) {
            val p = HeaderFit.plan(w, 80, gap, emptyList(), listOf(eye), more)
            assertFalse(p.showMore)
            assertEquals((w - eye - gap).coerceIn(0, 80), p.labelWidth)
        }
    }

    @Test fun `no actions at all leaves the label alone until it overflows`() {
        assertEquals(HeaderFit.Plan(0, showMore = false, labelWidth = 80),
            HeaderFit.plan(80, 80, gap, emptyList(), emptyList(), more))
        assertEquals(HeaderFit.Plan(0, showMore = false, labelWidth = 50),
            HeaderFit.plan(50, 80, gap, emptyList(), emptyList(), more))
    }

    @Test fun `an unspecified width puts everything inline at the natural label`() {
        assertEquals(HeaderFit.Plan(4, showMore = false, labelWidth = 100), source(null))
    }

    @Test fun `zero and negative widths degrade without crashing`() {
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 0), source(0))
        assertEquals(HeaderFit.Plan(0, showMore = true, labelWidth = 0), source(-40))
    }

    @Test fun `a wider row never folds more or cuts the label shorter`() {
        for (shape in listOf(::source, ::target)) {
            var prev = shape(0, 100)
            for (w in 1..600) {
                val p = shape(w, 100)
                assertTrue("inline shrank at $w", p.inlineCount >= prev.inlineCount)
                assertTrue("label shrank at $w", p.labelWidth >= prev.labelWidth)
                prev = p
            }
        }
    }

    @Test fun `the gap is part of the budget`() {
        // Without gaps four icons + eye fit a 100 label in 280; with them all
        // four take 360, two + more + eye 308, and one + more + eye 256.
        val noGap = HeaderFit.plan(280, 100, 0, List(4) { icon }, listOf(eye), more)
        assertEquals(4, noGap.inlineCount)
        assertEquals(1, source(280).inlineCount)
    }
}
