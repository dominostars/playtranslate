package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.TranslationCache
import com.playtranslate.translation.GroupTranslator
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
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicLong

/** The service object: one cooldown, built once, cleared by the user's
 *  gestures, by a regained network (connection failures only, through the
 *  registry's fan-out and the cloud's registry backend) and by the cloud
 *  page's health check finding the service running (the causes that answer
 *  disproves: a stopped pod, a lost connection, a server error); the settings a test
 *  installs; and the reconcile of the shared cache's routing identity that
 *  follows every reset, a gesture's and the health check's. */
@RunWith(RobolectricTestRunner::class)
class PtCloudServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)

    private fun install() = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        .also { PtCloudService.installForTest(it) }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationCache.shared.resetForTest()
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
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

    @Test fun `the registry's connectivity fan-out clears a connection cooldown through the cloud's backend and keeps a server one`() {
        // The Application registered the cloud's backend; nothing else
        // passes the signal to the cloud.
        assertTrue(TranslationBackendRegistry.byId(PtCloudService.ID) is PtCloudBackend)
        val state = install()
        state.recordNetworkFailure("Connection failed")
        state.recordNetworkFailure("Connection failed")
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
        TranslationBackendRegistry.onConnectivityRestored()
        assertNull(PtCloudService.unavailableUntil())

        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        TranslationBackendRegistry.onConnectivityRestored()
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
    }

    @Test fun `config is the stored settings, or a test's while installed`() {
        val stored = PtCloudService.config
        val test = PtCloudTestSupport.config(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), test)
        assertSame(test, PtCloudService.config)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID))
        assertSame(stored, PtCloudService.config)
    }

    @Test fun `a gesture reconciles the shared cache's routing identity after the reset`() {
        // The wall clock: the registry judges a cooldown against it.
        val state = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled = true
        state.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        TranslationCache.shared.resetForTest()
        // The cloud cools down: the identity is the next online service's.
        GroupTranslator.reconcileShared(ctx)
        assertTrue(TranslationBackendRegistry.preferredOnlineId("ja", "en") != PtCloudService.ID)

        PtCloudService.setTranslateEnabled(true)

        // The gesture moved the identity onto the cloud, so a reconcile
        // after it finds nothing changed and keeps what the cache holds.
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        GroupTranslator.reconcileShared(ctx)
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
    }

    /** The OCR switch changes where a frame is read, not what a translation
     *  is made by: its flip keeps the translation cache, where the
     *  translation switch's clears it. */
    @Test fun `an OCR flip keeps the shared translation cache, a translation flip clears it`() {
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled = true
        TranslationCache.shared.resetForTest()
        GroupTranslator.reconcileShared(ctx)
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME

        PtCloudService.setOcrEnabled(true)
        assertEquals("kept across the OCR flip", "one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
        PtCloudService.setOcrEnabled(false)
        assertEquals("and back", "one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])

        PtCloudService.setTranslateEnabled(true)
        assertNull("the translation flip clears it", TranslationCache.shared[key])
    }

    /** The reconcile stays in the OCR flip's tail: its cooldown reset can
     *  make the cloud the waterfall's first backend again (translation on,
     *  the cooldown gone), and the identity's move is what clears the
     *  entries the fallback's routing made. */
    @Test fun `an OCR flip's cooldown reset moves the routing identity back onto the cloud and clears the fallback's entry`() {
        val state = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled = true
        state.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        TranslationCache.shared.resetForTest()
        GroupTranslator.reconcileShared(ctx)
        assertTrue(TranslationBackendRegistry.preferredOnlineId("ja", "en") != PtCloudService.ID)
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to "Fallback"

        PtCloudService.setOcrEnabled(true)

        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        assertNull(TranslationCache.shared[key])
    }

    // ── The key save ────────────────────────────────────────────────────

    @Test fun `the first key saved turns both switches on`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)

        PtCloudService.saveClientKey(PtCloudTestSupport.KEY)

        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertTrue(prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a replacement key leaves the switches as the user set them`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        prefs.ptCloudClientKey = PtCloudTestSupport.KEY
        prefs.ptCloudOcrEnabled = false
        prefs.ptCloudTranslateEnabled = true

        PtCloudService.saveClientKey("another-key")

        assertEquals("another-key", prefs.ptCloudClientKey)
        assertFalse("a deliberate off survives a key rotation", prefs.ptCloudOcrEnabled)
        assertTrue(prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a cleared key turns both switches off`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        prefs.ptCloudClientKey = PtCloudTestSupport.KEY
        prefs.ptCloudOcrEnabled = true
        prefs.ptCloudTranslateEnabled = true

        PtCloudService.saveClientKey("")

        assertEquals("", prefs.ptCloudClientKey)
        assertFalse("nothing stays stored behind a disabled switch", prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a blank or unusable key saved turns nothing on`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))

        PtCloudService.saveClientKey("")
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)

        // A character no header may carry reads as no key (PtCloudConfig.hasKey).
        PtCloudService.saveClientKey("k\u0001ey")
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a Running health check reconciles the shared cache's routing identity, as a gesture does`() {
        // The wall clock: the registry judges a cooldown against it.
        val state = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled = true
        state.recordLadderFailure(CooldownLadder.RateLimit, "Not running", CooldownCause.NOT_RUNNING)
        TranslationCache.shared.resetForTest()
        // The cloud cools down: the identity is the next online service's,
        // and a translation of its is cached under that identity.
        GroupTranslator.reconcileShared(ctx)
        assertTrue(TranslationBackendRegistry.preferredOnlineId("ja", "en") != PtCloudService.ID)
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to "Fallback"

        PtCloudService.onHealthRunning()

        // The check moved the identity back onto the cloud, which clears
        // what the fallback's routing made: a frame the cache answered in
        // full would otherwise keep showing it.
        assertEquals(PtCloudService.ID, TranslationBackendRegistry.preferredOnlineId("ja", "en"))
        assertNull(TranslationCache.shared[key])
    }

    @Test fun `every gesture and a health check that resets count a change, a recorded failure does not`() {
        val state = install()
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        val before = PtCloudService.changes.value

        PtCloudService.setOcrEnabled(true)
        assertEquals(before + 1, PtCloudService.changes.value)
        PtCloudService.setTranslateEnabled(true)
        assertEquals(before + 2, PtCloudService.changes.value)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY)
        assertEquals(before + 3, PtCloudService.changes.value)

        state.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        assertEquals("a failure is no gesture", before + 3, PtCloudService.changes.value)
        PtCloudService.onHealthRunning()
        assertEquals("Running resets nothing it does not disprove", before + 3, PtCloudService.changes.value)

        state.resetCooldown()
        state.recordLadderFailure(CooldownLadder.RateLimit, "Not running", CooldownCause.NOT_RUNNING)
        PtCloudService.onHealthRunning()
        assertEquals(before + 4, PtCloudService.changes.value)
    }

    @Test fun `toggling either switch or saving a key drops any cooldown`() {
        val state = install()
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        state.recordParsedFailure(clock.get() + 3_600_000, "Daily quota used", CooldownCause.DAILY_QUOTA)
        PtCloudService.setOcrEnabled(true)
        assertNull(PtCloudService.unavailableUntil())

        state.recordParsedFailure(clock.get() + 3_600_000, "Daily quota used", CooldownCause.DAILY_QUOTA)
        PtCloudService.setTranslateEnabled(true)
        assertNull(PtCloudService.unavailableUntil())

        state.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY)
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

    @Test fun `a running service seen by the health check drops the cooldown on the one state and reports nothing`() {
        val state = install()
        state.recordLadderFailure(CooldownLadder.RateLimit, "Not running", CooldownCause.NOT_RUNNING)
        assertTrue(PtCloudService.isCoolingDown(clock.get()))
        val seen = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { seen += it }

        PtCloudService.onHealthRunning()

        assertNull(PtCloudService.unavailableUntil())
        assertFalse(PtCloudService.isCoolingDown(clock.get()))
        assertSame(state, PtCloudService.cooldown)
        assertTrue("no attempt reported through the registry", seen.isEmpty())
    }

    @Test fun `a Running health check leaves a bad-key or quota cooldown alone, and does nothing with none`() {
        val state = install()
        // The check takes no key: Running says nothing about the key.
        state.recordLadderFailure(CooldownLadder.RateLimit, "Bad key", CooldownCause.AUTH)
        val until = PtCloudService.unavailableUntil()
        PtCloudService.onHealthRunning()
        assertEquals(until, PtCloudService.unavailableUntil())
        assertEquals(CooldownCause.AUTH, PtCloudService.unavailableCause())

        state.resetCooldown()
        state.recordParsedFailure(clock.get() + 3_600_000, "Daily quota used", CooldownCause.DAILY_QUOTA)
        PtCloudService.onHealthRunning()
        assertEquals(CooldownCause.DAILY_QUOTA, PtCloudService.unavailableCause())

        state.resetCooldown()
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
    }

    @Test fun `a Running health check drops a 502's or a lost connection's cooldown, ladders and all`() {
        val state = install()
        // The edge's 502 while the pod boots: a server error on the RateLimit ladder.
        PtCloudErrors.classifyHttp(502, "", null).record(state)
        assertEquals(CooldownCause.SERVER_ERROR, PtCloudService.unavailableCause())
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
        // Its rung went with it: the next server error costs the first step.
        PtCloudErrors.classifyHttp(502, "", null).record(state)
        assertEquals(clock.get() + 60_000, PtCloudService.unavailableUntil())

        state.resetCooldown()
        // Two transport failures in a row: the Network ladder.
        repeat(2) { PtCloudErrors.classifyTransport(ConnectException("refused")).record(state) }
        assertEquals(CooldownCause.CONNECTION_FAILED, PtCloudService.unavailableCause())
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
    }

    @Test fun `a Running health check drops exactly the causes a reachable, answering server disproves`() {
        val state = install()
        val disproved = setOf(CooldownCause.NOT_RUNNING, CooldownCause.CONNECTION_FAILED, CooldownCause.SERVER_ERROR)
        for (cause in CooldownCause.entries) {
            state.resetCooldown()
            state.recordParsedFailure(clock.get() + 3_600_000, cause.name, cause)
            PtCloudService.onHealthRunning()
            assertEquals("$cause still cooling down", cause !in disproved, PtCloudService.isCoolingDown(clock.get()))
        }
    }
}
