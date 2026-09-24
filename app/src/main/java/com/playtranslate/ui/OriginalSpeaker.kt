package com.playtranslate.ui

import com.playtranslate.audio.AudioRequest
import com.playtranslate.audio.PlayOutcome
import com.playtranslate.audio.PronunciationPlayer
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The results source section's read-aloud action, as a play/stop toggle for
 * a block of text: [toggle] while idle reads it aloud through
 * [PronunciationPlayer]; [toggle] while speaking cancels playback. If no
 * engine is available, or it has no voice for the language, the standard
 * TTS alert is shown on [alertTarget].
 *
 * View-agnostic: the section header shows the action inline or in its ⋯
 * menu ([HeaderAction]), so the speaking state leaves through
 * [onSpeakingChanged] (the header paints the accent) rather than onto a
 * button. Call [release] when the host view is torn down.
 *
 * [request] supplies the text and source language to speak, evaluated at tap
 * time, or null when there is nothing to speak.
 */
class OriginalSpeaker(
    private val scope: CoroutineScope,
    private val alertTarget: TtsAlertTarget,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val request: () -> Request?,
) {
    /** The text to speak and the language it is in. */
    data class Request(val text: String, val lang: SourceLangId)

    /** In-flight speak coroutine. It runs for the whole utterance (TtsEngine
     *  is asked to await completion), so its liveness is "currently speaking". */
    private var job: Job? = null

    fun toggle() {
        // A tap while speaking cancels: cancelling the job unblocks the await
        // and runs its finally (clearing the accent); stop() ends the utterance.
        if (job?.isActive == true) {
            job?.cancel()
            PronunciationPlayer.stop()
            return
        }
        val req = request() ?: return
        job = scope.launch {
            onSpeakingChanged(true)
            try {
                // Default playback through the single owner: Commons has no
                // sentence recordings, so this resolves to TTS — routing it here
                // keeps one stop() authority across both backends. The TTS source
                // applies the voice pref and kana spoken-form itself.
                val outcome = PronunciationPlayer.play(
                    alertTarget.context,
                    AudioRequest.sentence(req.text, req.lang),
                    awaitCompletion = true,
                )
                withContext(Dispatchers.Main) {
                    when (outcome) {
                        PlayOutcome.TtsNoEngine ->
                            showTtsNoEngineDialog(alertTarget) {}
                        is PlayOutcome.TtsLanguageUnsupported ->
                            showTtsLanguageUnsupportedDialog(
                                alertTarget, req.lang, outcome.engineLabel,
                            )
                        else -> { /* finished playing */ }
                    }
                }
            } finally {
                // Reached on natural completion, an error result, or
                // cancellation — every path clears the accent.
                onSpeakingChanged(false)
            }
        }
    }

    /** Cancel any in-progress speak and stop playback. Call when the host
     *  view is torn down. */
    fun release() {
        job?.cancel()
        PronunciationPlayer.stop()
    }
}
