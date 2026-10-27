package com.playtranslate.audio

import com.playtranslate.language.ReadingOverride
import com.playtranslate.language.SourceLangId

/**
 * A request for pronunciation audio of [surface] in [lang]. [reading] is the
 * disambiguating reading when known (e.g. JA kana for a kanji headword) and is
 * used both for TTS text prep and for matching recordings.
 *
 * [Kind.WORD] is a single headword; [Kind.SENTENCE] is a full line. Recording
 * sources only serve words — they return nothing for a sentence, so the
 * resolver falls through to TTS. A sentence for a card carries the card's
 * [readingOverride], so its audio reads the line as the card's furigana does.
 */
data class AudioRequest(
    val surface: String,
    val reading: String?,
    val lang: SourceLangId,
    val kind: Kind,
    val readingOverride: ReadingOverride? = null,
) {
    enum class Kind { WORD, SENTENCE }

    companion object {
        fun word(surface: String, reading: String?, lang: SourceLangId): AudioRequest =
            AudioRequest(surface, reading?.ifBlank { null }, lang, Kind.WORD)

        fun sentence(text: String, lang: SourceLangId, readingOverride: ReadingOverride? = null): AudioRequest =
            AudioRequest(text, null, lang, Kind.SENTENCE, readingOverride)
    }
}
