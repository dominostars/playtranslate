package com.playtranslate.ui

import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinitionsDocumentTest {

    private val structuredGlossary =
        """[{"type":"structured-content","content":{"tag":"span","content":"styled cat"}}]"""

    private fun data(
        groups: List<ImportedSenseGroup> = emptyList(),
        senses: List<SenseDisplay> = emptyList(),
    ) = WordDefinitionData(
        word = "猫",
        reading = "ねこ",
        senses = senses,
        freqScore = 0,
        isCommon = true,
        importedGroups = groups,
    )

    private fun content(
        d: WordDefinitionData,
        structured: Map<Long, String> = emptyMap(),
    ) = DefinitionsDocument.contentHtml(d, structured, localizePos = { it.joinToString("/") })

    @Test
    fun `imported groups render in sections scoped by dict id`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Jitendex", listOf(ImportedSense("cat", scRowid = 7L)), dictId = "abc",
                    ),
                ),
            ),
            structured = mapOf(7L to structuredGlossary),
        )
        assertTrue(html.contains("<section class=\"dict-group\" data-dictionary=\"abc\">"))
        assertTrue(html.contains("styled cat"))
    }

    @Test
    fun `senses without structured rows fall back to flat text`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict",
                        listOf(ImportedSense("flat def", scRowid = null)),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertTrue(html.contains("flat def"))
    }

    @Test
    fun `flattened imported rows are skipped - the groups are those rows`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup("Dict", listOf(ImportedSense("real")), dictId = "d"),
                ),
                senses = listOf(
                    SenseDisplay(listOf("Dict · n"), "real", emptyList(), imported = true),
                    SenseDisplay(listOf("noun"), "pack def", emptyList()),
                ),
            ),
        )
        assertEquals(1, Regex(">real<").findAll(html).count())
        assertTrue(html.contains("pack def"))
    }

    @Test
    fun `consecutive same-header senses emit the header once`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict",
                        listOf(ImportedSense("one", pos = "n"), ImportedSense("two", pos = "n")),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertEquals(1, Regex("Dict · n").findAll(html).count())
    }

    @Test
    fun `pack senses keep numbering and localized pos headers`() {
        val html = content(
            data(
                senses = listOf(
                    SenseDisplay(listOf("noun"), "a cat", emptyList()),
                    SenseDisplay(listOf("noun"), "a feline", emptyList()),
                ),
            ),
        )
        assertTrue(html.contains(">noun<"))
        assertTrue(html.contains(">1.<"))
        assertTrue(html.contains(">2.<"))
        assertEquals(1, Regex("class=\"pos-h\"").findAll(html).count())
    }

    @Test
    fun `group accent color tints its headers`() {
        val html = content(
            data(
                groups = listOf(
                    ImportedSenseGroup(
                        "Dict", listOf(ImportedSense("x")), accentColor = 0xFF112233.toInt(),
                        dictId = "d",
                    ),
                ),
            ),
        )
        assertTrue(html.contains("style=\"color:#112233\""))
    }

    @Test
    fun `structured gating helpers`() {
        val without = data(
            groups = listOf(ImportedSenseGroup("D", listOf(ImportedSense("x")), dictId = "d")),
        )
        val with = data(
            groups = listOf(
                ImportedSenseGroup("D", listOf(ImportedSense("x", scRowid = 3L)), dictId = "d"),
            ),
        )
        assertFalse(DefinitionsDocument.hasStructuredContent(without))
        assertTrue(DefinitionsDocument.hasStructuredContent(with))
        assertEquals(listOf(3L), DefinitionsDocument.structuredRowids(with))
    }

    @Test
    fun `shell carries csp, tokens, and the scope-enforcing script`() {
        val shell = DefinitionsDocument.shellHtml(
            DefinitionsDocument.Tokens(
                text = 0xFFEFEFEF.toInt(),
                textMuted = 0xFFA0A0A0.toInt(),
                textHint = 0xFF606060.toInt(),
                accent = 0xFF00BCD4.toInt(),
                panel = 0xFF242424.toInt(),
                baseFontSizePx = 14.5f,
            ),
        )
        assertTrue(shell.contains("Content-Security-Policy"))
        assertTrue(shell.contains("default-src 'none'"))
        assertTrue(shell.contains("img-src https://pt-media.internal"))
        assertTrue(shell.contains("--text-color: #EFEFEF"))
        assertTrue(shell.contains("--background-color: #242424"))
        assertTrue(shell.contains("font-size: 14.5px"))
        // The scope-escape sweep: every top-level rule other than the
        // wrapper is deleted post-parse.
        assertTrue(shell.contains("selectorText !== scope"))
        assertTrue(shell.contains("ptSwap"))
        assertTrue(shell.contains("ptApplyDictCss"))
    }

    @Test
    fun `cssHex renders opaque and translucent colors`() {
        assertEquals("#102030", DefinitionsDocument.cssHex(0xFF102030.toInt()))
        assertEquals("rgba(16,32,48,0.502)", DefinitionsDocument.cssHex(0x80102030.toInt()))
    }
}
