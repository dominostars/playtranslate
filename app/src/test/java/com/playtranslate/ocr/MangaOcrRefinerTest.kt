package com.playtranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.playtranslate.language.TextAlignment
import com.playtranslate.language.TextOrientation
import com.playtranslate.ocr.core.DetectedRegion
import com.playtranslate.ocr.core.LayoutGroup
import com.playtranslate.ocr.core.OcrBox
import com.playtranslate.ocr.core.OcrImage
import com.playtranslate.ocr.core.RecognizedLine
import com.playtranslate.ocr.core.RecognizedRegion
import com.playtranslate.ocr.core.RegionOrigin
import com.playtranslate.ocr.core.synthesizeEvenCharBoxes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioural tests for [MangaOcrRefiner.refineWith] via an injected fake
 * [MangaOcrRefiner.BlockReader] (the production [MangaOcrRefiner.refine] supplies
 * MangaOcrBridge's real MNN session, which a unit test can't load). Robolectric for
 * `Rect`/`Bitmap`.
 *
 * Block mode: ONE decode per eligible [LayoutGroup] (both orientations), spliced
 * back per line through [com.playtranslate.ocr.core.BlockTextAligner] — whose own
 * geometry/guard behavior is pinned by BlockTextAlignerTest; here we assert the
 * refiner-level policy (eligibility gates, decode budget, normalization, adoption
 * vs rejection, best-effort fault handling).
 *
 * Serialization (the bridge [kotlinx.coroutines.sync.Mutex]) is a structural
 * property and not asserted here — a timing-based concurrency test would be flaky.
 */
