package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.RegionEntry
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * [CapturePageDriver]: a capture session's states on the page's view-model,
 * what a capture cancelled from outside hands its host, a capture the page
 * abandons, the frame a switch of OCR tool re-reads, and who a deferred
 * translation goes to. The service's own completion and re-read are the
 * service's tests; the model's deferred cells are
 * [TranslationResultViewModelDeferredTest].
 */
@RunWith(RobolectricTestRunner::class)
class CapturePageDriverTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vm = TranslationResultViewModel(scope)

    private var service: CaptureService? = null
    private var pinned: String? = null
    private var sentencePendings = 0
    private val cancelled = mutableListOf<ResultState>()

    private val driver = CapturePageDriver(
        vm, ctx, scope,
        service = { service },
        collectWhileShown = { collect -> scope.launch { collect() } },
        pinnedScreenshotPath = { pinned },
        onSentencePending = { sentencePendings++ },
        onCancelled = { cancelled += it },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A capture as the service hands one out: its states are the test's to
     *  write. */
    private class Capture {
        val state = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing…"))
        val job = Job()
        val session = CaptureSession(state.asStateFlow(), job)
    }

    private fun provenance(ownOverlays: Boolean? = null) = OcrProvenance(
        engineLabel = "ML Kit", engineToken = "mlkit", displayId = 0,
        // A fixed id: a region otherwise gets a random one, and two
        // provenances built the same way would not be equal.
        sourceLangId = SourceLangId.JA, region = RegionEntry("", 0f, 1f, 0f, 1f, id = "region"),
        frameIncludesOwnOverlays = ownOverlays,
    )

    private fun result(
        original: String = "こんにちは",
        screenshotPath: String? = null,
        provenance: OcrProvenance? = null,
        pending: PendingTranslation? = null,
    ) = TranslationResult(
        originalText = original,
        segments = original.map { TextSegment(it.toString()) },
        translatedText = "hello",
        timestamp = "00:00:00",
        screenshotPath = screenshotPath,
        ocrProvenance = provenance,
        pendingTranslation = pending,
        langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
    )

    private fun segments(text: String) = text.map { TextSegment(it.toString()) }

    // ── A session's states on the page ───────────────────────────────────

    @Test
    fun `a capture walks the page through its status, its source and its result`() {
        val capture = Capture()
        driver.follow(capture.session)
        assertEquals(ResultState.Status("Capturing…"), vm.result.value)

        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        val placeholder = vm.result.value
        assertTrue(placeholder is ResultState.Translating)
        assertEquals("こんにちは", (placeholder as ResultState.Translating).originalText)
        assertEquals(provenance(), placeholder.ocrProvenance)

        val done = result()
        capture.state.value = CaptureState.Done(done)
        assertSame(done, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `a capture that found no text shows its message with the frame to re-read`() {
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.NoText("No text", provenance(), "/shots/a.jpg")
        assertEquals(
            ResultState.Status("No text", ocrProvenance = provenance(), screenshotPath = "/shots/a.jpg"),
            vm.result.value,
        )
    }

    @Test
    fun `a capture that failed shows its error`() {
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Failed("boom")
        assertEquals(ResultState.Error("boom"), vm.result.value)
    }

    // ── Cancelled from outside ───────────────────────────────────────────

    @Test
    fun `a cancelled capture hands its host what the page showed before it`() {
        val before = result()
        vm.displayResult(before, ctx)
        val shown = vm.result.value
        val capture = Capture()
        driver.follow(capture.session)

        capture.state.value = CaptureState.Cancelled

        assertEquals(listOf(shown), cancelled)
        // The driver itself leaves the page to its host.
        assertEquals(ResultState.Status("Capturing…"), vm.result.value)
    }

    @Test
    fun `captures that replaced one another hand back what stood before the first`() {
        vm.displayResult(result(), ctx)
        val shown = vm.result.value
        val first = Capture()
        driver.follow(first.session)
        val second = Capture()
        driver.follow(second.session)

        second.state.value = CaptureState.Cancelled

        // Not the first capture's "Capturing…", which nothing would replace.
        assertEquals(listOf(shown), cancelled)
    }

    @Test
    fun `a cancelled capture after one that finished hands back that one's result`() {
        val first = Capture()
        driver.follow(first.session)
        first.state.value = CaptureState.Done(result("一"))
        val firstShown = vm.result.value
        val second = Capture()
        driver.follow(second.session)

        second.state.value = CaptureState.Cancelled

        assertEquals(listOf(firstShown), cancelled)
    }

    @Test
    fun `a capture another replaced is no longer read`() {
        val first = Capture()
        driver.follow(first.session)
        val second = Capture()
        driver.follow(second.session)
        second.state.value = CaptureState.Done(result("二"))

        first.state.value = CaptureState.Done(result("一"))
        first.state.value = CaptureState.Cancelled

        assertEquals("二", (vm.result.value as ResultState.Ready).result.originalText)
        assertTrue(cancelled.isEmpty())
    }

    // ── Abandoned by the page ────────────────────────────────────────────

    @Test
    fun `an abandoned capture is cancelled and delivers nothing more`() {
        val capture = Capture()
        driver.follow(capture.session)
        vm.showStatus("idle")

        driver.abandon()
        assertTrue(capture.job.isCancelled)
        capture.state.value = CaptureState.Cancelled
        capture.state.value = CaptureState.Done(result())

        assertEquals(ResultState.Status("idle"), vm.result.value)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `after an abandoned capture the next cancel hands back the page as it stands`() {
        val first = Capture()
        driver.follow(first.session)
        driver.abandon()
        vm.showStatus("idle")
        val second = Capture()
        driver.follow(second.session)

        second.state.value = CaptureState.Cancelled

        assertEquals(listOf<ResultState>(ResultState.Status("idle")), cancelled)
    }

    // ── The frame an OCR switch re-reads ─────────────────────────────────

    @Test
    fun `a result's frame is its own screenshot unless the host was opened with one`() {
        vm.displayResult(result(screenshotPath = "/cache/d0.jpg", provenance = provenance()), ctx)
        assertEquals(provenance() to "/cache/d0.jpg", driver.reOcrTarget())

        pinned = "/pinned/shot.jpg"
        assertEquals(provenance() to "/pinned/shot.jpg", driver.reOcrTarget())
    }

    @Test
    fun `a no-text status offers its frame`() {
        vm.showStatus("No text", ocrProvenance = provenance(), screenshotPath = "/cache/d0.jpg")
        assertEquals(provenance() to "/cache/d0.jpg", driver.reOcrTarget())
    }

    @Test
    fun `nothing to re-read without a readable frame`() {
        // No result or status with a frame.
        assertNull(driver.reOcrTarget())
        vm.showStatus("idle")
        assertNull(driver.reOcrTarget())
        // A frame that carries our own overlays is never re-read.
        vm.displayResult(result(screenshotPath = "/cache/d0.jpg", provenance = provenance(ownOverlays = true)), ctx)
        assertNull(driver.reOcrTarget())
        // A result with no screenshot and no pinned one.
        vm.displayResult(result(provenance = provenance()), ctx)
        assertNull(driver.reOcrTarget())
        // A result that was not read from a frame.
        pinned = "/pinned/shot.jpg"
        vm.displayResult(result(), ctx)
        assertNull(driver.reOcrTarget())
    }

    // ── A deferred translation ───────────────────────────────────────────

    private fun pending(isCapture: Boolean) = PendingTranslation(
        groupTexts = listOf("こんにちは"), sourceLangId = SourceLangId.JA, targetLang = "en",
        isCapture = isCapture,
    )

    @Test
    fun `with no service a deferred translation waits`() {
        val deferred = result(pending = pending(isCapture = false))
        vm.displayResult(deferred, ctx)

        driver.completeDeferred()

        assertEquals(0, sentencePendings)
        assertSame(deferred, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `a deferred sentence that is not a capture goes to its host`() {
        service = Robolectric.buildService(CaptureService::class.java).create().get()
        vm.displayResult(result(pending = pending(isCapture = false)), ctx)

        driver.completeDeferred()

        assertEquals(1, sentencePendings)
    }

    @Test
    fun `nothing deferred, nothing done`() {
        service = Robolectric.buildService(CaptureService::class.java).create().get()
        vm.displayResult(result(), ctx)

        driver.completeDeferred()

        assertEquals(0, sentencePendings)
    }
}
