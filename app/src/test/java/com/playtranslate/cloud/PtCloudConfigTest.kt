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
 * read only with a usable key and as nothing when it does not decode, with
 * the standing and the admission read from it ([PtCloudConfig.standing],
 * [PtCloudConfig.admitted]).
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

    // ── The standing and the admission ──────────────────────────────────

    private fun write(prefs: Prefs, account: PtCloudAccount?) {
        prefs.ptCloudAccountJson = account?.let { PtJson.lenient.encodeToString(PtCloudAccount.serializer(), it) }
    }

    private fun active(source: String, untilMs: Long?) =
        PtCloudAccount(email = "player@example.com", active = true, source = source, entitledUntilMs = untilMs, checkedAtMs = 1L)

    @Test fun `no key has no standing and is not admitted, whatever record is stored`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        write(prefs, active("kofi", null))
        val config = PtCloudConfig(prefs)
        assertNull(config.standing(NOW))
        assertFalse(config.admitted)
        prefs.ptCloudClientKey = "abc\ndef"
        assertNull("a key that is no key", config.standing(NOW))
        assertFalse(config.admitted)
    }

    /** A key with no record passes: the app knows nothing against it. */
    @Test fun `a key with no record, or one that does not decode, is NO_DETAILS and admitted`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "pt-key-123"
        val config = PtCloudConfig(prefs)
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, config.standing(NOW))
        assertTrue(config.admitted)
        prefs.ptCloudAccountJson = "not json"
        assertEquals(PtCloudAccount.Standing.NO_DETAILS, config.standing(NOW))
        assertTrue(config.admitted)
    }

    @Test fun `a trial and a membership are admitted, an inactive record is not`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "pt-key-123"
        val config = PtCloudConfig(prefs)
        val now = System.currentTimeMillis()

        write(prefs, active(SOURCE_TRIAL, now + DAY))
        assertEquals(PtCloudAccount.Standing.TRIAL, config.standing())
        assertTrue(config.admitted)

        write(prefs, active("kofi", null))
        assertEquals(PtCloudAccount.Standing.MEMBER, config.standing())
        assertTrue(config.admitted)

        write(prefs, PtCloudAccount(active = false, lastSource = SOURCE_TRIAL, endedAtMs = now - DAY, checkedAtMs = 1L))
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, config.standing())
        assertFalse(config.admitted)

        write(prefs, PtCloudAccount(active = false, checkedAtMs = 1L))
        assertEquals(PtCloudAccount.Standing.INACTIVE, config.standing())
        assertFalse(config.admitted)
    }

    /** The current terms owed withhold an account its entitlement would
     *  serve: [PtCloudConfig.admitted] is false for a record owing them,
     *  whatever its dates and source, and true for every other active record,
     *  as before; an inactive record stays out either way. */
    @Test fun `a record owing the terms is not admitted, and every other active record is`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "pt-key-123"
        val config = PtCloudConfig(prefs)
        val now = System.currentTimeMillis()
        val records = listOf(
            active(SOURCE_TRIAL, now + DAY), active(SOURCE_TRIAL, now - 1_000), active("kofi", null),
            active("kofi", now - 1_000), PtCloudAccount(active = true, checkedAtMs = 1L),
        )
        for (record in records) {
            write(prefs, record)
            assertTrue("$record", config.admitted)
            write(prefs, record.copy(termsRequired = true))
            assertFalse("$record, owing the terms", config.admitted)
            assertEquals("$record, owing the terms", PtCloudAccount.Standing.TERMS_REQUIRED, config.standing())
        }
        write(prefs, PtCloudAccount(active = false, lastSource = "kofi", termsRequired = true, checkedAtMs = 1L))
        assertFalse(config.admitted)
        assertEquals(PtCloudAccount.Standing.INACTIVE, config.standing())
    }

    /** The device's clock words a trial's end ([PtCloudConfig.standing])
     *  and ends nothing: [PtCloudConfig.admitted] is the server's last word,
     *  the record's `active` and `termsRequired`, so a trial or a lease whose
     *  dated end has passed here still routes to the cloud, whose 403 is the
     *  ending (Codex adversarial review, 2026-10-29: a Ko-fi lease renews on
     *  the server with a payment the device has not read). */
    @Test fun `an active record whose end has passed stays admitted, a trial reading TRIAL_ENDED for its words and a lease MEMBER`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudClientKey = "pt-key-123"
        val config = PtCloudConfig(prefs)
        write(prefs, active(SOURCE_TRIAL, NOW))
        assertEquals(PtCloudAccount.Standing.TRIAL, config.standing(NOW - 1))
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, config.standing(NOW))

        val now = System.currentTimeMillis()
        write(prefs, active(SOURCE_TRIAL, now + DAY))
        assertTrue(config.admitted)
        write(prefs, active(SOURCE_TRIAL, now - 1_000))
        assertTrue("ended on the device's clock a second ago: the server's to end", config.admitted)
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, config.standing())
        write(prefs, active("kofi", now - 1_000))
        assertTrue("a lease's end passing says nothing", config.admitted)
        assertEquals(PtCloudAccount.Standing.MEMBER, config.standing())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        /** 2026-10-28 12:00 UTC. */
        const val NOW = 1_793_188_800_000L
    }
}
