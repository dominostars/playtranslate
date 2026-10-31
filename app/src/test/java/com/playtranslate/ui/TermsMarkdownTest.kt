package com.playtranslate.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The terms' Markdown subset as styled text ([TermsMarkdown.render]): a `#`
 * heading bold at 1.3 times and a `##` heading bold at 1.15 times, their
 * marks gone; a `**lead**` bold with its marks gone and a `**` with no pair
 * left as written; hard-wrapped lines joined with one space; blocks a blank
 * line apart, blank ones dropped; `\r\n` read as `\n`; nothing else read as
 * a mark; and the text the Worker serves today, the fixture
 * `ptcloud/terms_en.md` (a copy of `worker/src/terms/en.md` in the
 * playtranslate-cloud repository), rendered whole. Robolectric, for the
 * framework's span classes.
 */
@RunWith(RobolectricTestRunner::class)
class TermsMarkdownTest {

    private fun render(markdown: String): Spanned = TermsMarkdown.render(markdown) as Spanned

    /** The text under each bold span, in order. */
    private fun Spanned.bold(): List<String> = getSpans(0, length, StyleSpan::class.java)
        .filter { it.style == Typeface.BOLD }
        .sortedBy { getSpanStart(it) }
        .map { subSequence(getSpanStart(it), getSpanEnd(it)).toString() }

    /** The text under each size span with its proportion, in order. */
    private fun Spanned.sized(): List<Pair<String, Float>> = getSpans(0, length, RelativeSizeSpan::class.java)
        .sortedBy { getSpanStart(it) }
        .map { subSequence(getSpanStart(it), getSpanEnd(it)).toString() to it.sizeChange }

    @Test fun `a one-mark heading is bold at its large size, its mark gone`() {
        val out = render("# PlayTranslate Cloud: Terms and Privacy\n\nOne paragraph.")
        assertEquals("PlayTranslate Cloud: Terms and Privacy\n\nOne paragraph.", out.toString())
        assertEquals(listOf("PlayTranslate Cloud: Terms and Privacy" to 1.3f), out.sized())
        assertEquals(listOf("PlayTranslate Cloud: Terms and Privacy"), out.bold())
    }

    @Test fun `a two-mark heading is bold at its medium size, its mark gone`() {
        val out = render("One paragraph.\n\n## The service\n\nAnother.")
        assertEquals("One paragraph.\n\nThe service\n\nAnother.", out.toString())
        assertEquals(listOf("The service" to 1.15f), out.sized())
        assertEquals(listOf("The service"), out.bold())
    }

    @Test fun `a bold lead is bold with its marks gone, the rest of its paragraph plain`() {
        val out = render("**Your account.** One account per person.")
        assertEquals("Your account. One account per person.", out.toString())
        assertEquals(listOf("Your account."), out.bold())
        assertTrue("a paragraph keeps its size", out.sized().isEmpty())
        val two = render("A **first** and a **second** run.")
        assertEquals("A first and a second run.", two.toString())
        assertEquals(listOf("first", "second"), two.bold())
    }

    @Test fun `hard-wrapped lines join with one space`() {
        val out = render("One account per person, 13 or older; under 18, a parent or guardian agrees to\nthese terms for you.\n  Your own devices may share your account.  ")
        assertEquals(
            "One account per person, 13 or older; under 18, a parent or guardian agrees to these terms for you. " +
                "Your own devices may share your account.",
            out.toString(),
        )
    }

    @Test fun `blocks keep one blank line between them, however many lines part them, and blank ones are dropped`() {
        assertEquals("A\n\nB\n\nC", render("\n\nA\n\n\n\nB\n   \nC\n\n").toString())
        assertEquals("", render("\n \n").toString())
    }

    @Test fun `an unclosed bold mark stays as written`() {
        val out = render("**Lead.** A stray ** stays.")
        assertEquals("Lead. A stray ** stays.", out.toString())
        assertEquals(listOf("Lead."), out.bold())
        val alone = render("Five ** stars.")
        assertEquals("Five ** stars.", alone.toString())
        assertTrue(alone.bold().isEmpty())
        val empty = render("None **** here.")
        assertEquals("a pair with nothing inside is no run", "None **** here.", empty.toString())
        assertTrue(empty.bold().isEmpty())
    }

    @Test fun `CRLF reads as LF`() {
        val out = render("# Title\r\n\r\n**Lead.** One line\r\nand the next.\r\n")
        assertEquals("Title\n\nLead. One line and the next.", out.toString())
        assertEquals(listOf("Title" to 1.3f), out.sized())
        assertEquals(listOf("Title", "Lead."), out.bold())
    }

    /** The server sends no lists, links, code or deeper headings, so none
     *  is read: the `Version:` line, a `###`, a dash and a `#` with no space
     *  are plain paragraphs. */
    @Test fun `nothing else is read as a mark`() {
        val markdown = "Version: 2026-10-30\n\n### Not a heading\n\n- not a list\n\n#No space\n\n[a](link) and `code`"
        val out = render(markdown)
        assertEquals(markdown, out.toString())
        assertTrue(out.sized().isEmpty())
        assertTrue(out.bold().isEmpty())
    }

    /** The shipped text: one `#` heading and two `##`, fourteen bold leads,
     *  nineteen blocks with no line break inside one, and no mark left. */
    @Test fun `the shipped text renders with its three headings and fourteen bold leads, and no mark left`() {
        val markdown = javaClass.getResourceAsStream("/ptcloud/terms_en.md")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val out = render(markdown)
        val text = out.toString()

        assertEquals(
            listOf("PlayTranslate Cloud: Terms and Privacy" to 1.3f, "The service" to 1.15f, "Privacy" to 1.15f),
            out.sized(),
        )
        val headingStarts = out.getSpans(0, out.length, RelativeSizeSpan::class.java).map { out.getSpanStart(it) }.toSet()
        val leads = out.getSpans(0, out.length, StyleSpan::class.java)
            .filter { it.style == Typeface.BOLD && out.getSpanStart(it) !in headingStarts }
            .sortedBy { out.getSpanStart(it) }
            .map { out.subSequence(out.getSpanStart(it), out.getSpanEnd(it)).toString() }
        assertEquals(
            listOf(
                "Your account.", "Paying.", "Fair use.", "What you get.", "Your content.", "Liability.", "Ending.",
                "Changes.", "Law.", "Your content is not kept.", "What leaves your device.", "Where it goes.",
                "What we keep.", "Deletion.",
            ),
            leads,
        )
        assertEquals("the heading's bold and the leads'", 3 + 14, out.bold().size)

        assertFalse(text, '*' in text)
        assertFalse(text, '#' in text)
        val blocks = text.split("\n\n")
        assertEquals(19, blocks.size)
        assertTrue("no hard wrap survives inside a block", blocks.none { '\n' in it || it.isBlank() })
        assertEquals("the version line is a plain paragraph", "Version: 2026-10-30", blocks[1])
        assertTrue("a wrapped lead joins its next line", "a parent or guardian agrees to these terms for you." in text)
    }
}
