package com.playtranslate.cloud

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.security.SecretCodec
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownLadder
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.TranslationCache
import com.playtranslate.PtJson
import com.playtranslate.net.NetworkConnectivity.InternetState
import com.playtranslate.translation.GroupTranslator
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.TranslationError
import com.playtranslate.translation.TranslationErrorKey
import com.playtranslate.translation.TranslationErrorPresenter
import com.playtranslate.translation.TranslationErrorTracker
import com.playtranslate.translation.TranslationErrors
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** The service object: one cooldown, built once, cleared by the user's
 *  gestures, by a regained network (connection failures only, through the
 *  registry's fan-out and the cloud's registry backend), by the cloud
 *  page's health check finding the service running (the causes that answer
 *  disproves: a stopped pod, a lost connection, a server error, a closed
 *  service) and by an accepted check of the stored key (a refused key, an
 *  older build's no-membership cooldown); the settings a test installs; the
 *  key and the account record written together, only beside a key that was
 *  stored, and the stored key's check acting only on the key it checked;
 *  the two switches following the account's entitlement (2026-10-29: on
 *  when an answer makes it entitled, off whenever the app learns it is not,
 *  the ending forgetting no pill and resetting nothing); and the reconcile
 *  of the shared cache's routing identity that follows every reset, a
 *  gesture's and the health check's.
 *
 *  Records with a date are far ahead of, or behind, the wall clock the
 *  entitlement is read on, so no cell changes its answer as the calendar
 *  moves. */
