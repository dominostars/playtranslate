package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.OverlayFlavor
import com.playtranslate.language.SourceLangId
import com.playtranslate.net.NetworkConnectivity.InternetState
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

/**
 * The router's decision ([PtCloudService.refusal]), which the router, its
 * warm call and the floating menu's header all ask, and the header's
 * question built on it ([PtCloudService.wouldTakeNextCapture]): a network,
 * and a read the router would send to the cloud, stating device-only always
 * while the furigana tier runs, and while another tier runs only in a
 * session the service refused.
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
        PtCloudService.onLiveSessionStarted()
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        PtCloudService.onLiveSessionStarted()
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    /** The switch [on] and [key] stored, as [PtCloudConfig] reads them. */
    private fun config(on: Boolean = true, key: String = PtCloudTestSupport.KEY): PtCloudConfig {
        val prefs = PtCloudTestSupport.prefs(ctx)
        prefs.ptCloudEnabled = on
        prefs.ptCloudClientKey = key
        return PtCloudConfig(prefs)
    }

    private fun coolDown() =
        cooldown.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)

    private fun next(
        config: PtCloudConfig = config(),
        liveFlavor: OverlayFlavor? = null,
        internet: InternetState = InternetState.VALIDATED,
    ) = PtCloudService.wouldTakeNextCapture(config, ja, liveFlavor = liveFlavor, internet = internet)

    // ── The router's decision, one reason per cell, in its order ───────

    @Test fun `with the switch off, the switch is the reason whatever else holds`() {
        coolDown()
        assertEquals(CloudRefusal.SWITCH_OFF, PtCloudService.refusal(config(on = false, key = ""), ja, deviceOnly = true))
    }

    @Test fun `a device-only read is refused for that before anything else is read`() {
        coolDown()
        assertEquals(CloudRefusal.DEVICE_ONLY, PtCloudService.refusal(config(key = ""), ja, deviceOnly = true))
    }

    @Test fun `no usable key keeps the read on the device`() {
        coolDown()
        assertEquals(CloudRefusal.NO_KEY, PtCloudService.refusal(config(key = ""), ja, deviceOnly = false))
        // A character no header may carry reads as no key, as a capture reads it.
        assertEquals(CloudRefusal.NO_KEY, PtCloudService.refusal(config(key = "k\u0001ey"), ja, deviceOnly = false))
    }

    @Test fun `a cooldown keeps the read on the device`() {
        coolDown()
        assertEquals(CloudRefusal.COOLING_DOWN, PtCloudService.refusal(config(), ja, deviceOnly = false))
    }

    @Test fun `with nothing against it the read goes to the cloud`() {
        assertNull(PtCloudService.refusal(config(), ja, deviceOnly = false))
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

    @Test fun `a refused session counts only while a reconciler or pinhole tier runs`() {
        PtCloudService.blockForLiveSession()
        assertFalse("the live read states device-only", next(liveFlavor = OverlayFlavor.TRANSLATION))
        assertFalse(next(liveFlavor = OverlayFlavor.IN_APP_ONLY))
        assertTrue("a one-shot's read states nothing", next(liveFlavor = null))

        PtCloudService.onLiveSessionStarted()
        assertTrue(next(liveFlavor = OverlayFlavor.TRANSLATION))
        assertTrue(next(liveFlavor = OverlayFlavor.IN_APP_ONLY))
    }

    // Codex: the furigana tier reads through runOcr, on the device always.
    @Test fun `while the furigana tier runs the next capture stays on the device`() {
        assertFalse("in a session the service never refused", next(liveFlavor = OverlayFlavor.FURIGANA))
        PtCloudService.blockForLiveSession()
        assertFalse(next(liveFlavor = OverlayFlavor.FURIGANA))
    }
}
