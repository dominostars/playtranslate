package com.playtranslate.audio

import com.playtranslate.language.SourceLangId

/**
 * A request for pronunciation audio of [surface] in [lang]. [reading] is the
 * disambiguating reading when known (e.g. JA kana for a kanji headword) and is
 * used both for TTS text prep and for matching recordings.
 *
 * [Kind.WORD] is a single headword; [Kind.SENTENCE] is a full line. Recording
 * sources only serve words — they return nothing for a sentence, so the
 * resolver falls through to TTS.
 */
data class AudioRequest(
    val surface: String,
    val reading: String?,
    val lang: SourceLangId,
    val kind: Kind,
) {
    enum class Kind { WORD, SENTENCE }

    companion object {
        fun word(surface: String, reading: String?, lang: SourceLangId): AudioRequest =
            AudioRequest(surface, reading?.ifBlank { null }, lang, Kind.WORD)

        fun sentence(text: String, lang: SourceLangId): AudioRequest =
            AudioRequest(text, null, lang, Kind.SENTENCE)
    }
}
