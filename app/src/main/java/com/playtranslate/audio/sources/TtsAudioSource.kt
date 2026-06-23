package com.playtranslate.audio.sources

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.audio.AudioCandidate
import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.AudioSource
import com.playtranslate.audio.CandidateLabel
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.SpokenText
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.TtsVoiceLabels
import java.io.File

/**
 * The system text-to-speech source — the always-on floor (`toggleable = false`).
 * Wraps [TtsEngine] behind the generic [AudioSource] contract.
 */
object TtsAudioSource : AudioSource {

    const val ID = "tts"
    override val id = ID
    override fun label(ctx: Context): String = ctx.getString(R.string.audio_source_tts_name)
    override val toggleable = false
    override val remote = false
    override fun isEnabled(ctx: Context) = true
    override fun setEnabled(ctx: Context, on: Boolean) { /* always-on floor */ }

    /** Full voice list for the picker: a "Default" entry plus each engine voice. */
    override suspend fun candidates(ctx: Context, req: AudioRequest): List<AudioCandidate> {
        val voices = TtsEngine.voicesFor(ctx, req.lang)
        val default = AudioCandidate(
            sourceId = ID,
            key = DEFAULT_KEY,
            title = CandidateLabel.Res(R.string.tts_voice_default),
        )
        val rest = voices.mapIndexed { i, v ->
            val label = TtsVoiceLabels.forVoice(ctx, voices, i)
            AudioCandidate(
                sourceId = ID,
                key = v.name,
                title = CandidateLabel.Text(label.title),
                subtitle = CandidateLabel.Text(label.subtitle),
            )
        }
        return listOf(default) + rest
    }

    /** Hot path: the saved pref voice directly — NO enumeration of engine voices. */
    override suspend fun defaultCandidate(ctx: Context, req: AudioRequest): AudioCandidate {
        val voiceName = Prefs(ctx).ttsVoiceName(req.lang)
        return AudioCandidate(
            sourceId = ID,
            key = voiceName ?: DEFAULT_KEY,
            title = CandidateLabel.Res(R.string.audio_source_tts_name),
            locator = voiceName,
        )
    }

    override suspend fun play(
        ctx: Context,
        candidate: AudioCandidate,
        req: AudioRequest,
        awaitCompletion: Boolean,
        onStart: (() -> Unit)?,
    ): PlayOutcome {
        val text = SpokenText.forRequest(ctx, req)
        val voice = candidate.key.takeIf { it != DEFAULT_KEY }
        return TtsEngine.speak(ctx, text, req.lang, awaitCompletion, onStart, voice).toPlayOutcome()
    }

    override suspend fun toFile(ctx: Context, candidate: AudioCandidate, req: AudioRequest): File? {
        val text = SpokenText.forRequest(ctx, req)
        val voice = candidate.key.takeIf { it != DEFAULT_KEY }
        return TtsEngine.synthesizeToFile(ctx, text, req.lang, voice)
    }

    private fun TtsEngine.SpeakResult.toPlayOutcome(): PlayOutcome = when (this) {
        TtsEngine.SpeakResult.Spoken -> PlayOutcome.Played
        is TtsEngine.SpeakResult.LanguageUnsupported -> PlayOutcome.TtsLanguageUnsupported(engineLabel)
        TtsEngine.SpeakResult.NoEngine -> PlayOutcome.TtsNoEngine
    }

    /** Sentinel key for "engine default voice" (vs. a concrete Voice.name). */
    const val DEFAULT_KEY = "__default__"
}
