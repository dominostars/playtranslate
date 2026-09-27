package com.playtranslate.translation

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

/**
 * What [TranslationBackendRegistry] reports to
 * [TranslationBackendRegistry.onlineAttemptListener]: one [OnlineAttempt]
 * per online service a pass actually called, with the service's own
 * verdict, whether its server answered, and when its request went out. The
 * translation-error pill decides everything from these, so a missing report
 * is a pill that never shows and a wrong one is a pill that blames the
 * wrong thing. Robolectric for [SystemClock], which the stamps read.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationBackendRegistryAttemptsTest {

    private val reports = mutableListOf<List<OnlineAttempt>>()

    /** The reports without their send times, which [sent] checks. */
    private val verdicts get() = reports.map { pass -> pass.map { it.copy(sentAtMs = 0) } }

    private fun attempt(id: String, name: String, failure: BackendFailure?, reachedServer: Boolean) =
        OnlineAttempt(id, name, failure, 0, reachedServer)

    @Before fun setUp() {
        TranslationBackendRegistry.onlineAttemptListener = { reports += it }
    }

    @After fun tearDown() {
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationBackendRegistry.close()
    }

    /** Online batching backend that answers every batch and every text. */
    private class FakeBatchingBackend(override val id: BackendId, override val priority: Int) :
        TranslationBackend, BatchTranslator {
        override val displayName = "batching-$id"
        override val requiresInternet = true
        override val isDegradedFallback = false
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String) = "t-$text"
        override suspend fun translateBatch(texts: List<String>, source: String, target: String) =
            texts.map { "t-$it" }
    }

    /** Online backend whose call is cancelled (the hold released mid-call). */
    private class FakeCancelledBackend(override val id: BackendId, override val priority: Int) :
        TranslationBackend {
        override val displayName = "cancelled-$id"
        override val requiresInternet = true
        override val isDegradedFallback = false
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String): String =
            throw CancellationException("hold released")
    }

    /** Online, non-batching: each text's call takes [callMs] on the
     *  device clock, then throws that text's exception from [failures]
     *  or translates it. */
    private class FakePerTextBackend(
        override val id: BackendId,
        override val priority: Int,
        private val failures: Map<String, Exception> = emptyMap(),
        private val callMs: Long = 0,
    ) : TranslationBackend {
        override val displayName = "per-text-$id"
        override val requiresInternet = true
        override val isDegradedFallback = false
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String): String {
            if (callMs > 0) ShadowSystemClock.advanceBy(Duration.ofMillis(callMs))
            failures[text]?.let { throw it }
            return "t-$text"
        }
    }

    /** Online batching backend whose batch ends in a
     *  [BatchParseException] (a reply it couldn't use, or with
     *  [serverAnswered] false a refusal before sending, DeepL's cap) and
     *  whose per-text calls then fail in transport with [perText]. */
    private class FakeParseThenTransportBackend(
        override val id: BackendId,
        override val priority: Int,
        private val serverAnswered: Boolean,
        private val perText: () -> Exception,
    ) : TranslationBackend, BatchTranslator {
        override val displayName = "parse-$id"
        override val requiresInternet = true
        override val isDegradedFallback = false
        override fun isUsable(source: String, target: String) = true
        override suspend fun translate(text: String, source: String, target: String): String =
            throw perText()
        override suspend fun translateBatch(texts: List<String>, source: String, target: String): List<String> =
            throw BatchParseException("unusable batch", serverAnswered = serverAnswered)
    }

    private val billing = BackendFailure(BackendFailureKind.BILLING, 400)

    @Test fun `a failed online service then an offline answer reports just the online failure`() = runBlocking {
        val claude = FakeThrowingBackend(
            id = "claude", priority = 10, displayName = "Claude",
            exception = StructuralFailureException("credit balance too low", billing),
        )
        TranslationBackendRegistry.init(listOf(claude, FakeDegradedBackend()))

        TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals(listOf(listOf(attempt("claude", "Claude", billing, reachedServer = true))), verdicts)
    }

    @Test fun `a masked failure is reported with the service that answered after it`() = runBlocking {
        // "Claude is out of credits, Lingva answered": the case no surface
        // showed before the pill.
        val claude = FakeThrowingBackend(
            id = "claude", priority = 10, displayName = "Claude",
            exception = StructuralFailureException("credit balance too low", billing),
        )
        val lingva = FakeOnlineBackend(id = "lingva", priority = 20, displayName = "Lingva")
        TranslationBackendRegistry.init(listOf(claude, lingva))

        TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals(
            listOf(listOf(
                attempt("claude", "Claude", billing, reachedServer = true),
                attempt("lingva", "Lingva", null, reachedServer = true),
            )),
            verdicts,
        )
    }

    @Test fun `cooldown-skipped and offline backends are never reported`() = runBlocking {
        val cooled = FakeCooldownableBackend(id = "cooled", priority = 10)
        cooled.cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        TranslationBackendRegistry.init(listOf(cooled, FakeDegradedBackend()))

        TranslationBackendRegistry.translate("hi", "ja", "en")

        assertEquals(0, cooled.translateCalls.get())
        assertTrue("a pass that called no online service reports nothing", reports.isEmpty())
    }

    @Test fun `a pass where everything fails still reports, then throws`() = runBlocking {
        val gemini = FakeThrowingBackend(
            id = "gemini", priority = 10, displayName = "Gemini",
            exception = UnknownHostException("generativelanguage.googleapis.com"),
        )
        val broken = object : TranslationBackend {
            override val id = "mlkit"
            override val displayName = "ML Kit"
            override val priority = 30
            override val requiresInternet = false
            override val isDegradedFallback = true
            override fun isUsable(source: String, target: String) = true
            override suspend fun translate(text: String, source: String, target: String): String =
                throw IllegalStateException("no model")
        }
        TranslationBackendRegistry.init(listOf(gemini, broken))

        try {
            TranslationBackendRegistry.translate("hi", "ja", "en")
            fail("expected the all-failed throw")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertEquals(
            listOf(listOf(attempt("gemini", "Gemini", BackendFailure(BackendFailureKind.UNREACHABLE), reachedServer = false))),
            verdicts,
        )
    }

    @Test fun `a cancelled pass reports what completed before the cancellation`() = runBlocking {
        val claude = FakeThrowingBackend(
            id = "claude", priority = 10, displayName = "Claude",
            exception = StructuralFailureException("credit balance too low", billing),
        )
        TranslationBackendRegistry.init(listOf(claude, FakeCancelledBackend("slow", 20)))

        try {
            TranslationBackendRegistry.translate("hi", "ja", "en")
            fail("expected the cancellation")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(listOf(listOf(attempt("claude", "Claude", billing, reachedServer = true))), verdicts)
    }

    @Test fun `a one-text batch reports once, through the single path`() = runBlocking {
        TranslationBackendRegistry.init(listOf(FakeOnlineBackend(id = "lingva", priority = 10, displayName = "Lingva")))

        TranslationBackendRegistry.translateBatch(listOf("only"), "ja", "en")

        assertEquals(listOf(listOf(attempt("lingva", "Lingva", null, reachedServer = true))), verdicts)
    }

    @Test fun `batch answered reports the service healthy`() = runBlocking {
        TranslationBackendRegistry.init(listOf(FakeBatchingBackend("gemini", 10)))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(listOf(listOf(attempt("gemini", "batching-gemini", null, reachedServer = true))), verdicts)
    }

    @Test fun `a batch that fails reports the failure and the next service's answer`() = runBlocking {
        val limited = FakeFailingBatchBackend(
            id = "deepl", priority = 10,
            batchException = DeepLRateLimitException(),
        )
        TranslationBackendRegistry.init(listOf(limited, FakeBatchingBackend("lingva", 20)))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(
            listOf(listOf(
                attempt("deepl", "fake-batch-deepl", BackendFailure(BackendFailureKind.RATE_LIMITED, 429), reachedServer = true),
                attempt("lingva", "batching-lingva", null, reachedServer = true),
            )),
            verdicts,
        )
    }

    @Test fun `a batch shape failure answered per-text is one healthy report, not a failure`() = runBlocking {
        val llm = FakeFailingBatchBackend(
            id = "llm", priority = 10,
            batchException = BatchParseException("envelope malformed"),
        )
        TranslationBackendRegistry.init(listOf(llm))

        TranslationBackendRegistry.translateBatch(listOf("a", "b", "c"), "ja", "en")

        assertEquals(listOf(listOf(attempt("llm", "fake-batch-llm", null, reachedServer = true))), verdicts)
    }

    @Test fun `per-text with some texts failing reports the service as failed`() = runBlocking {
        // Same health rule as the cooldown clear: healthy only when it
        // answered every text.
        val mixed = FakeMixedResultCooldownableBackend(id = "mixed", priority = 10, failingTexts = setOf("b"))
        TranslationBackendRegistry.init(listOf(mixed, FakeDegradedBackend()))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(1, reports.size)
        val attempt = reports.single().single()
        assertEquals("mixed", attempt.serviceId)
        // The fake throws a plain IOException, which reads as transport.
        assertEquals(BackendFailureKind.UNREACHABLE, attempt.failure?.kind)
        assertTrue("it translated \"a\": its server answered", attempt.reachedServer)
    }

    // ── what a per-text pass proves ────────────────────────────────────

    @Test fun `per-text names a failure the server answered over a sibling's timeout`() = runBlocking {
        // Codex native 2026-09-27: the first failure was reported, a
        // timeout, and the 429 that proved the server was reached was lost.
        val limited = DeepLRateLimitException()
        TranslationBackendRegistry.init(listOf(
            FakePerTextBackend("deepl", 10, failures = mapOf("a" to SocketTimeoutException(), "b" to limited)),
            FakeDegradedBackend(),
        ))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(
            listOf(listOf(attempt("deepl", "per-text-deepl", limited.failure, reachedServer = true))),
            verdicts,
        )
    }

    @Test fun `per-text where every call failed in transport never reached the server`() = runBlocking {
        TranslationBackendRegistry.init(listOf(
            FakePerTextBackend("gemini", 10, failures = mapOf("a" to IOException(), "b" to SocketTimeoutException())),
            FakeDegradedBackend(),
        ))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        val attempt = verdicts.single().single()
        assertEquals(BackendFailureKind.UNREACHABLE, attempt.failure?.kind)
        assertEquals(false, attempt.reachedServer)
    }

    @Test fun `a batch refused before sending is no proof the server was reached`() = runBlocking {
        // DeepL throws BatchParseException for its batch cap before sending
        // anything, so only the per-text calls after it can prove a reach.
        TranslationBackendRegistry.init(listOf(
            FakeParseThenTransportBackend("deepl", 10, serverAnswered = false) { UnknownHostException("api.example") },
            FakeDegradedBackend(),
        ))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(
            listOf(listOf(attempt("deepl", "parse-deepl", BackendFailure(BackendFailureKind.UNREACHABLE), reachedServer = false))),
            verdicts,
        )
    }

    @Test fun `a batch reply it couldn't use proves the server was reached, even if every retry timed out`() = runBlocking {
        // Codex native 2026-09-27: a malformed Gemini/OpenAI batch reply,
        // then per-text timeouts, read as no server reached; on a network
        // without validated internet that became a connection error.
        TranslationBackendRegistry.init(listOf(
            FakeParseThenTransportBackend("gemini", 10, serverAnswered = true) { SocketTimeoutException() },
            FakeDegradedBackend(),
        ))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        assertEquals(
            listOf(listOf(attempt("gemini", "parse-gemini", BackendFailure(BackendFailureKind.TIMEOUT), reachedServer = true))),
            verdicts,
        )
    }

    @Test fun `a per-text failure that completed before the pass was cancelled is still reported`() = runBlocking {
        // Codex native 2026-09-27: one text's 429 came back, the pass was
        // cancelled while another text was still out, and the report was
        // lost, so a cooldown could hide the error until it ended.
        val limited = DeepLRateLimitException()
        val bStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backend = object : TranslationBackend {
            override val id: BackendId = "deepl"
            override val displayName = "DeepL"
            override val priority = 10
            override val requiresInternet = true
            override val isDegradedFallback = false
            override fun isUsable(source: String, target: String) = true
            override suspend fun translate(text: String, source: String, target: String): String {
                if (text == "a") throw limited
                bStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        TranslationBackendRegistry.init(listOf(backend, FakeDegradedBackend()))

        val pass = launch { TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en") }
        bStarted.await()
        pass.cancel()
        pass.join()

        assertEquals(
            listOf(listOf(attempt("deepl", "DeepL", limited.failure, reachedServer = true))),
            verdicts,
        )
    }

    @Test fun `a cancelled per-text pass with only successes so far reports nothing`() = runBlocking {
        // The texts that never finished could have failed: no success is
        // claimed for the service.
        val bStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backend = object : TranslationBackend {
            override val id: BackendId = "gemini"
            override val displayName = "Gemini"
            override val priority = 10
            override val requiresInternet = true
            override val isDegradedFallback = false
            override fun isUsable(source: String, target: String) = true
            override suspend fun translate(text: String, source: String, target: String): String {
                if (text == "a") return "t-a"
                bStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        TranslationBackendRegistry.init(listOf(backend, FakeDegradedBackend()))

        val pass = launch { TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en") }
        bStarted.await()
        pass.cancel()
        pass.join()

        assertTrue(reports.isEmpty())
    }

    // ── when each request went out ─────────────────────────────────────

    @Test fun `each attempt is dated when its own request went out, not when the pass began`() = runBlocking {
        // The tracker orders reports by these dates; a pass-wide date would
        // make a service called after a slow one look older than it is.
        val before = SystemClock.elapsedRealtime()
        TranslationBackendRegistry.init(listOf(
            FakePerTextBackend("slow", 10, failures = mapOf("hi" to SocketTimeoutException()), callMs = 30_000),
            FakePerTextBackend("next", 20),
        ))

        TranslationBackendRegistry.translate("hi", "ja", "en")

        val (slow, next) = reports.single()
        assertEquals(before, slow.sentAtMs)
        assertEquals("sent after the slow call gave up", before + 30_000, next.sentAtMs)
    }

    @Test fun `a batch pass dates each service's request too`() = runBlocking {
        val before = SystemClock.elapsedRealtime()
        TranslationBackendRegistry.init(listOf(
            FakePerTextBackend("slow", 10, failures = mapOf("a" to SocketTimeoutException(), "b" to SocketTimeoutException()), callMs = 10_000),
            FakeBatchingBackend("next", 20),
        ))

        TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en")

        val (slow, next) = reports.single()
        assertEquals(before, slow.sentAtMs)
        assertEquals("sent after both slow calls gave up", before + 20_000, next.sentAtMs)
    }
}
