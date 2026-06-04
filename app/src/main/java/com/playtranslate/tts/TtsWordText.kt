package com.playtranslate.tts

import com.playtranslate.language.SourceLangId

/**
 * The string to feed [TtsEngine] when pronouncing a single dictionary word.
 *
 * The system engine runs its own kanji→reading conversion, and for Japanese
 * that guess can diverge from the reading we display (初夏 → はつか from the
 * engine vs the dictionary's しょか). When a kana [reading] is known we speak
 * that instead, so the audio matches the furigana — and a homograph is read
 * with the reading the user looked up rather than the engine's default.
 *
 * Japanese-only: only its readings are kana, which a Japanese voice
 * pronounces directly. Chinese readings are pinyin (Latin), which a Chinese
 * voice would spell out letter by letter; Korean and Latin-script surfaces
 * are already phonetic. Every other language speaks [written] unchanged.
 */
fun ttsTextForWord(written: String, reading: String?, lang: SourceLangId): String =
    if (lang == SourceLangId.JA && !reading.isNullOrBlank()) reading else written
