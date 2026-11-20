package com.playtranslate.ui

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
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
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The over-game capture sheet with its mode pill: what each mode paints, when
 * a collapsed start posture is honored, the rescue when a reading build lands
 * empty, the pill's picks, and the deferred translation a parked sheet waits
 * with until it leaves the park or switches to Translations. The pill as
 * the capture's sign of work: up with a spinner from the first status, the
 * control once the text is read, gone for no text or a failure, and out
 * with the sheet as it closes.
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
        ShadowChoreographer.setPaused(false)
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

    /** [v] is drawn in [p]: it and every ancestor below the pill visible,
     *  none faded out. */
    private fun shown(v: View, p: OverlayModePill): Boolean =
        generateSequence(v) { it.parent as? View }.takeWhile { it !== p }
            .all { it.visibility == View.VISIBLE && it.alpha > 0f }

    private fun shownSegments(p: OverlayModePill): List<String> =
        descendants(p).filterIsInstance<TextView>().filter { it.isClickable && shown(it, p) }.map { it.text.toString() }

    private fun spinnerShown(p: OverlayModePill): Boolean =
        descendants(p).filterIsInstance<ProgressBar>().any { shown(it, p) }

    /** The disc's glyph: the pill's one image whose tap expands it. */
    private fun glyphShown(p: OverlayModePill): Boolean =
        descendants(p).filterIsInstance<ImageView>().any {
            shown(it, p) && it.contentDescription != ctx.getString(R.string.capture_overlay_pill_collapse_cd)
        }

    /** Past every morph and fade, and the sheet's exit slide. */
    private fun idleLong() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    /** Frames come as the clock is moved, so an exit can be seen under
     *  way; the runtime would otherwise run it out in one turn of the
     *  looper. [tearDown] hands it back. */
    private fun paceFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /** Let what is animating run out, a frame at a time. */
    private fun land() = repeat(60) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    @Test fun `translations paints the chips and offers the modes the layer can paint`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idle()
        assertTrue(layerOf(o).hasBoxes)
        assertEquals(View.VISIBLE, pillOf(o).visibility)
        assertEquals(listOf("Translation", "Furigana", "Definitions", "None"), segments(pillOf(o)))

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

    @Test fun `a sheet back from a page never starts parked, with no boxes to stand on`() {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        prefs.capturePanelPosture = CaptureResultGeometry.COLLAPSED_POSTURE
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        assertTrue("the posture alone would park it", o.willOpenParked())
        o.showWithResult(1280, 720, result())
        idle()
        assertFalse(parked(o))
        assertFalse(layerOf(o).hasBoxes)
        assertEquals("no pill without boxes", 0, pillOf(o).childCount)
    }

    @Test fun `closing a sheet back from a page keeps a parked posture for the next capture`() {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        prefs.capturePanelPosture = CaptureResultGeometry.COLLAPSED_POSTURE
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.showWithResult(1280, 720, result())
        idle()
        assertFalse(parked(o))

        o.dismiss()
        idle()
        assertEquals(CaptureResultGeometry.COLLAPSED_POSTURE, prefs.capturePanelPosture, 0f)
        val next = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        assertTrue("the next capture still opens parked", next.willOpenParked())
    }

    @Test fun `the pill is not up before the capture's first status`() {
        val o = open(OverlayMode.TRANSLATION)
        assertEquals(View.GONE, pillOf(o).visibility)
        assertEquals(0, pillOf(o).childCount)
    }

    // ── the pill as the capture's sign of work ───────────────────────────

    @Test fun `the pill is up with a spinner from the first status and opens to the control once the text is read`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        idle()
        val pill = pillOf(o)
        assertEquals(View.VISIBLE, pill.visibility)
        assertTrue(spinnerShown(pill))
        assertTrue("nothing to pick from while it reads", shownSegments(pill).isEmpty())
        assertFalse(glyphShown(pill))

        flow.value = CaptureState.InProgress("Recognizing text")
        idleLong()
        assertTrue("still reading", spinnerShown(pill))

        flow.value = translating(data(""))
        idleLong()
        assertEquals(listOf("Translation", "Furigana", "Definitions", "None"), shownSegments(pill))
        assertFalse(spinnerShown(pill))
        assertFalse("opening it was not the user's doing", prefs.captureOverlayPillCollapsed)
    }

    @Test fun `a pill the user left collapsed stays a disc, its spinner giving way to the mode's glyph`() {
        prefs.captureOverlayPillCollapsed = true
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        idle()
        val pill = pillOf(o)
        assertTrue(spinnerShown(pill))
        assertFalse(glyphShown(pill))

        flow.value = translating(data(""))
        idleLong()
        assertTrue(glyphShown(pill))
        assertFalse(spinnerShown(pill))
        assertTrue(shownSegments(pill).isEmpty())
        assertTrue(prefs.captureOverlayPillCollapsed)
    }

    @Test fun `no text and a failure take the pill away, and a read that starts again brings it back`() {
        for (terminal in listOf(CaptureState.NoText("No text"), CaptureState.Failed("Failed"))) {
            val o = open(OverlayMode.TRANSLATION)
            val (flow, s) = session()
            o.observe(s)
            idle()
            val pill = pillOf(o)
            assertEquals(View.VISIBLE, pill.visibility)

            flow.value = terminal
            idleLong()
            assertEquals("$terminal", View.GONE, pill.visibility)

            flow.value = CaptureState.InProgress("Recognizing text")
            idleLong()
            assertEquals("$terminal", View.VISIBLE, pill.visibility)
            assertTrue(spinnerShown(pill))
            o.dismiss()
            idle()
        }
    }

    @Test fun `a parked strip keeps its tap hint while the capture reads`() {
        val o = open(OverlayMode.TRANSLATION, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        val (flow, s) = session()
        o.observe(s)
        flow.value = CaptureState.InProgress("Recognizing text")
        idle()
        assertTrue(parked(o))
        val hint: TextView = field(o, "sliverHint")
        assertEquals(ctx.getString(R.string.capture_sliver_expand_hint), hint.text.toString())
        assertTrue("the pill's spinner is the sign of the work", spinnerShown(pillOf(o)))
    }

    @Test fun `the width the hint is placed by is the width it draws at, cut off or not`() {
        val o = open(OverlayMode.TRANSLATION, posture = CaptureResultGeometry.COLLAPSED_POSTURE)
        val hint: TextView = field(o, "sliverHint")
        val placedBy = CaptureResultOverlay::class.java.getDeclaredMethod("fullHintRowWidthPx")
            .apply { isAccessible = true }
        fun drawn(): Int {
            val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            hint.measure(free, free)
            return hint.measuredWidth
        }
        val whole = drawn()
        assertTrue("the glyph and its gap lead the text", whole > hint.paint.measureText(hint.text.toString()))
        assertTrue("the display leaves it room", whole < hint.maxWidth)
        assertEquals("with room", whole, placedBy.invoke(o))

        // A bound the whole hint does not fit: a long locale at a large font.
        hint.maxWidth = whole - 2
        assertEquals("the text trails off at the bound", whole - 2, drawn())
        assertEquals("cut off", whole - 2, placedBy.invoke(o))
    }

    @Test fun `closing the sheet fades the pill out with it, its bar closing, and remembers nothing`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        flow.value = translating(data(""))
        idleLong()
        val pill = pillOf(o)
        assertEquals(4, shownSegments(pill).size)

        o.animateOutAndDismiss()
        assertTrue("the bar is closing", field<android.animation.ValueAnimator?>(pill, "morph")?.isStarted == true)
        assertEquals("up while it fades", View.VISIBLE, pill.visibility)
        idleLong()
        assertEquals(View.GONE, pill.visibility)
        assertFalse("the close is not the user collapsing it", prefs.captureOverlayPillCollapsed)
    }

    @Test fun `a result that lands under the exit slide does not bring the pill back`() {
        val o = open(OverlayMode.TRANSLATION)
        val (flow, s) = session()
        o.observe(s)
        idle()
        val pill = pillOf(o)
        assertTrue(spinnerShown(pill))

        paceFrames()
        o.animateOutAndDismiss()
        flow.value = translating(data(""))
        idle()
        assertFalse("still sliding out", field<Boolean>(o, "dismissed"))
        assertTrue("the result did land", layerOf(o).hasBoxes)

        land()
        assertTrue(field<Boolean>(o, "dismissed"))
        assertEquals(View.GONE, pill.visibility)
    }
}
