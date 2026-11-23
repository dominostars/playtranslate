package com.playtranslate.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureService
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
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
import org.junit.Assert.assertFalse
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

    /** What the driver handed over to be read aloud. */
    private val read = mutableListOf<String>()

    /** Every text that reached the clipboard, in order. */
    private val copied = mutableListOf<String?>()
    private val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java).also { cm ->
        cm.addPrimaryClipChangedListener { copied += cm.primaryClip?.getItemAt(0)?.text?.toString() }
    }

    /** The collection the driver last started, and its body: a test runs
     *  the body again as a host back in the foreground does ([comeBack]). */
    private var collector: (suspend () -> Unit)? = null
    private var collecting: Job? = null

    /** The host went to the background and came back: its collection of the
     *  capture ended and a new one begins, from the capture's last state. */
    private fun comeBack() {
        collecting?.cancel()
        collecting = scope.launch { collector!!.invoke() }
    }

    /** The host went to the background: its collection of the capture
     *  ended. Its scope goes on, as an activity's does. */
    private fun goBehind() {
        collecting?.cancel()
    }

    /** The read of a re-read's frame: a test holds it open with [frame]. */
    private val frame = kotlinx.coroutines.CompletableDeferred<android.graphics.Bitmap?>()
    private var frameReads = 0

    private val driver = CapturePageDriver(
        vm, ctx, scope,
        service = { service },
        collectWhileShown = { collect ->
            collector = collect
            scope.launch { collect() }.also { collecting = it }
        },
        pinnedScreenshotPath = { pinned },
        onSentencePending = { sentencePendings++ },
        onOriginalToRead = { read += it },
        decodeFrame = {
            frameReads++
            frame.await()
        },
        onCancelled = { cancelled += it },
    )

    @After
    fun tearDown() {
        scope.cancel()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
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

    // ── The original, read aloud ─────────────────────────────────────────

    @Test
    fun `a capture's original is handed over to be read once, as its text arrives, and the next capture's again`() {
        val first = Capture()
        driver.follow(first.session)
        assertTrue("a status has nothing to read", read.isEmpty())

        first.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        assertEquals(listOf("こんにちは"), read)
        first.state.value = CaptureState.Done(result())
        assertEquals("the result is the same text", listOf("こんにちは"), read)

        val second = Capture()
        driver.follow(second.session)
        second.state.value = CaptureState.Translating("さようなら", segments("さようなら"), provenance())
        assertEquals(listOf("こんにちは", "さようなら"), read)
    }

    @Test
    fun `a host that comes back to a finished capture is not handed its original again`() {
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        val done = result()
        capture.state.value = CaptureState.Done(done)
        assertEquals(listOf("こんにちは"), read)

        comeBack()
        comeBack()

        assertEquals("read once, however often the page is returned to", listOf("こんにちは"), read)
        assertSame("the result is still shown", done, (vm.result.value as ResultState.Ready).result)
    }

    @Test
    fun `nor one that comes back while it is still translating`() {
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())

        comeBack()
        capture.state.value = CaptureState.Done(result())

        assertEquals(listOf("こんにちは"), read)
    }

    @Test
    fun `a capture first seen at its result is read then, and once`() {
        val capture = Capture()
        capture.state.value = CaptureState.Done(result())
        driver.follow(capture.session)
        assertEquals(listOf("こんにちは"), read)

        comeBack()

        assertEquals(listOf("こんにちは"), read)
    }

    // ── The original, copied (Auto Copy to Clipboard) ────────────────────

    @Test
    fun `with Auto Copy on a capture's original is copied once, as its text arrives, and the next capture's replaces it`() {
        Prefs(ctx).autoCopyOriginal = true
        val first = Capture()
        driver.follow(first.session)
        assertTrue("a status has nothing to copy", copied.isEmpty())

        first.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        assertEquals("copied before the translation is back", listOf<String?>("こんにちは"), copied)
        first.state.value = CaptureState.Done(result())
        assertEquals("the result is the same text", listOf<String?>("こんにちは"), copied)

        val second = Capture()
        driver.follow(second.session)
        second.state.value = CaptureState.Translating("さようなら", segments("さようなら"), provenance())
        assertEquals(listOf<String?>("こんにちは", "さようなら"), copied)
    }

    @Test
    fun `a host that comes back to a capture does not copy it over what the user copied since`() {
        Prefs(ctx).autoCopyOriginal = true
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        capture.state.value = CaptureState.Done(result())
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "the user's own copy"))

        comeBack()
        comeBack()

        assertEquals(listOf<String?>("こんにちは", "the user's own copy"), copied)
    }

    // Codex review, 2026-11-22: the copy was made from the page's own
    // collection of its capture, which stops while the page is hidden. A
    // capture read then was not on the clipboard when the user pasted in
    // the app they had switched to, and was copied on the way back, over
    // what they had copied since.
    @Test
    fun `a capture read while its host is in the background is copied then, and the host coming back copies nothing`() {
        Prefs(ctx).autoCopyOriginal = true
        val capture = Capture()
        driver.follow(capture.session)

        goBehind()
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        capture.state.value = CaptureState.Done(result())
        assertEquals("copied as the capture read it", listOf<String?>("こんにちは"), copied)
        assertTrue("the hidden page showed and read nothing: $read", read.isEmpty())

        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "the user's own copy"))
        comeBack()
        comeBack()

        assertEquals(listOf<String?>("こんにちは", "the user's own copy"), copied)
        assertEquals("back on show, the page reads it once", listOf("こんにちは"), read)
    }

    @Test
    fun `with Auto Copy off a capture copies nothing`() {
        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        capture.state.value = CaptureState.Done(result())
        assertTrue("$copied", copied.isEmpty())
        assertEquals("and it is still read", listOf("こんにちは"), read)
    }

    // Codex review, 2026-11-22: a tap-to-lookup capture that cannot go up as
    // the sheet over the game opens a full-screen page, and the page copied
    // and read its text as an ordinary capture's.
    @Test
    fun `a page opened for a tap-to-lookup capture shows it and neither copies nor reads its original`() {
        Prefs(ctx).autoCopyOriginal = true
        val lookupPage = CapturePageDriver(
            vm, ctx, scope,
            service = { service },
            collectWhileShown = { collect -> scope.launch { collect() } },
            modeOverride = OverlayMode.DEFINITIONS,
            onOriginalToRead = { read += it },
            onCancelled = { cancelled += it },
        )
        val capture = Capture()
        lookupPage.follow(capture.session)
        capture.state.value = CaptureState.Translating("こんにちは", segments("こんにちは"), provenance())
        val done = result()
        capture.state.value = CaptureState.Done(done)

        assertSame("the page shows the capture", done, (vm.result.value as ResultState.Ready).result)
        assertTrue("nothing copied: $copied", copied.isEmpty())
        assertTrue("nothing read: $read", read.isEmpty())
    }

    // ── A re-read that something overtook ────────────────────────────────

    private fun realService(): CaptureService =
        org.robolectric.Robolectric.buildService(CaptureService::class.java).create().get()

    private fun aFrame() = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `a re-read nothing overtook starts once its frame is read`() {
        service = realService()
        val shown = result(screenshotPath = "/cache/d0.jpg", provenance = provenance())
        vm.displayResult(shown, ctx)

        driver.reOcr(deviceOnly = true)
        assertEquals(1, frameReads)
        assertSame("nothing changes while the frame is read", shown, (vm.result.value as ResultState.Ready).result)

        frame.complete(aFrame())
        idle()
        assertFalse("the page is the re-read's from here", vm.result.value is ResultState.Ready)
    }

    @Test
    fun `a re-read still reading its frame when another capture is followed never starts`() {
        service = realService()
        vm.displayResult(result(screenshotPath = "/cache/d0.jpg", provenance = provenance()), ctx)
        driver.reOcr(deviceOnly = true)

        val newer = Capture()
        driver.follow(newer.session)
        val done = result("新しい")
        newer.state.value = CaptureState.Done(done)
        frame.complete(aFrame())
        idle()

        assertSame("the newer capture's result stands", done, (vm.result.value as ResultState.Ready).result)
        assertFalse("and its capture was not taken from it", newer.job.isCancelled)
    }

    @Test
    fun `a re-read still reading its frame when the page abandons what it shows never starts`() {
        service = realService()
        vm.displayResult(result(screenshotPath = "/cache/d0.jpg", provenance = provenance()), ctx)
        driver.reOcr(deviceOnly = true)

        // Clear, or a change of language: the host puts its own state up.
        driver.abandon()
        vm.showStatus("idle")
        frame.complete(aFrame())
        idle()

        assertEquals(ResultState.Status("idle"), vm.result.value)
    }

    // ── The display's image written again ────────────────────────────────

    @Test
    fun `an image written while this page's capture is in flight is its own, and a cancel then hands back idle`() {
        vm.displayResult(result(), ctx)
        val capture = Capture()
        driver.follow(capture.session)

        driver.imageReplaced()
        capture.state.value = CaptureState.Cancelled

        assertEquals("not the result whose image is gone", listOf<ResultState>(ResultState.Idle), cancelled)
    }

    @Test
    fun `an image written with nothing in flight leaves what a later cancel hands back`() {
        val shown = result()
        vm.displayResult(shown, ctx)
        driver.imageReplaced()

        val capture = Capture()
        driver.follow(capture.session)
        capture.state.value = CaptureState.Cancelled

        assertSame("what the page showed when that capture began", shown, (cancelled.single() as ResultState.Ready).result)
    }
}
