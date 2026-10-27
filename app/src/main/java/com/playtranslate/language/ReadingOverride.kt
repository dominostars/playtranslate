package com.playtranslate.language

/**
 * A sentence card's own reading of one stretch of its sentence: the card is
 * for [word], found in the sentence as [surface], and that text reads
 * [reading] (kana for JA), where the sentence's analysis chose another word
 * there. A word picked under "Other matches" is the case: 弾けた in
 * 「一番うまく弾けた！」 resolves to 弾ける (はじける), and a card made for 弾く
 * read ひく reads it ひけた.
 *
 * Only the card follows it, and every part of the card follows it the same
 * way, through [SourceLanguageEngine.withReading]: the card's furigana and
 * word wrappers (the send's one analysis) and its sentence audio
 * ([SourceLanguageEngine.spokenForm]). It names a text, not an occurrence,
 * so a sentence that holds [surface] more than once keeps its analysis
 * there; a [surface] inside one larger span (a member word, 気 in 気になる)
 * is split out of it for the card to mark, with the span's readings kept.
 * The results screen keeps the analysis as it was. Only the JA engine
 * derives one ([SourceLanguageEngine.occurrenceReading]).
 */
data class ReadingOverride(
    val surface: String,
    val word: String,
    val reading: String,
)
