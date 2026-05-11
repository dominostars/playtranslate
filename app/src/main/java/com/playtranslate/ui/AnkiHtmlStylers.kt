package com.playtranslate.ui

/**
 * A pluggable "how to render a styling concern on an HTML element"
 * callback. The first param is an optional PlayTranslate CSS class
 * identifier (e.g. `"gl-sense"`, `"gl-hl-bg"`) and the second is any
 * per-callsite inline CSS to also apply.
 *
 * The styler returns a single attribute string ready to inline into an
 * opening tag (e.g. `class="gl-sense" style="margin-top:4px;"` or
 * `style="margin:14px 4px;margin-top:4px;"`). Returning ONE combined
 * attribute string means callsites never accidentally emit two
 * `style=""` attributes on the same element.
 *
 * Usage:
 * ```
 * sb.append("<div ${styler("gl-hl-bg", "margin-bottom:14px;")}>")
 * ```
 */
internal typealias HtmlStyler = (cls: String?, extraInlineStyle: String) -> String

/**
 * Legacy path: emit class refs. The surrounding `<style>` block in
 * [com.playtranslate.ui.SentenceAnkiHtmlBuilder.buildBackHtml] or
 * [com.playtranslate.ui.WordAnkiReviewSheet.buildWordBackHtml] supplies
 * the matching CSS. Inline styles still pass through.
 */
internal val classStyler: HtmlStyler = { cls, extra ->
    buildString {
        if (cls != null) {
            append("class=\"")
            append(cls)
            append("\"")
            if (extra.isNotEmpty()) append(' ')
        }
        if (extra.isNotEmpty()) {
            append("style=\"")
            append(extra)
            append("\"")
        }
    }
}

/**
 * Structured path: replace known PlayTranslate class refs with inline
 * CSS, merged with any per-callsite inline styles into a single
 * `style=""` attribute. Class refs not in [INLINE_STYLES] pass through
 * as plain `class="cls"` so callers shipping their own classes
 * (e.g. JPMN's `glossary` class) still emit usable markup.
 */
internal val inlineStyler: HtmlStyler = { cls, extra ->
    val inlined = cls?.let { INLINE_STYLES[it] }
    val combined = (inlined.orEmpty()) + extra
    buildString {
        if (cls != null && inlined == null) {
            append("class=\"")
            append(cls)
            append("\"")
            if (combined.isNotEmpty()) append(' ')
        }
        if (combined.isNotEmpty()) {
            append("style=\"")
            append(combined)
            append("\"")
        }
    }
}

/**
 * Mode-agnostic inline equivalents of the v004 model's CSS classes.
 * The structured path can't ship `@media(prefers-color-scheme)`
 * adaptive rules without fighting the user's template, so colors are
 * mid-tone values chosen to read on both light and dark AnkiDroid
 * cards. The accent color (#6cd1c2) and highlight (#CC6B1A at 18%
 * alpha) match the v004 dark-mode palette closely enough for visual
 * continuity.
 */
internal val INLINE_STYLES: Map<String, String> = mapOf(
    // Per-sense layout
    "gl-sense"     to "margin:14px 4px;",
    "gl-pos"       to "font-size:0.78em;letter-spacing:0.08em;color:#8a8a8a;text-transform:uppercase;",
    "gl-gloss"     to "font-size:1.1em;margin-top:4px;",
    "gl-misc"      to "font-size:0.85em;color:#8a8a8a;font-style:italic;margin-top:2px;",
    "gl-ex"        to "margin:8px 0 0 8px;padding-left:10px;border-left:2px solid #6cd1c2;",
    "gl-ex-tr"     to "font-size:0.92em;color:#8a8a8a;margin-top:2px;",
    "gl-section"   to "font-size:0.78em;letter-spacing:0.08em;color:#8a8a8a;text-transform:uppercase;margin:18px 4px 6px;",
    // Sentence-words HTML
    "gl-secondary" to "color:#8a8a8a;",
    "gl-hint"      to "color:#9a9a9a;",
    "gl-hl"        to "color:#CC6B1A;",
    "gl-hl-bg"     to "background:rgba(204,107,26,0.18);",
)
