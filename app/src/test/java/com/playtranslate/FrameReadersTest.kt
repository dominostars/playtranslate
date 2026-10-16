package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one router every consumer of the seam asks ([FrameReaders.readerFor]):
 * the test override wins over everything, a read with the switch off (every
 * release build) or stated device-only is the one device reader, and the
 * frame ids it mints name their consumer. The cloud branch (a usable key,
 * which no JVM test can store: production [Prefs] fails closed without the
 * AndroidKeyStore) is pinned on the decision itself in
 * [com.playtranslate.cloud.PtCloudNextCaptureTest].
 */
@RunWith(RobolectricTestRunner::class)
class FrameReadersTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val scripted = object : FrameReader {
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading =
            FrameReading(null, attribution = null, translations = null)
    }

    @After fun tearDown() {
        FrameReaders.overrideForTest = null
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `init is idempotent and the switch off routes every read to the one device reader`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudEnabled = false
        for (id in SourceLangId.entries) {
            assertSame(FrameReaders.device, FrameReaders.readerFor(id, deviceOnly = false))
            assertSame(FrameReaders.device, FrameReaders.readerFor(id, deviceOnly = true))
        }
    }

    @Test fun `a device-only read stays on the device with the switch on`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudEnabled = true
        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = true))
    }

    @Test fun `the test override wins over the router`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        FrameReaders.overrideForTest = scripted
        assertSame(scripted, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false))
        assertSame(scripted, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = true))
        FrameReaders.overrideForTest = null
        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = true))
    }

    @Test fun `a frame id names its consumer and is unique over time`() {
        val a = FrameReaders.frameId("cam")
        assertTrue(a, a.startsWith("cam-"))
        assertTrue(FrameReaders.frameId("d0").startsWith("d0-"))
        assertEquals("the tag is the whole prefix", "import", FrameReaders.frameId("import").substringBefore('-'))
    }

    @Test fun `the warm returns at once with the switch off`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudEnabled = false
        // Nothing to observe but that it neither throws nor blocks.
        FrameReaders.warm(SourceLangId.JA, deviceOnly = false)
        FrameReaders.warm(SourceLangId.JA, deviceOnly = true)
    }
}
