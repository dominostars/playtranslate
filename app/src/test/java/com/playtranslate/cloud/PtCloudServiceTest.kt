package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/** The service object: one cooldown, built once, cleared by the user's
 *  gestures and by a regained network (connection failures only). */
@RunWith(RobolectricTestRunner::class)
class PtCloudServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)

    private fun install() = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        .also { PtCloudService.installForTest(it) }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
    }

    @Test fun `the cooldown says so when init was never called`() {
        PtCloudService.installForTest(null)
        try {
            PtCloudService.cooldown
            fail("expected the missing-init error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("init"))
        }
    }

    @Test fun `init builds the state once`() {
        PtCloudService.installForTest(null)
        PtCloudService.init(ctx)
        val first = PtCloudService.cooldown
        PtCloudService.init(ctx)
        assertSame(first, PtCloudService.cooldown)
    }

    @Test fun `cooling down while a cooldown runs, not after it expires`() {
        val state = install()
        assertFalse(PtCloudService.isCoolingDown(clock.get()))
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
        assertEquals(CooldownCause.SERVER_ERROR, PtCloudService.unavailableCause())
        assertEquals("Server error", PtCloudService.unavailableDescription())
        clock.addAndGet(61_000)
        assertFalse(PtCloudService.isCoolingDown(clock.get()))
    }

    @Test fun `a regained network clears a connection cooldown and keeps a server one`() {
        val state = install()
        state.recordNetworkFailure("Connection failed")
        state.recordNetworkFailure("Connection failed")
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
        assertTrue(PtCloudService.onConnectivityRestored())
        assertNull(PtCloudService.unavailableUntil())

        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        assertFalse(PtCloudService.onConnectivityRestored())
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
    }

    @Test fun `toggling the switch or saving credentials drops any cooldown`() {
        val state = install()
        state.recordParsedFailure(clock.get() + 3_600_000, "Daily quota used", CooldownCause.DAILY_QUOTA)
        PtCloudService.onToggled()
        assertNull(PtCloudService.unavailableUntil())

        state.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        PtCloudService.onCredentialsSaved()
        assertNull(PtCloudService.unavailableUntil())
    }

    @Test fun `report reaches the registry's listener`() {
        install()
        val seen = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { seen += it }
        val attempt = OnlineAttempt(
            PtCloudService.ID, PtCloudService.DISPLAY_NAME,
            BackendFailure(BackendFailureKind.NOT_RUNNING, 404), sentAtMs = 7, reachedServer = true,
        )
        PtCloudService.report(attempt)
        assertEquals(listOf(listOf(attempt)), seen)
    }
}
