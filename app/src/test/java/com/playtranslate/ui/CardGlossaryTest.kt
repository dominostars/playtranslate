package com.playtranslate.ui

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Part-of-speech tokens joined as they are: a stand-in for the app's
 *  labels, which need a Context. */
private val RAW_POS: (List<String>) -> String = { it.joinToString(" · ") }


/**
 * Pins the one renderer of a card's definitions: the three layouts on exact
 * strings (a note type's CSS is never rewritten, so the panel's and the
 * cell's markup is a contract with cards already made), and the rules the
 * layouts share (a row's dictionary tag is the title, a stylesheet rides
 * only with a structured glossary that was drawn).
 */
class CardGlossaryTest {

    private val title = "Jitendex.org [2025-06-01]"
    private val structuredJson =
        """[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"cat"}]}}]"""
    private val structuredHtml =
        "<div class=\"gloss-item\"><ul class=\"gloss-sc-ul\"><li class=\"gloss-sc-li\">cat</li></ul></div>"

    private val jitendex = ImportedSenseGroup(
        source = "Jitendex",
        senses = listOf(
            ImportedSense("cat (flat)", pos = "n", scRowid = 7L),
            ImportedSense("line one\nline two", pos = ""),
        ),
        dictId = "d1",
    )
    private val meikyo = ImportedSenseGroup("明鏡", listOf(ImportedSense("家畜。")), dictId = "d2")

    private fun sense(
        defs: List<String>,
        misc: List<String> = emptyList(),
        examples: List<Example> = emptyList(),
    ) = Sense(
        targetDefinitions = defs, partsOfSpeech = listOf("noun"), tags = emptyList(),
        restrictions = emptyList(), info = emptyList(), misc = misc, examples = examples,
    )

    private val cat = sense(
        listOf("cat", "feline"), misc = listOf("col"),
        examples = listOf(Example("猫が好き。", "I like cats."), Example("猫だ。", "")),
    )
    private val kitten = sense(listOf("kitten"))

    private fun entry(imported: List<ImportedSenseGroup>, senses: List<Sense>) = DictionaryEntry(
        slug = "猫", isCommon = true, tags = emptyList(), jlpt = emptyList(),
        headwords = listOf(Headword(written = "猫", reading = "ねこ")),
        senses = senses, importedSenses = imported,
    )

    private val styled = YomitanStyledData(
        structured = mapOf(7L to structuredJson),
        dictStyles = mapOf("d1" to "li{color:red}", "d2" to "p{color:blue}"),
    )
    private val titles = mapOf("d1" to title, "d2" to "明鏡国語辞典 第三版")
    private val misc: (List<String>) -> String? = { it.joinToString(", ").ifEmpty { null } }

    private fun glossary(
        def: WordCardDefinition,
        styled: YomitanStyledData? = this.styled,
        titles: Map<String, String> = this.titles,
    ) = def.glossary(styled, titles, RAW_POS, misc)

    private val full = WordCardDefinition("the fallback", entry(listOf(jitendex, meikyo), listOf(cat, kitten)))

    // ── The panel ───────────────────────────────────────────────────────

