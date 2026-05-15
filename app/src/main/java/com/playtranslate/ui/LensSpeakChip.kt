package com.playtranslate.ui

import com.playtranslate.language.SourceLangId
import com.playtranslate.tts.TtsEngine
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
 * [alertTarget]. One instance per lens — the constructor installs the lens's
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
    /** The word to speak and the language it is in. */
    data class Request(val word: String, val lang: SourceLangId)

    /** In-flight speak coroutine — guards against overlapping taps. */
    private var job: Job? = null

    init {
        lens.onSpeakTap = { onTap() }
    }

    private fun onTap() {
        if (job?.isActive == true) return
        val req = request() ?: return
        job = scope.launch {
            val result = TtsEngine.speak(alertTarget.context, req.word, req.lang)
            withContext(Dispatchers.Main) {
                when (result) {
                    TtsEngine.SpeakResult.Spoken -> { /* audio playing */ }
                    TtsEngine.SpeakResult.NoEngine ->
                        showTtsNoEngineDialog(alertTarget) { lens.dismiss() }
                    is TtsEngine.SpeakResult.LanguageUnsupported ->
                        showTtsLanguageUnsupportedDialog(
                            alertTarget, req.lang, result.engineLabel,
                        )
                }
            }
        }
    }

    /** Cancel a pending speak and stop playback. Call when the lens is gone. */
    fun release() {
        job?.cancel()
        TtsEngine.stop()
    }
}
