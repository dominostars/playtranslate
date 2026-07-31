package com.playtranslate.processtext

import com.playtranslate.CaptureState
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationLangContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the PROCESS_TEXT session's state machine: it starts at
 * [CaptureState.Translating] (no InProgress — the source is known instantly),
 * and its [CaptureState.Done] carries null overlayData and null
 * screenshotPath — the "Show on screen" button stays hidden and the Anki
 * card stays image-less on exactly those nulls. A backend that returns
 * nothing must land on [CaptureState.Failed], never a blank Done (the panel
 * renders blank translatedText as a permanently stuck "Translating…").
 */
class ProcessTextSessionTest {

    private val langContext =
        TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED)

    private val failedMessage = "translation failed"

    /** Builds a session in [runBlocking]'s scope, captures the pre-job state
     *  (single-threaded, so the launch hasn't run yet), and awaits terminal. */
    private fun run(
        text: String = "こんにちは\n世界",
        translate: suspend (String) -> CameraTranslator.Detailed?,
    ): Pair<CaptureState, CaptureState> = runBlocking {
        val session = ProcessTextSession.build(text, langContext, this, failedMessage, translate = translate)
        val initial = session.state.value
        val terminal = withTimeout(5_000) { session.state.first { it.isTerminal } }
        initial to terminal
    }

    @Test
    fun `starts Translating with no overlay data, lands Done with no image fields`() {
        val (initial, terminal) = run { CameraTranslator.Detailed("hola", null, "Fake") }

        val translating = initial as CaptureState.Translating
        assertEquals("こんにちは\n世界", translating.originalText)
        assertEquals(TextSegments.ofText("こんにちは\n世界"), translating.segments)
        assertNull(translating.ocrProvenance)
        assertNull(translating.overlayData)

        val done = terminal as CaptureState.Done
        assertNull(done.overlayData)
        assertEquals("hola", done.result.translatedText)
        assertEquals("こんにちは\n世界", done.result.originalText)
        assertEquals("Fake", done.result.backendDisplayName)
        assertEquals(langContext, done.result.langContext)
        assertNull(done.result.screenshotPath)
        assertNull(done.result.ocrProvenance)
    }

    @Test
    fun `null backend result fails instead of binding a blank Done`() {
        val (_, terminal) = run { null }
        assertEquals(CaptureState.Failed(failedMessage), terminal)
    }

    @Test
    fun `empty translation fails instead of binding a blank Done`() {
        val (_, terminal) = run { CameraTranslator.Detailed("", null, null) }
        assertEquals(CaptureState.Failed(failedMessage), terminal)
    }

    @Test
    fun `backend throw lands on Failed with its message`() {
        val (_, terminal) = run { throw IllegalStateException("backend down") }
        assertEquals(CaptureState.Failed("backend down"), terminal)
    }

    @Test
    fun `cancel mid-translation lands on Cancelled, not a stuck Translating`() {
        runBlocking {
            val session = ProcessTextSession.build(
                "hello", langContext, this, failedMessage,
            ) { awaitCancellation() }
            yield() // let the job start and suspend inside translate
            session.cancel()
            val terminal = withTimeout(5_000) { session.state.first { it.isTerminal } }
            assertTrue(terminal is CaptureState.Cancelled)
        }
    }

    @Test
    fun `deferred lands a pending Done and never calls translate`() {
        // Hidden-section deferral: the blank translatedText is legal here
        // BECAUSE pendingTranslation marks it "never ran" — distinct from the
        // blank Done the empty-translation guard fails.
        var translateCalled = false
        val (initial, terminal) = runBlocking {
            val session = ProcessTextSession.build(
                "こんにちは", langContext, this, failedMessage,
                deferTranslation = true,
            ) { translateCalled = true; CameraTranslator.Detailed("hola", null, "Fake") }
            val initial = session.state.value
            val terminal = withTimeout(5_000) { session.state.first { it.isTerminal } }
            initial to terminal
        }
        assertTrue(initial is CaptureState.Translating)
        val done = terminal as CaptureState.Done
        assertFalse(translateCalled)
        assertEquals("", done.result.translatedText)
        val pending = done.result.pendingTranslation!!
        assertEquals(listOf("こんにちは"), pending.groupTexts)
        assertEquals(langContext.sourceLangId, pending.sourceLangId)
        assertEquals(langContext.targetLang, pending.targetLang)
        assertNull(pending.historySessionId)
        assertNull(done.overlayData)
    }
}
