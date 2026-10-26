package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CapturedFrame
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.ocr.ReaderAttribution
import com.playtranslate.translation.CooldownState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowNetwork

/**
 * What a re-OCR states to the reader router, seen at
 * [CaptureService.processScreenshot]'s warm call ([FrameReaders.warm]), which
 * runs before the crop and the encode: a re-OCR on the OCR picker's cloud
 * entry states no device-only, so with the OCR switch on, a key stored and a
 * network up the warm reads the key, on IO, to decide; a re-OCR on a device
 * engine states device-only and never warms; a fresh frame is not warmed
 * here at all, since its caller warms before its screenshot and a second warm
 * would be a second connection. The read itself is scripted
 * ([FrameReaders.overrideForTest]), so no cycle reaches the network. Mirrors
 * [HoldRouterStatementTest].
 */
@RunWith(RobolectricTestRunner::class)
class ReOcrRouterStatementTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null
    private lateinit var codec: PtCloudTestSupport.CountingCodec

    /** A reader that finds no text: the cycle ends at once, off the network. */
    private val scripted = object : FrameReader {
        override suspend fun read(bitmap: Bitmap, request: ReadRequest): FrameReading =
            FrameReading(null, ReaderAttribution("device", "device"), translations = null)
    }

    private fun clearPrefs() = PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()

    @Before fun setUp() {
        clearPrefs()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
        Prefs(ctx).apply {
            sourceLang = "ja"
            captureDisplayIds = setOf(0)
        }
        // The process's one cloud reader is built on the stored settings
        // first, so it never keeps this test's.
        FrameReaders.cloud
        // Configured before the key is there (HoldRouterStatementTest's
        // reason: configuring reconciles the translation cache, whose
        // registry walk could read the key). The OCR switch alone: with the
        // translation switch on, the registry walk's isUsable would read the
        // key and spoil the count.
        service!!.get().configureSaved(displayIds = setOf(0))
        codec = PtCloudTestSupport.CountingCodec()
        val prefs = Prefs(ctx, codec).apply {
            ptCloudOcrEnabled = true
            ptCloudClientKey = PtCloudTestSupport.KEY
        }
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        PtCloudTestSupport.offline(ctx).onAvailable(ShadowNetwork.newInstance(101))
        FrameReaders.overrideForTest = scripted
    }

    @After fun tearDown() {
        FrameReaders.overrideForTest = null
        service?.destroy()
        service = null
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    private fun frame() = CapturedFrame(
        Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888),
        includesSystemUi = false,
        includesOwnOverlays = false,
    )

    /** Runs a frame through processScreenshot: a re-OCR carries the pinned
     *  region and language as a result's gear does; a fresh frame carries
     *  neither. */
    private fun process(reOcr: Boolean, deviceOnly: Boolean) {
        service!!.get().processScreenshot(
            frame(),
            displayId = 0,
            regionOverride = if (reOcr) CaptureService.DEFAULT_REGION else null,
            sourceLangIdOverride = if (reOcr) SourceLangId.JA else null,
            deviceOnly = deviceOnly,
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Whether the warm read the key within a bound: it decides on IO. */
    private fun keyRead(): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (codec.decrypts.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        return codec.decrypts.get() > 0
    }

    @Test fun `a re-OCR on the cloud entry warms the cloud, reading the key to decide`() {
        process(reOcr = true, deviceOnly = false)
        assertTrue("the warm read the key to decide", keyRead())
    }

    @Test fun `a re-OCR on a device engine states device-only and never warms`() {
        process(reOcr = true, deviceOnly = true)
        assertTrue("no warm went out, so no key was read", !keyRead())
        assertEquals(0, codec.decrypts.get())
    }

    @Test fun `a fresh frame is not warmed here, its caller warms before the screenshot`() {
        process(reOcr = false, deviceOnly = false)
        assertTrue("no second warm", !keyRead())
        assertEquals(0, codec.decrypts.get())
    }
}
