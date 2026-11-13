package com.playtranslate.ui

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part-of-speech tokens joined as they are: a stand-in for the app's
 *  labels, which need a Context. */
private val RAW_POS: (List<String>) -> String = { it.joinToString(" · ") }


/**
 * Pins the one-producer contract of the word card's Definition field: the
 * review sheet and the hold-to-create one-tap both describe the field as a
 * [WordCardDefinition] and the same builder renders it — styled imported
 * groups included — so a one-tap card can never silently fall back to the
 * flattened text the sheet stopped shipping.
 */
class WordCardDefinitionTest {

    private val header = "Definitions"
    private val noMisc: (List<String>) -> String? = { null }

    /** The panel of this card's rows: the Definition field the word card
     *  and the `DEFINITION` source ship. */
    private fun WordCardDefinition.panelHtml(
        styler: HtmlStyler,
        styled: YomitanStyledData?,
        header: String,
        renderMisc: (List<String>) -> String?,
        titles: Map<String, String> = emptyMap(),
    ): String = glossary(styled, titles, RAW_POS, renderMisc).panelHtml(styler, header)

    private fun entry(
        imported: List<ImportedSenseGroup> = emptyList(),
        senses: List<Sense> = emptyList(),
    ) = DictionaryEntry(
        slug = "猫",
        isCommon = true,
        tags = emptyList(),
        jlpt = emptyList(),
        headwords = listOf(Headword(written = "猫", reading = "ねこ")),
        senses = senses,
        importedSenses = imported,
    )

    private fun sense(vararg defs: String, examples: List<Example> = emptyList()) = Sense(
        targetDefinitions = defs.toList(),
        partsOfSpeech = listOf("noun"),
        tags = emptyList(),
        restrictions = emptyList(),
        info = emptyList(),
        examples = examples,
    )

    private val jitendex = ImportedSenseGroup(
        source = "Jitendex",
        senses = listOf(ImportedSense("cat (flat)", pos = "n", scRowid = 7L)),
        dictId = "d1",
    )
    private val structuredCat =
        """[{"type":"structured-content","content":{"tag":"ul","content":[{"tag":"li","content":"cat"}]}}]"""

    private fun styled(css: Map<String, String> = emptyMap()) = YomitanStyledData(
        structured = mapOf(7L to structuredCat),
        dictStyles = css,
    )

    @Test
    fun `fetched glossary renders the imported sense as a gl-sc block with scoped css`() {
        val def = WordCardDefinition(
            fallback = "1. cat (flat)\n2. a cat",
            entry = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"))),
        )
        assertEquals(listOf(jitendex), def.importedGroups)

        val html = def.panelHtml(
            classStyler, styled(mapOf("d1" to "li{color:red}")), header, noMisc,
            titles = mapOf("d1" to "Jitendex.org [2025]"),
        )

        assertTrue(html, html.contains("gl-sc"))
        // The tag and the stylesheet's scope are the dictionary's title,
        // never our id.
        assertTrue(html, html.contains("data-dictionary=\"Jitendex.org [2025]\""))
        assertTrue(html, html.contains("<li"))
        assertFalse("structured sense must replace its flat text", html.contains("cat (flat)"))
        assertTrue("pack senses still render", html.contains("a cat"))
        assertTrue(html, html.contains("<style>"))
        assertTrue(html, html.contains(".gl-sc[data-dictionary=\"Jitendex.org [2025]\"] li"))
        assertFalse(html, html.contains("d1"))
        assertTrue("dictionary label rides the imported row", html.contains(">Jitendex · n</div>"))
    }

    @Test
    fun `no styled payload keeps the imported sense flat`() {
        val def = WordCardDefinition(
            fallback = "cat (flat)",
            entry = entry(imported = listOf(jitendex)),
        )

        val html = def.panelHtml(classStyler, null, header, noMisc)

        assertTrue(html, html.contains("cat (flat)"))
        assertFalse(html, html.contains("gl-sc"))
        assertFalse(html, html.contains("<style>"))
    }

    @Test
    fun `no entry renders the flat fallback chrome`() {
        val def = WordCardDefinition(fallback = "1. cat\n2. kitten")

        assertEquals(
            "<div class=\"gl-section\">Definitions</div><div class=\"gl-panel\">" +
                "<div class=\"gl-gloss\" style=\"padding:14px 0;\">1. cat<br>2. kitten</div></div>",
            def.panelHtml(classStyler, null, header, noMisc),
        )
        assertTrue(def.importedGroups.isEmpty())
    }

