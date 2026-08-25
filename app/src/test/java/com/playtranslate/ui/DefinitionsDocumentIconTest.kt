package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The styled document draws the deck badge's glyph from its own copy of the
 * `cards_stack` path ([DefinitionsDocument.CARD_STACK_PATH]) because a
 * vector drawable and a page string have no build step that can share one.
 * This is the guard on that copy: re-authoring `ic_card_stack.xml` without
 * re-copying the path would leave the WebView chip drawing the OLD glyph,
 * silently and only on the surfaces a Yomitan dictionary styles.
 *
 * A source scan rather than a lint rule, matching
 * [com.playtranslate.net.PtHttpEnforcementTest]'s pattern — the project
 * configures neither detekt nor a custom lint.
 */
class DefinitionsDocumentIconTest {

    @Test fun `the page's card-stack path matches the vector drawable`() {
        // testDebugUnitTest runs with the module dir as cwd; fall back to the
        // repo-root-relative path just in case.
        val xml = listOf(
            File("src/main/res/drawable/ic_card_stack.xml"),
            File("app/src/main/res/drawable/ic_card_stack.xml"),
        ).firstOrNull { it.isFile }
            ?: error("ic_card_stack.xml not found (cwd=${File(".").absolutePath})")

        val pathData = Regex("""android:pathData="([^"]*)"""")
            .find(xml.readText())?.groupValues?.get(1)
            ?: error("no android:pathData in ${xml.path}")

        assertEquals(
            "ic_card_stack.xml's path changed — copy it into " +
                "DefinitionsDocument.CARD_STACK_PATH so the styled meta row's " +
                "deck badge draws the same glyph as the native pill.",
            pathData,
            DefinitionsDocument.CARD_STACK_PATH,
        )
    }

    @Test fun `the glyph is inline SVG, which the shell's CSP admits`() {
        // An <img> would need a data: URI, and the shell allows images only
        // from the media origin — the chip would render icon-less again, the
        // exact failure this replaced.
        val svg = DefinitionsDocument.contentHtml(
            WordDefinitionData(
                word = "猫",
                reading = null,
                senses = emptyList(),
                freqScore = 0,
                isCommon = false,
                importedGroups = emptyList(),
            ),
            structured = emptyMap(),
            localizePos = { it.joinToString("/") },
            metaChips = listOf(
                DefinitionsDocument.MetaChip(
                    "Core 2k",
                    icon = DefinitionsDocument.MetaChip.Icon.CARD_STACK,
                ),
            ),
        )
        assertTrue(svg, svg.contains("<span class=\"meta-chip with-icon\">"))
        assertTrue(svg, svg.contains("<svg class=\"chip-icon\""))
        // currentColor, not a literal: the glyph takes the chip's ink the way
        // the native pill's drawable takes its tint.
        assertTrue(svg, svg.contains("fill=\"currentColor\""))
        assertTrue(svg, svg.contains(DefinitionsDocument.CARD_STACK_PATH))
        assertTrue(svg, svg.endsWith("Core 2k</span></div>"))
        assertTrue(svg, !svg.contains("<img"))
    }
}
