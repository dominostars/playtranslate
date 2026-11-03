package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.model.TextSegment
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The display's geometry rides with a capture from its frame
 * ([CapturedFrame.geometry]) into what its window over the game is built
 * from ([OneShotOverlayData.frameGeometry], which
 * [OverlayUiController.presentCapture] checks) and into the result's
 * provenance ([com.playtranslate.model.OcrProvenance.frameGeometry]), which
 * a re-read of the saved screenshot carries back into its frame; a frame
 * with none leaves both null. A screenshot is stamped with the layout the
 * display had when it was asked for, so a turn while it is taken leaves it
 * with the old one ([CaptureService.stampedWithRequestLayout]). A derived
 * frame keeps it, and stamping one keeps the pixels and every other fact.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureFrameGeometryTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private val collectors = mutableListOf<Job>()

    private val taken = DisplayGeometry(1920, 1080, 1)

    /** A reader that finds one group of text, off the network. */
    private val scripted = object : FrameReader {
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            val rect = Rect(2, 2, 40, 12)
            val group = OcrManager.OcrGroup(
                "テスト", rect, lines = listOf(OcrManager.LineBox("テスト", rect, groupIndex = 0)),
            )
            return FrameReading(
                OcrManager.OcrResult("テスト", listOf(TextSegment("テスト")), groups = listOf(group)),
                ReaderAttribution("device", "device"),
                translations = null,
            )
        }
    }

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
        Prefs(ctx).apply {
            sourceLang = "ja"
            captureDisplayIds = setOf(0)
        }
        service!!.get().configureSaved(displayIds = setOf(0))
        FrameReaders.overrideForTest = scripted
    }

    @After fun tearDown() {
        collectors.forEach { it.cancel() }
        FrameReaders.overrideForTest = null
        service?.destroy()
        service = null
        clearPrefs()
    }

    private fun frame(geometry: DisplayGeometry?) = CapturedFrame(
        Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888),
        includesSystemUi = false,
        includesOwnOverlays = false,
        geometry = geometry,
    )

    /** The first state of a capture of [frame] with its boxes and source. */
    private fun firstRead(frame: CapturedFrame): CaptureState.Translating {
        val session = service!!.get().processScreenshot(frame, displayId = 0)
        val seen = mutableListOf<CaptureState>()
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { session.state.collect { seen += it } }
        val deadline = System.currentTimeMillis() + 5_000
        while (seen.none { it is CaptureState.Translating || it.isTerminal } &&
            System.currentTimeMillis() < deadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        session.cancel()
        val read = seen.filterIsInstance<CaptureState.Translating>().firstOrNull()
        assertNotNull("the capture read its text: $seen", read)
        return read!!
    }

    @Test fun `a capture's boxes and provenance carry the layout its frame was taken in`() {
        val read = firstRead(frame(taken))
        assertEquals("its boxes", taken, read.overlayData?.frameGeometry)
        assertEquals("its provenance, for a re-read", taken, read.ocrProvenance?.frameGeometry)
    }

    @Test fun `a frame with no layout leaves both null`() {
        val read = firstRead(frame(null))
        assertNotNull(read.overlayData)
        assertNull("its boxes", read.overlayData?.frameGeometry)
        assertNotNull(read.ocrProvenance)
        assertNull("its provenance", read.ocrProvenance?.frameGeometry)
    }

    /** The game display takes [qualifiers]: the display itself, and the
     *  configuration the app reads its size through. */
    private fun reconfigure(qualifiers: String) {
        ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, qualifiers)
        RuntimeEnvironment.setQualifiers(qualifiers)
    }

    @Test fun `a screenshot is stamped with the layout the display had when it was asked for`() {
        val svc = service!!.get()
        val asked = svc.displayGeometryOf(Display.DEFAULT_DISPLAY)!!

        // The display turns while the screenshot is taken.
        val stamped = runBlocking {
            svc.stampedWithRequestLayout(Display.DEFAULT_DISPLAY) { reconfigure("+land"); frame(null) }
        }!!

        assertNotEquals("the display turned", asked, svc.displayGeometryOf(Display.DEFAULT_DISPLAY))
        assertEquals("the layout it was asked for in, not the new one", asked, stamped.geometry)
    }

    @Test fun `with no change a screenshot is stamped with the display's layout`() {
        val svc = service!!.get()
        val stamped = runBlocking { svc.stampedWithRequestLayout(Display.DEFAULT_DISPLAY) { frame(null) } }!!
        assertEquals(svc.displayGeometryOf(Display.DEFAULT_DISPLAY), stamped.geometry)
    }

    @Test fun `no screenshot, nothing stamped`() {
        assertNull(runBlocking { service!!.get().stampedWithRequestLayout(Display.DEFAULT_DISPLAY) { null } })
    }

    @Test fun `stamping a frame keeps its pixels and facts, and a derived frame keeps the layout`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val frame = CapturedFrame(bitmap, includesSystemUi = false, includesOwnOverlays = true, capturedAtMs = 7L)

        val stamped = frame.withGeometry(taken)
        assertSame(bitmap, stamped.bitmap)
        assertEquals(false, stamped.includesSystemUi)
        assertEquals(true, stamped.includesOwnOverlays)
        assertEquals(7L, stamped.capturedAtMs)
        assertEquals(taken, stamped.geometry)

        assertEquals(taken, stamped.derive(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).geometry)
    }
}
