package com.playtranslate.ui

import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Example
import com.playtranslate.model.Headword
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        sourceLanguage = "ja",
    )

    @Test
    fun `fetched glossary renders the imported sense as a gl-sc block with scoped css`() {
        val def = WordCardDefinition(
            fallback = "1. cat (flat)\n2. a cat",
            entry = entry(imported = listOf(jitendex), senses = listOf(sense("a cat"))),
        )
        assertEquals(listOf(jitendex), def.importedGroups)

        val html = def.panelHtml(classStyler, styled(mapOf("d1" to "li{color:red}")), header, noMisc)

        assertTrue(html, html.contains("gl-sc"))
        assertTrue(html, html.contains("data-dictionary=\"d1\""))
        assertTrue(html, html.contains("<li"))
        assertFalse("structured sense must replace its flat text", html.contains("cat (flat)"))
        assertTrue("pack senses still render", html.contains("a cat"))
        assertTrue(html, html.contains("<style>"))
        assertTrue(html, html.contains(".gl-sc[data-dictionary=\"d1\"] li"))
        assertTrue("dictionary label rides the imported row", html.contains("Jitendex"))
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
            WordAnkiHtmlBuilder.wrapFlatDefinitionHtml("1. cat\n2. kitten", classStyler, header),
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
}
