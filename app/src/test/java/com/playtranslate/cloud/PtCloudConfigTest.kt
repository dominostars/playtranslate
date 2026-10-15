package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayTranslate Cloud's settings through [Prefs]: the client key's
 * encrypted slot round-trips (AndroidKeyStore is instrumented-only, so a
 * reversible codec stands in), and [PtCloudConfig] serves the defaults and
 * the cleaned-up values the client sends, refusing a stored key that
 * request building would throw on, and reads the transport knobs (upload
 * format, item lines, warm call) at each use.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudConfigTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clear() { PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit() }
    @After fun tearDown() { clear() }

    @Test fun `the client key round-trips through its encrypted slot`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        assertEquals("", prefs.ptCloudClientKey)
        prefs.ptCloudClientKey = "pt-key-123"
        assertEquals("pt-key-123", PtCloudTestSupport.prefs(ctx).ptCloudClientKey)
        val stored = PtCloudTestSupport.sharedPrefs(ctx).getString(Prefs.KEY_PT_CLOUD_CLIENT_KEY, null)
        assertNotEquals("never stored in plaintext", "pt-key-123", stored)
        assertTrue(stored!!.startsWith(PtCloudTestSupport.FakeCodec.PREFIX))
        // Cleared is an explicit "", not the absent default.
        prefs.ptCloudClientKey = ""
        assertEquals("", PtCloudTestSupport.prefs(ctx).ptCloudClientKey)
    }

    @Test fun `defaults come from PtCloudConfig`() {
        val config = PtCloudConfig(PtCloudTestSupport.prefs(ctx))
        assertEquals(PtCloudConfig.DEFAULT_UPLOAD_LONG_SIDE_CAP, config.uploadLongSideCap)
        assertFalse(config.hasKey)
        assertFalse(config.enabled)
    }

    @Test fun `the transport knobs default to JPEG, item lines asked for, and the warm call on`() {
        val config = PtCloudConfig(PtCloudTestSupport.prefs(ctx))
        assertEquals(FrameFormat.JPEG, config.uploadFormat)
        assertTrue(config.streamItems)
        assertTrue(config.warmEnabled)
        // And those are the companion's values, the only copy.
        assertEquals(PtCloudConfig.DEFAULT_UPLOAD_FORMAT, config.uploadFormat)
        assertEquals(PtCloudConfig.DEFAULT_STREAM_ITEMS, config.streamItems)
        assertEquals(PtCloudConfig.DEFAULT_WARM, config.warmEnabled)
    }

    @Test fun `each transport knob is read at each use, so a change applies to the next capture`() {
        val config = PtCloudConfig(PtCloudTestSupport.prefs(ctx))
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudUploadFormat = FrameFormat.WEBP.wireName
        prefs.ptCloudStreamItems = false
        prefs.ptCloudWarm = false
        assertEquals(FrameFormat.WEBP, config.uploadFormat)
        assertFalse(config.streamItems)
        assertFalse(config.warmEnabled)
        prefs.ptCloudUploadFormat = FrameFormat.JPEG.wireName
        prefs.ptCloudStreamItems = true
        prefs.ptCloudWarm = true
        assertEquals(FrameFormat.JPEG, config.uploadFormat)
        assertTrue(config.streamItems)
        assertTrue(config.warmEnabled)
    }

    @Test fun `a stored format this build does not know reads as the default`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val config = PtCloudConfig(prefs)
        for (unknown in listOf("png", "", "WEBP", "image/webp")) {
            prefs.ptCloudUploadFormat = unknown
            assertEquals(unknown, FrameFormat.JPEG, config.uploadFormat)
        }
    }

    @Test fun `the config serves the stored values, cleaned for the wire`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "  key\n"
        prefs.ptCloudUploadLongSideCap = 2560
        val config = PtCloudConfig(prefs)
        assertEquals("key", config.clientKey)
        assertTrue(config.hasKey)
        assertEquals(2560, config.uploadLongSideCap)
    }

    /** A config over freshly cleared prefs holding only [key]. */
    private fun config(key: String): PtCloudConfig {
        clear()
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = key
        return PtCloudConfig(prefs)
    }

    @Test fun `a key is usable only as a legal bearer value`() {
        assertFalse(config(key = "").hasKey)
        assertFalse(config(key = "   \n").hasKey)
        // Inside the key, where trimming can't reach: OkHttp would throw on
        // the header, so it reads as no key.
        assertFalse(config(key = "abc\ndef").hasKey)
        assertFalse(config(key = "abc\u007Fdef").hasKey)
        assertFalse(config(key = "clé-123").hasKey)
        assertFalse(config(key = "key\u00A0123").hasKey)
        assertTrue(config(key = "pt_live-AbC.123~xyz").hasKey)
        assertTrue(config(key = "with space and\ttab").hasKey)
    }
}
