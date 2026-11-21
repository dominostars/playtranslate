package com.playtranslate.ui

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.CaptureSession
import com.playtranslate.CaptureState
import com.playtranslate.OcrManager
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.fillOneShotOverlayData
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.testutil.RecordingWindowManager
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.FakeSwitchedBackend
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.ui.ChosenServiceTranslation.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A service picked for the result on the over-game capture sheet
 * ([CaptureResultOverlay]'s side of [TranslationPicker]): the page's rule
 * ([TranslationResultViewModelPickTest]) in the sheet's own funnel, with the
 * boxes the sheet paints over the game.
 *
 * A capture's result has the gear on its credit. Its tap opens the picker in
 * a window over the game; a pick asks that one service about the result the
 * picker was opened for. While it runs the sheet's result is the object it
 * was (what a page opened from the sheet would be handed), its translation
 * dimmed under a "Translating…" line. It lands in one bind: the text, the
 * service as the credit, and the capture's boxes filled again group by
 * group and swapped in place. A failure leaves all of it and is said in an
 * alert.
 *
 * Whatever takes the result's place ends the run, so a late answer lands
 * nowhere: a status (an OCR tool switch re-reading the frame), another
 * capture's text, an edit of the source. That holds for a service whose
 * reply comes back after its run was ended, too. A sheet with no boxes to refill
 * (re-shown from its result alone) still takes the text. A host that paints
 * its picture's boxes itself offers no gear. The picker's settings button
 * starts the app's page and an over-game sheet makes way for it.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureSheetTranslationPickTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = RecordingWindowManager(OwnWindows.manager(ctx)!!)
    private val prefs = Prefs(ctx)
    private var sheet: CaptureResultOverlay? = null

    private val rects = listOf(Rect(100, 100, 300, 140), Rect(100, 160, 300, 200))
    private val groups = rects.mapIndexed { i, r ->
        OcrManager.OcrGroup("g$i", r, lines = listOf(OcrManager.LineBox("g$i", r, groupIndex = i)))
    }

    private val lingva = FakeSwitchedBackend(id = "lingva", priority = 10, displayName = "Lingva")
    private val gemini = FakeSwitchedBackend(id = "gemini", priority = 20, displayName = "Gemini", enabled = false)

    /** Each call of the sheet's seam, held open until the test answers. */
    private class Asked(val backend: TranslationBackend, val result: TranslationResult) {
        val answer = CompletableDeferred<Outcome>()
    }

    private val asked = mutableListOf<Asked>()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
        TranslationBackendRegistry.init(listOf(lingva, gemini))
    }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        TranslationBackendRegistry.close()
        sp().edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(o: Any, name: String): T =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T

    private fun layerOf(o: CaptureResultOverlay): CaptureOverlayLayer = field(o, "layer")
    private fun lastResult(o: CaptureResultOverlay): TranslationResult? = field(o, "lastResult")
    private fun rootOf(o: CaptureResultOverlay): ViewGroup = field(o, "root")

    private fun <V : View> view(o: CaptureResultOverlay, id: Int): V = rootOf(o).findViewById(id)
    private fun row(o: CaptureResultOverlay): View = view(o, R.id.translationNoteRow)
    private fun gear(o: CaptureResultOverlay): View = view(o, R.id.btnTranslationService)
    private fun credit(o: CaptureResultOverlay): String = view<TextView>(o, R.id.tvTranslationNote).text.toString()
    private fun translation(o: CaptureResultOverlay): TextView = view(o, R.id.tvTranslation)

    private fun open(configure: (CaptureResultOverlay) -> Unit = {}): CaptureResultOverlay {
        prefs.captureOverlayMode = OverlayMode.TRANSLATION
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.chosenTranslation = { _, backend, result -> Asked(backend, result).also { asked += it }.answer.await() }
        configure(o)
        o.show(1280, 720)
        idle()
        return o
    }

    private fun skeleton() = OneShotOverlayData(
        boxes = rects.map { TextBox("", it) },
        cropLeft = 0, cropTop = 0, screenshotW = 1280, screenshotH = 720,
        cropWidth = 1280, cropHeight = 720,
        text = rects,
        groups = groups,
    )

    private fun result(
        text: String = "g0 g1",
        translation: String = "one\n\ntwo",
        units: List<String>? = listOf("g0", "g1"),
    ) = TranslationResult(
        originalText = text,
        segments = listOf(TextSegment(text)),
        translatedText = translation,
        timestamp = "",
        backendDisplayName = "Lingva",
        langContext = prefs.langContext(),
        groupTexts = units,
    )

    /** A capture walked to its result on a new sheet: the two groups'
     *  boxes painted with "one" and "two". */
    private fun captured(
        withBoxes: Boolean = true,
        configure: (CaptureResultOverlay) -> Unit = {},
    ): Triple<CaptureResultOverlay, MutableStateFlow<CaptureState>, TranslationResult> {
        val o = open(configure)
        val flow = MutableStateFlow<CaptureState>(CaptureState.InProgress("Capturing"))
        o.observe(CaptureSession(flow, Job()))
        val done = result()
        if (withBoxes) {
            flow.value = CaptureState.Translating("g0 g1", listOf(TextSegment("g0 g1")), null, skeleton())
            idle()
            flow.value = CaptureState.Done(done, fillOneShotOverlayData(skeleton(), listOf("one", "two")))
        } else {
            flow.value = CaptureState.Done(done, null)
        }
        idle()
        return Triple(o, flow, done)
    }

    private fun boxTexts(o: CaptureResultOverlay): List<String>? = layerOf(o).data?.boxes?.map { it.translatedText }

    /** The alert last put up over the game. */
    private fun alertTexts(): List<String> =
        wm.added.last().first.allViews.filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun alertButton(label: String): Button =
        wm.added.last().first.allViews.filterIsInstance<Button>().single { it.text.toString() == label }

    private fun pick(o: CaptureResultOverlay, service: String) {
        row(o).performClick()
        idle()
        alertButton(service).performClick()
        idle()
    }

    private fun answer(index: Int, outcome: Outcome) {
        asked[index].answer.complete(outcome)
        idle()
    }

    private fun lingvaCredit() = ctx.getString(R.string.translation_source_label, "Lingva")

    // ── the gear and the picker ──────────────────────────────────────────

    @Test fun `a capture's result has the gear, and its tap opens the picker in a window over the game`() {
        val (o, _, _) = captured()
        assertEquals(lingvaCredit(), credit(o))
        assertEquals(View.VISIBLE, gear(o).visibility)
        assertTrue(row(o).isClickable)
        val windowsBefore = wm.added.size

        row(o).performClick()
        idle()

        assertEquals("one more window, the picker", windowsBefore + 1, wm.added.size)
        assertTrue(ctx.getString(R.string.translation_picker_title) in alertTexts())
        assertTrue("opening it asks nobody", asked.isEmpty())
    }

    // ── a pick ───────────────────────────────────────────────────────────

    @Test fun `while a pick runs the sheet's result is the object it was, drawn dimmed under a Translating line`() {
        val (o, _, done) = captured()

        pick(o, "Gemini")

        assertSame(gemini, asked.single().backend)
        assertSame("asked about the result the picker was opened for", done, asked.single().result)
        assertSame("what a page opened now would be handed: the whole old result", done, lastResult(o))
        assertEquals("one\n\ntwo", translation(o).text.toString())
        assertTrue("dimmed", translation(o).alpha < 1f)
        assertEquals(ctx.getString(R.string.status_translating), credit(o))
        assertEquals("no second pick over a running one", View.GONE, gear(o).visibility)
        assertEquals("the boxes say what they said", listOf("one", "two"), boxTexts(o))
    }

    @Test fun `a translation lands as the text, the credit and the capture's boxes filled again`() {
        val (o, _, done) = captured()
        pick(o, "Gemini")

        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertEquals("uno\n\ndos", translation(o).text.toString())
        assertEquals(1f, translation(o).alpha, 0f)
        assertEquals(ctx.getString(R.string.translation_source_label, "Gemini"), credit(o))
        assertEquals(View.VISIBLE, gear(o).visibility)
        assertEquals("each group's box took its new translation", listOf("uno", "dos"), boxTexts(o))
        val landed = lastResult(o)!!
        assertEquals("uno\n\ndos", landed.translatedText)
        assertEquals("Gemini", landed.backendDisplayName)
        assertEquals("still the capture's two groups, for the next pick", done.groupTexts, landed.groupTexts)
        assertEquals(done.originalText, landed.originalText)
    }

    @Test fun `a failed pick leaves the text, the credit and the boxes, and is said in an alert over the game`() {
        val (o, _, done) = captured()
        pick(o, "Gemini")
        val windowsBefore = wm.added.size

        answer(0, Outcome.Failed("gemini", "Gemini", BackendFailure(BackendFailureKind.SERVER_ERROR, 503)))

        assertSame(done, lastResult(o))
        assertEquals("one\n\ntwo", translation(o).text.toString())
        assertEquals(1f, translation(o).alpha, 0f)
        assertEquals(lingvaCredit(), credit(o))
        assertEquals(View.VISIBLE, gear(o).visibility)
        assertEquals(listOf("one", "two"), boxTexts(o))
        assertEquals("one more window, the alert", windowsBefore + 1, wm.added.size)
        assertTrue(ctx.getString(R.string.translation_pick_failed_title) in alertTexts())
        assertTrue(ctx.getString(R.string.translation_error_server, "Gemini") in alertTexts())
    }

    @Test fun `a sheet with no boxes to refill still takes the text`() {
        val (o, _, _) = captured(withBoxes = false)
        pick(o, "Gemini")

        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertEquals("uno\n\ndos", translation(o).text.toString())
        assertEquals(ctx.getString(R.string.translation_source_label, "Gemini"), credit(o))
    }

    // ── what ends a run ──────────────────────────────────────────────────

    @Test fun `a status ends the run, so a late answer does not land over the re-read's result`() {
        val (o, flow, _) = captured()
        pick(o, "Gemini")

        // The OCR tool was switched: the frame is being read again.
        flow.value = CaptureState.InProgress("Recognizing text")
        idle()
        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertEquals("the status stands", View.VISIBLE, field<View>(o, "statusText").visibility)
        val reRead = result(text = "h0 h1", translation = "three\n\nfour", units = listOf("h0", "h1"))
        flow.value = CaptureState.Done(reRead, null)
        idle()
        assertEquals("three\n\nfour", translation(o).text.toString())
        assertEquals(1f, translation(o).alpha, 0f)
        assertEquals(lingvaCredit(), credit(o))
        assertSame(reRead, lastResult(o))
    }

    @Test fun `an ended run whose service answers all the same lands nothing`() {
        // A request a cancellation does not interrupt: its reply comes back
        // to a run the status ended, as a reply already in hand does through
        // the release of a switched-off on-device model.
        var cameBack = false
        val (o, flow, done) = captured { sheet ->
            sheet.chosenTranslation = { _, backend, result ->
                val call = Asked(backend, result).also { asked += it }
                withContext(NonCancellable) { call.answer.await() }.also { cameBack = true }
            }
        }
        pick(o, "Gemini")

        flow.value = CaptureState.InProgress("Recognizing text")
        idle()
        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertTrue("the ended run's request did hand its reply back", cameBack)
        assertEquals("the status stands", View.VISIBLE, field<View>(o, "statusText").visibility)
        assertSame("the sheet's result is the capture's own", done, lastResult(o))
        assertEquals("Lingva", lastResult(o)?.backendDisplayName)
    }

    @Test fun `another capture's text ends the run`() {
        val (o, flow, _) = captured()
        pick(o, "Gemini")

        flow.value = CaptureState.Translating("next line", listOf(TextSegment("next line")), null, null)
        idle()
        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertEquals("the new text is still being translated", "next line", lastResult(o)?.originalText)
        assertEquals(ctx.getString(R.string.status_translating), translation(o).text.toString())
        assertEquals(1f, translation(o).alpha, 0f)
        assertEquals("no credit under a placeholder", View.GONE, row(o).visibility)
    }

    @Test fun `an edit of the source ends the run`() {
        val (o, _, _) = captured()
        pick(o, "Gemini")

        field<EditText>(o, "editText").setText("an edited line")
        CaptureResultOverlay::class.java.getDeclaredMethod("commitEdit").apply { isAccessible = true }.invoke(o)
        idle()
        answer(0, Outcome.Translated(listOf("uno", "dos"), "Gemini"))

        assertEquals("an edited line", lastResult(o)?.originalText)
        assertNull("the edited text is no longer the capture's groups", lastResult(o)?.groupTexts)
        assertFalse(
            "the translation of the old source did not land over the edit",
            translation(o).text.toString().contains("uno"),
        )
    }

    @Test fun `a pick made after another result took the sheet's place asks nobody`() {
        val (o, flow, _) = captured()
        row(o).performClick()
        idle()
        // A re-read lands under the open picker.
        flow.value = CaptureState.Done(result(text = "h0 h1", translation = "three\n\nfour", units = listOf("h0", "h1")), null)
        idle()

        alertButton("Gemini").performClick()
        idle()

        assertTrue(asked.isEmpty())
        assertEquals("three\n\nfour", translation(o).text.toString())
        assertEquals(1f, translation(o).alpha, 0f)
    }

    // ── hosts ────────────────────────────────────────────────────────────

    @Test fun `a host that paints its picture's boxes itself offers no gear`() {
        val (o, _, _) = captured { it.translationPickerOffered = false }

        assertEquals("the credit is still said", lingvaCredit(), credit(o))
        assertEquals(View.VISIBLE, row(o).visibility)
        assertEquals(View.GONE, gear(o).visibility)
        assertFalse(row(o).isClickable)
    }

    @Test fun `the picker's settings button starts Translation services and the over-game sheet makes way`() {
        val (o, _, _) = captured()
        row(o).performClick()
        idle()

        alertButton(ctx.getString(R.string.settings_cell_translation_services)).performClick()
        idle()

        val started = shadowOf(ctx as Application).nextStartedActivity
        assertEquals(TranslationServicesActivity::class.java.name, started?.component?.className)
        assertTrue("the app's page takes the screen", field<Boolean>(o, "dismissed"))
        assertTrue(asked.isEmpty())
    }
}
