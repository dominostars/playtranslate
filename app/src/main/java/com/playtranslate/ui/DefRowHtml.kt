package com.playtranslate.ui

import java.util.Locale
import android.content.Context
import com.playtranslate.R
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.model.ReadingRow

/**
 * A [DefRow] as the markup of one element of a definitions document. The
 * row's chrome (headword, readings, buttons, stub) is built here; its
 * definitions come from [DefinitionsDocument.contentHtml], the one sense
 * loop, so a row and every other document share it.
 *
 * Needs a themed [Context] for the strings and the chip colours, which is
 * why it lives beside the Context-free [DefinitionsDocument] and not in it.
 * Reads resources only, so it may run off the main thread.
 *
 * Every tappable element carries `data-act` with a [DefAction]; the row
 * element carries `data-row` and its DOM id, which is how the page names
 * the row a tap or a rect belongs to. `data-inert` makes a link inside the
 * row a tap on the row: a list's row is a tap target that opens its word,
 * and a link in its definitions would otherwise take that tap away.
 * `data-hold` makes a press held on the element its own action
 * ([DefAction.hold]).
 */
internal object DefRowHtml {

    /**
     * [row] as one element with the DOM id [domId]. [structured] maps a
     * sense's `scRowid` to its glossary JSON; a sense missing from it draws
     * its flat text. [sourceLang] is the language of the headword and its
     * readings, so the engine picks that language's glyphs for them.
     */
    fun html(
        ctx: Context,
        row: DefRow,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String = when (row) {
        is DefRow.Word -> word(ctx, row, domId, structured, sourceLang)
        is DefRow.More -> more(row, domId)
        is DefRow.Imported -> imported(ctx, row, domId, structured)
        is DefRow.Sense -> sense(ctx, row, domId, sourceLang)
        is DefRow.SheetWord -> sheetWord(ctx, row, domId, structured, sourceLang)
    }

    /**
     * A word of the Anki sentence sheet. Top to bottom in one element, so
     * the target's wash covers all of it: the title line (the word, its
     * reading under its accent contour, the stars, and the eye at the line's
     * end), the audio row while the word is a target, then the definition
     * ([DefRow.SheetWord] says which), kept clear of the eye's column. The row is the target toggle; the
     * audio row and its two controls, and the eye, each take their own tap.
     * Links are inert: a tap on the definition is a tap on the row.
     */
    private fun sheetWord(
        ctx: Context,
        row: DefRow.SheetWord,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String {
        val lang = htmlEscape(sourceLang)
        val sb = StringBuilder(1024)
        sb.append("<div class=\"row sw")
            .append(if (row.hidden) " stub" else if (row.target) " tgt" else "")
            .append("\" id=\"").append(domId).append("\" data-row")
        if (!row.hidden) sb.append(" data-inert data-act=\"").append(DefAction.TARGET).append('"')
        sb.append("><div class=\"swt\"><div class=\"swl\" lang=\"").append(lang).append("\">")
            .append("<span class=\"sww\">").append(htmlEscape(row.word)).append("</span>")
        if (!row.hidden) {
            // A kana word has no separate reading but may have an accent:
            // the kana is repeated as the contour's surface.
            val pitchKana = row.reading.takeIf { it.isNotBlank() }
                ?: row.word.takeIf { row.pitch.isNotEmpty() && row.word.all(Deinflector::isKana) }
            if (pitchKana != null) {
                sb.append("<span class=\"swr\">")
                    .append(PitchAccentHtml.pitchAccentHtml(pitchKana, row.pitch).ifEmpty { htmlEscape(pitchKana) })
                    .append("</span>")
            }
            if (row.stars.isNotEmpty()) {
                sb.append("<span class=\"sws\">").append(htmlEscape(row.stars)).append("</span>")
            }
        }
        sb.append("</div>")
        val (eye, eyeLabel) = if (row.hidden) {
            R.drawable.ic_visibility_off to R.string.hidden_word_show_content_description
        } else {
            R.drawable.ic_visibility to R.string.hidden_word_hide_content_description
        }
        sb.append("<button type=\"button\" class=\"swe\" data-act=\"").append(DefAction.TRAILING)
            .append("\" aria-label=\"").append(htmlEscape(ctx.getString(eyeLabel))).append("\">")
            .append(icon(ctx, eye)).append("</button></div>")
        if (row.hidden) return sb.append("</div>").toString()

        row.audio?.let { appendAudioRow(ctx, sb, it) }

        // The body previews the dictionary that leads the word's definitions
        // on the card. The built-in one leads when the user's order puts it
        // above every imported dictionary that has the word: the body is
        // then the meaning's first line, which is its first sense, and not
        // the imported dictionaries' markup under a word whose card opens
        // with another dictionary's definition.
        val builtInLeads = row.importedGroups.firstOrNull()?.belowBuiltIn == true && row.meaning.isNotBlank()
        val styled = !builtInLeads &&
            row.importedGroups.any { g -> g.senses.any { it.scRowid?.let(structured::containsKey) == true } }
        if (styled) {
            sb.append("<div class=\"swd\">")
                .append(
                    DefinitionsDocument.contentHtml(
                        WordDefinitionData(
                            word = row.word, reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                            importedGroups = row.importedGroups,
                        ),
                        structured,
                        localizePos = { it.joinToString(" \u00b7 ") },
                    ),
                )
                .append("</div>")
        } else if (row.meaning.isNotBlank()) {
            sb.append("<div class=\"swd swm\">")
                .append(htmlEscape(row.meaning.lines().firstOrNull { it.isNotBlank() } ?: row.meaning))
                .append("</div>")
        }
        return sb.append("</div>").toString()
    }

    /**
     * A target's audio row: the preview control (a ringed speaker, a spinner
     * while its audio loads, a pause while it plays), the pill naming the
     * audio source, the row's title and its switch. The whole row is the
     * switch; the preview and the pill are their own targets inside it. The
     * preview is lit while it is busy or the switch is on, the pill and the
     * title while the switch is on.
     */
    private fun appendAudioRow(ctx: Context, sb: StringBuilder, audio: DefRow.Audio) {
        val busy = audio.play != DefRow.Play.IDLE
        sb.append("<div class=\"au").append(if (audio.on) " on" else "")
            .append("\" data-act=\"").append(DefAction.AUDIO)
            .append("\" role=\"switch\" aria-checked=\"").append(audio.on).append("\">")
        sb.append("<button type=\"button\" class=\"auc").append(if (audio.on || busy) " lit" else "")
            .append("\" data-act=\"").append(DefAction.SPEAK).append("\" aria-label=\"")
            .append(htmlEscape(ctx.getString(R.string.anki_audio_preview_content_description)))
            .append("\"><span class=\"aur\">")
        sb.append(
            when (audio.play) {
                DefRow.Play.IDLE -> icon(ctx, R.drawable.ic_lens_speak)
                DefRow.Play.LOADING -> "<span class=\"spin\"></span>"
                DefRow.Play.PLAYING -> icon(ctx, R.drawable.ic_pause)
            },
        )
        sb.append("</span></button>")
        sb.append("<button type=\"button\" class=\"aup\" data-act=\"").append(DefAction.SOURCE)
            .append("\"><span>").append(htmlEscape(audio.source)).append("</span></button>")
        sb.append("<span class=\"aut\">")
            .append(htmlEscape(ctx.getString(R.string.anki_word_audio_row_title)))
            .append("</span><span class=\"tog\"></span></div>")
    }

    /**
     * The imported block of a word's page. With structured content it is the
     * dictionaries' own markup through the one sense loop, headers as the
     * dictionaries wrote them. With none (plain-text dictionaries, or their
     * styling switched off) each definition reads as a sense of the page:
     * the dictionary's name and the part of speech as its eyebrow, with a
     * hairline between them. Under a curation the removed definitions are
     * left out, a dictionary's name rides its first KEPT definition (so
     * removing the one that carried it hands it on), and each kept one
     * carries its ✕.
     */
    private fun imported(ctx: Context, row: DefRow.Imported, domId: String, structured: Map<Long, String>): String {
        val sheet = if (row.look == DefRow.SenseLook.SHEET) " sheet" else ""
        val removeLabel = ctx.getString(R.string.anki_word_remove_content_description)
        val styled = row.groups.any { g -> g.senses.any { s -> s.scRowid?.let { it in structured } == true } }
        // A tap on the text is reported only where the host asked for it,
        // and never under a curation.
        val wordTaps = row.words && row.curation == null
        val words = if (wordTaps) " data-words" else ""
        if (styled) {
            return "<div class=\"row impf$sheet\" id=\"$domId\" data-row$words>" +
                DefinitionsDocument.contentHtml(
                    WordDefinitionData(
                        word = "", reading = null, senses = emptyList(), freqScore = 0, isCommon = false,
                        importedGroups = row.groups,
                    ),
                    structured,
                    localizePos = { it.joinToString(" \u00b7 ") },
                    importedCuration = row.curation?.let {
                        DefinitionsDocument.ImportedCuration(it.removed, it.removable, removeLabel, it.base)
                    },
                    references = row.links && row.curation == null,
                    wordTaps = wordTaps,
                ) + "</div>"
        }
        val sb = StringBuilder(512)
        sb.append("<div class=\"row impl\" id=\"").append(domId).append("\" data-row").append(words).append('>')
        var flat = row.curation?.base ?: 0
        for (group in row.groups) {
            var labelled = false
            for (sense in group.senses) {
                val index = flat++
                if (row.curation?.removed?.contains(index) == true) continue
                val eyebrow = buildList {
                    if (!labelled) add(group.source)
                    if (sense.pos.isNotBlank()) add(sense.pos)
                }.joinToString(" \u00b7 ").uppercase(Locale.ROOT)
                labelled = true
                sb.append("<div class=\"sense").append(sheet).append('"')
                // Whose text this is, for a tap on it (the styled block
                // names its dictionary on its scope).
                if (wordTaps && group.dictId.isNotEmpty()) {
                    sb.append(" data-dict=\"").append(htmlEscape(group.dictId)).append('"')
                }
                sb.append('>')
                appendSense(
                    sb, null, eyebrow,
                    group.accentColor.takeIf { row.look == DefRow.SenseLook.PAGE },
                    sense.definition, null, emptyList(), removeLabel,
                    removeAct = if (row.curation?.removable == true) DefAction.removeItem(index) else null,
                )
                sb.append("</div>")
            }
        }
        return sb.append("</div>").toString()
    }

    private fun sense(ctx: Context, row: DefRow.Sense, domId: String, sourceLang: String): String {
        val sb = StringBuilder(512)
        sb.append("<div class=\"row sense")
            .append(if (row.look == DefRow.SenseLook.SHEET) " sheet" else "")
            .append(if (row.number != null) " num" else "")
            .append("\" id=\"").append(domId).append("\" data-row")
            .append(if (row.words) " data-words" else "").append('>')
        appendSense(
            sb, row.number, row.pos, row.posColor, row.gloss, row.misc, row.examples,
            ctx.getString(R.string.anki_word_remove_content_description),
            removeAct = if (row.removable) DefAction.REMOVE else null,
            exampleLang = sourceLang.takeIf { row.words && it.isNotEmpty() },
        )
        return sb.append("</div>").toString()
    }

    /** What a sense holds: the number column, then the eyebrow, the gloss,
     *  the register note and each example behind its rail, then the ✕ that
     *  removes it ([removeAct], null for none). An example's own ✕ sits
     *  beside it. [exampleLang] marks each example's sentence as written in
     *  that language (`data-lang`, which changes nothing of how it draws):
     *  what tells a tap on it from a tap on the gloss. */
    private fun appendSense(
        sb: StringBuilder,
        number: Int?,
        pos: String,
        posColor: Int?,
        gloss: String,
        misc: String?,
        examples: List<DefRow.Example>,
        removeLabel: String,
        removeAct: String?,
        exampleLang: String? = null,
    ) {
        val exampleOpen = if (exampleLang == null) {
            "<div class=\"ex\"><div class=\"exi\"><div class=\"exs\">"
        } else {
            "<div class=\"ex\"><div class=\"exi\"><div class=\"exs\" data-lang=\"" + htmlEscape(exampleLang) + "\">"
        }
        number?.let {
            sb.append("<div class=\"sn\">").append(String.format(Locale.getDefault(), "%d", it)).append("</div>")
        }
        sb.append("<div class=\"sb\">")
        if (pos.isNotEmpty()) {
            sb.append("<div class=\"sp\"")
            posColor?.let { sb.append(" style=\"color:").append(DefinitionsDocument.cssHex(it)).append('"') }
            sb.append('>').append(htmlEscape(pos)).append("</div>")
        }
        sb.append("<div class=\"sg\">").append(htmlEscape(gloss)).append("</div>")
        misc?.let { sb.append("<div class=\"sm\">").append(htmlEscape(it)).append("</div>") }
        for (ex in examples) {
            sb.append(exampleOpen).append(htmlEscape(ex.text)).append("</div>")
            if (ex.translation.isNotBlank()) {
                sb.append("<div class=\"ext").append(if (ex.italic) " it" else "").append("\">")
                    .append(htmlEscape(ex.translation)).append("</div>")
            }
            sb.append("</div>")
            ex.removeIndex?.let { appendRemove(sb, DefAction.removeItem(it), removeLabel) }
            sb.append("</div>")
        }
        sb.append("</div>")
        removeAct?.let { appendRemove(sb, it, removeLabel) }
    }

    private fun appendRemove(sb: StringBuilder, act: String, label: String) {
        sb.append("<button type=\"button\" class=\"sx\" data-act=\"").append(act)
            .append("\" aria-label=\"").append(htmlEscape(label)).append("\">\u2715</button>")
    }

    private fun more(row: DefRow.More, domId: String): String {
        val sb = StringBuilder()
        sb.append("<div class=\"row more\" id=\"").append(domId).append("\" data-row data-act=\"")
            .append(DefAction.MORE).append("\">")
        return sb.append(htmlEscape(row.text)).append("</div>").toString()
    }

    private fun word(
        ctx: Context,
        row: DefRow.Word,
        domId: String,
        structured: Map<Long, String>,
        sourceLang: String,
    ): String {
        val d = row.data
        val stub = row.trailing == DefRow.Trailing.SHOW
        // Every reading in common-use order, or the lone one: the same rule,
        // and the same kana-only case, as the native cell this replaces.
        val readings = d.readingRows.ifEmpty {
            val inline = d.reading?.takeIf { it.isNotEmpty() }
                ?: d.word.takeIf { d.pitch.isNotEmpty() && d.word.all(Deinflector::isKana) }
            if (inline != null) listOf(ReadingRow(d.word, inline, d.pitch, false)) else emptyList()
        }
        // Kana-only: the reading would repeat the title, so the title itself
        // carries the accent.
        val kanaOnly = readings.size == 1 && readings[0].reading == d.word

        val sb = StringBuilder(1024)
        sb.append("<div class=\"row cell").append(if (stub) " stub" else "")
            .append("\" id=\"").append(domId).append("\" data-row")
        // A preview's links are the row's tap; a full row's are live.
        if (!row.full) sb.append(" data-inert")
        if (row.opens) sb.append(" data-act=\"").append(DefAction.OPEN).append('"')
        // A head that keeps room at its end for the chevron (op).
        sb.append("><div class=\"head").append(if (row.opens) " op" else "")
            .append("\"><div class=\"title\" lang=\"").append(htmlEscape(sourceLang)).append("\">")
        sb.append("<span class=\"w\">")
        val titlePitch = if (!stub && kanaOnly) PitchAccentHtml.pitchAccentHtml(d.word, readings[0].pitch) else ""
        sb.append(titlePitch.ifEmpty { htmlEscape(d.word) }).append("</span>")
        if (!stub && readings.size == 1 && !kanaOnly) appendReading(sb, readings[0], bold = false)
        sb.append("</div>")

        // The buttons are one group: it stands beside the title while the
        // headword fits there, and under it, at the line's end, when it
        // does not (the page's rule, ROW_CSS). No button, no group.
        val acts = StringBuilder()
        row.explain?.let { explain ->
            if (stub) {
                // Keeps its place like the speak column, so the eye does not
                // move when the row is toggled.
                acts.append("<span class=\"act explain ghost\"></span>")
            } else {
                acts.append("<button class=\"act explain").append(if (explain.answer != null) " on" else "")
                    .append("\" data-act=\"").append(DefAction.EXPLAIN)
                    .append("\" aria-label=\"").append(htmlEscape(ctx.getString(R.string.cd_word_explain)))
                    .append("\">")
                // The whole wait: nothing else of the row moves until there
                // is something to read.
                if (explain.busy) acts.append("<span class=\"spin\"></span>")
                else acts.append(icon(ctx, R.drawable.ic_ai_info))
                acts.append("</button>")
            }
        }
        row.speak?.let { speak ->
            if (stub) {
                // The column keeps its place, so the eye does not move when
                // the row is toggled.
                acts.append("<span class=\"act speak ghost\"></span>")
            } else {
                acts.append("<button class=\"act speak\" data-act=\"").append(DefAction.SPEAK).append("\">")
                if (speak == DefRow.Speak.LOADING) acts.append("<span class=\"spin\"></span>")
                else acts.append(icon(ctx, R.drawable.ic_lens_speak))
                acts.append("</button>")
            }
        }
        row.trailing?.let { trailing ->
            val (drawable, label) = when (trailing) {
                DefRow.Trailing.ANKI -> R.drawable.ic_card_stack_add to R.string.cd_add_to_anki
                DefRow.Trailing.HIDE -> R.drawable.ic_visibility to R.string.hidden_word_hide_content_description
                DefRow.Trailing.SHOW -> R.drawable.ic_visibility_off to R.string.hidden_word_show_content_description
            }
            acts.append("<button class=\"act\" data-act=\"").append(DefAction.TRAILING).append('"')
            if (row.trailingHolds) acts.append(" data-hold")
            acts.append(" aria-label=\"").append(htmlEscape(ctx.getString(label))).append("\">")
                .append(icon(ctx, drawable)).append("</button>")
        }
        if (acts.isNotEmpty()) sb.append("<div class=\"acts\">").append(acts).append("</div>")
        if (row.opens) {
            sb.append("<span class=\"act chev\">").append(icon(ctx, R.drawable.ic_chevron_right)).append("</span>")
        }
        sb.append("</div>")
        if (stub) return sb.append("</div>").toString()

        if (readings.size > 1) {
            sb.append("<div class=\"rds\" lang=\"").append(htmlEscape(sourceLang)).append("\">")
            readings.forEach { appendReading(sb, it, bold = it.bolded) }
            sb.append("</div>")
        }
        if (d.inflectedForms.isNotEmpty()) {
            // Capped, so a lemma seen in many forms cannot grow the row
            // without bound; the rest collapse into one "+N more" line.
            val (shown, overflow) = capInflectionForms(d.inflectedForms)
            val lines = shown.map { InflectionChain.format(ctx, it) } +
                listOfNotNull(ctx.getString(R.string.inflection_more, overflow).takeIf { overflow > 0 })
            sb.append("<div class=\"infl\">")
                .append(lines.joinToString("<br>") { htmlEscape(it) }).append("</div>")
        }

        row.explain?.answer?.let { appendExplainAnswer(ctx, sb, it) }

        val body = body(ctx, row, structured)
        if (body.isNotEmpty()) sb.append("<div class=\"body\">").append(body).append("</div>")
        return sb.append("</div>").toString()
    }

    /**
     * The definitions under a word's header: the warning label and the meta
     * chips, then the word's dictionaries in the user's order, the imported
     * ones and the pack's own senses where that order puts the built-in
     * dictionary ([DefinitionsDocument.contentHtml]), or the row's
     * placeholder when the word has none of either. Every
     * dictionary's definitions are drawn in full, in a list's row as in the
     * lens's, each dictionary under its title, and a dictionary the user has
     * shut in this row ([DefRow.Word.shutDictionaries]) is its title alone:
     * that, and no cap, is what keeps one long entry from out-sizing a list.
     * Where the row draws an imported dictionary, the pack's own senses are
     * one more dictionary under a title of their own; alone they have none,
     * and are drawn whatever the row was told is shut.
     */
    private fun body(ctx: Context, row: DefRow.Word, structured: Map<Long, String>): String {
        val d = row.data
        val localize: (List<String>) -> String = { ctx.localizePos(it) }
        val none = WordDefinitionData(word = "", reading = null, senses = emptyList(), freqScore = 0, isCommon = false)
        val sb = StringBuilder()
        val chips = styledMetaChips(ctx, d)
        if (chips.isNotEmpty() || !row.label.isNullOrBlank()) {
            sb.append(DefinitionsDocument.contentHtml(none, emptyMap(), localize, metaChips = chips, label = row.label))
        }
        // Every dictionary in ONE call: the order is its to draw, and a
        // dictionary's links are numbered over the call.
        sb.append(
            DefinitionsDocument.contentHtml(
                none.copy(senses = d.senses, importedGroups = d.importedGroups), structured, localize,
                showMisc = true, renderMisc = { ctx.renderMiscText(it) },
                collapse = DefinitionsDocument.ImportedCollapse(
                    row.shutDictionaries,
                    arrow = VectorDrawableSvg.svg(ctx.resources, R.drawable.ic_chevron_right, "dict-a"),
                    ownTitle = ctx.getString(R.string.dictionary_title_builtin),
                ),
                // A link opens its word as the row opens its own:
                // live where the row's links are and the row opens.
                references = row.full && row.opens,
            ),
        )
        if (d.senses.none { !it.imported } && d.importedGroups.isEmpty() && row.emptyText != null) {
            sb.append("<div class=\"empty\">").append(htmlEscape(row.emptyText)).append("</div>")
        }
        return sb.toString()
    }

    /**
     * What came of a row's explain question ([DefRow.Answer]), between the
     * row's head and its definitions: there only once the user asked, and
     * put away by its own ✕. The title line with the ✕ at its end, the
     * text, the footer under an answer, Try again after a failure, and a
     * hairline (the block's own) where the definitions begin.
     */
    private fun appendExplainAnswer(ctx: Context, sb: StringBuilder, answer: DefRow.Answer) {
        sb.append("<div class=\"xp\"><div class=\"xp-h\"><span class=\"xp-t\">")
            .append(htmlEscape(answer.title)).append("</span>")
            .append("<button type=\"button\" class=\"xp-x\" data-act=\"").append(DefAction.EXPLAIN_CLOSE)
            .append("\" aria-label=\"").append(htmlEscape(ctx.getString(R.string.cd_ask_close))).append("\">")
            .append(icon(ctx, R.drawable.ic_close)).append("</button></div>")
        sb.append("<div class=\"xp-a").append(if (answer.muted) " mu" else "").append("\">")
            .append(htmlEscape(answer.text)).append("</div>")
        answer.footer?.let { sb.append("<div class=\"xp-f\">").append(htmlEscape(it)).append("</div>") }
        if (answer.retry) {
            sb.append("<button type=\"button\" class=\"xp-r\" data-act=\"").append(DefAction.EXPLAIN_RETRY)
                .append("\">").append(htmlEscape(ctx.getString(R.string.ask_retry))).append("</button>")
        }
        sb.append("</div>")
    }

    private fun appendReading(sb: StringBuilder, row: ReadingRow, bold: Boolean) {
        sb.append("<span class=\"rd").append(if (bold) " b" else "").append("\">")
            .append(PitchAccentHtml.pitchAccentHtml(row.reading, row.pitch).ifEmpty { htmlEscape(row.reading) })
            .append("</span>")
    }

    private fun icon(ctx: Context, drawable: Int): String =
        VectorDrawableSvg.svg(ctx.resources, drawable, "ic")
}
