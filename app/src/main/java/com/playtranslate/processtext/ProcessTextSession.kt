package com.playtranslate.processtext

import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.cancelledStateOrNull
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Builds a [CaptureSession] from a plain string — the PROCESS_TEXT flow's
 * stand-in for the OCR pipeline. The flow starts directly at
 * [CaptureState.Translating] (the source is known instantly; its blank
 * translatedText renders the panel's "Translating…" placeholder) and lands
 * on [CaptureState.Done] with null overlayData and null screenshotPath —
 * which keeps the panel's "Show on screen" toggle hidden and the Anki card
 * image-less, the contract this flow's no-image requirements ride on.
 */
object ProcessTextSession {

    /** [translate] is injected (the activity passes the real
     *  [CameraTranslator]) so the state machine tests against a fake. */
    fun build(
        text: String,
        langContext: TranslationLangContext,
        scope: CoroutineScope,
        failedMessage: String,
        translate: suspend (String) -> CameraTranslator.Detailed?,
    ): CaptureSession {
        val segments = TextSegments.ofText(text)
        val state = MutableStateFlow<CaptureState>(CaptureState.Translating(text, segments))
        val job = scope.launch {
            state.value = try {
                val d = translate(text)
                if (d == null || d.text.isEmpty()) {
                    // A Done with blank translatedText renders as a
                    // permanently stuck "Translating…" — fail instead.
                    CaptureState.Failed(failedMessage)
                } else {
                    CaptureState.Done(
                        TranslationResult(
                            originalText = text,
                            segments = segments,
                            translatedText = d.text,
                            timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date()),
                            note = d.note,
                            backendDisplayName = d.backendDisplayName,
                            langContext = langContext,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CaptureState.Failed(e.message ?: failedMessage)
            }
        }
        job.invokeOnCompletion { cause ->
            cancelledStateOrNull(cause, state.value)?.let { state.value = it }
        }
        return CaptureSession(state.asStateFlow(), job)
    }
}
