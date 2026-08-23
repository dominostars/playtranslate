package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import com.playtranslate.model.FrequencyTag

/**
 * Builds [PtNote] payloads for the default field-based PlayTranslate
 * note types — the [AnkiCardOutputBuilder] counterpart for the
 * "Default (PlayTranslate)" path. Values here are canonical-field
 * strings [PtModels.assemble] maps onto the model's actual field list.
 *
 * Differences from the structured (third-party card type) outputs, on
 * purpose:
 *  - audio fields carry the bare `[sound:]` tag; the CC credit lives
 *    in its own `AudioCredit` field (our templates own its rendering),
 *    instead of being concatenated after the tag;
 *  - Definition/Examples carry [classStyler]-built HTML — the model
 *    CSS defines the `gl-*` classes, so fields stay free of inline
 *    style noise and are saner to edit in Anki;
 *  - the words table bakes pitch contours (`renderPitch = true`) like
 *    the retired v005 back did — the raw-downstep + template-JS path
 *    applies only to the word card's main contour.
 */
internal object PtNoteBuilder {

    fun forWord(
        word: String,
        reading: String,
        pos: String,
        /** Class-styled per-sense definition HTML — senses only, the
         *  Tatoeba "More examples" block goes in [examplesHtml]. */
        definitionHtml: String,
        /** Class-styled "More examples" block, carrying its own
         *  localized `gl-section` header; "" when there are none. */
        examplesHtml: String,
        freqScore: Int,
        pitch: List<Int>,
        frequencies: List<FrequencyTag>,
        imageFilename: String?,
        audioFilename: String?,
        audioCredit: String?,
    ): PtNote.Word = PtNote.Word(
        expression = htmlEscape(word),
        reading = htmlEscape(reading),
        // Raw downsteps ("0,2") — the template's pitch JS draws the
        // contour from this plus Reading at review time.
        pitchPosition = AnkiFrequencyFormat.pitchPositions(pitch),
        // Populated but no longer rendered by the v002 template (POS
        // belongs to each sense now); kept for users who reference it
        // in their own templates.
        partOfSpeech = htmlEscape(pos),
        definition = definitionHtml,
        examples = examplesHtml,
        // Chips row (stars + source: display chips); the template's
        // .pt-meta flex row owns layout. NOT frequencyValuesHtml — that
        // <ul> shape is the structured path's Lapis pin.
        frequency = AnkiFrequencyFormat.frequencyChipsHtml(freqScore, frequencies),
        picture = pictureHtml(imageFilename),
        wordAudio = soundTag(audioFilename),
        audioCredit = creditHtml(audioCredit),
    )

    fun forSentence(
        cardData: SentenceAnkiContentView.CardData,
        /** The sentence's [com.playtranslate.language.SentenceAnnotation] —
         *  the single analysis the furigana renderer draws. Null (tests,
         *  legacy callers) degrades the furigana field to the plain+<b>
         *  form; never a re-derived reading. */
        annotation: com.playtranslate.language.SentenceAnnotation? = null,
        imageFilename: String?,
        audioFilename: String?,
        /** Per-target-word audio filenames keyed by word; rendered as
         *  inline `[sound:]` tags in the words-table rows (there is no
         *  separate per-word audio field — one field can't carry
         *  N play buttons meaningfully outside the table). */
        wordAudioFilenames: Map<String, String>,
        /** Aggregate credit (sentence + per-word Commons clips). */
        audioCredit: String?,
        /** Localized `.gl-section` header baked ahead of the words table
         *  (the template can't localize); "" skips the header. Defaults
         *  keep JVM/Robolectric tests and pre-l10n callers compiling —
         *  production passes real localized values. */
        wordsSectionHeader: String = "",
        /** Localized Common-pill label for the word cells' meta rows. */
        commonLabel: String = "Common",
        /** POS localizer for non-imported senses (Context::localizePos). */
        localizePos: (List<String>) -> String = { it.joinToString(" · ") },
        /** Misc register-tag renderer (Context::renderMiscText). */
        renderMisc: (List<String>) -> String? = { null },
        /** scRowid -> glossary JSON for the words table's structured
         *  senses; empty = flat rows. */
        structuredGlossaries: Map<Long, String> = emptyMap(),
        /** dictId -> raw styles.css; scoped and inlined as <style> when a
         *  structured sense from that dictionary renders (Tier 2). */
        dictStyles: Map<String, String> = emptyMap(),
    ): PtNote.Sentence {
        val firstHighlighted = cardData.words.firstOrNull {
            it.word in cardData.selectedWords
        }
        // Bracket furigana only for languages with a reading-annotation
        // path. Everything else leaves the field EMPTY — not plain
        // text — so the template's {{^SentenceFurigana}} branch falls
        // back to the Sentence field (which carries the <b> highlights).
        val hasFurigana = cardData.sourceLangId == SourceLangId.JA ||
            cardData.sourceLangId == SourceLangId.ZH ||
            cardData.sourceLangId == SourceLangId.ZH_HANT
        val furigana = if (hasFurigana) {
            SentenceAnkiHtmlBuilder.buildSentenceFurigana(
                text = cardData.source,
                words = cardData.words,
                highlightedWords = cardData.selectedWords,
                sourceLangId = cardData.sourceLangId,
                // Our own template's JS reads the data-pt-* word wrappers
                // (front tooltip pitch, back tap-to-cell scroll); the
                // structured path never sets this.
                wrapWords = true,
                annotation = annotation,
            )
        } else ""
        // Highlighted words sort to the top of the table, matching the
        // v005 back and the structured WORDS_TABLE output.
        val sorted = if (cardData.selectedWords.isNotEmpty()) {
            cardData.words.sortedByDescending { it.word in cardData.selectedWords }
        } else cardData.words
        val wordsHtml = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            sorted, cardData.selectedWords, classStyler,
            wordAudioFilenames, renderPitch = true,
            commonLabel = commonLabel,
            localizePos = localizePos,
            renderMisc = renderMisc,
            structuredGlossaries = structuredGlossaries,
            dictStyles = dictStyles,
        )
        return PtNote.Sentence(
            sentence = SentenceAnkiHtmlBuilder.buildSentencePlain(
                text = cardData.source,
                words = cardData.words,
                highlightedWords = cardData.selectedWords,
            ),
            sentenceFurigana = furigana,
            translation = htmlEscape(cardData.target)
                .replace(Regex("[\\n\\r]+"), "<br>"),
            // Plain first-highlighted word for the "already in Anki"
            // field-name search; "" when nothing is highlighted (field 0
            // Sentence owns sort/dup duty, so no fallback needed here).
            targetWord = htmlEscape(firstHighlighted?.word.orEmpty()),
            // The localized section header is baked into the field (the
            // template can't localize), but only above a non-empty table —
            // the template's {{#WordsTable}} gate must keep an empty field
            // section-free.
            wordsTable = if (wordsHtml.isEmpty() || wordsSectionHeader.isEmpty()) wordsHtml
            else "<div class=\"gl-section\">${htmlEscape(wordsSectionHeader)}</div>$wordsHtml",
            picture = pictureHtml(imageFilename),
            sentenceAudio = soundTag(audioFilename),
            audioCredit = creditHtml(audioCredit),
        )
    }

    /** Escaped credit text, newline runs → `<br>`, no wrapper div —
     *  the templates' `pt-credit` block owns the styling. */
    private fun creditHtml(credit: String?): String =
        if (credit.isNullOrBlank()) ""
        else htmlEscape(credit).replace(Regex("[\\n\\r]+"), "<br>")
}
