package com.playtranslate.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [TranslationBackendRegistry.translateWith], the run of some texts through
 * the one service the user picked for them, and
 * [TranslationBackendRegistry.setUpFor], the list they pick it from. Pinned
 * here:
 *  - who is asked: the picked service and nobody else, whether or not it is
 *    switched on, provided it is set up for the pair; a service that is not,
 *    or is no longer registered, is sent nothing;
 *  - the cooldown: a service in one is asked all the same, its answer
 *    clears the cooldown as any answer does, and its failure leaves a
 *    running one as it was;
 *  - the request: one batch call for several texts where the service takes
 *    one, the per-text retry after a batch reply it could not use, one call
 *    a text otherwise;
 *  - how it ends: one result a text, in order, each naming the service; all
 *    or nothing, a text left untranslated failing the run with what the
 *    service threw; cancellation stops it;
 *  - an answer with nothing in it, for a text that has some: a failure of
 *    the run in each of its three calls (one text, a batch, a text of
 *    several), thrown before the answer is counted, so a running cooldown
 *    stays as it was; a text with nothing in it may come back so; and the
 *    waterfall's own passes take such an answer as they did;
 *  - what it leaves alone: the translation error pill's listener hears
 *    nothing of it (contrasted with a waterfall pass over the same service,
 *    which reports), and the waterfall itself still passes by a service
 *    that is off or cooling;
 *  - the list: every service set up for the pair in the waterfall's order,
 *    the switched-off and the cooling among them.
 *
 * Pure JVM over fakes; the registry's Log calls are no-ops through
 * `unitTests.isReturnDefaultValues`.
 */
class TranslationBackendRegistryChosenTest {

    @After fun tearDown() {
        TranslationBackendRegistry.onlineAttemptListener = null
        TranslationBackendRegistry.close()
    }

    private fun with(id: String, vararg texts: String) = runBlocking {
        TranslationBackendRegistry.translateWith(id, texts.toList(), "ja", "en")
    }

    /** Runs the pick and returns what it threw, failing if it translated. */
    private fun failure(id: String, vararg texts: String): ChosenServiceFailedException {
        val outcome = runCatching { with(id, *texts) }
        val thrown = outcome.exceptionOrNull()
            ?: throw AssertionError("expected the run to throw, it returned ${outcome.getOrNull()?.map { it.text }}")
        assertTrue("$thrown", thrown is ChosenServiceFailedException)
        return thrown as ChosenServiceFailedException
    }

    // ── who is asked ─────────────────────────────────────────────────────

    @Test fun `only the picked service is asked, whatever comes before it in the order`() {
        val first = FakeSwitchedBackend(id = "first", priority = 10)
        val picked = FakeSwitchedBackend(id = "picked", priority = 20)
        val last = FakeSwitchedBackend(id = "last", priority = 30, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(first, picked, last))

        val results = with("picked", "こんにちは")

        assertEquals(listOf("picked:こんにちは"), results.map { it.text })
        assertSame(picked, results.single().backend)
        assertEquals(1, picked.translateCalls.get())
        assertEquals("the service the waterfall would ask first is not asked", 0, first.translateCalls.get())
        assertEquals(0, last.translateCalls.get())
    }

    @Test fun `a service that is switched off is asked when it is set up`() {
        val off = FakeSwitchedBackend(id = "off", priority = 10, enabled = false)
        val on = FakeSwitchedBackend(id = "on", priority = 20)
        TranslationBackendRegistry.init(listOf(off, on))
        // The contrast: the waterfall passes a switched-off service by.
        assertSame(on, runBlocking { TranslationBackendRegistry.translate("a", "ja", "en") }.backend)
        assertEquals(0, off.translateCalls.get())

        val results = with("off", "a")

        assertSame(off, results.single().backend)
        assertEquals(1, off.translateCalls.get())
        assertEquals("the pick asked nobody else", 1, on.translateCalls.get())
    }

    @Test fun `a service that is not set up for the pair is sent nothing`() {
        val noKey = FakeSwitchedBackend(id = "no-key", priority = 10, setUp = false)
        val other = FakeSwitchedBackend(id = "other", priority = 20)
        TranslationBackendRegistry.init(listOf(noKey, other))

        val thrown = failure("no-key", "a", "b")

        assertSame(noKey, thrown.backend)
        assertNull("nothing was sent, so nothing was thrown back", thrown.cause)
        assertEquals(0, noKey.translateCalls.get())
        assertEquals("and nobody stands in for it", 0, other.translateCalls.get())
    }

