package com.playtranslate.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * PlayTranslate Cloud's terms as styled text for a TextView, from the
 * Markdown the server serves them in (`GET /v1/terms`, "Terms" in
 * docs/api.md in the playtranslate-cloud repository). The server promises a
 * subset and nothing more: `#` and `##` headings, `**bold**` paragraph leads
 * and plain paragraphs. So, and only so:
 *  - `\r\n` is read as `\n`; then a block is a run of lines between blank
 *    lines, its hard-wrapped lines joined with one space (each line's own
 *    ends trimmed), and a blank block, a trailing newline's included, is
 *    dropped;
 *  - a block that starts `# ` is a heading, its text bold at 1.3 times the
 *    view's size; one that starts `## `, bold at 1.15 times;
 *  - a `**x**` run anywhere in a block is x in bold with the marks removed;
 *    a `**` with no closing pair stays as written;
 *  - the blocks are joined with a blank line between them.
 * Nothing else is interpreted (no lists, links, code or escapes: the server
 * sends none), so the text's `Version:` line is a plain paragraph. Pure: no
 * Context and no resources, and the spans are relative, so the text follows
 * whatever size its view sets.
 */
object TermsMarkdown {

    fun render(markdown: String): CharSequence {
        val out = SpannableStringBuilder()
        blocks(markdown).forEachIndexed { i, block ->
            if (i > 0) out.append(BLOCK_SEPARATOR)
            val heading = HEADINGS.firstOrNull { (marker, _) -> block.startsWith(marker) }
            val start = out.length
            appendInline(out, heading?.let { (marker, _) -> block.removePrefix(marker).trimStart() } ?: block)
            if (heading != null) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(heading.second), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return SpannedString(out)
    }

    /** [markdown]'s blocks, in order: the runs of lines between blank
     *  lines, each run's lines trimmed and joined with one space. */
    private fun blocks(markdown: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (line in markdown.replace("\r\n", "\n").split('\n')) {
            if (line.isBlank()) {
                if (lines.isNotEmpty()) blocks += lines.joinToString(" ")
                lines.clear()
            } else {
                lines += line.trim()
            }
        }
        if (lines.isNotEmpty()) blocks += lines.joinToString(" ")
        return blocks
    }

    /** [text] onto [out], each `**x**` run as x in bold with its marks
     *  removed; a `**` with no closing pair is appended as written. */
    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var from = 0
        for (run in BOLD_RUN.findAll(text)) {
            out.append(text, from, run.range.first)
            val start = out.length
            out.append(run.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            from = run.range.last + 1
        }
        out.append(text, from, text.length)
    }

    /** The two heading markers and the size of each, relative to the
     *  view's text size. */
    private val HEADINGS = listOf("# " to 1.3f, "## " to 1.15f)

    /** A bold run: `**`, at least one character, the next `**`. */
    private val BOLD_RUN = Regex("""\*\*(.+?)\*\*""")

    private const val BLOCK_SEPARATOR = "\n\n"
}
