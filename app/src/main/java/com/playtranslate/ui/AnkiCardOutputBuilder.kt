package com.playtranslate.ui

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

    /** Builds outputs from a sentence sheet's current state. */
    fun forSentence(
        cardData: SentenceAnkiContentFragment.CardData,
        imageFilename: String?,
    ): CardOutputs {
        val firstHighlighted = cardData.words.firstOrNull {
            it.word in cardData.selectedWords
        }
        // EXPRESSION: prefer the first highlighted word; fall back to the
        // whole sentence so the field is non-empty (matters when a model
        // uses sortf=0 and EXPRESSION lands at the sort slot).
        val expression = firstHighlighted?.word
            ?: cardData.source.replace(Regex("[\\n\\r]+"), " ").trim()
        val reading = firstHighlighted?.reading.orEmpty()
        // DEFINITION: empty when nothing's highlighted. assembleNote's
        // sentence-mode fold (not yet implemented for this flow; see
        // plan §3 — left explicit "" so the WORDS_TABLE is what a user
        // would map their main definition field to instead).
        val definition = firstHighlighted?.meaning?.let { m ->
            m.lines().filter { it.isNotBlank() }
                .joinToString("<br>") { it.trimStart() }
        }.orEmpty()
        val frequency = firstHighlighted?.let {
            SentenceAnkiHtmlBuilder.starsString(it.freqScore)
        }.orEmpty()
        val sentenceHtml = wrapHighlighted(cardData.source, cardData.selectedWords)
        val translationHtml = cardData.target.replace(Regex("[\\n\\r]+"), "<br>")
        val sortedWords = if (cardData.selectedWords.isNotEmpty()) {
            cardData.words.sortedByDescending { it.word in cardData.selectedWords }
        } else cardData.words
        val wordsHtml = SentenceAnkiHtmlBuilder.buildWordsHtmlWith(
            sortedWords,
            cardData.selectedWords,
            styler = inlineStyler,
        )
        return CardOutputs(
            expression = expression,
            reading = reading,
            sentence = sentenceHtml,
            sentenceTranslation = translationHtml,
            picture = imageFilename.orEmpty(),
            definition = definition,
            frequency = frequency,
            partOfSpeech = "",
            wordsTable = wordsHtml,
        )
    }

    /**
     * Builds outputs from a word-sheet send. [definitionHtml] is the
     * caller's pre-rendered Definition HTML (built via
     * `WordAnkiReviewSheet.buildWordDefinitionHtml(inlineStyler)`)
     * because it depends on the sheet's curation state.
     */
    fun forWord(
        word: String,
        reading: String,
        pos: String,
        definitionHtml: String,
        freqScore: Int,
        imageFilename: String?,
    ): CardOutputs = CardOutputs(
        expression = word,
        reading = reading,
        sentence = "",
        sentenceTranslation = "",
        picture = imageFilename.orEmpty(),
        definition = definitionHtml,
        frequency = SentenceAnkiHtmlBuilder.starsString(freqScore),
        partOfSpeech = pos,
        wordsTable = "",
    )

    /**
     * Wraps any highlighted word occurrences in [text] with `<b>…</b>`.
     * Plain text in / plain HTML out — no `<ruby>`, no JavaScript, no
     * bracketed-furigana markup. Templates that want furigana on the
     * Sentence field rely on Anki's `{{furigana:Sentence}}` filter
     * against a separately-mapped bracketed-format field, which is out
     * of scope for v1.
     *
     * Longer words are matched first so that "猫" doesn't steal a match
     * from "黒猫" when both are highlighted. Match is purely literal
     * substring — no deinflection (that's a Japanese-only concern best
     * served by the future SentenceFurigana output channel).
     */
    private fun wrapHighlighted(text: String, highlighted: Set<String>): String {
        if (highlighted.isEmpty()) {
            return text.replace(Regex("[\\n\\r]+"), "<br>")
        }
        val sortedHighlights = highlighted.filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                sb.append("<br>")
                i++
                while (i < text.length && (text[i] == '\n' || text[i] == '\r')) i++
                continue
            }
            val hit = sortedHighlights.firstOrNull { text.startsWith(it, i) }
            if (hit != null) {
                sb.append("<b>").append(hit).append("</b>")
                i += hit.length
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
