package com.playtranslate.ui

import com.playtranslate.model.Example

/**
 * A word's definitions for an Anki card, as data, and the one place they
 * become markup. [WordCardDefinition.glossary] is the one producer (the
 * rules about which rows a card keeps live there); the three layouts below
 * are the only shapes a card field draws definitions in:
 *
 *  - [panelHtml]: the word card's Definition field, and the `DEFINITION`
 *    content source for every other note type.
 *  - [cellHtml]: one word's cell of the sentence card's words table.
 *  - [yomitanHtml]: Yomitan's own `{glossary}` markup, for the `GLOSSARY`
 *    content sources, which a note type made for Yomitan (Lapis) reads.
 *
 * The panel and the cell are OUR markup, element for element what they were
 * before this file existed: their look was tuned on device and a note
 * type's CSS is never rewritten, so they do not take Yomitan's `ol > li`
 * (2026-11-11: it bought nothing visible on our own cards, and its list
 * elements would have met `.gl-sense:first-child` and other templates' list
 * styling). Only [yomitanHtml] is written for someone else's template.
 *
 * What is decided once, here, for all three:
 *  - a row is its dictionary's structured glossary when it has one
 *    ([Item.structuredHtml]), its flat text otherwise;
 *  - `data-dictionary` is the dictionary's TITLE wherever it appears
 *    ([Item.dictionary]), which is what Yomitan writes and what a template
 *    or a user's own CSS can key on; our internal id never reaches a card;
 *  - a dictionary's stylesheet rides along only when one of its structured
 *    glossaries was drawn (a stylesheet with nothing to style is dead
 *    weight in a field that is capped in size), scoped to that title.
 *
 * Pure and synchronous: the send's size probe renders a card up to three
 * times off the main thread and must get the same bytes each time.
 */
