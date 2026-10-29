package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.PtJson
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
 * the cleaned-up values the client sends, refusing a stored key that
 * request building would throw on; and the account record beside the key,
 * read only with a usable key and as nothing when it does not decode.
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
        assertFalse(config.ocrEnabled)
        assertFalse(config.translateEnabled)
        assertFalse(config.anyEnabled)
    }

    @Test fun `the two switches are independent, and anyEnabled is either`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val config = PtCloudConfig(prefs)
        prefs.ptCloudOcrEnabled = true
        assertTrue(config.ocrEnabled)
        assertFalse(config.translateEnabled)
        assertTrue(config.anyEnabled)
        prefs.ptCloudOcrEnabled = false
        prefs.ptCloudTranslateEnabled = true
        assertFalse(config.ocrEnabled)
        assertTrue(config.translateEnabled)
        assertTrue(config.anyEnabled)
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

    @Test fun `the account record is read beside a usable key, and never without one`() {
        val record = PtCloudAccount(email = "player@example.com", active = true, source = "trial", entitledUntilMs = 5L, checkedAtMs = 1L)
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudAccountJson = PtJson.lenient.encodeToString(PtCloudAccount.serializer(), record)
        val config = PtCloudConfig(prefs)
        assertNull("no key: no record, whatever is stored", config.account)
        prefs.ptCloudClientKey = "abc\ndef"
        assertNull("a key that is no key: no record", config.account)
        prefs.ptCloudClientKey = "pt-key-123"
        assertEquals(record, config.account)
    }

    @Test fun `no record, or one that does not decode, reads as none`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "pt-key-123"
        val config = PtCloudConfig(prefs)
        assertNull(config.account)
        for (json in listOf("", "not json", "[]", """{"email":"a@b.c"}""")) {
            prefs.ptCloudAccountJson = json
            assertNull(json, config.account)
        }
    }
}
