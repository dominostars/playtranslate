package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FallbackFrameReader
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.NoTextMemo
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * [NoTextMemo] through the read it stands in front of
 * ([OverlayToolkit.runOcrPipeline]): a read that found nothing to translate
 * is made once, and the same OCR input asked the same question is that
 * answer again; one pixel of difference, a different question, a fallback's
 * reading or a reading nobody signed is a read. Native graphics, bitmaps
 * only: the crop, the blackout fill and [Bitmap.sameAs] are the platform's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoTextMemoTest {

    private val w = 224
    private val h = 96
    private val fullScreen = RegionEntry("", 0f, 1f)
    private val memo = NoTextMemo()
    private val scripted = ReaderAttribution("Scripted", "scripted")

    /** Finds [text] in every frame (null = nothing), signed [attribution],
     *  and counts its reads; throws [failure] instead when one is set. */
    private open inner class ScriptedReader(
        var text: String? = null,
        var attribution: ReaderAttribution? = scripted,
        var failure: Exception? = null,
    ) : FrameReader {
        var reads = 0
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            reads++
            failure?.let { throw it }
            val t = text ?: return FrameReading(null, attribution, translations = null)
            val bounds = Rect(40, 40, 90, 60)
            val group = OcrManager.OcrGroup(
                text = t, bounds = bounds,
                lines = listOf(OcrManager.LineBox(text = t, bounds = bounds, groupIndex = 0)),
            )
            return FrameReading(
                OcrManager.OcrResult(fullText = t, segments = emptyList(), groups = listOf(group)),
                attribution, translations = null,
            )
        }
    }

    /** Another kind of reader, as the device's is to the cloud's. */
    private inner class OtherReader : ScriptedReader()

    private val reader = ScriptedReader()

    /** A game-like frame, a two-tone checker, with [dots] set white. */
    private fun frame(vararg dots: Pair<Int, Int>): Bitmap {
        val dark = Color.rgb(40, 40, 40)
        val light = Color.rgb(200, 200, 200)
        val px = IntArray(w * h) { i -> if (((i % w) / 8 + (i / w) / 8) % 2 == 0) dark else light }
        for ((x, y) in dots) px[y * w + x] = Color.WHITE
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    private fun request(source: SourceLangId = SourceLangId.JA, frameId: String = "f") =
        ReadRequest(source, screenshotWidth = w, targetLang = "en", frameId = frameId)

    private fun read(
        raw: Bitmap,
        by: FrameReader = reader,
        request: ReadRequest = request(),
        statusBar: Int = 0,
        exclude: Rect? = null,
        chrome: List<Rect> = emptyList(),
        memo: NoTextMemo? = this.memo,
    ): OverlayToolkit.OcrPipelineRead = runBlocking {
        OverlayToolkit.runOcrPipeline(
            raw, fullScreen, statusBar, by, request,
            excludeRect = exclude, blackoutRects = chrome, noTextMemo = memo,
        )
    }

    // ── An empty read is made once ─────────────────────────────────────

    @Test fun `an empty read is made once, and the same input again is its answer`() {
        val first = read(frame())
        assertNull(first.result)
        repeat(3) {
            val again = read(frame())
            assertNull(again.result)
            assertEquals("who looked is who looked the first time", scripted, again.attribution)
        }
        assertEquals(1, reader.reads)
    }

    @Test fun `with no memo every read is made`() {
        repeat(3) { read(frame(), memo = null) }
        assertEquals(3, reader.reads)
    }

    @Test fun `one pixel is a read, and the screen it belongs to is remembered in its turn`() {
        read(frame())
        read(frame(100 to 50))
        assertEquals("a single pixel differs: read", 2, reader.reads)
        read(frame(100 to 50))
        assertEquals(2, reader.reads)
        read(frame())
        assertEquals("the first screen is not the one remembered any more", 3, reader.reads)
    }

    @Test fun `text in another script is nothing to translate, and is remembered like an empty read`() {
        reader.text = "Hello"
        assertNull("Latin text, Japanese source", read(frame()).result)
        assertNull(read(frame()).result)
        assertEquals(1, reader.reads)
    }

    // ── Text is never remembered ───────────────────────────────────────

    @Test fun `a read that finds text is made every time`() {
        reader.text = "こんにちは"
        repeat(3) { assertNotNull(read(frame()).result) }
        assertEquals(3, reader.reads)
    }

    @Test fun `a read that finds text forgets the empty read before it`() {
        read(frame())
        reader.text = "こんにちは"
        assertNotNull(read(frame(100 to 50)).result)
        reader.text = null
        read(frame())
        assertEquals("the first screen again: read again", 3, reader.reads)
    }

    // ── What the read ignores, the comparison ignores ──────────────────

    @Test fun `a pixel the read crops away is not a read, one inside the crop is`() {
        read(frame(), statusBar = 16)
        read(frame(100 to 5), statusBar = 16)
        assertEquals("the status bar's rows are not in the OCR input", 1, reader.reads)
        read(frame(100 to 50), statusBar = 16)
        assertEquals(2, reader.reads)
    }

    @Test fun `a pixel under this app's own chrome is not a read, one beside it is`() {
        val icon = listOf(Rect(40, 30, 80, 50))
        read(frame(), chrome = icon)
        read(frame(50 to 40), chrome = icon)
        assertEquals("blacked out of the OCR input both times", 1, reader.reads)
        read(frame(100 to 40), chrome = icon)
        assertEquals(2, reader.reads)
    }

    // ── The question is part of what is remembered ─────────────────────

    @Test fun `a new frame id alone is the same question`() {
        read(frame(), request = request(frameId = "d0-1"))
        read(frame(), request = request(frameId = "d0-2"))
        assertEquals(1, reader.reads)
    }

    @Test fun `another source language is another question`() {
        read(frame())
        read(frame(), request = request(source = SourceLangId.EN))
        assertEquals(2, reader.reads)
    }

    @Test fun `another rect to drop groups under is another question`() {
        read(frame(), exclude = Rect(0, 0, 50, 20))
        read(frame(), exclude = Rect(0, 0, 50, 20))
        assertEquals(1, reader.reads)
        read(frame(), exclude = null)
        assertEquals(2, reader.reads)
    }

    @Test fun `another kind of reader is asked, whatever the last one said`() {
        read(frame())
        val other = OtherReader()
        read(frame(), by = other)
        assertEquals("the router chose another reader: it reads", 1, other.reads)
        read(frame(), by = other)
        assertEquals(1, other.reads)
    }

    // ── Whose answer is remembered ─────────────────────────────────────

    @Test fun `a reading nobody signed, the empty engine's, is not remembered`() {
        reader.attribution = null
        repeat(3) { read(frame()) }
        assertEquals(3, reader.reads)
    }

    @Test fun `a fallback's reading is not remembered, so the reader asked is asked again`() {
        val cloud = ScriptedReader(failure = IOException("unreachable"))
        val device = ScriptedReader()
        val routed = { FallbackFrameReader(cloud, device) }
        assertNull(read(frame(), by = routed()).result)
        assertNull(read(frame(), by = routed()).result)
        assertEquals("the cloud is tried on every read while it fails", 2, cloud.reads)
        assertEquals(2, device.reads)
        // The cloud reads again and finds nothing: its own answer is kept.
        cloud.failure = null
        read(frame(), by = routed())
        read(frame(), by = routed())
        assertEquals(3, cloud.reads)
        assertEquals(2, device.reads)
    }

    @Test fun `a read that fails remembers nothing`() {
        reader.failure = IllegalStateException("engine")
        try {
            read(frame())
            fail("the failure propagates")
        } catch (_: IllegalStateException) {
        }
        reader.failure = null
        read(frame())
        assertEquals(2, reader.reads)
    }

    // ── The memo itself ────────────────────────────────────────────────

    private val asked = NoTextMemo.Asked.of(reader, request(), exclude = null)

    @Test fun `the remembered input is a copy the caller's bitmap does not back`() {
        val input = frame()
        memo.remember(input, asked, scripted)
        input.eraseColor(Color.WHITE)
        input.recycle()
        assertEquals(scripted, memo.recall(frame(), asked))
    }

    @Test fun `an input of another size is not the one remembered`() {
        memo.remember(frame(), asked, scripted)
        assertNull(memo.recall(Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888), asked))
    }

    @Test fun `forget leaves nothing to recall`() {
        memo.remember(frame(), asked, scripted)
        memo.forget()
        assertNull(memo.recall(frame(), asked))
    }

    @Test fun `the summary counts what was remembered and what was recalled`() {
        read(frame())
        read(frame())
        read(frame())
        read(frame(100 to 50))
        assertTrue(memo.summary(), memo.summary() == "no-text memo: remembered=2 recalled=2")
    }
}
