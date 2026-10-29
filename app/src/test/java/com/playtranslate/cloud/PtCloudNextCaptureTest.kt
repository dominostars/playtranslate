package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

/**
 * The router's decision ([PtCloudService.refusal]), which the router, its
 * warm call and the floating menu's header all ask, the network among its
 * reasons (read before the key, so a device with none decrypts nothing), and
 * the header's question built on it ([PtCloudService.wouldTakeNextCapture]):
 * a read the router would send to the cloud, the next capture never
 * stating device-only (a stopped pod and a refused key are cooldowns), and
 * an account whose trial or membership ended kept off the cloud after the
 * key and before the cooldown ([CloudRefusal.NOT_ENTITLED]).
 *
 * The key is stored through the reversible test codec (the AndroidKeyStore
 * is instrumented-only); the cooldown runs on the wall clock, the one
 * [PtCloudService.isCoolingDown] reads by default.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudNextCaptureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cooldown: CooldownState
    private val ja = SourceLangId.JA

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, PtCloudService.ID).also { PtCloudService.installForTest(it) }
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    /** The OCR switch [on], the translation switch [translate], [key] and
     *  the [account] record beside it stored, as [PtCloudConfig] reads them,
     *  through [codec]. */
    private fun config(
        on: Boolean = true,
        key: String = PtCloudTestSupport.KEY,
        codec: SecretCodec = PtCloudTestSupport.FakeCodec(),
        translate: Boolean = false,
        account: PtCloudAccount? = null,
    ): PtCloudConfig {
        val prefs = Prefs(ctx, codec)
        prefs.ptCloudOcrEnabled = on
        prefs.ptCloudTranslateEnabled = translate
        prefs.ptCloudClientKey = key
        prefs.ptCloudAccountJson = account?.let { PtJson.lenient.encodeToString(PtCloudAccount.serializer(), it) }
        return PtCloudConfig(prefs)
    }

    /** A record whose trial ended a day ago, on the wall clock the decision
     *  reads; and one whose trial is still on. */
    private fun endedTrial() = PtCloudAccount(
        active = false, lastSource = SOURCE_TRIAL, endedAtMs = System.currentTimeMillis() - DAY, checkedAtMs = 1L,
    )

    private fun onTrial() = PtCloudAccount(
        active = true, source = SOURCE_TRIAL, entitledUntilMs = System.currentTimeMillis() + DAY, checkedAtMs = 1L,
    )

    private fun coolDown() =
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)

    /** The router's decision on [internet], a network unless a cell says otherwise. */
    private fun refusal(
        config: PtCloudConfig,
        deviceOnly: Boolean = false,
        internet: InternetState = InternetState.VALIDATED,
    ) = PtCloudService.refusal(config, ja, deviceOnly, internet)

    private fun next(
        config: PtCloudConfig = config(),
        internet: InternetState = InternetState.VALIDATED,
    ) = PtCloudService.wouldTakeNextCapture(config, ja, internet = internet)

    // ── The router's decision, one reason per cell, in its order ───────

    @Test fun `with the switch off, the switch is the reason whatever else holds`() {
        coolDown()
        assertEquals(
            CloudRefusal.SWITCH_OFF,
            refusal(config(on = false, key = ""), deviceOnly = true, internet = InternetState.NONE),
        )
    }

    /** The OCR switch is the frame route's; the translation switch has no say
     *  over where a frame is read, in either direction. */
    @Test fun `the translation switch alone sends nothing to the cloud, and off it keeps nothing from it`() {
        assertEquals(CloudRefusal.SWITCH_OFF, refusal(config(on = false, translate = true)))
        assertFalse(next(config(on = false, translate = true)))
        assertNull(refusal(config(on = true, translate = false)))
        assertTrue(next(config(on = true, translate = false)))
    }

    @Test fun `a device-only read is refused for that before anything else is read`() {
        coolDown()
        assertEquals(CloudRefusal.DEVICE_ONLY, refusal(config(key = ""), deviceOnly = true, internet = InternetState.NONE))
    }

    @Test fun `with no network the read stays on the device, refused before its key is read`() {
        val codec = PtCloudTestSupport.CountingCodec()
        val config = config(codec = codec)
        coolDown()
        assertEquals(CloudRefusal.OFFLINE, refusal(config, internet = InternetState.NONE))
        assertEquals("no key decrypted", 0, codec.decrypts.get())
        // With a network, the same read is judged on: its key is read, and
        // the cooldown is the reason.
        assertEquals(CloudRefusal.COOLING_DOWN, refusal(config, internet = InternetState.UNVALIDATED))
        assertTrue("the key was read", codec.decrypts.get() > 0)
    }

    @Test fun `the decision reads the network as the connectivity callback last heard it`() {
        val callback = PtCloudTestSupport.offline(ctx)
        assertEquals(CloudRefusal.OFFLINE, PtCloudService.refusal(config(), ja, deviceOnly = false))
        callback.onAvailable(ShadowNetwork.newInstance(101))
        assertNull("a network, validated or not, counts", PtCloudService.refusal(config(), ja, deviceOnly = false))
    }

    @Test fun `no usable key keeps the read on the device`() {
        coolDown()
        assertEquals(CloudRefusal.NO_KEY, refusal(config(key = "")))
        // A character no header may carry reads as no key, as a capture reads it.
        assertEquals(CloudRefusal.NO_KEY, refusal(config(key = "k\u0001ey")))
    }

    @Test fun `a cooldown keeps the read on the device`() {
        coolDown()
        assertEquals(CloudRefusal.COOLING_DOWN, refusal(config()))
    }

    /** NOT_ENTITLED's place in the reasons: the key comes first (no key has
     *  no account to judge), and a record the server wrote as inactive
     *  outranks a cooldown. A trial or a lease whose dated end has passed on
     *  this clock is no refusal: the clock ends nothing, and the server's
     *  403 does (Codex adversarial review, 2026-10-29). The source cannot be
     *  put before it here: every source language is one the server reads
     *  ([PtCloudRoutingTest]). */
    @Test fun `an inactive record keeps the read on the device, after the key and before the source and the cooldown`() {
        assertEquals(CloudRefusal.NOT_ENTITLED, refusal(config(account = endedTrial())))
        val lapsed = onTrial().copy(entitledUntilMs = System.currentTimeMillis() - 1_000)
        assertNull("a trial's end passing on this clock refuses nothing", refusal(config(account = lapsed)))
        assertNull("nor a lease's", refusal(config(account = lapsed.copy(source = "kofi"))))
        assertEquals("the key first", CloudRefusal.NO_KEY, refusal(config(key = "", account = endedTrial())))
        coolDown()
        assertEquals("before the cooldown", CloudRefusal.NOT_ENTITLED, refusal(config(account = endedTrial())))
        assertEquals("a trial still on is judged on", CloudRefusal.COOLING_DOWN, refusal(config(account = onTrial())))
    }

    @Test fun `with nothing against it the read goes to the cloud`() {
        assertNull(refusal(config()))
    }

    // ── The next capture, as the menu's header shows it ────────────────

    @Test fun `with no network the next capture stays on the device`() {
        assertFalse(next(internet = InternetState.NONE))
    }

    @Test fun `any network counts, validated or not, as the no-internet notes count it`() {
        assertTrue(next(internet = InternetState.UNVALIDATED))
        assertTrue(next(internet = InternetState.VALIDATED))
    }

    @Test fun `every refusal of the router is a no`() {
        assertFalse(next(config = config(on = false)))
        assertFalse(next(config = config(key = "")))
        coolDown()
        assertFalse(next())
    }

    @Test fun `an inactive record's next capture stays on the device, and a trial still on or one whose end has passed here goes to the cloud`() {
        assertFalse(next(config(account = endedTrial())))
        assertTrue(next(config(account = onTrial())))
        assertTrue("the strip never crosses out an end the clock passed", next(config(account = onTrial().copy(entitledUntilMs = 1L))))
    }

    @Test fun `a stopped pod or a refused key is a cooldown, so the next capture stays on the device`() {
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Not running", CooldownCause.NOT_RUNNING)
        assertFalse(next())
        cooldown.resetCooldown()
        assertTrue(next())
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        assertFalse(next())
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
