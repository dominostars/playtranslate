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
enum class ContentSource(@StringRes val labelRes: Int) {
    NONE                 (R.string.anki_content_none),
    EXPRESSION           (R.string.anki_content_expression),
    READING              (R.string.anki_content_reading),
    SENTENCE             (R.string.anki_content_sentence),
    SENTENCE_TRANSLATION (R.string.anki_content_sentence_translation),
    DEFINITION           (R.string.anki_content_definition),
    PICTURE              (R.string.anki_content_picture),
    FREQUENCY            (R.string.anki_content_frequency),
    PART_OF_SPEECH       (R.string.anki_content_part_of_speech),
    WORDS_TABLE          (R.string.anki_content_words_table),
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
    val reading: String,
    val sentence: String,
    val sentenceTranslation: String,
    val picture: String,
    val definition: String,
    val frequency: String,
    val partOfSpeech: String,
    val wordsTable: String,
)

fun CardOutputs.valueFor(source: ContentSource): String = when (source) {
    ContentSource.NONE                 -> ""
    ContentSource.EXPRESSION           -> expression
    ContentSource.READING              -> reading
    ContentSource.SENTENCE             -> sentence
    ContentSource.SENTENCE_TRANSLATION -> sentenceTranslation
    ContentSource.DEFINITION           -> definition
    ContentSource.PICTURE              -> picture
    ContentSource.FREQUENCY            -> frequency
    ContentSource.PART_OF_SPEECH       -> partOfSpeech
    ContentSource.WORDS_TABLE          -> wordsTable
}

/**
 * Which review-sheet flow a card came from. Word cards leave
 * sentence/translation empty; sentence cards leave part-of-speech
 * empty. The mode also controls Basic-shape template defaults (Front =
 * EXPRESSION in word mode, SENTENCE in sentence mode).
 */
enum class CardMode { WORD, SENTENCE }
