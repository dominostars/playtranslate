package com.playtranslate.ui

/**
 * Word-card HTML helpers shared by the review sheet and the one-tap
 * path. The v005-era front/back blob builders that used to live here
 * are gone — the default card types are the field-based [PtModels]
 * note types, assembled by [PtNoteBuilder].
 */
internal object WordAnkiHtmlBuilder {

    /**
     * Wraps a plain-text fallback definition in a styled div. One-tap
     * (no resolved entry) passes the result as the Definition field
     * value for both the default and structured paths; the sheet uses
     * it as its own fallback when [WordAnkiReviewSheet] couldn't
     * resolve a dictionary entry.
     */
    fun wrapFlatDefinitionHtml(fallbackDefinition: String): String {
        val defHtml = fallbackDefinition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { htmlEscape(it.trimStart()) }
        return "<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>"
    }
}
