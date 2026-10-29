package com.playtranslate

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.cloud.PtCloudAccount
import com.playtranslate.cloud.PtCloudConfig
import com.playtranslate.cloud.PtCloudService
import com.playtranslate.cloud.PtCloudTestSupport
import com.playtranslate.cloud.SOURCE_TRIAL
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.FallbackFrameReader
import com.playtranslate.ocr.FrameReader
import com.playtranslate.ocr.FrameReaders
import com.playtranslate.ocr.FrameReading
import com.playtranslate.ocr.ReadRequest
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork

/**
 * The one router every consumer of the seam asks ([FrameReaders.readerFor]):
 * the test override wins over everything, a read with the switch off (every
 * release build), stated device-only, or made with no network is the one
 * device reader, and the frame ids it mints name their consumer. The cloud
 * branch needs a usable key, which production [Prefs] cannot store without
 * the AndroidKeyStore, so those cells install settings on the test codec
 * ([PtCloudService.installForTest]); the decision's every reason is pinned
 * in [com.playtranslate.cloud.PtCloudNextCaptureTest]. The device's clock
 * ends no account: a record whose dated end has passed routes to the cloud,
 * whose 403 is the ending ([com.playtranslate.cloud.PtCloudFailureMappingTest]),
 * and a record the server wrote as inactive is refused as silently as no key.
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
        TranslationBackendRegistry.onlineAttemptListener = null
        PtCloudService.installForTest(null)
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The switch on and a key stored through [codec], installed as the
     *  service's settings. The process's one cloud reader is built first, on
     *  the stored settings, so it never keeps this test's. */
    private fun cloudConfigured(codec: PtCloudTestSupport.CountingCodec) {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        FrameReaders.cloud
        val prefs = Prefs(ctx, codec).apply {
            ptCloudOcrEnabled = true
            ptCloudClientKey = PtCloudTestSupport.KEY
        }
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
    }

    @Test fun `with no network a read the cloud would take stays on the device, its key never read`() {
        val codec = PtCloudTestSupport.CountingCodec()
        cloudConfigured(codec)
        PtCloudTestSupport.offline(ctx)
        ShadowLog.clear()

        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false))
        assertEquals("no key decrypted", 0, codec.decrypts.get())
        val lines = ShadowLog.getLogsForTag("FrameReaders").map { it.msg }
        assertTrue("the reason is logged: $lines", lines.any { it.endsWith(": no network") })
    }

    @Test fun `with a network the same read goes to the cloud, the device its fallback`() {
        val codec = PtCloudTestSupport.CountingCodec()
        cloudConfigured(codec)
        PtCloudTestSupport.offline(ctx).onAvailable(ShadowNetwork.newInstance(101))

        assertTrue(FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false) is FallbackFrameReader)
        assertTrue("the key was read", codec.decrypts.get() > 0)
    }

    /** Both switches on with a key and [account] beside it, installed as
     *  the service's settings, with a network; the reports the read makes
     *  are collected in the returned list. */
    private fun configuredWith(account: PtCloudAccount): Pair<Prefs, MutableList<OnlineAttempt>> {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        FrameReaders.cloud
        val prefs = PtCloudTestSupport.prefs(ctx).apply {
            ptCloudOcrEnabled = true
            ptCloudTranslateEnabled = true
            ptCloudClientKey = PtCloudTestSupport.KEY
            ptCloudAccountJson = PtJson.lenient.encodeToString(PtCloudAccount.serializer(), account)
        }
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        PtCloudTestSupport.offline(ctx).onAvailable(ShadowNetwork.newInstance(101))
        val reported = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { reported += it }
        return prefs to reported
    }

    /** The trial's end, or a lease's, passed on this clock since the server
     *  last described the account: the read goes out all the same, since
     *  the clock ends nothing (a Ko-fi lease renews on the server with a
     *  payment the device has not read; Codex adversarial review,
     *  2026-10-29), and the server's 403, if it comes, is the ending. The
     *  router reports and flips nothing. */
    @Test fun `a read with a record whose end has passed on this clock goes to the cloud, reports nothing and flips nothing`() {
        val now = System.currentTimeMillis()
        val records = listOf(
            PtCloudAccount(active = true, source = SOURCE_TRIAL, entitledUntilMs = now - 1_000, checkedAtMs = now - DAY),
            PtCloudAccount(active = true, source = "kofi", entitledUntilMs = now - 1_000, checkedAtMs = now - DAY),
        )
        for (record in records) {
            val (prefs, reported) = configuredWith(record)

            assertTrue("$record", FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false) is FallbackFrameReader)

            assertTrue("$record: nothing reported, $reported", reported.isEmpty())
            assertTrue("$record: both switches as they were", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        }
    }

    /** A record the server wrote as inactive (a 403's, a check's) comes
     *  with both switches off; should it ever stand beside a switch on, the
     *  read stays on the device as silently as with no key. */
    @Test fun `a read with a record the server wrote as inactive stays on the device and says nothing`() {
        val now = System.currentTimeMillis()
        val (prefs, reported) = configuredWith(
            PtCloudAccount(active = false, lastSource = "kofi", endedAtMs = now - DAY, checkedAtMs = now - DAY),
        )

        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false))

        assertTrue("nothing reported: $reported", reported.isEmpty())
        assertTrue("the switches are not the router's to flip", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    @Test fun `init is idempotent and the switch off routes every read to the one device reader`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudOcrEnabled = false
        for (id in SourceLangId.entries) {
            assertSame(FrameReaders.device, FrameReaders.readerFor(id, deviceOnly = false))
            assertSame(FrameReaders.device, FrameReaders.readerFor(id, deviceOnly = true))
        }
    }

    @Test fun `a device-only read stays on the device with the switch on`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudOcrEnabled = true
        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = true))
    }

    /** The translation switch decides what the cloud reader asks for, never
     *  where a frame is read. */
    @Test fun `the translation switch alone routes every read to the device`() {
        FrameReaders.init(ctx, CoroutineScope(Dispatchers.IO))
        Prefs(ctx).ptCloudOcrEnabled = false
        Prefs(ctx).ptCloudTranslateEnabled = true
        assertSame(FrameReaders.device, FrameReaders.readerFor(SourceLangId.JA, deviceOnly = false))
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
        Prefs(ctx).ptCloudOcrEnabled = false
        // Nothing to observe but that it neither throws nor blocks.
        FrameReaders.warm(SourceLangId.JA, deviceOnly = false)
        FrameReaders.warm(SourceLangId.JA, deviceOnly = true)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
