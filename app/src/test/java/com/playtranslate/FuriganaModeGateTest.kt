package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.capture.LiveCaptureSource
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

/**
 * [FuriganaMode]'s frame handling with the change gate in front of its read,
 * driven frame by frame: the gate's verdicts on real pixels, the floor-paced
 * follow-up after a moving skip, full cadence on a persistently animating
 * block, the page keys dropped by a read that found no text, and the panel's
 * confirming read, once. The read is a scripted [FrameReader] through
 * [FrameReaders.overrideForTest]; frames are a CLEAN task
 * mirror's (stamped free of our overlays), so no patch and no clean capture
 * stand between the frame and the read. English source and target, so the
 * panel's offer takes the OCR-only bypass and commits without a backend.
 */
@RunWith(RobolectricTestRunner::class)
class FuriganaModeGateTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private lateinit var svc: CaptureService
    private val loop = FrameLoop()
    private val reader = ScriptedReader()
    private lateinit var mode: FuriganaMode

    /** A frame loop the test drives by hand: it keeps the mode's callbacks
     *  and records what the mode asks of it. */
    private class FrameLoop : LiveCaptureSource {
        var onRaw: ((CapturedFrame) -> Unit)? = null
        val cleanRequests = mutableListOf<Int>()
        val pokes = mutableListOf<Long>()
        private val running = mutableSetOf<Int>()
        override val minCaptureIntervalMs = 500L
        override val hasAnyLoop: Boolean get() = running.isNotEmpty()
        override suspend fun requestClean(displayId: Int, maskOwnWindows: Boolean): CapturedFrame? = null
        override suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)?, maskOwnWindows: Boolean): CapturedFrame? = null
        override fun saveToCache(bitmap: Bitmap, displayId: Int): String? = null
        override fun destroy() {}
        override fun startLoop(
            displayId: Int,
            scope: CoroutineScope,
            onCleanFrame: (CapturedFrame) -> Unit,
            onRawFrame: (CapturedFrame) -> Unit,
        ) {
            running += displayId
            onRaw = onRawFrame
        }
        override fun requestCleanCapture(displayId: Int) { cleanRequests += displayId }
        override fun requestCleanCaptureAll() {}
        override fun pokeFastPoll(displayId: Int, windowMs: Long) { pokes += windowMs }
        override fun stopLoop(displayId: Int) { running -= displayId }
        override fun stopAllLoops() { running.clear() }
        override fun isLoopRunning(displayId: Int): Boolean = displayId in running
    }

    /** Finds [text] in every frame (null = no text), and counts its reads. */
    private class ScriptedReader : FrameReader {
        var text: String? = "Hello"
        var reads = 0
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            reads++
            val t = text ?: return FrameReading(null, attribution = null, translations = null)
            val bounds = Rect(40, 40, 90, 60)
            val group = OcrManager.OcrGroup(
                text = t, bounds = bounds,
                lines = listOf(OcrManager.LineBox(text = t, bounds = bounds, groupIndex = 0)),
            )
            return FrameReading(
                OcrManager.OcrResult(fullText = t, segments = emptyList(), groups = listOf(group)),
                attribution = null, translations = null,
            )
        }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before fun setUp() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val prefs = Prefs(ctx)
        prefs.sourceLang = "en"
        prefs.setTarget("en")
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        val built = Robolectric.buildService(CaptureService::class.java).create()
        service = built
        svc = built.get()
        // The clean path reads only on a configured service.
        svc.configureSaved(Display.DEFAULT_DISPLAY)
        FrameReaders.overrideForTest = reader
        mode = FuriganaMode(svc, Display.DEFAULT_DISPLAY, liveSource = { loop })
        mode.start()
        idle(50)
        assertTrue("the loop started", loop.onRaw != null)
    }

    @After fun tearDown() {
        FrameReaders.overrideForTest = null
        mode.stop()
        MainActivity.isInForeground = false
        MainActivity.isInMultiWindowMode = false
        service?.destroy()
        service = null
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Frames ─────────────────────────────────────────────────────────

    private val w = 224
    private val h = 96

    /** A game-like frame: a two-tone checker over 7 by 3 grid blocks, so the
     *  gate's photometric fit has contrast to fit (a flat frame makes the
     *  fit degenerate and every block read as changed); [block0] fills the
     *  top-left 32 px block when given, [fill] replaces the checker. */
    private fun frame(fill: Int? = null, block0: Int? = null): Bitmap {
        val dark = Color.rgb(40, 40, 40)
        val light = Color.rgb(200, 200, 200)
        val px = IntArray(w * h) { i ->
            fill ?: if (((i % w) / 8 + (i / w) / 8) % 2 == 0) dark else light
        }
        if (block0 != null) for (y in 0 until 32) for (x in 0 until 32) px[y * w + x] = block0
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** One raw tick of a CLEAN mirror's frame, run to the end of its read. */
    private fun tick(bitmap: Bitmap) {
        loop.onRaw!!(CapturedFrame(bitmap, includesSystemUi = false, includesOwnOverlays = false))
        repeat(200) {
            idle(10)
            if (!mode.isReadingForTest) return
            Thread.sleep(5)
        }
        fail("the read did not finish")
    }

    // ── Cells ──────────────────────────────────────────────────────────

    @Test fun `the first frame seeds and reads, an unchanged frame is skipped`() {
        tick(frame())
        assertEquals(1, reader.reads)
        tick(frame())
        tick(frame())
        assertEquals("unchanged pixels: nothing read", 1, reader.reads)
        assertEquals(emptyList<Long>(), loop.pokes)
    }

    @Test fun `a changed block is skipped as moving with one floor poke, and read once it stands still`() {
        tick(frame())
        assertEquals(1, reader.reads)
        tick(frame(block0 = Color.WHITE))
        assertEquals("the change is a smear this tick", 1, reader.reads)
        assertEquals("one floor-paced follow-up", listOf(500L), loop.pokes)
        tick(frame(block0 = Color.WHITE))
        assertEquals("still and differing from the anchor: read", 2, reader.reads)
        tick(frame(block0 = Color.WHITE))
        assertEquals("anchored on the read: quiet again", 2, reader.reads)
    }

    @Test fun `a persistently animating block reads at full cadence`() {
        tick(frame())
        val colors = listOf(Color.WHITE, Color.BLACK, Color.WHITE, Color.BLACK)
        for (c in colors) tick(frame(block0 = c))
        // Moving on four runs in a row: the block is volatile from here.
        val before = reader.reads
        tick(frame(block0 = Color.WHITE))
        tick(frame(block0 = Color.BLACK))
        tick(frame(block0 = Color.WHITE))
        assertEquals("every tick reads while the block animates", before + 3, reader.reads)
    }

    @Test fun `a read that finds no text drops the page keys, so a waiting panel forces no read on the empty screen`() {
        tick(frame())
        assertEquals(1, reader.reads)
        // The page ends: the next read finds nothing, through the clean path.
        reader.text = null
        tick(frame(fill = Color.WHITE))
        tick(frame(fill = Color.WHITE))
        assertEquals("the moving tick skipped, the still one read", 2, reader.reads)
        // The panel comes to the front on the empty screen.
        MainActivity.isInMultiWindowMode = true
        MainActivity.isInForeground = true
        tick(frame(fill = Color.WHITE))
        tick(frame(fill = Color.WHITE))
        assertEquals("no page: nothing for the panel to wait for", 2, reader.reads)
    }

    @Test fun `a refresh drops the gate's anchor and asks for a clean frame, and the next raw tick reads`() {
        tick(frame())
        tick(frame())
        assertEquals(1, reader.reads)
        // What the menu's engine pick, a region change and a hold's end do.
        mode.refresh()
        assertEquals("a clean capture is requested of the running loop", listOf(Display.DEFAULT_DISPLAY), loop.cleanRequests)
        tick(frame())
        assertEquals("the same pixels, read again: the gate seeded afresh", 2, reader.reads)
    }

    @Test fun `the panel key follows a settled read whose punctuation differs, so delivery ends the waiting`() {
        MainActivity.isInMultiWindowMode = true
        MainActivity.isInForeground = true
        tick(frame())
        assertEquals(1, reader.reads)
        // The same letters with four more marks: the source-only key is
        // unchanged (the settled fast path), the full text is not, and the
        // confirming read delivers THIS full text to the panel.
        reader.text = "Hello!!!!"
        tick(frame())
        assertEquals("the confirming read", 2, reader.reads)
        tick(frame())
        tick(frame())
        assertEquals("delivered under the read's own key: nothing waits", 2, reader.reads)
    }

    @Test fun `a page is confirmed once for a waiting panel, then skipped`() {
        MainActivity.isInMultiWindowMode = true
        MainActivity.isInForeground = true
        tick(frame())
        assertEquals("the first build never offers", 1, reader.reads)
        tick(frame())
        assertEquals("the confirming read, which offers the page", 2, reader.reads)
        tick(frame())
        tick(frame())
        assertEquals("delivered: the panel waits no more", 2, reader.reads)
    }
}