    @Test
    fun `one-tap defaults render exactly what an uncurated sheet sends`() {
        val e = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"), sense("kitten")))
        val sheet = WordCardDefinition(
            fallback = "fallback",
            entry = e,
            entries = listOf(e),
            defResult = null,
            targetLang = "en",
            removedSenses = emptySet(),
            removedImported = emptySet(),
            removedExamples = emptySet(),
            exampleTranslations = emptyMap(),
        )
        val oneTap = WordCardDefinition(fallback = "fallback", entry = e, targetLang = "en")

        for (styler in listOf(classStyler, inlineStyler)) {
            assertEquals(
                sheet.panelHtml(styler, styled(mapOf("d1" to "li{color:red}")), header, noMisc),
                oneTap.panelHtml(styler, styled(mapOf("d1" to "li{color:red}")), header, noMisc),
            )
        }
    }

    @Test
    fun `curation drops removed senses and applies editor example translations`() {
        val def = WordCardDefinition(
            fallback = "fallback",
            entry = entry(
                senses = listOf(
                    sense("a cat"),
                    sense("kitten", examples = listOf(Example("子猫がいる", "there is a kitten"))),
                ),
            ),
            removedSenses = setOf(0),
            exampleTranslations = mapOf((1 to 0) to "il y a un chaton"),
        )

        val html = def.panelHtml(classStyler, null, header, noMisc)

        assertFalse(html, html.contains("a cat"))
        assertTrue(html, html.contains("kitten"))
        assertTrue(html, html.contains("il y a un chaton"))
        assertFalse(html, html.contains("there is a kitten"))
        assertTrue("surviving sense numbers from 1", html.contains(">1</span>"))
        assertFalse(html, html.contains(">2</span>"))
    }

    // An example the editor removed is named by its sense's flat index and
    // its own place among that sense's examples, before any was removed;
    // the editor's translation of a kept one is named the same way.
    // Mutations: keep a removed example; index the examples after removal,
    // so the translation of example 2 lands on what is now example 1.
    @Test
    fun `an example removed in the editor stays off the card and the others keep their own translations`() {
        val def = WordCardDefinition(
            fallback = "fallback",
            entry = entry(
                senses = listOf(
                    sense("a cat"),
                    sense(
                        "kitten",
                        examples = listOf(
                            Example("一つ目", "the first"),
                            Example("二つ目", "the second"),
                            Example("三つ目", "the third"),
                        ),
                    ),
                ),
            ),
            removedExamples = setOf(1 to 1),
            exampleTranslations = mapOf((1 to 2) to "la troisième"),
        )

        val row = def.glossary(null, emptyMap(), RAW_POS, noMisc).items.last()

        assertEquals(
            listOf(Example("一つ目", "the first"), Example("三つ目", "la troisième")),
            row.examples,
        )
    }

    // The target-language rows carry their own examples and are curated
    // by their place in sense order.
    // Mutation: keep a removed example of a target-language row.
    @Test
    fun `a target-language row drops the example removed from it`() {
        val e = entry(senses = listOf(sense("a cat")))
        val native = com.playtranslate.language.DefinitionResult.Native(
            response = com.playtranslate.model.DictionaryResponse(listOf(e)),
            targetSenses = listOf(
                com.playtranslate.language.TargetSense(
                    0, listOf("nom"), listOf("chat"), "src",
                    examples = listOf(Example("Le chat dort.", "The cat sleeps."), Example("Un chat.", "A cat.")),
                ),
            ),
            source = "src",
        )
        val def = WordCardDefinition(
            fallback = "fallback", entry = e, defResult = native, targetLang = "fr",
            removedExamples = setOf(0 to 0),
        )

        val row = def.glossary(null, emptyMap(), RAW_POS, noMisc).items.single()

        assertEquals("chat", row.text)
        assertEquals(listOf(Example("Un chat.", "A cat.")), row.examples)
    }

