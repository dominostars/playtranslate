package com.playtranslate.ui

import android.content.res.ColorStateList
import android.widget.ImageButton
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.themeColor
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds an [ImageButton] to [TtsEngine] as a play/stop toggle for a block of
 * text — the result screen's original-text "speak" button.
 *
 * Tapping while idle tints the icon with the accent colour and reads the text
 * aloud; the tint clears once the utterance finishes (or errors, or is
 * interrupted). Tapping while it is speaking cancels playback. If no engine is
 * available, or it has no voice for the language, the standard TTS alert is
 * shown on [alertTarget].
 *
 * One instance per button — the constructor installs the click handler; call
 * [release] when the host view is torn down.
 *
 * [request] supplies the text and source language to speak, evaluated at tap
 * time, or null when there is nothing to speak.
 */
class OriginalSpeakButton(
    private val button: ImageButton,
    private val scope: CoroutineScope,
    private val alertTarget: TtsAlertTarget,
    private val request: () -> Request?,
) {
    /** The text to speak and the language it is in. */
    data class Request(val text: String, val lang: SourceLangId)

    /** In-flight speak coroutine. It runs for the whole utterance (TtsEngine
     *  is asked to await completion), so its liveness is "currently speaking". */
    private var job: Job? = null

    init {
        button.setOnClickListener { onTap() }
    }

    private fun onTap() {
        // A tap while speaking cancels: cancelling the job unblocks the await
        // and runs its finally (clearing the tint); stop() ends the utterance.
        if (job?.isActive == true) {
            job?.cancel()
            TtsEngine.stop()
            return
        }
        val req = request() ?: return
        job = scope.launch {
            setSpeaking(true)
            try {
                // Live-mode caller — resolve the global voice pref now
                // that TtsEngine takes null to mean "engine default."
                val voice = Prefs(alertTarget.context).ttsVoiceName(req.lang)
                // Speak the kana pronunciation so the engine doesn't re-guess
                // compound readings (初夏 → はつか); identity for non-JA.
                val spoken = SourceLanguageEngines.get(alertTarget.context, req.lang)
                    .spokenForm(req.text)
                val result = TtsEngine.speak(
                    alertTarget.context, spoken, req.lang, awaitCompletion = true,
                    voiceNameOverride = voice,
                )
                withContext(Dispatchers.Main) {
                    when (result) {
                        TtsEngine.SpeakResult.Spoken -> { /* finished playing */ }
                        TtsEngine.SpeakResult.NoEngine ->
                            showTtsNoEngineDialog(alertTarget) {}
                        is TtsEngine.SpeakResult.LanguageUnsupported ->
                            showTtsLanguageUnsupportedDialog(
                                alertTarget, req.lang, result.engineLabel,
                            )
                    }
                }
            } finally {
                // Reached on natural completion, an error result, or
                // cancellation — every path clears the highlight.
                setSpeaking(false)
            }
        }
    }

    /** Tint the icon to mark whether it is currently reading text aloud. */
    private fun setSpeaking(speaking: Boolean) {
        val attr = if (speaking) R.attr.ptAccent else R.attr.ptTextMuted
        button.imageTintList = ColorStateList.valueOf(button.context.themeColor(attr))
    }

    /** Cancel any in-progress speak and stop playback. Call when the host
     *  view is torn down. */
    fun release() {
        job?.cancel()
        TtsEngine.stop()
    }
}
