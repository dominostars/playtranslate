package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayTranslate Cloud's settings through [Prefs]: the client key's
 * encrypted slot round-trips (AndroidKeyStore is instrumented-only, so a
 * reversible codec stands in), and [PtCloudConfig] serves the defaults and
 * the cleaned-up values the client sends, refusing a stored URL or key that
 * request building would throw on.
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
        assertEquals(PtCloudConfig.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(PtCloudConfig.DEFAULT_UPLOAD_LONG_SIDE_CAP, config.uploadLongSideCap)
        assertFalse(config.hasKey)
        assertFalse(config.enabled)
    }

    @Test fun `the config serves the stored values, cleaned for the wire`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudBaseUrl = " http://192.168.1.20:8080/ "
        prefs.ptCloudClientKey = "  key\n"
        prefs.ptCloudUploadLongSideCap = 2560
        val config = PtCloudConfig(prefs)
        assertEquals("http://192.168.1.20:8080", config.baseUrl)
        assertEquals("key", config.clientKey)
        assertTrue(config.hasKey)
        assertEquals(2560, config.uploadLongSideCap)
    }

    /** A config over freshly cleared prefs holding only what is given. */
    private fun config(baseUrl: String? = null, key: String? = null): PtCloudConfig {
        clear()
        val prefs = PtCloudTestSupport.prefs(ctx)
        if (baseUrl != null) prefs.ptCloudBaseUrl = baseUrl
        if (key != null) prefs.ptCloudClientKey = key
        return PtCloudConfig(prefs)
    }

    @Test fun `the endpoint is https to any host, or http to a LAN host`() {
        assertEquals("https://pt.example.test/", config("https://pt.example.test").endpoint.toString())
        assertEquals("https://pt.example.test/", config(" https://pt.example.test/ ").endpoint.toString())
        assertEquals("http://192.168.1.20:8080/", config("http://192.168.1.20:8080").endpoint.toString())
        assertEquals("http://localhost:8080/", config("http://localhost:8080").endpoint.toString())
        // The default is usable as it ships.
        assertEquals("${PtCloudConfig.DEFAULT_BASE_URL}/", config().endpoint.toString())
    }

    @Test fun `cleartext to a public host or a malformed URL has no endpoint`() {
        for (bad in listOf("http://pt.example.test", "http://8.8.8.8", "not a url", "ftp://pt.example.test", "", "https://")) {
            val c = config(bad)
            assertNull(bad, c.endpoint)
            // The settings field still shows what was stored.
            assertEquals(bad.trim().trimEnd('/'), c.baseUrl)
        }
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
