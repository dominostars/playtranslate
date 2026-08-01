package com.playtranslate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [HtmlStyler] contract the card HTML builders rely on —
 * especially the compound-class rule: a space-separated class argument
 * ("gl-w-read gl-hint") must resolve every token on the structured
 * path, or those elements render completely unstyled on third-party
 * note types (the v002 builders emit compounds everywhere).
 */
class AnkiHtmlStylersTest {

    @Test fun `classStyler emits compound class attribute verbatim`() {
        assertEquals("class=\"gl-w-read gl-hint\"", classStyler("gl-w-read gl-hint", ""))
    }

    @Test fun `inlineStyler merges compound classes into one style attribute`() {
        val out = inlineStyler("gl-w-read gl-hint", "")
        assertTrue("styles from first class: $out", out.contains("flex:1"))
        assertTrue("styles from second class: $out", out.contains("color:#9a9a9a"))
        assertFalse("no class attr when all classes resolve: $out", out.contains("class="))
        // Single style attribute only.
        assertEquals(1, Regex("style=\"").findAll(out).count())
    }

    @Test fun `inlineStyler passes unknown classes through beside resolved styles`() {
        val out = inlineStyler("glossary gl-hint", "margin-top:2px;")
        assertTrue(out.contains("class=\"glossary\""))
        assertTrue(out.contains("style=\"color:#9a9a9a;margin-top:2px;\""))
    }

    @Test fun `inlineStyler with null class emits extra style only`() {
        assertEquals("style=\"margin:0;\"", inlineStyler(null, "margin:0;"))
    }

    @Test fun `every inline style key resolves to non-empty css`() {
        INLINE_STYLES.forEach { (cls, css) ->
            assertTrue("empty inline css for $cls", css.isNotBlank())
            assertTrue("unterminated css for $cls: $css", css.endsWith(";"))
        }
    }
}
