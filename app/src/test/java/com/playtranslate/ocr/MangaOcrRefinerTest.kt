package com.playtranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrCapabilities
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.OcrOrientationSupport
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.TextRecognizer
import com.playtranslate.ocr.core.synthesizeEvenCharBoxes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioural tests for [MangaOcrRefiner.refineWith] via an injected fake recognizer
 * (the production [MangaOcrRefiner.refine] supplies MangaOcrBridge's real MNN session,
 * which a unit test can't load). Robolectric for `Rect`/`Bitmap`.
 *
 * Serialization (the frame-level [kotlinx.coroutines.sync.Mutex]) is a structural
 * property mirrored from DetectThenRecognize and not asserted here — a timing-based
 * concurrency test would be flaky.
 */
@RunWith(RobolectricTestRunner::class)
class MangaOcrRefinerTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    /** Returns canned text keyed on the region's top edge (mirrors what
     *  MangaOcrRecognizer emits: text + synthesized chars). Missing/blank → null. */
    private class FakeRecognizer(private val byTop: Map<Int, String>) : TextRecognizer {
        var calls = 0
        override val capabilities = OcrCapabilities(
            OcrOrientationSupport.BOTH, true, false, false, false, true, false,
        )

        override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? {
            calls++
            val text = byTop[region.box.bounds.top]?.takeIf { it.isNotBlank() } ?: return null
            val line = RecognizedLine(
                text = text,
                box = region.box,
                orientation = region.orientation,
                chars = synthesizeEvenCharBoxes(
                    text, region.box.bounds, region.orientation == TextOrientation.VERTICAL,
                ),
            )
            return RecognizedRegion(text, region.box, region.orientation, -1f, listOf(line), RegionOrigin.LINE)
        }

        override fun close() {}
    }

    private fun line(text: String, top: Int, vertical: Boolean) = RecognizedLine(
        text = text,
        box = OcrBox.upright(Rect(0, top, 40, top + 40)),
        orientation = if (vertical) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL,
    )

    private fun group(lines: List<RecognizedLine>, vertical: Boolean) = LayoutGroup(
        text = lines.joinToString("") { it.text },
        lines = lines,
        bounds = Rect(0, lines.first().box.bounds.top, 40, lines.last().box.bounds.bottom),
        orientation = if (vertical) TextOrientation.VERTICAL else TextOrientation.HORIZONTAL,
        alignment = TextAlignment.LEFT,
    )

    @Test
    fun `vertical line with changed text is replaced and the group is re-joined`() = runBlocking {
        val g = group(listOf(line("あ", 0, true), line("い", 100, true)), vertical = true)
        val fake = FakeRecognizer(mapOf(0 to "ア", 100 to "い")) // line 0 changes, line 1 same
        val rg = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").single()

        assertEquals("re-joined with no separator for ja", "アい", rg.text)
        assertEquals("ア", rg.lines[0].text)
        assertTrue("replaced line gets synthesized chars", rg.lines[0].chars.isNotEmpty())
        assertEquals("い", rg.lines[1].text)
    }

    @Test
    fun `vertical line with identical text keeps the base line and its real boxes`() = runBlocking {
        val baseChars = synthesizeEvenCharBoxes("い", Rect(0, 0, 40, 40), vertical = true)
        val base = line("い", 0, true).copy(chars = baseChars)
        val g = group(listOf(base), vertical = true)
        val fake = FakeRecognizer(mapOf(0 to "い")) // same text → no change

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame("an unchanged group is returned as the same instance", g, out.single())
        assertSame("base char boxes are preserved (not re-synthesized)", baseChars, out.single().lines[0].chars)
    }

    @Test
    fun `horizontal lines are never sent to manga-ocr`() = runBlocking {
        val g = group(listOf(line("hello", 0, false)), vertical = false)
        val fake = FakeRecognizer(mapOf(0 to "XXXXX")) // would change if (wrongly) called

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame(g, out.single())
        assertEquals("recognizer is not invoked for horizontal lines", 0, fake.calls)
    }

    @Test
    fun `blank or missing recognition leaves the base line untouched`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val fake = FakeRecognizer(emptyMap()) // recognizer returns null

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame(g, out.single())
    }

    @Test
    fun `a junk-only candidate (cursor arrow) is dropped, base line preserved`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val fake = FakeRecognizer(mapOf(0 to "▼")) // normalizes away to nothing
        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame("a candidate that cleans to nothing must not replace the base", g, out.single())
    }

    @Test
    fun `a candidate is normalized (edge pipes stripped) before adoption`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val fake = FakeRecognizer(mapOf(0 to "|ありがとう|")) // leading/trailing pipes are edge junk
        val rg = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").single()
        assertEquals("ありがとう", rg.lines[0].text)
        assertEquals("ありがとう", rg.text)
    }

    @Test
    fun `a candidate that normalizes back to the base text keeps the base line`() = runBlocking {
        val g = group(listOf(line("ありがとう", 0, true)), vertical = true)
        val fake = FakeRecognizer(mapOf(0 to "ありがとう▼")) // trailing cursor stripped -> == base
        assertSame(
            "a candidate equal to base after cleaning preserves the base line (real boxes)",
            g, MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").single(),
        )
    }

    /** A recognizer that throws on every call (simulating an OpenCV/MNN native fault). */
    private class FailingRecognizer(private val ex: () -> Throwable) : TextRecognizer {
        override val capabilities = OcrCapabilities(
            OcrOrientationSupport.BOTH, true, false, false, false, true, false,
        )
        override suspend fun recognize(image: OcrImage, region: DetectedRegion): RecognizedRegion? = throw ex()
        override fun close() {}
    }

    @Test
    fun `a recognizer failure keeps the base groups (best-effort, never sinks the capture)`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val boom = FailingRecognizer { RuntimeException("native decode failed") }

        val out = MangaOcrRefiner.refineWith(boom, listOf(g), bitmap, "ja")
        assertSame("base OCR result must survive a refinement failure", g, out.single())
    }

    @Test
    fun `cancellation propagates rather than being swallowed as best-effort`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val cancelling = FailingRecognizer { CancellationException("superseded frame") }
        try {
            MangaOcrRefiner.refineWith(cancelling, listOf(g), bitmap, "ja")
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — a superseded frame must cancel, not silently fall back
        }
    }
}
