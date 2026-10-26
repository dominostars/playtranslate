package com.playtranslate

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.translation.CooldownState
import org.junit.After
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
 * What a press-and-hold states to the reader router
 * ([FrameReaders.readerFor], [FrameReaders.warm]): never device-only, as the
 * live tiers, the furigana hold included. Seen at the hold's warm call,
 * which runs before its screenshot: with the switch on, a key stored and a
 * network up, a warm stating device-only returns before the router's
 * decision and never reads the key, and one that does not reads it, on IO,
 * to decide. The hold's read itself sits behind its screenshot, which no
 * capture source serves on the JVM, so what the read states is not seen
 * here. The process's one client sends nothing: its settings are the
 * stored ones, on the AndroidKeyStore codec, which holds no key here.
 */
@RunWith(RobolectricTestRunner::class)
class HoldRouterStatementTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private var service: ServiceController<CaptureService>? = null

    private fun clearPrefs() = PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()

    @Before fun setUp() {
        clearPrefs()
        MainActivity.isInForeground = false
        service = Robolectric.buildService(CaptureService::class.java).create()
    }

    @After fun tearDown() {
        service?.get()?.holdEnd()
        service?.destroy()
        service = null
        PtCloudService.installForTest(null)
        clearPrefs()
    }

    @Test fun `a furigana hold warms the cloud for its read, stating no device-only`() {
        Prefs(ctx).apply {
            sourceLang = "ja"
            overlayMode = OverlayMode.FURIGANA
            captureDisplayIds = setOf(0)
        }
        // The process's one cloud reader is built on the stored settings
        // first, so it never keeps this test's.
        FrameReaders.cloud
        // Configured before the key is there: configuring reconciles the
        // translation cache, whose registry walk asks the cloud backend
        // whether it is usable, which reads the key, and a read decrypts once
        // and is kept, so the warm's own read would go uncounted.
        service!!.get().configureSaved(displayIds = setOf(0))
        val codec = PtCloudTestSupport.CountingCodec()
        val prefs = Prefs(ctx, codec).apply {
            ptCloudOcrEnabled = true
            ptCloudClientKey = PtCloudTestSupport.KEY
        }
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        PtCloudTestSupport.offline(ctx).onAvailable(ShadowNetwork.newInstance(101))

        service!!.get().holdStart(0)
        shadowOf(Looper.getMainLooper()).idle()

        // The warm decides on IO.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (codec.decrypts.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("the warm read the key to decide", codec.decrypts.get() > 0)
    }
}