    // Mutations: number from index (0-based); drop row 0's inline hairline;
    // name the dictionary on every row; name the built-in dictionary; write
    // an imported row's line break raw.
    @Test
    fun `the panel is the header and numbered rows, each dictionary named on its first row`() {
        assertEquals(
            "<style>.gl-sc[data-dictionary=\"$title\"] li{color:red;}</style>" +
                "<div class=\"gl-section\">Definitions</div><div class=\"gl-panel\">" +
                "<div class=\"gl-sense\" style=\"border-top:0;\"><span class=\"gl-sense-n gl-hint\">1</span>" +
                "<div class=\"gl-sense-b\"><div class=\"gl-sc\" data-dictionary=\"$title\">$structuredHtml</div>" +
                "<div class=\"gl-pos gl-hint\">Jitendex · n</div></div></div>" +
                "<div class=\"gl-sense\"><span class=\"gl-sense-n gl-hint\">2</span>" +
                "<div class=\"gl-sense-b\"><div class=\"gl-gloss\">line one<br>line two</div></div></div>" +
                "<div class=\"gl-sense\"><span class=\"gl-sense-n gl-hint\">3</span>" +
                "<div class=\"gl-sense-b\"><div class=\"gl-gloss\">家畜。</div>" +
                "<div class=\"gl-pos gl-hint\">明鏡</div></div></div>" +
                "<div class=\"gl-sense\"><span class=\"gl-sense-n gl-hint\">4</span>" +
                "<div class=\"gl-sense-b\"><div class=\"gl-gloss\">cat; feline</div>" +
                "<div class=\"gl-pos gl-hint\">noun</div><div class=\"gl-misc gl-hint\">col</div>" +
                "<div class=\"gl-ex gl-hint\">猫が好き。<div class=\"gl-ex-tr\">I like cats.</div></div>" +
                "<div class=\"gl-ex gl-hint\">猫だ。</div></div></div>" +
                "<div class=\"gl-sense\"><span class=\"gl-sense-n gl-hint\">5</span>" +
                "<div class=\"gl-sense-b\"><div class=\"gl-gloss\">kitten</div>" +
                "<div class=\"gl-pos gl-hint\">noun</div></div></div>" +
                "</div>",
            glossary(full).panelHtml(classStyler, "Definitions"),
        )
    }

    // The inline path has no stylesheet of ours, so the first row's missing
    // hairline is inline; the structured wrapper keeps its class, the one
    // hook the dictionary's scoped stylesheet has on someone else's card.
    @Test
    fun `the inline panel keeps the structured wrapper's class and the first row's inline hairline`() {
        val html = glossary(full).panelHtml(inlineStyler, "Definitions")

        assertTrue(html, html.contains("<div class=\"gl-sc\" data-dictionary=\"$title\">"))
        assertEquals(1, Regex("border-top:0;").findAll(html).count())
        assertFalse("no other class of ours on an inline card", Regex("class=\"gl-(?!sc\")").containsMatchIn(html))
    }

    // Mutations: keep the id as the tag; scope the stylesheet by id.
    @Test
    fun `a row is tagged with its dictionary's title and our id never reaches the card`() {
        val g = glossary(full)
        for (html in listOf(
            g.panelHtml(classStyler, "Definitions"), g.cellHtml(classStyler, target = true),
            g.yomitanHtml(primaryOnly = false),
        )) {
            assertFalse(html, html.contains("d1"))
            assertFalse(html, html.contains("d2"))
        }
    }

    // Mutation: leave the tag empty when the title is unknown.
    @Test
    fun `a dictionary whose title could not be read is tagged with its label`() {
        val html = glossary(full, titles = emptyMap()).panelHtml(classStyler, "Definitions")

        assertTrue(html, html.contains("<div class=\"gl-sc\" data-dictionary=\"Jitendex\">"))
        assertTrue(html, html.contains("<style>.gl-sc[data-dictionary=\"Jitendex\"] li{color:red;}</style>"))
    }

    // Mutations: draw the fallback under kept rows; keep the chrome around
    // a blank fallback.
    @Test
    fun `with no row the panel is the fallback's lines, and nothing at all when that is blank`() {
        val none = WordCardDefinition("  1. cat\n\n2. kitten & co")
        assertEquals(
            "<div class=\"gl-section\">Definitions</div><div class=\"gl-panel\">" +
                "<div class=\"gl-gloss\" style=\"padding:14px 0;\">1. cat<br>2. kitten &amp; co</div></div>",
            glossary(none).panelHtml(classStyler, "Definitions"),
        )
        assertEquals("", glossary(WordCardDefinition("  \n")).panelHtml(classStyler, "Definitions"))
        // An entry whose every row the editor removed, with no fallback text:
        // an empty field, not an empty panel.
        val emptied = WordCardDefinition("", entry(emptyList(), listOf(kitten)), removedSenses = setOf(0))
        assertEquals("", glossary(emptied).panelHtml(inlineStyler, "Definitions"))
        assertFalse(glossary(full).panelHtml(classStyler, "Definitions").contains("the fallback"))
    }

    // ── The words table's cell ──────────────────────────────────────────