    @Test
    fun `every sense curated away falls back to the flat text unless imported rows rendered`() {
        val bare = WordCardDefinition(
            fallback = "the fallback",
            entry = entry(senses = listOf(sense("a cat"))),
            removedSenses = setOf(0),
        )
        assertTrue(bare.panelHtml(classStyler, null, header, noMisc).contains("the fallback"))

        val withImported = WordCardDefinition(
            fallback = "the fallback",
            entry = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"))),
            removedSenses = setOf(0),
        )
        val html = withImported.panelHtml(classStyler, null, header, noMisc)
        assertTrue(html, html.contains("cat (flat)"))
        assertFalse("imported rows already carry the fallback's lines", html.contains("the fallback"))
    }

    // ─── removedImported: imported rows curated away ────────────────────
    // The sheet removes an imported row by its flat index across the
    // entry's imported senses, in group order. importedGroups is the one
    // retained view every rule reads: the rows the panel renders, the
    // dictionaries whose CSS it inlines, the fallback's gate, and the
    // groups the pipeline fetches glossaries for.

    private val groupG = ImportedSenseGroup(
        source = "G",
        senses = listOf(ImportedSense("alpha"), ImportedSense("beta")),
        dictId = "g",
    )
    private val groupH = ImportedSenseGroup(
        source = "H",
        senses = listOf(ImportedSense("gamma")),
        dictId = "h",
    )

    // Mutation: index removedImported per group (defIdx), so 2 matches no row and gamma stays.
    @Test
    fun `a removed imported row is skipped by its flat index across groups`() {
        val def = WordCardDefinition(
            fallback = "fallback",
            entry = entry(imported = listOf(groupG, groupH)),
            removedImported = setOf(2),
        )

        val html = def.panelHtml(classStyler, null, header, noMisc)

        assertTrue(html, html.contains("alpha"))
        assertTrue(html, html.contains("beta"))
        assertFalse(html, html.contains("gamma"))
    }

    // Mutation: key the dictionary label on the row's original defIdx == 0, so removing that row drops the label.
    @Test
    fun `the dictionary label moves to the group's first kept row`() {
        val twoSenses = ImportedSenseGroup(
            source = "Jitendex",
            senses = listOf(ImportedSense("cat (first)", pos = "n"), ImportedSense("feline (second)", pos = "n")),
            dictId = "d1",
        )
        val def = WordCardDefinition(
            fallback = "fallback",
            entry = entry(imported = listOf(twoSenses)),
            removedImported = setOf(0),
        )

        val html = def.panelHtml(classStyler, null, header, noMisc)

        assertFalse(html, html.contains("cat (first)"))
        assertTrue(html, html.contains("feline (second)"))
        assertTrue("the kept row carries the dictionary label", html.contains("Jitendex"))
    }

    // Mutation: compute the inlined-CSS list from entry.importedSenses, so d1's scoped block ships.
    @Test
    fun `a dictionary with every row removed ships no scoped css`() {
        val daijirin = ImportedSenseGroup(
            source = "Daijirin",
            senses = listOf(ImportedSense("cat (d2 flat)", scRowid = 8L)),
            dictId = "d2",
        )
        val def = WordCardDefinition(
            fallback = "fallback",
            entry = entry(imported = listOf(jitendex, daijirin)),
            removedImported = setOf(0),
        )
        val st = YomitanStyledData(
            structured = mapOf(7L to structuredCat, 8L to structuredCat),
            dictStyles = mapOf("d1" to "li{color:red}", "d2" to "li{color:blue}"),
        )

        val html = def.panelHtml(
            classStyler, st, header, noMisc,
            titles = mapOf("d1" to "Jitendex.org", "d2" to "大辞林"),
        )

        assertTrue(html, html.contains("data-dictionary=\"大辞林\""))
        assertTrue(html, html.contains(".gl-sc[data-dictionary=\"大辞林\"] li"))
        assertFalse(
            "neither a gl-sc block nor a style selector names the removed dictionary",
            html.contains("data-dictionary=\"Jitendex.org\""),
        )
    }

    // Mutation: compute hasImportedRows from entry.importedSenses, so the removed row still counts and the card renders empty.
    @Test
    fun `every imported and pack row curated away falls back to the flat text`() {
        // The other half (an imported row kept, so no fallback) is pinned by
        // `every sense curated away falls back to the flat text unless imported rows rendered`.
        val def = WordCardDefinition(
            fallback = "the fallback",
            entry = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"))),
            removedSenses = setOf(0),
            removedImported = setOf(0),
        )

        val html = def.panelHtml(classStyler, null, header, noMisc)

        assertTrue(html, html.contains("the fallback"))
        assertFalse(html, html.contains("cat (flat)"))
    }

