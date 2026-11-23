package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [copyOriginalWhenRead]: a capture's original on the clipboard as the
 * capture reads it, when the user asked for that ([Prefs.autoCopyOriginal]),
 * once, and the clipboard left as it was otherwise. The listen is the
 * capture's own: nothing here stands for a page, on show or hidden. Which
 * captures a surface listens to is that surface's test
 * ([CapturePageDriverTest], the capture sheet's, the review panel's).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureAutoCopyTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Every text that reached the clipboard, in order. */
    private val copied = mutableListOf<String?>()
    private val clipboard = ctx.getSystemService(ClipboardManager::class.java).also { cm ->
        cm.addPrimaryClipChangedListener { copied += cm.primaryClip?.getItemAt(0)?.text?.toString() }
    }

    /** A capture as the service hands one out: its states are the test's to
     *  write. */
    private class Capture {
        val state = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing…"))
        val session = CaptureSession(state, Job())
    }

    private fun translating(text: String) =
        CaptureState.Translating(text, text.map { TextSegment(it.toString()) })

    private fun done(text: String) = CaptureState.Done(
        TranslationResult(
            originalText = text,
            segments = text.map { TextSegment(it.toString()) },
            translatedText = "hello",
            timestamp = "00:00:00",
            screenshotPath = null,
            langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
        ),
    )

    @After
    fun tearDown() {
        scope.cancel()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `the setting is off until the user turns it on, and a capture copies nothing while it is off`() {
        assertFalse(prefs.autoCopyOriginal)
        val capture = Capture()
        val listen = scope.copyOriginalWhenRead(ctx, capture.session)
        capture.state.value = translating("猫が鳴く")
        capture.state.value = done("猫が鳴く")
        assertTrue("$copied", copied.isEmpty())
        assertTrue("the listen is over", listen.isCompleted)
    }

    @Test
    fun `with the setting on the original is copied as the capture reads it, line breaks kept, and the listen ends there`() {
        prefs.autoCopyOriginal = true
        val capture = Capture()
        val listen = scope.copyOriginalWhenRead(ctx, capture.session)
        assertTrue("a status has nothing to copy", copied.isEmpty())
        assertFalse(listen.isCompleted)

        capture.state.value = translating("猫が\n鳴く")
        assertEquals("before the translation is back", listOf<String?>("猫が\n鳴く"), copied)
        assertTrue("the listen is over", listen.isCompleted)

        // What the user copies after it is theirs: the capture's later
        // states copy nothing over it.
        clipboard.setPrimaryClip(ClipData.newPlainText("", "the user's own copy"))
        capture.state.value = done("猫が\n鳴く")
        assertEquals(listOf<String?>("猫が\n鳴く", "the user's own copy"), copied)
    }

    @Test
    fun `the next capture's original replaces the last`() {
        prefs.autoCopyOriginal = true
        val first = Capture()
        scope.copyOriginalWhenRead(ctx, first.session)
        first.state.value = translating("猫が鳴く")

        val second = Capture()
        scope.copyOriginalWhenRead(ctx, second.session)
        second.state.value = translating("犬が走る")

        assertEquals(listOf<String?>("猫が鳴く", "犬が走る"), copied)
    }

    // A session keeps its latest state alone, so a listen can be handed the
    // result with the state before it never seen.
    @Test
    fun `a capture whose result is the first the listen hears of its text is copied from the result`() {
        prefs.autoCopyOriginal = true
        val capture = Capture()
        val listen = scope.copyOriginalWhenRead(ctx, capture.session)
        capture.state.value = done("猫が鳴く")
        assertEquals(listOf<String?>("猫が鳴く"), copied)
        assertTrue(listen.isCompleted)
    }

    @Test
    fun `a listen started on a capture that has already read its text copies it at once`() {
        prefs.autoCopyOriginal = true
        val capture = Capture()
        capture.state.value = translating("猫が鳴く")
        val listen = scope.copyOriginalWhenRead(ctx, capture.session)
        assertEquals(listOf<String?>("猫が鳴く"), copied)
        assertTrue(listen.isCompleted)
    }

    @Test
    fun `a capture that ends without text copies nothing, and the listen ends with it`() {
        prefs.autoCopyOriginal = true
        for (end in listOf(
            CaptureState.NoText("No text detected"),
            CaptureState.Failed("boom"),
            CaptureState.Cancelled,
        )) {
            val capture = Capture()
            val listen = scope.copyOriginalWhenRead(ctx, capture.session)
            capture.state.value = end
            assertTrue("$end: the listen is over", listen.isCompleted)
        }
        assertTrue("$copied", copied.isEmpty())
    }

    @Test
    fun `a capture up in a mode of its own copies nothing, and the listen ends with the capture`() {
        prefs.autoCopyOriginal = true
        val capture = Capture()
        val listen = scope.copyOriginalWhenRead(ctx, capture.session) { OverlayMode.DEFINITIONS }
        capture.state.value = translating("猫が鳴く")
        assertFalse("still the capture's", listen.isCompleted)
        capture.state.value = done("猫が鳴く")
        assertTrue(listen.isCompleted)
        assertTrue("$copied", copied.isEmpty())
    }

    @Test
    fun `text that is blank leaves what the user had copied`() {
        prefs.autoCopyOriginal = true
        clipboard.setPrimaryClip(ClipData.newPlainText("", "the user's own copy"))
        val capture = Capture()
        scope.copyOriginalWhenRead(ctx, capture.session)
        capture.state.value = translating("  \n")
        assertEquals(listOf<String?>("the user's own copy"), copied)
    }

    @Test
    fun `turned off again, a capture leaves what the user had copied`() {
        prefs.autoCopyOriginal = true
        prefs.autoCopyOriginal = false
        clipboard.setPrimaryClip(ClipData.newPlainText("", "the user's own copy"))
        val capture = Capture()
        scope.copyOriginalWhenRead(ctx, capture.session)
        capture.state.value = translating("猫が鳴く")
        assertEquals(listOf<String?>("the user's own copy"), copied)
    }
}