internal class CardGlossary(
    /** The definitions, in card order: imported dictionaries in the user's
     *  order, then the built-in dictionary's. */
    val items: List<Item>,
    /** Flat text drawn when [items] is empty: no entry resolved, or every
     *  row was removed in the editor. Unused otherwise. */
    val fallback: String,
    /** Dict id to raw styles.css for the dictionaries in [items]; empty
     *  ships no stylesheet (styling off, or the simplified tier). */
    private val dictStyles: Map<String, String>,
) {

    /** One definition. */
    class Item(
        /** From an imported dictionary; false for the built-in one's. */
        val imported: Boolean,
        /** The `data-dictionary` value: the imported dictionary's title (its
         *  label when the title could not be read), or [BUILT_IN_DICTIONARY]. */
        val dictionary: String,
        /** The imported dictionary's id, the key of its stylesheet; "" for
         *  the built-in dictionary and for a group that carries none. */
        val dictId: String,
        /** The imported dictionary's visible name (the user's alias, else
         *  its title); "" for the built-in dictionary, which the panel and
         *  the cell do not name. */
        val label: String,
        /** Which of the card's dictionaries the row belongs to: that
         *  dictionary's place in the card's order (0 is the first, the
         *  built-in one is last). THE identity rows are grouped by, for the
         *  panel's label and for the primary dictionary. [dictionary] and
         *  [label] are display text and two dictionaries can share either:
         *  an import titled like the built-in tag, or two labels standing in
         *  for titles that could not be read. */
        val group: Int,
        /** Part of speech as display text, "" for none: an imported row's
         *  own tags, a built-in row's labels through the caller's localizer. */
        val pos: String,
        /** The dictionary's structured glossary as markup
         *  ([YomitanContentHtml.glossaryHtml]); null draws [text]. */
        val structuredHtml: String?,
        /** The flat definition, raw. */
        val text: String,
        /** Register note, already rendered; built-in rows only. */
        val misc: String? = null,
        /** Examples to draw, curation and the editor's translations already
         *  applied; built-in rows only. */
        val examples: List<Example> = emptyList(),
    )

    /** The dictionaries whose stylesheet a field drawing [items] needs:
     *  those with a structured glossary drawn, in order, id to title. */
    fun styledDictionaries(): Map<String, String> = styledDictionaries(items)

    // ── The panel ───────────────────────────────────────────────────────

    /**
     * The Definition field: each styled dictionary's scoped stylesheet,
     * the localized [header] as a `.gl-section`, then the `.gl-panel` of
     * numbered rows (the row number runs across every dictionary), or the
     * [fallback] as one gloss. "" when there is nothing to show, so the
     * field, and the template's slot for it, stays empty.
     */
    fun panelHtml(styler: HtmlStyler, header: String): String {
        val body = StringBuilder()
        if (items.isEmpty()) {
            val lines = fallback.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { htmlEscape(it.trimStart()) }
            if (lines.isEmpty()) return ""
            body.append("<div ${styler("gl-gloss", "padding:14px 0;")}>").append(lines).append("</div>")
        } else {
            items.forEachIndexed { index, item ->
                // The first kept row of its dictionary.
                val leads = index == 0 || items[index - 1].group != item.group
                body.appendPanelRow(index, item, leads, styler)
            }
        }
        val sb = StringBuilder(ownStyleBlocks(styledDictionaries(), dictStyles))
        if (header.isNotEmpty()) {
            sb.append("<div ${styler("gl-section", "")}>").append(htmlEscape(header)).append("</div>")
        }
        return sb.append("<div ${styler("gl-panel", "")}>").append(body).append("</div>").toString()
    }

    /** A flex row: the right-aligned number beside the sense body. Row 0
     *  drops its hairline inline, since the inline-styled path has no
     *  `:first-child`. */
    private fun StringBuilder.appendPanelRow(index: Int, item: Item, leads: Boolean, styler: HtmlStyler) {
        append("<div ${styler("gl-sense", if (index == 0) "border-top:0;" else "")}>")
        append("<span ${styler("gl-sense-n gl-hint", "")}>").append(index + 1)
        append("</span><div ${styler("gl-sense-b", "")}>")
        if (item.structuredHtml != null) {
            appendOwnStructured(item, styler("gl-sc", ""))
        } else {
            append("<div ${styler("gl-gloss", "")}>")
            // An imported definition's line breaks are its structure (sense
            // groups, note lines). A built-in gloss is one line by
            // construction and is written as it is.
            append(if (item.imported) flatHtml(item.text) else htmlEscape(item.text))
            append("</div>")
        }
        // The dictionary's name rides its first kept row, ahead of the part
        // of speech; the built-in dictionary's rows carry the part of
        // speech alone.
        val label = listOfNotNull(
            item.label.takeIf { leads && it.isNotEmpty() },
            item.pos.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (label.isNotEmpty()) {
            append("<div ${styler("gl-pos gl-hint", "")}>").append(htmlEscape(label)).append("</div>")
        }
        item.misc?.let {
            append("<div ${styler("gl-misc gl-hint", "")}>").append(htmlEscape(it)).append("</div>")
        }
        for (example in item.examples) {
            append("<div ${styler("gl-ex gl-hint", "")}>").append(htmlEscape(example.text))
            if (example.translation.isNotBlank()) {
                append("<div ${styler("gl-ex-tr", "")}>").append(htmlEscape(example.translation)).append("</div>")
            }
            append("</div>")
        }
        append("</div></div>")
    }

    // ── The words table's cell ──────────────────────────────────────────

    /**
     * One word's definitions inside its cell of the words table: a header
     * line wherever the dictionary or the part of speech changes, then
     * `1.` rows numbered from one in each cell, or the [fallback]'s lines.
     * A [target] cell (the card's own word) runs its numbers and text one
     * step larger, inline, because target and context cells share their
     * classes and the inline-styled path has no descendant selectors.
     *
     * No stylesheet: a table has many cells and ships each dictionary's
     * once, ahead of the table ([styledDictionaries], [ownStyleBlocks]).
     */
    fun cellHtml(styler: HtmlStyler, target: Boolean): String {
        val size = if (target) "font-size:0.9em;" else ""
        val sb = StringBuilder()
        if (items.isEmpty()) {
            // Flat meaning lines, which carry their own numbering.
            fallback.split("\n").filter { it.isNotBlank() }.forEach { line ->
                sb.append("<div ${styler("gl-dtext gl-secondary", size + "margin-top:6px;")}>")
                    .append(htmlEscape(line)).append("</div>")
            }
            return sb.toString()
        }
        var previousHeader: String? = null
        items.forEachIndexed { index, item ->
            // An imported row's header is display text (dictionary name ·
            // tags); capitals come from the CSS, not from here.
            val header = when {
                !item.imported -> item.pos
                item.pos.isBlank() -> item.label
                else -> "${item.label} · ${item.pos}"
            }
            if (header.isNotEmpty() && header != previousHeader) {
                sb.append("<div ${styler("gl-pos-h gl-secondary", "")}>")
                    .append(htmlEscape(header)).append("</div>")
                previousHeader = header
            }
            sb.append("<div ${styler("gl-def", "")}>")
                .append("<span ${styler("gl-num gl-hint", size)}>").append(index + 1).append(".</span>")
            if (item.structuredHtml != null) {
                // Block-level, so it sits beside the number like the flat
                // span does.
                sb.appendOwnStructured(item, styler("gl-sc", "display:inline-block;vertical-align:top;"))
            } else {
                sb.append("<span ${styler("gl-dtext", size)}>").append(flatHtml(item.text)).append("</span>")
            }
            sb.append("</div>")
            item.misc?.let {
                sb.append("<div ${styler("gl-misc gl-hint", "margin-left:25px;")}>")
                    .append(htmlEscape(it)).append("</div>")
            }
        }
        return sb.toString()
    }

    /** A structured glossary in our markup: the `.gl-sc` wrapper the
     *  dictionary's stylesheet is scoped to ([AnkiCardCss.ownScope]). */
    private fun StringBuilder.appendOwnStructured(item: Item, wrapperAttributes: String) {
        append("<div ").append(wrapperAttributes).append(" data-dictionary=\"")
            .append(htmlEscape(item.dictionary)).append("\">")
            .append(item.structuredHtml).append("</div>")
    }

    // ── Yomitan's markup ────────────────────────────────────────────────

    /**
     * The definitions as Yomitan writes its `{glossary}` marker: a
     * `div.yomitan-glossary` holding one ordered list, one item per
     * definition, each tagged `data-dictionary` with its dictionary's
     * title, then the dictionaries' stylesheets scoped the way Yomitan
     * scopes them. No class and no inline style of ours: a note type made
     * for Yomitan styles this markup itself, and Lapis's card script reads
     * it (which dictionary is the main one, what to hide on its second
     * page).
     *
     * An item opens with Yomitan's italic tag, `(part of speech, register,
     * dictionary)`, then its content: a structured glossary in Yomitan's
     * `structured-content` wrapper, or the flat text. A built-in row's
     * examples follow as a plain list. The [fallback] is one untagged
     * built-in item.
     *
     * [primaryOnly] keeps the first dictionary's items alone ([Item.group],
     * never the tag, which two dictionaries can share): Lapis's
     * MainDefinition, where the full list is its Glossary. "First" is the
     * card's own order (the user's dictionary order, the built-in
     * dictionary last), the order the single-dictionary setting already
     * reads.
     *
     * "" when there is nothing to show.
     */
    fun yomitanHtml(primaryOnly: Boolean): String {
        val shown = if (primaryOnly) items.filter { it.group == items.first().group } else items
        val sb = StringBuilder("<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>")
        if (shown.isEmpty()) {
            val lines = fallback.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { htmlEscape(it.trimStart()) }
            if (lines.isEmpty()) return ""
            sb.append("<li data-dictionary=\"").append(htmlEscape(BUILT_IN_DICTIONARY)).append("\">")
                .append(lines).append("</li>")
        }
        for (item in shown) {
            sb.append("<li data-dictionary=\"").append(htmlEscape(item.dictionary)).append("\">")
            val tags = listOfNotNull(
                item.pos.takeIf { it.isNotBlank() },
                item.misc?.takeIf { it.isNotBlank() },
                (if (item.imported) item.label else BUILT_IN_DICTIONARY).takeIf { it.isNotEmpty() },
            )
            if (tags.isNotEmpty()) {
                sb.append("<i>(").append(htmlEscape(tags.joinToString(", "))).append(")</i> ")
            }
            if (item.structuredHtml != null) {
                sb.append("<span class=\"structured-content\">").append(item.structuredHtml).append("</span>")
            } else {
                sb.append(flatHtml(item.text))
            }
            if (item.examples.isNotEmpty()) {
                sb.append("<ul>")
                for (example in item.examples) {
                    sb.append("<li>").append(htmlEscape(example.text))
                    if (example.translation.isNotBlank()) {
                        sb.append("<br>").append(htmlEscape(example.translation))
                    }
                    sb.append("</li>")
                }
                sb.append("</ul>")
            }
            sb.append("</li>")
        }
        sb.append("</ol></div>")
        return sb.append(
            AnkiCardCss.styleBlocks(
                styledDictionaries(shown).mapValues { AnkiCardCss.yomitanScope(it.value) },
                dictStyles,
            ),
        ).toString()
    }

    companion object {
        /** What a built-in definition's `data-dictionary` reads, and the
         *  name in its Yomitan tag. A constant in every UI language: a
         *  template or a user's CSS keys on it, so it must not change with
         *  the app's locale the way the "Built-in" title on screen does. */
        const val BUILT_IN_DICTIONARY = "PlayTranslate"

        /** The stylesheets of [dictionaries] (id to title) for a field in
         *  OUR markup, each scoped to its `.gl-sc` wrapper. */
        fun ownStyleBlocks(dictionaries: Map<String, String>, dictStyles: Map<String, String>): String =
            AnkiCardCss.styleBlocks(dictionaries.mapValues { AnkiCardCss.ownScope(it.value) }, dictStyles)

        private fun styledDictionaries(items: List<Item>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (item in items) {
                if (item.structuredHtml != null && item.dictId.isNotEmpty()) out.putIfAbsent(item.dictId, item.dictionary)
            }
            return out
        }

        /** Flat definition text as markup: escaped, its line breaks kept
         *  (without `<br>` they collapse to spaces and a definition reads
         *  as one run-on). */
        private fun flatHtml(text: String): String = htmlEscape(text).replace("\n", "<br>")
    }
}
