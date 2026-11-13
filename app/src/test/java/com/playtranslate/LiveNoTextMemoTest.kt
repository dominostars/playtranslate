package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * The two cycle-driven live tiers ([PinholeOverlayMode], [ReconcilerLiveMode])
 * reading through the service's per-display [com.playtranslate.ocr.NoTextMemo]
 * ([CaptureService.runLiveOcrRead]), driven cycle by cycle: a screen with no
 * text is read once however many cycles grab it, one changed pixel is read on
 * the cycle that grabs it, a refresh asks again, and text coming back after
 * an idle stretch is read and shown on its first cycle. The tiers themselves
 * know nothing of the memo: they run their no-text path every cycle, and
 * only the reader is not reached.
 *
 * Frames come from a fake capture source handed to the tiers' test seam, the
 * read is a scripted [FrameReader] through [FrameReaders.overrideForTest],
 * and the cycles run on the main looper at the default one-second interval.
 * The reconciler tier runs with the panel presenter, which paints nothing.
 */
@RunWith(RobolectricTestRunner::class)
class LiveNoTextMemoTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService
    private lateinit var controller: OverlayUiController
    private val source = Frames()
    private val reader = ScriptedReader()
    private var mode: LiveMode? = null

    /** A capture source that serves [screen] to every grab and counts the
     *  grabs. Its frames are a task mirror's: no system UI, none of our own
     *  windows. */
    private class Frames : LiveCaptureSource {
        lateinit var screen: Bitmap
        var grabs = 0
        override val minCaptureIntervalMs = 500L
        override val framesIncludeSystemUi = false
        override val hasAnyLoop = false
        private fun grab(): CapturedFrame {
            grabs++
            return CapturedFrame(
                screen.copy(Bitmap.Config.ARGB_8888, true),
                includesSystemUi = false, includesOwnOverlays = false,
            )
        }
        override suspend fun requestClean(displayId: Int, maskOwnWindows: Boolean) = grab()
        override suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)?, maskOwnWindows: Boolean) = grab()
        override fun saveToCache(bitmap: Bitmap, displayId: Int): String? = null
        override fun destroy() {}
        override fun startLoop(
            displayId: Int,
            scope: CoroutineScope,
            onCleanFrame: (CapturedFrame) -> Unit,
            onRawFrame: (CapturedFrame) -> Unit,
        ) {}
        override fun requestCleanCapture(displayId: Int) {}
        override fun requestCleanCaptureAll() {}
        override fun stopLoop(displayId: Int) {}
        override fun stopAllLoops() {}
        override fun isLoopRunning(displayId: Int) = false
    }

    /** Finds [text] in every frame (null = no text), and counts its reads. */
    private class ScriptedReader : FrameReader {
        var text: String? = null
        var reads = 0
        private val me = ReaderAttribution("Scripted", "scripted")
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            reads++
            val t = text ?: return FrameReading(null, me, translations = null)
            val bounds = Rect(40, 150, 200, 170)
            val group = OcrManager.OcrGroup(
                text = t, bounds = bounds,
                lines = listOf(OcrManager.LineBox(text = t, bounds = bounds, groupIndex = 0)),
            )
            return FrameReading(
                OcrManager.OcrResult(fullText = t, segments = emptyList(), groups = listOf(group)),
                me, translations = null,
            )
        }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** One capture interval: one cycle. */
    private fun cycle() = idle(1_000)

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        Prefs(ctx).captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        TranslationCache.shared.resetForTest()
        TranslationBackendRegistry.init(listOf(FakeOnlineBackend(id = "fake", priority = 10)))
        // The tiers render through, and take their input from, the overlay
        // host: the accessibility backend's, the default one.
        val a11y = Robolectric.buildService(PlayTranslateAccessibilityService::class.java).create()
        PlayTranslateAccessibilityService.instance = a11y.get()
        controller = a11y.get().overlayUiController
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
        svc.configureSaved(Display.DEFAULT_DISPLAY)
        FrameReaders.overrideForTest = reader
        source.screen = frame()
    }

    @After fun tearDown() {
        mode?.stop()
        mode = null
        FrameReaders.overrideForTest = null
        service?.destroy()
        service = null
        controller.hideAll()
        PlayTranslateAccessibilityService.instance = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        TranslationBackendRegistry.close()
        TranslationCache.shared.resetForTest()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Frames ─────────────────────────────────────────────────────────

    private val w = 320
    private val h = 240

    /** A game-like frame, a two-tone checker; [dot] sets one pixel white,
     *  [bar] draws a white rect (text coming up), [fill] replaces the
     *  checker. */
    private fun frame(dot: Boolean = false, bar: Boolean = false, fill: Int? = null): Bitmap {
        val dark = Color.rgb(40, 40, 40)
        val light = Color.rgb(200, 200, 200)
        val px = IntArray(w * h) { i ->
            fill ?: if (((i % w) / 8 + (i / w) / 8) % 2 == 0) dark else light
        }
        if (dot) px[120 * w + 160] = Color.WHITE
        if (bar) for (y in 150 until 170) for (x in 40 until 200) px[y * w + x] = Color.WHITE
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    // ── The tiers ──────────────────────────────────────────────────────

    private fun startPinhole() {
        mode = PinholeOverlayMode(svc, Display.DEFAULT_DISPLAY, streamSource = { source }).also { it.start() }
        idle(50)
        assertEquals("the first look read", 1, reader.reads)
    }

    private fun startReconciler() {
        mode = ReconcilerLiveMode(
            svc, Display.DEFAULT_DISPLAY, PanelPresenter(svc, Display.DEFAULT_DISPLAY),
            streamSource = { source },
        ).also { it.start() }
        idle(50)
        assertEquals("the first look read", 1, reader.reads)
    }

    /** Runs [cell] on each tier in turn, from a fresh screen and a fresh
     *  memo (a refresh forgets the last tier's). */
    private fun eachTier(cell: () -> Unit) {
        for (start in listOf(::startPinhole, ::startReconciler)) {
            start()
            cell()
            mode?.stop()
            mode = null
            idle(2_000)
            svc.refreshLiveOverlay()
            reader.reads = 0
            reader.text = null
            source.grabs = 0
            source.screen = frame()
        }
    }

    // ── Cells, both tiers ──────────────────────────────────────────────

    @Test fun `a screen with no text is read once, however many cycles grab it`() = eachTier {
        val grabs = source.grabs
        repeat(30) { cycle() }
        assertEquals("the same pixels: nothing read", 1, reader.reads)
        assertTrue("the cycles kept running", source.grabs >= grabs + 30)
    }

    @Test fun `one changed pixel is read on the cycle that grabs it`() = eachTier {
        cycle()
        assertEquals(1, reader.reads)
        source.screen = frame(dot = true)
        cycle()
        assertEquals("a single pixel differs: read", 2, reader.reads)
        repeat(3) { cycle() }
        assertEquals("the new screen holds no text either: remembered in its turn", 2, reader.reads)
        source.screen = frame()
        cycle()
        assertEquals("back to the first screen: read again", 3, reader.reads)
    }

    @Test fun `a refresh asks again about the same pixels, once`() = eachTier {
        cycle()
        assertEquals(1, reader.reads)
        // What an OCR engine pick, the furigana filter and the cloud's
        // switch end in: the reader's answer may have changed.
        svc.refreshLiveOverlay()
        cycle()
        assertEquals(2, reader.reads)
        repeat(3) { cycle() }
        assertEquals(2, reader.reads)
    }

    private fun shownTexts() = mode!!.getCachedState()?.boxes?.map { it.sourceText }.orEmpty()

    /** Text shown, gone, and back after an idle stretch: the stretch costs
     *  no read, and the text that ends it is read and shown on the cycle
     *  that grabs it. */
    private fun textReturnsAfterAnIdleStretch(textGoesAway: () -> Unit) {
        reader.text = "こんにちは"
        source.screen = frame(bar = true)
        cycle()
        assertEquals("first seen, shown at once", listOf("こんにちは"), shownTexts())
        textGoesAway()
        repeat(3) { cycle() }
        assertEquals("nothing is shown on the empty screen", emptyList<String>(), shownTexts())
        val readsBefore = reader.reads
        repeat(60) { cycle() }
        assertEquals("a minute on the empty screen: no read", 0, reader.reads - readsBefore)
        reader.text = "こんにちは元気"
        source.screen = frame(bar = true)
        cycle()
        assertEquals("shown on its first cycle", listOf("こんにちは元気"), shownTexts())
    }

    @Test fun `pinhole, text coming back after an idle stretch is shown on its first cycle`() {
        startPinhole()
        textReturnsAfterAnIdleStretch {
            // A game input: the boxes come down, and the next look finds
            // the screen empty.
            reader.text = null
            source.screen = frame()
            mode!!.dismiss()
        }
    }

    @Test fun `reconciler, text coming back after an idle stretch is shown on its first cycle`() {
        startReconciler()
        textReturnsAfterAnIdleStretch {
            reader.text = null
            source.screen = frame()
        }
    }

    // ── The reconciler tier alone ──────────────────────────────────────

    /** The pinhole tier has no such cell: once a read finds text it places a
     *  box, and its own change gate decides the cycles after. */
    @Test fun `a screen with text on it is read on every cycle, unchanged or not`() {
        startReconciler()
        cycle()
        assertEquals(1, reader.reads)
        reader.text = "こんにちは。"
        source.screen = frame(bar = true)
        cycle()
        assertEquals("the change read", 2, reader.reads)
        val before = reader.reads
        repeat(4) { cycle() }
        assertTrue(
            "only an empty read is remembered (reads ${reader.reads - before} of 4 cycles)",
            reader.reads >= before + 4,
        )
    }

    @Test fun `a refresh that meets a black frame still asks about the screen that comes back`() {
        startReconciler()
        cycle()
        assertEquals(1, reader.reads)
        val screen = source.screen
        // The tier handles a black frame without a read, so the refresh's
        // question is still owed when the screen returns as it was.
        source.screen = frame(fill = Color.BLACK)
        svc.refreshLiveOverlay()
        repeat(2) { cycle() }
        assertEquals("a black frame is not read", 1, reader.reads)
        source.screen = screen
        cycle()
        assertEquals(2, reader.reads)
    }
}
