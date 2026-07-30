package com.playtranslate

import com.playtranslate.ui.SentenceAnkiHtmlBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Anki note-matching primitives: the first-field checksum and the
 * HTML stripping it depends on. The load-bearing case is that PlayTranslate's
 * own HTML-wrapped first field hashes to the same csum as a bare-word card, so
 * the "already in Anki" badge fires for the user's own cards.
 */
class AnkiCsumTest {

    /** SHA-1("a") = 86f7e437faa5a7fce15d1ddcb9eaeaea377667b8; csum is the first
     *  4 bytes as an unsigned 32-bit int. Anchors the algorithm to real SHA-1. */
    @Test
    fun checksum_matchesAnkiDefinition() {
        assertEquals(0x86f7e437L, AnkiCsum.checksum("a"))
    }

    @Test
    fun checksum_isDeterministic() {
        assertEquals(AnkiCsum.checksum("食べる"), AnkiCsum.checksum("食べる"))
    }

    @Test
    fun stripHtml_removesStyleBlockAndTags() {
        // Mirrors the retired v005 front blob's structure — legacy
        // notes in users' collections still carry this shape.
        val front = "<style>body{margin:0;padding:0;}</style>" +
            "<div class=\"gl-front\" style=\"text-align:center;\">食べる</div>"
        assertEquals("食べる", AnkiCsum.stripHtml(front))
    }

    /** The Sentence model's field 0 carries `<b>` highlight markup;
     *  stripping must recover the plain sentence so csum detection
     *  matches a bare-text query. */
    @Test
    fun stripHtml_ofSentencePlainWithBold_equalsPlainSentence() {
        val html = SentenceAnkiHtmlBuilder.buildSentencePlain(
            "友達に聞いた", words = emptyList(), highlightedWords = setOf("聞いた"),
        )
        assertEquals("友達に聞いた", AnkiCsum.stripHtml(html))
    }

    @Test
    fun stripHtml_removesInlineTagsAndComments() {
        assertEquals("dog", AnkiCsum.stripHtml("<b>dog</b>"))
        assertEquals("word", AnkiCsum.stripHtml("<!-- note -->word"))
    }

    @Test
    fun stripHtml_decodesEntities() {
        assertEquals("a&b", AnkiCsum.stripHtml("a&amp;b"))
        assertEquals("\"x\"", AnkiCsum.stripHtml("&quot;x&quot;"))
    }

    /** The property the feature hinges on: a legacy v005 note's
     *  HTML-wrapped first field and the bare word produce the same
     *  csum — which is also why a new field-based Word note (field 0 =
     *  the bare word) hashes identically to the old blob cards. Only
     *  Anki's mid-scoped dup check keeps the two models from colliding
     *  on insert; detection intentionally matches both. */
    @Test
    fun checksum_htmlWrappedFirstFieldEqualsBareWord() {
        val wrapped = "<style>body{margin:0;padding:0;}</style>" +
            "<div class=\"gl-front\">食べる</div>"
        assertEquals(
            AnkiCsum.checksum(AnkiCsum.stripHtml("食べる")),
            AnkiCsum.checksum(AnkiCsum.stripHtml(wrapped)),
        )
    }
}
