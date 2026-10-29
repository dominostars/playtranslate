package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.PtJson
import com.playtranslate.cloud.PtCloudTestSupport.CapturingExecutor
import com.playtranslate.cloud.PtCloudTestSupport.onExecutor
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
import com.playtranslate.translation.CooldownMessageClass
import com.playtranslate.translation.CooldownState
import com.playtranslate.translation.OnlineAttempt
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.nextMidnightPacific
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The failure table, row by row, on the frame route (the translate route
 * shares the table; its rows through [PtCloudClient.postTranslate] are in
 * [PtCloudBackendTest]). Before part 1 each row goes through
 * [PtCloudClient.postFrame] against a canned answer and is checked three
 * ways: the thrown [PtCloudReadException]'s kind and code, the cooldown it
 * leaves, and the [OnlineAttempt] it reports. After part 1 each row goes
 * through [PtCloudErrors.classifyTranslation] / [PtCloudErrors.classifyPart2]
 * and the cooldown the outcome records. A 403 `no_entitlement` records no
 * cooldown and carries the ended account, which the client hands to the
 * service for the key the frame carried. The warm call's every answer
 * records, reports and hands over nothing.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudFailureMappingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val clock = AtomicLong(1_750_000_000_000L)
    private val now: Long get() = clock.get()
    private lateinit var cooldown: CooldownState
    private val attempts = Collections.synchronizedList(mutableListOf<OnlineAttempt>())

    @Before fun setUp() {
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
        cooldown = CooldownState(null, "ptcloud", nowMs = { clock.get() })
        PtCloudService.installForTest(cooldown)
        TranslationBackendRegistry.onlineAttemptListener = { attempts += it }
    }

    @After fun tearDown() {
        PtCloudService.installForTest(null)
        TranslationBackendRegistry.onlineAttemptListener = null
        PtCloudTestSupport.sharedPrefs(ctx).edit().clear().commit()
    }

    // ── Before part 1 ──────────────────────────────────────────────────

    private fun post(client: OkHttpClient): PtCloudReadException {
        val c = PtCloudClient(PtCloudTestSupport.config(ctx), clientFactory = { client })
        try {
            runBlocking { c.postFrame(PtCloudTestSupport.frame(), PtCloudTestSupport.meta()) }
        } catch (e: PtCloudReadException) {
            return e
        }
        fail("expected PtCloudReadException")
        throw AssertionError()
    }

    private fun http(code: Int, body: String = "", retryAfter: String? = null) =
        post(PtCloudTestSupport.cannedClient(code, body, retryAfter))

    /** The thrown failure, the one attempt reported, and no cooldown. */
    private fun assertRow(
        e: PtCloudReadException,
        kind: BackendFailureKind,
        code: Int?,
        reachedServer: Boolean,
    ) {
        assertEquals(BackendFailure(kind, code), e.failure)
        val attempt = attempts.last()
        assertEquals(PtCloudService.ID, attempt.serviceId)
        assertEquals(PtCloudService.DISPLAY_NAME, attempt.serviceName)
        assertEquals(BackendFailure(kind, code), attempt.failure)
        assertEquals(reachedServer, attempt.reachedServer)
    }

    private fun assertNoCooldown() = assertNull(cooldown.unavailableUntil())

    private fun assertCooldown(untilMs: Long, cause: CooldownCause, description: String) {
        assertEquals(untilMs, cooldown.unavailableUntil())
        assertEquals(cause, cooldown.unavailableCause())
        assertEquals(description, cooldown.unavailableDescription())
    }

    @Test fun `401 is AUTH on the rate-limit ladder, as a bad key`() {
        assertRow(http(401, """{"error":"unauthorized"}"""), BackendFailureKind.AUTH, 401, reachedServer = true)
        assertCooldown(now + 60_000, CooldownCause.AUTH, "Bad key")
        assertEquals(CooldownMessageClass.ACCOUNT, CooldownCause.AUTH.messageClass)
    }

    /** The key is good but its free trial ran out: the trial's own
     *  wording, and no cooldown, since the service turns its switches off
     *  instead (2026-10-29); the account the body describes rides along for
     *  it. */
    @Test fun `a 403 whose last_source is trial is TRIAL_ENDED with no cooldown, carrying the ended account (last_source, ended_at)`() {
        val body = """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""
        assertRow(http(403, body), BackendFailureKind.TRIAL_ENDED, 403, reachedServer = true)
        assertNoCooldown()
        assertEquals(KeyCheck.Inactive("trial", ENDED_AT_MS), PtCloudErrors.classifyHttp(403, body, null).endedAccount)
    }

    /** Any other last entitlement, none ended, or a Worker that sent none:
     *  no active membership, never a trial that ended. */
    @Test fun `a 403 no_entitlement with another last_source is MEMBERSHIP_INACTIVE with no cooldown, carrying the ended account (last_source, ended_at)`() {
        val bodies = listOf(
            """{"error":"no_entitlement","last_source":"kofi","ended_at":"2026-11-03T12:00:00.000Z"}""" to
                KeyCheck.Inactive("kofi", ENDED_AT_MS),
            """{"error":"no_entitlement","last_source":"manual","ended_at":"2026-11-03T12:00:00.000Z"}""" to
                KeyCheck.Inactive("manual", ENDED_AT_MS),
            """{"error":"no_entitlement","last_source":null,"ended_at":null}""" to KeyCheck.Inactive(null, null),
            """{"error":"no_entitlement"}""" to KeyCheck.Inactive(null, null),
        )
        for ((body, ended) in bodies) {
            assertRow(http(403, body), BackendFailureKind.MEMBERSHIP_INACTIVE, 403, reachedServer = true)
            assertNoCooldown()
            assertEquals(body, ended, PtCloudErrors.classifyHttp(403, body, null).endedAccount)
        }
    }

    /** The route's 403 and the key check's are one reading of the body, so
     *  the two cannot say different things of one account. */
    @Test fun `a 403's ended account is the key check's Inactive for the same body, and no other answer carries one`() {
        val body = """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""
        assertEquals(PtCloudErrors.classifyKeyCheck(403) { body }, PtCloudErrors.classifyHttp(403, body, null).endedAccount)
        for ((code, other) in listOf(403 to """{"error":"forbidden"}""", 401 to "", 404 to "", 410 to """{"error":"service_closed"}""")) {
            assertNull("$code $other", PtCloudErrors.classifyHttp(code, other, null).endedAccount)
        }
    }

    /** The service's settings over the same store the client reads, both
     *  switches on, with the key the frame carries. */
    private fun serviceSwitchedOn(): Prefs {
        PtCloudService.installForTest(cooldown, PtCloudTestSupport.switchedOn(ctx))
        return PtCloudTestSupport.prefs(ctx)
    }

    /** A frame's 403 is the server's word that the account ended, the one
     *  way a route ends one: the record is written as ended beside the
     *  stored key and both switches go off; the frame's own report is the
     *  pill. */
    @Test fun `a frame's 403 no_entitlement through the client writes the ended record and turns both switches off`() {
        val prefs = serviceSwitchedOn()
        val body = """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""

        assertRow(http(403, body), BackendFailureKind.TRIAL_ENDED, 403, reachedServer = true)

        val account = PtCloudService.account!!
        assertEquals(
            PtCloudAccount(active = false, lastSource = "trial", endedAtMs = ENDED_AT_MS, checkedAtMs = account.checkedAtMs),
            account,
        )
        assertEquals(PtCloudTestSupport.KEY, prefs.ptCloudClientKey)
        assertFalse(prefs.ptCloudOcrEnabled)
        assertFalse(prefs.ptCloudTranslateEnabled)
        assertNoCooldown()
        assertEquals("one report, the frame's", 1, attempts.size)
    }

    /** A sign-out or a new sign-in while the frame was out makes its 403 an
     *  answer about a key that is gone: the frame still fails and reports,
     *  and the service changes nothing. */
    @Test fun `a frame's 403 for a key that is no longer the stored one changes nothing`() {
        val prefs = serviceSwitchedOn()
        val body = """{"error":"no_entitlement","last_source":"trial"}"""
        val swapping = PtCloudTestSupport.cannedClient(403, body).newBuilder()
            .apply {
                interceptors().add(
                    0,
                    Interceptor { chain ->
                        prefs.ptCloudClientKey = "another-key"
                        chain.proceed(chain.request())
                    },
                )
            }
            .build()

        assertRow(post(swapping), BackendFailureKind.TRIAL_ENDED, 403, reachedServer = true)

        assertEquals("another-key", prefs.ptCloudClientKey)
        assertNull("no record written", prefs.ptCloudAccountJson)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertNoCooldown()
    }

    /** The page's check answered Accepted (back from Join, say) while the
     *  frame was out: its 403 describes the record the frame was built
     *  against, which the check has since rewritten, so the frame still
     *  fails and reports, and the newer word stands (Codex adversarial
     *  review, 2026-10-29). The generation is read where the request is
     *  built, before any interceptor runs. */
    @Test fun `a frame's 403 that comes back after a newer check changes nothing, the Accepted standing`() {
        val prefs = serviceSwitchedOn()
        val body = """{"error":"no_entitlement","last_source":"trial","ended_at":"2026-11-03T12:00:00.000Z"}"""
        val racing = PtCloudTestSupport.cannedClient(403, body).newBuilder()
            .apply {
                interceptors().add(
                    0,
                    Interceptor { chain ->
                        PtCloudService.onStoredKeyChecked(PtCloudTestSupport.KEY, PtCloudService.accountGeneration, KeyCheck.Accepted("kofi", null))
                        chain.proceed(chain.request())
                    },
                )
            }
            .build()

        assertRow(post(racing), BackendFailureKind.TRIAL_ENDED, 403, reachedServer = true)

        val account = PtCloudService.account!!
        assertTrue("the check's record stands: $account", account.active)
        assertEquals("kofi", account.source)
        assertTrue(prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertNoCooldown()
    }

    @Test fun `any other 403 is a rejected request with its code, no cooldown`() {
        for (body in listOf("""{"error":"forbidden"}""", """{"error":null}""", "{}", "", "<html>403 Forbidden</html>")) {
            assertRow(http(403, body), BackendFailureKind.REJECTED, 403, reachedServer = true)
            assertNoCooldown()
        }
    }

    @Test fun `404 with an empty body is NOT_RUNNING on the rate-limit ladder, as not running`() {
        assertRow(http(404, ""), BackendFailureKind.NOT_RUNNING, 404, reachedServer = true)
        assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
        assertEquals(CooldownMessageClass.TRANSIENT, CooldownCause.NOT_RUNNING.messageClass)
        // A second answer while the cooldown runs is absorbed (the ladder's
        // rule for a batch's siblings); once it expires the next one climbs.
        assertRow(http(404, ""), BackendFailureKind.NOT_RUNNING, 404, reachedServer = true)
        assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
        clock.addAndGet(61_000)
        assertRow(http(404, ""), BackendFailureKind.NOT_RUNNING, 404, reachedServer = true)
        assertCooldown(now + 10 * 60_000, CooldownCause.NOT_RUNNING, "Not running")
    }

    @Test fun `404 with a JSON body is a rejected request, no cooldown`() {
        assertRow(http(404, """{"error":"not found"}"""), BackendFailureKind.REJECTED, 404, reachedServer = true)
        assertNoCooldown()
    }

    /** The one answer that says the service will not come back by itself:
     *  the account class, shown without a retry time; the operator's message
     *  is never recorded. */
    @Test fun `a 410 service_closed is CLOSED on the rate-limit ladder, cause CLOSED`() {
        val body = """{"error":"service_closed","message":"PlayTranslate Cloud has closed."}"""
        assertRow(http(410, body), BackendFailureKind.CLOSED, 410, reachedServer = true)
        assertCooldown(now + 60_000, CooldownCause.CLOSED, "Closed")
        assertEquals(CooldownMessageClass.ACCOUNT, CooldownCause.CLOSED.messageClass)
        // Once the step runs out, the next one climbs the ladder.
        clock.addAndGet(61_000)
        assertRow(http(410, """{"error":"service_closed"}"""), BackendFailureKind.CLOSED, 410, reachedServer = true)
        assertCooldown(now + 10 * 60_000, CooldownCause.CLOSED, "Closed")
    }

    /** A 410 is closed only with the server's word. */
    @Test fun `a 410 with another body stays REJECTED with its code, no cooldown`() {
        for (body in listOf("""{"error":"gone"}""", "{}", "", "<html>410 Gone</html>")) {
            assertRow(http(410, body), BackendFailureKind.REJECTED, 410, reachedServer = true)
            assertNoCooldown()
        }
    }

    @Test fun `413, 415 and 422 are rejected requests with their code, no cooldown`() {
        for (code in listOf(413, 415, 422)) {
            assertRow(http(code, """{"error":"x"}"""), BackendFailureKind.REJECTED, code, reachedServer = true)
            assertNoCooldown()
        }
    }

    @Test fun `411 (the edge wanted a Content-Length) is a rejected request with its code, no cooldown`() {
        assertRow(http(411, """{"error":"length required"}"""), BackendFailureKind.REJECTED, 411, reachedServer = true)
        assertNoCooldown()
    }

    @Test fun `429 is RATE_LIMITED until its Retry-After`() {
        // 60 is what the edge sends; 300 tells the header apart from the
        // ladder's first rung, which is also a minute.
        for ((header, ms) in listOf("60" to 60_000L, "300" to 300_000L)) {
            cooldown.resetCooldown()
            assertRow(http(429, """{"error":"rate limited"}""", retryAfter = header), BackendFailureKind.RATE_LIMITED, 429, true)
            assertCooldown(now + ms, CooldownCause.RATE_LIMITED, "Rate limited")
        }
    }

    @Test fun `429 without a Retry-After takes the rate-limit ladder`() {
        assertRow(http(429, "{}"), BackendFailureKind.RATE_LIMITED, 429, true)
        assertCooldown(now + 60_000, CooldownCause.RATE_LIMITED, "Rate limited")
    }

    @Test fun `500 is SERVER_ERROR on the rate-limit ladder`() {
        assertRow(http(500, """{"error":"internal"}"""), BackendFailureKind.SERVER_ERROR, 500, true)
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    /** The host being off: the edge's own body while the tunnel has no
     *  connector, or the tunnel's 502 passed through (ruled 2026-10-28). */
    @Test fun `a 502 without ocr_unavailable is NOT_RUNNING on the rate-limit ladder`() {
        val bodies = listOf(
            """{"error":"origin unreachable","route":"vpc"}""", "", "<html>502 Bad Gateway</html>", """{"error":"internal"}""",
        )
        for (body in bodies) {
            cooldown.resetCooldown()
            assertRow(http(502, body), BackendFailureKind.NOT_RUNNING, 502, true)
            assertCooldown(now + 60_000, CooldownCause.NOT_RUNNING, "Not running")
        }
    }

    @Test fun `a 502 ocr_unavailable is SERVER_ERROR on the rate-limit ladder`() {
        assertRow(http(502, """{"error":"ocr_unavailable","detail":"sidecar 422"}"""), BackendFailureKind.SERVER_ERROR, 502, true)
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    @Test fun `503 (the pod starting) is SERVER_ERROR with no cooldown`() {
        assertRow(http(503, """{"error":"starting"}"""), BackendFailureKind.SERVER_ERROR, 503, true)
        assertNoCooldown()
    }

    @Test fun `any other status is httpStatusFailure with no cooldown`() {
        assertRow(http(400, "{}"), BackendFailureKind.REJECTED, 400, true)
        assertNoCooldown()
        assertRow(http(504, ""), BackendFailureKind.SERVER_ERROR, 504, true)
        assertNoCooldown()
    }

    @Test fun `a connection failure is UNREACHABLE on the forgiving network path`() {
        assertRow(post(PtCloudTestSupport.failingClient(IOException("refused"))), BackendFailureKind.UNREACHABLE, null, false)
        assertNoCooldown()
        // The second within the pair window engages the network ladder.
        assertRow(post(PtCloudTestSupport.failingClient(IOException("refused"))), BackendFailureKind.UNREACHABLE, null, false)
        assertCooldown(now + 30_000, CooldownCause.CONNECTION_FAILED, "Connection failed")
    }

    @Test fun `a timeout before part 1 is TIMEOUT on the forgiving network path`() {
        assertRow(post(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))), BackendFailureKind.TIMEOUT, null, false)
        assertNoCooldown()
        assertRow(post(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))), BackendFailureKind.TIMEOUT, null, false)
        assertCooldown(now + 30_000, CooldownCause.CONNECTION_FAILED, "Timed out")
    }

    @Test fun `OkHttp's call timeout before part 1 is TIMEOUT, never read as the caller's cancellation`() {
        // OkHttp's call timeout cancels the call itself (RealCall.cancel), so
        // Call.isCanceled() is true after it although nobody cancelled the
        // capture. Read as a cancellation it would end the capture silently
        // instead of falling back to the device and showing the pill.
        val stalled = PtCloudTestSupport.cannedClient(200, "").newBuilder()
            .callTimeout(100, TimeUnit.MILLISECONDS)
            .apply {
                interceptors().add(
                    0,
                    Interceptor { chain ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!chain.call().isCanceled()) {
                            if (System.nanoTime() > deadline) throw IllegalStateException("the call timeout never fired")
                            Thread.sleep(5)
                        }
                        throw IOException("Canceled")
                    },
                )
            }
            .build()
        assertRow(post(stalled), BackendFailureKind.TIMEOUT, null, reachedServer = false)
        assertNoCooldown()
    }

    @Test fun `a part 1 that is not JSON, not part ocr, or not schema 1 is BAD_RESPONSE, no cooldown`() {
        val schema2 = PtCloudTestSupport.part1(PtCloudTestSupport.JA).replace("\"schema_version\":1", "\"schema_version\":2")
        val wrongPart = PtCloudTestSupport.part2()
        for (line in listOf("<html>502 Bad Gateway</html>", schema2, wrongPart, """{"part":"ocr"}""")) {
            assertRow(http(200, line + "\n"), BackendFailureKind.BAD_RESPONSE, null, reachedServer = true)
            assertNoCooldown()
        }
    }

    @Test fun `a 200 whose body ends before part 1 is a dropped connection`() {
        assertRow(http(200, ""), BackendFailureKind.UNREACHABLE, null, reachedServer = false)
    }

    // ── The warm call ──────────────────────────────────────────────────

    /** Sends one warm call answered by [client] and waits for its callback;
     *  returns what its callback thread threw. */
    private fun warm(client: OkHttpClient): List<Throwable> {
        val dispatcher = CapturingExecutor()
        PtCloudClient(PtCloudTestSupport.config(ctx), clientFactory = { client.onExecutor(dispatcher.executor) }).warm()
        dispatcher.await()
        return dispatcher.thrown.toList()
    }

    /** Nothing a warm hears turns the switches off or writes the record:
     *  the frame right behind it would then be refused silently, as the
     *  switch off, and the user would never see the pill (2026-10-29). */
    private fun assertNothingHandedOver(prefs: Prefs) {
        assertTrue("both switches still on", prefs.ptCloudOcrEnabled && prefs.ptCloudTranslateEnabled)
        assertNull("no record written", prefs.ptCloudAccountJson)
    }

    @Test fun `a warm answered 404, 401, 403, 500 or 429 records no cooldown, reports nothing, flips nothing, and throws nothing`() {
        val prefs = serviceSwitchedOn()
        // 500 and 429 would cool the service down were a frame answered so,
        // and a 403 no_entitlement would turn the switches off; they are
        // here so that recording anything from a warm shows up.
        for (client in listOf(
            PtCloudTestSupport.cannedClient(404, ""),
            PtCloudTestSupport.cannedClient(401, """{"error":"unauthorized"}"""),
            PtCloudTestSupport.cannedClient(403, """{"error":"no_entitlement","last_source":"trial"}"""),
            PtCloudTestSupport.cannedClient(500, """{"error":"internal"}"""),
            PtCloudTestSupport.cannedClient(429, """{"error":"rate limited"}""", retryAfter = "300"),
        )) {
            assertEquals(emptyList<Throwable>(), warm(client))
            assertNoCooldown()
            assertTrue("no attempt reported: $attempts", attempts.isEmpty())
            assertNothingHandedOver(prefs)
        }
    }

    @Test fun `a warm that fails on the transport records no cooldown, reports nothing, flips nothing, and throws nothing`() {
        val prefs = serviceSwitchedOn()
        // Twice: a second network failure in the pair window would engage the
        // network ladder were either recorded.
        repeat(2) {
            assertEquals(emptyList<Throwable>(), warm(PtCloudTestSupport.failingClient(IOException("refused"))))
            assertEquals(emptyList<Throwable>(), warm(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))))
        }
        assertNoCooldown()
        assertTrue("no attempt reported: $attempts", attempts.isEmpty())
        assertNothingHandedOver(prefs)
    }

    // ── After part 1 ───────────────────────────────────────────────────

    private fun part2(json: String): TranslationPart = PtJson.lenient.decodeFromString(json)

    private fun classify(json: String) = PtCloudErrors.classifyTranslation(part2(json), now)

    private fun failedPart2(error: String) =
        PtCloudTestSupport.part2("failed", "[]", extra = ""","translation_error":$error""")

    private fun assertFailed(outcome: TranslationOutcome, kind: BackendFailureKind, code: Int? = null) {
        assertTrue("$outcome", outcome is TranslationOutcome.Failed)
        assertEquals(BackendFailure(kind, code), outcome.failure)
    }

    private fun record(outcome: TranslationOutcome) = outcome.record(cooldown, attemptStartedAtMs = now)

    @Test fun `ok is the translations by group id, and a success`() {
        val outcome = classify(PtCloudTestSupport.part2("ok", """[{"id":0,"translation":"A"},{"id":2,"translation":"C"}]"""))
        assertEquals(TranslationOutcome.Translated(mapOf(0 to "A", 2 to "C")), outcome)
        assertNull(outcome.failure)
    }

    @Test fun `ok without translations is BAD_RESPONSE, no cooldown`() {
        val outcome = classify("""{"part":"translation","schema_version":1,"translation_status":"ok"}""")
        assertFailed(outcome, BackendFailureKind.BAD_RESPONSE)
        record(outcome)
        assertNoCooldown()
    }

    @Test fun `skipped is a success with nothing to translate`() {
        val outcome = classify(PtCloudTestSupport.part2("skipped", "[]"))
        assertEquals(TranslationOutcome.Skipped, outcome)
        assertNull(outcome.failure)
    }

    @Test fun `a success clears an earlier cooldown on the wall clock, never on the monotonic one`() {
        cooldown.recordLadderFailure(com.playtranslate.translation.CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val setAt = now
        clock.addAndGet(1_000)
        val ok = classify(PtCloudTestSupport.part2())
        // An elapsedRealtime-scale stamp predates the wall-clock setAt, so
        // the stale-success guard keeps the cooldown: the clocks must not mix.
        ok.record(cooldown, attemptStartedAtMs = 5_000L)
        assertTrue(cooldown.unavailableUntil() != null)
        // An attempt started (wall clock) after the cooldown was set clears it.
        ok.record(cooldown, attemptStartedAtMs = setAt + 500)
        assertNoCooldown()
        // Skipped clears the same way.
        cooldown.recordLadderFailure(com.playtranslate.translation.CooldownLadder.RateLimit, "Rate limited")
        TranslationOutcome.Skipped.record(cooldown, attemptStartedAtMs = now + 1)
        assertNoCooldown()
    }

    @Test fun `deferred is TIMEOUT with no cooldown`() {
        val outcome = classify(PtCloudTestSupport.part2("deferred", "[]"))
        assertFailed(outcome, BackendFailureKind.TIMEOUT)
        record(outcome)
        assertNoCooldown()
    }

    @Test fun `rate_limited with retry_after_s cools down until then`() {
        val outcome = classify(failedPart2("""{"class":"rate_limited","http_status":429,"retry_after_s":12}"""))
        assertFailed(outcome, BackendFailureKind.RATE_LIMITED, 429)
        record(outcome)
        assertCooldown(now + 12_000, CooldownCause.RATE_LIMITED, "Rate limited")
    }

    @Test fun `rate_limited without a usable retry_after_s takes the rate-limit ladder`() {
        for (err in listOf("""{"class":"rate_limited","http_status":429}""", """{"class":"rate_limited","retry_after_s":0}""")) {
            cooldown.resetCooldown()
            val outcome = classify(failedPart2(err))
            assertFailed(outcome, BackendFailureKind.RATE_LIMITED, (outcome.failure)!!.httpCode)
            record(outcome)
            assertCooldown(now + 60_000, CooldownCause.RATE_LIMITED, "Rate limited")
        }
    }

    @Test fun `quota with retry_after_s is DAILY_QUOTA until then`() {
        val outcome = classify(failedPart2("""{"class":"quota","http_status":429,"retry_after_s":3600}"""))
        assertFailed(outcome, BackendFailureKind.DAILY_QUOTA, 429)
        record(outcome)
        assertCooldown(now + 3_600_000, CooldownCause.DAILY_QUOTA, "Daily quota used")
    }

    @Test fun `quota without retry_after_s is DAILY_QUOTA until the next midnight Pacific`() {
        val outcome = classify(failedPart2("""{"class":"quota"}"""))
        assertFailed(outcome, BackendFailureKind.DAILY_QUOTA)
        record(outcome)
        assertCooldown(nextMidnightPacific(now), CooldownCause.DAILY_QUOTA, "Daily quota used")
    }

    @Test fun `server_error is SERVER_ERROR on the rate-limit ladder`() {
        val outcome = classify(failedPart2("""{"class":"server_error","http_status":503}"""))
        assertFailed(outcome, BackendFailureKind.SERVER_ERROR, 503)
        record(outcome)
        assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
    }

    @Test fun `bad_output is BAD_RESPONSE, no cooldown`() {
        val outcome = classify(failedPart2("""{"class":"bad_output"}"""))
        assertFailed(outcome, BackendFailureKind.BAD_RESPONSE)
        record(outcome)
        assertNoCooldown()
    }

    @Test fun `deadline is TIMEOUT with no cooldown, as deferred`() {
        val outcome = classify(failedPart2("""{"class":"deadline"}"""))
        assertFailed(outcome, BackendFailureKind.TIMEOUT)
        record(outcome)
        assertNoCooldown()
    }

    @Test fun `config is SERVER_ERROR, no cooldown, never the user's key`() {
        val outcome = classify(failedPart2("""{"class":"config","http_status":403}"""))
        assertFailed(outcome, BackendFailureKind.SERVER_ERROR, 403)
        record(outcome)
        assertNoCooldown()
    }

    @Test fun `an unknown class, a failure with no error, an unknown status, or a wrong part is BAD_RESPONSE`() {
        val lines = listOf(
            failedPart2("""{"class":"mystery"}"""),
            PtCloudTestSupport.part2("failed", "[]"),
            PtCloudTestSupport.part2("pending", "[]"),
            PtCloudTestSupport.part2().replace("\"part\":\"translation\"", "\"part\":\"ocr\""),
            PtCloudTestSupport.part2().replace("\"schema_version\":1", "\"schema_version\":2"),
        )
        for (line in lines) {
            val outcome = classify(line)
            assertFailed(outcome, BackendFailureKind.BAD_RESPONSE)
            record(outcome)
            assertNoCooldown()
        }
    }

    @Test fun `part 2 that never came is TIMEOUT, garbage is BAD_RESPONSE, neither cools down`() {
        val cases = listOf(
            Part2Result.Ended to BackendFailureKind.TIMEOUT,
            Part2Result.Failed(SocketTimeoutException("read")) to BackendFailureKind.TIMEOUT,
            Part2Result.Failed(IOException("unexpected end of stream")) to BackendFailureKind.TIMEOUT,
            Part2Result.Failed(SerializationException("garbage")) to BackendFailureKind.BAD_RESPONSE,
            Part2Result.Failed(IllegalStateException("?")) to BackendFailureKind.BAD_RESPONSE,
        )
        for ((result, kind) in cases) {
            val outcome = PtCloudErrors.classifyPart2(result, now)
            assertFailed(outcome, kind)
            record(outcome)
            assertNoCooldown()
        }
        assertEquals(
            TranslationOutcome.Skipped,
            PtCloudErrors.classifyPart2(Part2Result.Line(part2(PtCloudTestSupport.part2("skipped", "[]"))), now),
        )
    }

    private companion object {
        /** 2026-11-03T12:00:00.000Z, the 403 bodies' `ended_at`. */
        const val ENDED_AT_MS = 1_793_707_200_000L
    }
}
