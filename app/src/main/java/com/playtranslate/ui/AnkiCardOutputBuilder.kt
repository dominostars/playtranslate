package com.playtranslate.ui

import com.playtranslate.model.FrequencyTag

/**
 * Builds [CardOutputs] from the sheet-side state. The send-time
 * dispatcher passes the resulting struct to
 * [AnkiCardTypeMapper.assembleNote] alongside the user's saved field
 * mapping; each [ContentSource] picks one string from the outputs.
 *
 * Word-card definition HTML is supplied by the caller (the word sheet)
 * because it depends on per-card curation state (`removedSenses`,
 * `removedExamples`, `removedTatoebaIdx`). Sentence-card definition is
 * derived inline from the first highlighted word's `meaning`.
 */
object AnkiCardOutputBuilder {

    /** A small, always-visible credit line appended to the audio field so a
     *  CC-BY/CC-BY-SA recording's attribution travels with redistributed cards. */
    private fun audioCreditHtml(credit: String?): String =
        if (credit.isNullOrEmpty()) {
            ""
        } else {
            "<div style=\"font-size:0.7em;opacity:0.6;\">" +
                credit.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") +
                "</div>"
        }

    /**
     * Builds outputs from a sentence sheet's current state. The caller
     * may supply pre-rendered [examplesHtml] (Tatoeba pairs for the
     * highlighted word) — usually available only when the send is
     * routed through [WordAnkiReviewSheet], which carries the
     * surrounding word-lookup context. Sentence-only flows
     * ([AnkiReviewBottomSheet]) pass empty.
     */
    fun forSentence(
        cardData: SentenceAnkiContentView.CardData,
        /** The sentence's single analysis — the furigana renderer draws it.
         *  Null degrades SENTENCE_FURIGANA to the plain+<b> form. */
        annotation: com.playtranslate.language.SentenceAnnotation? = null,
        imageFilename: String?,
        examplesHtml: String = "",
        audioFilename: String? = null,
        /** CC credit for the sentence recording, co-located with the sentence audio field. */
        sentenceAudioCredit: String? = null,
        /** CC credit for per-word recordings, co-located with the word audio field so it
         *  travels even when the card type maps no sentence-audio field. */
        wordAudioCredit: String? = null,
        /** Per-target-word audio filenames keyed by word. Threaded into
         *  [SentenceAnkiHtmlBuilder.buildWordsHtmlWith] so each word's row
         *  in WORDS_TABLE gets a `[sound:…]` tag. [CardOutputs.wordAudio]
         *  stays empty — it's a single-string field that maps to one
         *  Anki field via [ContentSource.WORD_AUDIO] and can't carry
         *  per-word tags meaningfully. */
        wordAudioFilenames: Map<String, String> = emptyMap(),
        /** Localized Common-pill label + POS/misc localizers for the
         *  words-table cells — same contract as
         *  [PtNoteBuilder.forSentence]; defaults keep JVM tests
         *  context-free, production callers inject Context helpers. */
        commonLabel: String = "Common",
        localizePos: (List<String>) -> String = { it.joinToString(" · ") },
        renderMisc: (List<String>) -> String? = { null },
        /** scRowid -> glossary JSON for the words table's structured
         *  senses; empty = flat rows. */
        structuredGlossaries: Map<Long, String> = emptyMap(),
        /** dictId -> raw styles.css; scoped and inlined as <style> when a
         *  structured sense from that dictionary renders (Tier 2). */
        dictStyles: Map<String, String> = emptyMap(),
    ): CardOutputs {
        val firstHighlighted = cardData.words.firstOrNull {
            it.word in cardData.selectedWords
        }
        // EXPRESSION: highlighted word's plain headword text (no
        // brackets). For template fields that render `{{Expression}}`
        // raw — like Lapis's vocab-card front — putting brackets here
        // shows literal `[reading]` markup. Templates that want
        // furigana on the Expression use EXPRESSION_FURIGANA below.
        // Falls back to the whole sentence when nothing is highlighted
        // so the field is non-empty (matters when EXPRESSION lands at
        // the first field — Anki's note-identity slot — as it does on
        // Lapis/Senren-style note types).
        val expression = htmlEscape(
            firstHighlighted?.word
                ?: cardData.source.replace(Regex("[\\n\\r]+"), " ").trim()
        )
        // EXPRESSION_FURIGANA: per-kanji bracketed headword for fields
        // wrapped with `{{furigana:}}` (Lapis ExpressionFurigana,
        // Migaku Target Word, etc.). Empty when nothing is highlighted.
        val expressionFurigana = firstHighlighted?.let {
            SentenceAnkiHtmlBuilder.buildExpressionFurigana(
                word = it.word, reading = it.reading,
                sourceLangId = cardData.sourceLangId,
            )
        }.orEmpty()
        val reading = htmlEscape(firstHighlighted?.reading.orEmpty())
        // DEFINITION: empty when nothing's highlighted. assembleNote's
        // sentence-mode fold (not yet implemented for this flow; see
        // plan §3 — left explicit "" so the WORDS_TABLE is what a user
        // would map their main definition field to instead).
        val definition = firstHighlighted?.meaning?.let { m ->
            m.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { htmlEscape(it.trimStart()) }
        }.orEmpty()
        // starsString emits only ★ glyphs — safe by construction.
        val frequency = firstHighlighted?.let {
            SentenceAnkiHtmlBuilder.starsString(it.freqScore)
        }.orEmpty()
        // SENTENCE: plain sentence text with `<b>` around highlighted
        // surface forms. For fields rendered raw via `{{Sentence}}` —
        // JPMN's Sentence on every card type, Lapis's Sentence when no
        // filter wraps it — putting bracket syntax here shows literal
        // markup. The bracketed variant goes in SENTENCE_FURIGANA below.
        val sentenceHtml = SentenceAnkiHtmlBuilder.buildSentencePlain(
            text = cardData.source,
            words = cardData.words,
            highlightedWords = cardData.selectedWords,
        )
        // SENTENCE_FURIGANA: bracketed + `<wbr>` variant with `<b>`
        // around the highlighted bracketed block (matches JPMN's
        // `<b> 偽者[にせもの]</b>` shape). For fields wrapped with
        // `{{furigana:}}` (Lapis SentenceFurigana, JPMN SentenceReading,
        // Migaku Sentence).
        val sentenceFuriganaHtml = SentenceAnkiHtmlBuilder.buildSentenceFurigana(
            text = cardData.source,
            words = cardData.words,
            highlightedWords = cardData.selectedWords,
            sourceLangId = cardData.sourceLangId,
            annotation = annotation,
        )
        val translationHtml = htmlEscape(cardData.target).replace(Regex("[\\n\\r]+"), "<br>")
        val sortedWords = if (cardData.selectedWords.isNotEmpty()) {
            cardData.words.sortedByDescending { it.word in cardData.selectedWords }
        } else cardData.words
        val wordsHtml = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            sortedWords,
            cardData.selectedWords,
            styler = inlineStyler,
            wordAudioFilenames = wordAudioFilenames,
            commonLabel = commonLabel,
            localizePos = localizePos,
            renderMisc = renderMisc,
            structuredGlossaries = structuredGlossaries,
            dictStyles = dictStyles,
        )
        // Mapped audio fields (`ExpressionAudio`, `WordAudio`, `Word Audio`
        // in Lapis/JPMN/Migaku) bind to `ContentSource.WORD_AUDIO`, which
        // reads this field. For sentence mode with multiple target words,
        // concatenate every uploaded per-word sound tag so any template
        // wiring an audio field gets a play button per target word.
        // Sentence order (cardData.words is already in source order) so
        // the buttons mirror the words' position in the sentence — the
        // inline tags in WORDS_TABLE preserve the same order.
        val wordAudioBlock = cardData.words
            .asSequence()
            .filter { it.word in cardData.selectedWords && it.word in wordAudioFilenames }
            .joinToString("") { "[sound:${wordAudioFilenames[it.word]}]" }
        return CardOutputs(
            expression = expression,
            expressionFurigana = expressionFurigana,
            reading = reading,
            sentence = sentenceHtml,
            sentenceFurigana = sentenceFuriganaHtml,
            sentenceTranslation = translationHtml,
            picture = pictureHtml(imageFilename),
            wordAudio = wordAudioBlock + audioCreditHtml(wordAudioCredit),
            sentenceAudio = soundTag(audioFilename) + audioCreditHtml(sentenceAudioCredit),
            definition = definition,
            examples = examplesHtml,
            frequency = frequency,
            partOfSpeech = "",
            wordsTable = wordsHtml,
            // Yomitan pitch/frequency fields source from the first highlighted
            // word (the card's target word), matching how expression/reading/
            // definition above pick it. Empty when nothing is highlighted.
            pitchPosition = firstHighlighted
                ?.let { AnkiFrequencyFormat.pitchPositions(it.pitch) }.orEmpty(),
            frequencyValues = firstHighlighted
                ?.let { AnkiFrequencyFormat.frequencyValuesHtml(it.freqScore, it.frequencies) }.orEmpty(),
            frequencyStylized = firstHighlighted
                ?.let { AnkiFrequencyFormat.frequenciesStylizedJpmn(it.freqScore, it.frequencies) }.orEmpty(),
            frequencyHarmonic = firstHighlighted
                ?.let { AnkiFrequencyFormat.harmonicMean(it.frequencies.mapNotNull { f -> f.value })?.toString() }
                .orEmpty(),
            // Flag values for sentence-mode sends:
            //  - SENTENCE_CARD_FLAG always fires (Lapis/JPMN signal).
            //  - TARGETED_SENTENCE_CARD_FLAG fires only when there's a
            //    bolded word to target (JPMN's IsTargetedSentenceCard
            //    requires a bolded word in the Sentence field, which
            //    wrapHighlighted only produces with selectedWords).
            //  - VOCABULARY_CARD_FLAG stays empty — sentence sends are
            //    not vocab-variant cards.
            //  - ALWAYS_ON_MARKER fires for users who manually mapped a
            //    flag field to always-on.
            vocabularyCardFlag = "",
            sentenceCardFlag = "x",
            targetedSentenceCardFlag = if (cardData.selectedWords.isNotEmpty()) "x" else "",
            alwaysOnMarker = "x",
        )
    }

    /**
     * Builds outputs from a word-card send. [definitionHtml] is the
     * pipeline's inline-styled rendering of the caller's
     * [WordCardDefinition] (sheet curation included); [examplesHtml] is
     * the sheet's pre-rendered Tatoeba list (honoring `removedTatoebaIdx`).
     */
    fun forWord(
        word: String,
        reading: String,
        pos: String,
        definitionHtml: String,
        freqScore: Int,
        /** Pitch downsteps + per-dict frequencies for the looked-up word
         *  (from `entry.headwordDisplay(word)`); required so a word-send site
         *  that forgets to thread them is a compile error, not silent-empty. */
        pitch: List<Int>,
        frequencies: List<FrequencyTag>,
        imageFilename: String?,
        examplesHtml: String = "",
        sourceLangId: com.playtranslate.language.SourceLangId =
            com.playtranslate.language.SourceLangId.JA,
        audioFilename: String? = null,
        /** CC credit for a Commons recording, co-located with the word audio field. */
        audioCredit: String? = null,
    ): CardOutputs = CardOutputs(
        // EXPRESSION: plain headword text — for fields rendered raw
        // via `{{Expression}}` (Lapis vocab-card front, Hint, etc.).
        expression = htmlEscape(word),
        // EXPRESSION_FURIGANA: per-kanji bracketed headword for fields
        // wrapped with `{{furigana:}}` (Lapis ExpressionFurigana,
        // Migaku Target Word, etc.).
        expressionFurigana = SentenceAnkiHtmlBuilder.buildExpressionFurigana(
            word = word, reading = reading, sourceLangId = sourceLangId,
        ),
        reading = htmlEscape(reading),
        sentence = "",
        sentenceFurigana = "",
        sentenceTranslation = "",
        picture = pictureHtml(imageFilename),
        wordAudio = soundTag(audioFilename) + audioCreditHtml(audioCredit),
        sentenceAudio = "",
        definition = definitionHtml,
        examples = examplesHtml,
        // starsString emits only ★ glyphs — safe.
        frequency = SentenceAnkiHtmlBuilder.starsString(freqScore),
        partOfSpeech = htmlEscape(pos),
        wordsTable = "",
        // Yomitan pitch/frequency fields (JA). Empty for languages/words with
        // no pitch or frequency data, so the mapped field just stays blank.
        pitchPosition = AnkiFrequencyFormat.pitchPositions(pitch),
        frequencyValues = AnkiFrequencyFormat.frequencyValuesHtml(freqScore, frequencies),
        frequencyStylized = AnkiFrequencyFormat.frequenciesStylizedJpmn(freqScore, frequencies),
        frequencyHarmonic =
            AnkiFrequencyFormat.harmonicMean(frequencies.mapNotNull { it.value })?.toString().orEmpty(),
        // Flag values for word-mode sends: VOCABULARY_CARD_FLAG fires
        // (Migaku/Lapis vocab-variant signal); sentence flags stay
        // empty since word cards aren't sentence-variant. ALWAYS_ON
        // fires for manually-mapped always-on flag fields.
        vocabularyCardFlag = "x",
        sentenceCardFlag = "",
        targetedSentenceCardFlag = "",
        alwaysOnMarker = "x",
    )

}
