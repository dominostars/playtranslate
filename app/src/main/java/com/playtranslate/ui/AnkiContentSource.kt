package com.playtranslate.ui

import androidx.annotation.StringRes
import com.playtranslate.R

/**
 * The fixed set of "what to put in this field" choices the user sees in
 * a dropdown when mapping their note type's fields. Modeled after
 * asbplayer's per-field content-source dropdown — explicit user intent,
 * no autodetection.
 *
 * [NONE] always produces an empty string at send time. Used as the
 * default for unmapped fields and for fields the user explicitly chose
 * to leave blank.
 *
 * Enum constant names are persisted as JSON values in prefs (see
 * [com.playtranslate.Prefs.getAnkiFieldMapping]), so renaming a
 * constant is a breaking change for already-saved user mappings.
 */
enum class ContentSource(
    @StringRes val labelRes: Int,
    val kind: Kind = Kind.CONTENT,
) {
    NONE                 (R.string.anki_content_none),
    // EXPRESSION is the plain headword text (no brackets). It maps to
    // template fields rendered raw via `{{Expression}}` /
    // `{{TargetWord}}` etc — e.g. Lapis's vocab-card FRONT, which has
    // no `{{furigana:}}` filter and would otherwise display literal
    // `[reading]` markup. EXPRESSION_FURIGANA below carries the
    // bracketed variant for furigana-filtered fields.
    EXPRESSION           (R.string.anki_content_expression),
    EXPRESSION_FURIGANA  (R.string.anki_content_expression_furigana),
    READING              (R.string.anki_content_reading),
    // SENTENCE is the plain sentence text with `<b>` around each
    // highlighted-word surface. For template fields rendered raw via
    // `{{Sentence}}` — JPMN renders the Sentence field that way on
    // every card type — putting bracketed content there shows literal
    // `[reading]` markup. SENTENCE_FURIGANA below carries the
    // bracketed + `<wbr>` variant for furigana-filtered fields.
    SENTENCE             (R.string.anki_content_sentence),
    SENTENCE_FURIGANA    (R.string.anki_content_sentence_furigana),
    SENTENCE_TRANSLATION (R.string.anki_content_sentence_translation),
    DEFINITION           (R.string.anki_content_definition),
    EXAMPLE_SENTENCES    (R.string.anki_content_examples),
    PICTURE              (R.string.anki_content_picture),
    FREQUENCY            (R.string.anki_content_frequency),
    PART_OF_SPEECH       (R.string.anki_content_part_of_speech),
    WORDS_TABLE          (R.string.anki_content_words_table),

    // Card-type state flags. Each emits literal "x" when its mode
    // condition fires (computed inside AnkiCardOutputBuilder), empty
    // string otherwise. Lets users opt their template's "Is*Card"
    // fields into PT's mode signal without writing template logic.
    VOCABULARY_CARD_FLAG        (R.string.anki_content_flag_vocabulary,        Kind.FLAG),
    SENTENCE_CARD_FLAG          (R.string.anki_content_flag_sentence,          Kind.FLAG),
    TARGETED_SENTENCE_CARD_FLAG (R.string.anki_content_flag_targeted_sentence, Kind.FLAG),
    ALWAYS_ON_MARKER            (R.string.anki_content_flag_always_on,         Kind.FLAG);

    /** Two visual groups for the source picker:
     *  - CONTENT: substantive content sources
     *  - FLAG: card-type state markers whose value is "x" / empty
     */
    enum class Kind { CONTENT, FLAG }
}

/**
 * Structured output values built by [AnkiCardOutputBuilder] from the
 * current sheet state. The send-time dispatcher pairs these against the
 * user's saved field mapping via [valueFor]. Each value is a
 * pre-rendered string (HTML where appropriate) ready to be written
 * verbatim into a note field.
 */
data class CardOutputs(
    val expression: String,
    val expressionFurigana: String,
    val reading: String,
    val sentence: String,
    val sentenceFurigana: String,
    val sentenceTranslation: String,
    val picture: String,
    val definition: String,
    val examples: String,
    val frequency: String,
    val partOfSpeech: String,
    val wordsTable: String,
    val vocabularyCardFlag: String,
    val sentenceCardFlag: String,
    val targetedSentenceCardFlag: String,
    val alwaysOnMarker: String,
)

fun CardOutputs.valueFor(source: ContentSource): String = when (source) {
    ContentSource.NONE                        -> ""
    ContentSource.EXPRESSION                  -> expression
    ContentSource.EXPRESSION_FURIGANA         -> expressionFurigana
    ContentSource.READING                     -> reading
    ContentSource.SENTENCE                    -> sentence
    ContentSource.SENTENCE_FURIGANA           -> sentenceFurigana
    ContentSource.SENTENCE_TRANSLATION        -> sentenceTranslation
    ContentSource.DEFINITION                  -> definition
    ContentSource.EXAMPLE_SENTENCES           -> examples
    ContentSource.PICTURE                     -> picture
    ContentSource.FREQUENCY                   -> frequency
    ContentSource.PART_OF_SPEECH              -> partOfSpeech
    ContentSource.WORDS_TABLE                 -> wordsTable
    ContentSource.VOCABULARY_CARD_FLAG        -> vocabularyCardFlag
    ContentSource.SENTENCE_CARD_FLAG          -> sentenceCardFlag
    ContentSource.TARGETED_SENTENCE_CARD_FLAG -> targetedSentenceCardFlag
    ContentSource.ALWAYS_ON_MARKER            -> alwaysOnMarker
}

/**
 * Which review-sheet flow a card came from. Word cards leave
 * sentence/translation empty; sentence cards leave part-of-speech
 * empty. The mode also controls Basic-shape template defaults (Front =
 * EXPRESSION in word mode, SENTENCE in sentence mode).
 */
enum class CardMode { WORD, SENTENCE }
