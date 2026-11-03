package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [CaptureOverlayLayer] paints a capture's data in one mode at a time:
 * translation chips at once, readings once their build lands, nothing in
 * None, and re-flavors in place from the same data. Mode × data cells, the
 * async settle, the stale build, and the render-config replacement. While
 * readings build, translation chips are down and readings already up stay.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureOverlayLayerFlavorTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var readings: List<TextBox> = emptyList()
    private var readingCalls = 0
    private val settled = mutableListOf<Boolean>()

    /** Holds a reading build in flight until a test releases it, so the
     *  in-flight cells are judged with the build still pending. */
    private var gate: CompletableDeferred<Unit>? = null

    private val rect = Rect(10, 10, 110, 40)
    private val group = OcrManager.OcrGroup(
        "hello", rect, lines = listOf(OcrManager.LineBox("hello", rect, groupIndex = 0)),
    )
    private val readingBox = TextBox("へろ", Rect(10, 0, 110, 10), isFurigana = true)

    @After fun tearDown() {
        scope.cancel()
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun layer() = CaptureOverlayLayer(ctx, scope, prefs).also { l ->
        l.readingBoxes = { _, _ ->
            readingCalls++
            gate?.await()
            readings
        }
        l.onBoxesSettled = { settled += it }
    }

    private fun data(vararg texts: String, withGroups: Boolean = true) = OneShotOverlayData(
        boxes = texts.map { TextBox(it, rect) },
        cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
        cropWidth = 200, cropHeight = 160,
        text = listOf(rect),
        groups = if (withGroups) listOf(group) else emptyList(),
    )

    private fun chipsView(l: CaptureOverlayLayer): TranslationOverlayView? =
        (0 until l.childCount).map { l.getChildAt(it) }.filterIsInstance<TranslationOverlayView>().firstOrNull()

    /** Lets a reading build hop off the main thread and back. */
    private fun settle(l: CaptureOverlayLayer) {
        val deadline = System.currentTimeMillis() + 3000
        while (l.pending && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `translation chips paint at once, skeletons included`() {
        val l = layer()
        l.bind(data(""), SourceLangId.JA, OverlayMode.TRANSLATION)
        assertTrue(l.hasBoxes)
        assertFalse(l.pending)
        assertEquals(1f, chipsView(l)!!.alpha)
        assertEquals(0, readingCalls)
    }

    @Test fun `translation data without chips paints nothing`() {
        val l = layer()
        l.bind(data(), SourceLangId.JA, OverlayMode.TRANSLATION)
        assertFalse(l.hasBoxes)
        assertFalse(l.expectsBoxes)
    }

    @Test fun `readings build off the main thread and settle with boxes`() {
        readings = listOf(readingBox)
        val l = layer()
        gate = CompletableDeferred()
        l.bind(data(""), SourceLangId.JA, OverlayMode.FURIGANA)
        // The build is held off the main thread: what the layer expects is
        // known before it lands, and nothing is drawn yet.
        assertTrue("expected before the paint lands", l.expectsBoxes)
        assertTrue(l.pending)
        assertFalse(l.hasBoxes)
        gate!!.complete(Unit)
        settle(l)
        assertTrue(l.hasBoxes)
        assertFalse(l.pending)
        assertEquals(listOf(true), settled)
        assertEquals(1, readingCalls)
    }

    @Test fun `readings that come back empty settle without boxes`() {
        readings = emptyList()
        val l = layer()
        l.bind(data(""), SourceLangId.JA, OverlayMode.FURIGANA)
        settle(l)
        assertFalse(l.hasBoxes)
        assertEquals(listOf(false), settled)
    }

    @Test fun `readings need groups and a source language`() {
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data("", withGroups = false), SourceLangId.JA, OverlayMode.FURIGANA)
        assertFalse(l.expectsBoxes)
        assertFalse(l.pending)
        l.bind(data(""), null, OverlayMode.FURIGANA)
        assertFalse(l.expectsBoxes)
        assertFalse(l.pending)
        assertEquals(0, readingCalls)
    }

    @Test fun `none paints nothing and expects nothing`() {
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.NONE)
        assertFalse(l.hasBoxes)
        assertFalse(l.expectsBoxes)
        assertEquals(0, readingCalls)
    }

    @Test fun `definitions is not painted here yet and is not supported`() {
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.DEFINITIONS)
        assertFalse(l.hasBoxes)
        assertFalse(OverlayMode.DEFINITIONS in l.supportedModes)
        assertEquals(
            setOf(OverlayMode.TRANSLATION, OverlayMode.FURIGANA, OverlayMode.NONE),
            l.supportedModes,
        )
    }

    @Test fun `a mode change re-flavors the same data in place`() {
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.TRANSLATION)
        assertTrue(l.hasBoxes)
        l.setMode(OverlayMode.FURIGANA)
        settle(l)
        assertTrue(l.hasBoxes)
        assertEquals(1, readingCalls)
        l.setMode(OverlayMode.NONE)
        assertFalse(l.hasBoxes)
        l.setMode(OverlayMode.TRANSLATION)
        assertTrue(l.hasBoxes)
        assertEquals("no second build for a translation paint", 1, readingCalls)
    }

    @Test fun `a switch from translations takes the chips down while the readings build`() {
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.TRANSLATION)
        assertTrue(l.hasBoxes)
        gate = CompletableDeferred()

        l.setMode(OverlayMode.FURIGANA)

        // Translations are not readings: they never show under the reading
        // mode while it builds, and the build keeps the host's place.
        assertFalse(l.hasBoxes)
        assertEquals(0f, chipsView(l)!!.alpha)
        assertTrue(l.pending)
        gate!!.complete(Unit)
        settle(l)
        assertTrue(l.hasBoxes)
        assertEquals(1f, chipsView(l)!!.alpha)
    }

    @Test fun `a re-bind in the reading mode keeps its readings up while they rebuild`() {
        // The sheet binds the same capture again when its result lands.
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data(""), SourceLangId.JA, OverlayMode.FURIGANA)
        settle(l)
        assertTrue(l.hasBoxes)
        gate = CompletableDeferred()

        l.bind(data("hi"), SourceLangId.JA, OverlayMode.FURIGANA)

        assertTrue("no blank between the two builds", l.hasBoxes)
        assertEquals(1f, chipsView(l)!!.alpha)
        assertTrue(l.pending)
        gate!!.complete(Unit)
        settle(l)
        assertTrue(l.hasBoxes)
    }

    @Test fun `a fill repaints translation chips and leaves a reading build alone`() {
        readings = listOf(readingBox)
        val l = layer()
        l.bind(data(""), SourceLangId.JA, OverlayMode.FURIGANA)
        settle(l)
        l.update(data("hi"))
        assertEquals("the groups did not change", 1, readingCalls)
        assertTrue(l.hasBoxes)
        l.setMode(OverlayMode.TRANSLATION)
        assertTrue(l.hasBoxes)
        assertEquals("hi", l.data!!.boxes.single().translatedText)
    }

    @Test fun `a mode change before a build lands discards the build`() {
        readings = listOf(readingBox)
        val l = layer()
        gate = CompletableDeferred()
        l.bind(data(""), SourceLangId.JA, OverlayMode.FURIGANA)
        assertTrue(l.pending)
        l.setMode(OverlayMode.NONE)
        assertFalse(l.pending)
        // The held build lands after the switch: it must not paint over the
        // mode that replaced it, nor report a settle.
        gate!!.complete(Unit)
        val deadline = System.currentTimeMillis() + 2000
        while (settled.isEmpty() && !l.hasBoxes && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(l.hasBoxes)
        assertEquals(OverlayMode.NONE, l.mode)
        assertTrue("a cancelled build never settles", settled.isEmpty())
    }

    @Test fun `clear drops the data and the boxes`() {
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.TRANSLATION)
        l.clear()
        assertFalse(l.hasBoxes)
        assertNull(l.data)
        assertFalse(l.expectsBoxes)
        assertTrue(l.drawnBoxRects().isEmpty())
    }

    @Test fun `a render setting changed since the paint replaces the view`() {
        val l = layer()
        l.bind(data("hi"), SourceLangId.JA, OverlayMode.TRANSLATION)
        val first = chipsView(l)!!
        l.update(data("hi"))
        assertSame(first, chipsView(l))
        prefs.overlayMinTextSp = Prefs.OVERLAY_MIN_TEXT_SP_DEFAULT + 4
        l.update(data("hi"))
        val second = chipsView(l)!!
        assertNotSame(first, second)
        assertEquals(1, (0 until l.childCount).count { l.getChildAt(it) is TranslationOverlayView })
    }
}
