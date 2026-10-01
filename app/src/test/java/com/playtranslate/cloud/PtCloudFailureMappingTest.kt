package com.playtranslate.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.PtJson
import com.playtranslate.cloud.PtCloudTestSupport.CapturingExecutor
import com.playtranslate.cloud.PtCloudTestSupport.onExecutor
import com.playtranslate.translation.BackendFailure
import com.playtranslate.translation.BackendFailureKind
import com.playtranslate.translation.CooldownCause
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
 * The failure table, row by row. Before part 1 each row goes through
 * [PtCloudClient.postFrame] against a canned answer and is checked three
 * ways: the thrown [PtCloudReadException]'s kind and code, the cooldown it
 * leaves, and the [OnlineAttempt] it reports. After part 1 each row goes
 * through [PtCloudErrors.classifyTranslation] / [PtCloudErrors.classifyPart2]
 * and the cooldown the outcome records. The warm call's every answer records
 * and reports nothing.
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

    @Test fun `401 is AUTH, never a cooldown`() {
        assertRow(http(401, """{"error":"unauthorized"}"""), BackendFailureKind.AUTH, 401, reachedServer = true)
        assertNoCooldown()
    }

    @Test fun `404 with an empty body is NOT_RUNNING, no cooldown`() {
        assertRow(http(404, ""), BackendFailureKind.NOT_RUNNING, 404, reachedServer = true)
        assertNoCooldown()
        // Twice in a row still nothing: the stopped service is not a failure
        // to back off from.
        assertRow(http(404, ""), BackendFailureKind.NOT_RUNNING, 404, reachedServer = true)
        assertNoCooldown()
    }

    @Test fun `404 with a JSON body is a rejected request, no cooldown`() {
        assertRow(http(404, """{"error":"not found"}"""), BackendFailureKind.REJECTED, 404, reachedServer = true)
        assertNoCooldown()
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

    @Test fun `500 and 502 are SERVER_ERROR on the rate-limit ladder`() {
        for (code in listOf(500, 502)) {
            cooldown.resetCooldown()
            assertRow(http(code, """{"error":"internal"}"""), BackendFailureKind.SERVER_ERROR, code, true)
            assertCooldown(now + 60_000, CooldownCause.SERVER_ERROR, "Server error")
        }
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

    @Test fun `a warm answered 404, 401, 500 or 429 records no cooldown, reports nothing, and throws nothing`() {
        // 500 and 429 would cool the service down were a frame answered so;
        // they are here so that recording anything from a warm shows up.
        for (client in listOf(
            PtCloudTestSupport.cannedClient(404, ""),
            PtCloudTestSupport.cannedClient(401, """{"error":"unauthorized"}"""),
            PtCloudTestSupport.cannedClient(500, """{"error":"internal"}"""),
            PtCloudTestSupport.cannedClient(429, """{"error":"rate limited"}""", retryAfter = "300"),
        )) {
            assertEquals(emptyList<Throwable>(), warm(client))
            assertNoCooldown()
            assertTrue("no attempt reported: $attempts", attempts.isEmpty())
        }
    }

    @Test fun `a warm that fails on the transport records no cooldown, reports nothing, and throws nothing`() {
        // Twice: a second network failure in the pair window would engage the
        // network ladder were either recorded.
        repeat(2) {
            assertEquals(emptyList<Throwable>(), warm(PtCloudTestSupport.failingClient(IOException("refused"))))
            assertEquals(emptyList<Throwable>(), warm(PtCloudTestSupport.failingClient(SocketTimeoutException("read"))))
        }
        assertNoCooldown()
        assertTrue("no attempt reported: $attempts", attempts.isEmpty())
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
}
