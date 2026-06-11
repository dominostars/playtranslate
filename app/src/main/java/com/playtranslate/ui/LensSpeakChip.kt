package com.playtranslate.ui

import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
import com.playtranslate.tts.ttsTextForWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds a [MagnifierLens]'s Speak chip to [TtsEngine].
 *
 * Tapping the chip speaks the current word; if no engine is available, or it
 * has no voice for the language, the standard TTS alert is shown on
 * [alertTarget]. The chip shows a loading spinner while the request is in
 * flight. One instance per lens — the constructor installs the lens's
 * `onSpeakTap` handler; call [release] when the lens is torn down.
 *
 * [request] supplies the word and source language to speak, evaluated at tap
 * time, or null when there is nothing to speak.
 */
class LensSpeakChip(
    private val lens: MagnifierLens,
    private val scope: CoroutineScope,
    private val alertTarget: TtsAlertTarget,
    private val request: () -> Request?,
) {
    /** The word to speak and the language it is in. [reading] is the kana
     *  reading shown to the user, when known; for Japanese it is spoken in
     *  place of the kanji surface so the audio matches the displayed reading
     *  (see [ttsTextForWord]). Null when there is no reading to prefer. */
    data class Request(val word: String, val lang: SourceLangId, val reading: String? = null)

    /** In-flight speak coroutine — guards against overlapping taps. */
    private var job: Job? = null

    init {
        lens.onSpeakTap = { onTap() }
    }

    private fun onTap() {
        if (job?.isActive == true) return
        val req = request() ?: return
        job = scope.launch {
            lens.setSpeakChipLoading(true)
            try {
                speakWord(alertTarget, req) { lens.dismiss() }
            } finally {
                // Hide the spinner once a result is reached (or an alert shown).
                lens.setSpeakChipLoading(false)
            }
        }
    }

    /** Cancel a pending speak and stop playback. Call when the lens is gone. */
    fun release() {
        job?.cancel()
        TtsEngine.stop()
    }
}

/**
 * Speak a single dictionary word and surface the standard TTS alerts on
 * [target]. Shared by [LensSpeakChip] (the lens) and [WordResultCell]'s speak
 * action so the two paths can't diverge. The caller owns the loading
 * indicator and re-entrancy guard — this just performs the request and
 * presents any alert. [onNoEngine] runs after the "no engine" alert is shown
 * (the lens dismisses itself; the result cell passes a no-op).
 */
suspend fun speakWord(
    target: TtsAlertTarget,
    request: LensSpeakChip.Request,
    onNoEngine: () -> Unit = {},
) {
    val voice = Prefs(target.context).ttsVoiceName(request.lang)
    val result = TtsEngine.speak(
        target.context,
        ttsTextForWord(request.word, request.reading, request.lang),
        request.lang,
        voiceNameOverride = voice,
    )
    withContext(Dispatchers.Main) {
        when (result) {
            TtsEngine.SpeakResult.Spoken -> { /* audio playing */ }
            TtsEngine.SpeakResult.NoEngine -> showTtsNoEngineDialog(target, onNoEngine)
            is TtsEngine.SpeakResult.LanguageUnsupported ->
                showTtsLanguageUnsupportedDialog(target, request.lang, result.engineLabel)
        }
    }
}
