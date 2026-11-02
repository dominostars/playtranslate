package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.fillOneShotOverlayData
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.PendingTranslation
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The over-game capture sheet with its mode pill: what each mode paints, when
 * a collapsed start posture is honored, the rescue when a reading build lands
 * empty, the pill's picks, and the deferred translation a parked sheet waits
 * with until it leaves the park or switches to Translations.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureSheetModeControlTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val prefs = Prefs(ctx)
    private var sheet: CaptureResultOverlay? = null

    private val rect = Rect(100, 100, 300, 140)
    private val group = OcrManager.OcrGroup(
        "hello", rect, lines = listOf(OcrManager.LineBox("hello", rect, groupIndex = 0)),
    )
    private val readingBox = TextBox("へろ", Rect(100, 90, 300, 100), isFurigana = true)

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun clearPrefs() { sp().edit().clear().commit() }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(o: Any, name: String): T =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T

    private fun layerOf(o: CaptureResultOverlay): CaptureOverlayLayer = field(o, "layer")
    private fun pillOf(o: CaptureResultOverlay): OverlayModePill = field(o, "modePill")
    private fun parked(o: CaptureResultOverlay): Boolean = field(o, "sliverMode")

    private fun expandFromSliver(o: CaptureResultOverlay) {
        CaptureResultOverlay::class.java.getDeclaredMethod("expandFromSliver")
            .apply { isAccessible = true }.invoke(o)
        idle()
    }

    private fun open(
        mode: OverlayMode,
        posture: Float = CaptureResultGeometry.NO_POSTURE,
        readings: List<TextBox> = emptyList(),
    ): CaptureResultOverlay {
        prefs.captureOverlayMode = mode
        prefs.capturePanelPosture = posture
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        layerOf(o).readingBoxes = { _, _ -> readings }
        o.show(1280, 720)
        idle()
        return o
    }

    private fun session(): Pair<MutableStateFlow<CaptureState>, CaptureSession> {
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing"))
        return flow to CaptureSession(flow, Job())
    }

    private fun data(vararg texts: String) = OneShotOverlayData(
        boxes = texts.map { TextBox(it, rect) },
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = listOf(rect),
        groups = listOf(group),
    )

    private fun translating(d: OneShotOverlayData) =
        CaptureState.Translating("hello", listOf(TextSegment("hello")), null, d)

    private fun result(pending: PendingTranslation? = null) = TranslationResult(
        originalText = "hello",
        segments = listOf(TextSegment("hello")),
        translatedText = "hi",
        timestamp = "",
        pendingTranslation = pending,
        langContext = prefs.langContext(),
    )

    private fun pending() = PendingTranslation(
        groupTexts = listOf("hello"), sourceLangId = SourceLangId.JA, targetLang = "en", isCapture = true,
    )

    private fun settle(l: CaptureOverlayLayer) {
        val deadline = System.currentTimeMillis() + 3000
        while (l.pending && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
    }

    private fun descendants(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else listOf(v)

    private fun segments(p: OverlayModePill): List<String> =
        descendants(p).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }

    @Test fun `translations paints the chips and offers the modes the layer can paint`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertTrue(layerOf(o).hasBoxes)
        assertEquals(View.VISIBLE, pillOf(o).visibility)
        assertEquals(listOf("Translation", "Furigana", "None"), segments(pillOf(o)))

        flow.value = CaptureState.Done(result(), fillOneShotOverlayData(data(""), listOf("hi")))
        idle()
        assertTrue(layerOf(o).hasBoxes)
        assertEquals("hi", layerOf(o).data!!.boxes.single().translatedText)
    }

    @Test fun `none paints nothing, still offers the pill, and drops a collapsed start posture`() {
        val o = open(OverlayMode.NONE, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        assertFalse("a park with nothing to show is dropped", o.willOpenParked())
        assertFalse(parked(o))
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertFalse(layerOf(o).hasBoxes)
        assertFalse(parked(o))
        assertEquals(View.VISIBLE, pillOf(o).visibility)
    }

    @Test fun `translations with a collapsed posture parks on the skeleton chips`() {
        val o = open(OverlayMode.TRANSLATION, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        assertTrue(o.willOpenParked())
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertTrue(parked(o))
        assertTrue(layerOf(o).hasBoxes)
    }

    @Test fun `readings that land keep the park`() {
        val o = open(OverlayMode.FURIGANA, posture = CaptureResultGeometry.COLLAPSED_POSTURE, readings = listOf(readingBox))
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertTrue("parked on the build in flight", parked(o))
        settle(layerOf(o))
        assertTrue(parked(o))
        assertTrue(layerOf(o).hasBoxes)
    }

    @Test fun `a reading build that lands empty brings a parked sheet up`() {
        val o = open(OverlayMode.FURIGANA, posture = CaptureResultGeometry.COLLAPSED_POSTURE, readings = emptyList())
        assertTrue("the posture alone would park it", o.willOpenParked())
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        // The build may land before or after the park is judged; either way
        // an empty result leaves the sliver nothing to stand on.
        settle(layerOf(o))
        assertFalse(parked(o))
        assertFalse(layerOf(o).hasBoxes)
    }

    @Test fun `every translation blank leaves nothing to paint and brings a parked sheet up`() {
        val o = open(OverlayMode.TRANSLATION, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertTrue(parked(o))
        flow.value = CaptureState.Done(result(), fillOneShotOverlayData(data(""), listOf(" ")))
        idle()
        assertFalse(parked(o))
        assertFalse(layerOf(o).hasBoxes)
    }

    @Test fun `picking none takes the boxes down and writes the pref, translations brings them back`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        flow.value = CaptureState.Done(result(), fillOneShotOverlayData(data(""), listOf("hi")))
        idle()
        assertTrue(layerOf(o).hasBoxes)

        pillOf(o).onSelect!!.invoke(OverlayMode.NONE)
        idle()
        assertFalse(layerOf(o).hasBoxes)
        assertEquals(OverlayMode.NONE, prefs.captureOverlayMode)

        pillOf(o).onSelect!!.invoke(OverlayMode.TRANSLATION)
        idle()
        assertTrue(layerOf(o).hasBoxes)
        assertEquals(OverlayMode.TRANSLATION, prefs.captureOverlayMode)
    }

    @Test fun `a parked reading sheet waits with its deferred translation until it leaves the park`() {
        val o = open(OverlayMode.FURIGANA, posture = CaptureResultGeometry.COLLAPSED_POSTURE, readings = listOf(readingBox))
        var completions = 0
        o.completeDeferred = { completions++; listOf(CaptureResultOverlay.PanelTranslation("hi", null, null)) }
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        settle(layerOf(o))
        flow.value = CaptureState.Done(result(pending()), data(""))
        idle()
        assertTrue(parked(o))
        assertEquals("nobody is looking at the translation", 0, completions)

        expandFromSliver(o)
        assertEquals(1, completions)
    }

    @Test fun `switching a parked reading sheet to translations runs the deferred translation`() {
        val o = open(OverlayMode.FURIGANA, posture = CaptureResultGeometry.COLLAPSED_POSTURE, readings = listOf(readingBox))
        var completions = 0
        o.completeDeferred = { completions++; listOf(CaptureResultOverlay.PanelTranslation("hi", null, null)) }
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        settle(layerOf(o))
        flow.value = CaptureState.Done(result(pending()), data(""))
        idle()
        assertEquals(0, completions)

        pillOf(o).onSelect!!.invoke(OverlayMode.TRANSLATION)
        idle()
        assertEquals(1, completions)
        assertTrue("the skeleton chips went up", layerOf(o).hasBoxes)
    }

    @Test fun `the pill is not offered before a result is bound`() {
        val o = open(OverlayMode.TRANSLATION)
        assertEquals(0, pillOf(o).childCount)
    }
}
