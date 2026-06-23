package com.playtranslate.audio

import android.content.Context
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.tts.ttsTextForWord

/**
 * The single place that turns an [AudioRequest] into the text fed to TTS, so the
 * old call sites and the new [AudioSource]s can't drift apart:
 *  - WORD → reading-aware [ttsTextForWord] (JA kana swap so audio matches furigana).
 *  - SENTENCE → the language engine's `spokenForm` (e.g. JA kanji→kana).
 */
object SpokenText {
    suspend fun forRequest(ctx: Context, req: AudioRequest): String = when (req.kind) {
        AudioRequest.Kind.WORD -> ttsTextForWord(req.surface, req.reading, req.lang)
        AudioRequest.Kind.SENTENCE -> SourceLanguageEngines.get(ctx, req.lang).spokenForm(req.surface)
    }
}