    // Mutations: drop the target's size from the number or the text; drop
    // the inline-block from the structured wrapper; drop the misc indent;
    // repeat a header that did not change; number from zero.
    @Test
    fun `a target cell is headers on change and dotted rows, one step larger`() {
        assertEquals(
            "<div class=\"gl-pos-h gl-secondary\">Jitendex · n</div>" +
                "<div class=\"gl-def\"><span class=\"gl-num gl-hint\" style=\"font-size:0.9em;\">1.</span>" +
                "<div class=\"gl-sc\" style=\"display:inline-block;vertical-align:top;\" " +
                "data-dictionary=\"$title\">$structuredHtml</div></div>" +
                "<div class=\"gl-pos-h gl-secondary\">Jitendex</div>" +
                "<div class=\"gl-def\"><span class=\"gl-num gl-hint\" style=\"font-size:0.9em;\">2.</span>" +
                "<span class=\"gl-dtext\" style=\"font-size:0.9em;\">line one<br>line two</span></div>" +
                "<div class=\"gl-pos-h gl-secondary\">明鏡</div>" +
                "<div class=\"gl-def\"><span class=\"gl-num gl-hint\" style=\"font-size:0.9em;\">3.</span>" +
                "<span class=\"gl-dtext\" style=\"font-size:0.9em;\">家畜。</span></div>" +
                "<div class=\"gl-pos-h gl-secondary\">noun</div>" +
                "<div class=\"gl-def\"><span class=\"gl-num gl-hint\" style=\"font-size:0.9em;\">4.</span>" +
                "<span class=\"gl-dtext\" style=\"font-size:0.9em;\">cat; feline</span></div>" +
                "<div class=\"gl-misc gl-hint\" style=\"margin-left:25px;\">col</div>" +
                "<div class=\"gl-def\"><span class=\"gl-num gl-hint\" style=\"font-size:0.9em;\">5.</span>" +
                "<span class=\"gl-dtext\" style=\"font-size:0.9em;\">kitten</span></div>",
            glossary(full).cellHtml(classStyler, target = true),
        )
    }

    // Mutations: size a context cell; ship a stylesheet inside a cell.
    @Test
    fun `a context cell carries no size and a cell never carries a stylesheet`() {
        val html = glossary(full).cellHtml(classStyler, target = false)

        assertFalse(html, html.contains("font-size"))
        assertFalse(html, html.contains("<style>"))
        assertTrue(html, html.contains("<span class=\"gl-num gl-hint\">1.</span>"))
        assertEquals(mapOf("d1" to title), glossary(full).styledDictionaries())
    }

    // Mutations: trim or join the cell's fallback lines; drop their margin.
    @Test
    fun `a cell with no row is the fallback's lines, one block each`() {
        val g = glossary(WordCardDefinition("1. cat\n\n 2. kitten & co"))
        assertEquals(
            "<div class=\"gl-dtext gl-secondary\" style=\"font-size:0.9em;margin-top:6px;\">1. cat</div>" +
                "<div class=\"gl-dtext gl-secondary\" style=\"font-size:0.9em;margin-top:6px;\"> 2. kitten &amp; co</div>",
            g.cellHtml(classStyler, target = true),
        )
        assertEquals(
            "<div class=\"gl-dtext gl-secondary\" style=\"margin-top:6px;\">1. cat</div>" +
                "<div class=\"gl-dtext gl-secondary\" style=\"margin-top:6px;\"> 2. kitten &amp; co</div>",
            g.cellHtml(classStyler, target = false),
        )
        assertEquals("", glossary(WordCardDefinition("")).cellHtml(classStyler, target = true))
    }

    // ── Yomitan's markup ────────────────────────────────────────────────