    @Test fun `a service that is no longer registered fails the run with nobody asked`() {
        val other = FakeSwitchedBackend(id = "other", priority = 10)
        TranslationBackendRegistry.init(listOf(other))

        val thrown = failure("deleted", "a")

        assertNull(thrown.backend)
        assertNull(thrown.cause)
        assertEquals(0, other.translateCalls.get())
    }

    @Test fun `no texts is no call`() {
        val picked = FakeSwitchedBackend(id = "picked", priority = 10)
        TranslationBackendRegistry.init(listOf(picked))

        assertEquals(emptyList<WaterfallResult>(), with("picked"))
        assertEquals(0, picked.translateCalls.get())
    }

    // ── the cooldown ─────────────────────────────────────────────────────

    @Test fun `a service in a cooldown is asked, and its answer clears the cooldown`() {
        val cooled = FakeSwitchedBackend(id = "cooled", priority = 10).apply {
            cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        }
        val next = FakeSwitchedBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(cooled, next))
        // The contrast: the waterfall passes a cooling service by.
        assertSame(next, runBlocking { TranslationBackendRegistry.translate("a", "ja", "en") }.backend)
        assertEquals(0, cooled.translateCalls.get())

        val results = with("cooled", "a")

        assertSame(cooled, results.single().backend)
        assertEquals(1, cooled.recordSuccessCalls.get())
        assertNull("its answer cleared the cooldown, as any answer does", cooled.unavailableUntil())
    }

    @Test fun `a service in a cooldown is asked for several texts too`() {
        val cooled = FakeSwitchedBatchBackend(id = "cooled", priority = 10).apply {
            cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        }
        TranslationBackendRegistry.init(listOf(cooled))

        val results = with("cooled", "a", "b")

        assertEquals(listOf("cooled:a", "cooled:b"), results.map { it.text })
        assertNull(cooled.unavailableUntil())
    }

    @Test fun `a failure inside a cooldown leaves the cooldown as it was`() {
        val state = CooldownState(context = null, backendId = "cooled")
        state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
        val until = state.unavailableUntil()!!
        val cause = StructuralFailureException("error 503", BackendFailure(BackendFailureKind.SERVER_ERROR, 503))
        // Fails as a real backend does on a 5xx: the failure recorded, then
        // the throw.
        val cooled = FakeSwitchedBackend(
            id = "cooled", priority = 10, cooldownState = state,
            reply = {
                state.recordLadderFailure(CooldownLadder.RateLimit, "Server error", CooldownCause.SERVER_ERROR)
                throw cause
            },
        )
        TranslationBackendRegistry.init(listOf(cooled))

        val thrown = failure("cooled", "a")

        assertSame(cooled, thrown.backend)
        assertSame(cause, thrown.cause)
        assertEquals(1, cooled.translateCalls.get())
        assertEquals(0, cooled.recordSuccessCalls.get())
        assertEquals("the cooldown neither ended nor grew", until, state.unavailableUntil())
    }

    // ── the request ──────────────────────────────────────────────────────

    @Test fun `several texts go in one batch call where the service takes one`() {
        val picked = FakeSwitchedBatchBackend(id = "picked", priority = 10)
        TranslationBackendRegistry.init(listOf(picked))

        val results = with("picked", "a", "b", "c")

        assertEquals("one result a text, in order", listOf("picked:a", "picked:b", "picked:c"), results.map { it.text })
        assertTrue(results.all { it.backend === picked })
        assertEquals(1, picked.batchCalls.get())
        assertEquals("no per-text call beside the batch", 0, picked.translateCalls.get())
        assertEquals(1, picked.recordSuccessCalls.get())
    }

    @Test fun `a batch reply it could not use is followed by the per-text retry on the same service`() {
        val picked = FakeSwitchedBatchBackend(
            id = "picked", priority = 10,
            batchReply = { throw BatchParseException("short envelope") },
        )
        TranslationBackendRegistry.init(listOf(picked))

        val results = with("picked", "a", "b")

        assertEquals(listOf("picked:a", "picked:b"), results.map { it.text })
        assertEquals(1, picked.batchCalls.get())
        assertEquals(2, picked.translateCalls.get())
    }

    @Test fun `several texts go one call each where the service takes no batch`() {
        val picked = FakeSwitchedBackend(id = "picked", priority = 10, requiresInternet = false)
        TranslationBackendRegistry.init(listOf(picked))

        val results = with("picked", "a", "b")

        assertEquals(listOf("picked:a", "picked:b"), results.map { it.text })
        assertEquals(2, picked.translateCalls.get())
    }

    // ── how it ends ──────────────────────────────────────────────────────

    @Test fun `a failed text fails the run with what the service threw`() {
        val cause = IOException("connection reset")
        val picked = FakeSwitchedBackend(id = "picked", priority = 10, reply = { throw cause })
        TranslationBackendRegistry.init(listOf(picked))

        val thrown = failure("picked", "a")

        assertSame(picked, thrown.backend)
        assertSame(cause, thrown.cause)
    }

    @Test fun `a failed batch call fails the run with what the service threw and is not retried per text`() {
        val cause = StructuralFailureException("error 429", BackendFailure(BackendFailureKind.RATE_LIMITED, 429))
        val picked = FakeSwitchedBatchBackend(id = "picked", priority = 10, batchReply = { throw cause })
        TranslationBackendRegistry.init(listOf(picked))

        val thrown = failure("picked", "a", "b")

        assertSame(cause, thrown.cause)
        assertEquals(1, picked.batchCalls.get())
        assertEquals("a refused batch is not asked again text by text", 0, picked.translateCalls.get())
    }

    @Test fun `one text left untranslated fails the whole run`() {
        val cause = IOException("timeout on b")
        val picked = FakeSwitchedBackend(
            id = "picked", priority = 10,
            reply = { if (it == "b") throw cause else "picked:$it" },
        )
        val other = FakeSwitchedBackend(id = "other", priority = 20)
        TranslationBackendRegistry.init(listOf(picked, other))

        val thrown = failure("picked", "a", "b", "c")

        assertSame(picked, thrown.backend)
        assertSame(cause, thrown.cause)
        assertEquals("the text it could not do is not handed to another service", 0, other.translateCalls.get())
        assertEquals("a run that left a text is no success", 0, picked.recordSuccessCalls.get())
    }

    // ── an answer with nothing in it ─────────────────────────────────────

    private fun cooling(backend: FakeSwitchedBackend): Long {
        backend.cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        return backend.unavailableUntil()!!
    }

    /** [thrown] is the run's own failure for an answer with no text in it. */
    private fun assertEmptyAnswer(thrown: ChosenServiceFailedException) {
        val cause = thrown.cause
        assertTrue("$cause", cause is StructuralFailureException)
        assertEquals(BackendFailure.BAD_RESPONSE, (cause as StructuralFailureException).failure)
    }

    @Test fun `an answer with nothing in it fails the run and leaves a running cooldown as it was`() {
        val cooled = FakeSwitchedBackend(id = "cooled", priority = 10, reply = { " \n" })
        val until = cooling(cooled)
        TranslationBackendRegistry.init(listOf(cooled))

        val thrown = failure("cooled", "a")

        assertSame(cooled, thrown.backend)
        assertEmptyAnswer(thrown)
        assertEquals(1, cooled.translateCalls.get())
        assertEquals("an answer that translated nothing is not counted", 0, cooled.recordSuccessCalls.get())
        assertEquals("the cooldown is still running", until, cooled.unavailableUntil())
    }

    @Test fun `one blank among a batch's answers fails the run, uncounted and not asked again text by text`() {
        val cooled = FakeSwitchedBatchBackend(
            id = "cooled", priority = 10,
            batchReply = { texts -> texts.map { if (it == "b") "" else "cooled:$it" } },
        )
        val until = cooling(cooled)
        TranslationBackendRegistry.init(listOf(cooled))

        val thrown = failure("cooled", "a", "b", "c")

        assertEmptyAnswer(thrown)
        assertEquals(1, cooled.batchCalls.get())
        assertEquals("the batch was answered: asking again text by text is the same request", 0, cooled.translateCalls.get())
        assertEquals(0, cooled.recordSuccessCalls.get())
        assertEquals(until, cooled.unavailableUntil())
    }

    @Test fun `one blank among the answers to several texts fails the run, uncounted`() {
        val cooled = FakeSwitchedBackend(id = "cooled", priority = 10, reply = { if (it == "b") "" else "cooled:$it" })
        val until = cooling(cooled)
        TranslationBackendRegistry.init(listOf(cooled))

        val thrown = failure("cooled", "a", "b", "c")

        assertEmptyAnswer(thrown)
        assertEquals("every text was asked for", 3, cooled.translateCalls.get())
        assertEquals(0, cooled.recordSuccessCalls.get())
        assertEquals(until, cooled.unavailableUntil())
    }

    @Test fun `a text with nothing in it may be answered with nothing`() {
        val perText = FakeSwitchedBackend(id = "per-text", priority = 10, reply = { it.trim() })
        val batch = FakeSwitchedBatchBackend(id = "batch", priority = 20, batchReply = { texts -> texts.map { it.trim() } })
        TranslationBackendRegistry.init(listOf(perText, batch))

        assertEquals(listOf(""), with("per-text", " ").map { it.text })
        assertEquals(listOf("a", ""), with("per-text", "a", " ").map { it.text })
        assertEquals(listOf("a", ""), with("batch", "a", " ").map { it.text })

        assertEquals("each of those runs is an answer", 2, perText.recordSuccessCalls.get())
        assertEquals(1, batch.recordSuccessCalls.get())
    }

    @Test fun `the waterfall's own passes take an answer with nothing in it as they did`() {
        val blank = FakeSwitchedBatchBackend(
            id = "blank", priority = 10, reply = { "" }, batchReply = { texts -> texts.map { "" } },
        )
        val next = FakeSwitchedBackend(id = "next", priority = 20)
        TranslationBackendRegistry.init(listOf(blank, next))

        assertEquals("", runBlocking { TranslationBackendRegistry.translate("a", "ja", "en") }.text)
        assertEquals(
            listOf("", ""),
            runBlocking { TranslationBackendRegistry.translateBatch(listOf("a", "b"), "ja", "en") }.map { it.text },
        )

        assertEquals("nobody stood in for it", 0, next.translateCalls.get())
        assertEquals(2, blank.recordSuccessCalls.get())
    }

    @Test fun `cancellation stops the run`() {
        val picked = FakeSwitchedBackend(id = "picked", priority = 10, reply = { throw CancellationException("gone") })
        TranslationBackendRegistry.init(listOf(picked))

        val one = runCatching { with("picked", "a") }.exceptionOrNull()
        val several = runCatching { with("picked", "a", "b") }.exceptionOrNull()

        assertTrue("$one", one is CancellationException)
        assertTrue("$several", several is CancellationException)
    }

    // ── what it leaves alone ─────────────────────────────────────────────

    @Test fun `the error pill's listener hears nothing of a pick, an answer or a failure`() {
        val heard = mutableListOf<OnlineAttempt>()
        TranslationBackendRegistry.onlineAttemptListener = { heard += it }
        val answers = FakeSwitchedBatchBackend(id = "answers", priority = 10)
        val fails = FakeSwitchedBatchBackend(
            id = "fails", priority = 20,
            reply = { throw IOException("down") }, batchReply = { throw IOException("down") },
        )
        TranslationBackendRegistry.init(listOf(answers, fails))

        with("answers", "a")
        with("answers", "a", "b")
        failure("fails", "a")
        failure("fails", "a", "b")

        assertEquals(emptyList<OnlineAttempt>(), heard)

        // The contrast: a waterfall pass over the same services reports.
        runBlocking { TranslationBackendRegistry.translate("a", "ja", "en") }
        assertEquals(listOf("answers"), heard.map { it.serviceId })
    }

    // ── the list ─────────────────────────────────────────────────────────

    @Test fun `the list is every service set up for the pair, in the waterfall's order`() {
        val offline = FakeSwitchedBackend(id = "offline", priority = 30, requiresInternet = false, enabled = false)
        val noKey = FakeSwitchedBackend(id = "no-key", priority = 5, setUp = false)
        val off = FakeSwitchedBackend(id = "off", priority = 20, enabled = false)
        val cooling = FakeSwitchedBackend(id = "cooling", priority = 10).apply {
            cooldownState.recordParsedFailure(System.currentTimeMillis() + 60_000, "Rate limited")
        }
        val plain = FakeOnlineBackend(id = "plain", priority = 40)
        TranslationBackendRegistry.init(listOf(offline, noKey, off, cooling, plain))

        assertEquals(
            "off and cooling are listed, what is not set up is not; a backend with no switch is its usable self",
            listOf("cooling", "off", "offline", "plain"),
            TranslationBackendRegistry.setUpFor("ja", "en").map { it.id },
        )

        TranslationBackendRegistry.setOrder(listOf("off"))
        assertEquals(
            listOf("off", "cooling", "offline", "plain"),
            TranslationBackendRegistry.setUpFor("ja", "en").map { it.id },
        )
    }
}
