package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.model.TextSegment
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.translation.FakeOnlineBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * A result knows the texts its translation was made from
 * ([com.playtranslate.model.TranslationResult.groupTexts]), which is what a
 * run of it through a service the user picks translates again.
 *
 * A capture translates its OCR groups one by one and joins their
 * translations with a blank line: its result carries the groups' texts in
 * that order, beside an original text that is the groups joined by a space
 * and so cannot be split back into them, and its boxes keep one box a group
 * to be filled again ([OneShotOverlayData.skeleton]). A live tier's result
 * for the app's page is built from the boxes on show
 * ([OverlayToolkit.panelTexts]): its units are the boxes' source texts, the
 * ones that have one.
 *
 * The capture runs through the real service over a scripted reader and a
 * registry of one fake service.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureResultUnitsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private val collectors = mutableListOf<Job>()

    private val rects = listOf(Rect(2, 2, 40, 12), Rect(2, 16, 40, 26))

    /** A reader that finds two groups of text, off the network. */
    private val scripted = object : FrameReader {
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            val groups = listOf("一つ", "二つ").mapIndexed { i, text ->
                OcrManager.OcrGroup(text, rects[i], lines = listOf(OcrManager.LineBox(text, rects[i], groupIndex = i)))
            }
            return FrameReading(
                OcrManager.OcrResult("一つ 二つ", listOf(TextSegment("一つ 二つ")), groups = groups),
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
        TranslationCache.shared.resetForTest()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
        Prefs(ctx).apply {
            sourceLang = "ja"
            captureDisplayIds = setOf(0)
        }
        service!!.get().configureSaved(displayIds = setOf(0))
        FrameReaders.overrideForTest = scripted
        // One service, off the network, in place of the application's own.
        TranslationBackendRegistry.init(
            listOf(FakeOnlineBackend(id = "fake", priority = 10, displayName = "Fake", response = "translated")),
        )
    }

    @After fun tearDown() {
        collectors.forEach { it.cancel() }
        FrameReaders.overrideForTest = null
        service?.destroy()
        service = null
        TranslationBackendRegistry.close()
        TranslationCache.shared.resetForTest()
        clearPrefs()
    }

    @Test fun `a capture's result carries its groups' texts, and its boxes one box a group to fill again`() {
        val frame = CapturedFrame(
            Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888),
            includesSystemUi = false, includesOwnOverlays = false, geometry = null,
        )
        val session = service!!.get().processScreenshot(frame, displayId = 0)
        val seen = mutableListOf<CaptureState>()
        collectors += CoroutineScope(Dispatchers.Unconfined).launch { session.state.collect { seen += it } }
        val deadline = System.currentTimeMillis() + 5_000
        while (seen.none { it.isTerminal } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val done = seen.filterIsInstance<CaptureState.Done>().firstOrNull()
        assertNotNull("the capture finished: $seen", done)
        val result = done!!.result

        assertEquals("the groups, joined by a space, cannot be split back", "一つ 二つ", result.originalText)
        assertEquals(listOf("一つ", "二つ"), result.groupTexts)
        assertEquals(listOf("一つ", "二つ"), result.translationUnits)
        assertEquals("their translations, joined by a blank line", "translated\n\ntranslated", result.translatedText)
        assertEquals("Fake", result.backendDisplayName)
        val data = done.overlayData!!
        assertEquals(listOf("translated", "translated"), data.boxes.map { it.translatedText })
        assertEquals("one box a group, unfilled, kept for a second translation", listOf("", ""), data.skeleton.map { it.translatedText })
        assertEquals(rects, data.skeleton.map { it.bounds })
    }

    @Test fun `a live result's units are the source texts of the boxes that have one`() {
        val rect = Rect(0, 0, 10, 10)
        val texts = OverlayToolkit.panelTexts(
            listOf(
                TextBox("A", rect, sourceText = "あ"),
                TextBox("orphan", rect, sourceText = ""),
                TextBox("", rect, sourceText = "い"),
            ),
        )

        assertEquals(listOf("あ", "い"), texts.unitTexts)
        assertEquals("あ\nい", texts.originalText)
        assertEquals("A\n\norphan", texts.translatedText)
    }
}