    // Mutation: leave the importedGroups getter as entry?.importedSenses.orEmpty(), so removed rows stay listed.
    @Test
    fun `importedGroups is the retained list and the entry's own list when nothing is removed`() {
        val e = entry(imported = listOf(groupG, groupH))

        val curated = WordCardDefinition(fallback = "f", entry = e, removedImported = setOf(0, 2))
        assertEquals(
            listOf(groupG.copy(senses = listOf(ImportedSense("beta")))),
            curated.importedGroups,
        )

        val uncurated = WordCardDefinition(fallback = "f", entry = e)
        assertSame(e.importedSenses, uncurated.importedGroups)
    }

    // ─── fromSenses: the sentence card's shape ───────────────────────────
    // The sentence pipeline holds only flattened SenseDisplay rows for the
    // highlighted word. Described through fromSenses, they must render the
    // panel an uncurated word card sends for the same dictionary state —
    // that parity is what lets a DEFINITION-mapped field look the same in
    // both send modes (issue #31).

    private val importedRow = SenseDisplay(
        pos = listOf("Jitendex · n"), definition = "cat (flat)", misc = emptyList(),
        imported = true, scRowid = 7L, dictId = "d1",
    )

    @Test
    fun `fromSenses renders the transported rows exactly as the entry-driven word card`() {
        val e = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"), sense("kitten")))
        val rows = listOf(
            importedRow,
            SenseDisplay(pos = listOf("noun"), definition = "a cat", misc = emptyList()),
            SenseDisplay(pos = listOf("noun"), definition = "kitten", misc = emptyList()),
        )
        val fromEntry = WordCardDefinition(fallback = "fallback", entry = e)
        val fromRows = WordCardDefinition.fromSenses("猫", rows, fallback = "fallback")

        assertEquals(fromEntry.importedGroups, fromRows.importedGroups)
        for (styler in listOf(classStyler, inlineStyler)) {
            for (st in listOf(null, styled(mapOf("d1" to "li{color:red}")))) {
                assertEquals(
                    fromEntry.panelHtml(styler, st, header, noMisc),
                    fromRows.panelHtml(styler, st, header, noMisc),
                )
            }
        }
    }

    @Test
    fun `fromSenses skips a blank pack row like an entry sense with no definitions`() {
        val e = entry(senses = listOf(sense("a cat")))
        val rows = listOf(
            SenseDisplay(pos = listOf("noun"), definition = "a cat", misc = emptyList()),
            SenseDisplay(pos = listOf("noun"), definition = "", misc = emptyList()),
        )
        assertEquals(
            WordCardDefinition(fallback = "f", entry = e).panelHtml(inlineStyler, null, header, noMisc),
            WordCardDefinition.fromSenses("猫", rows, fallback = "f").panelHtml(inlineStyler, null, header, noMisc),
        )
    }

    // Mutation: open a group only when the dictionary id differs from the
    // last one, so a first row with no id never opens one and its label is "".
    @Test
    fun `a leading run of rows with no dictionary id keeps its label`() {
        val rows = listOf(
            SenseDisplay(pos = listOf("Legacy · x"), definition = "legacy def", misc = emptyList(), imported = true),
            importedRow,
        )

        val groups = importedGroupsFromSenses(rows)
        assertEquals(listOf("Legacy", "Jitendex"), groups.map { it.source })
        assertEquals(listOf("", "d1"), groups.map { it.dictId })
        val html = WordCardDefinition.fromSenses("猫", rows, fallback = "f").panelHtml(classStyler, null, header, noMisc)
        assertTrue(html, html.contains(">Legacy · x</div>"))
    }

    @Test
    fun `fromSenses with no rows is the flat fallback description`() {
        val def = WordCardDefinition.fromSenses("猫", emptyList(), fallback = "1. cat\n2. kitten")

        assertTrue(def.importedGroups.isEmpty())
        assertEquals(
            WordCardDefinition(fallback = "1. cat\n2. kitten").panelHtml(inlineStyler, null, header, noMisc),
            def.panelHtml(inlineStyler, null, header, noMisc),
        )
    }

}
