package com.playtranslate.ui

/**
 * Top-level builders for the legacy v004 word-card HTML. Extracted from
 * [WordAnkiReviewSheet] so the one-tap path can reuse the same
 * scaffolding without owning the sheet's curation state.
 *
 * The rich per-sense definitions block (with curation-aware sense /
 * example filtering and Tatoeba "More examples") lives in the sheet
 * because it depends on user state; this builder accepts whatever HTML
 * the caller hands it as [definitionHtml] in [buildBackHtml].
 */
internal object WordAnkiHtmlBuilder {

    /**
     * Front-face HTML — the headword centred on a clean page. Pure
     * function of [word]; no curation state involved.
     */
    fun buildFrontHtml(word: String): String = buildString {
        append("<style>")
        append("body{margin:0;padding:0;}")
        append("</style>")
        append("<div class=\"gl-front\" style=\"text-align:center;font-size:2.2em;padding:32px 16px;\">")
        append(htmlEscape(word))
        append("</div>")
    }

    /**
     * Back-face HTML wrapping [definitionHtml]. The CSS block at the
     * top supplies the `.gl-*` classes [classStyler] emits — callers
     * building [definitionHtml] with [classStyler] can lean on the
     * surrounding `<style>`.
     *
     * @param definitionHtml pre-rendered definition body inserted after
     *   the headword/reading/pos/stars block. The sheet passes its
     *   curation-aware rich per-sense HTML; one-tap passes the result
     *   of [wrapFlatDefinitionHtml] over the plain fallback definition.
     */
    fun buildBackHtml(
        word: String,
        reading: String,
        pos: String,
        freqScore: Int,
        imageFilename: String?,
        audioFilename: String?,
        definitionHtml: String,
    ): String = buildString {
        append("<style>")
        append("body{visibility:hidden!important;white-space:normal!important;}")
        append(".gl-front{display:none!important;}")
        append("#answer{display:none!important;}")
        append(".gl-back{visibility:visible!important;}")
        append(".gl-sense{margin:14px 4px;}")
        append(".gl-pos{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;}")
        append(".gl-gloss{font-size:1.1em;margin-top:4px;}")
        append(".gl-misc{font-size:0.85em;color:#888;font-style:italic;margin-top:2px;}")
        append(".gl-ex{margin:8px 0 0 8px;padding-left:10px;border-left:2px solid #6cd1c2;}")
        append(".gl-ex-tr{font-size:0.92em;color:#888;margin-top:2px;}")
        append(".gl-section{font-size:0.78em;letter-spacing:0.08em;color:#888;text-transform:uppercase;margin:18px 4px 6px;}")
        append("</style>")
        append("<div class=\"gl-back\">")
        if (imageFilename != null) {
            append("<div style=\"text-align:center;margin:12px 0;\">")
            append("<img src=\"")
            append(htmlEscape(imageFilename))
            append("\" style=\"max-width:100%;border-radius:6px;\">")
            append("</div>")
        }
        // [sound:] near the top of the back, under the screenshot. Inside
        // .gl-back so the replay button inherits the visible-back
        // visibility (body is hidden!important above).
        if (audioFilename != null) {
            append("<div style=\"text-align:center;margin:8px 0;\">")
            append("[sound:$audioFilename]")
            append("</div>")
        }
        append("<div style=\"text-align:center;font-size:1.8em;padding:12px 4px;\">")
        append(htmlEscape(word))
        append("</div>")
        if (reading.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:1.1em;color:#888;\">")
            append(htmlEscape(reading))
            append("</div>")
        }
        if (pos.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:0.85em;color:#888;\">")
            append(htmlEscape(pos))
            append("</div>")
        }
        if (freqScore > 0) {
            // starsString emits only ★ glyphs — safe.
            val stars = SentenceAnkiHtmlBuilder.starsString(freqScore)
            append("<div style=\"text-align:center;font-size:0.9em;color:#888;margin-top:4px;\">$stars</div>")
        }
        append("<div style=\"margin-bottom:12px;\"></div>")
        append("<hr>")
        append(definitionHtml)
        append("</div>")
    }

    /**
     * Wraps a plain-text fallback definition in the styled div the
     * sheet uses on its empty-entry branch. One-tap (no resolved entry)
     * passes the result through to [buildBackHtml]; the sheet uses it
     * as its own fallback when [WordAnkiReviewSheet] couldn't resolve a
     * dictionary entry.
     */
    fun wrapFlatDefinitionHtml(fallbackDefinition: String): String {
        val defHtml = fallbackDefinition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { htmlEscape(it.trimStart()) }
        return "<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>"
    }
}
