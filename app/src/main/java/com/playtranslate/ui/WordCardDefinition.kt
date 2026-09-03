package com.playtranslate.ui

import com.playtranslate.language.DefinitionResult
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.model.Sense
import com.playtranslate.model.unambiguousFallbackPos

/**
 * The word card's Definition field, as DATA. Every word-card send — the
 * review sheet and the hold-to-create one-tap alike — describes what it
 * wants rendered and lets [sendWordCard] fetch the structured-glossary
 * payload and emit the markup, the way [sendSentenceCard] already owns the
 * sentence card's words table. One producer: the sheet cannot ship a styled
 * Definition the one-tap silently lacks. (That drift is what this replaced:
 * the styled fetch and the gl-sc builder were private to the sheet, so a
 * hold-created word card stayed on the flattened text no matter what the
 * lens or the detail page had just shown.)
 *
 * The sheet fills the curation fields from its editor state; one-tap passes
 * the bare [entry] its surface already held and leaves them at their
 * "nothing removed, nothing translated" defaults — so for an unedited entry
 * both paths emit byte-identical HTML.
 */
data class WordCardDefinition(
    /** Flat definition text: the whole field when [entry] is null, the
     *  body when every pack sense was curated away. */
    val fallback: String,
    /** The resolved entry (imported groups + pack senses); null renders
     *  [fallback] alone. */
    val entry: DictionaryEntry? = null,
    /** Every entry the lookup resolved (Wiktionary packs split POS into
     *  separate entries); pack senses flatten across all of them. */
    val entries: List<DictionaryEntry> = listOfNotNull(entry),
    /** Target-language definition tier, when the surface resolved one. */
    val defResult: DefinitionResult? = null,
    /** Target-language code; a non-English target with Native senses
     *  renders those instead of the pack's glosses. */
    val targetLang: String = "en",
    /** Curation: indexes (flat pack senses, or Native target senses when
     *  target-driven) the user removed in the editor. */
    val removedSenses: Set<Int> = emptySet(),
    /** Curation: (sense, example) indexes the user removed. */
    val removedExamples: Set<Pair<Int, Int>> = emptySet(),
    /** (sense, example) → translation the editor produced for a pack
     *  example; absent = the example's own translation. */
    val exampleTranslations: Map<Pair<Int, Int>, String> = emptyMap(),
) {
    /** The groups whose structured glossaries the pipeline fetches. */
    val importedGroups: List<ImportedSenseGroup>
        get() = entry?.importedSenses.orEmpty()

    /**
     * The Definition field's full v002 chrome — localized "Definitions"
     * `.gl-section` header plus the `.gl-panel` holding the senses (or the
     * flat fallback when no entry resolved), preceded by each rendering
     * dictionary's scoped styles.css (Tier 2) when [styled] carries one.
     * "" when there is nothing to show, keeping the field blank.
     *
     * [styled] is the pipeline's fetch for [importedGroups]; null = every
     * imported sense renders as its flat text. [renderMisc] is the
     * register-tag renderer (production: `Context::renderMiscText`).
     */
    fun panelHtml(
        styler: HtmlStyler,
        styled: YomitanStyledData?,
        definitionsHeader: String,
        renderMisc: (List<String>) -> String?,
    ): String {
        val entry = entry
            ?: return WordAnkiHtmlBuilder.wrapFlatDefinitionHtml(fallback, styler, definitionsHeader)
        val body = buildString { appendSenses(entry, styler, styled, renderMisc) }
        if (body.isEmpty()) return ""
        // Tier 2: dictionaries whose structured senses render in this card
        // ship their styles.css, scoped, inline in the field (identity by
        // the data-dictionary the gl-sc blocks carry).
        val styles = styled?.let { st ->
            val used = entry.importedSenses
                .filter { g -> g.senses.any { it.scRowid != null && st.structured.containsKey(it.scRowid) } }
                .map { it.dictId }
            AnkiCardCss.styleBlocks(used, st.dictStyles)
        }.orEmpty()
        return styles + "<div ${styler("gl-section", "")}>" +
            htmlEscape(definitionsHeader) +
            "</div><div ${styler("gl-panel", "")}>" + body + "</div>"
    }

    private fun StringBuilder.appendSenses(
        entry: DictionaryEntry,
        styler: HtmlStyler,
        styled: YomitanStyledData?,
        renderMisc: (List<String>) -> String?,
    ) {
        // Sense rows are flex: a right-aligned number column beside the
        // sense body (the lens's buildDefinitionRow, in HTML). One counter
        // across every branch keeps numbering continuous (imported rows
        // first, like the lens) and gives the structured path its
        // border-top:0 on row 0.
        var rowIdx = 0
        fun openSense() {
            append("<div ${styler("gl-sense", if (rowIdx == 0) "border-top:0;" else "")}>")
            append("<span ${styler("gl-sense-n gl-hint", "")}>")
            append(++rowIdx)
            append("</span><div ${styler("gl-sense-b", "")}>")
        }
        fun closeSense() {
            append("</div></div>")
        }
        fun appendFlatFallback() {
            val defHtml = fallback.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { htmlEscape(it.trimStart()) }
            append("<div ${styler("gl-gloss", "padding:14px 0;")}>$defHtml</div>")
        }
        // Imported term-dictionary definitions lead the card, final text.
        val hasImportedRows = entry.importedSenses.any { it.senses.isNotEmpty() }
        entry.importedSenses.forEach { group ->
            group.senses.forEachIndexed { defIdx, sense ->
                openSense()
                // Structured glossary when the sense retained one and the
                // pipeline's fetch delivered it; flat text otherwise.
                val structuredHtml = sense.scRowid
                    ?.let { styled?.structured?.get(it) }
                    ?.let { YomitanContentHtml.glossaryHtml(it, group.dictId, includeImages = false) }
                if (structuredHtml != null) {
                    append("<div ${styler("gl-sc", "")} data-dictionary=\"")
                    append(htmlEscape(group.dictId))
                    append("\">")
                    append(structuredHtml)
                    append("</div>")
                } else {
                    append("<div ${styler("gl-gloss", "")}>")
                    append(htmlEscape(sense.definition).replace("\n", "<br>"))
                    append("</div>")
                }
                val label = buildList {
                    if (defIdx == 0) add(group.source)
                    if (sense.pos.isNotBlank()) add(sense.pos)
                }.joinToString(" · ")
                if (label.isNotEmpty()) {
                    append("<div ${styler("gl-pos gl-hint", "")}>")
                    append(htmlEscape(label))
                    append("</div>")
                }
                closeSense()
            }
        }

        val flatSenses = entries.flatMap { it.senses }
        val defResult = defResult
        val translatedDefs = when (defResult) {
            is DefinitionResult.MachineTranslated -> defResult.translatedDefinitions
            is DefinitionResult.EnglishFallback -> defResult.translatedDefinitions
            else -> null
        }

        val nativeTargetSenses = (defResult as? DefinitionResult.Native)
            ?.targetSenses
            ?.sortedBy { it.senseOrd }
            ?.takeIf { it.isNotEmpty() }
        val isTargetDriven = targetLang != "en" && nativeTargetSenses != null

        if (isTargetDriven) {
            // Blank-pos target rows (PanLex) inherit the source-entry POS
            // only when entries agree; multi-POS source yields an empty
            // fallback so we don't mislabel verb/intj cells as NOUN.
            val fallbackPos = unambiguousFallbackPos(entries)
            val visibleTarget = nativeTargetSenses.withIndex()
                .filter { (idx, _) -> idx !in removedSenses }
            if (visibleTarget.isEmpty()) {
                // The flat fallback already contains the imported lines —
                // emitting it after the imported blocks would duplicate them.
                if (!hasImportedRows) appendFlatFallback()
                return
            }
            visibleTarget.forEach { (idx, target) ->
                val posLabels = target.pos.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?: fallbackPos
                openSense()
                append("<div ${styler("gl-gloss", "")}>")
                append(htmlEscape(target.glosses.joinToString("; ")))
                append("</div>")
                if (posLabels.isNotEmpty()) {
                    append("<div ${styler("gl-pos gl-hint", "")}>")
                    append(htmlEscape(posLabels.joinToString(" · ")))
                    append("</div>")
                }
                renderMisc(target.misc)?.let { misc ->
                    append("<div ${styler("gl-misc gl-hint", "")}>")
                    append(htmlEscape(misc))
                    append("</div>")
                }
                target.examples.withIndex()
                    .filter { (eIdx, _) -> (idx to eIdx) !in removedExamples }
                    .forEach { (_, ex) ->
                        append("<div ${styler("gl-ex gl-hint", "")}>")
                        append(htmlEscape(ex.text))
                        if (ex.translation.isNotBlank()) {
                            append("<div ${styler("gl-ex-tr", "")}>")
                            append(htmlEscape(ex.translation))
                            append("</div>")
                        }
                        append("</div>")
                    }
                closeSense()
            }
            return
        }

        val targetByOrd = if (defResult is DefinitionResult.Native)
            defResult.targetSenses.associateBy { it.senseOrd } else null
        val visibleSenses = flatSenses.withIndex().filter { (idx, s) ->
            s.targetDefinitions.isNotEmpty() && idx !in removedSenses
        }
        if (visibleSenses.isEmpty()) {
            // User stripped every sense — the renderer hides the × on
            // the last visible sense, but defensive: emit the fallback
            // so the card never goes empty. Skipped when imported blocks
            // already rendered (the fallback would duplicate them).
            if (!hasImportedRows) appendFlatFallback()
            return
        }
        visibleSenses.forEach { (flatIdx, sense) ->
            val target = targetByOrd?.get(flatIdx)
            val posLabels = (target?.pos ?: sense.partsOfSpeech).filter { it.isNotBlank() }
            val gloss = target?.glosses?.joinToString("; ")
                ?: translatedDefs?.getOrNull(flatIdx)
                ?: sense.targetDefinitions.joinToString("; ")
            openSense()
            append("<div ${styler("gl-gloss", "")}>")
            append(htmlEscape(gloss))
            append("</div>")
            if (posLabels.isNotEmpty()) {
                append("<div ${styler("gl-pos gl-hint", "")}>")
                append(htmlEscape(posLabels.joinToString(" · ")))
                append("</div>")
            }
            renderMisc(sense.misc)?.let { misc ->
                append("<div ${styler("gl-misc gl-hint", "")}>")
                append(htmlEscape(misc))
                append("</div>")
            }
            sense.examples.withIndex()
                .filter { (eIdx, _) -> (flatIdx to eIdx) !in removedExamples }
                .forEach { (eIdx, ex) ->
                    val tr = exampleTranslations[flatIdx to eIdx] ?: ex.translation
                    append("<div ${styler("gl-ex gl-hint", "")}>")
                    append(htmlEscape(ex.text))
                    if (tr.isNotBlank()) {
                        append("<div ${styler("gl-ex-tr", "")}>")
                        append(htmlEscape(tr))
                        append("</div>")
                    }
                    append("</div>")
                }
            closeSense()
        }
    }

    companion object {
        /**
         * The sentence card's shape. The sentence pipeline never holds a
         * [DictionaryEntry] for the highlighted word — only the flattened
         * [SenseDisplay] rows that crossed the enrichment transport — so the
         * DEFINITION source describes the field by rebuilding the two sense
         * lists [panelHtml] reads: imported rows regrouped by dictionary the
         * way the sentence sheet's preview does ([importedGroupsFromSenses]),
         * pack rows as one-gloss [Sense]s carrying their POS and misc. The
         * rows already reflect the target-language tier (the transport's
         * builder applied it), so the description carries no [defResult]
         * and no curation: for the same senses it renders byte-identically
         * to an uncurated word card. A blank pack row becomes a
         * definition-less sense, which the renderer skips exactly as it
         * skips an entry sense with no target definitions. [fallback] is the
         * flat meaning, rendered only when [senses] is empty.
         */
        fun fromSenses(
            word: String,
            senses: List<SenseDisplay>,
            fallback: String,
        ): WordCardDefinition {
            if (senses.isEmpty()) return WordCardDefinition(fallback = fallback)
            val packSenses = senses.filterNot { it.imported }.map { s ->
                Sense(
                    targetDefinitions = listOf(s.definition).filter { it.isNotBlank() },
                    partsOfSpeech = s.pos,
                    tags = emptyList(),
                    restrictions = emptyList(),
                    info = emptyList(),
                    misc = s.misc,
                )
            }
            val entry = DictionaryEntry(
                slug = word,
                isCommon = null,
                tags = emptyList(),
                jlpt = emptyList(),
                headwords = emptyList(),
                senses = packSenses,
                importedSenses = importedGroupsFromSenses(senses),
            )
            return WordCardDefinition(fallback = fallback, entry = entry)
        }
    }
}
