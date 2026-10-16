package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.FallbackFrameReader
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.ocr.readLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [readLines], the one rule for a consumer that wants a frame's lines and no
 * translation (drag-to-lookup): it asks for the same-language pair (the
 * source's own translationCode as the target, so a translating reader asks
 * its service for none) with no menu split and the caller's frame id, cancels
 * whatever translations the reader hands over since nothing awaits them, and
 * returns the result's lines, or null when there are none. It catches
 * nothing: a reader that cannot read propagates, since the fallback rule is
 * [FallbackFrameReader]'s, and so does cancellation.
 *
 * Robolectric only for a [Bitmap] instance (legacy graphics: nothing is drawn
 * or read from it) and for [Rect].
 */
@RunWith(RobolectricTestRunner::class)
class ReadLinesTest {

    private class FakeReader(private val body: () -> FrameReading) : FrameReader {
        val requests = mutableListOf<ReadRequest>()
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            requests += request
            return body()
        }
    }

    private val bitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private val result = OcrManager.OcrResult(
        fullText = "はい",
        segments = emptyList(),
        groups = listOf(
            OcrManager.OcrGroup(
                text = "はい",
                bounds = Rect(0, 0, 4, 2),
                lines = listOf(OcrManager.LineBox(text = "はい", bounds = Rect(0, 0, 4, 2), groupIndex = 0)),
            ),
        ),
    )

    private fun reading(ocr: OcrManager.OcrResult?, translations: ReaderTranslations? = null) =
        FrameReading(ocr, attribution = null, translations = translations)

    @Test fun `the request is the source's same-language pair, with no menu split and the caller's frame id`() = runBlocking {
        for (id in SourceLangId.entries) {
            val reader = FakeReader { reading(null) }
            reader.readLines(bitmap, id, "d3-42")
            val request = reader.requests.single()
            assertEquals(id, request.sourceLangId)
            assertEquals("$id: no menu split", 0, request.screenshotWidth)
            assertEquals("$id: the source's own translationCode", SourceLanguageProfiles[id].translationCode, request.targetLang)
            assertEquals("d3-42", request.frameId)
        }
        // Spelled out, a source whose code is its translationCode and one
        // whose translationCode differs (Traditional Chinese translates as "zh").
        for ((id, target) in listOf(SourceLangId.JA to "ja", SourceLangId.ZH_HANT to "zh")) {
            val reader = FakeReader { reading(null) }
            reader.readLines(bitmap, id, "d3-42")
            assertEquals(target, reader.requests.single().targetLang)
        }
    }

    @Test fun `the reader's translations are cancelled, since nothing awaits them`() = runBlocking {
        val job = Job()
        val translations = ReaderTranslations("ja", "ja", "Reader", groupCount = 1, ready = emptyMap(), job = job)

        val lines = FakeReader { reading(result, translations) }.readLines(bitmap, SourceLangId.JA, "d0-1")

        assertTrue("cancelled by the time readLines returns", job.isCancelled)
        assertEquals(result.lines(), lines)
    }

    @Test fun `a reading with no translations reads its lines`() = runBlocking {
        val lines = FakeReader { reading(result) }.readLines(bitmap, SourceLangId.JA, "d0-1")

        assertEquals(result.lines(), lines)
        assertEquals(listOf("はい"), lines!!.map { it.text })
    }

    @Test fun `no text found is null`() = runBlocking {
        assertNull(FakeReader { reading(null) }.readLines(bitmap, SourceLangId.JA, "d0-1"))
    }

    @Test fun `a result whose groups hold no lines is null`() = runBlocking {
        val lineless = OcrManager.OcrResult(
            fullText = "はい",
            segments = emptyList(),
            groups = listOf(OcrManager.OcrGroup(text = "はい", bounds = Rect(0, 0, 4, 2))),
        )
        assertNull(FakeReader { reading(lineless) }.readLines(bitmap, SourceLangId.JA, "d0-1"))
    }

    @Test fun `the same-language request is one definition, which the furigana tier's read shares`() {
        for (id in SourceLangId.entries) {
            val request = ReadRequest.sameLanguage(id, screenshotWidth = 1920, frameId = "d0-7")
            assertEquals(id, request.sourceLangId)
            assertEquals(1920, request.screenshotWidth)
            assertEquals("$id: the source's own translationCode", SourceLanguageProfiles[id].translationCode, request.targetLang)
            assertEquals("d0-7", request.frameId)
        }
        assertEquals("zh", ReadRequest.sameLanguage(SourceLangId.ZH_HANT, 0, "d0-7").targetLang)
    }

    @Test fun `a reader that cannot read propagates its failure`() = runBlocking {
        val reader = FakeReader { throw IOException("offline") }
        try {
            reader.readLines(bitmap, SourceLangId.JA, "d0-1")
            fail("readLines adds no catch: the failure must propagate")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun `a cancelled read propagates`() = runBlocking {
        val reader = FakeReader { throw CancellationException("released") }
        try {
            reader.readLines(bitmap, SourceLangId.JA, "d0-1")
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("released", e.message)
        }
    }
}
