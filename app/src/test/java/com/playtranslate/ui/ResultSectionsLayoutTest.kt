package com.playtranslate.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.ui.ResultSectionsGeometry.PairedRow
import com.playtranslate.ui.ResultSectionsGeometry.Span
import com.playtranslate.ui.ResultSectionsLayout.Arrangement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ResultSectionsLayout] arranging the real results sections, inflated from
 * fragment_translation_result.xml (results_sections.xml's header and card
 * pairs, plus the page's Clear row) and measured the way its parent would.
 * A section is hidden the way its binder hides it: its card goes GONE.
 *
 * Pins: the arrangement follows the layout's FULL width through the 300dp
 * column rule (600dp stacks, 601dp pairs), flips both ways on a re-measure,
 * and yields to a host's fixed arrangement (and follows the width again
 * when that is cleared); stacked, the sections run Translation, Original,
 * Ask, Words, each across the inner width with no gaps; paired, the header cells
 * and cards sit where [ResultSectionsGeometry.pairedRow] puts them for each
 * of the four hide combinations, under one header row as tall as the
 * taller header, the surviving card spanning both headers and no card row
 * with both hidden; paired cards end on one line (the taller's height, a
 * card's minimum height included); Words, then the Clear row with its
 * margins, stack beneath at full width; a host given one of the pair stacks
 * it whatever it asks; RTL mirrors the pair; the measured height is the
 * last shown child's bottom plus the bottom padding; and every header is
 * measured at its final cell width.
 *
 * Native graphics, so the texts measure in a real font and a long text
 * really is taller. Pinned to mdpi: 1dp is 1px, so the design's numbers
 * (14dp side padding, a 17dp gap, a 140dp hidden cell) read as pixels; one
 * test runs at xhdpi, where those numbers must scale with the density.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "mdpi")
class ResultSectionsLayoutTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var activity: Host
    private lateinit var sections: ResultSectionsLayout
    private var density = 1f

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Host::class.java).setup().get()
        density = activity.resources.displayMetrics.density
        sections = inflateSections()
    }

    private fun inflateSections(): ResultSectionsLayout =
        LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
            .findViewById(R.id.resultsScrollContent)

    /** [value] dp in px, rounded the way the layout rounds its own. */
    private fun dp(value: Int): Int = (value * density).toInt()

    private val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    /** One measure and layout pass at [width], as a parent runs it. */
    private fun layOut(
        width: Int,
        heightSpec: Int = unspecified,
        layout: ResultSectionsLayout = sections,
    ) {
        layout.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
        layout.layout(0, 0, width, layout.measuredHeight)
    }

    private fun v(id: Int, layout: ResultSectionsLayout = sections): View = layout.findViewById(id)
    private val targetHeader: View get() = v(R.id.targetHeaderRow)
    private val targetCard: View get() = v(R.id.cardTranslation)
    private val sourceHeader: View get() = v(R.id.sourceHeaderRow)
    private val sourceCard: View get() = v(R.id.cardOriginal)
    private val askHeader: View get() = v(R.id.askHeaderRow)
    private val askCard: View get() = v(R.id.cardAsk)
    private val wordsHeader: View get() = v(R.id.wordsHeaderRow)
    private val wordsCard: View get() = v(R.id.cardWords)
    private val clearRow: View get() = v(R.id.resultActionButtons)

    private val pad: Int get() = sections.paddingLeft
    private fun inner(width: Int): Int = width - sections.paddingLeft - sections.paddingRight

    /** Hide a section the way its binder does: its card goes GONE. */
    private fun setHidden(original: Boolean, translation: Boolean) {
        sourceCard.visibility = if (original) View.GONE else View.VISIBLE
        targetCard.visibility = if (translation) View.GONE else View.VISIBLE
    }

    /** The paired row the layout should build at [width]. */
    private fun expectedRow(width: Int, originalHidden: Boolean, translationHidden: Boolean): PairedRow =
        ResultSectionsGeometry.pairedRow(
            inner(width), dp(1), dp(8), dp(140), originalHidden, translationHidden,
        )

    /** [view] laid out over [span] of the inner width: from the left padding,
     *  or mirrored from the right one under [rtl]. */
    private fun assertSpan(what: String, span: Span, view: View, width: Int, rtl: Boolean = false) {
        val left = if (rtl) width - sections.paddingRight - span.start - span.width else pad + span.start
        assertEquals("$what's left edge", left, view.left)
        assertEquals("$what's right edge", left + span.width, view.right)
    }

    /** [view] across the full inner width. */
    private fun assertFullWidth(what: String, view: View, width: Int) =
        assertSpan(what, Span(0, inner(width)), view, width)

    /**
     * The two sections beneath the top block, from [top]: Ask (its header,
     * then its card) and then Words, each beginning where the one above it
     * ended and running the full inner width of [width]. What "beneath the
     * top block" means in every arrangement and hide combination.
     */
    private fun assertBeneathTopBlock(at: String, top: Int, width: Int) {
        assertTrue("the Ask card has a height ($at)", askCard.height > 0)
        assertEquals("Ask starts under the top block ($at)", top, askHeader.top)
        assertFullWidth("the Ask header, $at", askHeader, width)
        assertEquals("the Ask card under its header ($at)", askHeader.bottom, askCard.top)
        assertFullWidth("the Ask card, $at", askCard, width)
        assertEquals("Words starts under Ask ($at)", askCard.bottom, wordsHeader.top)
        assertFullWidth("the Words header, $at", wordsHeader, width)
    }

    /** Gives the Words card something to show, so its own height is real. */
    private fun fillWords() {
        v(R.id.tvNoWords).visibility = View.VISIBLE
    }

    private fun setTexts(original: String, translation: String) {
        v(R.id.tvOriginal).let { (it as TextView).text = original }
        v(R.id.tvTranslation).let { (it as TextView).text = translation }
    }

    /**
     * Lays out at [width] with the given cards hidden and checks the paired
     * top block against [expectedRow]: the header cells at their spans
     * under one top, the shown cards at theirs, starting at the header row's
     * bottom and ending on one line. Returns the y beneath the block.
     */
    private fun assertPairedTopBlock(width: Int, originalHidden: Boolean, translationHidden: Boolean): Int {
        setHidden(originalHidden, translationHidden)
        layOut(width)
        assertEquals(Arrangement.PAIRED, sections.arrangement)
        val row = expectedRow(width, originalHidden, translationHidden)
        assertSpan("the Original header", row.sourceHeader, sourceHeader, width)
        assertSpan("the Translation header", row.targetHeader, targetHeader, width)
        assertEquals("the headers share one top", sections.paddingTop, sourceHeader.top)
        assertEquals("the headers share one top", sections.paddingTop, targetHeader.top)
        val headerRowBottom = maxOf(sourceHeader.bottom, targetHeader.bottom)
        var bottom = headerRowBottom
        row.sourceCard?.let { span ->
            assertSpan("the Original card", span, sourceCard, width)
            assertEquals("the card row starts at the header row's bottom", headerRowBottom, sourceCard.top)
            bottom = sourceCard.bottom
        }
        row.targetCard?.let { span ->
            assertSpan("the Translation card", span, targetCard, width)
            assertEquals("the card row starts at the header row's bottom", headerRowBottom, targetCard.top)
            if (row.sourceCard != null) assertEquals("the cards end on one line", bottom, targetCard.bottom)
            bottom = targetCard.bottom
        }
        return bottom
    }

    // ── The arrangement ─────────────────────────────────────────────────

    @Test
    fun `the arrangement follows the full width, padding included - 600dp stacks, 601dp pairs`() {
        layOut(dp(600))
        assertEquals(Arrangement.STACKED, sections.arrangement)
        assertEquals("stacked: the Original header under the Translation card", targetCard.bottom, sourceHeader.top)
        // (601 - 1) / 2 = 300 columns pair. The inner width (573) would give
        // (573 - 1) / 2 = 286 and stack: the rule reads the full width.
        layOut(dp(601))
        assertEquals(Arrangement.PAIRED, sections.arrangement)
        assertEquals("paired: the two headers share the top", targetHeader.top, sourceHeader.top)
        assertSpan("the Original header", expectedRow(dp(601), false, false).sourceHeader, sourceHeader, dp(601))
    }

    @Test
    @Config(qualifiers = "xhdpi")
    fun `at another density the column rule, the gap and the hidden cell scale with it`() {
        // At mdpi a dp read as a px passes unnoticed; at 2px per dp it cannot.
        assertEquals(2f, density, 0f)
        // Columns of (1201 - 2) / 2 = 599px stack; (1202 - 2) / 2 = 600px pair.
        layOut(1201)
        assertEquals(Arrangement.STACKED, sections.arrangement)
        layOut(1202)
        assertEquals(Arrangement.PAIRED, sections.arrangement)
        // 1600px: padding 28 each side, inner 1544, gap 2 + 2 * 16 = 34,
        // shared 1510; the hidden cell 280, the Original header the rest.
        setHidden(original = false, translation = true)
        layOut(1600)
        assertEquals(28 to 1258, sourceHeader.left to sourceHeader.right)
        assertEquals(1292 to 1572, targetHeader.left to targetHeader.right)
        assertEquals(280, targetHeader.measuredWidth)
        assertEquals(28 to 1572, sourceCard.left to sourceCard.right)
    }

    @Test
    fun `re-measuring the same layout at another width flips the arrangement both ways`() {
        val widths = listOf(
            601 to Arrangement.PAIRED, 600 to Arrangement.STACKED, 601 to Arrangement.PAIRED,
            412 to Arrangement.STACKED, 1200 to Arrangement.PAIRED, 600 to Arrangement.STACKED,
        )
        for ((widthDp, expected) in widths) {
            val width = dp(widthDp)
            layOut(width)
            assertEquals("at ${widthDp}dp", expected, sections.arrangement)
            if (expected == Arrangement.PAIRED) {
                assertEquals("paired at ${widthDp}dp", sections.paddingTop, sourceHeader.top)
                assertSpan("the Original header at ${widthDp}dp",
                    expectedRow(width, false, false).sourceHeader, sourceHeader, width)
            } else {
                assertEquals("stacked at ${widthDp}dp", targetCard.bottom, sourceHeader.top)
                assertFullWidth("the Original header at ${widthDp}dp", sourceHeader, width)
            }
        }
    }

    @Test
    fun `a fixed arrangement overrides the width both ways, and clearing it follows the width again`() {
        sections.fixedArrangement = Arrangement.PAIRED
        layOut(dp(412))
        assertEquals("paired at a phone's width when the host says so", Arrangement.PAIRED, sections.arrangement)
        // 412: inner 384, shared 367, halves 183 | 184.
        assertEquals(Span(0, 183), expectedRow(dp(412), false, false).sourceHeader)
        assertSpan("the Original header", Span(0, 183), sourceHeader, dp(412))
        assertSpan("the Translation header", Span(200, 184), targetHeader, dp(412))

        sections.fixedArrangement = Arrangement.STACKED
        layOut(dp(800))
        assertEquals("stacked at a tablet's width when the host says so", Arrangement.STACKED, sections.arrangement)
        assertEquals(targetCard.bottom, sourceHeader.top)
        assertFullWidth("the Original header", sourceHeader, dp(800))

        sections.fixedArrangement = null
        layOut(dp(800))
        assertEquals("cleared: the width decides again", Arrangement.PAIRED, sections.arrangement)
        assertEquals(sections.paddingTop, sourceHeader.top)
        layOut(dp(412))
        assertEquals(Arrangement.STACKED, sections.arrangement)
    }

    // ── Stacked ─────────────────────────────────────────────────────────

    @Test
    fun `stacked, the sections run Translation, Original, Ask, Words top to bottom, each across the inner width with no gaps`() {
        fillWords()
        setTexts("猫が魚を食べた。", "The cat ate the fish.")
        val width = dp(412)
        layOut(width)
        assertEquals(Arrangement.STACKED, sections.arrangement)
        val order = listOf(
            "the Translation header" to targetHeader, "the Translation card" to targetCard,
            "the Original header" to sourceHeader, "the Original card" to sourceCard,
            "the Ask header" to askHeader, "the Ask card" to askCard,
            "the Words header" to wordsHeader, "the Words card" to wordsCard,
        )
        var y = sections.paddingTop
        for ((what, view) in order) {
            assertTrue("$what has a height", view.height > 0)
            assertEquals("$what starts where the one above it ended", y, view.top)
            assertFullWidth(what, view, width)
            y = view.bottom
        }
        assertEquals(dp(14), pad)
        assertEquals("inner width: 412 less 14 each side", dp(384), wordsCard.width)
    }

    // ── Paired: the four hide combinations ──────────────────────────────

    @Test
    fun `paired with both shown, the headers halve the row and each card sits under its own header`() {
        fillWords()
        val bottom = assertPairedTopBlock(dp(800), originalHidden = false, translationHidden = false)
        // The design at 800: inner 772, shared 755, halves 377 | 378.
        assertEquals(dp(14) to dp(391), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(408) to dp(786), targetHeader.left to targetHeader.right)
        assertEquals(sourceHeader.left to sourceHeader.right, sourceCard.left to sourceCard.right)
        assertEquals(targetHeader.left to targetHeader.right, targetCard.left to targetCard.right)
        assertBeneathTopBlock("under the card row", bottom, dp(800))
        // And at a second width, against the geometry alone.
        assertPairedTopBlock(dp(1000), originalHidden = false, translationHidden = false)
    }

    @Test
    fun `paired with Translation hidden, its header is the 140dp cell at the end and the Original card spans both headers`() {
        fillWords()
        val bottom = assertPairedTopBlock(dp(800), originalHidden = false, translationHidden = true)
        assertEquals(dp(14) to dp(629), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(646) to dp(786), targetHeader.left to targetHeader.right)
        assertFullWidth("the surviving Original card", sourceCard, dp(800))
        assertBeneathTopBlock("under the one card", bottom, dp(800))
        assertPairedTopBlock(dp(1000), originalHidden = false, translationHidden = true)
    }

    @Test
    fun `paired with Original hidden, its header is the 140dp cell at the start and the Translation card spans both headers`() {
        fillWords()
        val bottom = assertPairedTopBlock(dp(800), originalHidden = true, translationHidden = false)
        assertEquals(dp(14) to dp(154), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(171) to dp(786), targetHeader.left to targetHeader.right)
        assertFullWidth("the surviving Translation card", targetCard, dp(800))
        assertBeneathTopBlock("under the one card", bottom, dp(800))
        assertPairedTopBlock(dp(1000), originalHidden = true, translationHidden = false)
    }

    @Test
    fun `paired with both hidden, the headers halve the row, there is no card row, and the sections beneath start right under the headers`() {
        fillWords()
        val bottom = assertPairedTopBlock(dp(800), originalHidden = true, translationHidden = true)
        assertEquals(dp(14) to dp(391), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(408) to dp(786), targetHeader.left to targetHeader.right)
        assertEquals("no card row", maxOf(sourceHeader.bottom, targetHeader.bottom), bottom)
        assertBeneathTopBlock("directly under the header row", bottom, dp(800))
    }

    @Test
    fun `the card row starts under the taller of the two headers`() {
        layOut(dp(800))
        val natural = sourceHeader.height
        assertEquals("the two headers are alike", natural, targetHeader.height)

        targetHeader.minimumHeight = natural + dp(30)
        layOut(dp(800))
        assertEquals(natural + dp(30), targetHeader.height)
        assertEquals(sections.paddingTop, sourceHeader.top)
        assertEquals(targetHeader.bottom, sourceCard.top)
        assertEquals(targetHeader.bottom, targetCard.top)

        targetHeader.minimumHeight = 0
        sourceHeader.minimumHeight = natural + dp(40)
        layOut(dp(800))
        assertEquals(sections.paddingTop, targetHeader.top)
        assertEquals(sourceHeader.bottom, sourceCard.top)
        assertEquals(sourceHeader.bottom, targetCard.top)
    }

    @Test
    fun `each header is measured at its final cell width`() {
        setHidden(original = false, translation = true)
        layOut(dp(800))
        assertEquals("the hidden cell", dp(140), targetHeader.measuredWidth)
        assertEquals("the rest: 772 - 17 - 140", dp(615), sourceHeader.measuredWidth)

        setHidden(original = true, translation = false)
        layOut(dp(800))
        assertEquals("the hidden cell", dp(140), sourceHeader.measuredWidth)
        assertEquals(dp(615), targetHeader.measuredWidth)

        setHidden(original = false, translation = false)
        layOut(dp(800))
        assertEquals(dp(377), sourceHeader.measuredWidth)
        assertEquals(dp(378), targetHeader.measuredWidth)

        layOut(dp(412))
        assertEquals("stacked: the inner width", dp(384), sourceHeader.measuredWidth)
        assertEquals(dp(384), targetHeader.measuredWidth)
    }

    // ── Paired cards: one height ────────────────────────────────────────

    /** [card]'s own height at its laid-out width, as if it stood alone. */
    private fun naturalHeight(card: View): Int {
        card.measure(MeasureSpec.makeMeasureSpec(card.width, MeasureSpec.EXACTLY), unspecified)
        return card.measuredHeight
    }

    @Test
    fun `paired cards take the taller card's height when their texts differ`() {
        val long = "The quick brown fox jumps over the lazy dog, again and again, ".repeat(8)
        for ((original, translation) in listOf(long to "Short.", "短い。" to long)) {
            setTexts(original, translation)
            layOut(dp(800))
            assertEquals(Arrangement.PAIRED, sections.arrangement)
            val top = maxOf(sourceHeader.bottom, targetHeader.bottom)
            assertEquals(top, sourceCard.top)
            assertEquals(top, targetCard.top)
            val sourceBottom = sourceCard.bottom
            val targetBottom = targetCard.bottom
            val laidOut = sourceCard.height
            // Measured alone (after the positions were read), the two cards
            // differ by many lines: the shared height is the layout's doing.
            val naturalSource = naturalHeight(sourceCard)
            val naturalTarget = naturalHeight(targetCard)
            assertTrue(
                "the texts make cards of different heights ($naturalSource vs $naturalTarget)",
                kotlin.math.abs(naturalSource - naturalTarget) > dp(100),
            )
            assertEquals("one bottom line", sourceBottom, targetBottom)
            assertEquals("the taller card's height", maxOf(naturalSource, naturalTarget), laidOut)
        }
    }

    @Test
    fun `a card's minimum height pulls the other paired card to the same height`() {
        val tall = dp(400)
        targetCard.minimumHeight = tall
        layOut(dp(800))
        assertEquals(tall, targetCard.height)
        assertEquals("the Original card follows the Translation's minimum", tall, sourceCard.height)
        assertEquals(targetCard.bottom, sourceCard.bottom)
        assertTrue("the Original card alone is shorter", naturalHeight(sourceCard) < tall)

        targetCard.minimumHeight = 0
        sourceCard.minimumHeight = tall
        layOut(dp(800))
        assertEquals(tall, sourceCard.height)
        assertEquals("the Translation card follows the Original's minimum", tall, targetCard.height)
        assertEquals(sourceCard.bottom, targetCard.bottom)
        assertTrue("the Translation card alone is shorter", naturalHeight(targetCard) < tall)
    }

    // ── Ask, Words and the Clear row ────────────────────────────────────

    @Test
    fun `Ask and Words sit beneath the top block at the full inner width in both arrangements`() {
        fillWords()
        layOut(dp(412))
        assertEquals(Arrangement.STACKED, sections.arrangement)
        assertBeneathTopBlock("stacked", sourceCard.bottom, dp(412))
        assertEquals(wordsHeader.bottom, wordsCard.top)
        assertFullWidth("the Words card", wordsCard, dp(412))

        for ((originalHidden, translationHidden) in listOf(false to false, false to true, true to false, true to true)) {
            val blockBottom = assertPairedTopBlock(dp(800), originalHidden, translationHidden)
            val at = "paired, Original hidden $originalHidden, Translation hidden $translationHidden"
            assertBeneathTopBlock(at, blockBottom, dp(800))
            assertEquals(at, wordsHeader.bottom, wordsCard.top)
            assertFullWidth("the Words card, $at", wordsCard, dp(800))
        }
    }

    @Test
    fun `a hidden Ask card leaves its header row, with Words directly beneath it`() {
        fillWords()
        askCard.visibility = View.GONE
        for (width in listOf(dp(412), dp(800))) {
            layOut(width)
            val blockBottom = if (sections.arrangement == Arrangement.PAIRED) {
                maxOf(sourceCard.bottom, targetCard.bottom)
            } else {
                sourceCard.bottom
            }
            assertEquals("the header row stays at ${width}px", blockBottom, askHeader.top)
            assertTrue(askHeader.height > 0)
            assertFullWidth("the Ask header", askHeader, width)
            assertEquals("Words right under it at ${width}px", askHeader.bottom, wordsHeader.top)
        }
    }

    @Test
    fun `a hidden Words card leaves its header row, with the next child directly beneath it`() {
        wordsCard.visibility = View.GONE
        clearRow.visibility = View.VISIBLE
        val clearTopMargin = (clearRow.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        for (width in listOf(dp(412), dp(800))) {
            layOut(width)
            val blockBottom = if (sections.arrangement == Arrangement.PAIRED) {
                maxOf(sourceCard.bottom, targetCard.bottom)
            } else {
                sourceCard.bottom
            }
            assertEquals("the header row stays at ${width}px", askCard.bottom, wordsHeader.top)
            assertEquals("Ask is between it and the top block at ${width}px", blockBottom, askHeader.top)
            assertTrue(wordsHeader.height > 0)
            assertFullWidth("the Words header", wordsHeader, width)
            assertEquals("the Clear row right under it at ${width}px",
                wordsHeader.bottom + clearTopMargin, clearRow.top)
        }
    }

    @Test
    fun `the Clear row stacks last beneath Words inside its margins, and takes no space while GONE`() {
        fillWords()
        val lp = clearRow.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals("the row's margins as laid out in XML", dp(20) to dp(12), lp.topMargin to lp.bottomMargin)
        for (width in listOf(dp(412), dp(800))) {
            clearRow.visibility = View.VISIBLE
            layOut(width)
            assertEquals("at ${width}px", wordsCard.bottom + dp(20), clearRow.top)
            assertTrue(clearRow.height > 0)
            assertFullWidth("the Clear row", clearRow, width)
            assertEquals("its bottom margin and the padding close the layout at ${width}px",
                clearRow.bottom + dp(12) + sections.paddingBottom, sections.measuredHeight)

            clearRow.visibility = View.GONE
            layOut(width)
            assertEquals("GONE, nothing below Words at ${width}px",
                wordsCard.bottom + sections.paddingBottom, sections.measuredHeight)
        }
    }

    // ── One of the pair ─────────────────────────────────────────────────

    @Test
    fun `a layout given only one of the pair stacks it at any width, even when PAIRED is asked for`() {
        for ((keptHeader, keptCard, droppedHeader, droppedCard) in listOf(
            listOf(R.id.sourceHeaderRow, R.id.cardOriginal, R.id.targetHeaderRow, R.id.cardTranslation),
            listOf(R.id.targetHeaderRow, R.id.cardTranslation, R.id.sourceHeaderRow, R.id.cardOriginal),
        )) {
            val layout = inflateSections()
            layout.removeView(v(droppedHeader, layout))
            layout.removeView(v(droppedCard, layout))
            layout.fixedArrangement = Arrangement.PAIRED
            for (width in listOf(dp(412), dp(800), dp(1200))) {
                layOut(width, layout = layout)
                val name = layout.resources.getResourceEntryName(keptHeader)
                assertEquals("only $name, at ${width}px", Arrangement.STACKED, layout.arrangement)
                val header = v(keptHeader, layout)
                val card = v(keptCard, layout)
                assertEquals(layout.paddingTop, header.top)
                assertEquals(layout.paddingLeft to width - layout.paddingRight, header.left to header.right)
                assertEquals(header.bottom, card.top)
                assertEquals(layout.paddingLeft to width - layout.paddingRight, card.left to card.right)
                assertEquals(card.bottom, v(R.id.askHeaderRow, layout).top)
            }
            layout.fixedArrangement = null
            layOut(dp(1200), layout = layout)
            assertEquals(Arrangement.STACKED, layout.arrangement)
        }
    }

    // ── RTL ─────────────────────────────────────────────────────────────

    @Test
    fun `under RTL the pair mirrors - Original's cell on the right, a hidden Translation's 140dp cell at the left end`() {
        sections.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val width = dp(800)

        setHidden(original = false, translation = false)
        layOut(width)
        assertEquals(dp(409) to dp(786), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(14) to dp(392), targetHeader.left to targetHeader.right)
        assertEquals(sourceHeader.left to sourceHeader.right, sourceCard.left to sourceCard.right)
        assertEquals(targetHeader.left to targetHeader.right, targetCard.left to targetCard.right)

        setHidden(original = false, translation = true)
        layOut(width)
        assertEquals("the hidden Translation's cell at the left end", dp(14) to dp(154), targetHeader.left to targetHeader.right)
        assertEquals(dp(171) to dp(786), sourceHeader.left to sourceHeader.right)
        assertEquals("the Original card spans both", dp(14) to dp(786), sourceCard.left to sourceCard.right)

        setHidden(original = true, translation = false)
        layOut(width)
        assertEquals("the hidden Original's cell at the right end", dp(646) to dp(786), sourceHeader.left to sourceHeader.right)
        assertEquals(dp(14) to dp(629), targetHeader.left to targetHeader.right)
        assertEquals(dp(14) to dp(786), targetCard.left to targetCard.right)

        // Against the geometry, mirrored, for every combination.
        for ((originalHidden, translationHidden) in listOf(false to false, false to true, true to false, true to true)) {
            setHidden(originalHidden, translationHidden)
            layOut(width)
            val row = expectedRow(width, originalHidden, translationHidden)
            assertSpan("the Original header", row.sourceHeader, sourceHeader, width, rtl = true)
            assertSpan("the Translation header", row.targetHeader, targetHeader, width, rtl = true)
            row.sourceCard?.let { assertSpan("the Original card", it, sourceCard, width, rtl = true) }
            row.targetCard?.let { assertSpan("the Translation card", it, targetCard, width, rtl = true) }
        }
    }

    // ── The layout's own height ─────────────────────────────────────────

    @Test
    fun `the measured height is the last shown child's bottom plus the bottom padding, and an exact height is taken top-aligned`() {
        fillWords()
        // The page's bottom padding: the floating Anki pill's clearance.
        val pad = sections.resources.getDimensionPixelSize(R.dimen.pt_anki_pill_clearance)
        assertEquals(pad, sections.paddingBottom)
        for (width in listOf(dp(412), dp(800))) {
            layOut(width)
            assertEquals("at ${width}px", wordsCard.bottom + pad, sections.measuredHeight)
            val natural = sections.measuredHeight
            val all = listOf(
                targetHeader, targetCard, sourceHeader, sourceCard, askHeader, askCard, wordsHeader, wordsCard,
            )
            val tops = all.map { it.top }

            val taller = natural + dp(500)
            layOut(width, MeasureSpec.makeMeasureSpec(taller, MeasureSpec.EXACTLY))
            assertEquals("an exact height is taken at ${width}px", taller, sections.measuredHeight)
            assertEquals(
                "the children stay where they were, top-aligned, at ${width}px",
                tops,
                all.map { it.top },
            )
        }
        // The last shown child is a header row when every card below the
        // pair is hidden too.
        setHidden(original = true, translation = true)
        wordsCard.visibility = View.GONE
        layOut(dp(800))
        assertEquals(wordsHeader.bottom + pad, sections.measuredHeight)
    }

    // ── A flush top ──────────────────────────────────────────────────────

    @Test
    fun `flush, the stacked top header starts at the top edge and the rest keep their own padding`() {
        layOut(500)
        assertEquals(Arrangement.STACKED, sections.arrangement)
        val gap = targetHeader.paddingTop
        assertTrue("the header has a top padding to give up", gap > 0)
        assertEquals("by default it is kept", 0, targetHeader.top)
        val height = sections.measuredHeight
        val sourceTop = sourceHeader.top

        sections.flushTop = true
        layOut(500)

        assertEquals("the header's content starts at the top edge", -gap, targetHeader.top)
        assertEquals("everything beneath moves up with it", sourceTop - gap, sourceHeader.top)
        assertEquals("the next header still follows the card above it", targetCard.bottom, sourceHeader.top)
        assertEquals(height - gap, sections.measuredHeight)

        sections.flushTop = false
        layOut(500)
        assertEquals(0, targetHeader.top)
        assertEquals(height, sections.measuredHeight)
    }

    @Test
    fun `flush, with the Translation header gone the next header placed is the one at the top edge`() {
        targetHeader.visibility = View.GONE
        targetCard.visibility = View.GONE
        sections.flushTop = true
        layOut(500)

        assertEquals(-sourceHeader.paddingTop, sourceHeader.top)
    }

    @Test
    fun `flush, the pair's two headers both start at the top edge`() {
        sections.flushTop = true
        layOut(700)
        assertEquals(Arrangement.PAIRED, sections.arrangement)

        assertEquals(-targetHeader.paddingTop, targetHeader.top)
        assertEquals(-sourceHeader.paddingTop, sourceHeader.top)
        assertEquals("the cards follow the header row", targetHeader.bottom, targetCard.top)
    }
}