@RunWith(RobolectricTestRunner::class)
class PtCloudServiceTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)

    private fun install() = CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
        .also { PtCloudService.installForTest(it) }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationErrors.resetForTest()
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

    /** The first key is the sign-up moment: the account it opens is
     *  entitled, so both switches go on, for a key with no record (the app
     *  knows nothing against it) as for a sign-in's with its trial. */
    @Test fun `the first key saved for an entitled account, or with no record, turns both switches on`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        PtCloudService.installForTest(CooldownState(null, PtCloudService.ID), PtCloudConfig(prefs))
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)

        PtCloudService.saveClientKey(PtCloudTestSupport.KEY)

        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertTrue(prefs.ptCloudOcrEnabled)
        assertTrue(prefs.ptCloudTranslateEnabled)

        PtCloudService.saveClientKey("")
        assertTrue(PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL))
        assertTrue("a sign-in's key with its trial", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    /** A verify's key to an account that has already ended is stored, and
     *  nothing about it is entitled: both switches stay off, and nothing is
     *  reported (no pill: the page the user is on says it, and Join is
     *  next). */
    @Test fun `a sign-in's key to an ended account is stored with both switches left off and nothing reported`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        val seen = mutableListOf<List<OnlineAttempt>>()
        TranslationBackendRegistry.onlineAttemptListener = { seen += it }

        assertTrue(PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL))

        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertEquals(ENDED_TRIAL, PtCloudService.account)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertTrue("no pill", seen.isEmpty())
    }

    @Test fun `a replacement key for an entitled account leaves the switches as the user set them`() {
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

    /** The first key's rule, for a key over an ended account's: the account
     *  the new key opens is entitled where the old one was not. */
    @Test fun `a key saved over an ended account's, for an entitled one, turns both switches on`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)

        assertTrue(PtCloudService.saveClientKey("another-key", TRIAL))

        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a cleared key turns both switches off, no key being entitled`() {
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

    @Test fun `a blank or unusable key saved turns nothing on, no key being entitled`() {
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
        // The edge's 502 while it cannot reach the host (the pod booting):
        // not running, on the RateLimit ladder.
        PtCloudErrors.classifyHttp(502, "", null).record(state)
        assertEquals(CooldownCause.NOT_RUNNING, PtCloudService.unavailableCause())
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
        // Its rung went with it: the next 502 costs the first step.
        PtCloudErrors.classifyHttp(502, "", null).record(state)
        assertEquals(clock.get() + 60_000, PtCloudService.unavailableUntil())

        state.resetCooldown()
        // Two transport failures in a row: the Network ladder.
        repeat(2) { PtCloudErrors.classifyTransport(ConnectException("refused")).record(state) }
        assertEquals(CooldownCause.CONNECTION_FAILED, PtCloudService.unavailableCause())
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
    }

    /** A closed service answers `/health` 410 too, so a Running health says
     *  it reopened. */
    @Test fun `a Running health drops a CLOSED cooldown`() {
        val state = install()
        PtCloudErrors.classifyHttp(410, """{"error":"service_closed","message":"Closed."}""", null).record(state)
        assertEquals(CooldownCause.CLOSED, PtCloudService.unavailableCause())
        PtCloudService.onHealthRunning()
        assertNull(PtCloudService.unavailableUntil())
    }

    @Test fun `a Running health check drops exactly the causes a reachable, answering server disproves`() {
        val state = install()
        val disproved = setOf(
            CooldownCause.NOT_RUNNING, CooldownCause.CONNECTION_FAILED, CooldownCause.SERVER_ERROR, CooldownCause.CLOSED,
        )
        for (cause in CooldownCause.entries) {
            state.resetCooldown()
            state.recordParsedFailure(clock.get() + 3_600_000, cause.name, cause)
            PtCloudService.onHealthRunning()
            assertEquals("$cause still cooling down", cause !in disproved, PtCloudService.isCoolingDown(clock.get()))
        }
    }

    // ── An accepted check of the stored key ─────────────────────────────

    /** AUTH and BILLING only: a key check says nothing about a rate limit,
     *  a quota, or a service that is down or closed. */
    @Test fun `onKeyAccepted drops an AUTH or a BILLING cooldown and no other`() {
        val state = install()
        val cleared = setOf(CooldownCause.AUTH, CooldownCause.BILLING)
        for (cause in CooldownCause.entries) {
            state.resetCooldown()
            state.recordParsedFailure(clock.get() + 3_600_000, cause.name, cause)
            PtCloudService.onKeyAccepted()
            assertEquals("$cause still cooling down", cause !in cleared, PtCloudService.isCoolingDown(clock.get()))
        }
    }

    /** The configuration did not change, so the cache stays, where a key
     *  save clears it; the reset is counted as a gesture's is. */
    @Test fun `onKeyAccepted keeps the translation cache and counts a change`() {
        // The wall clock: the registry judges a cooldown against it.
        val state = CooldownState(null, PtCloudService.ID)
        PtCloudService.installForTest(state, PtCloudTestSupport.config(ctx))
        PtCloudTestSupport.prefs(ctx).ptCloudTranslateEnabled = true
        TranslationCache.shared.resetForTest()
        GroupTranslator.reconcileShared(ctx)
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        state.recordLadderFailure(CooldownLadder.RateLimit, "No membership", CooldownCause.BILLING)
        val before = PtCloudService.changes.value

        PtCloudService.onKeyAccepted()

        assertNull(PtCloudService.unavailableUntil())
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
        assertEquals(before + 1, PtCloudService.changes.value)
    }

    @Test fun `onKeyAccepted with no cooldown, or one it keeps, counts no change`() {
        val state = install()
        val before = PtCloudService.changes.value
        PtCloudService.onKeyAccepted()
        state.recordLadderFailure(CooldownLadder.RateLimit, "Closed", CooldownCause.CLOSED)
        PtCloudService.onKeyAccepted()
        assertEquals(before, PtCloudService.changes.value)
        assertEquals(CooldownCause.CLOSED, PtCloudService.unavailableCause())
    }

    // ── The account record beside the key ───────────────────────────────

    /** The service's settings over [prefs], with a cooldown on the test's
     *  clock. */
    private fun installWith(prefs: Prefs): CooldownState =
        CooldownState(null, PtCloudService.ID, nowMs = { clock.get() })
            .also { PtCloudService.installForTest(it, PtCloudConfig(prefs)) }

    @Test fun `saveClientKey stores the account beside the key and stamps when the key was stored`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        assertNull(PtCloudService.keyStoredAtMs)

        assertTrue(PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL))

        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertEquals(TRIAL, PtCloudService.account)
        assertEquals(SystemClock.elapsedRealtime(), PtCloudService.keyStoredAtMs)
        assertTrue("a sign-in's key is a first key: both switches on", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a change of key replaces the account, and a key saved with none clears it`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val pasted = PtCloudAccount(active = true, source = "manual", checkedAtMs = 2L)

        assertTrue(PtCloudService.saveClientKey("another-key", pasted))
        assertEquals(pasted, PtCloudService.account)

        assertTrue(PtCloudService.saveClientKey("a-third-key"))
        assertNull("a record never outlives the key it describes", prefs.ptCloudAccountJson)
        assertNull(PtCloudService.account)
    }

    @Test fun `a blank key clears the key and the account together, and is no key stored`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val storedAt = PtCloudService.keyStoredAtMs
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

        assertTrue(PtCloudService.saveClientKey("", TRIAL))

        assertEquals("", prefs.ptCloudClientKey)
        assertNull("no record beside no key, whatever was passed", prefs.ptCloudAccountJson)
        assertEquals("only a key stored stamps the moment", storedAt, PtCloudService.keyStoredAtMs)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    /** The keystore refusing the new key keeps the old one
     *  ([Prefs.writeSecret]'s fail-closed rule): the save says so, and the
     *  old key keeps its own record. */
    @Test fun `a key the keystore refuses stores neither the key nor its account, and says so`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val refusing = Prefs(ctx, RefusingCodec())
        installWith(refusing)

        assertFalse(PtCloudService.saveClientKey("pt_new-key", TRIAL.copy(email = "someone@example.com")))

        assertEquals(PtCloudTestSupport.KEY, refusing.ptCloudClientKey)
        assertEquals(TRIAL, PtCloudService.account)
        assertNull("not stored, not stamped", PtCloudService.keyStoredAtMs)
        assertTrue("nothing flipped", refusing.ptCloudOcrEnabled && refusing.ptCloudTranslateEnabled)
    }

    /** A record whose key reads as no key (unusable) is never shown, and
     *  the config's reading is the service's. */
    @Test fun `the account reads null without a usable key`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        prefs.ptCloudClientKey = "k\u0001ey"
        assertNull(PtCloudService.account)
        assertTrue("the record is still stored", prefs.ptCloudAccountJson != null)
    }

    // ── The stored key's check ──────────────────────────────────────────

    /** A sign-out or another copy of the page can change the key during the
     *  check's round trip: an answer for another key is about a key that is
     *  gone, whatever it says. */
    @Test fun `a check of a key that is no longer the stored one changes nothing`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        state.recordLadderFailure(CooldownLadder.RateLimit, "No membership", CooldownCause.BILLING)
        val checks = listOf(
            KeyCheck.Accepted("kofi", null), KeyCheck.Inactive("trial", 5L), KeyCheck.Refused,
            KeyCheck.Closed("closed"), KeyCheck.NotChecked,
        )
        for (check in checks) {
            PtCloudService.onStoredKeyChecked("an-older-key", PtCloudService.accountGeneration, check)
            assertEquals("$check", PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
            assertEquals("$check", TRIAL, PtCloudService.account)
            assertEquals("$check", CooldownCause.BILLING, PtCloudService.unavailableCause())
            assertTrue("$check", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        }
    }

    @Test fun `an Accepted check rewrites the account, keeping its email, and drops a BILLING cooldown`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        state.recordLadderFailure(CooldownLadder.RateLimit, "No membership", CooldownCause.BILLING)

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))

        val account = PtCloudService.account!!
        assertEquals(
            PtCloudAccount(email = EMAIL, active = true, source = "kofi", entitledUntilMs = LEASE_END_MS, checkedAtMs = account.checkedAtMs),
            account,
        )
        assertNull("a member who joined is out of the ladder", PtCloudService.unavailableUntil())
        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
    }

    /** Back from Join: an Accepted check of an account that was not entitled
     *  is the first key's twin, both switches on with the reset a key save
     *  makes (the translation cache cleared, any cooldown dropped) and the
     *  change counted, so the cloud is in play at the next capture. */
    @Test fun `an Accepted check after an ended record turns both switches on, clears the translation cache and counts a change`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to "Fallback"
        state.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        val before = PtCloudService.changes.value

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))

        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertNull("cleared, as the first key's save clears it", TranslationCache.shared[key])
        assertNull("any cooldown dropped", PtCloudService.unavailableUntil())
        assertEquals(before + 1, PtCloudService.changes.value)
    }

    /** Entitled before and after: the check moves no switch, so a route the
     *  user turned off stays off, and with no cooldown to drop nothing is
     *  reset or counted. */
    @Test fun `an Accepted check after an entitled record leaves the switches as the user set them`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        prefs.ptCloudOcrEnabled = false
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME
        val before = PtCloudService.changes.value

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))

        assertFalse("a deliberate off survives the check", prefs.ptCloudOcrEnabled)
        assertTrue(prefs.ptCloudTranslateEnabled)
        assertEquals("one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])
        assertEquals(before, PtCloudService.changes.value)
    }

    /** The device's clock ahead of the server's: the server accepts the key
     *  with a trial end that has already passed here. An Accepted is the
     *  server's word that the account is entitled, whatever the dates read
     *  on this clock, so the record it writes is active and both switches
     *  come on from off (the page's account line still words the trial as
     *  ended, which the next answer corrects). */
    @Test fun `an Accepted check whose dates already read ended on this clock is entitled, the server's word`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        val before = PtCloudService.changes.value

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted(SOURCE_TRIAL, ENDED_AT_MS))

        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertTrue(PtCloudService.config.entitled)
        assertEquals("the words", PtCloudAccount.Standing.TRIAL_ENDED, PtCloudService.config.standing())
        assertEquals(before + 1, PtCloudService.changes.value)
    }

    @Test fun `an Accepted check of a pasted key's account keeps no email`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, PtCloudAccount(active = true, source = "manual", checkedAtMs = 2L))

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted(null, null))

        val account = PtCloudService.account!!
        assertNull(account.email)
        assertNull("an answer with no source says no more of the account", account.source)
    }

    /** The page's check finding the account ended turns both switches off
     *  (no pill: the user is on the page that says why) and resets nothing:
     *  an older build's BILLING cooldown stays to run out. */
    @Test fun `an Inactive check rewrites the account and turns both switches off, resetting no cooldown`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        state.recordLadderFailure(CooldownLadder.RateLimit, "No membership", CooldownCause.BILLING)
        val before = PtCloudService.changes.value

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS))

        val account = PtCloudService.account!!
        assertEquals(
            PtCloudAccount(email = EMAIL, active = false, lastSource = "trial", endedAtMs = ENDED_AT_MS, checkedAtMs = account.checkedAtMs),
            account,
        )
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertEquals(before + 1, PtCloudService.changes.value)
        assertEquals(CooldownCause.BILLING, PtCloudService.unavailableCause())
    }

    /** The HTTP 401 on the stored key, the one answer that clears it: as a
     *  sign-out does, both switches off. */
    @Test fun `a Refused check clears the key and the account, both switches off`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Refused)

        assertEquals("", prefs.ptCloudClientKey)
        assertNull(prefs.ptCloudAccountJson)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
    }

    /** A stored key the keystore would not decrypt this once reads as blank
     *  ([Prefs.readSecret]); a check made with that blank was never a check
     *  of the stored key, so even its Refused clears nothing: the ciphertext,
     *  the record and the switches stay for the next read to decrypt. */
    @Test fun `a check of an unusable key changes nothing, a Refused included`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val blind = Prefs(ctx, BlindCodec())
        installWith(blind)
        assertEquals("the key reads blank under a codec that will not decrypt", "", blind.ptCloudClientKey)

        for (check in listOf(KeyCheck.Refused, KeyCheck.Accepted("kofi", null), KeyCheck.Inactive(null, null))) {
            PtCloudService.onStoredKeyChecked(blind.ptCloudClientKey, PtCloudService.accountGeneration, check)
        }

        installWith(prefs)
        assertEquals("the ciphertext is untouched", PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertEquals(TRIAL, PtCloudService.account)
        assertTrue("nothing flipped", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    @Test fun `a Closed or NotChecked check leaves the key, the account and the cooldown`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        state.recordLadderFailure(CooldownLadder.RateLimit, "No membership", CooldownCause.BILLING)
        for (check in listOf(KeyCheck.Closed("closed"), KeyCheck.NotChecked)) {
            PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, check)
            assertEquals("$check", PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
            assertEquals("$check", TRIAL, PtCloudService.account)
            assertEquals("$check", CooldownCause.BILLING, PtCloudService.unavailableCause())
        }
    }

    // ── The ending ──────────────────────────────────────────────────────

    /** A key, both switches on, and a trial record whose end has passed on
     *  this clock since the server last described it: the clock ends
     *  nothing ([PtCloudConfig.entitled] is the server's word), so the next
     *  read goes out, and the 403 it meets is the ending. */
    private fun lapsedTrial(prefs: Prefs) {
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        prefs.ptCloudAccountJson =
            PtJson.lenient.encodeToString(PtCloudAccount.serializer(), TRIAL.copy(entitledUntilMs = ENDED_AT_MS))
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertTrue("the clock ends nothing", PtCloudService.config.entitled)
        assertEquals(PtCloudAccount.Standing.TRIAL_ENDED, PtCloudService.config.standing())
    }

    /** The server's 403 for the lapsed record: both off, one change; the
     *  page's Inactive after it rewrites the record and counts again, the
     *  record having moved, both switches being off already. */
    @Test fun `the ending turns both switches off and counts a change, as does a second ending with both already off`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        lapsedTrial(prefs)
        val before = PtCloudService.changes.value

        PtCloudService.onEntitlementEnded(
            PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS),
        )

        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertFalse(PtCloudService.config.entitled)
        assertEquals(before + 1, PtCloudService.changes.value)

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS))
        assertEquals("the record moved: counted, both switches off or not", before + 2, PtCloudService.changes.value)
    }

    /** A frame went out, and the page's check answered Accepted (back from
     *  Join) before the frame's 403 came back: the 403 describes the record
     *  the frame was built against, which the check has since rewritten, so
     *  the newer word stands and nothing is lost (Codex adversarial review,
     *  2026-10-29). */
    @Test fun `a 403 built against an older record changes nothing, the Accepted that came meanwhile standing`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        val sentGeneration = PtCloudService.accountGeneration
        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        val before = PtCloudService.changes.value

        PtCloudService.onEntitlementEnded(PtCloudTestSupport.KEY, sentGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS))

        assertEquals("kofi", PtCloudService.account!!.source)
        assertTrue(PtCloudService.config.entitled)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertEquals(before, PtCloudService.changes.value)

        // Built against the record the check wrote, the next 403 is the
        // server's newer word, and ends it.
        PtCloudService.onEntitlementEnded(
            PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("kofi", ENDED_AT_MS),
        )
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        assertEquals(before + 1, PtCloudService.changes.value)
    }

    /** Every write of the record counts a generation, the stamp a route
     *  reads where its request is built: the key save with its record, the
     *  blank save that clears it, and each answer that rewrites it; an
     *  answer that writes nothing counts nothing. */
    @Test fun `every write of the account record counts a generation`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        val start = PtCloudService.accountGeneration

        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        assertEquals(start + 1, PtCloudService.accountGeneration)
        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))
        assertEquals(start + 2, PtCloudService.accountGeneration)
        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.NotChecked)
        assertEquals("no record written", start + 2, PtCloudService.accountGeneration)
        PtCloudService.onEntitlementEnded(PtCloudTestSupport.KEY, start + 2, KeyCheck.Inactive("kofi", ENDED_AT_MS))
        assertEquals(start + 3, PtCloudService.accountGeneration)
        PtCloudService.saveClientKey("")
        assertEquals("the clear is a write", start + 4, PtCloudService.accountGeneration)
    }

    /** Both switches turned off during the call's round trip: the 403 still
     *  writes the record, and counts the change, since an open cloud page
     *  whose check is done repaints only from the count; silent, it would
     *  leave the page's rows live for a tap (Codex review, 2026-10-29). */
    @Test fun `a 403 with both switches already off writes the record and counts a change`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val sent = PtCloudService.accountGeneration
        PtCloudService.setOcrEnabled(false)
        PtCloudService.setTranslateEnabled(false)
        val before = PtCloudService.changes.value

        PtCloudService.onEntitlementEnded(PtCloudTestSupport.KEY, sent, KeyCheck.Inactive("trial", ENDED_AT_MS))

        assertFalse(PtCloudService.config.entitled)
        assertEquals(before + 1, PtCloudService.changes.value)
    }

    /** "On, not entitled" is not a state: a face can be stale (a 403 ended
     *  the account on IO while the page's rows were live), so the one
     *  writer refuses the switch-on and counts the refusal, so the face
     *  repaints to the switch as stored; off is never refused. */
    @Test fun `a switch cannot be turned on while the account is not entitled, and the refusal counts a change`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, ENDED_TRIAL)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        val before = PtCloudService.changes.value

        PtCloudService.setOcrEnabled(true)
        assertFalse("refused", prefs.ptCloudOcrEnabled)
        assertEquals("counted, so the face repaints", before + 1, PtCloudService.changes.value)
        PtCloudService.setTranslateEnabled(true)
        assertFalse("refused", prefs.ptCloudTranslateEnabled)
        assertEquals(before + 2, PtCloudService.changes.value)

        PtCloudService.setOcrEnabled(false)
        PtCloudService.setTranslateEnabled(false)
        assertEquals("off is never refused, and is a gesture", before + 4, PtCloudService.changes.value)

        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))
        assertTrue("entitled again: both on", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        PtCloudService.setOcrEnabled(false)
        PtCloudService.setOcrEnabled(true)
        assertTrue("and on is a gesture again", prefs.ptCloudOcrEnabled)
    }

    /** The mirror of the 403's guard: a check evaluated before the ending,
     *  delivered after a route's 403 wrote the record inactive, is dropped
     *  and says so, and the page asks again; acted on, it would rewrite
     *  the newer record active and turn both switches back on (Codex
     *  review, 2026-10-29). */
    @Test fun `a check whose record changed under it is dropped and says so, the newer record standing`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val sent = PtCloudService.accountGeneration
        PtCloudService.onEntitlementEnded(PtCloudTestSupport.KEY, sent, KeyCheck.Inactive("trial", ENDED_AT_MS))
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        val before = PtCloudService.changes.value

        assertFalse(PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, sent, KeyCheck.Accepted("kofi", LEASE_END_MS)))

        assertFalse("the 403's record stands", PtCloudService.config.entitled)
        assertFalse(prefs.ptCloudOcrEnabled || prefs.ptCloudTranslateEnabled)
        assertEquals(before, PtCloudService.changes.value)

        // Asked again against the record the 403 wrote, the answer stands.
        assertTrue(PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS)))
        assertTrue(PtCloudService.config.entitled)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)

        // A key that changed under the check is dropped too; an unusable
        // key was never sent, so its answer stands as not checked.
        assertFalse(PtCloudService.onStoredKeyChecked("an-older-key", PtCloudService.accountGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS)))
        assertTrue(PtCloudService.onStoredKeyChecked("", PtCloudService.accountGeneration, KeyCheck.NotChecked))
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
    }

    /** The pill that told the user stays up, and the ending resets nothing a
     *  gesture resets: no forget, no cooldown reset, no cache clear. A
     *  gesture after it forgets, so the presenter would have seen one. */
    @Test fun `the ending forgets no pill and keeps the cooldown and the translation cache`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        val state = installWith(prefs)
        lapsedTrial(prefs)
        val hidden = mutableListOf<TranslationErrorKey>()
        val tracker = TranslationErrorTracker(now = SystemClock::elapsedRealtime, internet = { InternetState.VALIDATED })
        tracker.presenter = object : TranslationErrorPresenter {
            override fun show(error: TranslationError): Boolean = true
            override fun update(error: TranslationError) = Unit
            override fun hide(keys: Collection<TranslationErrorKey>) { hidden += keys }
        }
        TranslationErrors.install(tracker)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        PtCloudService.report(
            OnlineAttempt(
                PtCloudService.ID, PtCloudService.DISPLAY_NAME, BackendFailure(BackendFailureKind.TRIAL_ENDED, 403),
                SystemClock.elapsedRealtime(), reachedServer = true,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        state.recordLadderFailure(CooldownLadder.RateLimit, "Rate limited")
        val until = PtCloudService.unavailableUntil()
        val key = TranslationCache.Key("一", "ja", "en")
        TranslationCache.shared[key] = "one" to PtCloudService.DISPLAY_NAME

        PtCloudService.onEntitlementEnded(
            PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("trial", ENDED_AT_MS),
        )

        assertTrue("the pill stays up: $hidden", hidden.isEmpty())
        assertEquals("the cooldown stays", until, PtCloudService.unavailableUntil())
        assertEquals("the cache stays", "one" to PtCloudService.DISPLAY_NAME, TranslationCache.shared[key])

        // A switch-on is refused for an ended account and forgets nothing;
        // the Accepted check back from Join is the gesture that does.
        PtCloudService.setOcrEnabled(true)
        assertTrue("refused, nothing forgotten: $hidden", hidden.isEmpty())
        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", LEASE_END_MS))
        assertEquals(
            "a gesture forgets it",
            listOf(TranslationErrorKey(PtCloudService.ID, BackendFailureKind.TRIAL_ENDED.name)),
            hidden,
        )
    }

    /** A sign-out or a new sign-in during the round trip makes a route's
     *  403 an answer about a key that is gone: nothing changes. */
    @Test fun `a 403 for a key that is no longer the stored one changes nothing`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val before = PtCloudService.changes.value

        val generation = PtCloudService.accountGeneration
        PtCloudService.onEntitlementEnded("an-older-key", generation, KeyCheck.Inactive("trial", ENDED_AT_MS))
        PtCloudService.onEntitlementEnded("", generation, KeyCheck.Inactive("trial", ENDED_AT_MS))

        assertEquals(TRIAL, PtCloudService.account)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertEquals(before, PtCloudService.changes.value)
    }

    @Test fun `a 403 for the stored key writes the account as ended, keeping its email, and turns both switches off`() {
        val prefs = PtCloudTestSupport.prefs(ctx)
        installWith(prefs)
        PtCloudService.saveClientKey(PtCloudTestSupport.KEY, TRIAL)
        val before = PtCloudService.changes.value

        PtCloudService.onEntitlementEnded(
            PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Inactive("kofi", ENDED_AT_MS),
        )

        val account = PtCloudService.account!!
        assertEquals(
            PtCloudAccount(email = EMAIL, active = false, lastSource = "kofi", endedAtMs = ENDED_AT_MS, checkedAtMs = account.checkedAtMs),
            account,
        )
        assertEquals(PtCloudAccount.Standing.INACTIVE, PtCloudService.config.standing())
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertEquals(before + 1, PtCloudService.changes.value)
        assertNull("no cooldown recorded", PtCloudService.unavailableUntil())
    }

    /** The AndroidKeyStore refusing to encrypt, as [Prefs] meets it: nothing
     *  is written, and what is stored still decrypts. */
    /** Encrypts as the fake codec does and decrypts nothing: the keystore
     *  refusing a read, which [Prefs.readSecret] answers with a blank. */
    private class BlindCodec : SecretCodec {
        private val inner = PtCloudTestSupport.FakeCodec()
        override fun encrypt(context: String, plaintext: String): String? = inner.encrypt(context, plaintext)
        override fun decrypt(context: String, stored: String): String? = null
    }

    private class RefusingCodec : SecretCodec {
        private val inner = PtCloudTestSupport.FakeCodec()
        override fun encrypt(context: String, plaintext: String): String? = null
        override fun decrypt(context: String, stored: String): String? = inner.decrypt(context, stored)
    }

    private companion object {
        const val EMAIL = "player@example.com"
        /** 2100-01-01 00:00 UTC: a trial still on whenever the test runs,
         *  since the entitlement is read on the wall clock. */
        const val TRIAL_END_MS = 4_102_444_800_000L
        /** 2101-01-01 00:00 UTC: a membership still on, likewise. */
        const val LEASE_END_MS = 4_133_980_800_000L
        /** 2025-10-28 12:00 UTC: an end long gone by. */
        const val ENDED_AT_MS = 1_761_652_800_000L
        /** A sign-in's record: a trial, with its end. */
        val TRIAL = PtCloudAccount(
            email = EMAIL, active = true, source = SOURCE_TRIAL, entitledUntilMs = TRIAL_END_MS, checkedAtMs = 1L,
        )
        /** The same account once its trial ended. */
        val ENDED_TRIAL = PtCloudAccount(
            email = EMAIL, active = false, lastSource = SOURCE_TRIAL, endedAtMs = ENDED_AT_MS, checkedAtMs = 1L,
        )
    }
}
