package com.playtranslate.imageimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureState
import com.playtranslate.LiveSessionFeedback
import com.playtranslate.OcrManager
import com.playtranslate.OcrTokenScope
import com.playtranslate.TranslationCache
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.TextSegments
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * The import tool's review cycle on the [FrameReader] seam, driven by a
 * scripted reader that reads and translates like PlayTranslate Cloud: the
 * first paint holds what arrived with the OCR (mapped onto the groups the
 * gate kept, by identity) with skeletons elsewhere and never counts as a
 * complete scene; the settled translations fill the rest under the
 * provider's name, the waterfall taking what the reader left; a reader for
 * another pair is ignored; a no-text reading's attribution reaches the
 * NoText state; a device-only re-read states so to the router; and the
 * slow-OCR prompt is not armed for a read that is not the device's.
 * Japanese to English, the default pair; the waterfall is one fake backend.
 */
@RunWith(RobolectricTestRunner::class)
class ImageImportSessionReaderTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val waterfall = FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "waterfall")
    private val cache = TranslationCache()

    private class ScriptedReader(private val body: (ReadRequest) -> FrameReading) : FrameReader {
        val requests = mutableListOf<ReadRequest>()
        val deviceOnly = mutableListOf<Boolean>()
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            requests += request
            return body(request)
        }
    }

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        TranslationBackendRegistry.init(listOf(waterfall))
    }

    @After fun tearDown() {
        TranslationBackendRegistry.close()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun group(text: String, top: Int, conf: Float = -1f) = OcrManager.OcrGroup(
        text = text,
        bounds = Rect(10, top, 300, top + 40),
        lines = listOf(OcrManager.LineBox(text = text, bounds = Rect(10, top, 300, top + 40), groupIndex = 0, confidence = conf)),
    )

    private fun ocr(vararg groups: OcrManager.OcrGroup) = OcrManager.OcrResult(
        fullText = groups.joinToString("\n\n") { it.text },
        segments = TextSegments.ofText(groups.joinToString("\n\n") { it.text }),
        groups = groups.toList(),
    )

    private val cloud = ReaderAttribution("PlayTranslate Cloud", "ptcloud")

    private fun reader(groupCount: Int, ready: Map<Int, String>, target: String = "en", job: Job = Job()) =
        ReaderTranslations("ja", target, "Cloud", groupCount, ready, job)

    private fun session(scripted: ScriptedReader, onSlowOcr: () -> Unit = {}) = ImageImportSession(
        context = ctx,
        scope = CoroutineScope(Dispatchers.Default),
        overlayHost = FrameLayout(ctx),
        onSlowOcr = onSlowOcr,
        readerFor = { _, deviceOnly -> scripted.deviceOnly += deviceOnly; scripted },
        translator = GroupTranslator(ctx, cache),
    ).also { it.startEpisode() }

    private val bitmap: Bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)

    private suspend inline fun <reified S : CaptureState> await(session: com.playtranslate.CaptureSession): S =
        withTimeout(10_000) { session.state.first { it is S } as S }

    @Test fun `the first paint holds the reading's ready text and skeletons, and is not a complete scene`() = runBlocking {
        val translations = reader(2, ready = mapOf(0 to "Hello"))
        val scripted = ScriptedReader { FrameReading(ocr(group("こんにちは", 10), group("さようなら", 100)), cloud, translations) }
        val session = session(scripted)

        val capture = session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0)
        await<CaptureState.Translating>(capture)
        assertEquals(listOf("Hello", null), session.firstPaintForTest())
        assertNull("a scene whose translations are in flight is not complete", session.exportScene())

        translations.settle(mapOf(1 to "Goodbye"))
        val done = await<CaptureState.Done>(capture)
        assertEquals("Hello\n\nGoodbye", done.result.translatedText)
        assertEquals("Cloud", done.result.backendDisplayName)
        val scene = session.exportScene()
        assertNotNull(scene)
        assertEquals(listOf("Hello" to "Cloud", "Goodbye" to "Cloud"), scene!!.perGroup.map { it.text to it.backendDisplayName })
        assertNull("promoted: the first paint is gone", session.firstPaintForTest())
        assertEquals("nothing went to the waterfall", 0, waterfall.translateCalls.get())
        assertEquals(OcrTokenScope.IMPORT, scripted.requests.single().scope)
        assertEquals("en", scripted.requests.single().targetLang)
        assertEquals(listOf(false), scripted.deviceOnly)
    }

    @Test fun `the ready text follows its group through the gate, by identity`() = runBlocking {
        // The reader translated its group 1 and has yet to name its group 2;
        // group 0 is gated out by its confidence, so the kept groups are
        // positions 0 and 1 of the translation. The unnamed group holds the
        // cycle at its translate call, so the first paint is observable.
        val translations = reader(3, ready = mapOf(1 to "Second"))
        val scripted = ScriptedReader {
            FrameReading(ocr(group("ごみ", 10, conf = 0.1f), group("二", 100), group("三", 200)), cloud, translations)
        }
        val session = session(scripted)
        val capture = session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0)
        await<CaptureState.Translating>(capture)
        assertEquals(listOf("Second", null), session.firstPaintForTest())
        translations.settle(mapOf(2 to "Third"))
        val done = await<CaptureState.Done>(capture)
        assertEquals("Second\n\nThird", done.result.translatedText)
    }

    @Test fun `what the reader leaves unnamed goes through the waterfall, and a reader for another pair is ignored`() = runBlocking {
        val translations = reader(2, ready = mapOf(0 to "Hello"))
        val first = session(ScriptedReader { FrameReading(ocr(group("一", 10), group("二", 100)), cloud, translations) })
        val capture = first.runReview(bitmap, regionAu = null, preOcrDelayMs = 0)
        await<CaptureState.Translating>(capture)
        translations.settle()
        val done = await<CaptureState.Done>(capture)
        assertEquals(listOf("Hello" to "Cloud", "waterfall" to "Fake"), first.exportScene()!!.perGroup.map { it.text to it.backendDisplayName })
        assertEquals("Hello\n\nwaterfall", done.result.translatedText)
        assertEquals(1, waterfall.translateCalls.get())

        val other = reader(1, ready = mapOf(0 to "Bonjour"), target = "fr")
        val second = session(ScriptedReader { FrameReading(ocr(group("三", 10)), cloud, other) })
        val done2 = await<CaptureState.Done>(second.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        assertEquals("waterfall", done2.result.translatedText)
        assertEquals("Fake", done2.result.backendDisplayName)
    }

    @Test fun `a no-text reading's attribution reaches the NoText state`() = runBlocking {
        val session = session(ScriptedReader { FrameReading(null, cloud, translations = null) })
        val noText = await<CaptureState.NoText>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        assertEquals("PlayTranslate Cloud", noText.ocrProvenance?.engineLabel)
        assertEquals("ptcloud", noText.ocrProvenance?.engineToken)
        assertEquals(SourceLangId.JA, noText.ocrProvenance?.sourceLangId)
    }

    @Test fun `a device-only re-read states so to the router, and a plain one does not`() = runBlocking {
        val scripted = ScriptedReader { FrameReading(null, null, null) }
        val session = session(scripted)
        await<CaptureState.NoText>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0, deviceOnly = true))
        await<CaptureState.NoText>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        assertEquals(listOf(true, false), scripted.deviceOnly)
    }

    @Test fun `the reading's translations are cancelled once the cycle is done with them`() = runBlocking {
        val job = Job()
        val translations = reader(1, ready = mapOf(0 to "Hello"), job = job)
        val session = session(ScriptedReader { FrameReading(ocr(group("一", 10)), cloud, translations) })
        await<CaptureState.Done>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        // Fully answered at part 1: the translate call took them without a
        // wait, so nothing settled the job; the cycle cancelled it on its way
        // out (the seam's rule), which a reader's completion handler turns
        // into a settle and the end of part 2's read.
        withTimeout(5_000) { while (!job.isCancelled) delay(5) }
        assertTrue(job.isCancelled)
    }

    @Test fun `the slow-OCR prompt is armed when the device engine is about to run, a fallback's read included`() = runBlocking {
        val fired = AtomicInteger(0)
        // A reader that, like a cloud read falling back to the device, calls
        // the request's hook before a slow device read.
        val session = session(
            ScriptedReader { request ->
                runBlocking {
                    request.beforeDeviceOcr?.invoke()
                    delay(LiveSessionFeedback.OCR_SLOW_PROMPT_MS + 400)
                }
                FrameReading(null, cloud, null)
            },
            onSlowOcr = { fired.incrementAndGet() },
        )
        await<CaptureState.NoText>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        withTimeout(5_000) { while (fired.get() == 0) delay(5) }
        assertEquals(1, fired.get())
    }

    @Test fun `the slow-OCR prompt is not armed for a read that is not the device's`() = runBlocking {
        val fired = AtomicInteger(0)
        val session = session(
            ScriptedReader {
                runBlocking { delay(LiveSessionFeedback.OCR_SLOW_PROMPT_MS + 400) }
                FrameReading(null, cloud, null)
            },
            onSlowOcr = { fired.incrementAndGet() },
        )
        await<CaptureState.NoText>(session.runReview(bitmap, regionAu = null, preOcrDelayMs = 0))
        assertFalse("a slow cloud read is not a slow device engine", fired.get() > 0)
    }
}
