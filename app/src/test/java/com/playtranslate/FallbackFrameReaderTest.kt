package com.playtranslate

import android.graphics.Bitmap
import com.playtranslate.cloud.CloudRefusal
import com.playtranslate.cloud.PtCloudReadException
import com.playtranslate.cloud.PtCloudRefusedException
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FallbackFrameReader
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/**
 * [FallbackFrameReader]'s contract: the fallback reads only when the primary
 * could not read at all (an [IOException]) and the caller still wants the
 * read. Cancellation is never a failure to fall back from, and any other
 * exception is a bug that must surface rather than be papered over by a
 * second reader. The fallback's log line names the exception class only: a
 * message can carry a URL. A reading from the fallback is the fallback's own,
 * whatever the primary's failure was.
 *
 * Robolectric only for a [Bitmap] instance (legacy graphics: nothing is
 * drawn or read from it) and for [ShadowLog].
 */
@RunWith(RobolectricTestRunner::class)
class FallbackFrameReaderTest {

    private class FakeReader(private val body: suspend () -> FrameReading) : FrameReader {
        var calls = 0
        var lastRequest: ReadRequest? = null
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading {
            calls++
            lastRequest = request
            return body()
        }
    }

    private val bitmap: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private val request = ReadRequest(SourceLangId.JA, screenshotWidth = 4, targetLang = "en", frameId = "d0-1")

    private fun reading(label: String) =
        FrameReading(ocr = null, attribution = ReaderAttribution(label, label), translations = null)

    @Test fun `a primary that reads is the reading, and the fallback never runs`() = runBlocking {
        val primaryReading = reading("primary")
        val primary = FakeReader { primaryReading }
        val fallback = FakeReader { reading("fallback") }

        val result = FallbackFrameReader(primary, fallback).read(bitmap, request)

        assertSame(primaryReading, result)
        assertEquals(0, fallback.calls)
    }

    @Test fun `a primary that cannot read falls back, logging the class but not the message`() = runBlocking {
        ShadowLog.clear()
        val fallbackReading = reading("fallback")
        val primary = FakeReader { throw IOException("secret-detail https://example.invalid/k") }
        val fallback = FakeReader { fallbackReading }

        val result = FallbackFrameReader(primary, fallback).read(bitmap, request)

        assertSame(fallbackReading, result)
        assertEquals(1, fallback.calls)
        val lines = ShadowLog.getLogsForTag("FrameReader").map { it.msg }
        assertTrue("the fallback is logged: $lines", lines.any { "IOException" in it })
        assertFalse("no message text in the log: $lines", lines.any { "secret-detail" in it || "https" in it })
    }

    @Test fun `the cloud's classified failure falls back too, to the fallback's own reading`() = runBlocking {
        // Every field set, so the whole reading is seen to be the fallback's.
        val fallbackReading = FrameReading(
            ocr = OcrManager.OcrResult(fullText = "t", segments = emptyList()),
            attribution = ReaderAttribution("fallback", "fallback"),
            translations = ReaderTranslations("ja", "en", "fallback", groupCount = 1, ready = emptyMap(), job = Job()),
        )
        val primary = FakeReader {
            throw PtCloudReadException(BackendFailure(BackendFailureKind.NOT_RUNNING, 404), "pod stopped")
        }
        val fallback = FakeReader { fallbackReading }

        val result = FallbackFrameReader(primary, fallback).read(bitmap, request)

        assertSame(fallbackReading, result)
        assertEquals(1, fallback.calls)
    }

    @Test fun `the cloud's refusal before its upload falls back too, to the fallback's own reading`() = runBlocking {
        val fallbackReading = reading("fallback")
        val primary = FakeReader { throw PtCloudRefusedException(CloudRefusal.SWITCH_OFF) }
        val fallback = FakeReader { fallbackReading }

        val result = FallbackFrameReader(primary, fallback).read(bitmap, request)

        assertSame(fallbackReading, result)
        assertEquals(1, fallback.calls)
    }

    @Test fun `a cancelled primary propagates and the fallback never runs`() = runBlocking {
        val primary = FakeReader { throw CancellationException("released") }
        val fallback = FakeReader { reading("fallback") }

        try {
            FallbackFrameReader(primary, fallback).read(bitmap, request)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("released", e.message)
        }
        assertEquals(0, fallback.calls)
    }

    @Test fun `a primary that failed while the caller was cancelled does not start the fallback`() = runBlocking {
        val primary = FakeReader {
            // The caller is cancelled while the primary's call is in flight, and
            // the call then fails with an ordinary IOException (a cancelled socket).
            currentCoroutineContext().job.cancel()
            throw IOException("socket closed")
        }
        val fallback = FakeReader { reading("fallback") }

        val job = launch { FallbackFrameReader(primary, fallback).read(bitmap, request) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, fallback.calls)
    }

    @Test fun `any other primary failure propagates and the fallback never runs`() = runBlocking {
        val primary = FakeReader { throw IllegalStateException("bug") }
        val fallback = FakeReader { reading("fallback") }

        try {
            FallbackFrameReader(primary, fallback).read(bitmap, request)
            fail("a non-IO failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("bug", e.message)
        }
        assertEquals(0, fallback.calls)
    }

    @Test fun `the fallback reads with the very request the primary got, its hooks included`() = runBlocking {
        var hookCalls = 0
        val hooked = request.copy(beforeDeviceOcr = { hookCalls++ })
        val primary = FakeReader { throw IOException("offline") }
        val fallback = FakeReader { reading("fallback") }

        FallbackFrameReader(primary, fallback).read(bitmap, hooked)

        assertSame(hooked, fallback.lastRequest)
        fallback.lastRequest!!.beforeDeviceOcr!!.invoke()
        assertEquals("the device reader on the fallback path calls the caller's hook", 1, hookCalls)
    }
}
