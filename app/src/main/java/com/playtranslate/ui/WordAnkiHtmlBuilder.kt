package com.playtranslate.ui

/**
 * Word-card HTML helpers shared by the review sheet and the one-tap
 * path. The v005-era front/back blob builders that used to live here
 * are gone — the default card types are the field-based [PtModels]
 * note types, assembled by [PtNoteBuilder].
 */
internal object WordAnkiHtmlBuilder {

    /**
     * Wraps a plain-text fallback definition in the v002 Definition-field
     * chrome: optional localized `.gl-section` header, then a `.gl-panel`
     * holding the lines as one `.gl-gloss` row — the same surfaces real
     * senses get, so a no-entry card doesn't read as a different design.
     * [WordCardDefinition] renders it for both the default and structured
     * paths when no dictionary entry resolved. Empty string in → empty
     * string out, so the template's `{{Definition}}` slot stays blank.
     */
    fun wrapFlatDefinitionHtml(
        fallbackDefinition: String,
        styler: HtmlStyler = classStyler,
        /** Localized "Definitions" header; "" omits it. Callers with a
         *  Context pass R.string.anki_group_definitions. */
        sectionHeader: String = "",
    ): String {
        val defHtml = fallbackDefinition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { htmlEscape(it.trimStart()) }
        if (defHtml.isEmpty()) return ""
        val sb = StringBuilder()
        if (sectionHeader.isNotEmpty()) {
            sb.append("<div ${styler("gl-section", "")}>")
                .append(htmlEscape(sectionHeader)).append("</div>")
        }
        sb.append("<div ${styler("gl-panel", "")}>")
            .append("<div ${styler("gl-gloss", "padding:14px 0;")}>")
            .append(defHtml).append("</div></div>")
        return sb.toString()
    }
}
