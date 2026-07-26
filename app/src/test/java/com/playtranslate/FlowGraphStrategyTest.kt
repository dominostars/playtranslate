package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.FlowGraphStrategy
import com.playtranslate.ocr.core.GroupingContext
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.ProposedGroup
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins for [FlowGraphStrategy] — the behaviours the corpus paid for.
 *
 * Geometry is device-verbatim where the seed is named: these are the shapes
 * that produced the measurements in the class kdoc, so a pin failing means the
 * measurement no longer describes the code.
 */
@RunWith(RobolectricTestRunner::class)
class FlowGraphStrategyTest {

    private fun ctx(lang: String = "en", screenWidth: Float = 1920f, rtl: Boolean = false) =
        GroupingContext(
            sourceLang = lang,
            screenshotWidthInRegionSpace = screenWidth,
            rtl = rtl,
            spacedScript = lang !in setOf("ja", "zh", "th"),
            logDecisions = false,
        )

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int,
                     orientation: TextOrientation = TextOrientation.HORIZONTAL): RecognizedRegion {
        val box = OcrBox.upright(Rect(l, t, r, b))
        return RecognizedRegion(
            text = text, box = box, orientation = orientation, confidence = 0.9f,
            lines = listOf(RecognizedLine(text, box, orientation)),
            origin = RegionOrigin.LINE,
        )
    }

    /** Group index per input region, so assertions read as "same group or not". */
    private fun groupsOf(
        regions: List<RecognizedRegion>,
        ctx: GroupingContext,
        strategy: FlowGraphStrategy = FlowGraphStrategy(),
    ): Map<String, Int> {
        val out = HashMap<String, Int>()
        strategy.group(regions, ctx).forEachIndexed { gi, g: ProposedGroup ->
            g.regions.forEach { out[it.text] = gi }
        }
        return out
    }

    // ── the architectural claim: adjacency, not sequence ─────────────────────

    @Test
    fun otherPanelInterleaved_doesNotBreakTheParagraph() {
        // A three-line paragraph on the right, with a left-hand panel whose
        // lines interleave in top-sort order. The production walk compares
        // against a globally sorted list; a nearest-neighbour graph does not
        // see the other panel at all because it is not the paragraph's
        // neighbour. Shape taken from Screenshot_20260721-211150.
        val regions = listOf(
            line("Fleeting moments from any point", 929, 842, 1632, 872),
            line("Consumables", 180, 852, 475, 889),
            line("lifetime, condensed into", 890, 886, 1670, 916),
            line("the air.", 1235, 932, 1324, 955),
            line("Possibilities are transient", 899, 976, 1661, 1006),
            line("Egoshards", 212, 988, 441, 1038),
            line("uncertainty unless seized.", 1104, 1021, 1456, 1050),
        )
        val g = groupsOf(regions, ctx())
        val body = listOf(
            "Fleeting moments from any point", "lifetime, condensed into", "the air.",
            "Possibilities are transient", "uncertainty unless seized.",
        )
        assertEquals(
            "all five paragraph lines share a group across the interleaved panel",
            1, body.map { g[it] }.toSet().size,
        )
        assertTrue(
            "the other panel must not join the paragraph",
            g["Consumables"] != g["Fleeting moments from any point"],
        )
    }

    // ── rhythm ───────────────────────────────────────────────────────────────

    @Test
    fun headingAtTheBodysOwnPitch_stillSplits() {
        // nhk-news-easy: the title sits 56px above a body whose internal pitch
        // is 56.5px, so no gap rule can separate them. The short-opener rule is
        // what carries this one.
        val regions = listOf(
            line("NHKやさしいことばニュース", 120, 378, 1108, 422),
            line("ハHやさしいことばニュース」は、日本に住んでいる外国人の皆さんや", 144, 437, 1777, 475),
            line("けやさしい日本語でニュースを伝えるサイトです。", 123, 494, 937, 531),
        )
        val g = groupsOf(regions, ctx("ja"))
        assertTrue(
            "title must not join the body it sits at the same pitch as",
            g["NHKやさしいことばニュース"] != g["けやさしい日本語でニュースを伝えるサイトです。"],
        )
    }

    @Test
    fun headingRuleDoesNotCascadeDownAGrowingBlock() {
        // Rows that grow in length: without the cascade guard every row in turn
        // is "short against the block below" and the block shreds row by row.
        // This is what took the corpus's Arabic seed from SPLIT to PASS.
        val regions = listOf(
            line("Short first", 100, 100, 400, 130),
            line("A somewhat longer second line", 100, 140, 800, 170),
            line("An even longer third line here now", 100, 180, 1000, 210),
            line("And the longest fourth line of them all", 100, 220, 1200, 250),
        )
        val g = groupsOf(regions, ctx())
        assertEquals(
            "rows 2-4 must not shred once the first cut has been made",
            g["A somewhat longer second line"], g["An even longer third line here now"],
        )
        assertEquals(
            g["An even longer third line here now"], g["And the longest fourth line of them all"],
        )
    }

    // ── text evidence ────────────────────────────────────────────────────────

    @Test
    fun quotedOptions_eachRowClosed_explodeIntoChoices() {
        // persona-4-golden-choices-4. Every row opens AND closes its quotation,
        // which a wrapped quoted paragraph never does.
        val regions = listOf(
            line("\"A sOuvenir.\"", 300, 400, 700, 436),
            line("\"A bear-shaped backpack,\"", 300, 452, 820, 488),
            line("\"I made it today in class,\"", 300, 504, 850, 540),
        )
        val g = groupsOf(regions, ctx())
        assertEquals("each quoted option is its own unit", 3, g.values.toSet().size)
    }

    @Test
    fun wrappedQuotedParagraph_marksOnlyItsEnds_staysMerged() {
        // The counter-case that makes the rule safe: the quote opens on the
        // first row and closes on the last, so no row is self-closed.
        val regions = listOf(
            line("\"This is a long quoted line that", 300, 400, 900, 436),
            line("wraps across rows and only closes", 300, 452, 900, 488),
            line("its quotation right here.\"", 300, 504, 800, 540),
        )
        val g = groupsOf(regions, ctx())
        assertEquals("wrapped quoted prose must stay one unit", 1, g.values.toSet().size)
    }

    @Test
    fun continuationCue_protectsAGenerousGap() {
        // A trailing comma is the corpus's strongest merge evidence:
        // P(boundary | cue) = 0.02 inside the ambiguous pitch band.
        val regions = listOf(
            line("旅先で、宿屋がない時や、", 545, 770, 1131, 827),
            line("ビンボーな時には、", 573, 850, 979, 907),
        )
        val g = groupsOf(regions, ctx("ja"))
        assertEquals(
            "a mid-thought line must hold its successor",
            g["旅先で、宿屋がない時や、"], g["ビンボーな時には、"],
        )
    }

    // ── shell parity ─────────────────────────────────────────────────────────

    @Test
    fun sourceScriptFilter_runsOnTheBlock_notTheExplodedRows() {
        // A numeric HUD row inside an otherwise source-language menu survives,
        // because the filter asks the question of the BLOCK before any list
        // split — the same point DefaultGroupingStrategy applies it. Filtering
        // per exploded row instead dropped 11 such lines across 8 cells.
        val regions = listOf(
            line("Items", 100, 100, 260, 140),
            line("107/113", 100, 180, 260, 220),
            line("Equipment", 100, 260, 300, 300),
        )
        val g = groupsOf(regions, ctx())
        assertTrue("the numeric row must not be dropped", g.containsKey("107/113"))
    }

    @Test
    fun groupsPartitionTheInput_nothingDroppedOrDuplicated() {
        val regions = listOf(
            line("Alpha beta gamma", 100, 100, 700, 140),
            line("delta epsilon zeta", 100, 150, 700, 190),
            line("Unrelated", 1400, 900, 1700, 940),
        )
        val emitted = FlowGraphStrategy().group(regions, ctx()).flatMap { it.regions }
        assertEquals("every region emitted exactly once", regions.size, emitted.size)
        assertEquals(regions.toSet(), emitted.toSet())
    }

    @Test
    fun emptyInput_isEmptyOutput() {
        assertTrue(FlowGraphStrategy().group(emptyList(), ctx()).isEmpty())
    }

    // ── RTL ──────────────────────────────────────────────────────────────────

    @Test
    fun rtlBlock_alignedOnItsRightMargin_staysMerged() {
        // Arabic wraps toward the LEFT, so the rows share a RIGHT start edge and
        // ragged left ends. Judged in start/end terms this is an ordinary block;
        // judged in left/right it is not.
        val regions = listOf(
            line("النظام الانتخابي والاستفتاءات", 900, 400, 1500, 440),
            line("الانتخابات غير المباشرة", 1050, 450, 1500, 490),
            line("الأنظمة الانتخابية", 1180, 500, 1500, 540),
        )
        val g = groupsOf(regions, ctx(lang = "ar", rtl = true))
        assertEquals("RTL wrapped text must stay one unit", 1, g.values.toSet().size)
    }

    @Test
    fun defaultConstruction_isTheShippingConfiguration() {
        // The kdoc's numbers describe FlowGraphStrategy() with no arguments.
        // If a default moves, the measurements stop describing the code.
        val s = FlowGraphStrategy()
        assertEquals("flowgraph", s.name)
        // A vertical pair far off any rhythm must still merge, which is only
        // true while rhythmVertical is OFF by default.
        val cols = listOf(
            line("いやいや", 587, 730, 608, 900, TextOrientation.VERTICAL),
            line("ウチが住んでるのに", 547, 732, 570, 948, TextOrientation.VERTICAL),
            line("田舎なわけないじゃない?", 506, 727, 531, 960, TextOrientation.VERTICAL),
        )
        val g = groupsOf(cols, ctx("ja"), s)
        assertEquals("vertical columns of one bubble stay together", 1, g.values.toSet().size)
    }

    @Test
    fun rubyColumn_doesNotStealTheLinkFromABodyLine() {
        // Half-size furigana between two body columns must neither be chosen as
        // a neighbour nor fence the real neighbour off. The gate is 1.65.
        val regions = listOf(
            line("ドカンにのってからAを押せば", 568, 829, 1057, 863),
            line("なかはい", 767, 870, 871, 887),
            line("そのドカンの中に入れます。", 561, 889, 986, 923),
        )
        val g = groupsOf(regions, ctx("ja"))
        assertEquals(
            "body lines link across the ruby between them",
            g["ドカンにのってからAを押せば"], g["そのドカンの中に入れます。"],
        )
        assertFalse(
            "the ruby is not part of the body block",
            g["なかはい"] == g["ドカンにのってからAを押せば"],
        )
    }
}