@RunWith(RobolectricTestRunner::class)
class MangaOcrRefinerTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    /** Returns canned block text keyed on the requested region's top edge (block mode:
     *  the region is the GROUP bounds). Records call count + the budget it was given.
     *  Missing/blank → null (decode declined). */
    private class FakeReader(private val byTop: Map<Int, String>) : MangaOcrRefiner.BlockReader {
        var calls = 0
        var lastMaxTokens = -1

        override suspend fun read(
            image: OcrImage,
            region: DetectedRegion,
            maxTokens: Int,
        ): RecognizedRegion? {
            calls++
            lastMaxTokens = maxTokens
            val text = byTop[region.box.bounds.top]?.takeIf { it.isNotBlank() } ?: return null
            val line = RecognizedLine(text, region.box, region.orientation)
            return RecognizedRegion(text, region.box, region.orientation, -1f, listOf(line), RegionOrigin.LINE)
        }
    }

    /** A reader that throws on every call (simulating an OpenCV/MNN native fault). */
    private class FailingReader(private val ex: () -> Throwable) : MangaOcrRefiner.BlockReader {
        override suspend fun read(image: OcrImage, region: DetectedRegion, maxTokens: Int): RecognizedRegion? =
            throw ex()
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
    fun `an eligible group is decoded once as a whole block and spliced per line`() = runBlocking {
        val g = group(listOf(line("いたい", 0, true), line("何をする", 100, true)), vertical = true)
        val fake = FakeReader(mapOf(0 to "いったい何をする")) // base engine dropped the っ
        val res = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        val rg = res.groups.single()

        assertEquals("one decode per group, not per line", 1, fake.calls)
        assertEquals("decode count feeds the attribution", 1, res.decodedBlocks)
        assertEquals("re-joined with no separator for ja", "いったい何をする", rg.text)
        assertEquals("いったい", rg.lines[0].text)
        assertTrue("adopted line gets a char tier", rg.lines[0].chars.isNotEmpty())
        assertSame("untouched line keeps its instance", g.lines[1], rg.lines[1])
    }

    @Test
    fun `horizontal groups are eligible in block mode`() = runBlocking {
        val g = group(listOf(line("平く", 0, false)), vertical = false)
        val fake = FakeReader(mapOf(0 to "早く"))
        val rg = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").groups.single()

        assertEquals(1, fake.calls)
        assertEquals("早く", rg.text)
    }

    @Test
    fun `an identical reading keeps the group instance and its real boxes`() = runBlocking {
        val baseChars = synthesizeEvenCharBoxes("い", OcrBox.upright(Rect(0, 0, 40, 40)), vertical = true)
        val base = line("い", 0, true).copy(chars = baseChars)
        val g = group(listOf(base), vertical = true)
        val fake = FakeReader(mapOf(0 to "い"))

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame("an unchanged group is returned as the same instance", g, out.groups.single())
        assertSame("base char boxes are preserved (not re-synthesized)", baseChars, out.groups.single().lines[0].chars)
        assertEquals("a confirming read still counts as a scan (attribution)", 1, out.decodedBlocks)
    }

    @Test
    fun `a reading that fails alignment is rejected and the base kept`() = runBlocking {
        // Same length, zero agreement — a confident hallucination. The old per-line
        // "differs → adopt" policy would have taken it; the aligner's match-rate
        // guard must not.
        val g = group(listOf(line("こんにちは", 0, true)), vertical = true)
        val fake = FakeReader(mapOf(0 to "ありがとう"))

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertEquals(1, fake.calls)
        assertSame("an unalignable reading must not replace the base", g, out.groups.single())
        assertEquals("a rejected reading was still READ -> counts as a scan", 1, out.decodedBlocks)
    }

    @Test
    fun `a group with an over-long line is ineligible - never decoded`() = runBlocking {
        // 21 chars on one line: past the squash-resolution gate (MAX_LINE_CHARS=20),
        // exactly the shape the findings report shows manga-ocr garbling.
        val g = group(listOf(line("あ".repeat(21), 0, false)), vertical = false)
        val fake = FakeReader(mapOf(0 to "X".repeat(21)))

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame(g, out.groups.single())
        assertEquals("ineligible group must not reach the reader", 0, fake.calls)
        assertEquals("no decode -> no attribution", 0, out.decodedBlocks)
    }

    @Test
    fun `a group with too many lines is ineligible`() = runBlocking {
        val lines = (0 until 9).map { line("あ", it * 50, true) } // MAX_LINES = 8
        val g = group(lines, vertical = true)
        val fake = FakeReader(mapOf(0 to "あああああああああ"))

        assertSame(g, MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").groups.single())
        assertEquals(0, fake.calls)
    }

    @Test
    fun `a line whose slant disagrees with its group past the cap is ineligible`() = runBlocking {
        // Line at 45°, group at 0 — the crop (framed by the group's angle)
        // would misframe the line, so the group is skipped.
        val slanted = RecognizedLine(
            text = "あい",
            box = OcrBox(Rect(0, 0, 40, 40), 40f, 40f, angleDeg = 45f),
            orientation = TextOrientation.HORIZONTAL,
        )
        val g = LayoutGroup("あい", listOf(slanted), Rect(0, 0, 40, 40), TextOrientation.HORIZONTAL, TextAlignment.LEFT)
        val fake = FakeReader(mapOf(0 to "うえ"))

        assertSame(g, MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").groups.single())
        assertEquals(0, fake.calls)
    }

    @Test
    fun `a consistently slanted group is eligible and hands the reader its oriented box`() = runBlocking {
        val slanted = RecognizedLine(
            text = "いたい",
            box = OcrBox(Rect(10, 10, 120, 80), 100f, 40f, angleDeg = -20f),
            orientation = TextOrientation.HORIZONTAL,
        )
        val g = LayoutGroup(
            "いたい", listOf(slanted), Rect(10, 10, 120, 80),
            TextOrientation.HORIZONTAL, TextAlignment.LEFT,
            angleDeg = -20f, orientedWidth = 100f, orientedHeight = 40f,
        )
        var seenBox: OcrBox? = null
        val fake = MangaOcrRefiner.BlockReader { _, region, _ ->
            seenBox = region.box
            null // decline the read — eligibility + region shape is the assertion
        }
        MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertEquals(-20f, seenBox!!.angleDeg, 0f)
        assertEquals(100f, seenBox!!.orientedWidth, 0f)
        assertEquals(40f, seenBox!!.orientedHeight, 0f)
    }

    @Test
    fun `deskewAffine maps the oriented corner to the destination origin at every test angle`() {
        for (angle in listOf(0f, 25f, -25f, 30f, -30f)) {
            val box = OcrBox(Rect(51, 76, 349, 224), 300f, 48f, angle)
            val m = com.playtranslate.ocr.mangaocr.deskewAffine(box)
            val rad = Math.toRadians(angle.toDouble())
            val c = Math.cos(rad).toFloat()
            val s = Math.sin(rad).toFloat()
            val cx = box.bounds.exactCenterX()
            val cy = box.bounds.exactCenterY()
            // The oriented rect's top-left corner in screen space (clockwise-
            // positive y-down rotation about the center)...
            val px = cx + (-150f) * c - (-24f) * s
            val py = cy + (-150f) * s + (-24f) * c
            // ...must land on the destination origin; the center on the
            // destination center. A sign error doubles the skew and misses.
            assertEquals("corner x @ $angle", 0f, m[0] * px + m[1] * py + m[2], 0.01f)
            assertEquals("corner y @ $angle", 0f, m[3] * px + m[4] * py + m[5], 0.01f)
            assertEquals("center x @ $angle", 150f, m[0] * cx + m[1] * cy + m[2], 0.01f)
            assertEquals("center y @ $angle", 24f, m[3] * cx + m[4] * cy + m[5], 0.01f)
        }
    }

    @Test
    fun `ineligible groups are skipped while eligible ones still refine`() = runBlocking {
        val eligible = group(listOf(line("いたい", 0, true)), vertical = true)
        val oversized = group(listOf(line("あ".repeat(21), 500, false)), vertical = false)
        val fake = FakeReader(mapOf(0 to "いったい"))

        val out = MangaOcrRefiner.refineWith(fake, listOf(eligible, oversized), bitmap, "ja")
        assertEquals(1, fake.calls)
        assertEquals(1, out.decodedBlocks)
        assertEquals("いったい", out.groups[0].text)
        assertSame(oversized, out.groups[1])
    }

    @Test
    fun `the decode budget scales with the base reading length`() = runBlocking {
        val g = group(listOf(line("いたい何をする", 0, true)), vertical = true) // 7 chars
        val fake = FakeReader(mapOf(0 to "いたい何をする"))

        MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertEquals(MangaOcrRefiner.budgetFor(7), fake.lastMaxTokens)
        assertEquals("~1.5× base + slack", 14, fake.lastMaxTokens)
    }

    @Test
    fun `blank or missing recognition leaves the base group untouched`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val fake = FakeReader(emptyMap()) // reader returns null

        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertEquals(1, fake.calls)
        assertSame(g, out.groups.single())
        assertEquals("a no-output attempt must not feed the attribution", 0, out.decodedBlocks)
    }

    @Test
    fun `a junk-only candidate (cursor arrow) is dropped, base group preserved`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val fake = FakeReader(mapOf(0 to "▼")) // normalizes away to nothing
        val out = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja")
        assertSame("a candidate that cleans to nothing must not replace the base", g, out.groups.single())
        assertEquals("a normalized-to-junk attempt must not feed the attribution", 0, out.decodedBlocks)
    }

    @Test
    fun `a candidate is normalized (edge pipes stripped) before alignment`() = runBlocking {
        val g = group(listOf(line("いたい", 0, true)), vertical = true)
        val fake = FakeReader(mapOf(0 to "|いったい|")) // leading/trailing pipes are edge junk
        val rg = MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").groups.single()
        assertEquals("いったい", rg.lines[0].text)
        assertEquals("いったい", rg.text)
    }

    @Test
    fun `a candidate that normalizes back to the base text keeps the base group`() = runBlocking {
        val g = group(listOf(line("ありがとう", 0, true)), vertical = true)
        val fake = FakeReader(mapOf(0 to "ありがとう▼")) // trailing cursor stripped -> == base
        assertSame(
            "a candidate equal to base after cleaning preserves the base group (real boxes)",
            g, MangaOcrRefiner.refineWith(fake, listOf(g), bitmap, "ja").groups.single(),
        )
    }

    @Test
    fun `a reader failure keeps the base groups (best-effort, never sinks the capture)`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val boom = FailingReader { RuntimeException("native decode failed") }

        val out = MangaOcrRefiner.refineWith(boom, listOf(g), bitmap, "ja")
        assertSame("base OCR result must survive a refinement failure", g, out.groups.single())
        assertEquals("a failed pass contributes nothing -> no attribution", 0, out.decodedBlocks)
    }

    @Test
    fun `cancellation propagates rather than being swallowed as best-effort`() = runBlocking {
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        val cancelling = FailingReader { CancellationException("superseded frame") }
        try {
            MangaOcrRefiner.refineWith(cancelling, listOf(g), bitmap, "ja")
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — a superseded frame must cancel, not silently fall back
        }
    }

    @Test
    fun `an aborted decode surfaces cancellation instead of a base-kept result`() = runBlocking {
        // The session's cooperative abort returns a null reading WITHOUT throwing
        // (MangaOcrSession polls shouldContinue between decode steps). The refiner
        // must convert that into CancellationException at its own boundary — even on
        // the last eligible group, where no later per-group check runs — rather than
        // hand a "successful" base-kept result back to a superseded frame. Run in a
        // child job: cancelling runBlocking's own job would mask the behavior under
        // the coroutine builder's completion check.
        val g = group(listOf(line("あ", 0, true)), vertical = true)
        var thrown: Throwable? = null
        var returned: MangaOcrRefiner.Refined? = null
        val worker = launch {
            val self = coroutineContext[Job]!!
            val aborting = MangaOcrRefiner.BlockReader { _, _, _ ->
                self.cancel() // frame superseded mid-decode
                null          // the abort path: no reading, no throw
            }
            try {
                returned = MangaOcrRefiner.refineWith(aborting, listOf(g), bitmap, "ja")
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        worker.join()
        assertTrue("expected CancellationException, got result=$returned", thrown is CancellationException)
        assertEquals(null, returned)
    }
}
