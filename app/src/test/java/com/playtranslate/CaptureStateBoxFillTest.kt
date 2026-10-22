package com.playtranslate

import android.graphics.Rect
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TextBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A one-shot session re-emits [CaptureState.Translating] as translations arrive
 * group by group, and its collectors update only the boxes for such a fill.
 * Pins the two halves of telling a fill from a new capture:
 * [CaptureState.Translating.fillsBoxesOf] compares everything but the boxes, and
 * [CaptureSession.stateWithPrevious] scopes the state it compares against to one
 * collection of one session. Robolectric for [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class CaptureStateBoxFillTest {

    private val drawn = listOf(Rect(10, 10, 110, 40), Rect(10, 60, 110, 90))

    /** Fresh instances on every call, so equality is by value, as it is across
     *  emissions that rebuild the source or the provenance. */
    private fun segments(text: String = "ソース") = listOf(TextSegment(text))

    private fun provenance(engineToken: String = "mlkit") = OcrProvenance(
        engineLabel = "ML Kit",
        engineToken = engineToken,
        displayId = 0,
        sourceLangId = SourceLangId.JA,
        region = RegionEntry(label = "", top = 0f, bottom = 1f, id = "full"),
    )

    /** The capture's boxes with [texts] as their translations ("" is a skeleton). */
    private fun boxes(vararg texts: String) = OneShotOverlayData(
        boxes = drawn.mapIndexed { i, r -> TextBox(texts[i], Rect(r), drawBounds = Rect(r)) },
        cropLeft = 0, cropTop = 0, screenshotW = 400, screenshotH = 300,
        cropWidth = 200, cropHeight = 160,
        text = drawn.map { Rect(it) },
    )

    private fun translating(
        text: String = "ソース",
        segments: List<TextSegment> = segments(text),
        provenance: OcrProvenance? = provenance(),
        overlay: OneShotOverlayData? = boxes("", ""),
    ) = CaptureState.Translating(text, segments, provenance, overlay)

    // ─── fillsBoxesOf ───────────────────────────────────────────────────

    @Test fun `more boxes filled for the same capture is a box fill`() {
        val skeleton = translating()
        assertTrue(translating(overlay = boxes("source", "")).fillsBoxesOf(skeleton))
        assertTrue(translating(overlay = boxes("source", "text")).fillsBoxesOf(translating(overlay = boxes("source", ""))))
    }

    @Test fun `an identical state is a box fill`() {
        // A StateFlow never re-emits an equal value, so this cell is never
        // handled; true is the honest answer for it.
        assertTrue(translating().fillsBoxesOf(translating()))
    }

    @Test fun `different source text is not a box fill`() {
        // Same segments on purpose, so the text alone decides.
        assertFalse(translating(text = "別のソース", segments = segments()).fillsBoxesOf(translating()))
    }

    @Test fun `different segments of the same text are not a box fill`() {
        val resplit = listOf(TextSegment("ソ"), TextSegment("ース"))
        assertFalse(translating(segments = resplit).fillsBoxesOf(translating()))
    }

    @Test fun `different provenance is not a box fill`() {
        assertFalse(translating(provenance = provenance(engineToken = "meiki")).fillsBoxesOf(translating()))
        assertFalse(translating(provenance = null).fillsBoxesOf(translating()))
    }

    @Test fun `a first Translating is not a box fill, even with boxes already filled`() {
        assertFalse(translating(overlay = boxes("source", "")).fillsBoxesOf(null))
    }

    @Test fun `a Translating after any other state is not a box fill`() {
        val done = CaptureState.Done(
            TranslationResult(
                originalText = "ソース",
                segments = segments(),
                translatedText = "source",
                timestamp = "00:00:00",
                langContext = TranslationLangContext(SourceLangId.JA, "en", ChineseScriptVariant.SIMPLIFIED),
            ),
        )
        for (previous in listOf(CaptureState.InProgress("OCR"), done, CaptureState.NoText("none"))) {
            assertFalse(translating().fillsBoxesOf(previous))
        }
    }

    // ─── stateWithPrevious ──────────────────────────────────────────────

    @Test fun `one collection pairs each state with the one it received before`() = runBlocking {
        val ocr = CaptureState.InProgress("OCR")
        val skeleton = translating()
        val fill = translating(overlay = boxes("source", ""))
        val state = MutableStateFlow<CaptureState>(ocr)
        val session = CaptureSession(state, Job())
        val next = ArrayDeque(listOf(skeleton, fill))

        val pairs = session.stateWithPrevious
            .take(3)
            .onEach { next.removeFirstOrNull()?.let { state.value = it } }
            .toList()

        assertEquals(listOf(null to ocr, ocr to skeleton, skeleton to fill), pairs)
    }

    /** Collects [session] from its skeleton through [fill], as a collector that
     *  handled both before it stopped. */
    private suspend fun collectThroughFill(session: CaptureSession, fill: CaptureState): List<Pair<CaptureState?, CaptureState>> {
        val state = session.state as MutableStateFlow<CaptureState>
        return session.stateWithPrevious
            .take(2)
            .onEach { (_, current) -> if (current != fill) state.value = fill }
            .toList()
    }

    @Test fun `a fresh collection of the same session starts with no previous`() = runBlocking {
        // A lifecycle STOP/START re-collects mid-session: its first Translating
        // is handled in full even though an earlier collection handled the
        // skeleton before it.
        val fill = translating(overlay = boxes("source", ""))
        val session = CaptureSession(MutableStateFlow<CaptureState>(translating()), Job())
        assertEquals(listOf(null to translating(), translating() to fill), collectThroughFill(session, fill))

        assertEquals(null to fill, session.stateWithPrevious.first())
    }

    @Test fun `a new capture of an identical screen starts with no previous`() = runBlocking {
        val fill = translating(overlay = boxes("source", ""))
        val first = CaptureSession(MutableStateFlow<CaptureState>(translating()), Job())
        collectThroughFill(first, fill)
        val second = CaptureSession(MutableStateFlow<CaptureState>(fill), Job())

        val (previous, state) = second.stateWithPrevious.first()
        assertEquals(null, previous)
        assertFalse((state as CaptureState.Translating).fillsBoxesOf(previous))
    }
}