    // Mutations: tag with the label; drop the dictionary from an item's
    // tag; write our classes into it; scope the stylesheet to .gl-sc.
    @Test
    fun `yomitan markup is one tagged list item per definition with nothing of ours in it`() {
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>" +
                "<li data-dictionary=\"$title\"><i>(n, Jitendex)</i> " +
                "<span class=\"structured-content\">$structuredHtml</span></li>" +
                "<li data-dictionary=\"$title\"><i>(Jitendex)</i> line one<br>line two</li>" +
                "<li data-dictionary=\"明鏡国語辞典 第三版\"><i>(明鏡)</i> 家畜。</li>" +
                "<li data-dictionary=\"PlayTranslate\"><i>(noun, col, PlayTranslate)</i> cat; feline" +
                "<ul><li>猫が好き。<br>I like cats.</li><li>猫だ。</li></ul></li>" +
                "<li data-dictionary=\"PlayTranslate\"><i>(noun, PlayTranslate)</i> kitten</li>" +
                "</ol></div>" +
                "<style>.yomitan-glossary [data-dictionary=\"$title\"] li{color:red;}</style>",
            glossary(full).yomitanHtml(primaryOnly = false),
        )
    }

    // Mutations: keep one item only; keep every imported dictionary; keep
    // the built-in rows after an imported primary.
    @Test
    fun `the primary is every item of the first dictionary and nothing else`() {
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>" +
                "<li data-dictionary=\"$title\"><i>(n, Jitendex)</i> " +
                "<span class=\"structured-content\">$structuredHtml</span></li>" +
                "<li data-dictionary=\"$title\"><i>(Jitendex)</i> line one<br>line two</li>" +
                "</ol></div>" +
                "<style>.yomitan-glossary [data-dictionary=\"$title\"] li{color:red;}</style>",
            glossary(full).yomitanHtml(primaryOnly = true),
        )
    }

    // The primary is the first DICTIONARY, by identity. The tag is display
    // text and two dictionaries can share it: an import titled like the
    // built-in tag (the importer only compares imports with each other),
    // or two labels standing in for titles that could not be read.
    // Mutations: select the primary by its tag; name a dictionary in the
    // panel wherever the tag or the label changes rather than the dictionary.
    @Test
    fun `two dictionaries that share a tag are still two dictionaries`() {
        val named = glossary(full, titles = mapOf("d1" to "PlayTranslate", "d2" to "明鏡国語辞典 第三版"))
        val primary = named.yomitanHtml(primaryOnly = true)
        assertEquals(2, Regex("<li data-dictionary=\"PlayTranslate\">").findAll(primary).count())
        assertFalse("the built-in rows are another dictionary", primary.contains("cat; feline"))
        assertFalse(primary, primary.contains("kitten"))

        val twin = ImportedSenseGroup("Jitendex", listOf(ImportedSense("the twin row")), dictId = "d9")
        val twins = glossary(WordCardDefinition("fb", entry(listOf(jitendex, twin), emptyList())), titles = emptyMap())
        assertFalse(twins.yomitanHtml(primaryOnly = true).contains("the twin row"))
        assertTrue(twins.yomitanHtml(primaryOnly = false).contains("the twin row"))
        // Each is named on its own first row: "Jitendex · n", then "Jitendex".
        assertEquals(2, Regex(">Jitendex( · n)?</div>").findAll(twins.panelHtml(classStyler, "D")).count())
    }

    // The built-in dictionary is last in the order, so it is the primary
    // exactly when no imported dictionary has the word: both of a Lapis
    // card's fields then hold the same items.
    @Test
    fun `with no imported dictionary the built-in one is the primary`() {
        val g = glossary(WordCardDefinition("fb", entry(emptyList(), listOf(cat, kitten))))

        assertEquals(g.yomitanHtml(primaryOnly = false), g.yomitanHtml(primaryOnly = true))
        assertEquals(2, Regex("<li data-dictionary=\"PlayTranslate\">").findAll(g.yomitanHtml(true)).count())
        assertFalse(g.yomitanHtml(true).contains("<style>"))
    }

    // Mutations: tag the fallback item; emit an empty list for a blank one.
    @Test
    fun `with no row yomitan markup is one untagged built-in item, and nothing when that is blank`() {
        assertEquals(
            "<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>" +
                "<li data-dictionary=\"PlayTranslate\">1. cat<br>2. kitten</li></ol></div>",
            glossary(WordCardDefinition("1. cat\n2. kitten")).yomitanHtml(primaryOnly = true),
        )
        assertEquals("", glossary(WordCardDefinition("")).yomitanHtml(primaryOnly = false))
    }

    // ── The stylesheet rule ─────────────────────────────────────────────

    // Mutations: ship a stylesheet for a dictionary whose row id is merely
    // in the payload; ship one for a dictionary drawn flat.
    @Test
    fun `a stylesheet rides only with a structured glossary that was drawn`() {
        // d2's row is flat; d1's glossary does not parse, so it is drawn
        // flat too.
        val broken = YomitanStyledData(mapOf(7L to "{not json"), styled.dictStyles)
        val g = glossary(full, styled = broken)

        assertTrue(g.styledDictionaries().isEmpty())
        for (html in listOf(g.panelHtml(classStyler, "D"), g.yomitanHtml(false))) {
            assertFalse(html, html.contains("<style>"))
            assertTrue("the flat text stands in", html.contains("cat (flat)"))
        }
        // No payload at all: flat throughout.
        assertFalse(glossary(full, styled = null).panelHtml(classStyler, "D").contains("gl-sc"))
    }

    // Mutation: write the title into the selector or the attribute raw.
    @Test
    fun `a title is escaped for the attribute and for the selector`() {
        val odd = "A \"b\" \\ <c>\nd"
        val g = glossary(full, titles = mapOf("d1" to odd))

        val html = g.panelHtml(classStyler, "D")
        assertTrue(html, html.contains("data-dictionary=\"A &quot;b&quot; \\ &lt;c&gt;\nd\""))
        assertTrue(html, html.contains("<style>.gl-sc[data-dictionary=\"A \\\"b\\\" \\\\ <c>\\a d\"] li{color:red;}</style>"))
        assertTrue(
            g.yomitanHtml(false),
            g.yomitanHtml(false).contains(".yomitan-glossary [data-dictionary=\"A \\\"b\\\" \\\\ <c>\\a d\"] li"),
        )
    }

    // A title that carries the raw-text terminator cannot end the block.
    @Test
    fun `a title cannot close the style element`() {
        val html = glossary(full, titles = mapOf("d1" to "x</style><script>")).panelHtml(classStyler, "D")

        assertEquals(1, Regex("</style", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html, html.contains("[data-dictionary=\"x<\\/style><script>\"]"))
    }

    // ── The adapter's rules the layouts lean on ─────────────────────────

    // Mutations: number a sense with no definition; localize in the adapter
    // regardless of the caller's localizer.
    @Test
    fun `a sense with no definition is no row, and part of speech goes through the caller's localizer`() {
        val blank = sense(emptyList())
        val def = WordCardDefinition("fb", entry(emptyList(), listOf(blank, kitten)))

        val raw = def.glossary(null, emptyMap(), RAW_POS, misc)
        assertEquals(listOf("kitten"), raw.items.map { it.text })
        assertEquals("noun", raw.items.single().pos)
        val localized = def.glossary(null, emptyMap(), { "L(${it.joinToString("/")})" }, misc)
        assertEquals("L(noun)", localized.items.single().pos)
    }

    // ── Titles: the read's two rules ────────────────────────────────────

    // Mutations: call the read with no id to look up; let a failure out.
    @Test
    fun `titles are not read when no definition has a dictionary id, and a failed read is no titles`() {
        runBlocking {
            assertEquals(
                emptyMap<String, String>(),
                containedTitles("ja", listOf("", "")) { fail("no id, no read"); emptyMap() },
            )
            assertEquals(
                emptyMap<String, String>(),
                containedTitles("ja", listOf("d1")) { throw IllegalStateException("database is locked") },
            )
            assertEquals(
                mapOf("d1" to title),
                containedTitles("ja", listOf("d1", "")) { mapOf("d1" to title, "d9" to "Other") },
            )
        }
    }

    // Mutation: swallow the cancellation with the other failures.
    @Test
    fun `a cancelled title read stays cancelled`() {
        try {
            runBlocking { containedTitles("ja", listOf("d1")) { throw CancellationException("gone") } }
            fail("the cancellation must propagate")
        } catch (expected: CancellationException) {
            // The send was cancelled; it must not go on with empty titles.
        }
    }
}
